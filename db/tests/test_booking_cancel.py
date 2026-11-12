"""WP-M2-26 / LH-6.4-BKG-005: Storno in der Datenbank: ein verbrauchter Hold wird nur zusammen mit dem Storno seiner Buchung freigegeben (auch gegen direktes SQL), Stornoparameter sind im
Konfigurationsregister mit Grenzen registriert."""
import unittest

import psycopg
from helpers import tx, uid, seed_tenant, admin
from test_route_stop import make_run
from test_booking_status_history import numbered_booking, advance

A = uid()


class BookingCancel(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm226-a')

    def consumed(self, number):
        with tx(A) as c:
            run, _, _ = make_run(c, A)
            bid, h = numbered_booking(c, A, run, number)
            c.execute("UPDATE taktovia.capacity_hold SET state='CONSUMED' WHERE id=%s", (h,))
        return bid, h

    def test_verbrauchter_hold_wird_nur_mit_dem_storno_der_buchung_freigegeben(self):
        bid, h = self.consumed('BK-2026-000400')
        # Buchung bestätigt: keine Freigabe, weder für die Runtime-Rolle noch für die Administration
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: c.execute("UPDATE taktovia.capacity_hold SET state='RELEASED' WHERE id=%s", (h,))
        with self.assertRaises(psycopg.errors.CheckViolation):
            with admin() as a: a.execute("UPDATE taktovia.capacity_hold SET state='RELEASED' WHERE id=%s", (h,))
        # Storno der Buchung und Freigabe in einer Transaktion: erlaubt; danach endgültig
        with tx(A) as c:
            advance(c, A, bid, 2, 'CONFIRMING', 'COMPENSATING', 'PASSENGER_REQUEST')
            c.execute("UPDATE taktovia.capacity_hold SET state='RELEASED' WHERE id=%s", (h,))
            advance(c, A, bid, 3, 'COMPENSATING', 'CANCELED', 'COMPENSATION_DONE')
        for target in ('HELD', 'CONSUMED', 'EXPIRED'):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=target):
                with tx(A) as c: c.execute("UPDATE taktovia.capacity_hold SET state=%s WHERE id=%s", (target, h))
        # ein Hold ohne Buchung bleibt endgültig verbraucht
        with tx(A) as c:
            run, _, _ = make_run(c, A)
            from test_capacity_hold_state import hold
            h2 = hold(c, A, run); c.execute("UPDATE taktovia.capacity_hold SET state='CONSUMED' WHERE id=%s", (h2,))
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: c.execute("UPDATE taktovia.capacity_hold SET state='RELEASED' WHERE id=%s", (h2,))

    def test_stornoparameter_sind_registriert_und_begrenzt(self):
        with admin() as c:
            rows = {r[0]: r[1:] for r in c.execute("SELECT parameter, value_type, min_value, max_value, default_value, allowed_levels FROM taktovia.policy_schema WHERE parameter LIKE 'cancellation_%'")}
        self.assertEqual(set(rows), {'cancellation_deadline_min', 'cancellation_reasons'})
        typ, lo, hi, default, levels = rows['cancellation_deadline_min']
        self.assertEqual((typ, lo, hi, default), ('integer', 0, 43200, 60)); self.assertEqual(set(levels), {'TENANT', 'REGION', 'SERVICE'})
        self.assertEqual(rows['cancellation_reasons'][0], 'object')
        self.assertEqual(set(rows['cancellation_reasons'][3]['reasons']), {'PASSENGER_REQUEST', 'OPERATOR_CANCELLATION', 'NO_SHOW', 'PAYMENT_FAILED', 'SAFETY', 'VEHICLE_OUTAGE'})

    def test_stornogruende_im_katalog_entsprechen_den_parametern(self):
        with tx(A) as c:
            codes = {r[0] for r in c.execute("SELECT code FROM taktovia.booking_status_reason WHERE kind='CANCELLATION'")}
        self.assertTrue({'PASSENGER_REQUEST', 'OPERATOR_CANCELLATION', 'NO_SHOW', 'PAYMENT_FAILED', 'SAFETY', 'VEHICLE_OUTAGE'} <= codes)


if __name__ == '__main__':
    unittest.main()
