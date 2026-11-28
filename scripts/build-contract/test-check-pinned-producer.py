#!/usr/bin/env python3
"""Offline contract tests for check-pinned-producer.py divergence ledger. No network: fixtures pass --sources-jar."""
import hashlib,importlib.util,io,json,pathlib,subprocess,tempfile,unittest,zipfile
spec=importlib.util.spec_from_file_location('check_pinned_producer',pathlib.Path(__file__).with_name('check-pinned-producer.py'));c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
sha=lambda b:hashlib.sha256(b).hexdigest()
class Contract(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();t=pathlib.Path(self.tmp.name)
  self.prod=t/'prod';self.cons=t/'cons';self.prod.mkdir();self.cons.mkdir();self.jp=t/'src.jar'
  self.orig={k:getattr(c,k) for k in ('PRODUCER','PRODUCER_TREE','SEALI_BASELINE_COMMIT','SEALI_BASELINE_TREE','DIVERGENCE_LEDGER','PINNED_SOURCES_JAR_SHA256')}
 def tearDown(self):
  for k,v in self.orig.items():setattr(c,k,v)
  self.tmp.cleanup()
 def build(self,extra_mismatch=False,missing_jar_entry=False,missing_ledger_row=False,baseline_copy_changed=False):
  ledger=[f'library/src/main/java/p/L{i}.kt' for i in range(4)]+['library/src/test/java/p/T1.kt']
  c.DIVERGENCE_LEDGER={f:sha(b'post'+f.encode()) for f in ledger}
  rows=[]
  for i,f in enumerate(ledger):
   post=b'post'+f.encode();base=b'base'+f.encode()
   pf=self.prod/f;pf.parent.mkdir(parents=True,exist_ok=True);pf.write_bytes(post)
   cf=self.cons/f;cf.parent.mkdir(parents=True,exist_ok=True);cf.write_bytes(b'changed' if baseline_copy_changed and i==0 else base)
   rows.append({'file':f,'sourcePath':f,'sha256':sha(base)})
  for i in range(21):
   f=f'library/src/main/java/p/S{i}.kt';data=b'strict%d'%i
   pf=self.prod/f;pf.parent.mkdir(parents=True,exist_ok=True);pf.write_bytes(b'mismatch' if extra_mismatch and i==0 else data)
   cf=self.cons/f;cf.parent.mkdir(parents=True,exist_ok=True);cf.write_bytes(data)
   rows.append({'file':f,'sourcePath':f,'sha256':sha(data)})
  if missing_ledger_row:rows=[r for r in rows if r['file']!=ledger[0]]
  (self.prod/'source-baseline-bindings.json').write_text(json.dumps({'baselineCommit':'b'*40,'baselineTree':'c'*40,'files':rows}))
  c.SEALI_BASELINE_COMMIT='b'*40;c.SEALI_BASELINE_TREE='c'*40
  subprocess.run(['git','init','-q'],cwd=self.prod,check=True)
  subprocess.run(['git','add','-A'],cwd=self.prod,check=True)
  subprocess.run(['git','-c','user.email=t@t','-c','user.name=t','commit','-qm','x'],cwd=self.prod,check=True)
  c.PRODUCER=subprocess.check_output(['git','rev-parse','HEAD'],cwd=self.prod,text=True).strip()
  c.PRODUCER_TREE=subprocess.check_output(['git','rev-parse','HEAD^{tree}'],cwd=self.prod,text=True).strip()
  buf=io.BytesIO()
  with zipfile.ZipFile(buf,'w') as z:
   for i,f in enumerate(ledger[:4]):
    if missing_jar_entry and i==0:continue
    z.writestr(f[len(c.SOURCES_JAR_PREFIX):],b'post'+f.encode())
  jar=buf.getvalue();c.PINNED_SOURCES_JAR_SHA256=sha(jar);self.jp.write_bytes(jar)
 def run_check(self):return c.check(self.cons,self.prod,str(self.jp))
 def reject(self,needle):
  with self.assertRaises(ValueError) as m:self.run_check()
  self.assertIn(needle,str(m.exception))
 def test_ledger_rows_pass(self):
  r=self.build();out=self.run_check()
  self.assertEqual(out['source_files_checked'],26);self.assertEqual(len(out['divergence_ledger']),5)
 def test_flipped_ledger_hash_rejected(self):
  self.build();k=sorted(c.DIVERGENCE_LEDGER)[0];c.DIVERGENCE_LEDGER[k]='0'*64
  self.reject('producer file differs from pinned post-baseline bytes')
 def test_extra_nonledger_row_mismatch_rejected(self):
  self.build(extra_mismatch=True);self.reject('source binding differs: library/src/main/java/p/S0.kt')
 def test_baseline_copy_changed_rejected(self):
  self.build(baseline_copy_changed=True);self.reject('Seali baseline copy differs from manifest')
 def test_missing_sources_jar_entry_rejected(self):
  self.build(missing_jar_entry=True);self.reject('alpha8 sources jar missing ledger entry')
 def test_sources_jar_sha_differs_rejected(self):
  self.build();c.PINNED_SOURCES_JAR_SHA256='0'*64;self.reject('alpha8 sources jar sha256 differs from pin')
 def test_manifest_missing_ledger_row_rejected(self):
  self.build(missing_ledger_row=True);self.reject('divergence ledger not closed')
if __name__=='__main__':unittest.main()
