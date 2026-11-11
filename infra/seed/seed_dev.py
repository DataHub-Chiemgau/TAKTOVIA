#!/usr/bin/env python3
"""Synthetischer Seed-Datengenerator fuer die Mandanten A/B (WP-M1-40, LH-6.18-SEC-012).

Erzeugt ausschliesslich synthetische Daten in einer Entwicklungsdatenbank, deterministisch und wiederholbar (gleiche IDs, gleicher Datenstand).
Es setzt auf dem Seed des Akzeptanzgeruests auf (tests/acceptance/taktovia_at/seed.py, PH-AT-001: je Mandant Rechtstraeger, Organisation,
Fahrgast, Fahrzeug, Beleg, identisch benannte Kostenstelle; Identitaeten wie im Entwicklungs-IdP).

Sperre: laeuft nur gegen eine Datenbank mit der Umgebungskennung `development`; gegen staging, performance und production verweigert es
den Lauf (Ausgang 2), ebenso ohne Kennung. Danach prueft es sich selbst mit synthetic_scan.

Aufruf: python infra/seed/seed_dev.py   (Ziel: TAKTOVIA_DB_NAME und Zugangsdaten wie db/db.py)
"""
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / 'tests' / 'acceptance'))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from taktovia_at import seed  # noqa: E402  (setzt auch den Pfad nach db/)
import db as dbtool  # noqa: E402
from synthetic_scan import scan  # noqa: E402


def main():
    name = dbtool.env('TAKTOVIA_DB_NAME', 'taktovia')
    with dbtool.admin(name) as c:
        rows = c.execute('SELECT environment FROM taktovia_meta.environment_marker').fetchall()
    environment = rows[0][0] if len(rows) == 1 else None
    if environment != 'development':
        print(f'Seed abgewiesen: Datenbank {name} traegt die Umgebungskennung {environment!r}; der Seed laeuft nur in der Entwicklung (development).', file=sys.stderr)
        return 2
    report = seed.seed_two_tenants(name)
    problems = scan(name)
    print(json.dumps({"seed": report["seed"], "version": report["seed_version"], "datenstand_sha256": report["data_state_sha256"], "synthetik_funde": len(problems)}))
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
