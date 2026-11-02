const { test, expect } = require('@playwright/test');
for (const width of [1440, 768, 375, 320]) {
  test(`layout at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto('./');
    await expect(page.getByRole('heading', { level: 1 })).toContainText('One shared shelf.');
    await page.locator('#screens').scrollIntoViewIfNeeded();
    await page.locator('#downloads').scrollIntoViewIfNeeded();
    for (const image of await page.locator('img[src]').all()) {
      await image.scrollIntoViewIfNeeded();
      await expect.poll(() => image.evaluate(element => element.complete && element.naturalWidth > 0)).toBe(true);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    const nav = await page.locator('.nav').boundingBox();
    for (const element of await page.locator('.nav a:visible').all()) {
      const box = await element.boundingBox();
      expect(box.x).toBeGreaterThanOrEqual(nav.x);
      expect(box.x + box.width).toBeLessThanOrEqual(nav.x + nav.width + 1);
    }
    expect(errors).toEqual([]);
    await page.evaluate(() => scrollTo(0, 0));
    await page.screenshot({ path: require('path').resolve(__dirname, `../../artifacts/site-${width}.png`), fullPage: true });
  });
}
test('theme preview, modal keyboard and FAQ', async ({ page }) => {
  await page.goto('./');
  await page.getByRole('button', { name: 'Dark', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Dark', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('[data-android-image]')).toHaveAttribute('src', 'assets/android-shelf-dark.png');
  const screenshot = page.locator('[data-android-link]');
  await screenshot.click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog')).not.toBeVisible();
  await expect(screenshot).toBeFocused();
  const question = page.locator('summary').first();
  await question.focus();
  await page.keyboard.press('Enter');
  await expect(page.locator('details').first()).toHaveAttribute('open', '');
});
test('reduced motion and no-JS download fallback', async ({ browser }) => {
  const context = await browser.newContext({ javaScriptEnabled: false, reducedMotion: 'reduce' });
  const page = await context.newPage();
  await page.goto('./');
  await expect(page.locator('[data-download="mac"]')).toHaveAttribute('href', /github\.com/);
  await expect(page.locator('[data-theme-controls]')).toBeHidden();
  expect(await page.evaluate(() => getComputedStyle(document.documentElement).scrollBehavior)).toBe('auto');
  await context.close();
});
