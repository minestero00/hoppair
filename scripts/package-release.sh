#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${1:?Usage: scripts/package-release.sh <version>}"
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$ ]]; then
    echo 'Use a semantic version, such as 0.1.1.' >&2
    exit 1
fi
"$ROOT/scripts/build-mac.sh"
python3 - "$ROOT/dist/Hoppair.app/Contents/Info.plist" "$VERSION" <<'PY'
import plistlib,sys
actual=plistlib.load(open(sys.argv[1],'rb'))['CFBundleShortVersionString']
if actual != sys.argv[2]:
    raise SystemExit(f'Requested {sys.argv[2]}, but the Mac build is {actual}. Match the release argument and app versions before packaging.')
PY
NOTARY_PROFILE="${HOPPAIR_NOTARY_PROFILE:-${MACDROID_NOTARY_PROFILE:-}}"
if [[ -n "$NOTARY_PROFILE" ]]; then
    "$ROOT/scripts/notarize-mac.sh" "$NOTARY_PROFILE"
fi
"$ROOT/scripts/build-android-release.sh"
python3 - "$ROOT/dist/Hoppair-android-release.apk" "$VERSION" <<'PY'
import os,re,subprocess,sys
from pathlib import Path
sdk=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Library/Android/sdk')))
tools=sorted((sdk/'build-tools').glob('*'),key=lambda path:tuple(int(n) for n in re.findall(r'\d+',path.name)))
if not tools: raise SystemExit('Set ANDROID_HOME to an Android SDK with build-tools.')
badging=subprocess.check_output([str(tools[-1]/'aapt'),'dump','badging',sys.argv[1]],text=True)
match=re.search(r"package: .*?versionName='([^']+)'",badging)
if not match or match[1] != sys.argv[2]:
    raise SystemExit('The Android version does not match the requested release. Match both app versions before packaging.')
PY
OUTPUT="$ROOT/dist/release/$VERSION"
mkdir -p "$OUTPUT"
ARCH="$(uname -m)"
ditto -c -k --sequesterRsrc --keepParent "$ROOT/dist/Hoppair.app" "$OUTPUT/Hoppair-$VERSION-macos-$ARCH.zip"
cp "$ROOT/dist/Hoppair-android-release.apk" "$OUTPUT/Hoppair-$VERSION-android.apk"
cd "$OUTPUT"
shasum -a 256 ./*.zip ./*.apk > SHA256SUMS.txt
printf '\nRelease files: %s\n' "$OUTPUT"
