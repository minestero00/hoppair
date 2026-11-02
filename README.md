<p align="center"><img src="assets/app-icon/AppIcon.icon/Assets/Artwork.png" width="96" alt="MacDroid icon"></p>

# MacDroid

A native macOS + Android companion for sharing files, links, notes, and phone photos.

**Early preview.** Android syncs while the app is open. Cross-network connections require a self-hosted relay.

- Pair by scanning a QR code.
- Share through a persistent shelf, Android's share menu, and Mac drag and drop.
- Use the Mac menu bar and command palette; request a photo from your phone.
- Transfer directly nearby, or use an encrypted relay when networks change.

## Get started

[Download a preview](https://github.com/minestero00/macDroid/releases) · [Install and pair](docs/install.md) · [Build and test](docs/development.md) · [Set up a relay](docs/relay.md)

## Repository

```text
apps/macos/       SwiftUI app and shared Swift protocol code
apps/android/     Kotlin / Jetpack Compose app
services/relay/   Cloudflare Worker and Durable Object
site/             Static GitHub Pages website
assets/app-icon/  Editable icon source and launcher artwork
scripts/          Build, test, and deployment commands
docs/             Installation, development, and protocol guides
```

## Contribute

Improvements and bug reports are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md), open an issue, or send a focused pull request.

[MIT](LICENSE). Third-party notices are listed in [NOTICE](NOTICE).
