"""Merge the inspected inventory with human-reviewed navigation annotations."""
import json
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]

def main():
    inventory = json.loads((ROOT / '.work/analysis/inventory.json').read_text())
    annotations = json.loads((ROOT / 'scripts/annotations.json').read_text())
    tracks = []
    for item in inventory:
        annotation = annotations[str(item['number'])]
        tracks.append(dict(**item, **annotation))
    catalog = dict(schemaVersion=1, catalogVersion=1, book='Einfach weiter! Deutsch B2 · Gesamtausgabe', tracks=tracks)
    (ROOT / 'app/src/main/assets/catalog.json').write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + '\n')
    print(f'Built catalog: {len(tracks)} tracks, {sum(len(x["references"]) for x in tracks)} exercise/section references')

if __name__ == '__main__':
    main()
