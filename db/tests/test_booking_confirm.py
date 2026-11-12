"""WP-M2-23 / LH-6.4-BKG-003: `booking` unter der echten Runtime-Rolle: Zusage (`promise_snapshot`) nach dem Anlegen unveränderlich (auch für die Administration), Zustand durch den Automaten `booking`
gehärtet (auch gegen direktes SQL), Revision nur +1, Buchungsnummer je Mandant eindeutig und im Format, Zähler steigt nur um 1 und ist mandantengetrennt, eine Buchung je Hold."""
import json
import unittest

import psycopg
from helpers import tx, uid, seed_tenant, admin
from test_route_stop import make_run
from test_capacity_hold_state import hold
from geo_helpers import make_service

A, B = uid(), uid()
SNAP = json.dumps({'pickup_earliest': '2026-11-02T09:00:00Z', 'gross_minor': 950})


def booking(c, tenant, run, number=None, state='CONFIRMING', service=None):
    h = hold(c, tenant, run)
    service = service or make_service(c, tenant)
    bid = uid()
    c.execute("INSERT INTO taktovia.booking(tenant_id,id,hold_id,service_id,federation_ref,state,promise_snapshot,revision,booking_no,created_by) VALUES (%s,%s,%s,%s,%s,%s,%s::jsonb,1,%s,'user:test')",
              (tenant, bid, h, service, f"fed-{bid}", state, SNAP, number))
    return bid, h


class BookingConfirm(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm223-a'); seed_tenant(B, 'm223-b')

    def test_zusage_ist_nach_dem_anlegen_unveraenderlich(self):
        with tx(A) as c:
            run, _, _ = make_run(c, A); bid, _ = booking(c, A, run, 'BK-2026-000001')
        for sql in ("promise_snapshot = '{}'::jsonb", "promise_snapshot = jsonb_set(promise_snapshot, '{gross_minor}', '1')"):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=sql):
                with tx(A) as c: c.execute(f"UPDATE taktovia.booking SET {sql}, revision = revision + 1 WHERE id=%s", (bid,))
            with self.assertRaises(psycopg.errors.CheckViolation, msg=sql):
                with admin() as a: a.execute(f"UPDATE taktovia.booking SET {sql}, revision = revision + 1 WHERE id=%s", (bid,))
        # Identität (Hold, Dienst, Nummer, Urheber) ebenfalls unveränderlich
        for sql in ("booking_no = 'BK-2026-000009'", "created_by = 'user:anders'", "service_id = gen_random_uuid()"):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=sql):
                with tx(A) as c: c.execute(f"UPDATE taktovia.booking SET {sql}, revision = revision + 1 WHERE id=%s", (bid,))

    def test_zustand_automat_und_revision_nur_plus_eins(self):
        with tx(A) as c:
            run, _, _ = make_run(c, A); bid, _ = booking(c, A, run, 'BK-2026-000002')
        with self.assertRaises(psycopg.errors.CheckViolation):                      # CONFIRMING -> COMPLETED ist laut Automat nicht erlaubt
            with tx(A) as c: c.execute("UPDATE taktovia.booking SET state='COMPLETED', revision=2 WHERE id=%s", (bid,))
        with self.assertRaises(psycopg.errors.CheckViolation):                      # unbekannter Zustand
            with tx(A) as c: c.execute("UPDATE taktovia.booking SET state='DONE', revision=2 WHERE id=%s", (bid,))
        with self.assertRaises(psycopg.errors.CheckViolation):                      # Revision springt
            with tx(A) as c: c.execute("UPDATE taktovia.booking SET state='CONFIRMED', revision=5 WHERE id=%s", (bid,))
        with tx(A) as c:
            c.execute("UPDATE taktovia.booking SET state='CONFIRMED', revision=2 WHERE id=%s", (bid,))
            c.execute("UPDATE taktovia.booking SET state='CANCEL_PENDING', revision=3 WHERE id=%s", (bid,))
            self.assertEqual(c.execute("SELECT state, revision FROM taktovia.booking WHERE id=%s", (bid,)).fetchone(), ('CANCEL_PENDING', 3))

    def test_buchungsnummer_je_mandant_eindeutig_zaehler_und_format(self):
        with tx(A) as c:
            run, _, _ = make_run(c, A); svc = make_service(c, A); booking(c, A, run, 'BK-2026-000100', service=svc)
        with self.assertRaises(psycopg.errors.UniqueViolation):
            with tx(A) as c: booking(c, A, run, 'BK-2026-000100', service=svc)
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: booking(c, A, run, 'KEINE-NUMMER', service=svc)
        with tx(B) as c:                                                           # gleiche Nummer in einem anderen Mandanten ist zulässig
            runb, _, _ = make_run(c, B); booking(c, B, runb, 'BK-2026-000100')
        # Zähler: je Mandant lückenlos ab 1, nur +1, kein Löschen, Mandanten getrennt
        with tx(A) as c:
            self.assertEqual([c.execute("SELECT taktovia.next_booking_number()").fetchone()[0] for _ in range(3)][0][-6:], '000001')
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT taktovia.next_booking_number()").fetchone()[0][-6:], '000001')
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.booking_number_counter").fetchone()[0], 1)
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: c.execute("UPDATE taktovia.booking_number_counter SET last_number = last_number + 5")
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):                # die Runtime-Rolle darf nicht löschen
            with tx(A) as c: c.execute("DELETE FROM taktovia.booking_number_counter")
        with self.assertRaises(psycopg.errors.CheckViolation):                       # auch die Administration nicht (Trigger)
            with admin() as a: a.execute("DELETE FROM taktovia.booking_number_counter WHERE tenant_id=%s", (A,))

    def test_eine_buchung_je_hold(self):
        with tx(A) as c:
            run, _, _ = make_run(c, A); svc = make_service(c, A); bid, h = booking(c, A, run, 'BK-2026-000200', service=svc)
        with self.assertRaises(psycopg.errors.UniqueViolation):
            with tx(A) as c:
                c.execute("INSERT INTO taktovia.booking(tenant_id,id,hold_id,service_id,federation_ref,state,promise_snapshot,revision) VALUES (%s,%s,%s,%s,%s,'CONFIRMING','{}'::jsonb,1)", (A, uid(), h, svc, f"fed-{uid()}"))


if __name__ == '__main__':
    unittest.main()
