# Install and pair

Get both apps from [GitHub Releases](https://github.com/minestero00/hoppair/releases).

## macOS

1. Download the macOS ARM64 ZIP and unzip it.
2. Move `Hoppair.app` to Applications and open it.

Requires macOS 14 or later on Apple silicon. Official Mac downloads are Developer ID signed and Apple notarized. [Source builds](development.md) are also available.

## Android

1. Download the APK on your phone.
2. Open it and allow installation from that source when Android asks.

Requires Android 9 or later. Release APKs use a dedicated signing key; no Play Store account is needed for personal APK installation. Some regions have additional [developer verification requirements](https://developer.android.com/developer-verification).

## Pair

1. Start on the same local network for direct transfers, or configure the optional relay.
2. On Mac, choose **Connect Phone** and scan its QR with your Android camera.
3. If Hoppair is installed, open it and confirm the Mac. Otherwise, install the APK from the guide, return to that page, and choose **Open Hoppair**.

You can also scan inside Hoppair with **Scan QR to connect**. This decodes the invite locally, without opening a website. Allow camera access when asked. Tap the preview to focus, pinch or use the zoom buttons for a small QR, and use the flashlight in dim light. The bundled scanner works without a model download. QR images and contents stay on your phone; Google ML Kit collects SDK performance and usage metrics ([privacy and terms](https://developers.google.com/ml-kit/terms)).

A pairing link is available under **More connection options** if scanning is unavailable. Treat QR codes and pairing links as private credentials.

Name your devices in the connection settings, or leave the name blank to use the system device name. The other device shows this name after syncing. **Relay ready** means the Mac can reach the relay; the phone is shown as connected only after it confirms a round trip. Update both apps to see this confirmation.

Add a file, link, or note on either device. On Mac, press **⌘Return** to send the composer, **⌘K** for commands, or **⇧⌘Space** to open commands from another app. On Android, other apps can send items through **Share → Hoppair**. The Mac's **Take Photo** action asks the phone to open its camera; confirm on the phone.

## Away from home

Configure the optional [relay](relay.md) once, then scan the updated QR. The same device identity survives changes to your Mac's network and IP. The download does not include a hosted relay.

Pairing is saved securely. Reopen both apps to reconnect automatically; no new QR scan is needed. Keep the Mac awake and both apps open to sync. Pending items remain stored while disconnected.

If you reinstall Hoppair on your Mac or switch Macs, scan the new QR. Android asks before replacing a different saved connection and explains which waiting items will be sent. Canceling or a failed connection check keeps the previous pairing.

Limits: 8 MiB per file, 64 KiB per text item, 500 shelf items. This release targets one Mac and one Android phone. Use **Delete from shared shelf** in an item’s details to free space. Android deletion needs the Mac online; deletions made on Mac reach the phone when it reconnects. Update both apps to sync deletions. There is no in-app key rotation yet.

## Updating from MacDroid

If you installed this project's MacDroid v0.1.0 release, install the official Hoppair release over it. On Mac, quit MacDroid and replace the old app with `Hoppair.app`; remove the old app bundle only after the new one opens. The shelf, pairing, and relay settings use the same internal app identity. Android release APKs update the existing package in place. No uninstallation or re-pairing is required; source builds still require their original release or debug signer.

## App updates

Install v0.1.6 once on **Mac first, then Android**. Older versions do not have the built-in updater. New Android builds require the Mac's correlated replies and paged shelf protocol.

From v0.1.6, Mac checks automatically and offers download, installation, and relaunch through Sparkle. Use **Hoppair → Check for Updates…** to check manually. Android checks when opened; use **More → Check for updates** for a manual check. Tap **Update**, allow Hoppair as an installation source if Android asks, then confirm the system installer. Your shelf and pairing stay in place.

The updater removes its own temporary downloads and installation files. Android also removes completed APKs when the updated app opens, and prunes stale downloads after 24 hours. Files you downloaded manually to Downloads are yours to remove. Mac releases use ZIP archives, not DMG installers.

Read [security and trust boundaries](security.md) and the [release/update guide](updates.md) for verification and maintenance details.
