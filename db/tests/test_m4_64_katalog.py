"""WP-M4-64: Katalogtest für alle M4-Tabellen. Jede Tabelle der M4-Module muss im Katalog (db/m4_tabellenkatalog.csv) stehen und RLS, Policy, Schlüssel- und
Indexkonvention, Eigentümermodul und Unveränderlichkeit erfüllen; eine Tabelle ohne Eintrag oder ohne RLS/Policy lässt den Test scheitern."""
import unittest
from helpers import admin
import m4_katalog as K

SCRATCH = 'm4_katalog_probe'


class Katalog(unittest.TestCase):
    def test_alle_m4_tabellen_sind_im_katalog_und_erfuellen_die_regeln(self):  # AK 1-3, 5
        cat = K.load_catalog()
        with admin() as c:
            owners = dict(c.execute('SELECT tabelle, modul FROM taktovia_meta.table_owner').fetchall())
            m4_tables = sorted(t for t, m in owners.items() if m in K.M4_MODULES and t not in K.M3_STAFFTIME_TABLES and K.exists(c, 'taktovia', t))
            self.assertEqual([t for t in m4_tables if t not in cat], [], 'M4-Tabelle ohne Katalogeintrag (db/m4_tabellenkatalog.csv ergänzen)')
            existing = [t for t in cat if K.exists(c, 'taktovia', t)]
            self.assertGreaterEqual(len(existing), 12)
            problems = [p for t in existing for p in K.check_table(c, 'taktovia', t, cat[t], owners)]
            self.assertEqual(problems, [])

    def test_katalogpruefung_erkennt_tabellen_ohne_rls_policy_eigentuemer_index_oder_trigger(self):  # AK 5
        entry = {'klasse': 'APPEND_ONLY', 'modul': 'staff-time', 'hr_finanz_spalten': ''}
        plain = dict(entry, klasse='VERAENDERLICH')
        with admin() as c:
            c.execute(f'DROP SCHEMA IF EXISTS {SCRATCH} CASCADE')
            c.execute(f'CREATE SCHEMA {SCRATCH}')
            try:
                for t in ('no_rls', 'no_policy', 'ok', 'bad_index', 'append'):
                    c.execute(f'CREATE TABLE {SCRATCH}.{t} (tenant_id uuid NOT NULL, id uuid NOT NULL, x int, PRIMARY KEY (tenant_id, id))')
                for t in ('no_policy', 'ok', 'bad_index', 'append'):
                    c.execute(f'ALTER TABLE {SCRATCH}.{t} ENABLE ROW LEVEL SECURITY'); c.execute(f'ALTER TABLE {SCRATCH}.{t} FORCE ROW LEVEL SECURITY')
                for t in ('ok', 'bad_index', 'append'):
                    c.execute(f'CREATE POLICY tenant_isolation ON {SCRATCH}.{t} USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant())')
                c.execute(f'CREATE INDEX ON {SCRATCH}.bad_index (x)')
                owners = {t: 'staff-time' for t in ('no_rls', 'no_policy', 'ok', 'bad_index', 'append')}
                self.assertEqual(K.check_table(c, SCRATCH, 'ok', plain, owners), [])
                self.assertTrue(any('RLS nicht aktiviert' in p for p in K.check_table(c, SCRATCH, 'no_rls', plain, owners)))
                self.assertTrue(any('Policy tenant_isolation fehlt' in p for p in K.check_table(c, SCRATCH, 'no_policy', plain, owners)))
                self.assertTrue(any('Index' in p and 'tenant_id' in p for p in K.check_table(c, SCRATCH, 'bad_index', plain, owners)))
                self.assertTrue(any('reject_mutation' in p for p in K.check_table(c, SCRATCH, 'append', entry, owners)))
                self.assertTrue(any('Eigentümermodul' in p for p in K.check_table(c, SCRATCH, 'ok', plain, {})))   # Tabelle ohne Eigentümereintrag
                self.assertTrue(any('fehlt' in p for p in K.check_table(c, SCRATCH, 'gibt_es_nicht', plain, owners)))
            finally:
                c.execute(f'DROP SCHEMA IF EXISTS {SCRATCH} CASCADE')

    def test_tabellenbesitz_in_der_datenbank_entspricht_den_csv_dateien(self):
        import csv
        from pathlib import Path
        expected = {}
        for f in sorted((Path(__file__).resolve().parents[1] / 'table_owners.d').glob('*.csv')):
            with f.open(encoding='utf-8', newline='') as fh:
                for r in csv.DictReader(fh, delimiter=';'): expected[r['table']] = r['owner']
        with admin() as c:
            self.assertEqual(dict(c.execute('SELECT tabelle, modul FROM taktovia_meta.table_owner').fetchall()), expected)

    def test_runtime_hat_keinen_zugriff_auf_den_besitzspiegel(self):
        from helpers import runtime
        import psycopg
        c = runtime()
        try:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                c.execute('SELECT * FROM taktovia_meta.table_owner')
        finally:
            c.rollback(); c.close()


if __name__ == '__main__':
    unittest.main()
