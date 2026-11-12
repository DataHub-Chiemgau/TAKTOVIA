#!/usr/bin/env python3
"""Importpruefung fuer Datenbank-Dumps (WP-M1-40, LH-6.18-SEC-012): echte Fahrgastdaten gelangen nicht in Nicht-Produktionssysteme.

Jede Datenbank traegt eine unveraenderliche Umgebungskennung (`taktovia_meta.environment_marker`, V1400); sie steckt auch in jedem Dump.
Vor dem Einspielen liest dieses Werkzeug die Kennung des Dumps und die des Ziels und weist ab:

  - Dump ohne lesbare Umgebungskennung (Herkunft unbekannt), Verzeichnisformat, beschaedigtes Archiv
  - Dump der Umgebung `production` in jede andere Umgebung (Produktionsdaten werden nicht in Entwicklung kopiert, Kap. 01 §1.7)
  - jedes Ziel `production` (Wiederherstellung der Produktion ist Sache von WP-M1-39, nicht dieses Werkzeugs)
  - Ziel ohne Umgebungskennung oder gleichzeitiger zweiter Import in dieselbe Datenbank (Advisory-Lock)

Eine kontrollierte, anonymisierte Datenuebernahme gibt es nicht: bis zu einem eigenen Beschluss ist jede Uebernahme verboten.
Grenze: Die Kennung schuetzt vor Versehen und Fehlbedienung, nicht vor jemandem, der einen Dump absichtlich umetikettiert; dafuer
sorgt die Trennung der Zugaenge (kein Netzpfad und keine geteilten Zugangsdaten zwischen Entwicklung und Produktion, infra/umgebungsmatrix.json).

Aufruf: python db/db.py import-dump <datei>   (Ziel: TAKTOVIA_DB_NAME, Administratorzugang wie db.py). Ausgang 0 = eingespielt, 2 = abgewiesen.
Eingespielt wird nur das pg_restore-Custom-Format (pg_dump -Fc), ausschliesslich Daten; die Kennung des Ziels wird nie ueberschrieben.
"""
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import db as dbtool  # noqa: E402

MARKER = 'taktovia_meta.environment_marker'
VALUES = ('development', 'staging', 'performance', 'production')
# Tabellen, deren Inhalt die Migrationen selbst anlegen (Kennung, Historie, Waehrungskatalog): ihre Daten werden nie aus einem Dump uebernommen
MIGRATION_OWNED = r'TABLE DATA (taktovia_meta (environment_marker|schema_history|table_owner)|taktovia (currency|state_transition_rule|role_catalog|rights_matrix_rollout|policy_schema|policy_schema_version|config_vehicle_override_release|rejection_reason)) '
LOCK_KEY = 4_0_1_2  # Advisory-Lock "Import" je Datenbank


class ImportRefused(Exception):
    """Der Import ist technisch abgewiesen worden (nichts wurde eingespielt)."""


def _pg_env():
    return dict(os.environ, PGPASSWORD=dbtool.env('TAKTOVIA_DB_ADMIN_PASSWORD'))


def _pg_args(dbname=None):
    return ['-h', dbtool.env('TAKTOVIA_DB_HOST', '127.0.0.1'), '-p', dbtool.env('TAKTOVIA_DB_PORT', '5432'),
            '-U', dbtool.env('TAKTOVIA_DB_ADMIN_USER', 'dev_admin')] + (['-d', dbname] if dbname else [])


def _marker_from_sql(text):
    """Umgebungskennung aus Dump-Text (COPY-Block oder INSERT). Genau ein Treffer, sonst None."""
    found = []
    copy = re.search(r'COPY taktovia_meta\.environment_marker \(([^)]*)\) FROM stdin;\n(.*?)\n\\\.', text, re.S)
    if copy:
        cols = [c.strip() for c in copy.group(1).split(',')]
        if 'environment' in cols:
            i = cols.index('environment')
            for row in copy.group(2).split('\n'):
                cells = row.split('\t')
                if len(cells) == len(cols):
                    found.append(cells[i])
    for m in re.finditer(r"INSERT INTO taktovia_meta\.environment_marker[^;]*?VALUES \([^)]*?'(\w+)'", text):
        found.append(m.group(1))
    return found[0] if len(found) == 1 and found[0] in VALUES else None


def inspect_dump(path):
    """Umgebungskennung, die der Dump traegt, oder None (unlesbar, ohne Kennung, mehrdeutig). Custom- und Klartextformat."""
    p = Path(path)
    if not p.is_file():
        return None
    with p.open('rb') as f:
        head = f.read(5)
    if head == b'PGDMP':
        r = subprocess.run(['pg_restore', '--data-only', '--schema=taktovia_meta', '--table=environment_marker', '-f', '-', str(p)],
                           capture_output=True, text=True, env=_pg_env())
        return _marker_from_sql(r.stdout) if r.returncode == 0 else None
    try:
        return _marker_from_sql(p.read_text(encoding='utf-8', errors='strict'))
    except (UnicodeDecodeError, OSError):
        return None


def target_environment(dbname):
    try:
        with dbtool.admin(dbname) as c:
            rows = c.execute('SELECT environment FROM taktovia_meta.environment_marker').fetchall()
    except Exception:
        return None
    return rows[0][0] if len(rows) == 1 else None


def check_import(dump_env, target_env):
    """Entscheidungstabelle; wirft ImportRefused mit Begruendung. Reine Funktion, getrennt testbar."""
    if dump_env is None:
        raise ImportRefused('Dump ohne lesbare Umgebungskennung (Herkunft unbekannt, Verzeichnisformat oder beschaedigt): Import abgewiesen')
    if target_env is None:
        raise ImportRefused('Zieldatenbank ohne Umgebungskennung: Import abgewiesen')
    if target_env == 'production':
        raise ImportRefused('Ziel ist Produktion: Wiederherstellung der Produktion gehoert nicht zu diesem Werkzeug (WP-M1-39)')
    if dump_env == 'production':
        raise ImportRefused(f'Produktionsdump darf nicht in die Umgebung {target_env} importiert werden (Produktionsdaten werden nicht in Nicht-Produktion kopiert)')


def import_dump(path, dbname=None):
    """Prueft und spielt den Dump (pg_restore, nur Daten) in `dbname` (Standard: TAKTOVIA_DB_NAME) ein."""
    dbname = dbname or dbtool.env('TAKTOVIA_DB_NAME', 'taktovia')
    p = Path(path)
    if p.is_dir():
        raise ImportRefused('Verzeichnisformat ist nicht pruefbar: Import abgewiesen')
    check_import(inspect_dump(p), target_environment(dbname))      # vor jedem Lock und jeder Schreiboperation
    with p.open('rb') as f:
        if f.read(5) != b'PGDMP':
            raise ImportRefused('nur pg_restore-Archive (pg_dump -Fc) werden eingespielt; Klartextdumps sind nicht zugelassen')
    with dbtool.admin(dbname) as lock:
        if not lock.execute('SELECT pg_try_advisory_lock(%s)', (LOCK_KEY,)).fetchone()[0]:
            raise ImportRefused('ein anderer Import laeuft bereits in dieser Datenbank')
        try:
            # Die Pruefung vor dem Lock und die Pruefung unter dem Lock ergeben dasselbe; unter dem Lock wird erneut gelesen.
            check_import(inspect_dump(p), target_environment(dbname))
            # Inhaltsverzeichnis des Archivs ohne die Daten migrationseigener Tabellen (Kennung, Historie, Katalog des Ziels bleiben unangetastet)
            toc = subprocess.run(['pg_restore', '-l', str(p)], capture_output=True, text=True, env=_pg_env())
            if toc.returncode != 0:
                raise ImportRefused('Archiv nicht lesbar: Import abgewiesen')
            keep = [l for l in toc.stdout.splitlines() if not (' TABLE DATA ' in l and re.search(MIGRATION_OWNED, l))]
            with tempfile.NamedTemporaryFile('w', suffix='.list', delete=False, encoding='utf-8') as lst:
                lst.write('\n'.join(keep) + '\n')
            try:
                r = subprocess.run(['pg_restore', '--data-only', '--disable-triggers', '--exit-on-error', '-L', lst.name, *_pg_args(dbname), str(p)],
                                   capture_output=True, text=True, env=_pg_env())
            finally:
                os.unlink(lst.name)
            if r.returncode != 0:
                raise ImportRefused(f'pg_restore fehlgeschlagen: {r.stderr.strip()[:300]}')
        finally:
            lock.execute('SELECT pg_advisory_unlock(%s)', (LOCK_KEY,))


def main(argv):
    if len(argv) != 1:
        print(__doc__, file=sys.stderr)
        return 1
    try:
        import_dump(argv[0])
    except ImportRefused as e:
        print(f'Import abgewiesen: {e}', file=sys.stderr)
        return 2
    print('Import eingespielt (Umgebungskennung des Ziels unveraendert)')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
