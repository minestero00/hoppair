# App icon

A white laptop-and-phone mark on charcoal, shared by both apps and the website.

- `AppIcon.icon/Assets/Symbol.svg` is the editable vector source.
- `AppIcon.icon/` supplies the native macOS mask and background.
- `brand.png` is the rounded in-app mark; `android.png` shows the adaptive safe-area composition.

To regenerate the PNGs and Android vectors, run `npm ci --prefix tests/site` and `npx --prefix tests/site playwright install chromium`, then `node scripts/export-icons.cjs`. `python3 scripts/build-icons.py` compiles the Apple icon with Xcode.

The original vector artwork is licensed under MIT.
