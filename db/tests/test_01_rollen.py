"""WP-M0-15: Rollen und Rechte unter der echten Runtime-Rolle."""
import csv, unittest
from pathlib import Path
import psycopg
from helpers import admin, migrator, runtime, set_tenant, uid, seed_tenant

MATRIX = Path(__file__).resolve().parents[1] / 'role_matrix.csv'
T1 = uid()


def matrix():
    with MATRIX.open(encoding='utf-8', newline='') as f:
        return {r['table']: set(filter(None, r['runtime_privileges'].split(','))) for r in csv.DictReader(f, delimiter=';')}


class Rollen(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(T1, 'rollen')

    def test_attribute_und_eigentum(self):  # AK 1
        with admin() as c:
            r = c.execute("SELECT rolsuper, rolbypassrls, rolcreaterole, rolcreatedb FROM pg_roles WHERE rolname='taktovia_runtime'").fetchone()
            self.assertEqual(r, (False, False, False, False))
            owned = c.execute("""SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                                 JOIN pg_roles o ON o.oid=c.relowner
                                 WHERE n.nspname IN ('taktovia','taktovia_meta') AND o.rolname='taktovia_runtime'""").fetchone()[0]
            self.assertEqual(owned, 0)
            sch = c.execute("SELECT count(*) FROM pg_namespace n JOIN pg_roles o ON o.oid=nspowner WHERE o.rolname='taktovia_runtime'").fetchone()[0]
            self.assertEqual(sch, 0)
            not_mig = c.execute("""SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                                   JOIN pg_roles o ON o.oid=c.relowner
                                   WHERE n.nspname='taktovia' AND c.relkind IN ('r','S','v') AND o.rolname<>'taktovia_migrator'""").fetchall()
            self.assertEqual(not_mig, [])  # M0-16 AK 4

    def test_ddl_truncate_setrole_scheitern(self):  # AK 2
        for stmt in ('CREATE TABLE taktovia.x(a int)', 'ALTER TABLE taktovia.vehicle ADD COLUMN x int',
                     'TRUNCATE taktovia.vehicle', 'SET ROLE taktovia_migrator', 'CREATE TABLE public.x(a int)',
                     'CREATE SCHEMA evil', 'DROP TABLE taktovia.vehicle'):
            c = runtime()
            try:
                set_tenant(c, T1)
                with self.assertRaises(psycopg.errors.Error, msg=stmt):
                    c.execute(stmt)
            finally:
                c.rollback(); c.close()

    def test_currency_nur_lesen(self):  # AK 5
        c = runtime()
        try:
            self.assertGreaterEqual(c.execute('SELECT count(*) FROM taktovia.currency').fetchone()[0], 1)  # ohne Tenantkontext
            for stmt in ("INSERT INTO taktovia.currency VALUES ('USD',2)", "UPDATE taktovia.currency SET exponent=3",
                         'DELETE FROM taktovia.currency'):
                with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=stmt):
                    c.execute(stmt)
                c.rollback()
        finally:
            c.close()

    def test_matrix_vollstaendig_und_wirksam(self):  # AK 4
        with admin() as c:
            tabs = {r[0] for r in c.execute("SELECT tablename FROM pg_tables WHERE schemaname='taktovia'")}
            m = matrix()
            self.assertEqual(tabs, set(m), 'Tabelle ohne Eintrag in role_matrix.csv (oder umgekehrt)')
            actual = {}
            for t, p in c.execute("""SELECT table_name, privilege_type FROM information_schema.role_table_grants
                                     WHERE table_schema='taktovia' AND grantee='taktovia_runtime'"""):
                actual.setdefault(t, set()).add(p)
            for t in tabs:
                self.assertEqual(actual.get(t, set()), m[t], t)

    def test_unveraenderliche_ohne_update_delete(self):
        for t in ('economic_event', 'audit_event', 'partner_contract_version', 'fare_quote'):
            c = runtime()
            try:
                set_tenant(c, T1)
                for stmt in (f'UPDATE taktovia.{t} SET tenant_id=tenant_id', f'DELETE FROM taktovia.{t}'):
                    with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=f'{t}: {stmt}'):
                        c.execute(stmt)
                    c.rollback(); set_tenant(c, T1)
            finally:
                c.close()

    def test_public_ohne_rechte(self):  # AK 6
        with admin() as c:
            r = c.execute("""SELECT has_schema_privilege('public','taktovia','USAGE'), has_schema_privilege('public','taktovia','CREATE'),
                                    has_schema_privilege('public','public','CREATE'),
                                    has_function_privilege('public','taktovia.current_tenant()','EXECUTE'),
                                    has_function_privilege('public','taktovia.reject_mutation()','EXECUTE')""").fetchone()
            self.assertEqual(r, (False, False, False, False, False))
            tab = c.execute("""SELECT count(*) FROM information_schema.role_table_grants
                               WHERE table_schema='taktovia' AND grantee='PUBLIC'""").fetchone()[0]
            self.assertEqual(tab, 0)

    def test_migrator_nicht_als_runtime(self):  # Integritätsgrenze: Runtime hat kein Migrator-Mitglied
        with admin() as c:
            m = c.execute("""SELECT count(*) FROM pg_auth_members am JOIN pg_roles r ON r.oid=am.roleid
                             JOIN pg_roles u ON u.oid=am.member WHERE u.rolname='taktovia_runtime'""").fetchone()[0]
            self.assertEqual(m, 0)

    def test_laufzeit_kein_tabellen_grant_ohne_tenant(self):
        c = runtime()
        try:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.vehicle').fetchone()[0], 0)  # fail-closed
        finally:
            c.close()


if __name__ == '__main__':
    unittest.main()
