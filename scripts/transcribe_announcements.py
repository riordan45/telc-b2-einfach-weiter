"""Local-only, resumable transcription of the opening 40 seconds of every track."""
import concurrent.futures
import subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.work/analysis/announcements'
OUT.mkdir(parents=True, exist_ok=True)

def transcribe(mp3):
    target = OUT / mp3.stem
    if target.with_suffix('.json').exists():
        return mp3.stem
    wav = target.with_suffix('.wav')
    subprocess.run(['ffmpeg', '-nostdin', '-y', '-v', 'error', '-i', str(mp3), '-t', '40', '-ar', '16000', '-ac', '1', '-c:a', 'pcm_s16le', str(wav)], check=True)
    with target.with_suffix('.log').open('w') as log:
        subprocess.run([str(ROOT / '.work/tools/whisper-build/bin/whisper-cli'), '-m', str(ROOT / '.work/tools/ggml-small.bin'), '-l', 'de', '-t', '4', '-oj', '-otxt', '-of', str(target), '-f', str(wav)], stdout=log, stderr=log, check=True)
    return mp3.stem

with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    for name in pool.map(transcribe, sorted((ROOT / 'app/src/main/assets/audio').glob('*.mp3'))):
        print(f'Transcribed {name}', flush=True)
