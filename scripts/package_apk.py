"""Verify and copy a Gradle-built metadata-only or explicitly bundled APK."""
import json
import shutil
import zipfile
from pathlib import Path
from build_app import ROOT, CATALOG, digest, verify_apk, verify_reviewed_snapshot

def main():
    verify_reviewed_snapshot()
    catalog = json.loads(CATALOG.read_text())
    source = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    with zipfile.ZipFile(source) as apk:
        try:
            bundled = json.loads(apk.read('assets/bundled.json'))
        except KeyError:
            bundled = {'resources': {}, 'books': []}
    verify_apk(source, catalog, bundled['resources'])
    output = ROOT / 'output'
    output.mkdir(exist_ok=True)
    label = '-bundled' if bundled['books'] else ''
    target = output / ('Weiter-2.0.0' + label + '.apk')
    shutil.copyfile(source, target)
    target.with_suffix('.apk.sha256').write_text(digest(target) + '  ' + target.name + '\n')
    print(f"Verified {target}: {target.stat().st_size / 1024**2:.1f} MiB, {len(bundled['resources'])} bundled resources")

if __name__ == '__main__':
    main()
