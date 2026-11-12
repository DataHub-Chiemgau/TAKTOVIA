#!/usr/bin/env python3
"""Befüllt eine Staging- oder Performance-Datenbank mit dem synthetischen Referenzbestand und schreibt das Umgebungsprotokoll (WP-M5-02).

  python tests/performance/datagen/fill.py --scale reference --seed 20261201 --protocol tests/performance/out/umgebungsprotokoll.json [--provision]

Zugang wie `db/db.py` (TAKTOVIA_DB_NAME, Host, Passwörter aus der Umgebung; keine Zugangsdaten im Repository). Die Befüllung läuft ausschließlich unter der Runtime-Rolle; mit `--provision` legt der
Plattformbetrieb zuvor die Mandanten an (Administratorzugang, ein getrennter Schritt). Beide Mandanten werden parallel befüllt. Das Protokoll nennt Umgebungskennung, Migrationsstand, Seed, Skala,
Zeilenzahlen je Tabelle und Mandant, Datenstandsprüfsumme, Hardware und Dauer. Exitcode 0 nur, wenn Befüllung, Synthetikprüfung und RLS-Stichprobe bestanden sind.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
import threading
from dataclasses import asdict
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'db'))
sys.path.insert(0, str(REPO / 'tests' / 'performance'))
import db as dbtool  # noqa: E402
from datagen import generator as G  # noqa: E402
from perfkit import hardware  # noqa: E402


def runtime_connection():
    return dbtool.conn(G.RUNTIME_ROLE, dbtool.env('TAKTOVIA_DB_RUNTIME_PASSWORD'), autocommit=False)


def environment_state(name: str) -> dict:
    with dbtool.admin(name) as c:
        env = c.execute('SELECT environment FROM taktovia_meta.environment_marker').fetchall()
        hist = c.execute('SELECT script FROM taktovia_meta.schema_history ORDER BY script').fetchall()
        version = c.execute('SHOW server_version').fetchone()[0]
        postgis = c.execute("SELECT extversion FROM pg_extension WHERE extname = 'postgis'").fetchone()
        size = c.execute('SELECT pg_database_size(current_database())').fetchone()[0]
    versioned = sorted([r[0] for r in hist if r[0].startswith('V')], key=lambda s: int(s[1:].split('__')[0]))
    return {'umgebungskennung': env[0][0] if len(env) == 1 else None, 'postgresql': version, 'postgis': postgis[0] if postgis else None, 'migrationen_angewendet': len(hist),
            'letzte_versionsmigration': versioned[-1] if versioned else None, 'datenbankgroesse_bytes': size}


def run(scale: G.Scale, seed: int, provision: bool, tenants: int = 2) -> dict:
    name = dbtool.env('TAKTOVIA_DB_NAME', 'taktovia')
    t0 = dt.datetime.now(dt.timezone.utc)
    if provision:
        with dbtool.admin(name) as a:
            ids = G.provision_tenants(a, seed, tenants)
    else:
        ids = [G.tenant_id(seed, i) for i in range(1, tenants + 1)]
    reports: dict[int, G.Report] = {}; errors: dict[int, BaseException] = {}

    def work(i: int):
        try:
            c = runtime_connection()
            try:
                reports[i] = G.fill_tenant(c, ids[i], seed, scale, i + 1)
            finally:
                c.close()
        except BaseException as e:  # noqa: BLE001 - wird gemeldet
            errors[i] = e

    threads = [threading.Thread(target=work, args=(i,)) for i in range(tenants)]
    [t.start() for t in threads]; [t.join() for t in threads]
    if errors:
        raise SystemExit('Befüllung fehlgeschlagen: ' + '; '.join(f'Mandant {i + 1}: {e!r}' for i, e in errors.items()))
    checks = {}
    with runtime_connection() as c:
        counts = {ids[i]: G.row_counts(c, ids[i]) for i in range(tenants)}
        finds = {ids[i]: G.scan_synthetic(c, ids[i]) for i in range(tenants)}
        # RLS-Stichprobe: im Kontext von Mandant 1 ist von Mandant 2 nichts sichtbar (und umgekehrt)
        leak = 0
        for a, b in ((0, 1), (1, 0)) if tenants > 1 else ():
            c.execute("SELECT set_config('app.tenant_id', %s, true)", (ids[a],))
            for tbl in ('vehicle', 'booking', 'staff_shift', 'passenger_private'):
                leak += c.execute(f'SELECT count(*) FROM taktovia.{tbl} WHERE tenant_id = %s', (ids[b],)).fetchone()[0]  # sql-lint: ok festes Tabellenverzeichnis
            c.rollback()
    checks['rls_fremdzeilen_sichtbar'] = leak
    checks['synthetikfunde'] = sum(len(v) for v in finds.values())
    checks['mindestens_250_fahrzeuge_je_mandant'] = all(counts[t]['vehicle'] >= 250 for t in ids)
    proto = {
        'wp': 'WP-M5-02', 'erzeugt_am': t0.isoformat(), 'seed': seed, 'skala': asdict(scale), 'umgebung': environment_state(name), 'datenbank': name, 'hardware': hardware.collect(),
        'mandanten': [{'id': t, 'zeilen': counts[t], 'befuellung_sekunden': reports[i].seconds, 'datenstand_sha256': reports[i].data_state_sha256} for i, t in enumerate(ids)],
        'pruefungen': checks, 'hinweis': 'Synthetische Daten; keine Produktionsdaten; Zugangsdaten nicht im Protokoll. Hardware/Instanzgrößen: siehe infra/umgebungen/groessen.json (Zielgrößen) und das Feld hardware (tatsächlich eingesetzt).',
    }
    proto['bestanden'] = leak == 0 and checks['synthetikfunde'] == 0 and bool(checks['mindestens_250_fahrzeuge_je_mandant'])
    return proto


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--scale', choices=('reference', 'small'), default='reference')
    ap.add_argument('--seed', type=int, default=G.SEED_DEFAULT)
    ap.add_argument('--tenants', type=int, default=2)
    ap.add_argument('--provision', action='store_true', help='Mandanten anlegen (Betriebsschritt, Administratorzugang)')
    ap.add_argument('--protocol', default=str(REPO / 'tests' / 'performance' / 'out' / 'umgebungsprotokoll.json'))
    a = ap.parse_args(argv)
    proto = run(G.REFERENCE if a.scale == 'reference' else G.SMALL, a.seed, a.provision, a.tenants)
    p = Path(a.protocol); p.parent.mkdir(parents=True, exist_ok=True); p.write_text(json.dumps(proto, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(json.dumps({k: proto[k] for k in ('seed', 'datenbank', 'bestanden', 'pruefungen')}, ensure_ascii=False))
    return 0 if proto['bestanden'] else 1


if __name__ == '__main__':
    sys.exit(main())
