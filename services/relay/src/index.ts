import { DurableObject } from "cloudflare:workers";

const ROOM = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const MAX_MESSAGE = 12 * 1024 * 1024;
type SocketState = { inFlight: boolean; room: string; credentialHash: string; role: "host" | "phone"; start: number; count: number; bytes: number };

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/health") return Response.json({ ok: true, service: "hoppair-relay" });
    const room = url.pathname.match(/^\/room\/([^/]+)$/)?.[1];
    if (!room || !ROOM.test(room)) return new Response("Not found", { status: 404 });
    // Private alpha: reject unknown rooms before creating a Durable Object.
    const credentials = configuredRooms(env);
    if (!Object.hasOwn(credentials, room)) return new Response("Room is not enrolled", { status: 403 });
    if (request.method !== "GET" || request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("WebSocket required", { status: 426 });
    }
    return env.ROOMS.getByName(room).fetch(request);
  },
} satisfies ExportedHandler<Env>;

export class DeviceRoom extends DurableObject<Env> {
  async fetch(request: Request): Promise<Response> {
    const role = new URL(request.url).searchParams.get("role");
    if (role !== "host" && role !== "phone") return new Response("Invalid role", { status: 400 });
    const token = request.headers.get("Authorization")?.match(/^Bearer ([A-Za-z0-9_-]{43})$/)?.[1];
    if (!token) return new Response("Unauthorized", { status: 401 });
    const hash = Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token))))
      .map((byte) => byte.toString(16).padStart(2, "0")).join("");
    const room = new URL(request.url).pathname.split("/").pop()!;
    const enrolled = configuredRooms(this.env)[room];
    if (!enrolled || !same(enrolled, hash)) return new Response("Unauthorized", { status: 401 });
    if (role === "phone" && !this.ctx.getWebSockets("host").some((socket) => socket.readyState === WebSocket.OPEN)) {
      return new Response("Mac is offline", { status: 503 });
    }
    for (const old of this.ctx.getWebSockets(role)) old.close(1000, "Reconnected");
    const pair = new WebSocketPair();
    this.ctx.acceptWebSocket(pair[1], [role]);
    pair[1].serializeAttachment({ inFlight: false, room, credentialHash: hash, role, start: Date.now(), count: 0, bytes: 0 } satisfies SocketState);
    return new Response(null, { status: 101, webSocket: pair[0] });
  }
  webSocketMessage(socket: WebSocket, message: ArrayBuffer | string): void {
    // Opaque AES-GCM payloads only. Never parse, log, or store shelf contents here.
    if (typeof message === "string" || message.byteLength < 28 || message.byteLength > MAX_MESSAGE) {
      socket.close(1009, "Invalid payload size"); return;
    }
    const state = socket.deserializeAttachment() as SocketState;
    if (configuredRooms(this.env)[state.room] !== state.credentialHash) { socket.close(1008, "Device registration revoked"); return; }
    if (Date.now() - state.start > 60_000) { state.start = Date.now(); state.count = 0; state.bytes = 0; }
    state.count++; state.bytes += message.byteLength;
    if (state.count > 240 || state.bytes > 96 * 1024 * 1024) { socket.close(1008, "Transfer limit reached"); return; }
    socket.serializeAttachment(state);
    const peers = this.ctx.getWebSockets(state.role === "host" ? "phone" : "host").filter((peer) => peer.readyState === WebSocket.OPEN);
    if (!peers.length) {
      // A phone may leave while the host is computing its response. Keep the host available.
      if (state.role === "phone") socket.close(1013, "Mac is offline");
      return;
    }
    if (state.role === "phone") {
      if (state.inFlight) { socket.close(1008, "One request at a time"); return; }
      state.inFlight = true; socket.serializeAttachment(state);
    } else {
      const phone = peers[0].deserializeAttachment() as SocketState;
      if (!phone.inFlight) return;
      phone.inFlight = false; peers[0].serializeAttachment(phone);
    }
    try { for (const peer of peers) peer.send(message); }
    catch { socket.close(1011, "Other device disconnected"); }
  }

  webSocketClose(socket: WebSocket, code: number): void { socket.close(code >= 1000 && code < 5000 && ![1004, 1005, 1006, 1015].includes(code) ? code : 1001, "Disconnected"); }
  webSocketError(socket: WebSocket): void { socket.close(1011, "Connection failed"); }
}
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
    const rooms: Record<string, string> = {};
    for (const [id, hash] of Object.entries(result)) {
      if (ROOM.test(id) && typeof hash === "string" && /^[a-f0-9]{64}$/.test(hash)) rooms[id] = hash;
    }
    return rooms;
  } catch { return {}; }
}
