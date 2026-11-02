#!/bin/bash
# Render the app's own content view with sample data; never reads the personal shelf or pairing key.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="$ROOT/dist/MacDroid.app"
PREVIEW="$ROOT/dist/MacDroid-Screenshots.app"
OUTPUT="${1:-$ROOT/artifacts/macos-ui/shelf.png}"
if [[ ! -d "$SOURCE" ]]; then echo 'Build the Mac app first.' >&2; exit 1; fi
ditto "$SOURCE" "$PREVIEW"
/usr/libexec/PlistBuddy -c 'Set :CFBundleIdentifier dev.macdroid.screenshots' "$PREVIEW/Contents/Info.plist"
codesign --force --sign - "$PREVIEW"
TEMP_OUTPUT="$OUTPUT.rendering-$$.png"
trap 'rm -f "$TEMP_OUTPUT"' EXIT
open -n -a "$PREVIEW" --env "MACDROID_SCREENSHOT_OUTPUT=$TEMP_OUTPUT" --env "MACDROID_SCREENSHOT_APPEARANCE=${MACDROID_SCREENSHOT_APPEARANCE:-light}"
for ((attempt = 0; attempt < 60; attempt++)); do
    if [[ -s "$TEMP_OUTPUT" ]]; then mv "$TEMP_OUTPUT" "$OUTPUT"; echo "Captured: $OUTPUT"; exit 0; fi
    sleep 0.25
done
echo 'The sample view did not render. Check the screenshot app window.' >&2
exit 1
