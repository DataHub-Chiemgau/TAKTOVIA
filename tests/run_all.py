#!/usr/bin/env python3
"""Führt alle Testsuiten unter tests/ nacheinander aus und fasst das Ergebnis zusammen (Entwickler- und CI-Hilfe).

  python tests/run_all.py [--no-db]      --no-db überspringt Suiten, die PostgreSQL brauchen (Akzeptanzgerüst, Spike a)

Datenbanksuiten brauchen die Umgebungsvariablen aus db/db.py (Administrator, Migrator- und Runtime-Passwort). Sie legen eigene Testdatenbanken
taktovia_at_selftest_* bzw. taktovia_spike_selftest an und verwerfen sie; fremde Datenbanken werden nie angefasst.
"""
import argparse
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SUITES = [  # (Name, Verzeichnis, braucht Datenbank)
    ("lib (Testkit)", "tests/lib", False),
    ("sandbox/psp (PSP-Simulator)", "tests/sandbox/psp", False),
    ("sandbox/partner (Sandbox-Partner)", "tests/sandbox/partner", False),
    ("performance (Lastgerüst)", "tests/performance", False),
    ("spike (d) PSP-Port", "tests/performance/spikes/spike_d", False),
    ("spike (e) Routing-Port", "tests/performance/spikes/spike_e", False),
    ("acceptance (Akzeptanzgerüst)", "tests/acceptance", True),
    ("spike (a) Hold/Confirm/PostGIS", "tests/performance/spikes/spike_a", True),
    ("contract (semantische Contract-Fälle)", "tests/contract", False),
]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--no-db", action="store_true")
    a = ap.parse_args()
    rows, rc = [], 0
    for name, d, db in SUITES:
        if db and a.no_db:
            rows.append((name, "übersprungen (--no-db)", 0.0))
            continue
        t = time.time()
        p = subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", d, "-p", "test_*.py"], cwd=REPO, capture_output=True, text=True)
        last = [l for l in p.stderr.strip().splitlines() if l.strip()][-3:]
        summary = " | ".join(l.strip() for l in last if l.startswith(("Ran", "OK", "FAILED")))
        rows.append((name, ("OK  " if p.returncode == 0 else "FEHLER ") + summary, time.time() - t))
        if p.returncode:
            rc = 1
            print(p.stderr[-3000:])
    for name, res, sec in rows:
        print(f"{name:<40} {res}  ({sec:.1f} s)")
    return rc


if __name__ == "__main__":
    sys.exit(main())
