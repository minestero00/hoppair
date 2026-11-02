# Build and test

Use the repository root for the commands below. Outputs go to `dist/`; test receipts go to `artifacts/`. Both directories are ignored by Git.

## macOS

Requires Xcode with Swift and Icon Composer's asset compiler, plus Python 3. Tested with Xcode 27.1; `xcrun --find actool` should resolve the asset compiler.

```bash
./scripts/build-mac.sh
open dist/MacDroid.app
swift test --package-path apps/macos
```

Local builds use ad-hoc signing. Set `MACDROID_SIGN_IDENTITY` to use your own signing identity. Rebuilt development apps may require renewed Keychain approval.

## Android

Requires JDK 17 and Android SDK 36. Set `ANDROID_HOME` or `apps/android/local.properties` if SDK discovery fails.

```bash
./scripts/build-android.sh
adb install -r dist/MacDroid-android-debug.apk
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

After building the Mac app, `./scripts/capture-mac-ui.sh` creates a separately identified screenshot app and renders its sample shelf to `artifacts/macos-ui/shelf.png`. It never loads the personal shelf, pairing key, or network services. Set `MACDROID_SCREENSHOT_APPEARANCE=dark` for a dark capture.

## Package a release

```bash
./scripts/package-release.sh 0.1.0
```

Create your own persistent Android signing key once with `python3 scripts/create-android-signing.py`. It is stored under `~/.config/macdroid/signing/`, outside Git; back up both files. Use `MACDROID_ANDROID_SIGNING_FILE` to select another configuration. Local debug APKs and signed release APKs use different keys and cannot update one another.

The packaging command builds both apps and writes a Mac ZIP, signed Android APK, and SHA-256 checksums to `dist/release/`. The Mac archive targets the build machine's architecture. Publish those files after the relevant local gates pass. Public Mac downloads should use Developer ID signing and notarization; never upload signing keys or local configuration.

For notarized Mac releases, use your own **Developer ID Application** identity. With Xcode's signed-in account:

```bash
MACDROID_SIGN_IDENTITY="Developer ID Application: Your Name (TEAMID)" ./scripts/archive-mac.sh
open dist/MacDroid.xcarchive
```

In Organizer, choose **Distribute App → Direct Distribution**. After approval, choose **Export Notarized App** and ZIP the exported app. Move an existing archive before running the script again.

For command-line packaging, use an existing `notarytool` Keychain profile:

```bash
MACDROID_SIGN_IDENTITY="Developer ID Application: Your Name (TEAMID)" \
MACDROID_NOTARY_PROFILE=macdroid-notary \
./scripts/package-release.sh 0.1.0
```

Create the profile yourself with `xcrun notarytool store-credentials macdroid-notary`; credentials stay outside Git. The packaging script enables hardened runtime, waits for notarization, staples the ticket, and then archives the app.

## Website

Run `npm ci --prefix tests/site`, `npx --prefix tests/site playwright install chromium`, and start the preview server described in [site/README.md](../site/README.md). Then run `npm test --prefix tests/site` to check responsive layouts, screenshot previews, keyboard behavior, and static fallbacks.
