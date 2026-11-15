#!/usr/bin/env python3
"""Guards version-expectation.json against the actual source of truth in
buildSrc Version.kt: the candidate verifier and custody scripts read the
JSON, so it must equal the version the source will actually build."""
import json, pathlib, re, sys, unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCHEME = {'Alpha': ('-{name_suffix}', 100, True), 'Beta': ('-beta.{build}', 200, True),
          'ReleaseCandidate': ('-rc.{build}', 300, True), 'Stable': ('', 400, False)}
ARM64_ABI_CODE = 2  # app/build.gradle.kts abiCodes; candidate builds ARM64 only

class Expectation(unittest.TestCase):
    def test_json_matches_version_kt(self):
        src = (ROOT / 'buildSrc/src/main/kotlin/Version.kt').read_text()
        m = re.search(r'val currentVersion:\s*Version\s*=\s*Version\.(\w+)\(([^)]*)\)', src)
        self.assertIsNotNone(m, 'currentVersion declaration not found')
        kind, args = m.group(1), dict(re.findall(r'(\w+)\s*=\s*(\d+)', m.group(2)))
        major, minor, patch = (int(args[k]) for k in ['versionMajor', 'versionMinor', 'versionPatch'])
        build = int(args.get('versionBuild', 0))
        self.assertIn(kind, SCHEME)
        _, variant, uses_build = SCHEME[kind]
        if uses_build:
            self.assertGreater(build, 0, kind + ' requires a build number')
            name = '%d.%d.%d-%s.%d' % (major, minor, patch, kind.lower().replace('releasecandidate', 'rc'), build)
        else:
            name = '%d.%d.%d' % (major, minor, patch)
        base = major * 100_000_000 + minor * 1_000_000 + patch * 10_000 + build * 10 + variant
        expect = json.loads((ROOT / 'scripts/release-qualification/version-expectation.json').read_text())
        self.assertEqual(expect['name'], name)
        self.assertEqual(expect['code'], base + ARM64_ABI_CODE)
        self.assertEqual(expect['asset_prefix'], 'Seali-' + name)
        self.assertGreater(base, 200_000_172, 'must exceed every distributed alpha code')

if __name__ == '__main__':
    unittest.main()
