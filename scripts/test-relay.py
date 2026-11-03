#!/usr/bin/env python3
"""Start an isolated local Worker, then run server and Android/Swift relay tests."""
import argparse
import hashlib
import hmac
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import time
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument("--serial", default="emulator-5580")
parser.add_argument("--protocol-only", action="store_true")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
artifacts = root / "artifacts"
artifacts.mkdir(exist_ok=True)
with socket.socket() as check:
    try:
        check.bind(("127.0.0.1", 8787))
    except OSError:
        raise SystemExit("Port 8787 is busy. Stop your existing local relay before running this gate.")
# Public deterministic fixtures, never an actual device's key or registration.
import base64
auth = base64.urlsafe_b64encode(hmac.digest(bytes([7]) * 32, b"macdroid-relay-auth-v1", "sha256")).decode().rstrip("=")
rooms = {
    "cd0ab698-24ee-465f-e4d6-ef293b3cd2cd": hashlib.sha256(auth.encode()).hexdigest(),
    "11111111-1111-1111-1111-111111111111": hashlib.sha256(("A" * 43).encode()).hexdigest(),
}
environment = {**os.environ, "WRANGLER_SEND_METRICS": "false"}
subprocess.run(["npm", "ci"], cwd=root / "services/relay", env=environment, check=True)
subprocess.run(["npm", "run", "check"], cwd=root / "services/relay", env=environment, check=True)
with (artifacts / "relay-worker.log").open("w") as log:
    process = subprocess.Popen([str(root / "services/relay/node_modules/.bin/wrangler"), "dev", "--local", "--ip", "127.0.0.1", "--port", "8787", "--var", "ROOM_CREDENTIALS:" + json.dumps(rooms, separators=(",", ":"))], cwd=root / "services/relay", env=environment, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        for attempt in range(60):
            if process.poll() is not None:
                raise RuntimeError("Local relay exited; see artifacts/relay-worker.log")
            try:
                with urllib.request.urlopen("http://127.0.0.1:8787/health", timeout=0.5) as response:
                    if json.load(response).get("service") == "hoppair-relay":
                        break
            except (OSError, ValueError):
                time.sleep(1)
        else:
            raise RuntimeError("Local relay did not become ready.")
        subprocess.run(["npm", "test"], cwd=root / "services/relay", env=environment, check=True)
        if not args.protocol_only:
            subprocess.run([str(root / "scripts/test-peer.sh"), args.serial, "relay"], cwd=root, env=environment, check=True)
    finally:
        os.killpg(process.pid, signal.SIGINT)
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGTERM)
            process.wait(timeout=5)
