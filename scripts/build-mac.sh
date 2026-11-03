#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FINAL_APP="$ROOT/dist/MacDroid.app"
ensure_app_stopped() {
    python3 - "$FINAL_APP/Contents/MacOS/MacDroid" <<'PYCODE'
import subprocess,sys
executable=sys.argv[1]
for line in subprocess.check_output(['ps','-axo','pid=,comm='],text=True).splitlines():
    parts=line.strip().split(None,1)
    if len(parts)==2 and parts[1]==executable:
        raise SystemExit('Quit MacDroid before rebuilding. The running app will not be overwritten.')
PYCODE
}
ensure_app_stopped
swift build --package-path "$ROOT/apps/macos" -c release
BIN="$(swift build --package-path "$ROOT/apps/macos" -c release --show-bin-path)"
mkdir -p "$ROOT/dist"
WORK_DIR="$(mktemp -d "$ROOT/dist/.macdroid-build.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT
APP="$WORK_DIR/MacDroid.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN/MacDroid" "$APP/Contents/MacOS/MacDroid"
python3 "$ROOT/scripts/build-icons.py"
cp "$ROOT/dist/CompiledIcons/AppIcon.icns" "$APP/Contents/Resources/AppIcon.icns"
cp "$ROOT/dist/CompiledIcons/Assets.car" "$APP/Contents/Resources/Assets.car"
sips -z 256 256 "$ROOT/assets/app-icon/AppIcon.icon/Assets/Artwork.png" --out "$APP/Contents/Resources/BrandIcon.png" >/dev/null
cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleExecutable</key><string>MacDroid</string>
<key>CFBundleIdentifier</key><string>dev.macdroid.mac</string>
<key>CFBundleIconName</key><string>AppIcon</string>
<key>CFBundleIconFile</key><string>AppIcon</string>
<key>CFBundleName</key><string>MacDroid</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>0.1.0</string>
<key>CFBundleVersion</key><string>4</string>
<key>LSMinimumSystemVersion</key><string>14.0</string>
<key>LSApplicationCategoryType</key><string>public.app-category.productivity</string>
<key>NSHighResolutionCapable</key><true/>
<key>NSLocalNetworkUsageDescription</key><string>Exchange files, text, and photo requests with your Android device.</string>
</dict></plist>
PLIST
if [[ -n "${MACDROID_RELAY_URL:-}" ]]; then
    /usr/libexec/PlistBuddy -c "Add :MacDroidRelayURL string $MACDROID_RELAY_URL" "$APP/Contents/Info.plist"
fi
mkdir -p "$APP/Contents/Resources/Licenses"
cp "$ROOT/LICENSE" "$APP/Contents/Resources/Licenses/MIT.txt"
cp "$ROOT/NOTICE" "$APP/Contents/Resources/Licenses/NOTICE.txt"
SIGNING_IDENTITY="${MACDROID_SIGN_IDENTITY:--}"
if [[ "$SIGNING_IDENTITY" == - ]]; then
    codesign --force --sign - "$APP"
else
    codesign --force --options runtime --timestamp --sign "$SIGNING_IDENTITY" "$APP"
fi
codesign --verify --deep --strict "$APP"
ensure_app_stopped
python3 - "$APP" "$FINAL_APP" "$WORK_DIR/previous.app" <<'PYCODE'
import os,sys
from pathlib import Path
staged,final,previous=map(Path,sys.argv[1:])
if final.exists(): os.replace(final,previous)
try:
    os.replace(staged,final)
except BaseException:
    if previous.exists(): os.replace(previous,final)
    raise
PYCODE
printf '\nBuilt %s\n' "$FINAL_APP"
