"""Verify packaged asset completeness and annotations without trusting filenames alone."""
import hashlib
import json
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]

def validate(root=ROOT):
    assets = root / 'app/src/main/assets'
    catalog = json.loads((assets / 'catalog.json').read_text())
    tracks = catalog['tracks']
    assert catalog['schemaVersion'] == 1
    assert [x['number'] for x in tracks] == list(range(1,64)), 'Missing/duplicate/out-of-order tracks'
    assert len({x['id'] for x in tracks}) == 63
    assert set(x['asset'] for x in tracks) == {f'audio/{n:03}.mp3' for n in range(1,64)}
    assert len(list((assets / 'audio').glob('*.mp3'))) == 63
    for track in tracks:
        data = (assets / track['asset']).read_bytes()
        assert len(data) == track['bytes']
        assert hashlib.sha256(data).hexdigest() == track['sha256'], f"Corrupted track {track['number']}"
        assert track['durationMs'] > 0
        for ref in track['references']:
            assert ref['evidence'] and ref['status'] in {'verified', 'chapter-context'}
            assert not any(value is None for value in ref.values()), 'Omit absent fields'
            if 'page' in ref:
                assert (track['number'], ref['page']) in {(2,9),(3,15),(44,133),(45,136),(48,161)}
            assert 'timestampMs' not in ref, 'No unverified exercise timestamps'
    print('PASS: 63 original SHA-256-matching assets and evidence-backed catalog')

if __name__ == '__main__':
    validate()
