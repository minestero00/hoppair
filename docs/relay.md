# Cross-network relay

The optional Cloudflare Worker connects your Mac and phone across different networks. Device identity comes from the pairing key, so changing the Mac's location or IP does not require pairing again. Both apps still need to be running; the relay does not store offline files.

SQLite Durable Objects are available on Cloudflare's free plan, subject to [usage limits and pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/). Start there for personal use and check actual usage. The scripts do not change plans or billing settings.

## Deploy your own

1. In the Mac app, open **Connect Phone → More connection options → Copy Relay Registration**. Save the JSON outside the repository.
2. Sign in to your Cloudflare account and deploy:

```bash
cd services/relay
npm ci
npx wrangler login
cd ../..
./scripts/deploy-relay.sh /absolute/path/to/registration.json
```

3. Change the deployed address from `https://…workers.dev` to `wss://…workers.dev`. Enter it under the Mac's **Relay Settings**, then choose **Apply**.
4. Once connected, scan the updated QR on Android. Subsequent network changes reuse the same pairing.

Use the deployment script with the same registration file for updates. A plain deployment uses an empty registration map and rejects devices.

If an existing deployment uses a different Worker name, set `HOPPAIR_RELAY_WORKER_NAME` to that name when running the script; this preserves its address instead of creating another Worker.

To include the address in your own Mac build:

```bash
HOPPAIR_RELAY_URL=wss://your-relay.workers.dev ./scripts/build-mac.sh
```

## Security and operation

- Only pre-registered device IDs and authentication hashes are accepted. Knowing a room ID cannot claim it.
- Contents remain AES-GCM ciphertext. The relay does not receive the content key or persist files; operators can see connection metadata.
- One Mac and one phone connect per room. Requests are sequential. The Mac reconnects after failure; Android retries while open.
- Persisted per-room, per-role guards allow 240 messages, 96 MiB, and 30 connections per minute. Reconnecting does not reset these limits. They are not account-wide billing quotas.
- Remove a registration and redeploy to revoke it. To revoke the sole registration, run `npx wrangler deploy --name EXISTING_WORKER_NAME` from `services/relay/`: its empty default map clears **all** enrolled rooms. The registration deployment script intentionally rejects an empty file. In-app pairing-key rotation is not implemented yet.

The service is intended for personal use, not a public multi-user relay. Verify your deployed endpoint, then test on a real phone over LTE and after network changes; local emulation does not cover these conditions.

Use direct LAN for a large initial shelf transfer. Relay quotas can interrupt bulk synchronization; this version does not persist a resumable multi-page download transaction across failures. Ordinary retries preserve pending items, but do not guarantee progress for shelves larger than a quota window. The relay cannot read encrypted content, but can observe timing and sizes; trust its operator for availability. See [security.md](security.md).

For local testing, see [development.md](development.md). Protocol details are in [architecture.md](architecture.md).
