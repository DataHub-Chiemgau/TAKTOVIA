"""WP-M4-04 / LH-6.23-SCH-004: Dienstplanrevision und Entwurfssichtbarkeit unter der echten Runtime-Rolle. Entwurfszeilen (`roster_revision`, `staff_shift` im Zustand DRAFT)
sind ohne das transaktionslokale Planerrecht (`app.roster_draft_access`, von der Anwendung nach Rollen- und Depotprüfung gesetzt) auch per Direktabfrage nicht sichtbar und nicht
schreibbar; je Dienstplan gibt es höchstens einen Entwurf und eine veröffentlichte Revision; Revisionen zählen lückenlos, Depot und Woche sind fest."""
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()
FLAG = "SELECT set_config('app.roster_draft_access', 'on', true)"


def depot(tenant):
    region, d = uid(), uid()
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.region(tenant_id,id,name,created_by) VALUES (%s,%s,'R','user:t')", (tenant, region))
        c.execute("INSERT INTO taktovia.depot(tenant_id,id,region_id,name,created_by) VALUES (%s,%s,%s,'Depot','user:t')", (tenant, d, region))
    return d


def revision(c, tenant, depot_id, roster, rev=1, week='2026-11-30'):
    rid = uid()
    c.execute("INSERT INTO taktovia.roster_revision(tenant_id,id,roster_id,depot_id,week_start,revision,author) VALUES (%s,%s,%s,%s,%s,%s,'user:planer')", (tenant, rid, roster, depot_id, week, rev))
    return rid


def shift(c, tenant, roster, rev, depot_id, state='DRAFT', driver=None):
    sid = uid()
    c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,roster_id,roster_revision,depot_id,driver_ref,starts_at,ends_at,timezone,state) "
              "VALUES (%s,%s,%s,%s,%s,%s,'2026-12-01T06:00:00Z','2026-12-01T14:00:00Z','Europe/Berlin',%s)", (tenant, sid, roster, rev, depot_id, driver, state))
    return sid


class Dienstplanrevision(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm404-a'); seed_tenant(B, 'm404-b')
        cls.dA, cls.dB = depot(A), depot(B)

    def test_entwurfszeilen_nur_mit_planerrecht(self):  # AK 1, 3 (SCH-004)
        roster = uid()
        with tx(A) as c:
            c.execute(FLAG)
            revision(c, A, self.dA, roster); sid = shift(c, A, roster, 1, self.dA)
        with tx(A) as c:   # kein Planerrecht: Entwurf unsichtbar, auch per Direktabfrage
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.roster_revision WHERE roster_id=%s', (roster,)).fetchone()[0], 0)
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.staff_shift WHERE id=%s', (sid,)).fetchone()[0], 0)
        with tx(A) as c:   # Schreiben ohne Recht scheitert an der restriktiven Policy
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): revision(c, A, self.dA, uid(), week='2026-12-07')
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): shift(c, A, roster, 1, self.dA)
        with tx(A) as c:   # mit Planerrecht sichtbar
            c.execute(FLAG)
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.roster_revision WHERE roster_id=%s', (roster,)).fetchone()[0], 1)
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.staff_shift WHERE id=%s', (sid,)).fetchone()[0], 1)
        with tx(B) as c:   # fremder Mandant: auch mit Planerrecht nichts
            c.execute(FLAG)
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.roster_revision WHERE roster_id=%s', (roster,)).fetchone()[0], 0)
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.staff_shift WHERE id=%s', (sid,)).fetchone()[0], 0)

    def test_veroeffentlichte_revision_bleibt_ohne_planerrecht_lesbar_und_unveraendert(self):  # AK 2
        roster = uid()
        with tx(A) as c:
            c.execute(FLAG)
            r1 = revision(c, A, self.dA, roster, week='2026-12-07'); s1 = shift(c, A, roster, 1, self.dA)
            c.execute("UPDATE taktovia.roster_revision SET state='PUBLISHED', published_at=now(), published_by='user:x' WHERE id=%s", (r1,))
            c.execute("UPDATE taktovia.staff_shift SET state='PUBLISHED' WHERE id=%s", (s1,))
            r2 = revision(c, A, self.dA, roster, 2, week='2026-12-07'); shift(c, A, roster, 2, self.dA)
        with tx(A) as c:   # ohne Recht: nur die veröffentlichte Revision und ihre Schicht
            self.assertEqual(c.execute('SELECT revision, state FROM taktovia.roster_revision WHERE roster_id=%s', (roster,)).fetchall(), [(1, 'PUBLISHED')])
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.staff_shift WHERE roster_id=%s', (roster,)).fetchone()[0], 1)
        with tx(A) as c:   # veröffentlichte Revision ist unveränderlich, auch ihre Felder
            c.execute(FLAG)
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.roster_revision SET author='user:y' WHERE id=%s", (r1,))
        with tx(A) as c:
            c.execute(FLAG)
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.roster_revision SET entity_revision = entity_revision + 1 WHERE id=%s", (r1,))

    def test_je_dienstplan_ein_entwurf_und_eine_veroeffentlichte_revision_lueckenlos(self):
        roster = uid()
        with tx(A) as c:
            c.execute(FLAG)
            r1 = revision(c, A, self.dA, roster, week='2026-12-14')
            with self.assertRaises(psycopg.errors.UniqueViolation): revision(c, A, self.dA, roster, 2, week='2026-12-14')   # zweiter Entwurf
        with tx(A) as c:
            c.execute(FLAG)
            with self.assertRaises(psycopg.errors.CheckViolation): revision(c, A, self.dA, uid(), 2, week='2026-12-21')      # erste Revision muss 1 sein
        with tx(A) as c:
            c.execute(FLAG)
            r1 = revision(c, A, self.dA, roster, week='2026-12-21')
            c.execute("UPDATE taktovia.roster_revision SET state='PUBLISHED', published_at=now(), published_by='user:x' WHERE id=%s", (r1,))
            with self.assertRaises(psycopg.errors.CheckViolation): revision(c, A, self.dA, roster, 3, week='2026-12-21')      # Lücke
        with tx(A) as c:
            c.execute(FLAG)
            with self.assertRaises(psycopg.errors.CheckViolation): revision(c, A, self.dA, roster, 2, week='2027-01-04')     # andere Woche im selben Dienstplan

    def test_woche_beginnt_am_montag_und_je_depot_und_woche_gibt_es_einen_dienstplan(self):
        with tx(A) as c:
            c.execute(FLAG)
            with self.assertRaises(psycopg.errors.CheckViolation): revision(c, A, self.dA, uid(), week='2026-12-02')
        with tx(A) as c:
            c.execute(FLAG)
            revision(c, A, self.dA, uid(), week='2027-02-01')
            with self.assertRaises(psycopg.errors.UniqueViolation): revision(c, A, self.dA, uid(), week='2027-02-01')

    def test_zustandsuebergaenge_und_fremdes_depot(self):
        roster = uid()
        with tx(A) as c:
            c.execute(FLAG)
            r1 = revision(c, A, self.dA, roster, week='2027-02-08')
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.roster_revision SET state='SUPERSEDED', published_at=now(), published_by='x' WHERE id=%s", (r1,))
        with tx(A) as c:
            c.execute(FLAG)
            with self.assertRaises(psycopg.errors.ForeignKeyViolation): revision(c, A, self.dB, uid(), week='2027-02-15')   # Depot eines anderen Mandanten
        with tx(A) as c:
            c.execute(FLAG)
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): c.execute('DELETE FROM taktovia.roster_revision')


if __name__ == '__main__':
    unittest.main()
