#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ARCHIVE="${1:-$ROOT/dist/Hoppair.xcarchive}"
if [[ -e "$ARCHIVE" ]]; then
    echo 'Archive already exists. Move it or choose another output path.' >&2
    exit 1
fi
"$ROOT/scripts/build-mac.sh"
python3 - "$ROOT" "$ARCHIVE" <<'PY'
import datetime
import os
import plistlib
import shutil
import subprocess
import sys
from pathlib import Path

root = Path(sys.argv[1])
source = Path(os.environ.get('HOPPAIR_OUTPUT_DIR', str(root / 'dist'))) / 'Hoppair.app'
archive = Path(sys.argv[2])
destination = archive / 'Products/Applications/Hoppair.app'
if archive.exists():
    raise SystemExit('Archive already exists. Move it or choose another output path.')
destination.parent.mkdir(parents=True)
shutil.copytree(source, destination)
with (source / 'Contents/Info.plist').open('rb') as file:
    app = plistlib.load(file)
info = {
    'ArchiveVersion': 2,
    'CreationDate': datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None),
    'Name': 'Hoppair',
    'SchemeName': 'Hoppair',
    'ApplicationProperties': {
        'ApplicationPath': 'Applications/Hoppair.app',
        **{key: app[key] for key in ('CFBundleIdentifier', 'CFBundleShortVersionString', 'CFBundleVersion')},
    },
}
signature = subprocess.run(['codesign', '-dvv', str(source)], capture_output=True, text=True, check=True).stderr
for line in signature.splitlines():
    if line.startswith('Authority=') and 'SigningIdentity' not in info['ApplicationProperties']:
        info['ApplicationProperties']['SigningIdentity'] = line.removeprefix('Authority=')
    elif line.startswith('TeamIdentifier=') and not line.endswith('not set'):
        info['ApplicationProperties']['Team'] = line.removeprefix('TeamIdentifier=')
with (archive / 'Info.plist').open('wb') as file:
    plistlib.dump(info, file)
print(f'Archive: {archive}')
PY
