"""WP-M2-30 / LH-6.10-PAY-004: `payment_intent` unter der echten Runtime-Rolle. Zustandswerte und Übergänge des Automaten `payment` auch gegen direktes SQL, CHECKs
erstattet <= erfasst <= autorisiert unter parallelen Schreibversuchen, Geldwächter (nie rückwärts, nur in den vorgesehenen Zuständen), `UNIQUE(tenant_id,provider,provider_ref)`,
höchstens eine wirksame Zahlung je Buchung, Mandantentrennung, `payment_operation` unveränderlich."""
import threading
import unittest

import psycopg

from helpers import tx, uid, seed_tenant, runtime, set_tenant
from payment_helpers import make_payment

A, B = uid(), uid()


class PaymentIntent(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm230-a'); seed_tenant(B, 'm230-b')

    def test_state_check_und_provider_ref_eindeutig(self):
        with tx(A) as c:
            pay, _, _ = make_payment(c, A, 'AUTHORIZED', authorized=1000)
        # unzulässiger Zustandswert (CHECK der Wertemenge)
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: c.execute("UPDATE taktovia.payment_intent SET state='PAID' WHERE id=%s", (pay,))
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: make_payment(c, A, 'GEZAHLT')
        # CAPTURED ohne AUTHORIZED: Übergang nicht in der Tabelle des Automaten
        with tx(A) as c:
            created, _, _ = make_payment(c, A, 'CREATED')
        for target in ('CAPTURED', 'CAPTURE_PENDING', 'VOIDED', 'REFUNDED'):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=target):
                with tx(A) as c: c.execute("UPDATE taktovia.payment_intent SET state=%s, captured_minor=5 WHERE id=%s", (target, created))
        # eindeutige PSP-Referenz je Mandant und Provider; anderer Provider und anderer Mandant haben eigene Mengen
        with tx(A) as c:
            first, _, _ = make_payment(c, A, 'CREATED'); other, _, _ = make_payment(c, A, 'CREATED')
            c.execute("UPDATE taktovia.payment_intent SET provider_ref='ref-eindeutig' WHERE id=%s", (first,))
        with self.assertRaises(psycopg.errors.UniqueViolation):
            with tx(A) as c: c.execute("UPDATE taktovia.payment_intent SET provider_ref='ref-eindeutig' WHERE id=%s", (other,))
        with tx(B) as c:
            b, _, _ = make_payment(c, B, 'CREATED')
            c.execute("UPDATE taktovia.payment_intent SET provider_ref='ref-eindeutig' WHERE id=%s", (b,))   # anderer Mandant: eigene Menge

    def test_ohne_psp_referenz_nur_in_created_und_authorization_failed(self):
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c:
                pay, _, _ = make_payment(c, A, 'CREATED')
                c.execute("UPDATE taktovia.payment_intent SET state='AUTHORIZED', authorized_minor=1000 WHERE id=%s", (pay,))   # ohne provider_ref nicht autorisiert

    def test_checks_erstattet_erfasst_autorisiert_unter_parallelitaet(self):
        with tx(A) as c:
            pay, _, _ = make_payment(c, A, 'REFUND_PENDING', authorized=1000, captured=1000)
        for sql in ("captured_minor = 1001", "authorized_minor = 999"):   # erfasst > autorisiert (Wächter bzw. CHECK)
            with self.assertRaises(psycopg.errors.CheckViolation, msg=sql):
                with tx(A) as c: c.execute(f"UPDATE taktovia.payment_intent SET {sql} WHERE id=%s", (pay,))
        ok, rejected, errors = [], [], []
        start = threading.Barrier(20)

        def worker():
            try:
                c = runtime()
                try:
                    set_tenant(c, A); start.wait(timeout=30)
                    try:
                        c.execute("UPDATE taktovia.payment_intent SET refunded_minor = refunded_minor + 100 WHERE id=%s", (pay,)); c.commit(); ok.append(1)
                    except psycopg.errors.CheckViolation:
                        c.rollback(); rejected.append(1)
                finally:
                    c.close()
            except Exception as e:   # pragma: no cover - Diagnose
                errors.append(repr(e))
        ts = [threading.Thread(target=worker) for _ in range(20)]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual(errors, [])
        self.assertEqual((len(ok), len(rejected)), (10, 10), "genau 10 x 100 passen unter den erfassten Betrag 1000")
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT refunded_minor FROM taktovia.payment_intent WHERE id=%s", (pay,)).fetchone()[0], 1000)

    def test_geldwaechter_nie_rueckwaerts_und_nur_in_den_vorgesehenen_zustaenden(self):
        with tx(A) as c:
            captured, _, _ = make_payment(c, A, 'CAPTURED', authorized=1000, captured=1000)
            authorized, _, _ = make_payment(c, A, 'AUTHORIZED', authorized=1000)
        bad = [(captured, "captured_minor = 900"), (captured, "refunded_minor = 10"), (authorized, "captured_minor = 10"), (authorized, "authorized_minor = 900"),
               (captured, "journey_id = gen_random_uuid()"), (captured, "provider = 'anderer'"), (captured, "requested_minor = 1")]
        for pay, sql in bad:
            with self.assertRaises(psycopg.errors.CheckViolation, msg=sql):
                with tx(A) as c: c.execute(f"UPDATE taktovia.payment_intent SET {sql} WHERE id=%s", (pay,))
        # Zustandswechsel nur mit passendem Betrag
        with tx(A) as c: c.execute("UPDATE taktovia.payment_intent SET state='REFUND_PENDING' WHERE id=%s", (captured,))
        with self.assertRaises(psycopg.errors.CheckViolation):   # REFUNDED nur bei Vollerstattung
            with tx(A) as c: c.execute("UPDATE taktovia.payment_intent SET refunded_minor=400 WHERE id=%s", (captured,)); c.execute("UPDATE taktovia.payment_intent SET state='REFUNDED' WHERE id=%s", (captured,))
        with tx(A) as c:
            c.execute("UPDATE taktovia.payment_intent SET refunded_minor=400 WHERE id=%s", (captured,))
            c.execute("UPDATE taktovia.payment_intent SET state='PARTIALLY_REFUNDED' WHERE id=%s", (captured,))

    def test_hoechstens_eine_wirksame_zahlung_je_buchung_und_mandantentrennung(self):
        from test_route_stop import make_run
        from test_booking_status_history import numbered_booking
        with tx(A) as c:
            run, _, _ = make_run(c, A); bid, _ = numbered_booking(c, A, run, 'BK-2026-000777')
            p1, _, _ = make_payment(c, A, 'CREATED'); p2, _, _ = make_payment(c, A, 'CREATED')
            c.execute("UPDATE taktovia.payment_intent SET booking_id=%s WHERE id=%s", (bid, p1))
        with self.assertRaises(psycopg.errors.UniqueViolation):
            with tx(A) as c: c.execute("UPDATE taktovia.payment_intent SET booking_id=%s WHERE id=%s", (bid, p2))
        with tx(A) as c:   # nach Ablehnung ist ein anderer Zahlungsweg möglich
            c.execute("UPDATE taktovia.payment_intent SET state='AUTHORIZATION_FAILED', failure_code='DECLINED' WHERE id=%s", (p1,))
            c.execute("UPDATE taktovia.payment_intent SET booking_id=%s WHERE id=%s", (bid, p2))
        with tx(B) as c: self.assertEqual(c.execute("SELECT count(*) FROM taktovia.payment_intent WHERE id=%s", (p2,)).fetchone()[0], 0)
        with tx(None) as c: self.assertEqual(c.execute("SELECT count(*) FROM taktovia.payment_intent").fetchone()[0], 0)

    def test_payment_operation_unveraenderlich_und_mandantengetrennt(self):
        with tx(A) as c:
            pay, jid, _ = make_payment(c, A, 'CREATED'); op = uid()
            c.execute("INSERT INTO taktovia.operation(tenant_id,id,journey_id,kind,status,deadline_at) VALUES (%s,%s,%s,'TEST_COMMAND','QUEUED',now()+interval '1 hour')", (A, op, jid))
            c.execute("INSERT INTO taktovia.payment_operation(tenant_id,operation_id,payment_id,action,seq,amount_minor,command_ref) VALUES (%s,%s,%s,'AUTHORIZE',1,1000,'cmd-authorize-0001')", (A, op, pay))
        with self.assertRaises(psycopg.errors.UniqueViolation):   # eine Kommandoreferenz genau einmal
            with tx(A) as c:
                op2 = uid()
                c.execute("INSERT INTO taktovia.operation(tenant_id,id,journey_id,kind,status,deadline_at) VALUES (%s,%s,%s,'TEST_COMMAND','QUEUED',now()+interval '1 hour')", (A, op2, jid))
                c.execute("INSERT INTO taktovia.payment_operation(tenant_id,operation_id,payment_id,action,seq,amount_minor,command_ref) VALUES (%s,%s,%s,'VOID',1,NULL,'cmd-authorize-0001')", (A, op2, pay))
        for sql in ("UPDATE taktovia.payment_operation SET amount_minor = 1", "DELETE FROM taktovia.payment_operation"):
            with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InsufficientPrivilege), msg=sql):
                with tx(A) as c: c.execute(sql + " WHERE operation_id=%s", (op,))
        with tx(B) as c: self.assertEqual(c.execute("SELECT count(*) FROM taktovia.payment_operation").fetchone()[0], 0)


if __name__ == '__main__':
    unittest.main()
