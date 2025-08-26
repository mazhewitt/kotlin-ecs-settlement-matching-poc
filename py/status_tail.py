#!/usr/bin/env python3
import time
from pathlib import Path

RUNTIME = Path(__file__).resolve().parent.parent / "runtime"
STATUS = RUNTIME / "status.txt"

def ensure_files():
    RUNTIME.mkdir(parents=True, exist_ok=True)
    if not STATUS.exists():
        STATUS.touch()

def tail():
    ensure_files()
    with STATUS.open('r', encoding='utf-8') as f:
        f.seek(0, 2)
        while True:
            line = f.readline()
            if not line:
                time.sleep(0.2)
                continue
            print(f"[STATUS] {line.strip()}")

if __name__ == '__main__':
    tail()
