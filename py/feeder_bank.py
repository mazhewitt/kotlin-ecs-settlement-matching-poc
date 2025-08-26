#!/usr/bin/env python3
import os
import time
import click
from pathlib import Path

RUNTIME = Path(__file__).resolve().parent.parent / "runtime"
BANK = RUNTIME / "bank.txt"

def ensure_files():
    RUNTIME.mkdir(parents=True, exist_ok=True)
    if not BANK.exists():
        BANK.touch()

@click.command()
@click.option('--id', 'obligation_id', required=True)
@click.option('--venue', required=True)
@click.option('--isin', required=True)
@click.option('--account', required=True)
@click.option('--settle-date', required=True, help='YYYY-MM-DD')
@click.option('--qty', required=True, type=int)
def main(obligation_id, venue, isin, account, settle_date, qty):
    ensure_files()
    line = f"{obligation_id},{venue},{isin},{account},{settle_date},{qty}\n"
    with BANK.open('a', encoding='utf-8') as f:
        f.write(line)
    click.echo(f"Wrote bank update: {line.strip()}")

if __name__ == '__main__':
    main()
