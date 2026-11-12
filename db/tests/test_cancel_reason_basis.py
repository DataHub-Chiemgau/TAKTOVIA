"""WP-M2-27 / LH-6.4-BKG-014: Stornoursache in der Datenbank: Verursacherklasse folgt dem Grund (CHECK), der Grund ist nach dem Setzen unveränderlich (auch für die Administration), nur in einem
Stornozustand setzbar, und die Abfrage auf Buchung und Statushistorie (die Datenbasis der Stornoquoten) trennt Fahrgaststorno, Betreiberstorno und Fahrzeugausfall (Datenbasis der Stornoquoten, Mandantentrennung per RLS)."""
import unittest

import psycopg
from helpers import tx, uid, seed_tenant, admin
from test_route_stop import make_run
from test_booking_status_history import numbered_booking, advance
from geo_helpers import make_service

A, B, C = uid(), uid(), uid()


def cancelled(c, tenant, run, service, number, reason, cause):
    bid, _ = numbered_booking(c, tenant, run, number, service)
    advance(c, tenant, bid, 2, 'CONFIRMING', 'CONFIRMED', 'PAYMENT_AUTHORIZED')
    c.execute("UPDATE taktovia.booking SET state='CANCEL_PENDING', revision=3, cancel_reason_code=%s, cancel_cause_class=%s WHERE id=%s", (reason, cause, bid))
    c.execute("INSERT INTO taktovia.booking_status_history(tenant_id,id,booking_id,from_state,to_state,reason_code,actor_ref,actor_kind,booking_revision) VALUES (%s,%s,%s,'CONFIRMED','CANCEL_PENDING',%s,'user:t','OPERATOR',3)", (tenant, uid(), bid, reason))
    return bid


class CancelReasonBasis(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm227-a'); seed_tenant(B, 'm227-b'); seed_tenant(C, 'm227-c')

    def test_stornoquote_datenbasis_trennt_fahrzeugausfall(self):
        with tx(C) as c:
            run, _, _ = make_run(c, C); svc = make_service(c, C)
            cancelled(c, C, run, svc, 'BK-2026-000501', 'PASSENGER_REQUEST', 'FAHRGAST')
            cancelled(c, C, run, svc, 'BK-2026-000502', 'VEHICLE_OUTAGE', 'FAHRZEUGAUSFALL')
            cancelled(c, C, run, svc, 'BK-2026-000503', 'OPERATOR_CANCELLATION', 'BETREIBER')
            numbered_booking(c, C, run, 'BK-2026-000504', svc)                         # nicht storniert: nicht in der Datenbasis
        with tx(C) as c:
            # Datenbasis: je Buchung mit Stornogrund; Nenner „bestätigte Buchungen“ aus der Historie, Stornozeitpunkt separat (Eintrag mit dem Stornogrund)
            row = c.execute("SELECT count(*) FILTER (WHERE b.cancel_cause_class = 'FAHRGAST'), count(*) FILTER (WHERE b.cancel_cause_class = 'BETREIBER'), "
                            "count(*) FILTER (WHERE b.cancel_cause_class = 'FAHRZEUGAUSFALL'), count(*), "
                            "count(*) FILTER (WHERE EXISTS (SELECT 1 FROM taktovia.booking_status_history h WHERE h.booking_id = b.id AND h.to_state = 'CONFIRMED')), "
                            "count(*) FILTER (WHERE EXISTS (SELECT 1 FROM taktovia.booking_status_history h WHERE h.booking_id = b.id AND h.reason_code = b.cancel_reason_code)) "
                            "FROM taktovia.booking b WHERE b.cancel_reason_code IS NOT NULL").fetchone()
            self.assertEqual(row, (1, 1, 1, 3, 3, 3))      # Fahrzeugausfall zählt weder als Fahrgast- noch als Betreiberstorno
        with tx(B) as c:                                    # RLS: Mandant B sieht die Stornos von C nicht
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.booking WHERE cancel_reason_code IS NOT NULL").fetchone()[0], 0)

    def test_cancel_grund_unveraenderlich_nach_setzen(self):
        with tx(A) as c:
            run, _, _ = make_run(c, A); svc = make_service(c, A)
            bid = cancelled(c, A, run, svc, 'BK-2026-000510', 'PASSENGER_REQUEST', 'FAHRGAST')
        for sql in ("cancel_reason_code='VEHICLE_OUTAGE', cancel_cause_class='FAHRZEUGAUSFALL'", "cancel_cause_class='BETREIBER'", "cancel_reason_code=NULL, cancel_cause_class=NULL"):
            for ctx in (tx(A), admin()):
                with self.assertRaises(psycopg.errors.CheckViolation, msg=sql):
                    with ctx as c: c.execute(f"UPDATE taktovia.booking SET {sql} WHERE id=%s", (bid,))
        # Verursacherklasse folgt dem Grund (CHECK), Grund nur im Stornozustand
        with tx(A) as c:
            run2, _, _ = make_run(c, A); b2, _ = numbered_booking(c, A, run2, 'BK-2026-000511', svc)
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: c.execute("UPDATE taktovia.booking SET cancel_reason_code='NO_SHOW', cancel_cause_class='BETREIBER' WHERE id=%s", (b2,))
        with self.assertRaises(psycopg.errors.CheckViolation):                       # Zustand CONFIRMING ist kein Stornozustand
            with tx(A) as c: c.execute("UPDATE taktovia.booking SET cancel_reason_code='NO_SHOW', cancel_cause_class='FAHRGAST' WHERE id=%s", (b2,))
        # im Stornozustand: ein Grund außerhalb des Katalogs (Fremdschlüssel) und ein Katalogcode, der kein Stornogrund ist (CHECK), werden abgewiesen
        with tx(A) as c:
            advance(c, A, b2, 2, 'CONFIRMING', 'CONFIRMED', 'PAYMENT_AUTHORIZED')
            c.execute("UPDATE taktovia.booking SET state='CANCEL_PENDING', revision=3 WHERE id=%s", (b2,))
            c.execute("INSERT INTO taktovia.booking_status_history(tenant_id,id,booking_id,from_state,to_state,reason_code,actor_ref,actor_kind,booking_revision) VALUES (%s,%s,%s,'CONFIRMED','CANCEL_PENDING','PASSENGER_REQUEST','user:t','OPERATOR',3)", (A, uid(), b2))
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: c.execute("UPDATE taktovia.booking SET cancel_reason_code='ERFUNDEN', cancel_cause_class='FAHRGAST' WHERE id=%s", (b2,))
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: c.execute("UPDATE taktovia.booking SET cancel_reason_code='HOLD_CONFIRMED', cancel_cause_class='FAHRGAST' WHERE id=%s", (b2,))


if __name__ == '__main__':
    unittest.main()
