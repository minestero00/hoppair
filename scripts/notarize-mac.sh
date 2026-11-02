#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROFILE="${1:?Pass an existing notarytool Keychain profile.}"
APP="$ROOT/dist/MacDroid.app"
SIGNATURE="$(codesign -dvv "$APP" 2>&1)"
if [[ "$SIGNATURE" != *'Authority=Developer ID Application:'* ]]; then
    echo 'Build with a Developer ID Application identity before notarizing.' >&2
    exit 1
fi
mkdir -p "$ROOT/artifacts"
UPLOAD="$ROOT/dist/MacDroid-notary-upload.zip"
ditto -c -k --sequesterRsrc --keepParent "$APP" "$UPLOAD"
xcrun notarytool submit "$UPLOAD" --keychain-profile "$PROFILE" --wait --output-format json > "$ROOT/artifacts/notarization.json"
python3 - "$ROOT/artifacts/notarization.json" <<'PY'
import json,sys
result=json.load(open(sys.argv[1]))
if result.get('status')!='Accepted':
    raise SystemExit('Notarization was not accepted. See artifacts/notarization.json and retrieve the submission log.')
print('Notarization accepted.')
PY
xcrun stapler staple "$APP"
xcrun stapler validate "$APP"
codesign --verify --deep --strict "$APP"
