#!/bin/bash
# Explicit operator action: publishes a Cloudflare Worker under the logged-in account.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REGISTRATION="${1:?Pass an absolute path to the registration JSON copied from the Mac app.}"
ROOM_CONFIG="$(node --input-type=module - "$REGISTRATION" <<'JS'
import {readFileSync} from 'node:fs';
const config=JSON.parse(readFileSync(process.argv[2],'utf8'));
const entries=Object.entries(config);
if (!entries.length || entries.some(([id,hash])=>!(/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/).test(id) || typeof hash!=='string' || !(/^[a-f0-9]{64}$/).test(hash))) throw new Error('Invalid registration JSON.');
process.stdout.write(JSON.stringify(config));
JS
)"
cd "$ROOT/services/relay"
npm ci
npm run check
npx wrangler deploy --dry-run
npx wrangler deploy --var "ROOM_CREDENTIALS:$ROOM_CONFIG"
