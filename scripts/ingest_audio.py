"""Import original telc audio and build a checksummed technical inventory."""
import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path, PurePosixPath

RANGES = ['001-007', '008-016', '017-019', '020-031', '032-043', '044-054', '055-063']
BASE = 'https://www.telc.net/fileadmin/user_upload/Downloads_Verlag/Einfach_weiter/Audios/'
ROOT = Path(__file__).resolve().parents[1]

def inspect_archive(path):
    tracks = []
    seen = set()
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            name = PurePosixPath(entry.filename)
            if name.is_absolute() or '..' in name.parts or '\\' in entry.filename:
                raise ValueError(f'Unsafe archive path: {entry.filename}')
            if '__MACOSX' in name.parts or name.name.startswith('._') or name.suffix.lower() != '.mp3':
                continue
            match = re.match(r'^(\d{3})_', name.name)
            if not match:
                raise ValueError(f'Unexpected audio filename: {name.name}')
            number = int(match[1])
            if not 1 <= number <= 63:
                raise ValueError(f'Track out of range: {number}')
            if number in seen:
                raise ValueError(f'Duplicate track: {number}')
            seen.add(number)
            tracks.append(dict(number=number, sourceFilename=name.name, data=archive.read(entry)))
    return sorted(tracks, key=lambda track: track['number'])

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--download', action='store_true')
    args = parser.parse_args()
    downloads = ROOT / '.work/downloads'
    assets = ROOT / 'app/src/main/assets/audio'
    downloads.mkdir(parents=True, exist_ok=True)
    assets.mkdir(parents=True, exist_ok=True)
    inventory = []
    for group in RANGES:
        filename = f'Einfach_weiter_Track_{group}.zip'
        archive = downloads / filename
        if args.download and not archive.exists():
            subprocess.run(['curl', '-fL', '--retry', '3', BASE + filename, '-o', str(archive)], check=True)
        entries = inspect_archive(archive)
        start, end = map(int, group.split('-'))
        if [entry['number'] for entry in entries] != list(range(start, end + 1)):
            raise ValueError(f'Incomplete archive: {filename}')
        for entry in entries:
            data = entry.pop('data')
            output = assets / f"{entry['number']:03}.mp3"
            output.write_bytes(data)
            probe = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'json', str(output)]))
            subprocess.run(['ffmpeg', '-nostdin', '-v', 'error', '-xerror', '-i', str(output), '-f', 'null', '-'], check=True)
            inventory.append(dict(**entry, id=f"b2-{entry['number']:03}", asset=f"audio/{entry['number']:03}.mp3", durationMs=round(float(probe['format']['duration']) * 1000), bytes=len(data), sha256=hashlib.sha256(data).hexdigest(), archiveUrl=BASE + filename))
    if [item['number'] for item in inventory] != list(range(1,64)):
        raise ValueError('Expected exactly 63 unique tracks')
    (ROOT / '.work/analysis').mkdir(parents=True, exist_ok=True)
    (ROOT / '.work/analysis/inventory.json').write_text(json.dumps(inventory, ensure_ascii=False, indent=2) + '\n')
    print(f"Validated {len(inventory)} original MP3s: {sum(x['bytes'] for x in inventory):,} bytes; {sum(x['durationMs'] for x in inventory)/3600000:.2f} hours")

if __name__ == '__main__':
    main()
