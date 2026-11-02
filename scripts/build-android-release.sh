#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG="${MACDROID_ANDROID_SIGNING_FILE:-$(python3 -c 'from pathlib import Path; print(Path.home() / ".config/macdroid/signing/android.json")')}"
if [[ ! -f "$CONFIG" ]]; then
    echo 'Create your release key first: python3 scripts/create-android-signing.py' >&2
    exit 1
fi
cd "$ROOT/apps/android"
MACDROID_ANDROID_SIGNING_FILE="$CONFIG" ./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease --console=plain
mkdir -p "$ROOT/dist"
cp app/build/outputs/apk/release/app-release.apk "$ROOT/dist/MacDroid-android-release.apk"
