"""WP-M2-05 / LH-6.9-GEO-002: Haltepunktrevisionen unter der echten Runtime-Rolle: Koordinate im Wertebereich, (stop_id, revision) eindeutig, höchstens eine wirksame Revision
je Haltepunkt, Aktivstatus je Revision, Deaktivierung als neue Revision (bestehende Verweise behalten ihre Revision), mindestens ein Verkehrsprodukt, Mandantentrennung."""
import json
import threading
import unittest

import psycopg
from helpers import tx, uid, seed_tenant

A, B = uid(), uid()
OK_ACC = json.dumps({'schema_version': '1.0', 'step_free': 'YES', 'wheelchair_boarding': 'UNVERIFIED'})


def add_stop(c, tenant, stop_id=None, revision=1, lon=13.74, lat=51.05, valid_from='2026-01-01Z', valid_to=None, active=True, products=None, approved='user:test'):
    rid = uid()
    c.execute("INSERT INTO taktovia.stop_revision(tenant_id,id,stop_id,revision,point,name,accessibility,approved_by,valid_from,valid_to,active,traffic_products,created_by) "
              "VALUES (%s,%s,%s,%s,ST_SetSRID(ST_MakePoint(%s,%s),4326),'Marktplatz',%s::jsonb,%s,%s,%s,%s,COALESCE(%s::text[], ARRAY['AREA_ON_DEMAND']),'user:test')",
              (tenant, rid, stop_id or uid(), revision, lon, lat, OK_ACC, approved, valid_from, valid_to, active, products))
    return rid


EFFECTIVE = ("SELECT id, revision FROM taktovia.stop_revision WHERE stop_id=%s AND active AND valid_from <= %s AND (valid_to IS NULL OR %s < valid_to) "
             "AND 'AREA_ON_DEMAND' = ANY(traffic_products)")


class StopRevision(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm205-a'); seed_tenant(B, 'm205-b')

    def test_inaktive_haltestelle_nicht_buchbar(self):
        sid = uid()
        with tx(A) as c:
            r1 = add_stop(c, A, sid, valid_to='2026-06-01Z')
            add_stop(c, A, sid, revision=2, valid_from='2026-06-01Z', active=False)       # Deaktivierung = neue Revision
        with tx(A) as c:
            self.assertEqual([r[0] for r in c.execute(EFFECTIVE, (sid, '2026-03-01Z', '2026-03-01Z')).fetchall()], [r1])
            self.assertEqual(c.execute(EFFECTIVE, (sid, '2026-06-01Z', '2026-06-01Z')).fetchall(), [], 'ab der Deaktivierung nicht mehr buchbar')
            self.assertEqual(c.execute(EFFECTIVE, (sid, '2025-12-31Z', '2025-12-31Z')).fetchall(), [], 'vor valid_from nicht wirksam')
            # die alte Revision bleibt unverändert lesbar (Haltepunktsnapshot bestehender Zusagen)
            self.assertEqual(c.execute("SELECT active, valid_to IS NOT NULL FROM taktovia.stop_revision WHERE id=%s", (r1,)).fetchone(), (True, True))

    def test_koordinate_ausserhalb_wertebereich_und_doppelte_revision_abgewiesen(self):
        for lon, lat in ((200.0, 10.0), (-181.0, 0.0), (10.0, 91.0), (10.0, -90.5)):
            with self.assertRaises(psycopg.errors.CheckViolation):
                with tx(A) as c: add_stop(c, A, lon=lon, lat=lat)
        sid = uid()
        with tx(A) as c: add_stop(c, A, sid, valid_to='2026-02-01Z')
        with self.assertRaises(psycopg.errors.UniqueViolation):
            with tx(A) as c: add_stop(c, A, sid, revision=1, valid_from='2026-03-01Z')   # (stop_id, revision) vergeben
        with self.assertRaises(psycopg.errors.ExclusionViolation):
            with tx(A) as c: add_stop(c, A, sid, revision=2, valid_from='2026-01-15Z')   # zwei wirksame Revisionen desselben Haltepunkts

    def test_mindestens_ein_verkehrsprodukt_und_gueltige_form(self):
        for products in ('{}', '{kleinbuchstaben}', '{A1,NULL}'):
            with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InvalidTextRepresentation)):
                with tx(A) as c: add_stop(c, A, products=products)
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: add_stop(c, A, products='{' + ','.join(f'P{i}X' for i in range(11)) + '}')
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: add_stop(c, A, approved='')
        with tx(A) as c:   # Standard für Altbestand ohne Angabe: alle bekannten Verkehrsprodukte
            rid = uid()
            c.execute("INSERT INTO taktovia.stop_revision(tenant_id,id,stop_id,revision,point,name,accessibility,approved_by,valid_from) VALUES (%s,%s,%s,1,ST_SetSRID(ST_MakePoint(1,1),4326),'Alt','{}'::jsonb,'synthetic','2026-01-01Z')", (A, rid, uid()))
            self.assertEqual(c.execute("SELECT active, cardinality(traffic_products) FROM taktovia.stop_revision WHERE id=%s", (rid,)).fetchone(), (True, 3))

    def test_revisionen_unveraenderlich_nur_ende_der_offenen_wird_gesetzt(self):
        with tx(A) as c:
            rid = add_stop(c, A)
            c.execute("UPDATE taktovia.stop_revision SET valid_to='2026-06-01Z' WHERE id=%s", (rid,))
        for sql in ("UPDATE taktovia.stop_revision SET valid_to='2026-07-01Z' WHERE id=%s", "UPDATE taktovia.stop_revision SET active=false WHERE id=%s"):
            with self.assertRaises(psycopg.errors.CheckViolation):
                with tx(A) as c: c.execute(sql, (rid,))
        with tx(A) as c: open_rev = add_stop(c, A)
        for sql in ("UPDATE taktovia.stop_revision SET active=false, valid_to='2027-01-01Z' WHERE id=%s", "UPDATE taktovia.stop_revision SET name='X', valid_to='2027-01-01Z' WHERE id=%s"):
            with self.assertRaises(psycopg.errors.CheckViolation):
                with tx(A) as c: c.execute(sql, (open_rev,))
        with self.assertRaises(psycopg.errors.Error):
            with tx(A) as c: c.execute("DELETE FROM taktovia.stop_revision WHERE id=%s", (rid,))

    def test_deaktivierung_parallel_zur_lesenden_angebotsbildung_nie_halbe_revision(self):
        sid = uid()
        with tx(A) as c: add_stop(c, A, sid, valid_from='2026-01-01Z')
        stop = threading.Event(); seen = []

        def reader():
            while not stop.is_set():
                with tx(A) as c:
                    seen.append(c.execute("SELECT revision, active FROM taktovia.stop_revision WHERE stop_id=%s AND valid_from <= now() AND (valid_to IS NULL OR now() < valid_to)", (sid,)).fetchall())
        ts = [threading.Thread(target=reader) for _ in range(3)]
        [t.start() for t in ts]
        with tx(A) as c:   # Schließen der alten und Anlegen der neuen Revision in einer Transaktion
            c.execute("UPDATE taktovia.stop_revision SET valid_to=clock_timestamp() WHERE stop_id=%s AND revision=1", (sid,))
            c.execute("INSERT INTO taktovia.stop_revision(tenant_id,id,stop_id,revision,point,name,accessibility,approved_by,valid_from,active,created_by) "
                      "SELECT tenant_id,%s,stop_id,2,point,name,accessibility,approved_by,valid_to,false,'user:test' FROM taktovia.stop_revision WHERE stop_id=%s AND revision=1", (uid(), sid))
        stop.set(); [t.join() for t in ts]
        self.assertTrue(seen)
        for rows in seen:
            self.assertEqual(len(rows), 1, f'genau eine wirksame Revision, nie keine oder zwei: {rows}')
            self.assertIn(rows[0], [(1, True), (2, False)])

    def test_mandantentrennung_rls(self):
        sid = uid()
        with tx(A) as c: add_stop(c, A, sid)
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.stop_revision WHERE stop_id=%s", (sid,)).fetchone()[0], 0)
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(B) as c: add_stop(c, A)


if __name__ == '__main__':
    unittest.main()
