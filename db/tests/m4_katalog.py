"""Prüflogik des M4-Tabellenkatalogs (WP-M4-64). Keine Testklasse: wird von test_m4_64_modulzugaenge.py und test_m4_64_katalog.py benutzt.

`check_table` liefert je Verstoß einen Text; eine leere Liste heißt: die Tabelle erfüllt Kap. 04 §4.5/§4.6 (RLS aktiviert und erzwungen, Policy `tenant_isolation`
mit USING und WITH CHECK gegen current_tenant(), Schlüssel und Indizes beginnend mit tenant_id, Runtime weder Eigentümer noch mit DDL-/TRUNCATE-Rechten,
Unveränderlichkeit gemäß Klasse, Eigentümermodul wie im Katalog)."""
import csv
from pathlib import Path

DB_DIR = Path(__file__).resolve().parents[1]
CATALOG = DB_DIR / 'm4_tabellenkatalog.csv'
M4_MODULES = ('staff-time', 'documents-finance', 'analytics')
# Tabellen im Besitz von staff-time, die zu M1/M3 gehören (Fahrerstamm, Fahrzeugstatus, Positionen, Einstiegscodes); sie stehen im M3-Katalog, nicht im M4-Katalog.
M3_STAFFTIME_TABLES = frozenset({'driver', 'qualification_revision', 'driver_plan_ack', 'position_sample', 'vehicle_status_history', 'vehicle_service_status', 'boarding_code'})
CLASSES = ('APPEND_ONLY', 'FREIGABE_UNVERAENDERLICH', 'VERAENDERLICH')


def load_catalog():
    with CATALOG.open(encoding='utf-8', newline='') as f:
        rows = list(csv.DictReader(f, delimiter=';'))
    out = {}
    for r in rows:
        assert r['tabelle'] not in out, f'Tabelle doppelt im Katalog: {r["tabelle"]}'
        assert r['modul'] in M4_MODULES, f'{r["tabelle"]}: unbekanntes Modul {r["modul"]}'
        assert r['klasse'] in CLASSES, f'{r["tabelle"]}: unbekannte Klasse {r["klasse"]}'
        out[r['tabelle']] = r
    return out


def exists(c, schema, table):
    return c.execute('SELECT to_regclass(%s)', (f'{schema}.{table}',)).fetchone()[0] is not None


def check_table(c, schema, table, entry, owners=None):
    """`c`: Administratorverbindung (liest nur den Katalog). `owners`: Mapping Tabelle -> Modul (taktovia_meta.table_owner) oder None (nicht prüfen)."""
    bad = []
    rel = c.execute("""SELECT c.oid, c.relrowsecurity, c.relforcerowsecurity, o.rolname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                       JOIN pg_roles o ON o.oid=c.relowner WHERE n.nspname=%s AND c.relname=%s""", (schema, table)).fetchone()
    if rel is None:
        return [f'{table}: Tabelle fehlt']
    oid, rls, force, owner = rel
    if not rls: bad.append(f'{table}: RLS nicht aktiviert')
    if not force: bad.append(f'{table}: RLS nicht erzwungen')
    pol = c.execute("SELECT qual, with_check FROM pg_policies WHERE schemaname=%s AND tablename=%s AND policyname='tenant_isolation'", (schema, table)).fetchone()
    if pol is None:
        bad.append(f'{table}: Policy tenant_isolation fehlt')
    else:
        if 'current_tenant()' not in (pol[0] or ''): bad.append(f'{table}: USING prüft nicht gegen current_tenant()')
        if 'current_tenant()' not in (pol[1] or ''): bad.append(f'{table}: WITH CHECK prüft nicht gegen current_tenant()')
    nn = c.execute("SELECT is_nullable FROM information_schema.columns WHERE table_schema=%s AND table_name=%s AND column_name='tenant_id'", (schema, table)).fetchone()
    if nn is None or nn[0] != 'NO': bad.append(f'{table}: tenant_id fehlt oder nullable')
    pk = c.execute("""SELECT a.attname FROM pg_index i JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=i.indkey[0] WHERE i.indrelid=%s AND i.indisprimary""", (oid,)).fetchone()
    if pk is None or pk[0] != 'tenant_id': bad.append(f'{table}: Primärschlüssel beginnt nicht mit tenant_id')
    for (idx, col) in c.execute("""SELECT k.relname, a.attname FROM pg_index i JOIN pg_class k ON k.oid=i.indexrelid
                                   JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=i.indkey[0]
                                   WHERE i.indrelid=%s AND NOT i.indisprimary AND i.indkey[0] <> 0""", (oid,)).fetchall():
        if col != 'tenant_id': bad.append(f'{table}: Index {idx} beginnt nicht mit tenant_id')
    if owner == 'taktovia_runtime' or c.execute("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname='taktovia_runtime'").fetchone()[0]:
        bad.append(f'{table}: Runtime-Rolle ist Eigentümer oder umgeht RLS')
    for priv in ('TRUNCATE', 'REFERENCES', 'TRIGGER'):
        if c.execute('SELECT has_table_privilege(%s, %s, %s)', ('taktovia_runtime', f'{schema}.{table}', priv)).fetchone()[0]:
            bad.append(f'{table}: Runtime hat {priv}')
    triggers = [r[0] for r in c.execute("""SELECT p.proname FROM pg_trigger t JOIN pg_proc p ON p.oid=t.tgfoid
                                           WHERE t.tgrelid=%s AND NOT t.tgisinternal AND (t.tgtype & 16) = 16""", (oid,)).fetchall()]   # BEFORE-UPDATE-Trigger (Bit 16)
    all_triggers = [r[0] for r in c.execute("""SELECT p.proname FROM pg_trigger t JOIN pg_proc p ON p.oid=t.tgfoid
                                               WHERE t.tgrelid=%s AND NOT t.tgisinternal""", (oid,)).fetchall()]
    if entry['klasse'] == 'APPEND_ONLY':
        if 'reject_mutation' not in all_triggers: bad.append(f'{table}: APPEND_ONLY ohne reject_mutation()-Trigger')
        for priv in ('UPDATE', 'DELETE'):
            if c.execute('SELECT has_table_privilege(%s, %s, %s)', ('taktovia_runtime', f'{schema}.{table}', priv)).fetchone()[0]:
                bad.append(f'{table}: APPEND_ONLY, Runtime hat {priv}')
    elif entry['klasse'] == 'FREIGABE_UNVERAENDERLICH':
        if not [t for t in triggers if t != 'reject_tenant_change']: bad.append(f'{table}: FREIGABE_UNVERAENDERLICH ohne Schutztrigger auf UPDATE')
    if owners is not None:
        if owners.get(table) != entry['modul']:
            bad.append(f'{table}: Eigentümermodul {owners.get(table)!r} statt {entry["modul"]!r} (db/table_owners.d)')
    for col in filter(None, entry.get('hr_finanz_spalten', '').split(',')):
        if c.execute("SELECT 1 FROM information_schema.columns WHERE table_schema=%s AND table_name=%s AND column_name=%s", (schema, table, col)).fetchone() is None:
            bad.append(f'{table}: geschützte Spalte {col} existiert nicht')
    return bad
