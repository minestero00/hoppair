#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${1:?Usage: scripts/package-release.sh 0.1.0-alpha.1}"
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$ ]]; then
    echo 'Use a semantic version, such as 0.1.0-alpha.1.' >&2
    exit 1
fi
"$ROOT/scripts/build-mac.sh"
if [[ -n "${MACDROID_NOTARY_PROFILE:-}" ]]; then
    "$ROOT/scripts/notarize-mac.sh" "$MACDROID_NOTARY_PROFILE"
fi
"$ROOT/scripts/build-android-release.sh"
OUTPUT="$ROOT/dist/release/$VERSION"
mkdir -p "$OUTPUT"
ARCH="$(uname -m)"
ditto -c -k --sequesterRsrc --keepParent "$ROOT/dist/MacDroid.app" "$OUTPUT/MacDroid-$VERSION-macos-$ARCH.zip"
cp "$ROOT/dist/MacDroid-android-release.apk" "$OUTPUT/MacDroid-$VERSION-android.apk"
cd "$OUTPUT"
shasum -a 256 ./*.zip ./*.apk > SHA256SUMS.txt
printf '\nRelease files: %s\n' "$OUTPUT"
