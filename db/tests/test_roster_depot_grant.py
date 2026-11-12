"""WP-M4-16 / LH-6.23-SCH-009: `roster_depot_grant` unter der echten Runtime-Rolle: Mandantentrennung, Vergabeform (Depot oder alle Depots), Entzug als einzige und endgültige Änderung,
Depot des eigenen Mandanten, höchstens eine wirksame Vergabe je Person und Depot, kein DELETE."""
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()


def depot(tenant):
    region, d = uid(), uid()
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.region(tenant_id,id,name,created_by) VALUES (%s,%s,'R','user:t')", (tenant, region))
        c.execute("INSERT INTO taktovia.depot(tenant_id,id,region_id,name,created_by) VALUES (%s,%s,%s,'D','user:t')", (tenant, d, region))
    return d


def grant(c, tenant, subject, kind='DEPOT', depot_id=None):
    gid = uid()
    c.execute("INSERT INTO taktovia.roster_depot_grant(tenant_id,id,subject,scope_kind,depot_id,granted_by) VALUES (%s,%s,%s,%s,%s,'user:admin')", (tenant, gid, subject, kind, depot_id))
    return gid


class Depotberechtigung(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm416-a'); seed_tenant(B, 'm416-b')
        cls.dA, cls.dB = depot(A), depot(B)

    def test_vergabeform_depot_oder_alle_depots(self):
        with tx(A) as c:
            grant(c, A, 'planer-1', 'DEPOT', self.dA); grant(c, A, 'planer-2', 'ALL_DEPOTS')
        for kind, d in (('DEPOT', None), ('ALL_DEPOTS', self.dA)):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation): grant(c, A, 'planer-3', kind, d)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): grant(c, A, 'Planer-Gross', 'ALL_DEPOTS')   # Subject kleingeschrieben

    def test_rls_fremder_mandant_sieht_nichts_und_depot_eines_anderen_mandanten_nicht_referenzierbar(self):
        with tx(A) as c: gid = grant(c, A, 'planer-rls', 'DEPOT', self.dA)
        with tx(B) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.roster_depot_grant WHERE id=%s', (gid,)).fetchone()[0], 0)
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): grant(c, A, 'x', 'ALL_DEPOTS')
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.ForeignKeyViolation): grant(c, A, 'planer-fk', 'DEPOT', self.dB)
        with tx(None) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.roster_depot_grant').fetchone()[0], 0)

    def test_entzug_ist_die_einzige_aenderung_und_endgueltig(self):
        with tx(A) as c: gid = grant(c, A, 'planer-entzug', 'DEPOT', self.dA)
        for stmt in ("UPDATE taktovia.roster_depot_grant SET subject='anderer' WHERE id=%s", "UPDATE taktovia.roster_depot_grant SET scope_kind='ALL_DEPOTS', depot_id=NULL WHERE id=%s",
                     "UPDATE taktovia.roster_depot_grant SET granted_by='user:x' WHERE id=%s"):
            with tx(A) as c:   # je Anweisung eine Transaktion (Mandantenkontext ist transaktionslokal)
                with self.assertRaises(psycopg.errors.CheckViolation): c.execute(stmt, (gid,))
        with tx(A) as c:
            c.execute("UPDATE taktovia.roster_depot_grant SET revoked_at=now(), revoked_by='user:admin' WHERE id=%s", (gid,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.roster_depot_grant SET revoked_at=NULL, revoked_by=NULL WHERE id=%s", (gid,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): c.execute('DELETE FROM taktovia.roster_depot_grant WHERE id=%s', (gid,))

    def test_hoechstens_eine_wirksame_vergabe_je_person_und_depot(self):
        with tx(A) as c:
            g = grant(c, A, 'planer-eindeutig', 'DEPOT', self.dA)
            with self.assertRaises(psycopg.errors.UniqueViolation): grant(c, A, 'planer-eindeutig', 'DEPOT', self.dA)
        with tx(A) as c:
            g = grant(c, A, 'planer-eindeutig2', 'DEPOT', self.dA)
            c.execute("UPDATE taktovia.roster_depot_grant SET revoked_at=now(), revoked_by='user:admin' WHERE id=%s", (g,))
            grant(c, A, 'planer-eindeutig2', 'DEPOT', self.dA)   # nach dem Entzug neu vergebbar


if __name__ == '__main__':
    unittest.main()
