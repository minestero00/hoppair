import { test } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import WebSocket from 'ws';
const base = process.env.RELAY_TEST_URL ?? 'ws://127.0.0.1:8787';
const room = '11111111-1111-1111-1111-111111111111';
const auth = 'A'.repeat(43);
function connect(role, token = auth, id = room) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(`${base}/room/${id}?role=${role}`, { headers: { Authorization: `Bearer ${token}` } });
    socket.once('open', () => resolve(socket));
    socket.once('error', reject);
    socket.once('unexpected-response', (_, response) => { response.resume(); socket.terminate(); reject(new Error(`HTTP ${response.statusCode}`)); });
  });
}
test('only enrolled authenticated devices exchange opaque binary data', async (t) => {
  await assert.rejects(connect('host', auth, '22222222-2222-2222-2222-222222222222'), /403/);
  await assert.rejects(connect('phone'), /503/);
  const host = await connect('host'); t.after(() => host.terminate());
  await assert.rejects(connect('phone', 'B'.repeat(43)), /401/);
  const phone = await connect('phone'); t.after(() => phone.terminate());
  const payload = Buffer.alloc(65537, 37);
  const incoming = once(host, 'message'); phone.send(payload);
  assert.deepEqual((await incoming)[0], payload);
  const returned = once(phone, 'message'); host.send(payload);
  assert.deepEqual((await returned)[0], payload);
  const closed = once(phone, 'close'); phone.send('not encrypted');
  assert.equal((await closed)[0], 1009);
});
