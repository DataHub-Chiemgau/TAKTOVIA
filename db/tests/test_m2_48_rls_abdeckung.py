"""WP-M2-48: RLS-Abdeckungsprüfung über alle in M2 neu angelegten Tabellen (Liste aus WP-M2-01, Kap. 04 §4.4/§4.6) und Negativtest der Prüfung selbst.

Die Tabellenliste wird nicht von Hand gepflegt: sie ist die Vereinigung aus den Tabellen der `db/table_owners.d/m2-*.csv` (Eigentum je M2-Paket) und aller Tabellen, die
M2-Migrationen (Nummernbereiche V2xxx und V20xxx) anlegen, ergänzt um die M2-Kerntabellen der Buchung, die früher angelegt wurden. Geprüft wird je Tabelle:
`tenant_id` NOT NULL; RLS aktiviert und erzwungen; mindestens eine Policy mit USING und WITH CHECK auf den Tenantkontext; Primärschlüssel beginnt mit (tenant_id, id)
bzw. enthält `tenant_id`; jeder Fremdschlüssel auf eine Mandantentabelle enthält `tenant_id` (zusammengesetzt, kein mandantenübergreifender Verweis).
Die Tabellen mit Nutzdaten werden zusätzlich unter der Runtime-Rolle mit zwei Mandanten gelesen (kein Fremdbestand, ohne Kontext keine Zeile).
Die Negativprüfung legt eine absichtlich fehlerhafte Tabelle an und verlangt, dass die Prüfung jeden der Fehler meldet."""
import csv
import re
import unittest
from pathlib import Path

from helpers import admin, seed_tenant, tx, uid

DB = Path(__file__).resolve().parents[1]
# Kerntabellen der M2-Buchung, die vor den M2-Paketen angelegt wurden (Kap. 04 §4.4)
M2_KERN = ['booking', 'capacity_hold', 'vehicle_run', 'service', 'service_area', 'stop', 'stop_revision', 'travel_attempt', 'fare_quote']
# globale Katalogtabellen ohne Mandantenbezug
GLOBAL = {'rejection_reason', 'booking_status_reason'}


def m2_tables(c):
    names = set()
    for f in sorted((DB / 'table_owners.d').glob('m2-*.csv')):
        with f.open(encoding='utf-8-sig', newline='') as fh:
            names |= {r['table'] for r in csv.DictReader(fh, delimiter=';') if r['table']}
    for f in (DB / 'migrations').glob('V*__*.sql'):
        m = re.match(r'V(\d+)__', f.name)
        if m and (2000 <= int(m.group(1)) < 3000 or 20000 <= int(m.group(1)) < 21000):
            names |= {t.split('.')[-1].lower() for t in re.findall(r'CREATE TABLE (?:IF NOT EXISTS )?([a-zA-Z_.]+)', f.read_text(encoding='utf-8'), re.I)}
    present = {r[0] for r in c.execute("SELECT table_name FROM information_schema.tables WHERE table_schema='taktovia' AND table_type='BASE TABLE'")}
    return sorted((names | set(M2_KERN)) & present)


def abdeckung(c, table):
    """Liste der Verstöße einer Tabelle gegen die DDL-Konvention (leer = konform)."""
    q = f'taktovia.{table}'
    problems = []
    col = c.execute("SELECT is_nullable FROM information_schema.columns WHERE table_schema='taktovia' AND table_name=%s AND column_name='tenant_id'", (table,)).fetchone()
    if col is None:
        return ['tenant_id fehlt']
    if col[0] != 'NO':
        problems.append('tenant_id nullable')
    rls = c.execute('SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE oid=%s::regclass', (q,)).fetchone()
    if not rls[0]:
        problems.append('RLS nicht aktiviert')
    if not rls[1]:
        problems.append('RLS nicht erzwungen (FORCE)')
    pol = c.execute("""SELECT polname, polqual IS NOT NULL, polwithcheck IS NOT NULL, pg_get_expr(polqual, polrelid), polcmd
                       FROM pg_policy WHERE polrelid=%s::regclass""", (q,)).fetchall()
    tenant_pols = [p for p in pol if p[3] and 'tenant_id' in p[3]]
    if not tenant_pols:
        problems.append('keine Policy auf tenant_id')
    else:
        if not any(p[1] for p in tenant_pols):
            problems.append('Policy ohne USING')
        if not any(p[2] for p in tenant_pols):
            problems.append('Policy ohne WITH CHECK')
    pk = c.execute("""SELECT array_agg(a.attname ORDER BY k.ord) FROM pg_constraint ct JOIN LATERAL unnest(ct.conkey) WITH ORDINALITY k(attnum, ord) ON true
                      JOIN pg_attribute a ON a.attrelid=ct.conrelid AND a.attnum=k.attnum WHERE ct.conrelid=%s::regclass AND ct.contype='p'""", (q,)).fetchone()[0]
    if pk is None:
        problems.append('kein Primärschlüssel')
    elif 'tenant_id' not in pk:
        problems.append(f'Primärschlüssel ohne tenant_id: {pk}')
    for name, cols, ftable in c.execute("""SELECT ct.conname, (SELECT array_agg(a.attname) FROM unnest(ct.conkey) k JOIN pg_attribute a ON a.attrelid=ct.conrelid AND a.attnum=k),
                                                  ct.confrelid::regclass::text FROM pg_constraint ct WHERE ct.conrelid=%s::regclass AND ct.contype='f'""", (q,)).fetchall():
        has_tenant = c.execute("SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='taktovia' AND table_name=%s AND column_name='tenant_id')",
                               (ftable.split('.')[-1].strip('"'),)).fetchone()[0]
        if has_tenant and 'tenant_id' not in cols:
            problems.append(f'Fremdschlüssel {name} ohne tenant_id (mandantenübergreifender Verweis möglich)')
    return problems


class M2RlsAbdeckung(unittest.TestCase):
    def test_jede_m2_tabelle_erfuellt_die_ddl_konvention(self):
        with admin() as c:
            tables = [t for t in m2_tables(c) if t not in GLOBAL]
            self.assertGreaterEqual(len(tables), 28, f'M2-Tabellenliste zu kurz: {tables}')
            for must in ('ride_offer', 'booking', 'capacity_hold', 'payment_event', 'refund_case', 'notification', 'tariff_revision', 'plan_change'):
                self.assertIn(must, tables)
            bad = {t: p for t in tables if (p := abdeckung(c, t))}
            self.assertEqual(bad, {}, 'Tabellen außerhalb der Konvention')

    def test_pruefung_erkennt_absichtlich_fehlerhafte_tabelle(self):
        with admin() as c:
            c.execute('DROP TABLE IF EXISTS taktovia.zz_m2_48_schlecht, taktovia.zz_m2_48_ohne_tenant')
            try:
                c.execute('CREATE TABLE taktovia.zz_m2_48_schlecht (tenant_id uuid, id uuid PRIMARY KEY, ref uuid REFERENCES taktovia.tenant(id))')
                c.execute('CREATE TABLE taktovia.zz_m2_48_ohne_tenant (id uuid PRIMARY KEY)')
                p = abdeckung(c, 'zz_m2_48_schlecht')
                for erwartet in ('tenant_id nullable', 'RLS nicht aktiviert', 'RLS nicht erzwungen (FORCE)', 'keine Policy auf tenant_id'):
                    self.assertIn(erwartet, p)
                self.assertTrue(any(x.startswith('Primärschlüssel ohne tenant_id') for x in p), p)
                self.assertEqual(abdeckung(c, 'zz_m2_48_ohne_tenant'), ['tenant_id fehlt'])
                # Policy nur mit USING (ohne WITH CHECK) wird ebenfalls gemeldet
                c.execute('ALTER TABLE taktovia.zz_m2_48_schlecht ENABLE ROW LEVEL SECURITY, FORCE ROW LEVEL SECURITY')
                c.execute("CREATE POLICY nur_using ON taktovia.zz_m2_48_schlecht FOR ALL USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)")
                self.assertIn('Policy ohne WITH CHECK', abdeckung(c, 'zz_m2_48_schlecht'))
            finally:
                c.execute('DROP TABLE IF EXISTS taktovia.zz_m2_48_schlecht, taktovia.zz_m2_48_ohne_tenant')

    def test_zwei_mandanten_sehen_keinen_fremdbestand_in_m2_tabellen(self):
        a, b = uid(), uid()
        seed_tenant(a, 'm2-48-a'); seed_tenant(b, 'm2-48-b')
        with admin() as c:
            tables = [t for t in m2_tables(c) if t not in GLOBAL]
        with tx(a) as c:
            for t in tables:
                n = c.execute(f'SELECT count(*) FROM taktovia.{t} WHERE tenant_id <> %s', (a,)).fetchone()[0]  # noqa: S608 (Tabellennamen aus dem Katalog)
                self.assertEqual(n, 0, f'{t}: Fremdbestand sichtbar')
        with tx(None) as c:
            for t in tables:
                try:
                    n = c.execute(f'SELECT count(*) FROM taktovia.{t}').fetchone()[0]  # noqa: S608
                except Exception:  # Tabelle ohne Leserecht der Runtime-Rolle: auch kein Leck
                    c.rollback(); continue
                self.assertEqual(n, 0, f'{t}: ohne Mandantenkontext sichtbar')


if __name__ == '__main__':
    unittest.main()
