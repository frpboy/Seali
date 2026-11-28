#!/usr/bin/env python3
"""Check the public producer source identity before local Maven qualification.
SEALI_BASELINE_* pin the Seali commit X7 extracted the producer sources from (recorded in the producer's source-baseline-bindings.json); PRODUCER_* pin the producer candidate itself.
The producer intentionally changed exactly five files on 2026-11-22 (commits 437e475, 2acd44d, e1dbad4, after the Seali baseline copies were taken) without refreshing the manifest pinned inside 8f864004. DIVERGENCE_LEDGER closes that set: for these rows the manifest sha still pins the Seali baseline copy (consumer side, requirement (a)) while the producer file must carry the pinned post-baseline bytes below (requirement (b)); for the four main-source files those bytes are also required to equal the same entry in the published alpha8 sources jar pinned by sha256 (requirement (c)). The test file is not in the sources jar; it is pinned by (b) plus PRODUCER_TREE. Every other row stays strict: producer file and Seali copy both equal the manifest sha, any sixth differing row fails, and a manifest missing any ledger row fails. Scope: source identity only, not artifact digest, runtime or publication approval."""
import argparse,hashlib,io,json,pathlib,subprocess,urllib.request,zipfile
PRODUCER='8f86400423c0acfb14630bec7ffb47b0fb0b6578'
PRODUCER_TREE='10924fc60d2a911afa93bc23212af5ca45d7e653'
SEALI_BASELINE_COMMIT='6e6c6e1a8f62f0d45d26cef386d726b145abf209'
SEALI_BASELINE_TREE='d989b3482317cab099aefd015f367a53b8e118c4'
DIVERGENCE_LEDGER={
 'library/src/main/java/com/yausername/youtubedl_android/OwnedInvocation.kt':'16e44b5c726d1bc1ae1950f58b4a0218a4c53c7b54c4f11a0aa3f8af70baa23f',
 'library/src/main/java/com/yausername/youtubedl_android/VerifiedEngineReplacement.kt':'42c415506c7dcc431861e1e653d52fa5a11a0fd9c2c0439c6ef2a32856c9aaba',
 'library/src/main/java/com/yausername/youtubedl_android/YoutubeDL.kt':'492efc57416a8692147dd2aeacb34bb5b50552eb403f2ea5aca6b9abb9868f20',
 'library/src/main/java/com/yausername/youtubedl_android/YoutubeDLException.kt':'736de7bacf210d18490f73a8fc55be625179dd4a60bc55fcf35531953324e338',
 'library/src/test/java/com/yausername/youtubedl_android/VerifiedEngineReplacementTest.kt':'c0d3143bf09c5ba5c60ac12ebd91f6f66d2f54e06ff485e9e92237f3a188b281',
}
PINNED_SOURCES_JAR_SHA256='bfdea71d1b8f017df13e0702bb2c53ef8b0e5c239d763059b379bb6a20c5ea75'
SOURCES_JAR_URL='https://repo1.maven.org/maven2/io/github/frpboy/youtubedl-android/library/0.1.0-alpha.8/library-0.1.0-alpha.8-sources.jar'
SOURCES_JAR_PREFIX='library/src/main/java/'
def _sha(b):return hashlib.sha256(b).hexdigest()
def _bound(owner,name):
 rel=pathlib.PurePosixPath(name)
 if rel.is_absolute() or '..' in rel.parts:raise ValueError('unsafe source binding path')
 p=owner/name
 if p.resolve()!=p.absolute() or not p.is_file():raise ValueError('source binding differs: '+name)
 return p
def check(root,producer,sources_jar=None):
 root=pathlib.Path(root).resolve(strict=True);producer=pathlib.Path(producer).resolve(strict=True)
 if subprocess.check_output(['git','-C',str(producer),'rev-parse','HEAD'],text=True).strip()!=PRODUCER:raise ValueError('producer commit differs')
 if subprocess.check_output(['git','-C',str(producer),'rev-parse','HEAD^{tree}'],text=True).strip()!=PRODUCER_TREE:raise ValueError('producer tree differs')
 if subprocess.check_output(['git','-C',str(producer),'status','--porcelain','--untracked-files=no'],text=True).strip():raise ValueError('producer source is dirty')
 manifest=producer/'source-baseline-bindings.json';lock=json.loads(manifest.read_text())
 if lock['baselineCommit']!=SEALI_BASELINE_COMMIT or lock['baselineTree']!=SEALI_BASELINE_TREE:raise ValueError('Seali source binding differs')
 checked=[];ledger_rows=set()
 for row in lock['files']:
  pf=_bound(producer,row['file']);cf=_bound(root,row['sourcePath'])
  ledger=DIVERGENCE_LEDGER.get(row['file'])
  if ledger is None:
   if _sha(pf.read_bytes())!=row['sha256'] or _sha(cf.read_bytes())!=row['sha256']:raise ValueError('source binding differs: '+row['file'])
  else:
   ledger_rows.add(row['file'])
   if _sha(cf.read_bytes())!=row['sha256']:raise ValueError('Seali baseline copy differs from manifest: '+row['file'])
   if _sha(pf.read_bytes())!=ledger:raise ValueError('producer file differs from pinned post-baseline bytes: '+row['file'])
  checked.append(row['file'])
 if len(checked)<25 or len(set(checked))!=len(checked):raise ValueError('incomplete/duplicate source binding')
 if ledger_rows!=set(DIVERGENCE_LEDGER):raise ValueError('divergence ledger not closed: manifest ledger rows '+repr(sorted(ledger_rows))+' != pinned ledger '+repr(sorted(DIVERGENCE_LEDGER)))
 jar=pathlib.Path(sources_jar).read_bytes() if sources_jar else urllib.request.urlopen(SOURCES_JAR_URL,timeout=60).read()
 if _sha(jar)!=PINNED_SOURCES_JAR_SHA256:raise ValueError('alpha8 sources jar sha256 differs from pin')
 z=zipfile.ZipFile(io.BytesIO(jar));names=set(z.namelist())
 for f,ledger in DIVERGENCE_LEDGER.items():
  if not f.startswith(SOURCES_JAR_PREFIX):continue
  entry=f[len(SOURCES_JAR_PREFIX):]
  if entry not in names:raise ValueError('alpha8 sources jar missing ledger entry: '+entry)
  if _sha(z.read(entry))!=ledger:raise ValueError('alpha8 sources jar entry differs from pinned post-baseline bytes: '+entry)
 return {'producer_commit':PRODUCER,'producer_tree':PRODUCER_TREE,'baseline_commit':SEALI_BASELINE_COMMIT,'baseline_tree':SEALI_BASELINE_TREE,'source_files_checked':len(checked),'divergence_ledger':sorted(DIVERGENCE_LEDGER),'sources_jar_sha256':PINNED_SOURCES_JAR_SHA256,'scope':'public producer source identity, not artifact digest, runtime or publication approval'}
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--root',required=True);p.add_argument('--producer',required=True);p.add_argument('--sources-jar');a=p.parse_args();print(json.dumps(check(a.root,a.producer,a.sources_jar),indent=2))
