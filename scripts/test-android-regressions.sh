#!/bin/bash
# Run isolated storage, transport, pairing, decoder, and search checks. No real peer required.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERIAL="${1:-emulator-5580}"
case "$SERIAL" in emulator-*) ;; *) echo 'Use a disposable Android emulator.' >&2; exit 1;; esac
mkdir -p "$ROOT/artifacts"
cd "$ROOT/apps/android"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain > "$ROOT/artifacts/android-regression-build.log" 2>&1
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
CLASSES=(ConnectionSecurityTest SyncRecoveryTest SyncFlowTest PairingRecoveryTest ShelfImageTest ShelfSearchTest QrScannerTest)
SELECTOR=""
for test_class in "${CLASSES[@]}"; do
    SELECTOR="${SELECTOR:+$SELECTOR,}dev.macdroid.app.$test_class"
done
adb -s "$SERIAL" shell am instrument -w -e class "$SELECTOR" dev.macdroid.app.test/androidx.test.runner.AndroidJUnitRunner > "$ROOT/artifacts/android-regression-test.log"
cat "$ROOT/artifacts/android-regression-test.log"
# Android's instrumentation command can exit zero even when its tests fail.
# Update this count when adding/removing selected tests; a partial suite must not pass.
rg -q '^OK \(44 tests\)$' "$ROOT/artifacts/android-regression-test.log"
for test_class in "${CLASSES[@]}"; do
    rg -q "^dev[.]macdroid[.]app[.]$test_class:" "$ROOT/artifacts/android-regression-test.log"
done
