#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERIAL="${1:-emulator-5580}"
case "$SERIAL" in emulator-*) ;; *) echo 'Use a disposable Android emulator.' >&2; exit 1;; esac
mkdir -p "$ROOT/artifacts/android-ui"
node "$ROOT/scripts/fixtures/android-ui-peer.mjs" > "$ROOT/artifacts/android-ui-peer.log" 2>&1 &
PEER_PID=$!
trap 'kill "$PEER_PID" 2>/dev/null || true' EXIT
for ((attempt = 0; attempt < 60; attempt++)); do
    if ! kill -0 "$PEER_PID" 2>/dev/null; then
        echo 'Could not start the UI fixture peer. Check artifacts/android-ui-peer.log; port 49273 must be free.' >&2
        exit 1
    fi
    if rg -q 'fixture peer ready' "$ROOT/artifacts/android-ui-peer.log"; then break; fi
    sleep 0.2
done
if ! rg -q 'fixture peer ready' "$ROOT/artifacts/android-ui-peer.log"; then echo 'UI fixture peer did not become ready.' >&2; exit 1; fi
cd "$ROOT/apps/android"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain > "$ROOT/artifacts/android-ui-build.log" 2>&1
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$SERIAL" shell am instrument -w -e uiFixture true -e class dev.macdroid.app.ShelfUiTest dev.macdroid.app.test/androidx.test.runner.AndroidJUnitRunner > "$ROOT/artifacts/android-ui-test.log"
cat "$ROOT/artifacts/android-ui-test.log"
if ! rg -q 'OK \(1 test\)' "$ROOT/artifacts/android-ui-test.log"; then exit 1; fi
adb -s "$SERIAL" pull /sdcard/Android/data/dev.macdroid.app/files/ui-review/ "$ROOT/artifacts/android-ui/"
