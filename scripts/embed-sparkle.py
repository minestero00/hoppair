#!/usr/bin/env python3
"""Embed the pinned Sparkle binary and sign nested code before the host app."""
import argparse
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('app', type=Path)
parser.add_argument('identity')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
source = root / 'apps/macos/.build/artifacts/sparkle/Sparkle/Sparkle.xcframework/macos-arm64_x86_64/Sparkle.framework'
if not source.is_dir():
    raise SystemExit('Resolve/build the pinned Swift package before embedding Sparkle.')
framework = args.app / 'Contents/Frameworks/Sparkle.framework'
framework.parent.mkdir(parents=True, exist_ok=True)
subprocess.run(['ditto', str(source), str(framework)], check=True)

# Development/build-machine search paths must never precede the bundled framework.
executable = args.app / 'Contents/MacOS/Hoppair'
loads = subprocess.check_output(['otool', '-l', str(executable)], text=True)
for path in re.findall(r'cmd LC_RPATH\n\s+cmdsize \d+\n\s+path (.*?) \(offset', loads):
    if path not in ('@executable_path/../Frameworks', '@loader_path/../Frameworks', '/usr/lib/swift'):
        subprocess.run(['install_name_tool', '-delete_rpath', path, str(executable)], check=True)
loads = subprocess.check_output(['otool', '-l', str(executable)], text=True)
if 'path @executable_path/../Frameworks (offset' not in loads:
    raise SystemExit('The executable must contain the bundled Frameworks runpath.')

base = framework / 'Versions/B'
# Sign from the leaves inward; do not use codesign --deep to repair signatures.
for item in [base / 'XPCServices/Downloader.xpc', base / 'XPCServices/Installer.xpc',
             base / 'Autoupdate', base / 'Updater.app', framework]:
    if not item.exists():
        raise SystemExit(f'Sparkle layout changed: missing {item.relative_to(framework)}')
    command = ['codesign', '--force', '--sign', args.identity]
    if args.identity != '-':
        command += ['--options', 'runtime', '--timestamp', '--preserve-metadata=entitlements,identifier']
    subprocess.run(command + [str(item)], check=True)
subprocess.run(['codesign', '--verify', '--deep', '--strict', str(framework)], check=True)
licenses = args.app / 'Contents/Resources/Licenses'
licenses.mkdir(parents=True, exist_ok=True)
(licenses / 'Sparkle.txt').write_bytes((source.parents[2] / 'LICENSE').read_bytes())
