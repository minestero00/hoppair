#!/bin/bash
# End-to-end protocol test against a local Android emulator, never a physical phone.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERIAL="${1:-emulator-5580}"
MODE="${2:-local}"
RELAY_MODE=0
if [[ "$MODE" == relay ]]; then RELAY_MODE=1; fi
PREFIX="peer-$MODE"
case "$SERIAL" in emulator-*) ;; *) echo 'Use an Android emulator serial.' >&2; exit 1;; esac
mkdir -p "$ROOT/artifacts"
cd "$ROOT/apps/android"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain > "$ROOT/artifacts/$PREFIX-android-build.log" 2>&1
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk > "$ROOT/artifacts/$PREFIX-install.log"
adb -s "$SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >> "$ROOT/artifacts/$PREFIX-install.log"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/macdroid-peer.XXXXXX")"
READY="$WORK_DIR/ready"
HOST_PID=''
cleanup() {
    if [[ -n "$HOST_PID" ]]; then kill "$HOST_PID" 2>/dev/null || true; fi
    rm -f "$READY"
    rmdir "$WORK_DIR"
}
trap cleanup EXIT
MACDROID_ANDROID_PEER=1 MACDROID_RELAY_PEER="$RELAY_MODE" MACDROID_PEER_READY="$READY" swift test --package-path "$ROOT/apps/macos" --filter AndroidPeerTests > "$ROOT/artifacts/$PREFIX-swift.log" 2>&1 &
HOST_PID=$!
for ((attempt = 0; attempt < 60; attempt++)); do
    if [[ -f "$READY" ]]; then break; fi
    if ! kill -0 "$HOST_PID" 2>/dev/null; then cat "$ROOT/artifacts/$PREFIX-swift.log"; exit 1; fi
    sleep 1
done
if [[ ! -f "$READY" ]]; then echo 'Swift peer did not become ready.' >&2; exit 1; fi
adb -s "$SERIAL" shell am instrument -w -e relay "$([[ "$RELAY_MODE" == 1 ]] && echo true || echo false)" -e class dev.macdroid.app.PeerIntegrationTest,dev.macdroid.app.RepositoryTest dev.macdroid.app.test/androidx.test.runner.AndroidJUnitRunner > "$ROOT/artifacts/$PREFIX-android.log"
cat "$ROOT/artifacts/$PREFIX-android.log"
if ! rg -q 'OK \(2 tests\)' "$ROOT/artifacts/$PREFIX-android.log"; then exit 1; fi
wait "$HOST_PID"
HOST_PID=''
cat "$ROOT/artifacts/$PREFIX-swift.log"
