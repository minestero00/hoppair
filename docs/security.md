# Security boundaries

Hoppair is an early personal-use app. This document describes its controls and limitations; it is not a security certification.

## Pairing and transport

- The pairing QR/link contains the content key. Anyone holding it has trusted-peer access to the shelf and photo requests. Keep it private; do not publish screenshots of real invites.
- AES-256-GCM authenticates content on direct TCP and relay connections. Android checks encrypted response IDs before accepting data or errors. Mac rejects stale requests and duplicate IDs with a bounded replay guard shared by both transports.
- Production relay addresses require WSS and system TLS certificate/hostname validation. Device enrollment uses a derived credential; unknown IDs and incorrect credentials receive the same rejection.
- A replacement relay connection cannot route replies to a retired peer. Persisted traffic and reconnect limits survive connection replacement.
- File paths use UUIDs. File/text/frame limits, metadata validation, paged snapshots, finite socket deadlines, and bounded WebSocket handshakes/fragment aggregation constrain hostile input. Files and URLs are not opened automatically. Phone camera access needs confirmation.

Keys are stored in macOS Keychain and Android Keystore-backed storage. The browser handoff keeps invites in URL fragments, so they are not sent in HTTP requests. The guide, browser extensions, and browser history can still access a fragment; use the in-app scanner to avoid that handoff.

## Updates

Mac uses pinned Sparkle 2.10.0, requires signed feeds without a time-based verification fallback, and verifies Ed25519 archive signatures before extraction. Official apps are Developer ID signed and notarized. Android accepts only this repository's HTTPS release feed and APK locations, checks exact size/SHA-256, then matches package ID, version, minimum SDK, and installed signer certificates before requesting system installation.

Update credentials stay outside Git. Compromise of the release signing keys remains a critical risk. Install official updates over the existing app; uninstalling Android may remove its pairing key and shelf.

## Remaining limits

- Pairing uses a long-lived shared key. There is no forward secrecy, per-device permission model, in-app rotation, or revocation flow. Remove a relay registration to revoke relay access; that does not revoke direct access for someone who already has the key.
- Replay history is memory-only. A Mac restart or extreme clock changes can weaken replay protection for a captured request still within the accepted time window. Stable item IDs also make normal upload retries idempotent.
- Connection/frame limits mitigate resource exhaustion; they do not prevent all local-network floods, slow peers, relay outages, or account-wide spending. Relay quotas limit large initial transfers; use direct LAN for those.
- One Mac and one Android phone are supported. Android syncs while open; sleep/wake, physical-device permissions, and LTE need device testing beyond local emulation.

The v0.1.6 review covered Mac, Android, relay, and browser handoff code, with separate reviewers and automated regression checks. This is a scoped engineering review, not a third-party penetration test.

## Reporting

For a potential vulnerability, use the repository's **Security → Report a vulnerability** option if available. Otherwise open an issue without keys, personal shelf contents, or a working exploit against a live user's device, and request a private contact channel.
