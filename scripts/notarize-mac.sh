#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROFILE="${1:?Pass an existing notarytool Keychain profile.}"
OUTPUT_DIR="${HOPPAIR_OUTPUT_DIR:-$ROOT/dist}"
OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd)"
APP="$OUTPUT_DIR/Hoppair.app"
SIGNATURE="$(codesign -dvv "$APP" 2>&1)"
if [[ "$SIGNATURE" != *'Authority=Developer ID Application:'* ]]; then
    echo 'Build with a Developer ID Application identity before notarizing.' >&2
    exit 1
fi
UPLOAD="$OUTPUT_DIR/Hoppair-notary-upload.zip"
RECEIPT="$OUTPUT_DIR/notarization.json"
ditto -c -k --sequesterRsrc --keepParent "$APP" "$UPLOAD"
xcrun notarytool submit "$UPLOAD" --keychain-profile "$PROFILE" --wait --output-format json > "$RECEIPT"
python3 - "$RECEIPT" <<'PY'
import json,sys
result=json.load(open(sys.argv[1]))
if result.get('status')!='Accepted':
    raise SystemExit(f'Notarization was not accepted. See {sys.argv[1]} and retrieve the submission log.')
print('Notarization accepted.')
PY
xcrun stapler staple "$APP"
xcrun stapler validate "$APP"
codesign --verify --deep --strict "$APP"
