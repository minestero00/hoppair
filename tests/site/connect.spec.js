const { test, expect } = require('@playwright/test');
const release = require('../../site/release.json');
const secret = Buffer.alloc(32, 7).toString('base64url');
const native = `hoppair://10.0.2.2:49273?key=${secret}`;
const payload = Buffer.from(native).toString('base64url');
test.use({ userAgent: 'Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36' });
test('pairing stays in the fragment and the app link has an install fallback', async ({ page }) => {
  const requests = [];
  page.on('request', request => requests.push(request.url()));
  await page.goto(`./connect/#pair=${payload}`);
  const open = page.locator('[data-open-app]');
  await expect(open).toBeVisible();
  const intent = await open.getAttribute('href');
  expect(intent).toContain('intent://10.0.2.2:49273?key=');
  expect(intent).toContain(';scheme=hoppair;package=dev.macdroid.app;');
  const fallback = decodeURIComponent(intent.split('S.browser_fallback_url=')[1].split(';end')[0]);
  expect(fallback).toContain('?install=1#pair=' + payload);
  await expect(page.locator('[data-install]')).toHaveAttribute('href', release.android.url);
  expect(requests.every(url => !url.includes(secret) && !url.includes(payload))).toBe(true);
});
test('install fallback preserves the return-to-app step on a small phone', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto(`./connect/?install=1#pair=${payload}`);
  await expect(page.getByRole('heading', { name: 'Install Hoppair.' })).toBeVisible();
  await expect(page.locator('[data-install-help]')).toContainText('Return to this page');
  await expect(page.locator('[data-open-app]')).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: require('path').resolve(__dirname, '../../artifacts/connect-phone.png'), fullPage: true });
});
for (const invalid of ['https://other.example/?key=' + secret, 'hoppair://user:password@10.0.2.2:49273?key=' + secret, 'hoppair://10.0.2.2:0?key=' + secret, 'hoppair://10.0.2.2:49273?key=short']) {
  test(`reject unsafe pairing payload ${invalid.split(':')[0]} ${invalid.length}`, async ({ page }) => {
    await page.goto('./connect/#pair=' + Buffer.from(invalid).toString('base64url'));
    await expect(page.locator('[data-open-app]')).toBeHidden();
    await expect(page.locator('[data-status]')).toContainText('No valid pairing code');
  });
}
