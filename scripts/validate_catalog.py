"""Validate schema-v2 completeness, provenance and optional original-file cache."""
import argparse
import hashlib
import json
import re
import zipfile
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def check_file(path, record):
    assert path.is_file(), f'Missing original file: {path}'
    assert path.stat().st_size == record['bytes'], f'Incorrect byte size: {path}'
    assert digest(path) == record['sha256'], f'Corrupted original file: {path}'


def validate_review(root, book):
    annotation_path = root / 'scripts/catalog/annotations' / (book['id'] + '.json')
    assert annotation_path.is_file(), f'Missing reviewed annotation file: {book["id"]}'
    annotation = json.loads(annotation_path.read_text())
    review = annotation.get('review', {})
    assert review.get('author') and review.get('reviewer') and review['author'] != review['reviewer'], f'Independent reviewer required: {book["id"]}'
    for field in ['reviewedAt', 'independentlyReviewedAt']:
        assert review.get(field), f'Missing {field}: {book["id"]}'
        assert datetime.fromisoformat(review[field].replace('Z', '+00:00')).tzinfo, f'UTC review date required: {book["id"]}'
    sources = {source.get('url', source.get('path')): source for source in annotation.get('sources', [])}
    for source in sources.values():
        assert re.fullmatch(r'[a-f0-9]{64}', source['sha256']), f'Evidence source SHA required: {book["id"]}'
    for kind in ['tracks', 'documents']:
        resources = {resource['id']: resource for resource in book[kind]}
        assert set(annotation.get(kind, {})) == set(resources), f'Unreviewed {kind}: {book["id"]}'
        for identity, record in annotation.get(kind, {}).items():
            audit = record['review']
            assert audit['status'] == 'reviewed' and audit['method'], f'Missing resource review: {identity}'
            assert audit['sourceSha256'] == resources[identity]['sha256'], f'Reviewed bytes mismatch: {identity}'
            refs = record.get('references', [])
            assert refs == resources[identity]['references'], f'Unmerged reviewed references: {identity}'
            if not refs:
                assert audit.get('reason'), f'Unsupported reference needs review reason: {identity}'
            for reference in refs:
                assert len(reference['evidence'].strip()) >= 12, f'Insufficient evidence description: {identity}'
                if 'sourceUrl' in reference:
                    evidence = sources.get(reference['sourceUrl'])
                    assert evidence, f'Unknown evidence source: {identity}'
                    if 'sourcePage' in reference:
                        assert 1 <= reference['sourcePage'] <= evidence['pageCount'], f'Invalid evidence PDF page: {identity}'
                else:
                    assert audit.get('sourceLocator'), f'Audio or legacy evidence locator required: {identity}'


def write_review_manifest(root, catalog):
    """Bind independent resource reviews and local ASR coverage to exact metadata."""
    annotations, sources, models, lengths, evidence_index = {}, {}, {}, {}, []
    sha_set = {track['sha256'] for book in catalog['books'] for track in book['tracks']}
    for book in catalog['books']:
        path = root / 'scripts/catalog/annotations' / (book['id'] + '.json')
        annotation = json.loads(path.read_text())
        resources = {}
        for kind in ['tracks', 'documents']:
            for resource in book[kind]:
                review = annotation[kind][resource['id']]['review']
                resources[resource['id']] = dict(sha256=resource['sha256'], kind=kind[:-1],
                    referenceCount=len(resource['references']), reviewStatus=review['status'])
                if not resource['references']:
                    resources[resource['id']]['reason'] = review['reason']
        annotations[book['id']] = dict(annotation['review'], sha256=digest(path),
            trackCount=len(book['tracks']), documentCount=len(book['documents']), resources=resources)
        for source in annotation.get('sources', []):
            key = source.get('url', source.get('path'))
            assert key, f'Evidence source locator required: {book["id"]}'
            if key in sources:
                assert sources[key]['sha256'] == source['sha256'], f'Conflicting source evidence hashes: {key}'
            sources[key] = {k: v for k, v in source.items() if k in {'url', 'path', 'sha256', 'pageCount', 'title', 'kind'}}
            cache_path = source.get('cachePath', source.get('path'))
            if cache_path and (root / cache_path).is_file():
                assert digest(root / cache_path) == source['sha256'], f'Changed evidence source: {key}'
    announcement_dir = root / '.work/analysis/announcements-by-sha'
    for sha in sorted(sha_set):
        paths = [announcement_dir / (sha + extension) for extension in ['.json', '.txt', '.evidence.json']]
        assert all(path.is_file() for path in paths), f'Incomplete canonical opening evidence: {sha}'
        evidence = json.loads(paths[2].read_text())
        assert evidence['sourceSha256'] == sha and evidence['model'] in {'small', 'base'}
        assert evidence['clipSeconds'] in {12, 40}, f'Unexpected opening evidence settings: {sha}'
        models[evidence['model']] = models.get(evidence['model'], 0) + 1
        lengths[str(evidence['clipSeconds'])] = lengths.get(str(evidence['clipSeconds']), 0) + 1
        evidence_index.append(dict(sourceSha256=sha, jsonSha256=digest(paths[0]),
            textSha256=digest(paths[1]), metadataSha256=digest(paths[2])))
    manifest = dict(schemaVersion=1, catalogSha256=digest(root / 'app/src/main/assets/catalog.json'),
        annotations=annotations, sources=list(sources.values()),
        announcements=dict(uniqueAudioCount=len(sha_set), completeCanonicalEvidence=len(evidence_index),
            models=models, clipSeconds=lengths,
            evidenceIndexSha256=hashlib.sha256(json.dumps(evidence_index, sort_keys=True).encode()).hexdigest()),
        totals=dict(books=len(catalog['books']), tracks=sum(len(book['tracks']) for book in catalog['books']),
            documents=sum(len(book['documents']) for book in catalog['books'])))
    target = root / 'scripts/catalog/review-manifest.json'
    target.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    return manifest


def validate(root=ROOT, verify_cache=False, verify_review=False):
    catalog = json.loads((root / 'app/src/main/assets/catalog.json').read_text())
    inventory = json.loads((root / 'scripts/catalog/source-inventory.json').read_text())
    legacy = json.loads((root / 'scripts/catalog/legacy-b2-catalog.json').read_text())
    registry = json.loads((root / 'scripts/catalog/registry.json').read_text())['ids']
    assert catalog['schemaVersion'] == 2
    assert catalog['catalogVersion'] >= 2
    assert catalog['sourceUrl'] == inventory['sourceUrl']
    assert catalog['sourceRetrievedAt']
    books = catalog['books']
    assert len(books) == len(inventory['books']) == 53
    assert {b['id'] for b in books} == {b['id'] for b in inventory['books']}
    assert len({b['id'] for b in books}) == len(books)
    all_resources, all_packages = [], []
    for book in books:
        source = next(b for b in inventory['books'] if b['id'] == book['id'])
        assert registry['book:' + source['sourceKey']] == book['id'], f'Book registry mismatch: {book["id"]}'
        packages = {p['id']: p for p in book['packages']}
        assert len(packages) == len(book['packages'])
        assert set(packages) == {p['id'] for p in source['packages']}
        assert {d['id'] for d in book['documents']} == {d['id'] for d in source['documents']}
        series = {s['id'] for s in book['series']}
        assert len(series) == len(book['series'])
        series_positions = {s['id']: s['position'] for s in book['series']}
        ordering = [(series_positions[t['seriesId']], t['number'], packages[t['packageId']]['position']) for t in book['tracks']]
        assert ordering == sorted(ordering), f'Book queue numeric order mismatch: {book["id"]}'
        for package in book['packages']:
            assert package['bookId'] == book['id']
            expected = next(p for p in source['packages'] if p['id'] == package['id'])
            assert package['url'] == expected['url']
            assert registry['package:' + package['url'].removeprefix(catalog['sourceUrl'].rstrip('/'))] == package['id']
            numbers = [track['number'] for track in book['tracks'] if track['packageId'] == package['id']]
            assert numbers == sorted(numbers), f'Track order mismatch: {package["id"]}'
            assert package['bytes'] > 0 and re.fullmatch(r'[a-f0-9]{64}', package['sha256'])
            if verify_cache:
                check_file(root / '.work/content/packages' / (package['id'] + '.zip'), package)
        for track in book['tracks']:
            assert track['bookId'] == book['id']
            assert track['packageId'] in packages and track['seriesId'] in series
            assert track['bytes'] > 0 and track['durationMs'] > 0
            assert isinstance(track['number'], int) and track['number'] >= 0
            assert track['sourceMember'].lower().endswith('.mp3')
            assert re.fullmatch(r'[a-f0-9]{64}', track['sha256'])
            assert registry['track:' + packages[track['packageId']]['url'] + '#' + track['sourceMember']] == track['id']
            if verify_cache:
                check_file(root / '.work/content/resources' / (track['id'] + '.mp3'), track)
        for document in book['documents']:
            assert document['bookId'] == book['id'] and document['language'] in {'de', 'en'}
            assert document['pageCount'] > 0 and document['kind'] in {'vocabulary', 'transcript', 'document'}
            assert document['bytes'] > 0 and re.fullmatch(r'[a-f0-9]{64}', document['sha256'])
            assert registry['document:' + document['url'].removeprefix(catalog['sourceUrl'].rstrip('/'))] == document['id']
            if verify_cache:
                check_file(root / '.work/content/resources' / (document['id'] + '.pdf'), document)
        if verify_cache:
            for package in book['packages']:
                expected_members = {t['sourceMember'] for t in book['tracks'] if t['packageId'] == package['id']}
                with zipfile.ZipFile(root / '.work/content/packages' / (package['id'] + '.zip')) as archive:
                    actual_members = {x.filename for x in archive.infolist() if not x.is_dir() and x.filename.lower().endswith('.mp3') and '__MACOSX' not in x.filename and not Path(x.filename).name.startswith('._')}
                assert expected_members == actual_members, f'Incomplete archive inventory: {package["id"]}'
        for resource in book['tracks'] + book['documents']:
            assert re.fullmatch(r'[a-z0-9-]+', resource['id']), f'Unsafe resource id: {resource["id"]}'
            for ref in resource['references']:
                assert ref['evidence'] and ref['status'] in {'verified', 'chapter-context'}
                assert not any(v is None for v in ref.values())
        all_resources.extend(book['tracks'] + book['documents'])
        all_packages.extend(book['packages'])
        if verify_review:
            validate_review(root, book)
    assert len({r['id'] for r in all_resources}) == len(all_resources), 'Duplicate resource ids'
    assert len({p['id'] for p in all_packages}) == len(all_packages) == 209
    documents = [d for b in books for d in b['documents']]
    assert len(documents) == 44 and sum(d['language'] == 'de' for d in documents) == 27
    assert sum(d['language'] == 'en' for d in documents) == 17
    full_b2 = next(b for b in books if b['id'] == 'einfach-weiter-b2')
    assert len(full_b2['tracks']) == 63
    by_id = {t['id']: t for t in full_b2['tracks']}
    for prior in legacy['tracks']:
        track = by_id[prior['id']]
        assert track['sha256'] == prior['sha256'], f'Legacy B2 byte mismatch: {track["id"]}'
        assert track['title'] == prior['title'] and track['references'] == prior['references']
    if verify_review:
        write_review_manifest(root, catalog)
    print(f"PASS: {len(books)} books, {len(all_packages)} original packages, {len(all_resources)-len(documents)} tracks, {len(documents)} PDFs; legacy B2 preserved")
    return catalog


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', action='store_true', help='Also verify every original/extracted byte and ZIP member list')
    parser.add_argument('--review', action='store_true', help='Require independent review of every track and document')
    args = parser.parse_args()
    validate(verify_cache=args.cache, verify_review=args.review)
