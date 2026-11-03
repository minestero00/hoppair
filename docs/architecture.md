# Architecture

The Mac hosts the canonical shelf. Android keeps a local cache and persistent outbox, syncing while its activity is visible. Files use UUID storage paths; display filenames never become filesystem paths.

## Transport

Direct TCP uses port 49271. A frame is a 4-byte unsigned big-endian length followed by AES-256-GCM data: 12-byte nonce, ciphertext, 16-byte tag. The plaintext is UTF-8 JSON. Requests carry a version, UUID request ID, timestamp, and action; timestamps outside a 60-second window are rejected. A shared, bounded in-memory replay guard rejects reused request IDs across direct and relay connections.

The optional WSS relay carries one sealed payload per binary message, without the TCP length prefix. Both transports include the request ID inside encrypted replies; Android requires an exact match before interpreting success or errors. Android reuses a healthy relay socket while foregrounded and closes it on failure, pairing changes, or backgrounding. An integrity/correlation failure does not trigger relay fallback. Routing identity and authentication are HMAC-SHA256 derivatives with distinct labels; the server verifies an operator-provisioned token hash without learning the content key.

## Shelf actions

| Action | Purpose |
| --- | --- |
| `listPage` | Bounded metadata page, optional next cursor and camera request |
| `list` | Small legacy shelf snapshot; large shelves require an app update |
| `put` | Add an item with optional base64 file data |
| `get` | Fetch one file by ID |
| `delete` | Delete an item and persist an anti-retry receipt |
| `deleted` | Query receipts for up to 1,000 known item IDs |
| `cameraAck` | Acknowledge a photo request |
| `syncAck` | Confirm a round trip and exchange device names |

The Mac writes file data and an atomic index before acknowledging `put`. Stable item UUIDs make retries idempotent. Android marks an outbox item delivered only after success. Received files and links are never opened automatically. Missing or truncated received files are fetched again without blocking unrelated notes. Repairs retain the saved item’s type and declared size; queued local files are never replaced by downloads. A repaired indexed file remains usable even if a later item aborts the snapshot, while new unindexed downloads are cleaned up.

Limits: 8 MiB per file, 64 KiB per text item, 12 MiB per encrypted frame, 500 shelf items. A full shelf rejects new items without removing old ones. Users can explicitly delete items to free space. The Mac advertises `supportsDeletion`; older clients must update to reconcile removals. SQLite deletion receipts commit before index compaction and payload cleanup, so delayed uploads cannot restore removed items. Android validates queried receipts before uploading its outbox, then drops matching local and stale snapshot entries. Cleanup retries survive restarts without deleting unrelated files.

Metadata pages contain at most 100 items and approximately 2 MiB of encoded JSON. `listPage.id` is the previous page's last item UUID; `nextCursor` repeats the new last UUID when another page remains. Android validates the complete snapshot, IDs, cursors, and metadata before applying it. JSON expansion cannot turn a valid large shelf into a single oversized frame. Large initial relay syncs remain subject to the [relay throughput limits](relay.md#security-and-operation).

Pairing keys are held in macOS Keychain and protected by Android Keystore. QR codes transfer them out of band. Camera requests require confirmation on the phone.

See the source definitions in [Protocol.swift](../apps/macos/Sources/HoppairCore/Protocol.swift) and [BridgeClient.kt](../apps/android/app/src/main/java/dev/macdroid/app/BridgeClient.kt).

## Upgrade compatibility

Hoppair was previously named MacDroid. It keeps the original macOS bundle ID, Android application ID, storage paths, Keychain/Keystore aliases, and v1 relay derivation labels so existing installations retain their shelf and pairing. New QR codes use a verified HTTPS app link with the native pairing URI encoded in the URL fragment. Android accepts these invites plus `hoppair://` and legacy `macdroid://` links. Existing relay addresses remain valid. These internal identifiers are compatibility contracts, not the public product name.

## Camera-to-app handoff

`services/connect/` serves the public Android Digital Asset Links association and redirects uninstalled devices to the static `site/connect/` guide. Pairing credentials stay in the URL fragment, which is not sent in HTTP requests. The guide can open the installed app through an explicit Android Intent or offer the release APK; installation still requires Android's user approval. A new external invite requires an in-app confirmation. A different pairing key requires a separate replacement confirmation that explains pending uploads; the candidate is authenticated and its snapshot validated before replacing the saved connection.

There is no account, deferred-install token, or server-side pairing store. After installing an APK, return to the same guide and choose **Open Hoppair**. The built-in scanner decodes invites locally and also works without loading the website.

## Device status

`listPage` may advertise `syncAcknowledgement: true` and a `deviceName`. After merging the shelf and attempting its pending uploads, Android sends `syncAck` with its own name and waits for the correlated reply. Mac updates its connection freshness on this acknowledgement, not on receipt of an arbitrary request. This confirms a round trip, not delivery of rejected uploads; Android retains those items and shows the rejection. Relay reachability is shown separately. Names travel inside the encrypted envelope and are display labels, not an additional identity or authorization mechanism. Optional fields preserve older protocol clients; both apps must be updated for the new confirmation display.
