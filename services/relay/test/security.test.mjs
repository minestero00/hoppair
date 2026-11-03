import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash, webcrypto } from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';
import vm from 'node:vm';
import { stripTypeScriptTypes } from 'node:module';

const room = '11111111-1111-1111-1111-111111111111';
const token = 'A'.repeat(43);
const hash = createHash('sha256').update(token).digest('hex');
const source = stripTypeScriptTypes(readFileSync(new URL('../src/index.ts', import.meta.url), 'utf8'))
  .replace('import { DurableObject } from "cloudflare:workers";', '')
  .replace('export default {', 'exports.default = {')
  .replace('export class DeviceRoom', 'exports.DeviceRoom = class DeviceRoom');
class Socket {
  static OPEN = 1;
  readyState = 1;
  sent = [];
  closes = [];
  serializeAttachment(value) { this.attachment = structuredClone(value); }
  deserializeAttachment() { return structuredClone(this.attachment ?? null); }
  send(value) { if (this.failSend) throw new Error('fixture send failure'); this.sent.push(value); }
  // Keep OPEN to model callbacks queued before the close handshake completes.
  close(code, reason) { this.closes.push({ code, reason }); }
}
class Pair { constructor() { this[0] = new Socket(); this[1] = new Socket(); } }
class WorkerResponse extends Response {
  constructor(body, options) {
    if (options?.status === 101) { super(null); this.socket = options.webSocket; this.upgraded = true; }
    else super(body, options);
  }
}
function fixture(database = new DatabaseSync(':memory:'), sockets = []) {
  const ctx = { storage: { sql: { exec(query, ...params) {
    const statement = database.prepare(query);
    const rows = statement.all(...params);
    return { toArray: () => rows };
  } } }, getWebSockets(role) { return sockets.filter(socket => !role || socket.role === role); }, acceptWebSocket(socket, roles) { socket.role = roles[0]; sockets.push(socket); } };
  const routes = [];
  const env = { ROOM_CREDENTIALS: JSON.stringify({ [room]: hash }), ROOMS: { getByName(id) { routes.push(id); return { fetch: () => new Response('routed') }; } } };
  const exports = {};
  const clock = { now: 100_000 };
  class Clock extends Date { static now() { return clock.now; } }
  vm.runInNewContext(source, { exports, DurableObject: class { constructor(ctx, env) { this.ctx = ctx; this.env = env; } }, Response: WorkerResponse, Request, URL, WebSocket: Socket, WebSocketPair: Pair, TextEncoder, crypto: webcrypto, Date: Clock });
  const instance = new exports.DeviceRoom(ctx, env);
  async function connect(role, id = room, credential = token) {
    const response = await instance.fetch(new Request(`https://relay.example/room/${id}?role=${role}`, { headers: { Upgrade: 'websocket', Authorization: `Bearer ${credential}` } }));
    return { response, socket: response.upgraded ? sockets.at(-1) : null };
  }
  return { instance, connect, sockets, env, worker: exports.default, routed: () => routes.length, routes, database, clock };
}
const payload = () => new Uint8Array(28).buffer;

test('unknown rooms and bad credentials fail before routing with identical responses', async () => {
  const f = fixture();
  const responses = [];
  for (const [id, credential] of [[room, 'B'.repeat(43)], ['22222222-2222-2222-2222-222222222222', token]]) {
    const response = await f.worker.fetch(new Request(`https://relay.example/room/${id}?role=host`, { headers: { Upgrade: 'websocket', Authorization: `Bearer ${credential}` } }), f.env);
    responses.push([response.status, await response.text(), [...response.headers]]);
  }
  assert.deepEqual(responses[0], responses[1]);
  assert.equal(f.routed(), 0);
});

test('authenticated binary exchange works; missing host and revoked registrations fail closed', async () => {
  const f = fixture();
  assert.equal((await f.connect('phone')).response.status, 503);
  const host = (await f.connect('host')).socket;
  const phone = (await f.connect('phone')).socket;
  f.instance.webSocketMessage(phone, payload());
  assert.equal(host.sent.length, 1);
  f.instance.webSocketMessage(host, payload());
  assert.equal(phone.sent.length, 1);
  f.env.ROOM_CREDENTIALS = '{}';
  f.instance.webSocketMessage(phone, payload());
  assert.equal(phone.closes.at(-1).code, 1008);
  assert.equal(host.sent.length, 1);
});

test('replaced sockets and their stale close/error/message callbacks cannot affect the new session', async () => {
  const f = fixture();
  const oldHost = (await f.connect('host')).socket;
  const phone = (await f.connect('phone')).socket;
  const newHost = (await f.connect('host')).socket;
  f.instance.webSocketMessage(phone, payload());
  assert.equal(newHost.sent.length, 1);
  f.instance.webSocketMessage(oldHost, payload());
  f.instance.webSocketClose(oldHost, 1000);
  f.instance.webSocketError(oldHost);
  assert.equal(phone.sent.length, 0);
  assert.equal(phone.deserializeAttachment().active, true);
  assert.equal(newHost.deserializeAttachment().active, true);
  f.instance.webSocketMessage(newHost, payload());
  assert.equal(phone.sent.length, 1);
});

test('phone replacement during a request retires both old sessions before accepting a new exchange', async () => {
  const f = fixture();
  const oldHost = (await f.connect('host')).socket;
  const oldPhone = (await f.connect('phone')).socket;
  f.instance.webSocketMessage(oldPhone, payload());
  assert.equal((await f.connect('phone')).response.status, 503);
  const newHost = (await f.connect('host')).socket;
  const newPhone = (await f.connect('phone')).socket;
  f.instance.webSocketMessage(newPhone, payload());
  f.instance.webSocketMessage(oldHost, payload());
  f.instance.webSocketMessage(oldPhone, payload());
  f.instance.webSocketClose(oldPhone, 1000);
  assert.equal(newPhone.sent.length, 0);
  assert.equal(newHost.deserializeAttachment().active, true);
  f.instance.webSocketMessage(newHost, payload());
  assert.equal(newPhone.sent.length, 1);
});

test('invalid sizes, text, and concurrent requests are rejected before forwarding', async () => {
  for (const invalid of ['text', new ArrayBuffer(27), new ArrayBuffer(12 * 1024 * 1024 + 1)]) {
    const f = fixture();
    const host = (await f.connect('host')).socket;
    const phone = (await f.connect('phone')).socket;
    f.instance.webSocketMessage(phone, invalid);
    assert.equal(host.sent.length, 0);
    assert.equal(phone.closes.at(-1).code, 1009);
  }
  const f = fixture();
  const host = (await f.connect('host')).socket;
  const phone = (await f.connect('phone')).socket;
  f.instance.webSocketMessage(phone, payload());
  f.instance.webSocketMessage(phone, payload());
  assert.equal(host.sent.length, 1);
  assert.equal(phone.closes.at(-1).code, 1008);
  assert.equal(host.deserializeAttachment().active, false);
});

test('message and byte limits survive reconnect and object reconstruction', async () => {
  const f = fixture();
  let host = (await f.connect('host')).socket;
  let phone = (await f.connect('phone')).socket;
  for (let i = 0; i < 240; i++) {
    f.instance.webSocketMessage(phone, payload()); f.instance.webSocketMessage(host, payload());
  }
  const resumed = fixture(f.database, f.sockets);
  phone = (await resumed.connect('phone')).socket;
  resumed.instance.webSocketMessage(phone, payload());
  assert.equal(phone.closes.at(-1).reason, 'Transfer limit reached');
  assert.equal(host.sent.length, 240);
  f.clock.now += 60_001; resumed.clock.now = f.clock.now;
  phone = (await resumed.connect('phone')).socket;
  for (let i = 0; i < 8; i++) {
    resumed.instance.webSocketMessage(phone, new ArrayBuffer(12 * 1024 * 1024));
    resumed.instance.webSocketMessage(host, payload());
  }
  phone = (await resumed.connect('phone')).socket;
  resumed.instance.webSocketMessage(phone, payload());
  assert.equal(phone.closes.at(-1).reason, 'Transfer limit reached');
});

test('authenticated reconnect flood is bounded without retiring the last accepted host', async () => {
  const f = fixture();
  let latest;
  for (let i = 0; i < 30; i++) latest = (await f.connect('host')).socket;
  assert.equal((await f.connect('host')).response.status, 429);
  assert.equal(latest.deserializeAttachment().active, true);
  const resumed = fixture(f.database, f.sockets);
  assert.equal((await resumed.connect('host')).response.status, 429);
  resumed.clock.now += 60_001;
  assert.equal((await resumed.connect('host')).response.upgraded, true);
});


test('each enrolled room requires its own credential before its Durable Object can be selected', async () => {
  const f = fixture();
  const otherRoom = '22222222-2222-2222-2222-222222222222';
  const otherToken = 'B'.repeat(43);
  f.env.ROOM_CREDENTIALS = JSON.stringify({ [room]: hash, [otherRoom]: createHash('sha256').update(otherToken).digest('hex') });
  const request = (id, credential) => new Request(`https://relay.example/room/${id}?role=host`, { headers: { Upgrade: 'websocket', Authorization: `Bearer ${credential}` } });
  assert.equal((await f.worker.fetch(request(otherRoom, token), f.env)).status, 401);
  assert.equal((await f.worker.fetch(request(room, otherToken), f.env)).status, 401);
  assert.deepEqual(f.routes, []);
  assert.equal((await f.worker.fetch(request(room, token), f.env)).status, 200);
  assert.equal((await f.worker.fetch(request(otherRoom, otherToken), f.env)).status, 200);
  assert.deepEqual(f.routes, [room, otherRoom]);
});


test('failed delivery retires both ends, including a failed host response after clearing pending markers', async () => {
  for (const failingRole of ['host', 'phone']) {
    const f = fixture();
    const host = (await f.connect('host')).socket;
    const phone = (await f.connect('phone')).socket;
    if (failingRole === 'host') host.failSend = true;
    f.instance.webSocketMessage(phone, payload());
    if (failingRole === 'phone') { phone.failSend = true; f.instance.webSocketMessage(host, payload()); }
    assert.equal(phone.deserializeAttachment().active, false);
    assert.equal(host.deserializeAttachment().active, false);
    assert.ok(phone.closes.length > 0);
    assert.ok(host.closes.length > 0);
  }
});
