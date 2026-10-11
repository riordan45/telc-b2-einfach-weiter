"""Build the metadata-only app, or bundle explicitly selected reviewed books."""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / 'app/src/main/assets/catalog.json'
# APK ZIP offsets use 32 bits; reserve space for code, metadata, and signing.
MAX_BUNDLED_BYTES = 4 * 1024**3 - 128 * 1024**2

def verify_reviewed_snapshot(catalog_path=CATALOG, review_path=ROOT / 'scripts/catalog/review-manifest.json'):
    catalog_path, review_path = Path(catalog_path), Path(review_path)
    if not review_path.is_file():
        raise ValueError('Missing independent-review manifest; complete catalog review before building')
    review = json.loads(review_path.read_text())
    if review.get('schemaVersion') != 1 or review.get('catalogSha256') != digest(catalog_path):
        raise ValueError('Catalog changed after independent review; regenerate the reviewed release snapshot')
    catalog = json.loads(catalog_path.read_text())
    annotations = review.get('annotations', {})
    if set(annotations) != {book['id'] for book in catalog['books']}:
        raise ValueError('Reviewed snapshot does not account for every book')
    for book_id, evidence in annotations.items():
        annotation = review_path.parent / 'annotations' / (book_id + '.json')
        if not annotation.is_file() or digest(annotation) != evidence.get('sha256'):
            raise ValueError('Book annotation changed after independent review: ' + book_id)
    return review

def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

def verified(path, record):
    return path.is_file() and path.stat().st_size == record['bytes'] and digest(path) == record['sha256']

def download(record, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    if verified(path, record):
        return
    split = urllib.parse.urlsplit(record['url'])
    if split.scheme != 'https':
        raise ValueError('Original source must use HTTPS')
    url = urllib.parse.urlunsplit((split.scheme, split.netloc, urllib.parse.quote(split.path, safe='/%!'), split.query, split.fragment))
    temporary = path.with_suffix(path.suffix + '.partial')
    try:
        with urllib.request.urlopen(url, timeout=90) as response, temporary.open('wb') as output:
            shutil.copyfileobj(response, output, 1024 * 1024)
        if not verified(temporary, record):
            raise ValueError(f"Original source checksum changed: {record['id']}")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)

def safe_member(name):
    path = PurePosixPath(name)
    return not path.is_absolute() and '..' not in path.parts and '\\' not in name and '\x00' not in name

def ensure_book(book, cache):
    resources = cache / 'resources'
    resources.mkdir(parents=True, exist_ok=True)
    for package in book['packages']:
        tracks = [track for track in book['tracks'] if track['packageId'] == package['id']]
        if all(verified(resources / (track['id'] + '.mp3'), track) for track in tracks):
            continue
        archive = cache / 'packages' / (package['id'] + '.zip')
        download(package, archive)
        with zipfile.ZipFile(archive) as source:
            names = source.namelist()
            if any(not safe_member(name) for name in names) or len(names) != len(set(names)):
                raise ValueError('Unsafe or duplicate archive content')
            for track in tracks:
                member = track['sourceMember']
                if not safe_member(member):
                    raise ValueError('Unsafe source member')
                target = resources / (track['id'] + '.mp3')
                temporary = target.with_suffix('.partial')
                try:
                    with source.open(member) as stream, temporary.open('wb') as output:
                        shutil.copyfileobj(stream, output, 1024 * 1024)
                    if not verified(temporary, track):
                        raise ValueError(f"Audio content checksum mismatch: {track['id']}")
                    temporary.replace(target)
                finally:
                    temporary.unlink(missing_ok=True)
    for document in book['documents']:
        target = resources / (document['id'] + '.pdf')
        cached = cache / 'documents' / (document['id'] + '.pdf')
        if verified(cached, document) and not verified(target, document):
            shutil.copyfile(cached, target)
        if not verified(target, document):
            download(document, target)

def prepare_bundle(catalog, selected_books, output_dir, cache_root):
    by_id = {book['id']: book for book in catalog['books']}
    unknown = set(selected_books) - by_id.keys()
    if unknown:
        raise ValueError('Unknown book selection: ' + ', '.join(sorted(unknown)))
    selected = list(dict.fromkeys(selected_books))
    size = sum(item['bytes'] for book_id in selected for kind in ('tracks', 'documents')
               for item in by_id[book_id][kind])
    if size > MAX_BUNDLED_BYTES:
        raise ValueError('Selection exceeds the APK size limit; bundle fewer books and download additional books in the app')
    output_dir = Path(output_dir)
    cache_root = Path(cache_root)
    if output_dir.exists():
        shutil.rmtree(output_dir)
    output_dir.mkdir(parents=True)
    resources = {}
    for book_id in selected:
        book = by_id[book_id]
        ensure_book(book, cache_root)
        for kind, suffix in [('tracks', '.mp3'), ('documents', '.pdf')]:
            for item in book[kind]:
                source = cache_root / 'resources' / (item['id'] + suffix)
                if not verified(source, item):
                    raise ValueError(f"Missing or corrupt cached content: {item['id']}")
                relative = 'content/' + item['id'] + suffix
                target = output_dir / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
                resources[item['id']] = relative
    manifest = dict(schemaVersion=1, resources=resources, books=selected)
    (output_dir / 'bundled.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    return manifest

def verify_apk(apk_path, catalog, resources):
    records = {item['id']: item for book in catalog['books'] for kind in ('tracks', 'documents') for item in book[kind]}
    with zipfile.ZipFile(apk_path) as apk:
        entries = apk.infolist()
        # Allow local/central headers, alignment, and APK signing, but reject
        # large abandoned ZIP regions from a previous incremental bundle.
        footprint = sum(item.compress_size + len(item.filename.encode('utf-8')) * 2 +
                        len(item.extra) * 2 + 76 for item in entries)
        allowance = len(entries) * 4096 + 256 * 1024
        if Path(apk_path).stat().st_size > footprint + allowance:
            raise ValueError('APK retains unused archive bytes from a stale bundle; rebuild with fresh packaging')
        names = apk.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate APK entries')
        if json.loads(apk.read('assets/catalog.json')) != catalog:
            raise ValueError('APK catalog differs from reviewed manifest')
        expected = {'assets/' + path for path in resources.values()}
        actual = {name for name in names if name.startswith('assets/') and
                  (name.startswith(('assets/content/', 'assets/audio/')) or name.lower().endswith(('.mp3', '.pdf', '.zip', '.mp4')))}
        if actual != expected:
            raise ValueError('APK contains missing or unselected content payload')
        if 'assets/bundled.json' in names:
            manifest = json.loads(apk.read('assets/bundled.json'))
            if manifest.get('schemaVersion') != 1 or manifest.get('resources') != resources:
                raise ValueError('APK bundle manifest does not match the selected resources')
        for identity, path in resources.items():
            data = apk.read('assets/' + path)
            record = records[identity]
            if len(data) != record['bytes'] or hashlib.sha256(data).hexdigest() != record['sha256']:
                raise ValueError('Packaged content checksum mismatch: ' + identity)
        preparation = [name for name in names if name.startswith('assets/') and name.endswith(('.bin', '.wav', '.log'))]
        if preparation:
            raise ValueError('Preparation files included in APK')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--list-books', action='store_true')
    parser.add_argument('--bundle-book', action='append', default=[], metavar='BOOK_ID')
    parser.add_argument('--cache', type=Path, default=ROOT / '.work/content')
    args = parser.parse_args()
    catalog = json.loads(CATALOG.read_text())
    if catalog.get('schemaVersion') != 2:
        parser.error('Run scripts/prepare_catalog.py to create the reviewed multi-book catalog first')
    if args.list_books:
        for book in catalog['books']:
            size = sum(item['bytes'] for kind in ('tracks', 'documents') for item in book[kind])
            print(f"{book['id']}\t{book['familyTitle']} · {book['title']}\t{size / 1024**2:.1f} MiB offline")
        return
    verify_reviewed_snapshot()
    staging = ROOT / '.work/build-bundles'
    staging.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='assets-', dir=staging) as directory:
        assets = Path(directory)
        bundle = prepare_bundle(catalog, args.bundle_book, assets, args.cache)
        env = os.environ.copy()
        env.setdefault('GRADLE_USER_HOME', str(ROOT / '.work/gradle-home'))
        local_gradle = ROOT / '.work/tools/gradle-8.13/bin/gradle'
        executable = str(local_gradle) if local_gradle.exists() else str(ROOT / 'gradlew')
        command = [executable, ':app:assembleDebug']
        if args.bundle_book:
            command.append('-PbundledAssetsDir=' + str(assets.resolve()))
        subprocess.run(command, cwd=ROOT, env=env, check=True)
        apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
        verify_apk(apk, catalog, bundle['resources'])
        output = ROOT / 'output'
        output.mkdir(exist_ok=True)
        selected = bundle['books']
        label = '-bundled-' + '-'.join(selected) if selected else ''
        if len(label) > 140:
            selection_hash = hashlib.sha256('\n'.join(selected).encode()).hexdigest()[:10]
            label = f'-bundled-{len(selected)}-books-{selection_hash}'
        target = output / ('Weiter-2.0.0' + label + '.apk')
        shutil.copyfile(apk, target)
        target.with_suffix('.apk.sha256').write_text(digest(target) + '  ' + target.name + '\n')
        print(f"Verified {target}: {target.stat().st_size / 1024**2:.1f} MiB, {len(bundle['resources'])} bundled resources")

if __name__ == '__main__':
    main()
