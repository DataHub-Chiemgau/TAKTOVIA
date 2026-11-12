"""WP-M4-15 / LH-6.23-SCH-010: Unveränderlichkeit veröffentlichter Dienstplanrevisionen und Änderungsprotokoll je Schicht unter der echten Runtime-Rolle (RLS, Trigger, Rechte).
Die Runtime-Rolle hat kein DELETE auf `roster_revision`; die Datenbankregel gilt zusätzlich für den Administrator (Trigger)."""
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


def published_roster(tenant, depot_id, week):
    """Dienstplan mit Revision 1 (veröffentlicht, eine verbindliche Schicht) und Revision 2 (Entwurf); gibt (roster, r1, shift1) zurück."""
    roster, r1, s1 = uid(), uid(), uid()
    with tx(tenant) as c:
        c.execute(FLAG)
        c.execute("INSERT INTO taktovia.roster_revision(tenant_id,id,roster_id,depot_id,week_start,revision,author) VALUES (%s,%s,%s,%s,%s,1,'user:planer')", (tenant, r1, roster, depot_id, week))
        c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,roster_id,roster_revision,depot_id,starts_at,ends_at,timezone,state) VALUES (%s,%s,%s,1,%s,'2026-12-01T06:00:00Z','2026-12-01T14:00:00Z','Europe/Berlin','DRAFT')", (tenant, s1, roster, depot_id))
        c.execute("UPDATE taktovia.roster_revision SET state='PUBLISHED', published_at=now(), published_by='user:x', effective_from=now(), publish_reason='Freigabe' WHERE id=%s", (r1,))
        c.execute("UPDATE taktovia.staff_shift SET state='PUBLISHED' WHERE id=%s", (s1,))
    return roster, r1, s1


class Revisionshistorie(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm415-a'); seed_tenant(B, 'm415-b')
        cls.dA = depot(A)

    def test_veroeffentlichte_revision_unveraenderlich(self):  # AK 3
        roster, r1, _ = published_roster(A, self.dA, '2026-11-30')
        for sql in ("UPDATE taktovia.roster_revision SET publish_reason='nachtraeglich geaendert' WHERE id=%s",
                    "UPDATE taktovia.roster_revision SET published_by='user:y' WHERE id=%s",
                    "UPDATE taktovia.roster_revision SET effective_from=now() + interval '1 day' WHERE id=%s",
                    "UPDATE taktovia.roster_revision SET entity_revision = entity_revision + 1 WHERE id=%s"):
            with tx(A) as c:
                c.execute(FLAG)
                with self.assertRaises(psycopg.errors.CheckViolation, msg=sql): c.execute(sql, (r1,))
        with tx(A) as c:   # Löschen: Runtime-Rolle ohne Recht; die Datenbankregel gilt auch für den Administrator
            c.execute(FLAG)
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): c.execute('DELETE FROM taktovia.roster_revision WHERE id=%s', (r1,))
        with admin() as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute('DELETE FROM taktovia.roster_revision WHERE id=%s', (r1,))
        with tx(A) as c:   # beim Ersetzen ändert sich nur der Zustand; Begleitfelder bleiben gesperrt
            c.execute(FLAG)
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.roster_revision SET state='SUPERSEDED', publish_reason='anderer Grund' WHERE id=%s", (r1,))
        with tx(A) as c:
            c.execute(FLAG)
            c.execute("UPDATE taktovia.roster_revision SET state='SUPERSEDED' WHERE id=%s", (r1,))
        with tx(A) as c:   # ersetzte Revision bleibt gesperrt
            c.execute(FLAG)
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.roster_revision SET publish_reason='x y z' WHERE id=%s", (r1,))

    def test_aenderungsprotokoll_nur_anhaengen_mit_autor_und_zeit(self):
        roster, r1, s1 = published_roster(A, self.dA, '2026-12-07')
        lineage = None
        with tx(A) as c:
            c.execute(FLAG)
            lineage = c.execute('SELECT lineage_id FROM taktovia.staff_shift WHERE id=%s', (s1,)).fetchone()[0]
            self.assertEqual(lineage, s1, 'die Schichtkennung ist ohne Vorgabe die eigene ID')
            c.execute("INSERT INTO taktovia.roster_shift_change(tenant_id,id,roster_id,roster_revision,lineage_id,shift_id,change_kind,shift_revision,actor,snapshot) "
                      "VALUES (%s,%s,%s,1,%s,%s,'PUBLISHED',1,'user:planer','{\"state\":\"PUBLISHED\"}')", (A, uid(), roster, lineage, s1))
        with tx(A) as c:
            row = c.execute('SELECT actor, occurred_at, change_kind FROM taktovia.roster_shift_change WHERE lineage_id=%s', (lineage,)).fetchone()
            self.assertEqual((row[0], row[2]), ('user:planer', 'PUBLISHED')); self.assertIsNotNone(row[1])
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): c.execute("UPDATE taktovia.roster_shift_change SET actor='user:x' WHERE lineage_id=%s", (lineage,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): c.execute('DELETE FROM taktovia.roster_shift_change WHERE lineage_id=%s', (lineage,))
        with admin() as c:   # auch der Administrator kann das Protokoll nicht ändern oder löschen (Trigger)
            with self.assertRaises(psycopg.errors.Error): c.execute("UPDATE taktovia.roster_shift_change SET actor='user:x' WHERE lineage_id=%s", (lineage,))
        with tx(B) as c:   # fremder Mandant sieht nichts
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.roster_shift_change WHERE lineage_id=%s', (lineage,)).fetchone()[0], 0)

    def test_entwurfsprotokoll_nur_mit_entwurfsrecht_und_schichtkennung_unveraenderlich(self):
        roster, r1, s1 = uid(), uid(), uid()
        with tx(A) as c:
            c.execute(FLAG)
            c.execute("INSERT INTO taktovia.roster_revision(tenant_id,id,roster_id,depot_id,week_start,revision,author) VALUES (%s,%s,%s,%s,'2026-12-14',1,'user:planer')", (A, r1, roster, self.dA))
            c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,roster_id,roster_revision,depot_id,starts_at,ends_at,timezone,state) VALUES (%s,%s,%s,1,%s,'2026-12-15T06:00:00Z','2026-12-15T14:00:00Z','Europe/Berlin','DRAFT')", (A, s1, roster, self.dA))
            c.execute("INSERT INTO taktovia.roster_shift_change(tenant_id,id,roster_id,roster_revision,lineage_id,shift_id,change_kind,shift_revision,actor,snapshot) "
                      "VALUES (%s,%s,%s,1,%s,%s,'CREATED',1,'user:planer','{}')", (A, uid(), roster, s1, s1))
        with tx(A) as c:
            c.execute(FLAG)
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute('UPDATE taktovia.staff_shift SET lineage_id=%s WHERE id=%s', (uid(), s1))
        with tx(A) as c:   # ohne Entwurfsrecht ist auch das Protokoll des Entwurfs unsichtbar
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.roster_shift_change WHERE roster_id=%s', (roster,)).fetchone()[0], 0)
        with tx(A) as c:
            c.execute(FLAG)
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.roster_shift_change WHERE roster_id=%s', (roster,)).fetchone()[0], 1)


if __name__ == '__main__':
    unittest.main()
