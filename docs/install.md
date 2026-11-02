# Install and pair

Get both apps from [GitHub Releases](https://github.com/minestero00/macDroid/releases).

## macOS

1. Download the macOS ARM64 ZIP and unzip it.
2. Move `MacDroid.app` to Applications and open it.

Requires macOS 14 or later on Apple silicon. Preview builds are ad-hoc signed and not notarized. If macOS blocks a download, use the normal **Privacy & Security → Open Anyway** flow after verifying its source, or [build from source](development.md).

## Android

1. Download the APK on your phone.
2. Open it and allow installation from that source when Android asks.

Requires Android 9 or later. Release APKs use a dedicated signing key; no Play Store account is needed for personal APK installation. Some regions have additional [developer verification requirements](https://developer.android.com/developer-verification).

## Pair

1. Open both apps on the same local network.
2. On Mac, choose **Connect Phone**.
3. On Android, choose **Scan QR to connect** and scan the code. Allow camera access when asked.

A pairing link is available under **More connection options** if scanning is unavailable. Treat QR codes and pairing links as private credentials.

Add a file, link, or note on either device. On Android, other apps can send items through **Share → MacDroid**. The Mac's **Take Photo** action asks the phone to open its camera; confirm on the phone.

## Away from home

Configure the optional [relay](relay.md) once, then scan the updated QR. The same device identity survives changes to your Mac's network and IP. The default preview does not include a hosted relay.

Keep the Mac awake and MacDroid running. Keep Android's app open to sync; pending items remain stored when disconnected.

Limits: 8 MiB per file, 64 KiB per text item, 500 shelf items. This preview targets one Mac and one Android phone. Full shelves reject new items; there is no item deletion or in-app key rotation yet.
