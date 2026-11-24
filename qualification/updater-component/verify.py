import pathlib,json,hashlib
root=pathlib.Path(__file__).parent
manifest=root/'host-manifest.json'
assert hashlib.sha256(manifest.read_bytes()).hexdigest()=='197f410d9b1cd95d68db0b691e921d64eb1ccf0c5ee4c8f1323c750557d647c0'
for row in json.loads(manifest.read_text()):
 p=root/row['path'];assert p.is_file() and not p.is_symlink()
 assert hashlib.sha256(p.read_bytes()).hexdigest()==row['sha256'],row['path']
print('reviewed component source verified')
