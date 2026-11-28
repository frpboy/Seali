#!/usr/bin/env python3
"""Host fixture tests for verify-debug-apk-native-set.py: positive, missing,
extra and hash-mismatch negatives. Self-contained; no network or custody."""
import importlib.util,json,pathlib,tempfile,hashlib,zipfile,unittest
ROOT=pathlib.Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('verify_debug_native',ROOT/'scripts/build-contract/verify-debug-apk-native-set.py')
v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
ENGINE=b'engine-bytes';MMKV=b'mmkv-bytes';GFX=b'gfx-bytes'
def h(b):return hashlib.sha256(b).hexdigest()
class Contract(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=pathlib.Path(self.tmp.name)
  (self.root/'functional-alpha-payload-manifest.json').write_text(json.dumps({'files':[
   {'path':'app/src/main/jniLibs/arm64-v8a/libqjs.so','sha256':h(ENGINE)}]}))
  (self.root/'functional-alpha-platform-jni.json').write_text(json.dumps([
   {'apk_entry':'lib/arm64-v8a/libmmkv.so','sha256':h(MMKV)},
   {'apk_entry':'lib/arm64-v8a/libandroidx.graphics.path.so','sha256':h(GFX)}]))
  self.entries={'lib/arm64-v8a/libqjs.so':ENGINE,'lib/arm64-v8a/libmmkv.so':MMKV,'lib/arm64-v8a/libandroidx.graphics.path.so':GFX}
 def tearDown(self):self.tmp.cleanup()
 def apk(self,entries):
  p=self.root/'app.apk'
  with zipfile.ZipFile(p,'w') as z:
   for n,b in entries.items():z.writestr(n,b)
  return p
 def reject(self,entries,fragment):
  with self.assertRaises(ValueError) as c:v.verify(self.apk(entries),self.root)
  self.assertIn(fragment,str(c.exception))
 def test_exact_set_passes(self):
  r=v.verify(self.apk(self.entries),self.root)
  self.assertEqual(r['native_entries'],3);self.assertTrue(r['libqjs_present'])
 def test_missing_entry_rejected_with_listing(self):
  e=dict(self.entries);del e['lib/arm64-v8a/libmmkv.so']
  self.reject(e,'missing=')
 def test_extra_entry_rejected_with_listing(self):
  e=dict(self.entries);e['lib/arm64-v8a/libextra.so']=b'x'
  self.reject(e,'extra=')
 def test_hash_mismatch_rejected(self):
  e=dict(self.entries);e['lib/arm64-v8a/libqjs.so']=b'changed'
  self.reject(e,'hash mismatch')
 def test_missing_libqjs_expectation_rejected(self):
  (self.root/'functional-alpha-payload-manifest.json').write_text(json.dumps({'files':[]}))
  with self.assertRaises(ValueError) as c:v.verify(self.apk(self.entries),self.root)
  self.assertIn('QuickJS',str(c.exception))
if __name__=='__main__':unittest.main()
