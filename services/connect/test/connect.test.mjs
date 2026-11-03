import test from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/index.js';
const env = { SITE_URL: 'https://minestero00.github.io/hoppair/', ANDROID_PACKAGE: 'dev.macdroid.app', ANDROID_CERT_SHA256: 'PUBLIC-TEST-CERTIFICATE' };
test('association is public JSON and connect redirects only to the fixed site', async () => {
  const association = worker.fetch(new Request('https://connect.example/.well-known/assetlinks.json'), env);
  assert.equal(association.status, 200);
  assert.match(association.headers.get('content-type'), /application\/json/);
  assert.equal((await association.json())[0].target.package_name, 'dev.macdroid.app');
  const response = worker.fetch(new Request('https://connect.example/connect/?next=https://other.example'), env);
  assert.equal(response.status, 302);
  assert.equal(response.headers.get('location'), 'https://minestero00.github.io/hoppair/connect/');
  assert.equal(worker.fetch(new Request('https://connect.example/private'), env).status, 404);
  assert.equal(worker.fetch(new Request('https://connect.example/connect/', { method: 'POST', body: 'not accepted' }), env).status, 405);
});
