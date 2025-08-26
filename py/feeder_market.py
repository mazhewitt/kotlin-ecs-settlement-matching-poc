#!/usr/bin/env python3
import os
import click
from pathlib import Path
from datetime import datetime, timezone

RUNTIME = Path(__file__).resolve().parent.parent / "runtime"
MARKET = RUNTIME / "market.txt"

def ensure_files():
    RUNTIME.mkdir(parents=True, exist_ok=True)
    if not MARKET.exists():
        MARKET.touch()

@click.command()
@click.option('--msg-id', required=True)
@click.option('--seq', required=True, type=int)
@click.option('--code', required=True, type=click.Choice(['ACK','MATCHED','PARTIAL_SETTLED','SETTLED']))
@click.option('--isin', required=True)
@click.option('--account', required=True)
@click.option('--settle-date', required=True, help='YYYY-MM-DD')
@click.option('--qty', required=True, type=int)
@click.option('--at', 'at_ts', required=False, help='ISO8601; defaults to now')
def main(msg_id, seq, code, isin, account, settle_date, qty, at_ts):
    ensure_files()
    if not at_ts:
        at_ts = datetime.now(timezone.utc).isoformat().replace('+00:00','Z')
    line = f"{msg_id},{seq},{code},{isin},{account},{settle_date},{qty},{at_ts}\n"
    with MARKET.open('a', encoding='utf-8') as f:
        f.write(line)
    click.echo(f"Wrote market update: {line.strip()}")

if __name__ == '__main__':
    main()
