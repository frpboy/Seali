#!/usr/bin/env python3
"""Verify a debug app APK's native set against the reviewed custody manifests.

Expected set construction is the exact `want` of the release gate
(scripts/release-qualification/verify-compatible-release.py): the custody
payload manifest (staged by stage-alpha7-inputs.py) plus the platform JNI
rows, with the libqjs.so presence assertion. Set equality and per-entry
sha256 are strict; failures name the lane/custody-gate mismatch and print
the actual lib/ listing so a first-run failure is diagnostic, not mysterious.
"""
import argparse,hashlib,json,pathlib,sys,zipfile

def expected_set(root):
    payload=json.loads((root/'functional-alpha-payload-manifest.json').read_text())
    platform=json.loads((root/'functional-alpha-platform-jni.json').read_text())
    want={'lib/arm64-v8a/'+pathlib.PurePosixPath(r['path']).name:r['sha256'] for r in payload['files']}
    want.update({r['apk_entry']:r['sha256'] for r in platform})
    if 'lib/arm64-v8a/libqjs.so' not in want: raise ValueError('real reviewed QuickJS payload required; version bump is not a fix')
    return want

def verify(apk,root):
    want=expected_set(pathlib.Path(root))
    with zipfile.ZipFile(apk) as z:
        names=z.namelist()
        if len(names)!=len(set(names)) or z.testzip() is not None: raise ValueError('bad APK ZIP')
        got={n for n in names if n.startswith('lib/')}
        if got!=set(want):
            listing='\n'.join(sorted(got))
            raise ValueError('lane/custody-gate native set mismatch in debug app APK\n'
                f'extra={sorted(got-set(want))}\nmissing={sorted(set(want)-got)}\nactual lib/ listing:\n{listing}')
        for n,h in want.items():
            if hashlib.sha256(z.read(n)).hexdigest()!=h: raise ValueError('lane/custody-gate hash mismatch: '+n)
    return {'native_entries':len(want),'libqjs_present':True,'scope':'debug APK native set identity against custody manifests only, not signing, runtime behavior or release approval'}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--apk',required=True);p.add_argument('--root',default='.')
    a=p.parse_args();print(json.dumps(verify(a.apk,a.root),indent=2))
