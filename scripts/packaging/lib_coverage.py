"""Automatic shipped-library derivation: resolve .so namelist through soname
chains, parse DT_NEEDED from each real ELF, and map every shipped library to
its license component. Any unmapped or unexpected external dep fails."""
import zipfile, struct

def resolve(z, name):
    data = z.read(name)
    chain = [name]
    while data[:6] != b'\x7fELF\x02\x01':
        name = 'usr/lib/' + data.decode('utf8', 'replace').strip()
        data = z.read(name); chain.append(name)
    return name, data

def dt_needed(data):
    phoff = struct.unpack_from('<Q', data, 32)[0]
    phentsize, phnum = struct.unpack_from('<HH', data, 54)
    dyn = None
    for i in range(phnum):
        p_type, p_flags, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align = struct.unpack_from('<IIQQQQQQ', data, phoff + i * phentsize)
        if p_type == 2:  # PT_DYNAMIC
            dyn = (p_offset, p_filesz); break
    if not dyn: return []
    off, size = dyn
    strtab_off = None; needed = []
    entries = []
    for i in range(0, size, 16):
        tag, val = struct.unpack_from('<qQ', data, off + i)
        entries.append((tag, val))
        if tag == 0: break
    strtab_vaddr = next((v for t, v in entries if t == 5), None)
    if strtab_vaddr is None: return []
    # map vaddr to file offset via PT_LOAD
    strtab_file = None
    for i in range(phnum):
        p_type, p_flags, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align = struct.unpack_from('<IIQQQQQQ', data, phoff + i * phentsize)
        if p_type == 1 and p_vaddr <= strtab_vaddr < p_vaddr + p_filesz:
            strtab_file = p_offset + (strtab_vaddr - p_vaddr); break
    if strtab_file is None: return []
    out = []
    for tag, val in entries:
        if tag == 1:  # DT_NEEDED
            end = data.index(b'\x00', strtab_file + val)
            out.append(data[strtab_file + val:end].decode())
    return out

# soname/path -> license component dir (prefix rules; new libs MUST map or fail)
def component_for(soname, path):
    s = soname.lower()
    rules = [
        ('libpython', 'Python-3.11.18'), ('libbz2', 'bzip2-1.0.8'),
        ('libcrypto', 'openssl-3.5.9'), ('libssl', 'openssl-3.5.9'),
        ('libexpat', 'expat-2.10.0'), ('libffi', 'libffi-3.8.0'),
        ('liblzma', 'xz-5.8.4'), ('libncursesw', 'ThomasDickey-ncurses-snapshots-26ceb3c'),
        ('libformw', 'ThomasDickey-ncurses-snapshots-26ceb3c'), ('libmenuw', 'ThomasDickey-ncurses-snapshots-26ceb3c'),
        ('libpanelw', 'ThomasDickey-ncurses-snapshots-26ceb3c'), ('libtinfow', 'ThomasDickey-ncurses-snapshots-26ceb3c'),
        ('libz', 'zlib-1.4'), ('libcrypt', 'libcrypt'),
        ('libandroid-support', 'libandroid-support-29'), ('libandroid-posix-semaphore', 'libandroid-posix-semaphore'),
        ('libsqlite', 'sqlite3'),
    ]
    for pref, comp in rules:
        if s.startswith(pref.lower()): return comp
    if '/lib-dynload/' in path: return 'Python-3.11.18'
    if '/site-packages/Cryptodome/' in path: return 'pycryptodomex-3.24.2'
    return None

ALLOWED_EXTERNAL = {'libc.so', 'libm.so', 'libdl.so'}

def shipped_library_components(zip_path):
    z = zipfile.ZipFile(zip_path)
    names = z.namelist()
    comps = {}
    external = set()
    for n in sorted(names):
        if not n.endswith('.so'): continue
        target, data = resolve(z, n)
        soname = target.split('/')[-1]
        comp = component_for(soname, target)
        comps.setdefault(comp, []).append(n)
        for dep in dt_needed(data):
            if 'usr/lib/' + dep not in names and dep not in {x.split('/')[-1] for x in names}:
                external.add(dep)
    return comps, external
