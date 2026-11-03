import { DurableObject } from "cloudflare:workers";

const ROOM = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const MAX_MESSAGE = 12 * 1024 * 1024;
type Role = "host" | "phone";
type SocketState = { active: boolean; session: string; pendingPeer: string | null; room: string; credentialHash: string; role: Role };
type Budget = { start: number; count: number; bytes: number };

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/health") return Response.json({ ok: true, service: "hoppair-relay" });
    const room = url.pathname.match(/^\/room\/([^/]+)$/)?.[1];
    if (!room || !ROOM.test(room)) return new Response("Not found", { status: 404 });
    // Authenticate before routing: unknown rooms and bad credentials have the same response.
    if (!await authenticate(request, env, room)) return unauthorized();
    if (request.method !== "GET" || request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("WebSocket required", { status: 426 });
    }
    return env.ROOMS.getByName(room).fetch(request);
  },
} satisfies ExportedHandler<Env>;

export class DeviceRoom extends DurableObject<Env> {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    // Only bounded counters persist; no payloads, pairing keys, or request contents.
    this.ctx.storage.sql.exec("CREATE TABLE IF NOT EXISTS budgets (id TEXT PRIMARY KEY, start INTEGER NOT NULL, count INTEGER NOT NULL, bytes INTEGER NOT NULL)");
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const room = url.pathname.match(/^\/room\/([^/]+)$/)?.[1];
    const hash = room && ROOM.test(room) ? await authenticate(request, this.env, room) : null;
    if (!hash) return unauthorized();
    if (request.method !== "GET" || request.headers.get("Upgrade")?.toLowerCase() !== "websocket") return new Response("WebSocket required", { status: 426 });
    const role = url.searchParams.get("role");
    if (role !== "host" && role !== "phone") return new Response("Invalid role", { status: 400 });
    if (!this.consume(`connect:${role}`, 0, 30, 0)) return new Response("Reconnect limit reached", { status: 429 });
    for (const old of this.ctx.getWebSockets(role)) this.disconnect(old, 1000, "Reconnected");
    if (role === "phone" && !this.peers("host").length) return new Response("Mac is offline", { status: 503 });
    const pair = new WebSocketPair();
    this.ctx.acceptWebSocket(pair[1], [role]);
    pair[1].serializeAttachment({ active: true, session: crypto.randomUUID(), pendingPeer: null, room: room!, credentialHash: hash, role } satisfies SocketState);
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  webSocketMessage(socket: WebSocket, message: ArrayBuffer | string): void {
    const state = socket.deserializeAttachment() as SocketState | null;
    // Close handshakes can leave queued events behind. Never route a retired socket.
    if (!state?.active || !state.session || socket.readyState !== WebSocket.OPEN) { this.disconnect(socket, 1008, "Session ended"); return; }
    if (configuredRooms(this.env)[state.room] !== state.credentialHash) { this.disconnect(socket, 1008, "Device registration revoked"); return; }
    // Opaque AES-GCM payloads only. Never parse, log, or store shelf contents here.
    if (typeof message === "string" || message.byteLength < 28 || message.byteLength > MAX_MESSAGE) {
      this.disconnect(socket, 1009, "Invalid payload size"); return;
    }
    if (!this.consume(`message:${state.role}`, message.byteLength, 240, 96 * 1024 * 1024)) {
      this.disconnect(socket, 1008, "Transfer limit reached"); return;
    }
    const peer = this.peers(state.role === "host" ? "phone" : "host")[0];
    if (!peer) {
      if (state.role === "phone") this.disconnect(socket, 1013, "Mac is offline");
      return;
    }
    const other = peer.deserializeAttachment() as SocketState;
    if (state.role === "phone") {
      if (state.pendingPeer || other.pendingPeer) { this.disconnect(socket, 1008, "One request at a time"); return; }
      state.pendingPeer = other.session;
      other.pendingPeer = state.session;
    } else {
      if (state.pendingPeer !== other.session || other.pendingPeer !== state.session) return;
      state.pendingPeer = null;
      other.pendingPeer = null;
    }
    socket.serializeAttachment(state);
    peer.serializeAttachment(other);
    try { peer.send(message); }
    catch {
      this.disconnect(peer, 1011, "Delivery failed");
      this.disconnect(socket, 1011, "Other device disconnected");
    }
  }

  webSocketClose(socket: WebSocket, code: number): void {
    this.disconnect(socket, code >= 1000 && code < 5000 && ![1004, 1005, 1006, 1015].includes(code) ? code : 1001, "Disconnected");
  }
  webSocketError(socket: WebSocket): void { this.disconnect(socket, 1011, "Connection failed"); }

  private peers(role: Role): WebSocket[] {
    return this.ctx.getWebSockets(role).filter((socket) => {
      const state = socket.deserializeAttachment() as SocketState | null;
      return socket.readyState === WebSocket.OPEN && state?.active && state.session && configuredRooms(this.env)[state.room] === state.credentialHash;
    });
  }

  private disconnect(socket: WebSocket, code: number, reason: string): void {
    const state = socket.deserializeAttachment() as SocketState | null;
    if (state?.active) {
      state.active = false;
      socket.serializeAttachment(state);
      // A response is opaque: renew both ends when an unfinished exchange loses a peer.
      // This prevents a late response from being delivered to a replacement phone.
      if (state.pendingPeer) {
        for (const peer of this.peers(state.role === "host" ? "phone" : "host")) {
          const other = peer.deserializeAttachment() as SocketState;
          if (other.session === state.pendingPeer && other.pendingPeer === state.session) {
            other.active = false;
            peer.serializeAttachment(other);
            peer.close(1012, "Peer session ended");
          }
        }
      }
    }
    socket.close(code, reason);
  }

  private consume(id: string, bytes: number, maxCount: number, maxBytes: number): boolean {
    const now = Date.now();
    const rows = this.ctx.storage.sql.exec<Budget>("SELECT start, count, bytes FROM budgets WHERE id = ?", id).toArray();
    const previous = rows[0];
    const budget = previous && now - previous.start < 60_000 ? previous : { start: now, count: 0, bytes: 0 };
    if (budget.count >= maxCount || budget.bytes + bytes > maxBytes) return false;
    this.ctx.storage.sql.exec("INSERT OR REPLACE INTO budgets (id, start, count, bytes) VALUES (?, ?, ?, ?)", id, budget.start, budget.count + 1, budget.bytes + bytes);
    return true;
  }
}

async function authenticate(request: Request, env: Env, room: string): Promise<string | null> {
  const token = request.headers.get("Authorization")?.match(/^Bearer ([A-Za-z0-9_-]{43})$/)?.[1];
  if (!token) return null;
  const hash = Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token))))
    .map((byte) => byte.toString(16).padStart(2, "0")).join("");
  const enrolled = configuredRooms(env)[room];
  const verified = same(enrolled ?? "0".repeat(64), hash);
  return enrolled && verified ? hash : null;
}

function unauthorized(): Response { return new Response("Unauthorized", { status: 401, headers: { "Cache-Control": "no-store" } }); }
function same(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let different = 0;
  for (let i = 0; i < a.length; i++) different |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return different === 0;
}

function configuredRooms(env: Env): Record<string, string> {
  try {
    const result = JSON.parse(env.ROOM_CREDENTIALS);
    if (!result || typeof result !== "object" || Array.isArray(result)) return {};
    const rooms: Record<string, string> = Object.create(null);
    for (const [id, hash] of Object.entries(result)) {
      if (ROOM.test(id) && typeof hash === "string" && /^[a-f0-9]{64}$/.test(hash)) rooms[id] = hash;
    }
    return rooms;
  } catch { return {}; }
}
