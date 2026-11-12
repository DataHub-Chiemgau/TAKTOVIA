"""WP-M2-25 / LH-6.4-BKG-013: `booking_status_history` unter der echten Runtime-Rolle: append-only (auch für die Administration), Katalog der Gründe, Anzahl der Einträge = `booking.revision`
und Zustand = letzter Eintrag (beim Commit geprüft), nur laut Automat erlaubte Übergänge, Mandantentrennung."""
import json
import unittest

import psycopg
from helpers import tx, uid, seed_tenant, admin
from test_route_stop import make_run
from test_capacity_hold_state import hold
from geo_helpers import make_service

A, B = uid(), uid()
SNAP = json.dumps({'pickup_earliest': '2026-11-02T09:00:00Z', 'gross_minor': 950})


def history(c, tenant, booking, rev, from_state, to_state, reason='HOLD_CONFIRMED'):
    c.execute("INSERT INTO taktovia.booking_status_history(tenant_id,id,booking_id,from_state,to_state,reason_code,actor_ref,actor_kind,booking_revision) VALUES (%s,%s,%s,%s,%s,%s,'user:test','OPERATOR',%s)",
              (tenant, uid(), booking, from_state, to_state, reason, rev))


def numbered_booking(c, tenant, run, number, service=None):
    """Buchung mit Buchungsnummer (= unter der Historienpflicht) samt erstem Eintrag, in einer Transaktion."""
    h = hold(c, tenant, run); service = service or make_service(c, tenant); bid = uid()
    c.execute("INSERT INTO taktovia.booking(tenant_id,id,hold_id,service_id,federation_ref,state,promise_snapshot,revision,booking_no,created_by) VALUES (%s,%s,%s,%s,%s,'CONFIRMING',%s::jsonb,1,%s,'user:test')",
              (tenant, bid, h, service, f"fed-{bid}", SNAP, number))
    history(c, tenant, bid, 1, None, 'CONFIRMING')
    return bid, h


def advance(c, tenant, bid, rev, from_state, to_state, reason):
    """Übergang mit Historieneintrag (ein Eintrag je Revision)."""
    c.execute("UPDATE taktovia.booking SET state=%s, revision=%s WHERE id=%s", (to_state, rev, bid))
    history(c, tenant, bid, rev, from_state, to_state, reason)


class BookingStatusHistory(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm225-a'); seed_tenant(B, 'm225-b')

    def make(self, tenant=A, number='BK-2026-000300'):
        with tx(tenant) as c:
            run, _, _ = make_run(c, tenant); bid, _ = numbered_booking(c, tenant, run, number)
        return bid

    def test_update_delete_abgewiesen(self):
        bid = self.make()
        with self.assertRaises(psycopg.errors.CheckViolation):                       # Trigger (auch für die Administration)
            with admin() as a: a.execute("UPDATE taktovia.booking_status_history SET reason_code='SAFETY' WHERE booking_id=%s", (bid,))
        with self.assertRaises(psycopg.errors.CheckViolation):
            with admin() as a: a.execute("DELETE FROM taktovia.booking_status_history WHERE booking_id=%s", (bid,))
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):                # Grants: Runtime ohne UPDATE/DELETE
            with tx(A) as c: c.execute("UPDATE taktovia.booking_status_history SET reason_code='SAFETY' WHERE booking_id=%s", (bid,))
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(A) as c: c.execute("DELETE FROM taktovia.booking_status_history WHERE booking_id=%s", (bid,))

    def test_historieneintraege_gleich_booking_revision(self):
        bid = self.make(number='BK-2026-000301')
        with tx(A) as c:
            advance(c, A, bid, 2, 'CONFIRMING', 'CONFIRMED', 'PAYMENT_AUTHORIZED')
            advance(c, A, bid, 3, 'CONFIRMED', 'CANCEL_PENDING', 'PASSENGER_REQUEST')
            advance(c, A, bid, 4, 'CANCEL_PENDING', 'CANCELED', 'CANCEL_COMPLETED')
        with tx(A) as c:
            n, rev, st = c.execute("SELECT (SELECT count(*) FROM taktovia.booking_status_history WHERE booking_id=b.id), b.revision, b.state FROM taktovia.booking b WHERE b.id=%s", (bid,)).fetchone()
            self.assertEqual((n, rev, st), (4, 4, 'CANCELED'))
        # Übergang ohne Historieneintrag: der Commit scheitert (Anzahl der Einträge <> Revision)
        bid2 = self.make(number='BK-2026-000302')
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: c.execute("UPDATE taktovia.booking SET state='CONFIRMED', revision=2 WHERE id=%s", (bid2,))
        # Eintrag ohne Übergang: ebenfalls (Revision unverändert, zwei Einträge)
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c:
                history(c, A, bid2, 2, 'CONFIRMING', 'CONFIRMED', 'PAYMENT_AUTHORIZED')
                c.execute("UPDATE taktovia.booking SET passenger_id = NULL WHERE id=%s", (bid2,))
        # Zustand der Buchung weicht vom letzten Eintrag ab
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c:
                c.execute("UPDATE taktovia.booking SET state='CONFIRMED', revision=2 WHERE id=%s", (bid2,))
                history(c, A, bid2, 2, 'CONFIRMING', 'RECONCILING', 'PAYMENT_UNDETERMINED')
        # keine Lücke und kein Duplikat: je (Buchung, Revision) höchstens ein Eintrag
        with self.assertRaises(psycopg.errors.UniqueViolation):
            with tx(A) as c: history(c, A, bid2, 1, None, 'CONFIRMING')

    def test_uebergang_ohne_grund_abgewiesen(self):
        bid = self.make(number='BK-2026-000303')
        with self.assertRaises(psycopg.errors.NotNullViolation):
            with tx(A) as c:
                c.execute("INSERT INTO taktovia.booking_status_history(tenant_id,id,booking_id,from_state,to_state,reason_code,actor_ref,actor_kind,booking_revision) VALUES (%s,%s,%s,'CONFIRMING','CONFIRMED',NULL,'user:t','OPERATOR',2)", (A, uid(), bid))
        # laut Automat unzulässiger Übergang wird auch als Eintrag abgewiesen
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: history(c, A, bid, 2, 'CONFIRMING', 'COMPLETED', 'TRIP_COMPLETED')

    def test_grund_nicht_im_katalog_abgewiesen(self):
        bid = self.make(number='BK-2026-000304')
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):
            with tx(A) as c: history(c, A, bid, 2, 'CONFIRMING', 'CONFIRMED', 'ERFUNDEN')
        with tx(A) as c:
            codes = {r[0] for r in c.execute("SELECT code FROM taktovia.booking_status_reason")}
        self.assertTrue({'HOLD_CONFIRMED', 'PASSENGER_REQUEST', 'OPERATOR_CANCELLATION', 'NO_SHOW', 'VEHICLE_OUTAGE', 'TRIP_COMPLETED', 'HOLD_EXPIRED'} <= codes)
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):                # der Katalog ist nur per Migration änderbar
            with tx(A) as c: c.execute("INSERT INTO taktovia.booking_status_reason(code,kind,passenger_text) VALUES ('NEU','EXECUTION','Neu')")

    def test_rls_fremder_mandant_nicht_lesbar(self):
        bid = self.make(number='BK-2026-000305')
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.booking_status_history WHERE booking_id=%s", (bid,)).fetchone()[0], 0)
        with self.assertRaises(psycopg.Error):
            with tx(B) as c: history(c, A, bid, 2, 'CONFIRMING', 'CONFIRMED', 'PAYMENT_AUTHORIZED')   # fremder Mandant: RLS WITH CHECK

    def test_tenant_id_unveraenderlich(self):
        bid = self.make(number='BK-2026-000306')
        with self.assertRaises(psycopg.errors.CheckViolation):
            with admin() as a: a.execute("UPDATE taktovia.booking_status_history SET tenant_id=%s WHERE booking_id=%s", (B, bid))


if __name__ == '__main__':
    unittest.main()
