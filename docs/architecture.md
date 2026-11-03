# Architecture

The Mac hosts the canonical shelf. Android keeps a local cache and persistent outbox, syncing while its activity is visible. Files use UUID storage paths; display filenames never become filesystem paths.

## Transport

Direct TCP uses port 49271. A frame is a 4-byte unsigned big-endian length followed by AES-256-GCM data: 12-byte nonce, ciphertext, 16-byte tag. The plaintext is UTF-8 JSON. Requests carry a version, request ID, timestamp, and action; requests older than 60 seconds are rejected.

The optional WSS relay carries one sealed payload per binary message, without the TCP length prefix. Relay responses include the request ID inside the ciphertext. Routing identity and authentication are HMAC-SHA256 derivatives with distinct labels; the server verifies an operator-provisioned token hash without learning the content key.

## Shelf actions

| Action | Purpose |
| --- | --- |
| `list` | Shelf metadata and an optional camera request |
| `put` | Add an item with optional base64 file data |
| `get` | Fetch one file by ID |
| `cameraAck` | Acknowledge a photo request |

The Mac writes file data and an atomic index before acknowledging `put`. Stable item UUIDs make retries idempotent. Android marks an outbox item delivered only after success. Received files and links are never opened automatically.

Limits: 8 MiB per file, 64 KiB per text item, 12 MiB per encrypted frame, 500 shelf items. A full shelf rejects new items without removing old ones.

Pairing keys are held in macOS Keychain and protected by Android Keystore. QR codes transfer them out of band. Camera requests require confirmation on the phone.

See the source definitions in [Protocol.swift](../apps/macos/Sources/HoppairCore/Protocol.swift) and [BridgeClient.kt](../apps/android/app/src/main/java/dev/macdroid/app/BridgeClient.kt).

## Upgrade compatibility

Hoppair was previously named MacDroid. Version 0.1.1 keeps the original macOS bundle ID, Android application ID, storage paths, Keychain/Keystore aliases, and v1 relay derivation labels so existing installations retain their shelf and pairing. New QR codes use a verified HTTPS app link with the native pairing URI encoded in the URL fragment. Android accepts these invites plus `hoppair://` and legacy `macdroid://` links. Existing relay addresses remain valid. These internal identifiers are compatibility contracts, not the public product name.

## Camera-to-app handoff

`services/connect/` serves the public Android Digital Asset Links association and redirects uninstalled devices to the static `site/connect/` guide. Pairing credentials stay in the URL fragment, which is not sent in HTTP requests. The guide can open the installed app through an explicit Android Intent or offer the release APK; installation still requires Android's user approval. A new external invite requires an in-app confirmation and cannot replace a different existing Mac pairing.

There is no account, deferred-install token, or server-side pairing store. After installing an APK, return to the same guide and choose **Open Hoppair**. The built-in scanner decodes invites locally and also works without loading the website.
