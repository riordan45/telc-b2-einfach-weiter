"""Local, resumable opening-announcement evidence for every original book.

Two concurrent whisper jobs, four threads each; no cloud uploads. SHA-256 cache
shares evidence for byte-identical originals across editions/packages. --watch
discovers verified package states while preparation is still running.
"""
import argparse
import concurrent.futures
import json
import shutil
import subprocess
import time
from pathlib import Path

from prepare_catalog import ROOT, ANALYSIS, CACHE, DATA, write_json

OUT = ROOT / '.work/analysis/announcements-by-sha'
JOBS = ROOT / '.work/analysis/annotation-jobs'


def package_tracks():
    tracks = []
    for path in sorted(ANALYSIS.glob('*-pack-*.json')):
        state = json.loads(path.read_text())
        tracks.extend(state.get('tracks', []))
    return tracks


def transcript_target(track, seconds=12, model='small'):
    suffix = '' if seconds == 12 and model == 'small' else f'-{seconds}s-{model}'
    return OUT / (track['sha256'] + suffix)


def transcript_complete(target):
    return all(target.with_suffix(extension).is_file() for extension in ['.json', '.txt', '.evidence.json'])


def transcribe(track, seconds=12, model='small', force=False, canonical=False):
    target = OUT / track['sha256'] if canonical else transcript_target(track, seconds, model)
    if transcript_complete(target) and not force:
        return track['sha256']
    reusable = transcript_target(track, seconds, model) if canonical else OUT / track['sha256']
    if reusable != target and transcript_complete(reusable) and not force:
        metadata = json.loads(reusable.with_suffix('.evidence.json').read_text())
        if metadata['model'] == model and metadata['clipSeconds'] == seconds:
            for extension in ['.json', '.txt', '.evidence.json']:
                shutil.copyfile(reusable.with_suffix(extension), target.with_suffix(extension))
            return track['sha256']
    target.with_suffix('.evidence.json').unlink(missing_ok=True)
    wav = target.with_suffix('.wav')
    source = CACHE / 'resources' / (track['id'] + '.mp3')
    subprocess.run(['ffmpeg', '-nostdin', '-y', '-v', 'error', '-i', str(source), '-t', str(seconds),
                    '-ar', '16000', '-ac', '1', '-c:a', 'pcm_s16le', str(wav)], check=True)
    with target.with_suffix('.log').open('w') as log:
        subprocess.run([str(ROOT / '.work/tools/whisper-build/bin/whisper-cli'), '-m',
            str(ROOT / f'.work/tools/ggml-{model}.bin'), '-l', 'de', '-t', '4', '-oj', '-otxt',
            '-of', str(target), '-f', str(wav)], stdout=log, stderr=log, check=True)
    wav.unlink(missing_ok=True)
    write_json(target.with_suffix('.evidence.json'), dict(sourceSha256=track['sha256'],
        clipSeconds=seconds, model=model, sourceMember=track['sourceMember'],
        resourceId=track['id'], transcriptionEngine='whisper.cpp-local'))
    return track['sha256']


def job_manifests(tracks):
    inventory = json.loads((DATA / 'source-inventory.json').read_text())
    for book in inventory['books']:
        entries = [dict(t, announcementJson=str(OUT / (t['sha256'] + '.json')),
                        announcementText=str(OUT / (t['sha256'] + '.txt')),
                        announcementReady=transcript_complete(OUT / t['sha256']),
                        announcementLongJson=str(transcript_target(t, 40).with_suffix('.json')),
                        announcementLongReady=transcript_complete(transcript_target(t, 40)),
                        announcementBaseJson=str(transcript_target(t, 12, 'base').with_suffix('.json')),
                        announcementBaseReady=transcript_complete(transcript_target(t, 12, 'base')))
                   for t in tracks if t['bookId'] == book['id']]
        if entries:
            write_json(JOBS / (book['id'] + '.json'), dict(bookId=book['id'], title=book['title'],
                expectedPackageCount=len(book['packages']), readyPackageCount=len({t['packageId'] for t in entries}),
                tracks=entries, documents=book['documents'],
                annotationOutput=str(DATA / 'annotations' / (book['id'] + '.json'))))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--watch', action='store_true')
    parser.add_argument('--book', action='append', default=[])
    parser.add_argument('--seconds', type=int, default=12, help='Opening clip length, use 40 for unclear announcements')
    parser.add_argument('--model', choices=['small', 'base'], default='small')
    parser.add_argument('--sha', action='append', default=[], help='Only this audio SHA-256; repeatable')
    parser.add_argument('--force', action='store_true', help='Regenerate only selected evidence cache entries')
    parser.add_argument('--primary', action='store_true', help='Store first-pass 12-second evidence in canonical SHA cache, recording the actual selected model')
    args = parser.parse_args()
    if args.seconds > 12 and not (args.book or args.sha):
        parser.error('Longer fallback clips require explicit --book or --sha selection')
    if args.primary and args.seconds != 12:
        parser.error('--primary applies only to the 12-second first pass')
    if not (ROOT / f'.work/tools/ggml-{args.model}.bin').exists():
        parser.error(f'Missing local model .work/tools/ggml-{args.model}.bin')
    OUT.mkdir(parents=True, exist_ok=True)
    legacy = json.loads((DATA / 'legacy-b2-catalog.json').read_text())
    for track in legacy['tracks']:
        for extension in ['json', 'txt']:
            prior = ROOT / '.work/analysis/announcements' / f"{track['number']:03}.{extension}"
            target = OUT / f"{track['sha256']}.{extension}"
            if prior.exists() and not target.exists():
                shutil.copyfile(prior, target)
        evidence_path = OUT / (track['sha256'] + '.evidence.json')
        if (OUT / (track['sha256'] + '.json')).exists() and (OUT / (track['sha256'] + '.txt')).exists() and not evidence_path.exists():
            write_json(evidence_path, dict(sourceSha256=track['sha256'], clipSeconds=40, model='small',
                sourceMember=track['sourceFilename'], resourceId=track['id'], transcriptionEngine='whisper.cpp-local',
                inheritedFrom='.work/analysis/announcements/' + f"{track['number']:03}.json"))
    finished, failed = set(), set()
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        while True:
            tracks = package_tracks()
            if args.book:
                tracks = [t for t in tracks if t['bookId'] in args.book]
            if args.sha:
                tracks = [t for t in tracks if t['sha256'] in args.sha]
            unique = {t['sha256']: t for t in tracks}
            # Interleave books so parallel reviewers receive useful evidence early.
            per_book = {}
            for h, track in unique.items():
                target = OUT / h if args.primary else transcript_target(track, args.seconds, args.model)
                if h not in finished | failed and (args.force or not transcript_complete(target)):
                    per_book.setdefault(track['bookId'], []).append(track)
            pending = []
            while any(per_book.values()):
                for entries in per_book.values():
                    if entries:
                        pending.append(entries.pop(0))
            job_manifests(tracks)
            # Submit in small batches so new per-book evidence is visible promptly.
            while pending:
                # Reviewers can prioritize uncovered tracks without restarting ASR.
                priority = set()
                for priority_path in (ROOT / '.work/analysis').glob('*-announcement-priority.json'):
                    priority.update(json.loads(priority_path.read_text()))
                pending.sort(key=lambda track: track['sha256'] not in priority)
                fallback = set()
                for fallback_path in (ROOT / '.work/analysis').glob('*-announcement-fallback.json'):
                    fallback.update(json.loads(fallback_path.read_text()))
                longer = [dict(t, requestedSeconds=40) for h, t in unique.items() if h in fallback
                          and h not in failed and not transcript_complete(transcript_target(t, 40, 'small'))]
                for track in longer:
                    track['requestedModel'] = 'small'
                base_fallback = set()
                for fallback_path in (ROOT / '.work/analysis').glob('*-announcement-base-fallback.json'):
                    base_fallback.update(json.loads(fallback_path.read_text()))
                base = [dict(t, requestedSeconds=12, requestedModel='base') for h, t in unique.items() if h in base_fallback
                        and h not in failed and not transcript_complete(transcript_target(t, 12, 'base'))]
                batch = (base + longer)[:8]
                available = 8 - len(batch)
                batch.extend(pending[:available])
                pending = pending[available:]
                futures = {pool.submit(transcribe, t, t.get('requestedSeconds', args.seconds), t.get('requestedModel', args.model), args.force,
                           args.primary and 'requestedSeconds' not in t): t for t in batch}
                for future in concurrent.futures.as_completed(futures):
                    track = futures[future]
                    try:
                        finished.add(future.result())
                        print(f"Transcribed {track['id']}", flush=True)
                    except Exception as error:
                        failed.add(track['sha256'])
                        print(f"ERROR {track['id']}: {error}", flush=True)
                job_manifests(tracks)
            if not args.watch or (ROOT / '.work/analysis/preparation-complete').exists():
                break
            time.sleep(5)
    if failed:
        raise SystemExit(f'{len(failed)} announcements failed; rerun to retry')
    print(f'{len(unique)} unique audio checksums have announcement evidence', flush=True)


if __name__ == '__main__':
    main()
