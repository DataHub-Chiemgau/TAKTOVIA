"""WP-M1-07 / LH-6.2-IAM-004: Rollenkatalog und Prüfung von membership.role_code; Ausrollprotokoll der Rechtematrix (nur anhängen)."""
import re, unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant

T = uid()
ROLES = {'passenger', 'driver', 'callcenter', 'support', 'dispatcher', 'planner', 'tenant_admin'}   # die sieben Pflichtrollen (IAM-004)
PLACEHOLDERS = {'finance', 'staff_planning', 'workshop'}


class Rollenkatalog(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(T, 'm107')

    def test_katalog_enthaelt_sieben_pflichtrollen_und_platzhalter(self):
        with admin() as c:
            rows = dict(c.execute('SELECT role_code, placeholder FROM taktovia.role_catalog').fetchall())
        self.assertEqual({r for r, p in rows.items() if not p}, ROLES)
        self.assertEqual({r for r, p in rows.items() if p}, PLACEHOLDERS)

    def test_check_und_katalog_sind_synchron(self):
        with admin() as c:
            definition = c.execute("""SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='membership_role_code_catalog'""").fetchone()[0]
            catalog = {r[0] for r in c.execute('SELECT role_code FROM taktovia.role_catalog')}
        self.assertEqual(set(re.findall(r"'([a-z_]+)'", definition)), catalog)

    def test_unbekannter_role_code_per_check_abgewiesen(self):
        for bad in ('superuser', 'ADMIN', 'tenant-admin', '', 'dispatcher '):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=bad):
                with tx(T) as c:
                    c.execute("INSERT INTO taktovia.membership(tenant_id,id,issuer,subject,role_code) VALUES (%s,%s,'i',%s,%s)", (T, uid(), f's-{uid()}', bad))
        for good in sorted(ROLES | PLACEHOLDERS):   # alle Katalogrollen sind zulässig
            with tx(T) as c:
                c.execute("INSERT INTO taktovia.membership(tenant_id,id,issuer,subject,role_code) VALUES (%s,%s,'i',%s,%s)", (T, uid(), f's-{uid()}', good))

    def test_rolle_ist_nach_dem_erteilen_unveraenderlich(self):
        with tx(T) as c:
            mid = uid(); s = f's-{uid()}'
            c.execute("INSERT INTO taktovia.membership(tenant_id,id,issuer,subject,role_code) VALUES (%s,%s,'i',%s,'support')", (T, mid, s))
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(T) as c: c.execute("UPDATE taktovia.membership SET role_code='tenant_admin' WHERE id=%s", (mid,))

    def test_katalog_ist_fuer_die_runtime_nur_lesbar(self):
        with tx(None) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.role_catalog').fetchone()[0], len(ROLES | PLACEHOLDERS))
        for sql in ("INSERT INTO taktovia.role_catalog VALUES ('x_rolle','X',false)", "UPDATE taktovia.role_catalog SET label_de='x'", 'DELETE FROM taktovia.role_catalog'):
            with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=sql):
                with tx(None) as c: c.execute(sql)


class Ausrollprotokoll(unittest.TestCase):
    def insert(self, c, sha, version=1, by='service:test'):
        c.execute("INSERT INTO taktovia.rights_matrix_rollout(matrix_version,matrix_sha256,previous_sha256,role_count,operation_count,rolled_out_by) VALUES (%s,%s,NULL,10,50,%s)", (version, sha, by))

    def test_nur_anhaengen_auch_fuer_die_administration(self):
        sha = uid().hex + uid().hex
        with tx(None) as c: self.insert(c, sha)
        for who in ('runtime', 'admin'):
            for sql in ('UPDATE taktovia.rights_matrix_rollout SET role_count=1', 'DELETE FROM taktovia.rights_matrix_rollout'):
                with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InsufficientPrivilege), msg=f'{who}: {sql}'):
                    if who == 'runtime':
                        with tx(None) as c: c.execute(sql)
                    else:
                        with admin() as c: c.execute(sql)

    def test_formatpruefung(self):
        for sha, by in (('xyz', 'service:t'), ('a' * 64, 'Max Mustermann'), ('A' * 64, 'service:t')):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=f'{sha[:4]}/{by}'):
                with tx(None) as c: self.insert(c, sha, by=by)


if __name__ == '__main__':
    unittest.main()
