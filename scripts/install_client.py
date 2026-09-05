#!/usr/bin/env python3
"""Install one verified Distant Stock build; archive older copies outside mods."""
import argparse
import hashlib
import re
import shutil
import zipfile
from datetime import datetime
from pathlib import Path


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    p = argparse.ArgumentParser()
    p.add_argument('jar', type=Path)
    p.add_argument('mods', type=Path)
    args = p.parse_args()
    jar, mods = args.jar.resolve(), args.mods.resolve()
    assert jar.is_file() and mods.is_dir()
    with zipfile.ZipFile(jar) as z:
        meta = z.read('META-INF/neoforge.mods.toml').decode()
        assert re.search(r'modId\s*=\s*"distantstock"', meta)
        assert z.testzip() is None
    candidates = list(mods.glob('Create-Distant-Stock-*.jar'))
    backup = mods.parent / 'distantstock-backups' / datetime.now().strftime('%Y%m%d-%H%M%S')
    if candidates:
        backup.mkdir(parents=True, exist_ok=False)
        for old in candidates:
            shutil.copy2(old, backup / old.name)
            assert digest(old) == digest(backup / old.name)
    dest = mods / jar.name
    staged = mods / (jar.name + '.installing')
    shutil.copy2(jar, staged)
    assert digest(jar) == digest(staged)
    # Old jars are removed only after both the backup and replacement have verified copies.
    staged.replace(dest)
    for old in candidates:
        if old != dest:
            old.unlink()
    assert digest(dest) == digest(jar)
    assert list(mods.glob('Create-Distant-Stock-*.jar')) == [dest]
    print('Installed:', dest)
    print('SHA256:', digest(dest))
    if candidates:
        print('Backup:', backup)


if __name__ == '__main__':
    main()
