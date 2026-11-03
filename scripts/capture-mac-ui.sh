#!/bin/bash
# Render the app's own content view with sample data; never reads the personal shelf or pairing key.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="${HOPPAIR_APP_SOURCE:-$ROOT/dist/Hoppair.app}"
PREVIEW="$ROOT/dist/Hoppair-Screenshots.app"
OUTPUT="${1:-$ROOT/artifacts/macos-ui/shelf.png}"
OUTPUT="$(python3 -c 'from pathlib import Path; import sys; print(Path(sys.argv[1]).resolve())' "$OUTPUT")"
python3 - "$PREVIEW/Contents/MacOS/Hoppair" <<'PYCODE'
import subprocess,sys
for line in subprocess.check_output(['ps','-axo','pid=,comm='],text=True).splitlines():
    parts=line.strip().split(None,1)
    if len(parts)==2 and parts[1]==sys.argv[1]:
        raise SystemExit('Quit the screenshot app before replacing its bundle and capturing again.')
PYCODE
if [[ ! -d "$SOURCE" ]]; then echo 'Build the Mac app first.' >&2; exit 1; fi
WORK_DIR="$(mktemp -d "$ROOT/dist/.hoppair-capture.XXXXXX")"
STAGED="$WORK_DIR/Hoppair-Screenshots.app"
TEMP_OUTPUT="$OUTPUT.rendering-$$.png"
trap 'rm -f "$TEMP_OUTPUT"; rm -rf "$WORK_DIR"' EXIT
mkdir -p "$(dirname "$OUTPUT")"
ditto "$SOURCE" "$STAGED"
/usr/libexec/PlistBuddy -c 'Set :CFBundleIdentifier dev.hoppair.screenshots' "$STAGED/Contents/Info.plist"
# The isolated, ad-hoc preview has no Team ID. Clear inherited hardened-runtime
# flags so its Developer ID-signed dependency can load; production is untouched.
codesign --force --options 0x0 --sign - "$STAGED"
codesign --verify --deep --strict "$STAGED"
python3 - "$STAGED" "$PREVIEW" "$WORK_DIR/previous.app" <<'PYCODE'
import os,subprocess,sys
from pathlib import Path
staged,final,previous=map(Path,sys.argv[1:])
for line in subprocess.check_output(['ps','-axo','pid=,comm='],text=True).splitlines():
    parts=line.strip().split(None,1)
    if len(parts)==2 and parts[1]==str(final/'Contents/MacOS/Hoppair'):
        raise SystemExit('Quit the screenshot app before replacing its bundle.')
if final.exists(): os.replace(final,previous)
try:
    os.replace(staged,final)
except BaseException:
    if previous.exists(): os.replace(previous,final)
    raise
PYCODE
open -n -a "$PREVIEW" --env "HOPPAIR_SCREENSHOT_OUTPUT=$TEMP_OUTPUT" --env "HOPPAIR_SCREENSHOT_APPEARANCE=${HOPPAIR_SCREENSHOT_APPEARANCE:-light}" --env "HOPPAIR_SCREENSHOT_SIZE=${HOPPAIR_SCREENSHOT_SIZE:-1100x740}" --env "HOPPAIR_SCREENSHOT_SCENE=${HOPPAIR_SCREENSHOT_SCENE:-shelf}" --env "HOPPAIR_SCREENSHOT_EXIT=${HOPPAIR_SCREENSHOT_EXIT:-0}" --env "HOPPAIR_SCREENSHOT_OPAQUE=${HOPPAIR_SCREENSHOT_OPAQUE:-0}"
for ((attempt = 0; attempt < 60; attempt++)); do
    if [[ -s "$TEMP_OUTPUT" ]]; then mv "$TEMP_OUTPUT" "$OUTPUT"; echo "Captured: $OUTPUT"; exit 0; fi
    sleep 0.25
done
echo 'The sample view did not render. Check the screenshot app window.' >&2
exit 1
