#!/usr/bin/env python3
"""Check data preservation between same-key debug APKs on a disposable emulator."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', default='emulator-5580')
parser.add_argument('--before', type=Path, required=True)
parser.add_argument('--after', type=Path, required=True, help='Same-package, same-debug-key APK with a higher versionCode')
args = parser.parse_args()
if not re.fullmatch(r'emulator-[0-9]+', args.serial):
    parser.error('Use a disposable emulator. Failed fixtures may leave test data for inspection.')
for path in [args.before, args.after]:
    if not path.is_file():
        parser.error(f'APK not found: {path}')
out = root / 'artifacts/android-upgrade'
out.mkdir(parents=True, exist_ok=True)
for name in ['prepare.log', 'restore.log', 'receipt.json']:
    (out / name).unlink(missing_ok=True)
adb = ['adb', '-s', args.serial]
package = 'dev.macdroid.app'

def run(*arguments):
    result = subprocess.run([*adb, *arguments], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(result.stdout)
    return result.stdout

if run('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
    parser.error('The selected device is not an emulator.')
with socket.socket() as probe:
    probe.bind(('127.0.0.1', 49273))  # Keep the test outbox offline throughout both phases.
    with tempfile.TemporaryDirectory(prefix='hoppair-upgrade-apks-') as temporary:
        files = {}
        for name, source in [('before', args.before), ('after', args.after)]:
            files[name] = Path(temporary) / (name + '.apk')
            shutil.copyfile(source, files[name])  # Build outputs may overwrite the caller's APK paths.
        with (out / 'build.log').open('w') as log:
            subprocess.run(['./gradlew', ':app:assembleDebug', ':app:assembleDebugAndroidTest', '--console=plain'],
                           cwd=root / 'apps/android', stdout=log, stderr=subprocess.STDOUT, check=True)
        apk_root = root / 'apps/android/app/build/outputs/apk'
        try:
            run('shell', 'am', 'force-stop', package)
            (out / 'install-before.log').write_text(run('install', '-r', '-d', str(files['before'])))
            run('install', '-r', str(apk_root / 'androidTest/debug/app-debug-androidTest.apk'))
            for phase in ['prepare', 'restore']:
                if phase == 'restore':
                    run('shell', 'am', 'force-stop', package)
                    (out / 'install-after.log').write_text(run('install', '-r', str(files['after'])))
                result = subprocess.run([*adb, 'shell', 'am', 'instrument', '-w', '-e', 'upgradeFixture', 'true',
                    '-e', 'phase', phase, '-e', 'class', 'dev.macdroid.app.UpgradePreservationTest',
                    package + '.test/androidx.test.runner.AndroidJUnitRunner'], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                (out / (phase + '.log')).write_text(result.stdout)
                print(result.stdout, flush=True)
                result.check_returncode()
                if 'OK (1 test)' not in result.stdout or 'FAILURES' in result.stdout:
                    raise RuntimeError(f'{phase} failed; inspect artifacts/android-upgrade/')
            (out / 'receipt.json').write_text(json.dumps({
                'apk_sha256': {name: hashlib.sha256(file.read_bytes()).hexdigest() for name, file in files.items()},
                'scope': 'adb in-place replacement of debug APKs; not the release updater or Android installer UI',
                'passed': True,
            }, indent=2) + '\n')
        finally:
            run('shell', 'am', 'force-stop', package)
            # Restore the current development binary, retaining app data and Keystore identity.
            (out / 'restore-app.log').write_text(run('install', '-r', '-d', str(apk_root / 'debug/app-debug.apk')))
