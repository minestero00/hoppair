#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/apps/android"
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
mkdir -p "$ROOT/dist"
cp app/build/outputs/apk/debug/app-debug.apk "$ROOT/dist/Hoppair-android-debug.apk"
