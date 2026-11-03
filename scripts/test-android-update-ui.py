#!/usr/bin/env python3
"""Check updater controls at 200% text on a narrow disposable Android emulator."""
import argparse
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', default='emulator-5580')
args = parser.parse_args()
if not re.fullmatch(r'emulator-[0-9]+', args.serial):
    parser.error('Use a disposable emulator, never a physical phone.')
artifacts = ROOT / 'artifacts/update-ui'
artifacts.mkdir(parents=True, exist_ok=True)
adb = ['adb', '-s', args.serial]
names = [f'{stage}-{theme}.png' for stage in ['available', 'retry', 'install'] for theme in ['light', 'dark']]
for name in names:
    (artifacts / 'update-review' / name).unlink(missing_ok=True)

def shell(*command):
    return subprocess.check_output([*adb, 'shell', *command], text=True).strip()

with (artifacts / 'build.log').open('w') as log:
    subprocess.run(['./gradlew', ':app:assembleDebug', ':app:assembleDebugAndroidTest', '--console=plain'],
                   cwd=ROOT / 'apps/android', stdout=log, stderr=subprocess.STDOUT, check=True)
for relative in ['debug/app-debug.apk', 'androidTest/debug/app-debug-androidTest.apk']:
    subprocess.run([*adb, 'install', '-r', str(ROOT / 'apps/android/app/build/outputs/apk' / relative)], check=True)
font = shell('settings', 'get', 'system', 'font_scale')
density = shell('wm', 'density')
previous = re.search(r'Override density: (\d+)', density)
size = shell('wm', 'size')
width = int(re.findall(r'(?:Physical|Override) size: (\d+)x\d+', size)[-1])
# 320dp wide, independent of the emulator's pixel dimensions.
target_density = str(round(width * 160 / 320))
try:
    shell('rm', '-f', *['/sdcard/Android/data/dev.macdroid.app/files/update-review/' + name for name in names])
    shell('settings', 'put', 'system', 'font_scale', '2.0')
    shell('wm', 'density', target_density)
    command = [*adb, 'shell', 'am', 'instrument', '-w', '-e', 'largeTextFixture', 'true', '-e', 'class',
               'dev.macdroid.app.UpdateUiTest#largeTextUpdateActionsRemainReachable',
               'dev.macdroid.app.test/androidx.test.runner.AndroidJUnitRunner']
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (artifacts / 'test.log').write_text(result.stdout)
    print(result.stdout)
    result.check_returncode()
    if 'OK (1 test)' not in result.stdout or 'FAILURES' in result.stdout:
        raise RuntimeError('Updater UI check failed; inspect artifacts/update-ui/test.log')
    subprocess.run([*adb, 'pull', '/sdcard/Android/data/dev.macdroid.app/files/update-review/', str(artifacts)], check=True)
finally:
    # Both restore attempts run even if one command fails.
    failures = []
    for command in [('settings', 'delete', 'system', 'font_scale') if font == 'null' else
                    ('settings', 'put', 'system', 'font_scale', font),
                    ('wm', 'density', previous.group(1) if previous else 'reset')]:
        try:
            shell(*command)
        except subprocess.CalledProcessError as error:
            failures.append(error)
    if failures:
        raise RuntimeError('Could not restore emulator display settings; inspect the emulator.') from failures[0]
