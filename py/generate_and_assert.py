#!/usr/bin/env python3
import os
import random
import string
import subprocess
import contextlib
import time
from dataclasses import dataclass
from pathlib import Path
from typing import List, Tuple

RUNTIME = Path(__file__).resolve().parent.parent / "runtime"
BANK = RUNTIME / "bank.txt"
MARKET = RUNTIME / "market.txt"
STATUS = RUNTIME / "status.txt"
PROJECT_ROOT = Path(__file__).resolve().parent.parent


@dataclass
class Trade:
    obligation_id: str
    venue: str
    isin: str
    account: str
    settle_date: str  # YYYY-MM-DD
    intended_qty: int


def rand_isin():
    return ''.join(random.choices('ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789', k=12))


def make_trades(n: int) -> List[Trade]:
    trades = []
    for i in range(n):
        trades.append(
            Trade(
                obligation_id=f"OBL{i+1:05d}",
                venue=random.choice(["LSE", "NYSE", "XETRA"]),
                isin=f"US{random.randint(0, 9999999999):010d}",
                account=f"ACC{random.randint(100,999)}",
                settle_date=f"2024-{random.randint(1,12):02d}-{random.randint(1,28):02d}",
                intended_qty=random.choice([100, 200, 500, 1000])
            )
        )
    return trades


def as_bank_line(t: Trade) -> str:
    # id,venue,isin,account,settleDate,intendedQty
    return f"{t.obligation_id},{t.venue},{t.isin},{t.account},{t.settle_date},{t.intended_qty}"


def as_market_line(t: Trade, msg_id: str, seq: int) -> str:
    # msgId,seq,code,isin,account,settleDate,qty,at
    # Use MATCHED with qty = intended_qty, and a fixed ISO instant for determinism
    at = "2024-01-01T00:00:00Z"
    return f"{msg_id},{seq},MATCHED,{t.isin},{t.account},{t.settle_date},{t.intended_qty},{at}"


def write_files(bank_lines: List[str], market_lines: List[str]) -> None:
    RUNTIME.mkdir(parents=True, exist_ok=True)
    for p in (BANK, MARKET, STATUS):
        if p.exists():
            p.unlink()
    BANK.write_text("\n".join(bank_lines) + ("\n" if bank_lines else ""), encoding='utf-8')
    MARKET.write_text("\n".join(market_lines) + ("\n" if market_lines else ""), encoding='utf-8')
    STATUS.touch()


def run_kotlin_runner_and_wait(expected: Tuple[int, int, int], timeout_sec: float = 15.0) -> Tuple[int, int, int]:
    """
    Start the Kotlin runner (./gradlew -q run), then poll the status file until
    expected (matches_to_matched, unmatches, duplicates) counts are reached or timeout.
    """
    proc = subprocess.Popen(["./gradlew", "-q", "run"], cwd=PROJECT_ROOT)
    target_matches, target_unmatches, target_duplicates = expected
    try:
        start = time.time()
        last_size = 0
        while time.time() - start < timeout_sec:
            if STATUS.exists():
                text = STATUS.read_text(encoding='utf-8')
                matches = sum(1 for line in text.splitlines() if line.startswith("StateChanged(") and "to=Matched" in line)
                unmatches = sum(1 for line in text.splitlines() if line.startswith("NoMatch("))
                duplicates = sum(1 for line in text.splitlines() if line.startswith("DuplicateIgnored("))
                if (matches, unmatches, duplicates) == expected:
                    return matches, unmatches, duplicates
            time.sleep(0.2)
        # Timed out; return whatever we saw
        if STATUS.exists():
            text = STATUS.read_text(encoding='utf-8')
            matches = sum(1 for line in text.splitlines() if line.startswith("StateChanged(") and "to=Matched" in line)
            unmatches = sum(1 for line in text.splitlines() if line.startswith("NoMatch("))
            duplicates = sum(1 for line in text.splitlines() if line.startswith("DuplicateIgnored("))
            return matches, unmatches, duplicates
        return 0, 0, 0
    finally:
        try:
            proc.terminate()
            proc.wait(timeout=5)
        except Exception:
            with contextlib.suppress(Exception):
                proc.kill()


def generate_dataset(n_trades: int, n_unmatches: int, n_duplicates: int) -> Tuple[List[str], List[str], Tuple[int,int,int]]:
    trades = make_trades(n_trades)
    # Bank lines for all known trades
    bank_lines = [as_bank_line(t) for t in trades]

    # Market lines: one MATCHED per trade
    market_lines = []
    msg_seq = {}
    for t in trades:
        msg_id = f"M_{t.obligation_id}"
        market_lines.append(as_market_line(t, msg_id, 1))
        msg_seq[t.obligation_id] = (msg_id, 1)

    # Duplicates: repeat some of the above lines exactly
    dup_indices = random.sample(range(len(trades)), k=min(n_duplicates, len(trades))) if n_duplicates>0 else []
    for idx in dup_indices:
        t = trades[idx]
        msg_id, seq = msg_seq[t.obligation_id]
        market_lines.append(as_market_line(t, msg_id, seq))  # exact duplicate

    # Unmatches: use keys that do not exist in bank file
    for i in range(n_unmatches):
        fake = Trade(
            obligation_id=f"FAKE{i+1}",
            venue="OTC",
            isin=rand_isin(),
            account=f"ACC{1000+i}",
            settle_date=f"2024-{random.randint(1,12):02d}-{random.randint(1,28):02d}",
            intended_qty=random.choice([100,200,500])
        )
        market_lines.append(as_market_line(fake, f"M_FAKE{i+1}", 1))

    # Shuffle lines independently to simulate arrival variance
    random.shuffle(bank_lines)
    random.shuffle(market_lines)

    # Expected counts: matches equals number of MATCHED that actually matched → n_trades
    # Duplicates: n_duplicates (each duplicate should produce DuplicateIgnored)
    # Unmatches: n_unmatches (each fake produces NoMatch)
    expected = (n_trades, n_unmatches, n_duplicates)
    return bank_lines, market_lines, expected


def main():
    random.seed(42)
    n_trades = 25
    n_unmatches = 7
    n_duplicates = 5

    bank_lines, market_lines, expected = generate_dataset(n_trades, n_unmatches, n_duplicates)
    write_files(bank_lines, market_lines)
    print(f"Wrote {len(bank_lines)} bank lines and {len(market_lines)} market lines into {RUNTIME}")

    # Run and assert
    got = run_kotlin_runner_and_wait(expected, timeout_sec=20.0)
    print(f"Expected (matches,to=Matched)={expected[0]}, unmatches={expected[1]}, duplicates={expected[2]}; got={got}")
    if got != expected:
        raise SystemExit(1)

    print("OK: counts match expected.")

if __name__ == "__main__":
    main()
