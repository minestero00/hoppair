<p align="center"><img src="assets/app-icon/brand.png" width="96" alt="Hoppair icon"></p>

# Hoppair

A native macOS + Android companion for sharing files, links, notes, and phone photos.

**Early release.** Android syncs while the app is open. Cross-network connections require a self-hosted relay.

- Pair by scanning a QR code.
- Share through a persistent shelf, Android's share menu, and Mac drag and drop.
- Use the Mac menu bar and command palette; request a photo from your phone.
- Transfer directly nearby, or use an encrypted relay when networks change.
- Check for signed app updates without replacing your shelf or pairing.

Previously published here as MacDroid. Existing installations can [update without re-pairing](docs/install.md#updating-from-macdroid).

## Get started

[Website and app screenshots](https://minestero00.github.io/hoppair/)

[Download apps](https://github.com/minestero00/hoppair/releases) · [Install and pair](docs/install.md) · [Build and test](docs/development.md) · [Set up a relay](docs/relay.md) · [Security](docs/security.md)

## Repository

```text
apps/macos/       SwiftUI app and shared Swift protocol code
apps/android/     Kotlin / Jetpack Compose app
services/relay/   Encrypted relay Worker and Durable Object
services/connect/ Android app association and install handoff
site/             Static GitHub Pages website
assets/app-icon/  Editable icon source and launcher artwork
scripts/          Build, test, and deployment commands
docs/             Installation, development, and protocol guides
```

## Contribute

Improvements and bug reports are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md), open an issue, or send a focused pull request.

[MIT](LICENSE). Third-party notices are listed in [NOTICE](NOTICE).
