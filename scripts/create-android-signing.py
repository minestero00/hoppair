#!/usr/bin/env python3
"""Create a persistent Android release key outside the repository. Never overwrite it."""
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
directory = Path.home() / ".config/hoppair/signing"
legacy = Path.home() / ".config/macdroid/signing"
if not (directory / "android.json").exists() and (legacy / "android.json").exists():
    directory = legacy
directory.mkdir(parents=True, exist_ok=True, mode=0o700)
config = directory / "android.json"
keystore = directory / "android-release.p12"
if config.exists() or keystore.exists():
    if config.exists() and keystore.exists():
        print(f"Existing signing configuration: {config}")
        raise SystemExit(0)
    raise SystemExit("Incomplete signing files exist. Resolve them manually; nothing was overwritten.")
password = secrets.token_urlsafe(36)
# Credentials remain in the child environment, never command arguments or logs.
environment = {**os.environ, "HOPPAIR_KEY_PASSWORD": password}
subprocess.run([
    "keytool", "-genkeypair", "-alias", "hoppair-release", "-keyalg", "RSA", "-keysize", "3072",
    "-validity", "10000", "-dname", "CN=Hoppair Release", "-storetype", "PKCS12",
    "-keystore", str(keystore), "-storepass:env", "HOPPAIR_KEY_PASSWORD",
    "-keypass:env", "HOPPAIR_KEY_PASSWORD",
], env=environment, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
os.chmod(keystore, 0o600)
value = {"storeFile": str(keystore), "storePassword": password, "keyAlias": "hoppair-release", "keyPassword": password}
with tempfile.NamedTemporaryFile(mode="w", dir=directory, delete=False) as output:
    json.dump(value, output)
    temporary = Path(output.name)
os.chmod(temporary, 0o600)
os.replace(temporary, config)
print(f"Signing configuration created: {config}")
print("Back up the configuration and keystore together. Future updates require this key.")
