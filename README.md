# Website

Static GitHub Pages site. No build step or external runtime dependencies.

Preview with `python3 -m http.server 8741 --directory site`. Publish a clean, committed checkout with `./scripts/deploy-pages.sh`; it updates the `gh-pages` branch without switching your application branch.

Download URLs and availability live in `release.json`. Update them only after verifying the published release assets.

Screens are captured from the native apps with isolated sample content. See [development](../docs/development.md) for capture commands; never publish personal pairing codes or shelf contents.
