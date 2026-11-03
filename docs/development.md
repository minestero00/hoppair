# Build and test

Use the repository root for the commands below. Outputs go to `dist/`; test receipts go to `artifacts/`. Both directories are ignored by Git.

## macOS

Requires Xcode with Swift and Icon Composer's asset compiler, plus Python 3. Tested with Xcode 27.1; `xcrun --find actool` should resolve the asset compiler.

```bash
./scripts/build-mac.sh
open dist/Hoppair.app
swift test --package-path apps/macos
```

Quit Hoppair before rebuilding; the script refuses to replace a running app. Local builds use ad-hoc signing. Set `HOPPAIR_SIGN_IDENTITY` to use your own signing identity. Rebuilt development apps may require renewed Keychain approval.

## Android

Requires JDK 17 and Android SDK 36. Set `ANDROID_HOME` or `apps/android/local.properties` if SDK discovery fails.

```bash
./scripts/build-android.sh
adb install -r dist/Hoppair-android-debug.apk
```

The script builds the APK and runs unit tests and lint. Open `apps/android/` in Android Studio to work on the app.

## Relay

Requires Node.js 22 or later.

```bash
cd services/relay
npm ci
npm run check
cd ../..
python3 scripts/test-relay.py --protocol-only
```

The check generates Worker types before running TypeScript. The local gate starts and stops its own Worker; it does not deploy or use personal registrations.

## Both apps together

Start an Android emulator, get its serial from `adb devices`, and substitute it below:

```bash
./scripts/test-peer.sh emulator-5580
python3 scripts/test-relay.py --serial emulator-5580
```

The first gate tests direct TCP. The second forces relay fallback with the direct address unreachable. Both check text/binary transfer, lost-ack retries, and Android outbox persistence using isolated fixtures.

Real-phone QR/camera, LTE, sleep/wake, permissions, accessibility, and battery behavior still require manual validation. Do not report emulator tests as hardware coverage.

Run `./scripts/test-android-ui.sh emulator-5580` on a disposable emulator for the Android UI gate (Node.js 22+ required). It uses an isolated loopback shelf, checks search/details/attachments, and captures onboarding, light/dark themes, large text, landscape, and tablet layouts under `artifacts/android-ui/`.

After building the Mac app, `./scripts/capture-mac-ui.sh` creates a separately identified screenshot app and renders its sample shelf to `artifacts/macos-ui/shelf.png`. It never loads the personal shelf, pairing key, or network services. Quit the screenshot app before capturing again. Set `HOPPAIR_SCREENSHOT_APPEARANCE=dark` for a dark capture or `HOPPAIR_SCREENSHOT_SIZE=800x640` for a compact window.

## Package a release

```bash
./scripts/package-release.sh 0.1.3
```

Existing MacDroid signing configurations are reused automatically; do not generate a replacement key for an existing installation. `MACDROID_*` signing/build environment variables remain accepted as fallbacks.

Create your own persistent Android signing key once with `python3 scripts/create-android-signing.py`. It is stored under `~/.config/hoppair/signing/`, outside Git; back up both files. Use `HOPPAIR_ANDROID_SIGNING_FILE` to select another configuration. Local debug APKs and signed release APKs use different keys and cannot update one another.

The packaging command builds both apps and writes a Mac ZIP, signed Android APK, and SHA-256 checksums to `dist/release/`. The Mac archive targets the build machine's architecture. Publish those files after the relevant local gates pass. Public Mac downloads should use Developer ID signing and notarization; never upload signing keys or local configuration.

For notarized Mac releases, use your own **Developer ID Application** identity. With Xcode's signed-in account:

```bash
HOPPAIR_SIGN_IDENTITY="Developer ID Application: Your Name (TEAMID)" ./scripts/archive-mac.sh
open dist/Hoppair.xcarchive
```

In Organizer, choose **Distribute App → Direct Distribution**. After approval, choose **Export Notarized App** and ZIP the exported app. Move an existing archive before running the script again.

For command-line packaging, use an existing `notarytool` Keychain profile:

```bash
HOPPAIR_SIGN_IDENTITY="Developer ID Application: Your Name (TEAMID)" \
HOPPAIR_NOTARY_PROFILE=hoppair-notary \
./scripts/package-release.sh 0.1.3
```

Create the profile yourself with `xcrun notarytool store-credentials hoppair-notary`; credentials stay outside Git. The packaging script enables hardened runtime, waits for notarization, staples the ticket, and then archives the app.

## Website

Run `npm ci --prefix tests/site`, `npx --prefix tests/site playwright install chromium`, and start the preview server described in [site/README.md](../site/README.md). Then run `npm test --prefix tests/site` to check responsive layouts, screenshot previews, keyboard behavior, and static fallbacks. Set `HOPPAIR_SITE_URL=https://minestero00.github.io/hoppair/` to check the published site.

## Pairing guide

The camera handoff uses `services/connect/` and `site/connect/`. Run `npm ci --prefix services/connect` and `npm test --prefix services/connect` for its routing/association check; deploy with `npm run deploy --prefix services/connect` from your authorized Cloudflare account. The app association contains the public Android release certificate fingerprint, never its private key. Forks must configure their own host in the Worker, Android manifest/parser, and Swift `PairingLink.page`.

The browser tests also cover install fallback, invalid invites, and keeping pairing credentials out of HTTP requests. Release-domain verification must report `verified` from Android's `pm get-app-links`, not a forced `approved` state.

For isolated Mac UI candidates, use `HOPPAIR_OUTPUT_DIR=/absolute/output ./scripts/build-mac.sh`; the running app is never replaced. Point `HOPPAIR_APP_SOURCE` at that candidate when capturing its sample screen.
