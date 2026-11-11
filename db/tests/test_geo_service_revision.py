"""WP-M2-03: Dienstversionen mit Betriebszeiten unter der echten Runtime-Rolle: Überlappungsverbot (Exclusion Constraint, auch bei echter Parallelität),
Revisionskette eindeutig, Unveränderlichkeit (nur das Ende einer offenen Version darf gesetzt werden), Mandantentrennung."""
import json
import threading
import unittest

import psycopg
from helpers import tx, uid, seed_tenant
from geo_helpers import make_service

A, B = uid(), uid()
WEEKLY = json.dumps([{'weekdays': ['MON', 'TUE'], 'from': '06:00', 'to': '22:00'}])


def add_rev(c, tenant, svc, revision, valid_from, valid_to=None, product='AREA_ON_DEMAND'):
    rid = uid()
    c.execute("INSERT INTO taktovia.service_revision(tenant_id,id,service_id,revision,traffic_product,timezone,weekly_hours,exceptions,valid_from,valid_to,reason,created_by) "
              "VALUES (%s,%s,%s,%s,%s,'Europe/Berlin',%s::jsonb,'[]'::jsonb,%s,%s,'Grund','user:test')", (tenant, rid, svc, revision, product, WEEKLY, valid_from, valid_to))
    return rid


class ServiceRevision(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm203-a'); seed_tenant(B, 'm203-b')

    def test_ueberlappende_aktive_dienstversion_abgewiesen(self):
        with tx(A) as c:
            svc = make_service(c, A)
            add_rev(c, A, svc, 1, '2026-01-01Z', '2026-06-01Z')
            add_rev(c, A, svc, 2, '2026-06-01Z')           # angrenzend (halboffen) ist zulässig
        with self.assertRaises(psycopg.errors.ExclusionViolation):
            with tx(A) as c: add_rev(c, A, svc, 3, '2026-05-31Z', '2026-07-01Z')
        with self.assertRaises(psycopg.errors.ExclusionViolation):
            with tx(A) as c: add_rev(c, A, svc, 3, '2027-01-01Z')   # Revision 2 ist offen

    def test_revision_je_dienst_eindeutig(self):
        with tx(A) as c:
            svc = make_service(c, A); add_rev(c, A, svc, 1, '2026-01-01Z', '2026-02-01Z')
        with self.assertRaises(psycopg.errors.UniqueViolation):
            with tx(A) as c: add_rev(c, A, svc, 1, '2026-03-01Z', '2026-04-01Z')

    def test_zwei_parallele_aktivierungen_desselben_zeitraums_genau_eine_gewinnt(self):
        with tx(A) as c: svc = make_service(c, A)
        barrier = threading.Barrier(2); results = []

        def worker(rev):
            try:
                with tx(A) as c:
                    barrier.wait(timeout=10)
                    add_rev(c, A, svc, rev, '2026-03-01Z', '2026-09-01Z')
                results.append('ok')
            except (psycopg.errors.ExclusionViolation, psycopg.errors.DeadlockDetected, psycopg.errors.SerializationFailure):
                results.append('verloren')
        ts = [threading.Thread(target=worker, args=(r,)) for r in (1, 2)]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual(sorted(results), ['ok', 'verloren'])
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.service_revision WHERE service_id=%s", (svc,)).fetchone()[0], 1)

    def test_nur_das_ende_einer_offenen_version_darf_gesetzt_werden(self):
        with tx(A) as c:
            svc = make_service(c, A); rid = add_rev(c, A, svc, 1, '2026-01-01Z')
            c.execute("UPDATE taktovia.service_revision SET valid_to='2026-06-01Z' WHERE id=%s", (rid,))   # Ende setzen: zulässig
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: c.execute("UPDATE taktovia.service_revision SET valid_to='2026-07-01Z' WHERE id=%s", (rid,))   # geschlossen: unveränderlich
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):   # Spaltenrecht: nur valid_to
            with tx(A) as c: c.execute("UPDATE taktovia.service_revision SET traffic_product='LINE_ON_DEMAND' WHERE id=%s", (rid,))
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(A) as c: c.execute("DELETE FROM taktovia.service_revision WHERE id=%s", (rid,))

    def test_ungueltige_werte_abgewiesen(self):
        with tx(A) as c: svc = make_service(c, A)
        for kwargs in ({'valid_to': '2025-12-31Z'}, {'product': 'kleinbuchstaben'}):
            with self.assertRaises(psycopg.errors.CheckViolation):
                with tx(A) as c: add_rev(c, A, svc, 9, '2026-01-01Z', kwargs.get('valid_to'), kwargs.get('product', 'AREA_ON_DEMAND'))
        with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InvalidParameterValue)):   # unbekannte Zone
            with tx(A) as c:
                c.execute("INSERT INTO taktovia.service_revision(tenant_id,id,service_id,revision,traffic_product,timezone,weekly_hours,exceptions,valid_from,reason,created_by) "
                          "VALUES (%s,%s,%s,9,'X1','Kaputt/Zone','[]'::jsonb,'[]'::jsonb,'2026-01-01Z','g','user:test')", (A, uid(), svc))

    def test_fremder_mandant_unsichtbar_und_nicht_referenzierbar(self):
        with tx(A) as c:
            svc = make_service(c, A); add_rev(c, A, svc, 1, '2026-01-01Z')
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.service_revision WHERE service_id=%s", (svc,)).fetchone()[0], 0)
        with self.assertRaises((psycopg.errors.ForeignKeyViolation, psycopg.errors.InsufficientPrivilege)):
            with tx(B) as c: add_rev(c, B, svc, 1, '2026-01-01Z')
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(B) as c: add_rev(c, A, svc, 2, '2026-01-01Z')


if __name__ == '__main__':
    unittest.main()
