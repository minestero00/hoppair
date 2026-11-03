#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG="${HOPPAIR_ANDROID_SIGNING_FILE:-${MACDROID_ANDROID_SIGNING_FILE:-$(python3 -c 'from pathlib import Path; base=Path.home()/".config"; current=base/"hoppair/signing/android.json"; legacy=base/"macdroid/signing/android.json"; print(current if current.exists() or not legacy.exists() else legacy)')}}"
if [[ ! -f "$CONFIG" ]]; then
    echo 'Create your release key first: python3 scripts/create-android-signing.py' >&2
    exit 1
fi
cd "$ROOT/apps/android"
HOPPAIR_ANDROID_SIGNING_FILE="$CONFIG" ./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease --console=plain
mkdir -p "$ROOT/dist"
cp app/build/outputs/apk/release/app-release.apk "$ROOT/dist/Hoppair-android-release.apk"
