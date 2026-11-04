# Changelog

## [0.1.11](https://github.com/minestero00/hoppair/releases/tag/v0.1.11) — 2026-11-04

- Remove the small Android TCP receive-buffer override that can stall relay downloads on higher-latency networks. Frame and message size limits remain enforced.
- Mac remains at v0.1.10 (build 22); no Mac update is required for this fix.

**Validation:** the same encrypted 1.9 MB file through the production relay did not complete within 45 seconds with the old buffer setting, and completed in 3.54 seconds with the system default using the Android transport library on macOS. Android release unit tests and lint passed, along with 11 emulator transport/security checks. Physical Galaxy/LTE confirmation is still required.

## [0.1.10](https://github.com/minestero00/hoppair/releases/tag/v0.1.10) — 2026-11-04

- Improve Android QR scanning with higher-resolution analysis, an aligned viewfinder, and more room in landscape.
- Keep notes and queued transfers moving when an individual file fails; re-download missing or truncated received files when their source is available.
- Replace a saved Mac connection only after confirmation and a successful connection check. Failed attempts keep the existing pairing.
- Delete items from the shared shelf on either device, with confirmation and protection against delayed uploads restoring deleted items.
- Show local image previews on Android and move large-shelf searches off the UI thread on both platforms.
- Improve compact Android layouts and verify update controls with large system text.
- Make Mac command/connection sheet transitions use a single presentation state, and correct the singular item count.

Update both apps to sync deletions. Install over the existing apps to retain your shelf and pairing; do not uninstall Android first. Android deletion requires the Mac online.

**Validation:** signed Android v0.1.9 → v0.1.10 upgrade tested on an emulator, including an offline queued note and automatic reconnection. Mac command/connection transitions, composer focus, search, and deletion dialogs checked in an isolated sample app. The Mac release is Developer ID signed and Apple notarized. Physical Galaxy QR/LTE behavior and remaining device checks are still pending.

## [0.1.9](https://github.com/minestero00/hoppair/releases/tag/v0.1.9)

- Introduce the bundled Android QR scanner with automatic zoom, tap-to-focus, pinch zoom, flashlight controls, and a pairing-link fallback.
- Enlarge the Mac pairing QR and its white margin.
- Improve first-pair connection error guidance.

See [GitHub Releases](https://github.com/minestero00/hoppair/releases) for earlier updates and downloads.
