import pathlib,json,hashlib
root=pathlib.Path(__file__).parent
manifest=root/'host-manifest.json'
assert hashlib.sha256(manifest.read_bytes()).hexdigest()=='ba227c383a49eadc79c34efdeca30ba0b055c3b84edc24ed5c67df623d27ea6a'
for row in json.loads(manifest.read_text()):
 p=root/row['path'];assert p.is_file() and not p.is_symlink()
 assert hashlib.sha256(p.read_bytes()).hexdigest()==row['sha256'],row['path']
print('reviewed component source verified')
