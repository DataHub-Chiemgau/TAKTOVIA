"""WP-M2-04 / LH-6.9-GEO-001: Bediengebiete unter der echten Runtime-Rolle: ungültige Geometrie verletzt CHECK(ST_IsValid), Revisionskette je Gebietsschlüssel,
Überlappungsverbot je Dienst (auch bei echter Parallelität), Unveränderlichkeit, Mandantentrennung."""
import threading
import unittest

import psycopg
from helpers import tx, uid, seed_tenant
from geo_helpers import make_service, square

A, B = uid(), uid()
BOWTIE = 'MULTIPOLYGON(((0 0,1 1,1 0,0 1,0 0)))'      # Schleife: selbstüberschneidend
OPEN_RING = 'MULTIPOLYGON(((0 0,1 0,1 1,0 1)))'        # nicht geschlossen: schon als Geometrie nicht bildbar


def add_area(c, tenant, svc, revision=1, wkt=None, valid_from='2026-01-01Z', valid_to=None, key=None, srid=4326):
    rid = uid()
    c.execute("INSERT INTO taktovia.service_area(tenant_id,id,service_id,revision,area,valid_from,valid_to,area_key,created_by) "
              "VALUES (%s,%s,%s,%s,ST_GeomFromText(%s,%s),%s,%s,%s,'user:test')", (tenant, rid, svc, revision, wkt or square(13.7, 51.0), srid, valid_from, valid_to, key))
    return rid


class ServiceArea(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm204-a'); seed_tenant(B, 'm204-b')

    def test_ungueltiges_polygon_verletzt_check(self):
        with tx(A) as c: svc = make_service(c, A)
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: add_area(c, A, svc, wkt=BOWTIE)                       # direkter SQL-Insert: ST_IsValid
        with self.assertRaises(psycopg.errors.Error):
            with tx(A) as c: add_area(c, A, svc, wkt=OPEN_RING)                    # offener Ring
        with self.assertRaises(psycopg.errors.Error):
            with tx(A) as c: add_area(c, A, svc, srid=3857)                        # falsches SRID
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: add_area(c, A, svc, valid_from='2026-02-01Z', valid_to='2026-01-01Z')
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.service_area WHERE service_id=%s", (svc,)).fetchone()[0], 0)

    def test_ueberlappende_aktive_version_abgewiesen(self):
        with tx(A) as c:
            svc = make_service(c, A)
            add_area(c, A, svc, valid_from='2026-01-01Z', valid_to='2026-06-01Z')
            add_area(c, A, svc, valid_from='2026-06-01Z')                           # angrenzend: zulässig (rechts offen)
        with self.assertRaises(psycopg.errors.ExclusionViolation):
            with tx(A) as c: add_area(c, A, svc, valid_from='2026-05-31Z', valid_to='2026-07-01Z')

    def test_revision_je_gebietsschluessel_eindeutig_und_ohne_angabe_ist_die_eigene_id_der_schluessel(self):
        with tx(A) as c:
            svc = make_service(c, A)
            r1 = add_area(c, A, svc, valid_to='2026-06-01Z')
            self.assertEqual(c.execute("SELECT COALESCE(area_key, id) FROM taktovia.service_area WHERE id=%s", (r1,)).fetchone()[0], r1)
            add_area(c, A, svc, revision=2, valid_from='2026-06-01Z', key=r1)
        with self.assertRaises(psycopg.errors.UniqueViolation):
            with tx(A) as c: add_area(c, A, svc, revision=2, valid_from='2027-01-01Z', key=r1)

    def test_parallele_veroeffentlichungen_ueberlappend_genau_eine_gewinnt(self):
        with tx(A) as c: svc = make_service(c, A)
        barrier = threading.Barrier(2); results = []

        def worker(x0):
            try:
                with tx(A) as c:
                    barrier.wait(timeout=10)
                    add_area(c, A, svc, wkt=square(x0, 51.0), valid_from='2026-03-01Z', valid_to='2026-09-01Z')
                results.append('ok')
            except (psycopg.errors.ExclusionViolation, psycopg.errors.DeadlockDetected):
                results.append('verloren')
        ts = [threading.Thread(target=worker, args=(x,)) for x in (13.0, 14.0)]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual(sorted(results), ['ok', 'verloren'])

    def test_revisionen_sind_unveraenderlich_nur_ende_der_offenen_darf_gesetzt_werden(self):
        with tx(A) as c:
            svc = make_service(c, A); rid = add_area(c, A, svc)
            c.execute("UPDATE taktovia.service_area SET valid_to='2026-06-01Z' WHERE id=%s", (rid,))
        for sql in ("UPDATE taktovia.service_area SET valid_to='2026-07-01Z' WHERE id=%s",
                    "UPDATE taktovia.service_area SET revision=5 WHERE id=%s"):
            with self.assertRaises(psycopg.errors.CheckViolation):
                with tx(A) as c: c.execute(sql, (rid,))
        with self.assertRaises(psycopg.errors.Error):
            with tx(A) as c: c.execute("DELETE FROM taktovia.service_area WHERE id=%s", (rid,))
        with tx(A) as c:   # die Geometrie einer offenen Revision ist ebenfalls unveränderlich
            svc2 = make_service(c, A); open_rev = add_area(c, A, svc2)
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: c.execute("UPDATE taktovia.service_area SET area=ST_GeomFromText(%s,4326), valid_to='2027-01-01Z' WHERE id=%s", (square(1, 1), open_rev))

    def test_mandantentrennung_rls(self):
        with tx(A) as c:
            svc = make_service(c, A); add_area(c, A, svc)
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.service_area WHERE service_id=%s", (svc,)).fetchone()[0], 0)
        with self.assertRaises(psycopg.errors.Error):
            with tx(B) as c: add_area(c, B, svc)                                   # Dienst des fremden Mandanten
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(B) as c: add_area(c, A, svc)                                   # fremde tenant_id verletzt die Policy


if __name__ == '__main__':
    unittest.main()
