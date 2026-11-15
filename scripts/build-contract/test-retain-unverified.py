#!/usr/bin/env python3
# Synthetic fixtures ONLY. No network, real custody release, signing or APK bytes.
import hashlib, importlib.util, json, os, pathlib, tempfile, unittest
SPEC = importlib.util.spec_from_file_location('custody_inputs', pathlib.Path(__file__).with_name('custody-inputs.py'))

def load():
    m = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(m); return m

class FakeResponse:
    def __init__(self, payload): self.payload = payload
    def __enter__(self): return self
    def __exit__(self, *a): return False
    def read(self, n=-1): return self.payload

class RetainUnverified(unittest.TestCase):
    def setUp(self):
        self.m = load()
        self.tmp = tempfile.TemporaryDirectory(); d = pathlib.Path(self.tmp.name)
        self.apk = d/'app.apk'; self.apk.write_bytes(b'fake-apk-bytes')
        self.digest = hashlib.sha256(b'fake-apk-bytes').hexdigest()
        self.receipt = d/'receipt.json'
        os.environ['GITHUB_RUN_ID'] = '12345'
        os.environ['GITHUB_SHA'] = 'a'*40
        os.environ['GH_TOKEN'] = 'fixture'
        self.uploads = []
        m = self.m
        lock = {'custody': {'release_id': 1}}
        self.lock = lock
        release = {'id': 1, 'assets': []}
        m.check = lambda l: (release, None)
        class Opener:
            def open(_, request, timeout=0):
                self.uploads.append((request.full_url, request.data))
                data = request.data
                name = request.full_url.split('name=')[1]
                return FakeResponse(json.dumps({'id': 9, 'name': name, 'size': len(data), 'digest': 'sha256:'+hashlib.sha256(data).hexdigest()}).encode())
        m.urllib.request.build_opener = lambda *a, **k: Opener()
    def tearDown(self):
        self.tmp.cleanup()
    def write_receipt(self, **kw):
        base = {'status': 'verification_failed', 'apk_sha256': self.digest}
        base.update(kw); self.receipt.write_text(json.dumps(base))
    def test_positive(self):
        self.write_receipt()
        out = self.m.retain_unverified(self.lock, self.apk, self.receipt)
        names = sorted(r['name'] for r in out)
        import json as _json, pathlib as _pl
        prefix = _json.loads(_pl.Path(__file__).with_name('..').joinpath('release-qualification', 'version-expectation.json').resolve().read_text())['asset_prefix']
        self.assertEqual(names, [prefix + '-12345-UNVERIFIED-arm64-v8a.apk', prefix + '-12345-failure-receipt.json'])
        self.assertEqual(len(self.uploads), 2)
    def test_wrong_status_refused(self):
        self.write_receipt(status='verified')
        with self.assertRaises(ValueError): self.m.retain_unverified(self.lock, self.apk, self.receipt)
        self.assertEqual(self.uploads, [])
    def test_hash_mismatch_refused(self):
        self.write_receipt(apk_sha256='0'*64)
        with self.assertRaises(ValueError): self.m.retain_unverified(self.lock, self.apk, self.receipt)
        self.assertEqual(self.uploads, [])
    def test_no_overwrite(self):
        m = self.m
        import json as _json, pathlib as _pl
        prefix = _json.loads(_pl.Path(__file__).with_name('..').joinpath('release-qualification', 'version-expectation.json').resolve().read_text())['asset_prefix']
        release = {'id': 1, 'assets': [{'name': prefix + '-12345-UNVERIFIED-arm64-v8a.apk'}]}
        m.check = lambda l: (release, None)
        self.write_receipt()
        with self.assertRaises(ValueError): m.retain_unverified(self.lock, self.apk, self.receipt)
        self.assertEqual(self.uploads, [])
    def test_draft_flip_blocks_upload(self):
        def boom(l): raise ValueError('custody is not unpublished')
        self.m.check = boom
        self.write_receipt()
        with self.assertRaises(ValueError): self.m.retain_unverified(self.lock, self.apk, self.receipt)
        self.assertEqual(self.uploads, [])

if __name__ == '__main__':
    unittest.main()
