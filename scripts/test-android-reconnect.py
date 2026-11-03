#!/usr/bin/env python3
"""Verify saved pairing across two fresh processes on a disposable Android emulator."""
import argparse
from pathlib import Path
import socket
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", default="emulator-5580")
args = parser.parse_args()
if not args.serial.startswith("emulator-"):
    raise SystemExit("Use a disposable Android emulator.")

root = Path(__file__).resolve().parents[1]
artifacts = root / "artifacts"
artifacts.mkdir(exist_ok=True)
with socket.socket() as probe:
    probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        probe.bind(("127.0.0.1", 49273))
    except OSError:
        raise SystemExit("Port 49273 is busy; the fixture must own its listener.")

subprocess.run(["./gradlew", ":app:assembleDebug", ":app:assembleDebugAndroidTest", "--console=plain"],
               cwd=root / "apps/android", check=True)
for apk in ["debug/app-debug.apk", "androidTest/debug/app-debug-androidTest.apk"]:
    subprocess.run(["adb", "-s", args.serial, "install", "-r",
                    str(root / "apps/android/app/build/outputs/apk" / apk)], check=True)

log_path = artifacts / "reconnect-fixture-peer.log"
with log_path.open("w") as log:
    peer = subprocess.Popen(["node", str(root / "scripts/fixtures/android-ui-peer.mjs")],
                            cwd=root, stdout=log, stderr=subprocess.STDOUT)
    try:
        for _ in range(50):
            if peer.poll() is not None:
                raise RuntimeError("Fixture did not start; see artifacts/reconnect-fixture-peer.log.")
            if "fixture peer ready" in log_path.read_text():
                break
            time.sleep(0.2)
        else:
            raise RuntimeError("Fixture did not become ready.")
        for phase in ["prepare", "restore"]:
            subprocess.run(["adb", "-s", args.serial, "shell", "am", "force-stop", "dev.macdroid.app"], check=True)
            result = subprocess.run([
                "adb", "-s", args.serial, "shell", "am", "instrument", "-w",
                "-e", "reconnectFixture", "true", "-e", "phase", phase,
                "-e", "class", "dev.macdroid.app.ReconnectTest",
                "dev.macdroid.app.test/androidx.test.runner.AndroidJUnitRunner",
            ], capture_output=True, text=True, timeout=60, check=True)
            (artifacts / f"reconnect-{phase}.log").write_text(result.stdout + result.stderr)
            if "OK (1 test)" not in result.stdout:
                raise RuntimeError(f"Reconnect {phase} failed; see artifacts/reconnect-{phase}.log.")
            print(f"{phase}: passed", flush=True)
    finally:
        peer.terminate()
        peer.wait(timeout=10)
