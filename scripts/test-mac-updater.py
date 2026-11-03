#!/usr/bin/env python3
"""Exercise Sparkle install/relaunch, cancellation and signed-feed rejection in disposable apps."""
import argparse
import functools
import http.server
import json
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import tempfile
import threading
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NS = 'http://www.andymatuschak.org/xml-namespaces/sparkle'


def run(*args):
    return subprocess.check_output([str(arg) for arg in args], stderr=subprocess.STDOUT, text=True).strip()


class Server(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def copyfile(self, source, output):
        # Keep a real download in flight long enough for deterministic cancellation.
        try:
            while chunk := source.read(16384):
                output.write(chunk)
                if self.path.endswith('.zip'):
                    time.sleep(0.003)
        except (BrokenPipeError, ConnectionResetError):
            pass


def scenario(work, mode, source, binary, identity, account):
    directory = work / mode
    directory.mkdir()
    identifier = 'dev.hoppair.updater-test.' + uuid.uuid4().hex
    handler = functools.partial(Server, directory=str(directory))
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f'http://127.0.0.1:{server.server_port}'
    app = directory / 'Hoppair.app'
    update = directory / 'new/Hoppair.app'
    canary = directory / 'saved-data.txt'
    canary.write_text('Updater fixture data must survive installation.\n')
    try:
        for target, build in [(app, '1'), (update, '2')]:
            target.parent.mkdir(parents=True, exist_ok=True)
            run('ditto', source, target)
            shutil.copyfile(binary, target / 'Contents/MacOS/Hoppair')
            info = {
                'CFBundleExecutable': 'Hoppair', 'CFBundleIdentifier': identifier,
                'CFBundleName': 'Hoppair Updater Test', 'CFBundlePackageType': 'APPL',
                'CFBundleShortVersionString': '1.0.' + build, 'CFBundleVersion': build,
                'LSMinimumSystemVersion': '14.0', 'LSUIElement': True,
                'SUFeedURL': base + '/appcast.xml',
                'SUPublicEDKey': (ROOT / 'config/update-public-key.txt').read_text().strip(),
                'SUEnableAutomaticChecks': False, 'SUAutomaticallyUpdate': False,
                'SURequireSignedFeed': True, 'SUVerifyUpdateBeforeExtraction': True,
                'SUSignedFeedFailureExpirationInterval': 0, 'SUShowReleaseNotes': False,
                'NSAppTransportSecurity': {'NSAllowsLocalNetworking': True},
                'HoppairTestRoot': str(directory), 'HoppairTestMode': mode,
            }
            (target / 'Contents/Info.plist').write_bytes(plistlib.dumps(info))
            run('codesign', '--force', '--options', 'runtime', '--timestamp', '--sign', identity, target)
            run('codesign', '--verify', '--deep', '--strict', target)
        archive = directory / 'update.zip'
        run('ditto', '-c', '-k', '--sequesterRsrc', '--keepParent', update, archive)
        sign = ROOT / 'apps/macos/.build/artifacts/sparkle/Sparkle/bin/sign_update'
        key = run(sign.with_name('generate_keys'), '--account', account, '-p')
        assert key == (ROOT / 'config/update-public-key.txt').read_text().strip()
        signature = run(sign, '--account', account, '-p', archive)
        if mode == 'payload-tampered':
            with archive.open('ab') as altered:
                altered.write(b'changed after signing')
        ET.register_namespace('sparkle', NS)
        feed = ET.Element('rss', version='2.0')
        channel = ET.SubElement(feed, 'channel')
        ET.SubElement(channel, 'title').text = 'Disposable updater fixture'
        item = ET.SubElement(channel, 'item')
        ET.SubElement(item, 'title').text = 'Fixture build 2'
        ET.SubElement(item, f'{{{NS}}}minimumSystemVersion').text = '14.0'
        ET.SubElement(item, 'enclosure', {'url': base + '/update.zip', 'length': str(archive.stat().st_size),
            'type': 'application/octet-stream', f'{{{NS}}}version': '2', f'{{{NS}}}edSignature': signature})
        path = directory / 'appcast.xml'
        ET.ElementTree(feed).write(path, encoding='utf-8', xml_declaration=True)
        run(sign, '--account', account, path)
        run(sign, '--account', account, '--verify', path)
        if mode == 'tampered':
            path.write_bytes(path.read_bytes().replace(b'Fixture build 2', b'Altered build 2'))
        log = directory / 'process.log'
        with log.open('w') as output:
            process = subprocess.Popen([str(app / 'Contents/MacOS/Hoppair')], stdout=output, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 100
            rows = []
            while time.monotonic() < deadline:
                events = directory / 'events.jsonl'
                if events.exists():
                    try:
                        rows = [json.loads(line) for line in events.read_text().splitlines()]
                    except json.JSONDecodeError:
                        continue
                terminal = 'relaunched' if mode == 'success' else 'cancel-completed' if mode == 'cancel' else 'tamper-rejected'
                names = [row['event'] for row in rows]
                if terminal in names:
                    break
                if any(name in names for name in ['startup-error', 'unexpected-error', 'unexpected-no-update', 'unexpected-feed-acceptance']):
                    raise RuntimeError(f'{mode} failed; inspect {directory}')
                if process.poll() is not None and mode != 'success':
                    raise RuntimeError(f'{mode} fixture exited without expected result; inspect {directory}')
                time.sleep(0.1)
            else:
                raise TimeoutError(f'{mode} did not finish; inspect {directory}')
            if mode == 'success':
                assert all(name in names for name in ['found', 'download-started', 'installer-started', 'ready', 'will-relaunch', 'relaunched'])
                assert next(row for row in rows if row['event'] == 'found')['signatureValid'] is True
                assert plistlib.loads((app / 'Contents/Info.plist').read_bytes())['CFBundleVersion'] == '2'
                run('codesign', '--verify', '--deep', '--strict', app)
            else:
                assert plistlib.loads((app / 'Contents/Info.plist').read_bytes())['CFBundleVersion'] == '1'
                assert 'ready' not in names and 'relaunched' not in names
                if mode == 'payload-tampered':
                    assert 'found' in names and 'download-started' in names
                else:
                    assert 'extracting' not in names
            assert canary.read_text() == 'Updater fixture data must survive installation.\n'
            # Let Sparkle's asynchronous cleanup and relaunched fixture termination finish.
            for _ in range(50):
                cache = Path.home() / 'Library/Caches' / identifier
                payloads = [p for p in cache.rglob('*') if p.is_file() and (p.suffix == '.zip' or p.name == 'Hoppair')]
                if not payloads and process.poll() is not None:
                    break
                time.sleep(0.1)
            assert not payloads, f'Sparkle left a downloaded payload in {cache}'
            print(f'{mode}: passed (real Sparkle callbacks, signature checks, fixture canary and download cleanup)', flush=True)
            return {'scenario': mode, 'passed': True, 'events': names, 'fixture_canary_preserved': True, 'download_payloads_remaining': 0}
    finally:
        server.shutdown(); server.server_close()
        # Only processes from this unique disposable fixture directory are ours.
        for line in run('ps', '-axo', 'pid=,comm=').splitlines():
            fields = line.strip().split(None, 1)
            if len(fields) == 2 and fields[1].startswith(str(directory) + '/'):
                try:
                    os.kill(int(fields[0]), 15)
                except ProcessLookupError:
                    pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', type=Path, default=ROOT / 'dist/Hoppair.app', help='Signed app supplying the pinned embedded Sparkle framework')
    parser.add_argument('--identity', default=os.environ.get('HOPPAIR_SIGN_IDENTITY'))
    parser.add_argument('--account', default='dev.hoppair.updates')
    args = parser.parse_args()
    if not args.identity or args.identity == '-':
        parser.error('Set --identity to your existing Developer ID Application identity.')
    source = args.app.resolve()
    assert plistlib.loads((source / 'Contents/Info.plist').read_bytes())['SUPublicEDKey'] == (ROOT / 'config/update-public-key.txt').read_text().strip()
    work = Path(tempfile.mkdtemp(prefix='hoppair-updater-test-')).resolve()
    framework = source / 'Contents/Frameworks'
    binary = work / 'Hoppair'
    run('xcrun', 'clang', '-fobjc-arc', '-mmacosx-version-min=14.0', '-framework', 'AppKit', '-framework', 'Sparkle',
        '-F' + str(framework), '-Wl,-rpath,@executable_path/../Frameworks',
        ROOT / 'tests/macos/UpdaterFixture.m', '-o', binary)
    print(f'Isolated fixture workspace: {work}', flush=True)
    results = [scenario(work, mode, source, binary, args.identity, args.account) for mode in ['tampered', 'payload-tampered', 'cancel', 'success']]
    output = ROOT / 'artifacts/polish/mac-updater.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({'fixture_root': str(work), 'results': results,
        'scope': 'Real pinned Sparkle engine with headless test user driver; not standard update-window interaction or Apple notarization.'}, indent=2) + '\n')
    print(f'Passed all scenarios. Receipt: {output}', flush=True)


if __name__ == '__main__':
    main()
