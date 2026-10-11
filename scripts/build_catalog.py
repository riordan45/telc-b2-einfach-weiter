"""Rebuild schema-v2 metadata from previously verified cached originals."""
import subprocess
import sys
from pathlib import Path

if __name__ == '__main__':
    subprocess.run([sys.executable, str(Path(__file__).with_name('prepare_catalog.py')), '--generate-only'], check=True)
