# Publishing updates

Apps before v0.1.6 need one manual update. Install Mac first; the new Android client requires correlated replies and shelf pagination. Later releases use the built-in update flow described in [install.md](install.md#app-updates).

## Signing identity

Keep the existing Android release key and Mac Developer ID identity. `config/update-public-key.txt` contains only the Sparkle public key; the corresponding private key is in the maintainer's login Keychain under account `dev.hoppair.updates`. Back it up securely outside the repository. Never regenerate it during a normal build or release.

Mac signed-feed validation does not expire after repeated failures. Losing the update key requires a manual recovery release; keep the backup available before distributing an updater-enabled app.

For a fork, create your own Sparkle key once with the pinned distribution's `bin/generate_keys --account YOUR_ACCOUNT`, replace the public key file, and configure your own release/feed locations and Android signer association. Mac builds accept `HOPPAIR_UPDATE_PUBLIC_KEY_FILE` and `HOPPAIR_UPDATE_FEED_URL`. Android's `UPDATE_FEED` and `UpdatePolicy` intentionally pin the official repository; forks must change both. Ad-hoc Mac builds disable automatic checks.

## Release order

1. Increase Mac `CFBundleVersion` and Android `versionCode` monotonically, even when the visible version stays the same. Run the [local gates](development.md).
2. Build/sign/notarize Mac and build the signed Android APK. ZIP the **exported notarized app**, without rebuilding it. Retain the same app IDs and signers.
3. Create update metadata from those exact final files:

```bash
python3 scripts/prepare-update-feeds.py 0.1.6 \
  --mac dist/release/0.1.6/Hoppair-0.1.6-macos-arm64.zip \
  --android dist/release/0.1.6/Hoppair-0.1.6-android.apk
```

The script verifies notarization, bundle identity, public key, architecture, APK signature/association and package metadata. It derives sizes, hashes, platform versions, and build numbers from the artifacts. It signs the ZIP and `site/appcast.xml` through Sparkle's Keychain tool and updates `site/release.json`. **Editing signed XML invalidates its signature.** Re-run the script after any change.

4. Finalize the version's entry in [CHANGELOG.md](../CHANGELOG.md), replacing **Unreleased** with the release date and updating validation notes. Use that entry as the GitHub release description. Update static download links on both site pages. Commit source and feeds; publish a GitHub release with the exact ZIP/APK and `SHA256SUMS.txt`. Then run `./scripts/deploy-pages.sh` from the clean checkout. Publish assets before feeds advertise them.
5. Download the public files again and check their hashes, APK signer, Mac signature/notarization, and signed appcast. Test upgrading an existing installation with shelf/pairing data. A successful build alone is not update-install evidence.

`release.json.version` identifies the GitHub release batch. Android's `android.versionName` and `versionCode`, and Mac's appcast version/build, identify each actual binary. A platform-only release can reuse the other platform's unchanged binary.

Sparkle owns its Mac download/extraction cleanup. Android uses only `cacheDir/updates` for updater downloads and removes completed/stale/cancelled files; no updater deletes arbitrary user Downloads. There are no DMG artifacts in the official pipeline.
