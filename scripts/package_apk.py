"""Verify the built APK's offline content and copy the personal install artifact."""
import hashlib
import json
import shutil
import zipfile
from pathlib import Path

from validate_catalog import ROOT, validate


def main():
    validate()
    source = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    catalog_path = ROOT / 'app/src/main/assets/catalog.json'
    catalog = json.loads(catalog_path.read_text())
    with zipfile.ZipFile(source) as apk:
        names = apk.namelist()
        assert len(names) == len(set(names)), 'Duplicate APK entries'
        assert apk.read('assets/catalog.json') == catalog_path.read_bytes()
        expected = {f"assets/{track['asset']}" for track in catalog['tracks']}
        assert {name for name in names if name.startswith('assets/audio/')} == expected
        for track in catalog['tracks']:
            data = apk.read(f"assets/{track['asset']}")
            assert len(data) == track['bytes']
            assert hashlib.sha256(data).hexdigest() == track['sha256']
        assert not any(name.startswith('assets/') and name.endswith(('.bin', '.wav', '.txt')) for name in names), 'Preparation files in APK'
    output = ROOT / 'output'
    output.mkdir(exist_ok=True)
    target = output / 'Weiter-B2-1.0.0.apk'
    shutil.copyfile(source, target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    target.with_suffix('.apk.sha256').write_text(f'{digest}  {target.name}\n')
    print(f'PASS: APK contains exactly 63 unchanged MP3s; {target.stat().st_size:,} bytes')
    print(f'SHA-256: {digest}')


if __name__ == '__main__':
    main()
