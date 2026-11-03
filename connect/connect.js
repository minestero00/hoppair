'use strict';
(() => {
  const open = document.querySelector('[data-open-app]');
  const install = document.querySelector('[data-install]');
  const status = document.querySelector('[data-status]');
  const installStage = new URLSearchParams(location.search).get('install') === '1';
  const android = /Android/i.test(navigator.userAgent);
  if (installStage) {
    document.querySelector('[data-title]').textContent = 'Install Hoppair.';
    document.querySelector('[data-description]').textContent = 'Get the Android app, then return here to connect your Mac.';
    document.querySelector('[data-install-help]').hidden = false;
    install.classList.add('install-primary');
  }
  try {
    const fragment = new URLSearchParams(location.hash.slice(1));
    if ([...fragment.keys()].some(name => name !== 'pair') || fragment.getAll('pair').length !== 1) throw new Error('invalid');
    const payload = fragment.get('pair');
    if (!payload || payload.length > 4096 || !/^[A-Za-z0-9_-]+={0,2}$/.test(payload)) throw new Error('missing');
    const bytes = Uint8Array.from(atob(payload.replace(/-/g, '+').replace(/_/g, '/')), value => value.charCodeAt(0));
    const decoded = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    const direct = new URL(decoded);
    if (!['hoppair:', 'macdroid:'].includes(direct.protocol) || direct.username || direct.password || direct.hash || !/^[A-Za-z0-9.-]+$/.test(direct.hostname) || !direct.port || Number(direct.port) < 1 || Number(direct.port) > 65535 || !['', '/'].includes(direct.pathname)) throw new Error('invalid');
    if (direct.searchParams.getAll('key').length !== 1 || direct.searchParams.getAll('relay').length > 1 || [...direct.searchParams.keys()].some(name => !['key', 'relay'].includes(name))) throw new Error('invalid');
    const key = direct.searchParams.get('key');
    if (!key || !/^[A-Za-z0-9_-]{43}=?$/.test(key) || atob(key.replace(/-/g, '+').replace(/_/g, '/')).length !== 32) throw new Error('invalid');
    const relay = direct.searchParams.get('relay');
    if (relay === '') throw new Error('invalid');
    if (relay) {
      const address = new URL(relay);
      if (address.protocol !== 'wss:' || address.username || address.password || !address.hostname || address.hash || address.search || !['', '/'].includes(address.pathname) || address.port === '0') throw new Error('invalid');
    }
    // Reconstruct the URI so input cannot inject Android Intent directives.
    const native = 'hoppair://' + direct.hostname + ':' + direct.port + '?key=' + encodeURIComponent(key) + (relay ? '&relay=' + encodeURIComponent(relay) : '');
    const fallback = new URL(location.href);
    fallback.search = '?install=1';
    open.href = native.replace(/^hoppair:/, 'intent:') + '#Intent;scheme=hoppair;package=dev.macdroid.app;S.browser_fallback_url=' + encodeURIComponent(fallback.href) + ';end';
    status.textContent = android ? 'Your pairing link is ready. Confirm the Mac in Hoppair to finish.' : 'Open this link on your Android phone, or scan the QR inside Hoppair.';
    open.hidden = !android;
    if (!android) document.querySelector('[data-device-note]').textContent = 'This download is for Android. Keep Hoppair open on your Mac while connecting.';
  } catch (_) {
    open.hidden = true;
    status.textContent = 'No valid pairing code found. Scan the QR shown in Hoppair on your Mac.';
    status.classList.add('invalid');
  }
  fetch('../release.json').then(response => {
    if (!response.ok) throw new Error('unavailable');
    return response.json();
  }).then(release => {
    const url = new URL(release.android.url);
    if (url.protocol === 'https:' && !url.username && !url.password && !url.port && !url.search && !url.hash && url.hostname === 'github.com' && url.pathname.startsWith('/minestero00/hoppair/releases/download/') && url.pathname.endsWith('.apk')) install.href = url.href;
  }).catch(() => {});
})();
