#!/usr/bin/env python3
"""Prepare verified release metadata and a signed Sparkle feed from final artifacts."""
import argparse
import base64
from datetime import datetime, timezone
from email.utils import format_datetime
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = 'minestero00/hoppair'
SPARKLE = 'http://www.andymatuschak.org/xml-namespaces/sparkle'


def run(*args):
    return subprocess.check_output([str(arg) for arg in args], text=True, stderr=subprocess.STDOUT).strip()


def version(value):
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', value):
        raise ValueError('Expected a stable semantic version.')
    return value


def prepare(batch, mac, apk, output, account):
    version(batch)
    output.mkdir(parents=True, exist_ok=True)
    public_key = (ROOT / 'config/update-public-key.txt').read_text().strip()
    if len(base64.b64decode(public_key, validate=True)) != 32:
        raise ValueError('Invalid public update key.')
    with zipfile.ZipFile(mac) as archive:
        info = plistlib.loads(archive.read('Hoppair.app/Contents/Info.plist'))
    mac_version = version(info['CFBundleShortVersionString'])
    build = info['CFBundleVersion']
    if not re.fullmatch(r'[1-9][0-9]*', build):
        raise ValueError('The Mac build must be a positive integer.')
    if info['CFBundleIdentifier'] != 'dev.macdroid.mac' or info.get('SUPublicEDKey') != public_key:
        raise ValueError('Unexpected Mac app identity or update key.')
    if not all(info.get(key) is True for key in ('HoppairUpdateChecksEnabled', 'SURequireSignedFeed', 'SUVerifyUpdateBeforeExtraction')):
        raise ValueError('The Mac app must enable verified updates.')
    if info.get('SUSignedFeedFailureExpirationInterval') != 0:
        raise ValueError('The Mac app must keep signed-feed verification enabled after prolonged failures.')
    if mac.name != f'Hoppair-{mac_version}-macos-arm64.zip':
        raise ValueError('Unexpected Mac archive filename.')
    with tempfile.TemporaryDirectory(prefix='hoppair-release-verify-') as temp:
        run('ditto', '-x', '-k', mac, temp)
        app = Path(temp) / 'Hoppair.app'
        run('codesign', '--verify', '--deep', '--strict', app)
        run('xcrun', 'stapler', 'validate', app)
        assessment = run('spctl', '--assess', '--type', 'execute', '--verbose=2', app)
        if 'Notarized Developer ID' not in assessment:
            raise ValueError('The Mac archive must contain the final notarized app.')
        if run('lipo', '-archs', app / 'Contents/MacOS/Hoppair') != 'arm64':
            raise ValueError('Expected an Apple silicon Mac app.')

    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
    tools = sorted((sdk / 'build-tools').glob('*'), key=lambda path: tuple(int(n) for n in re.findall(r'\d+', path.name)))
    if not tools:
        raise ValueError('Set ANDROID_HOME to an Android SDK with build-tools.')
    badging = run(tools[-1] / 'aapt', 'dump', 'badging', apk)
    package = re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging)
    minimum = re.search(r"sdkVersion:'([0-9]+)'", badging)
    if not package or package[1] != 'dev.macdroid.app' or not minimum:
        raise ValueError('Unexpected Android package metadata.')
    android_version = version(package[3])
    if apk.name != f'Hoppair-{android_version}-android.apk':
        raise ValueError('Unexpected Android archive filename.')
    certificate = run(tools[-1] / 'apksigner', 'verify', '--print-certs', apk)
    fingerprints = re.findall(r'Signer #[0-9]+ certificate SHA-256 digest: ([a-fA-F0-9]{64})', certificate)
    association = (ROOT / 'services/connect/wrangler.jsonc').read_text()
    expected = {value.replace(':', '').lower() for value in
                re.findall(r'"ANDROID_CERT_SHA256"\s*:\s*"([a-fA-F0-9:]+)"', association)}
    if len(fingerprints) != 1 or fingerprints[0].lower() not in expected:
        raise ValueError('Android signer differs from the published app association.')

    signing = ROOT / 'apps/macos/.build/artifacts/sparkle/Sparkle/bin/sign_update'
    if run(signing.with_name('generate_keys'), '--account', account, '-p') != public_key:
        raise ValueError('The selected Keychain account does not match the bundled public update key.')
    signature = run(signing, '--account', account, '-p', mac)
    run(signing, '--account', account, '--verify', mac, signature)
    if len(base64.b64decode(signature, validate=True)) != 64:
        raise ValueError('Invalid update signature.')
    base = f'https://github.com/{REPOSITORY}/releases/download/v{batch}/'
    ET.register_namespace('sparkle', SPARKLE)
    feed = ET.Element('rss', version='2.0')
    channel = ET.SubElement(feed, 'channel')
    ET.SubElement(channel, 'title').text = 'Hoppair Updates'
    ET.SubElement(channel, 'link').text = 'https://minestero00.github.io/hoppair/'
    ET.SubElement(channel, 'description').text = 'Stable Hoppair releases for macOS.'
    item = ET.SubElement(channel, 'item')
    ET.SubElement(item, 'title').text = f'Hoppair {mac_version}'
    ET.SubElement(item, 'pubDate').text = format_datetime(datetime.now(timezone.utc), usegmt=True)
    ET.SubElement(item, 'link').text = f'https://github.com/{REPOSITORY}/releases/tag/v{batch}'
    ET.SubElement(item, f'{{{SPARKLE}}}minimumSystemVersion').text = info['LSMinimumSystemVersion']
    ET.SubElement(item, f'{{{SPARKLE}}}hardwareRequirements').text = 'arm64'
    ET.SubElement(item, 'enclosure', {
        'url': base + mac.name, 'length': str(mac.stat().st_size), 'type': 'application/octet-stream',
        f'{{{SPARKLE}}}version': build, f'{{{SPARKLE}}}shortVersionString': mac_version,
        f'{{{SPARKLE}}}edSignature': signature,
    })
    manifest = {
        'version': batch, 'message': f'v{batch} is available.',
        'mac': {'url': base + mac.name, 'label': 'Download for Mac',
                'note': f'v{mac_version} · Developer ID signed · Apple notarized · {mac.stat().st_size / 2**20:.1f} MiB',
                'version': mac_version, 'build': int(build)},
        'android': {'url': base + apk.name, 'label': 'Download APK',
                    'note': f'Android 9+ · signed release · {apk.stat().st_size / 2**20:.1f} MiB',
                    'versionName': android_version, 'versionCode': int(package[2]), 'minSdk': int(minimum[1]),
                    'size': apk.stat().st_size, 'sha256': hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest()},
    }
    # Sign staging files before replacing either public source file. Never edit signed XML.
    with tempfile.TemporaryDirectory(prefix='.update-feed-', dir=output) as temp:
        staged = Path(temp) / 'appcast.xml'
        ET.indent(feed)
        ET.ElementTree(feed).write(staged, encoding='utf-8', xml_declaration=True)
        run(signing, '--account', account, staged)
        run(signing, '--account', account, '--verify', staged)
        staged_json = Path(temp) / 'release.json'
        staged_json.write_text(json.dumps(manifest, indent=2) + '\n')
        os.replace(staged, output / 'appcast.xml')
        os.replace(staged_json, output / 'release.json')
    print(f'Prepared signed Mac build {build} and verified Android code {package[2]} feeds in {output}.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('version', help='GitHub release batch, without v')
    parser.add_argument('--mac', type=Path, required=True, help='Final notarized ZIP')
    parser.add_argument('--android', type=Path, required=True, help='Final signed APK')
    parser.add_argument('--output', type=Path, default=ROOT / 'site')
    parser.add_argument('--account', default='dev.hoppair.updates', help='Existing Sparkle Keychain account')
    args = parser.parse_args()
    prepare(args.version, args.mac.resolve(), args.android.resolve(), args.output.resolve(), args.account)
