import pathlib,json,hashlib
root=pathlib.Path(__file__).parent
manifest=root/'host-manifest.json'
assert hashlib.sha256(manifest.read_bytes()).hexdigest()=='32570545ca83a74db095d0f49ae3decdbc72b0cd3696952fef2e7b84a9df8e44'
for row in json.loads(manifest.read_text()):
 p=root/row['path'];assert p.is_file() and not p.is_symlink()
 assert hashlib.sha256(p.read_bytes()).hexdigest()==row['sha256'],row['path']
print('reviewed component source verified')
