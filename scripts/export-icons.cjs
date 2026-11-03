#!/usr/bin/env node
// Render the original vector mark to platform assets; no image-generation service.
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require('../tests/site/node_modules/@playwright/test');

async function main() {
  const root = path.resolve(__dirname, '..');
  const source = await fs.readFile(path.join(root, 'assets/app-icon/AppIcon.icon/Assets/Symbol.svg'), 'utf8');
  const mark = source.match(/<g[\s\S]*<\/g>/)[0];
  const translation = source.match(/<g transform="translate\(([-.\d]+) ([-.\d]+)\)">/);
  if (!translation || !source.includes('viewBox="0 0 1024 1024" fill="#fff"')) throw new Error('Expected a white 1024px vector mark with a translation.');
  const paths = [...source.matchAll(/<path(?: fill-rule="(evenodd)")? d="([^"]+)"\s*\/>/g)];
  if (paths.length !== 3) throw new Error('Expected the three paths of the Hoppair mark.');
  const svg = (scale = 1, rounded = false) => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024"><rect width="1024" height="1024" rx="${rounded ? 224 : 0}" fill="#171717"/><g fill="#fff" transform="translate(512 512) scale(${scale}) translate(-512 -512)">${mark}</g></svg>`;
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ viewport: { width: 1024, height: 1024 }, deviceScaleFactor: 1 });
    async function render(file, size, scale, rounded) {
      await page.setViewportSize({ width: size, height: size });
      await page.setContent(`<style>html,body{margin:0;width:100%;height:100%;background:transparent}svg{display:block;width:100%;height:100%}</style>${svg(scale, rounded)}`);
      await page.screenshot({ path: path.join(root, file), omitBackground: true });
    }
    await render('assets/app-icon/brand.png', 1024, 1, true);
    await render('assets/app-icon/android.png', 1024, 0.78, false);
    await render('site/assets/icon.png', 256, 1, true);
    await render('apps/android/app/src/main/res/drawable-nodpi/hoppair_brand.png', 256, 1, true);
    for (const [density, size] of Object.entries({ mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 })) {
      await render(`apps/android/app/src/main/res/mipmap-${density}/ic_launcher.png`, size, 1, true);
    }
  } finally { await browser.close(); }
  // Both Android layers use the same paths and clear screen cutouts.
  const vector = `<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="1024" android:viewportHeight="1024">\n    <group android:pivotX="512" android:pivotY="512" android:scaleX="0.78" android:scaleY="0.78">\n        <group android:translateX="${translation[1]}" android:translateY="${translation[2]}">\n${paths.map(([, rule, d]) => `            <path android:fillColor="#FFFFFFFF"${rule ? ' android:fillType="evenOdd"' : ''} android:pathData="${d}" />`).join('\n')}\n        </group>\n    </group>\n</vector>\n`;
  const res = 'apps/android/app/src/main/res/drawable/';
  await fs.writeFile(path.join(root, res, 'hoppair_foreground.xml'), vector);
  await fs.writeFile(path.join(root, res, 'ic_launcher_monochrome.xml'), vector);
  console.log('Exported the monochrome mark for Mac branding, Android, and the website.');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
