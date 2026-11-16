#!/usr/bin/env python3
"""Static source contract for the Play/GitHub package and signing split.

Owner decision 2026-11-15: the Play variant ships as com.frpboy.seali.google
with its own upload key; the GitHub variant keeps com.frpboy.seali and the
existing upload key; the two never sign or update each other. Source checks
only: no Android execution, no signing, no store qualification.
"""
import pathlib
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
GRADLE = ROOT / 'app/build.gradle.kts'
PLAY_MANIFEST = ROOT / 'app/src/play/AndroidManifest.xml'
APP_UPDATER = ROOT / 'app/src/main/java/com/frpboy/seali/ui/page/AppUpdater.kt'
ANDROID_CI = ROOT / '.github/workflows/android_ci.yml'

PLAY_PACKAGE = 'com.frpboy.seali.google'
GITHUB_PACKAGE = 'com.frpboy.seali'
ANDROID_NS = '{http://schemas.android.com/apk/res/android}'
TOOLS_NS = '{http://schemas.android.com/tools}'


def flavor_block(gradle: str, name: str) -> str:
    match = re.search(r'create\("' + name + r'"\) \{(.*?)\n        \}', gradle, re.S)
    if not match:
        raise AssertionError(f'product flavor {name!r} not found')
    return match.group(1)


class PlayVariantSeparationTests(unittest.TestCase):
    def setUp(self):
        self.gradle = GRADLE.read_text()

    def test_play_flavor_uses_the_separate_play_package(self):
        block = flavor_block(self.gradle, 'play')
        self.assertIn(f'applicationId = "{PLAY_PACKAGE}"', block)

    def test_github_packages_unchanged(self):
        self.assertIn(f'applicationId = "{GITHUB_PACKAGE}"', self.gradle)
        preview = flavor_block(self.gradle, 'githubPreview')
        self.assertIn('applicationIdSuffix = ".preview"', preview)
        self.assertNotIn(PLAY_PACKAGE, preview)
        for other in ('generic', 'fdroid'):
            self.assertNotIn(PLAY_PACKAGE, flavor_block(self.gradle, other))

    def test_play_signing_is_separate_and_fail_closed(self):
        for var in (
            'SEALI_PLAY_UPLOAD_KEY_ALIAS',
            'SEALI_PLAY_UPLOAD_KEY_PASSWORD',
            'SEALI_PLAY_UPLOAD_STORE_PASSWORD',
            'SEALI_PLAY_UPLOAD_KEYSTORE_FILE',
        ):
            self.assertIn(var, self.gradle)
        self.assertIn('create("playPublish")', self.gradle)
        play = flavor_block(self.gradle, 'play')
        self.assertIn('signingConfig = signingConfigs.getByName("playPublish")', play)
        self.assertNotIn('githubPublish', play)
        # Release packaging per channel fails closed without that channel's key.
        self.assertIn('if (isPlayReleaseTask) playSigningConfigured else signingConfigured', self.gradle)
        self.assertIn(
            'if (isPlayReleaseTask) playUploadKeystoreFile?.isFile == true', self.gradle)

    def test_release_build_type_does_not_override_flavor_signing(self):
        # AGP resolves a build-type signingConfig over a flavor signingConfig
        # (verified against variant resolution on 2026-11-16: with githubPublish
        # on the shared release build type, playRelease signed with the GitHub
        # key despite the play flavor assigning playPublish). GitHub signing
        # must live on the GitHub-channel flavors only.
        build_types = self.gradle.split('buildTypes {', 1)[1].split('flavorDimensions', 1)[0]
        self.assertNotIn('signingConfig', build_types)
        for flavor in ('generic', 'githubPreview', 'fdroid'):
            block = flavor_block(self.gradle, flavor)
            self.assertIn(
                'signingConfig = signingConfigs.getByName("githubPublish")', block)

    def test_play_manifest_removes_sideload_and_all_files_permissions(self):
        root = ET.fromstring(PLAY_MANIFEST.read_text())
        removed = {
            el.attrib.get(ANDROID_NS + 'name')
            for el in root.findall('uses-permission')
            if el.attrib.get(TOOLS_NS + 'node') == 'remove'
        }
        self.assertIn('android.permission.REQUEST_INSTALL_PACKAGES', removed)
        self.assertIn('android.permission.MANAGE_EXTERNAL_STORAGE', removed)

    def test_sideload_updater_is_flavor_gated_not_package_gated(self):
        text = APP_UPDATER.read_text()
        self.assertNotIn('APPLICATION_ID == "com.frpboy.seali"', text)
        self.assertIn('BuildConfig.FLAVOR != "generic"', text)
        self.assertIn('BuildConfig.FLAVOR != "githubPreview"', text)

    def test_play_aab_is_never_retained_on_github(self):
        text = ANDROID_CI.read_text()
        stage = text.split('      - name: Stage private draft release assets\n', 1)[1]
        stage = stage.split('      - name: ', 1)[0]
        # No AAB copy, checksum append or asset upload in the GitHub staging step.
        self.assertNotIn('.aab', stage.replace("find release-assets -maxdepth 1 -type f -name '*.aab'", '').replace("grep -q '\\.aab' release-assets/SHA256SUMS", ''))
        self.assertIn('test "$(wc -l < release-assets/SHA256SUMS)" -eq 1', stage)
        # No artifact upload of bundles anywhere in the workflow.
        for section in text.split('      - name: '):
            if 'uses: actions/upload-artifact@v4' in section:
                self.assertNotIn('aab', section)
        # Built AABs are removed with signing material at job end.
        cleanup_pos = text.index('      - name: Remove private signing material')
        delete_pos = text.index("find app/build/outputs/bundle -type f -name '*.aab' -delete")
        self.assertGreater(delete_pos, cleanup_pos)

    def test_ci_verifies_signing_resolution_empirically(self):
        text = ANDROID_CI.read_text()
        section = text.split('      - name: Verify per-channel release signing resolution\n', 1)[1]
        step = section.split('      - name: ', 1)[0]
        # Dummy values for both channels; no real secrets are referenced.
        self.assertNotIn('secrets.', step)
        self.assertIn('SEALI_PLAY_UPLOAD_KEYSTORE_FILE: /tmp/play-release.jks', step)
        self.assertIn('SEALI_UPLOAD_KEYSTORE_FILE: /tmp/gh-release.jks', step)
        self.assertIn(
            "grep -q 'VARIANT_SIGNING playRelease: play-release.jks alias=play-alias'", step)
        for variant in ('genericRelease', 'githubPreviewRelease', 'fdroidRelease'):
            self.assertIn(variant, step)

    def test_ci_play_bundle_uses_only_play_secrets(self):
        text = ANDROID_CI.read_text()
        section = text.split('      - name: Build Google Play App Bundle\n', 1)[1]
        step = section.split('      - name: ', 1)[0]
        self.assertIn("if: steps.signing_play.outputs.available == 'true'", step)
        self.assertIn('secrets.SEALI_PLAY_UPLOAD_', step)
        self.assertNotIn('secrets.SEALI_UPLOAD_', step)


if __name__ == '__main__':
    unittest.main()
