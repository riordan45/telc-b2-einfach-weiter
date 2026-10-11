"""Prepare verified original telc content and its versioned app catalog.

Inventory uses the checked-in original-app feed. Preparation downloads immutable
bytes to .work/content, validates every MP3 through ffprobe and ffmpeg, and emits
schema-v2 metadata. Nothing is bundled into app assets by this script.
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
import re
import stat
import subprocess
import threading
import time
import unicodedata
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'scripts/catalog'
DEFAULT_FEED = DATA / 'original-feed-2026-10-11.json'
REGISTRY_PATH = DATA / 'registry.json'
INVENTORY_PATH = DATA / 'source-inventory.json'
CATALOG_PATH = ROOT / 'app/src/main/assets/catalog.json'
CACHE = ROOT / '.work/content'
ANALYSIS = ROOT / '.work/analysis/catalog-state'
SOURCE_URL = 'https://telc.container.adrodev.de/produktiv/'
BASE_URL = SOURCE_URL.rstrip('/')
SOURCE_RETRIEVED_AT = '2026-10-11T17:45:04Z'
LOCK = threading.Lock()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + '.tmp')
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
    temp.replace(path)


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def slug(value):
    value = unicodedata.normalize('NFKD', value).encode('ascii', 'ignore').decode()
    return re.sub(r'[^a-z0-9]+', '-', value.lower()).strip('-') or 'content'


def registry_id(registry, key, candidate):
    with LOCK:
        if key in registry:
            return registry[key]
        candidate = slug(candidate)
        if candidate in registry.values():
            candidate += '-' + hashlib.sha256(key.encode()).hexdigest()[:8]
        registry[key] = candidate
        return candidate


def natural_key(value):
    return [int(x) if x.isdigit() else x.casefold() for x in re.split(r'(\d+)', value)]


def track_number(filename, fallback):
    name = PurePosixPath(filename).stem
    for pattern in [r'^(\d+)[ _-]', r'[Tt]racks?[_ -]*(\d+)(?=\D|$)', r'[_ -](\d+)$']:
        match = re.search(pattern, name)
        if match:
            return int(match[1])
    return fallback


def refresh_track_numbers(state, state_path, package=None):
    """Refine metadata without changing registry IDs or verified audio bytes."""
    changed = False
    if package and state['package'].get('seriesId') != package['seriesId']:
        state['package']['seriesId'] = package['seriesId']
        for track in state['tracks']:
            track['seriesId'] = package['seriesId']
        changed = True
    for ordinal, track in enumerate(state['tracks'], 1):
        number = track_number(track['sourceMember'], ordinal)
        if track['number'] != number:
            track['number'] = number
            changed = True
    ordered = sorted(state['tracks'], key=lambda track: (track['number'], natural_key(track['sourceMember'])))
    if state['tracks'] != ordered:
        state['tracks'] = ordered
        changed = True
    if changed:
        write_json(state_path, state)
    return state


def audio_members(archive):
    selected = []
    seen = set()
    for entry in archive.infolist():
        path = PurePosixPath(entry.filename)
        mode = entry.external_attr >> 16
        if path.is_absolute() or '..' in path.parts or '\\' in entry.filename or stat.S_ISLNK(mode):
            raise ValueError(f'Unsafe archive path: {entry.filename}')
        if entry.is_dir() or '__MACOSX' in path.parts or path.name.startswith('._'):
            continue
        if path.suffix.lower() != '.mp3':
            continue
        if entry.filename in seen:
            raise ValueError(f'Duplicate archive member: {entry.filename}')
        seen.add(entry.filename)
        selected.append(entry)
    if not selected:
        raise ValueError('Archive contains no MP3 files')
    return sorted(selected, key=lambda x: natural_key(x.filename))


def document_language(item):
    name = item['name']
    if re.search(r'\b(?:Englisch|English)\b', name, re.I):
        return 'en'
    if re.search(r'Wortschatzliste.*\bDeutsch\b', name, re.I):
        return 'de'
    if name in {f'Alphabetische Wortschatzliste Einfach gut {level}' for level in ('A1', 'A2', 'B1')}:
        return 'de'
    if name in {f'Hörtexte Lektion {n}' for n in range(2, 13)} | {'Hörtexte Abschlusstest'}:
        return 'de'
    return None


def source_inventory(feed, registry):
    ftp = next(x for x in feed if x['name'] == 'ftp')
    books = []
    for family in ftp['lehrwerk']:
        if 'kursbuch' not in family:
            continue
        family_slug = slug(re.sub(r'^\d+_', '', family['originalFileName']))
        family_key = '/ftp/' + family['originalFileName']
        family_id = registry_id(registry, 'family:' + family_key, family_slug)
        for volume in family['kursbuch']:
            if 'content' not in volume:
                continue
            book_key = family_key + '/' + volume['originalFileName']
            volume_slug = slug(re.sub(r'^\d+_', '', volume['originalFileName']))
            candidate = family_slug if volume['name'] == 'HiddenFolder' else (
                volume_slug if volume_slug.startswith(family_slug) else family_slug + '-' + volume_slug
            )
            # A single-volume family deliberately shares its display-friendly id.
            if volume['name'] == 'HiddenFolder':
                book_id = registry.setdefault('book:' + book_key, family_id)
            else:
                book_id = registry_id(registry, 'book:' + book_key, candidate)
            book = dict(id=book_id, familyId=family_id, familyTitle=family['name'],
                        title=family['name'] if volume['name'] == 'HiddenFolder' else volume['name'],
                        position=family['position'] * 1000 + volume['position'],
                        sourceKey=book_key, series=[], packages=[], documents=[])
            for content in volume['content']:
                for kind, items in content.items():
                    if not isinstance(items, list):
                        continue
                    for item in items:
                        if kind == 'audio' and item['fileExtension'] == 'zip':
                            item_id = registry_id(registry, 'package:' + item['url'], f"{book_id}-pack-{item['position']}")
                            series_title = re.sub(r'\s+Tracks?\s+\d{1,3}(?:[- _]+\d{1,3})?\s*$', '', item['name'], flags=re.I).strip()
                            series_title = re.sub(r'\s+\d{1,3}\s*-\s*\d{1,3}\s*$', '', series_title).strip()
                            series_title = unicodedata.normalize('NFC', series_title)
                            series_key = 'series:' + book_key + ':' + series_title
                            series_id = registry_id(registry, series_key, f"{book_id}-series-{item['position']}")
                            if not any(s['id'] == series_id for s in book['series']):
                                book['series'].append(dict(id=series_id, title=series_title, position=item['position']))
                            book['packages'].append(dict(id=item_id, bookId=book_id, title=item['name'],
                                url=BASE_URL + item['url'], position=item['position'], seriesId=series_id,
                                sourceModifiedAt=item['lastModifiedDate'], sourceFilename=item['originalFileName']))
                        elif kind == 'other' and item['fileExtension'] == 'pdf':
                            language = document_language(item)
                            if not language:
                                continue
                            item_id = registry_id(registry, 'document:' + item['url'], f"{book_id}-doc-{item['position']}")
                            book['documents'].append(dict(id=item_id, bookId=book_id, title=item['name'], language=language,
                                kind='transcript' if item['name'].startswith('Hörtexte') else 'vocabulary',
                                url=BASE_URL + item['url'], position=item['position'], references=[],
                                sourceModifiedAt=item['lastModifiedDate'], sourceFilename=item['originalFileName']))
            books.append(book)
    return dict(schemaVersion=2, catalogVersion=2, sourceUrl=SOURCE_URL,
                sourceRetrievedAt=SOURCE_RETRIEVED_AT, books=sorted(books, key=lambda b: b['position']))


def download(item, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        return
    temp = path.with_suffix(path.suffix + '.part')
    url = urllib.parse.quote(item['url'], safe=':/%?=&!')
    for attempt in range(3):
        try:
            request = urllib.request.Request(url, headers={'User-Agent': 'TelcCompanionContentPreparation/1.0'})
            with urllib.request.urlopen(request, timeout=90) as response, temp.open('wb') as output:
                expected = response.headers.get('Content-Length')
                while chunk := response.read(1024 * 1024):
                    output.write(chunk)
            if expected and temp.stat().st_size != int(expected):
                raise ValueError(f"Incomplete download: {item['url']}")
            temp.replace(path)
            return
        except Exception:
            temp.unlink(missing_ok=True)
            if attempt == 2:
                raise
            time.sleep(attempt + 1)


def probe_audio(path):
    probe = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-show_entries',
        'format=duration:stream=codec_name,codec_type', '-of', 'json', str(path)]))
    if not any(x.get('codec_type') == 'audio' and x.get('codec_name') == 'mp3' for x in probe['streams']):
        raise ValueError(f'Expected original MP3: {path}')
    duration = round(float(probe['format']['duration']) * 1000)
    if duration <= 0:
        raise ValueError(f'Invalid audio duration: {path}')
    subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-xerror', '-i', str(path), '-f', 'null', '-'], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    return duration


def prepare_package(book, package, registry, legacy_by_hash):
    state_path = ANALYSIS / (package['id'] + '.json')
    archive_path = CACHE / 'packages' / (package['id'] + '.zip')
    download(package, archive_path)
    digest = sha256_file(archive_path)
    if state_path.exists():
        state = json.loads(state_path.read_text())
        if state['package']['sha256'] == digest and all(
                (CACHE / 'resources' / (x['id'] + '.mp3')).is_file()
                and sha256_file(CACHE / 'resources' / (x['id'] + '.mp3')) == x['sha256'] for x in state['tracks']):
            return refresh_track_numbers(state, state_path, package)
    technical = dict(package, bytes=archive_path.stat().st_size, sha256=digest)
    tracks = []
    with zipfile.ZipFile(archive_path) as archive:
        for ordinal, entry in enumerate(audio_members(archive), 1):
            data = archive.read(entry)
            audio_hash = hashlib.sha256(data).hexdigest()
            number = track_number(entry.filename, ordinal)
            legacy = legacy_by_hash.get(audio_hash) if book['id'] == 'einfach-weiter-b2' else None
            key = 'track:' + package['url'] + '#' + entry.filename
            if legacy:
                with LOCK:
                    registry[key] = legacy['id']
                resource_id = legacy['id']
            else:
                resource_id = registry_id(registry, key, f"{package['id']}-track-{number}")
            output = CACHE / 'resources' / (resource_id + '.mp3')
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(data)
            duration = probe_audio(output)
            title = legacy['title'] if legacy else PurePosixPath(entry.filename).stem.replace('_', ' ')
            tracks.append(dict(id=resource_id, bookId=book['id'], seriesId=package['seriesId'], packageId=package['id'],
                number=number, title=title, sourceMember=entry.filename, bytes=len(data), sha256=audio_hash,
                durationMs=duration, references=legacy['references'] if legacy else []))
    state = dict(package=technical, tracks=sorted(tracks, key=lambda track: (track['number'], natural_key(track['sourceMember']))))
    write_json(state_path, state)
    return state


def prepare_document(document):
    from pypdf import PdfReader
    source = CACHE / 'documents' / (document['id'] + '.pdf')
    download(document, source)
    digest = sha256_file(source)
    pages = len(PdfReader(source).pages)
    if pages <= 0:
        raise ValueError(f'Empty PDF: {source}')
    # Keep one exact original copy in the public resource cache used by bundling.
    resource = CACHE / 'resources' / (document['id'] + '.pdf')
    resource.parent.mkdir(parents=True, exist_ok=True)
    if not resource.exists() or sha256_file(resource) != digest:
        resource.write_bytes(source.read_bytes())
    result = dict(document, bytes=source.stat().st_size, sha256=digest, pageCount=pages)
    write_json(ANALYSIS / (document['id'] + '.json'), result)
    return result


def apply_annotations(book, annotations):
    if annotations.get('bookId') != book['id']:
        raise ValueError('Annotation book id does not match')
    for kind in ['tracks', 'documents']:
        known = {x['id']: x for x in book[kind]}
        for identity, annotation in annotations.get(kind, {}).items():
            if identity not in known:
                raise ValueError(f'Unknown annotated resource: {identity}')
            references = annotation.get('references', [])
            for reference in references:
                if not reference.get('evidence') or reference.get('status') not in {'verified', 'chapter-context'}:
                    raise ValueError(f'Missing reviewed evidence: {identity}')
                if not reference.get('section'):
                    raise ValueError(f'Missing reference section: {identity}')
            if 'title' in annotation:
                known[identity]['title'] = annotation['title']
            known[identity]['references'] = references


def generate(inventory, registry):
    result = {k: v for k, v in inventory.items() if k != 'books'}
    result['books'] = []
    for source_book in inventory['books']:
        book = {k: v for k, v in source_book.items() if k not in {'sourceKey', 'packages', 'documents'}}
        book['packages'], book['tracks'], book['documents'] = [], [], []
        for package in source_book['packages']:
            state_path = ANALYSIS / (package['id'] + '.json')
            state = refresh_track_numbers(json.loads(state_path.read_text()), state_path, package)
            # Series labels may be refined without invalidating immutable audio IDs.
            book['packages'].append(dict(state['package'], seriesId=package['seriesId']))
            book['tracks'].extend(dict(t, seriesId=package['seriesId']) for t in state['tracks'])
        for document in source_book['documents']:
            book['documents'].append(json.loads((ANALYSIS / (document['id'] + '.json')).read_text()))
        series_positions = {series['id']: series['position'] for series in book['series']}
        package_positions = {package['id']: package['position'] for package in book['packages']}
        book['tracks'].sort(key=lambda track: (series_positions[track['seriesId']], track['number'],
            package_positions[track['packageId']], natural_key(track['sourceMember'])))
        annotation_path = DATA / 'annotations' / (book['id'] + '.json')
        if annotation_path.exists():
            apply_annotations(book, json.loads(annotation_path.read_text()))
        result['books'].append(book)
    write_json(REGISTRY_PATH, dict(schemaVersion=1, ids=registry))
    write_json(CATALOG_PATH, result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inventory-only', action='store_true')
    parser.add_argument('--book', action='append', default=[], help='Only prepare this concrete book id; repeatable')
    parser.add_argument('--workers', type=int, default=6)
    parser.add_argument('--generate-only', action='store_true')
    args = parser.parse_args()
    registry = json.loads(REGISTRY_PATH.read_text())['ids'] if REGISTRY_PATH.exists() else {}
    inventory = source_inventory(json.loads(DEFAULT_FEED.read_text()), registry)
    write_json(INVENTORY_PATH, inventory)
    write_json(REGISTRY_PATH, dict(schemaVersion=1, ids=registry))
    if args.inventory_only:
        print(f"Inventory: {len(inventory['books'])} books, {sum(len(b['packages']) for b in inventory['books'])} packages, {sum(len(b['documents']) for b in inventory['books'])} PDFs")
        return
    if not args.generate_only:
        legacy = json.loads((DATA / 'legacy-b2-catalog.json').read_text())
        legacy_by_hash = {x['sha256']: x for x in legacy['tracks']}
        jobs = []
        books = [b for b in inventory['books'] if not args.book or b['id'] in args.book]
        unknown = set(args.book) - {b['id'] for b in inventory['books']}
        if unknown:
            parser.error('Unknown book ids: ' + ', '.join(sorted(unknown)))
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
            for book in books:
                jobs.extend(executor.submit(prepare_package, book, p, registry, legacy_by_hash) for p in book['packages'])
                jobs.extend(executor.submit(prepare_document, d) for d in book['documents'])
            errors = []
            for count, future in enumerate(concurrent.futures.as_completed(jobs), 1):
                try:
                    value = future.result()
                    identity = value.get('id') or value['package']['id']
                    print(f"[{count}/{len(jobs)}] Verified {identity}", flush=True)
                except Exception as error:
                    errors.append(str(error))
                    print(f"[{count}/{len(jobs)}] ERROR: {error}", flush=True)
                write_json(REGISTRY_PATH, dict(schemaVersion=1, ids=dict(registry)))
        if errors:
            raise SystemExit('\n'.join(errors))
    if args.book:
        print('Selected books prepared; full app catalog is generated only from complete inventory.')
        return
    result = generate(inventory, registry)
    print(f"Catalog: {len(result['books'])} books, {sum(len(b['tracks']) for b in result['books'])} tracks, {sum(len(b['documents']) for b in result['books'])} PDFs")


if __name__ == '__main__':
    main()
