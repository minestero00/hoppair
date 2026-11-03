import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../../../site/connect/connect.js', import.meta.url), 'utf8');
const key = Buffer.alloc(32, 7).toString('base64url');
const native = `hoppair://10.0.2.2:49273?key=${key}`;
const encode = value => Buffer.from(value).toString('base64url');
const releaseURL = 'https://github.com/minestero00/hoppair/releases/download/v1.0/Hoppair.apk';
async function page(fragment, { android = true, search = '', release = releaseURL } = {}) {
  const elements = new Map();
  const document = { querySelector(selector) {
    if (!elements.has(selector)) elements.set(selector, { hidden: false, href: '', textContent: '', classList: { add() {} } });
    return elements.get(selector);
  } };
  const location = new URL(`https://minestero00.github.io/hoppair/connect/${search}#${fragment}`);
  const requests = [];
  vm.runInNewContext(source, { document, location, navigator: { userAgent: android ? 'Android Chrome' : 'Macintosh Safari' }, URL, URLSearchParams, Uint8Array, TextDecoder, atob, encodeURIComponent, fetch: async url => {
    requests.push(url);
    return { ok: true, json: async () => ({ android: { url: release } }) };
  } });
  await new Promise(resolve => setImmediate(resolve));
  return { open: document.querySelector('[data-open-app]'), install: document.querySelector('[data-install]'), status: document.querySelector('[data-status]'), requests, location };
}

test('Android handoff fixes the target package and keeps credentials in the fallback fragment', async () => {
  const payload = encode(native);
  const result = await page(`pair=${payload}`);
  assert.equal(result.open.hidden, false);
  assert.match(result.open.href, /^intent:\/\/10\.0\.2\.2:49273\?key=/);
  assert.match(result.open.href, /#Intent;scheme=hoppair;package=dev\.macdroid\.app;/);
  const fallback = new URL(decodeURIComponent(result.open.href.split('S.browser_fallback_url=')[1].split(';end')[0]));
  assert.equal(fallback.origin, result.location.origin);
  assert.equal(fallback.pathname, result.location.pathname);
  assert.equal(fallback.search, '?install=1');
  assert.equal(fallback.hash, '#pair=' + payload);
  assert.deepEqual(result.requests, ['../release.json']);
  assert.equal(result.install.href, releaseURL);
});

test('relay values cannot inject Android Intent directives', async () => {
  const relay = 'wss://relay.example/';
  const result = await page('pair=' + encode(native + '&relay=' + encodeURIComponent(relay)));
  assert.equal(result.open.hidden, false);
  assert.match(result.open.href, /&relay=wss%3A%2F%2Frelay\.example%2F#Intent;/);
  const poisoned = await page('pair=' + encode(native + '&relay=' + encodeURIComponent('wss://relay.example/#Intent;package=other.app;end')));
  assert.equal(poisoned.open.hidden, true);
});

test('malformed, ambiguous, oversized, and non-origin relay invites do not get an app handoff', async () => {
  const invalidNative = [
    'https://other.example/?key=' + key,
    `hoppair://user:password@10.0.2.2:49273?key=${key}`,
    `hoppair://10.0.2.2:0?key=${key}`,
    native + '&key=' + key,
    native + '&other=value',
    native + '&relay=',
    native + '&relay=wss%3A%2F%2Frelay.example&relay=wss%3A%2F%2Fother.example',
    ...['ws://relay.example', 'wss://relay.example:0', 'wss://user@relay.example', 'wss://relay.example/private', 'wss://relay.example/?key=value', 'wss://relay.example/#fragment'].map(relay => native + '&relay=' + encodeURIComponent(relay)),
  ];
  const invalidFragments = ['pair=' + 'A'.repeat(4097), 'pair=%FF', 'pair=' + encode(native) + '&pair=' + encode(native), 'pair=' + encode(native) + '&extra=value', ...invalidNative.map(value => 'pair=' + encode(value))];
  for (const fragment of invalidFragments) {
    const result = await page(fragment);
    assert.equal(result.open.hidden, true, fragment.slice(0, 100));
    assert.match(result.status.textContent, /No valid pairing code/);
  }
});

test('non-Android browser never displays the app handoff; installation fallback still permits it on Android', async () => {
  assert.equal((await page('pair=' + encode(native), { android: false })).open.hidden, true);
  assert.equal((await page('pair=' + encode(native), { search: '?install=1' })).open.hidden, false);
});

test('release metadata cannot replace the APK link with a different origin or credential-bearing URL', async () => {
  for (const release of ['https://other.example/app.apk', releaseURL + '?redirect=other', releaseURL + '#fragment', releaseURL.replace('https://', 'https://user:password@'), 'https://github.com/other/repo/releases/download/v1/app.apk']) {
    assert.equal((await page('pair=' + encode(native), { release })).install.href, '');
  }
});
