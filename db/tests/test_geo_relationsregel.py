"""WP-M2-06 / LH-6.9-GEO-003: Zonenrolle an service_area und versionierte Zielpunktmenge (relation_rule) unter der echten Runtime-Rolle: eine wirksame Start- und eine wirksame
Zielzone je Dienst, Rolle BOTH zählt für beide, Mengenversionen ohne Überlappung und unveränderlich, Zielpunkte müssen Haltepunkte des Mandanten sein, Mandantentrennung."""
import threading
import unittest

import psycopg
from helpers import tx, uid, seed_tenant
from geo_helpers import make_service, square
from test_geo_service_area import add_area
from test_geo_stop_revision import add_stop

A, B = uid(), uid()


def add_zone(c, tenant, svc, role, x0=13.0, valid_from='2026-01-01Z', valid_to=None):
    rid = uid()
    c.execute("INSERT INTO taktovia.service_area(tenant_id,id,service_id,revision,area,valid_from,valid_to,created_by,zone_role) "
              "VALUES (%s,%s,%s,1,ST_GeomFromText(%s,4326),%s,%s,'user:test',%s)", (tenant, rid, svc, square(x0, 51.0), valid_from, valid_to, role))
    return rid


def add_rule(c, tenant, svc, revision, stops, valid_from='2026-01-01Z', valid_to=None):
    rid = uid()
    c.execute("INSERT INTO taktovia.relation_rule(tenant_id,id,service_id,revision,destination_stop_ids,valid_from,valid_to,reason,created_by) VALUES (%s,%s,%s,%s,%s,%s,%s,'Grund','user:test')",
              (tenant, rid, svc, revision, stops, valid_from, valid_to))
    return rid


class RelationRule(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm206-a'); seed_tenant(B, 'm206-b')

    def test_start_und_zielzone_getrennt_je_dienst_eine_wirksame(self):
        with tx(A) as c:
            svc = make_service(c, A)
            add_zone(c, A, svc, 'ORIGIN', 13.0, valid_to='2027-01-01Z'); add_zone(c, A, svc, 'DESTINATION', 14.0, valid_to='2027-01-01Z')   # getrennte Zonen desselben Zeitraums
        with self.assertRaises(psycopg.errors.ExclusionViolation):
            with tx(A) as c: add_zone(c, A, svc, 'ORIGIN', 15.0)                                 # zweite Startzone
        with self.assertRaises(psycopg.errors.ExclusionViolation):
            with tx(A) as c: add_zone(c, A, svc, 'BOTH', 16.0)                                   # BOTH kollidiert mit beiden
        with tx(A) as c: add_zone(c, A, svc, 'ORIGIN', 15.0, valid_from='2027-01-01Z')           # anderer Zeitraum: zulässig
        with tx(A) as c:
            svc2 = make_service(c, A); add_zone(c, A, svc2, 'BOTH', 13.0)
        for role in ('ORIGIN', 'DESTINATION', 'BOTH'):
            with self.assertRaises(psycopg.errors.ExclusionViolation):
                with tx(A) as c: add_zone(c, A, svc2, role, 20.0)
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: add_zone(c, A, svc2, 'ZIEL', 20.0, valid_from='2030-01-01Z')       # unbekannte Rolle

    def test_standardrolle_both_und_zonenrolle_unveraenderlich(self):
        with tx(A) as c:
            svc = make_service(c, A); rid = add_area(c, A, svc)                                  # ohne Rolle: BOTH (GEO-001 unverändert)
            self.assertEqual(c.execute("SELECT zone_role FROM taktovia.service_area WHERE id=%s", (rid,)).fetchone()[0], 'BOTH')
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: c.execute("UPDATE taktovia.service_area SET zone_role='ORIGIN', valid_to='2027-01-01Z' WHERE id=%s", (rid,))

    def test_mengenversionen_ohne_ueberlappung_unveraenderlich_und_parallel_genau_eine(self):
        with tx(A) as c:
            svc = make_service(c, A); ids = [uid(), uid()]
            for sid in ids: add_stop(c, A, sid)
        with tx(A) as c:
            add_rule(c, A, svc, 1, ids, valid_to='2026-06-01Z'); add_rule(c, A, svc, 2, [], valid_from='2026-06-01Z')   # leere Menge = keine Einschränkung
        with self.assertRaises(psycopg.errors.ExclusionViolation):
            with tx(A) as c: add_rule(c, A, svc, 3, ids, valid_from='2026-05-01Z', valid_to='2026-07-01Z')
        with self.assertRaises(psycopg.errors.UniqueViolation):
            with tx(A) as c: add_rule(c, A, svc, 2, ids, valid_from='2030-01-01Z')
        with tx(A) as c: svc2 = make_service(c, A)
        barrier = threading.Barrier(2); results = []

        def worker(n):
            try:
                with tx(A) as c:
                    barrier.wait(timeout=10); add_rule(c, A, svc2, n, ids, valid_from='2026-03-01Z', valid_to='2026-09-01Z')
                results.append('ok')
            except (psycopg.errors.ExclusionViolation, psycopg.errors.DeadlockDetected):
                results.append('verloren')
        ts = [threading.Thread(target=worker, args=(n,)) for n in (1, 2)]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual(sorted(results), ['ok', 'verloren'])
        with tx(A) as c:
            rid = add_rule(c, A, make_service(c, A), 1, ids)
            c.execute("UPDATE taktovia.relation_rule SET valid_to='2026-06-01Z' WHERE id=%s", (rid,))
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: c.execute("UPDATE taktovia.relation_rule SET valid_to='2026-07-01Z' WHERE id=%s", (rid,))
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(A) as c: c.execute("UPDATE taktovia.relation_rule SET destination_stop_ids='{}' WHERE id=%s", (rid,))
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(A) as c: c.execute("DELETE FROM taktovia.relation_rule WHERE id=%s", (rid,))

    def test_regel_fremder_mandant_unsichtbar_und_zielpunkt_referenz(self):
        with tx(A) as c:
            svc = make_service(c, A); sid = uid(); add_stop(c, A, sid)
            add_rule(c, A, svc, 1, [sid])
        with tx(B) as c:     # fremder Mandant: Regel unsichtbar
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.relation_rule WHERE service_id=%s", (svc,)).fetchone()[0], 0)
        with self.assertRaises((psycopg.errors.InsufficientPrivilege, psycopg.errors.ForeignKeyViolation)):
            with tx(B) as c: add_rule(c, A, svc, 2, [sid])                                       # fremde tenant_id (Policy oder Zielpunktprüfung, beides lehnt ab)
        with self.assertRaises(psycopg.errors.Error):
            with tx(B) as c: add_rule(c, B, svc, 1, [])                                           # Dienst eines anderen Mandanten
        # Zielpunkt muss ein Haltepunkt des eigenen Mandanten sein: unbekannte und fremde Kennungen werden abgewiesen
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):
            with tx(A) as c: add_rule(c, A, svc, 2, [uid()], valid_from='2030-01-01Z')
        with tx(B) as c:
            svc_b = make_service(c, B)
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):
            with tx(B) as c: add_rule(c, B, svc_b, 1, [sid])                                      # Haltepunkt gehört Mandant A


if __name__ == '__main__':
    unittest.main()
