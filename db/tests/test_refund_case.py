"""WP-M2-31: `refund_case` unter der echten Runtime-Rolle. Zustandsautomat (`PENDING_CAPTURE`, `REQUESTED`, `CONFIRMED`, `FAILED`, `NOT_DUE`, `NOT_REQUIRED`) auch gegen direktes SQL, je (Zahlung,
Schlüssel) ein Fall, eindeutige PSP-Kommandoreferenz, die Summe der beauftragten Erstattungen übersteigt nie den erfassten Betrag (auch unter parallelen Aufträgen und für die Administration),
unveränderliche Identität, Mandantentrennung."""
import threading
import unittest

import psycopg

from helpers import tx, uid, seed_tenant, runtime, set_tenant
from payment_helpers import make_payment

A, B = uid(), uid()

INSERT = ("INSERT INTO taktovia.refund_case(tenant_id,id,payment_id,case_key,seq,reason_code,cause_class,state,percent,requested_minor,command_ref) "
          "VALUES (%s,%s,%s,%s,(floor(random()*1000000000)::int + 1),'PASSENGER_REQUEST','FAHRGAST',%s,100,%s,%s)")


def case(c, tenant, pay, state='REQUESTED', amount=100, key=None, ref=None):
    cid = uid()
    pending = state == 'PENDING_CAPTURE'
    c.execute(INSERT, (tenant, cid, pay, key or uid(), state, None if pending else amount, None if pending else (ref or f"cmd-refund-{cid}")))
    return cid


class RefundCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm231-a'); seed_tenant(B, 'm231-b')

    def captured(self, tenant=A, amount=1000, state='REFUND_PENDING'):
        with tx(tenant) as c:
            pay, _, _ = make_payment(c, tenant, state, authorized=amount, captured=amount)
        return pay

    def test_zustandsautomat_auch_gegen_direktes_sql(self):
        pay = self.captured()
        with tx(A) as c:
            req = case(c, A, pay, 'REQUESTED', 100); pend = case(c, A, pay, 'PENDING_CAPTURE')
        with self.assertRaises(psycopg.errors.CheckViolation):   # ungültiger Zustandswert
            with tx(A) as c: c.execute("UPDATE taktovia.refund_case SET state='ERSTATTET' WHERE id=%s", (req,))
        for target in ('PENDING_CAPTURE', 'NOT_DUE', 'NOT_REQUIRED'):   # REQUESTED -> nur CONFIRMED oder FAILED
            with self.assertRaises(psycopg.errors.CheckViolation, msg=target):
                with tx(A) as c: c.execute("UPDATE taktovia.refund_case SET state=%s WHERE id=%s", (target, req))
        with tx(A) as c:   # REQUESTED -> CONFIRMED mit bestätigtem Betrag; danach endgültig
            c.execute("UPDATE taktovia.refund_case SET state='CONFIRMED', refunded_minor=100 WHERE id=%s", (req,))
        for target in ('REQUESTED', 'FAILED'):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=target):
                with tx(A) as c: c.execute("UPDATE taktovia.refund_case SET state=%s WHERE id=%s", (target, req))
        with tx(A) as c: c.execute("UPDATE taktovia.refund_case SET state='NOT_REQUIRED' WHERE id=%s", (pend,))   # kein Einzug zustande gekommen

    def test_erstattung_ueber_erfasstem_betrag_wird_abgewiesen(self):
        pay = self.captured(amount=1000)
        for amount in (1001, 5000):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=str(amount)):
                with tx(A) as c: case(c, A, pay, 'REQUESTED', amount)
        with tx(A) as c: case(c, A, pay, 'REQUESTED', 1000)               # genau der erfasste Betrag ist zulässig
        with self.assertRaises(psycopg.errors.CheckViolation):             # jede weitere Beauftragung überschreitet die Summe
            with tx(A) as c: case(c, A, pay, 'REQUESTED', 1)
        with self.assertRaises(psycopg.errors.CheckViolation):             # auch die Administration umgeht den Wächter nicht
            from helpers import admin
            with admin() as a: a.execute(INSERT, (A, uid(), pay, uid(), 'REQUESTED', 1, f"cmd-refund-{uid()}"))
        with self.assertRaises(psycopg.errors.CheckViolation):             # bestätigt <= beauftragt
            with tx(A) as c:
                p2 = self.captured(amount=500); cid = case(c, A, p2, 'REQUESTED', 100)
                c.execute("UPDATE taktovia.refund_case SET state='CONFIRMED', refunded_minor=101 WHERE id=%s", (cid,))

    def test_zwei_parallele_erstattungsauftraege_ueberschreiten_nie_den_erfassten_betrag(self):
        pay = self.captured(amount=1000)
        ok, rejected, errors = [], [], []
        start = threading.Barrier(20)

        def worker():
            try:
                c = runtime()
                try:
                    set_tenant(c, A); start.wait(timeout=30)
                    try:
                        case(c, A, pay, 'REQUESTED', 100); c.commit(); ok.append(1)
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
            self.assertEqual(c.execute("SELECT sum(requested_minor) FROM taktovia.refund_case WHERE payment_id=%s", (pay,)).fetchone()[0], 1000)

    def test_je_zahlung_und_schluessel_ein_fall_und_eindeutige_kommandoreferenz(self):
        pay = self.captured(amount=1000); key = uid()
        with tx(A) as c: case(c, A, pay, 'REQUESTED', 100, key=key, ref='cmd-refund-eindeutig')
        with self.assertRaises(psycopg.errors.UniqueViolation):   # derselbe Schlüssel: wiederholtes Storno erzeugt keinen zweiten Fall
            with tx(A) as c: case(c, A, pay, 'REQUESTED', 100, key=key)
        with self.assertRaises(psycopg.errors.UniqueViolation):   # eine PSP-Kommandoreferenz genau einmal
            with tx(A) as c: case(c, A, pay, 'REQUESTED', 100, ref='cmd-refund-eindeutig')

    def test_identitaet_unveraenderlich_mandantentrennung_kein_delete(self):
        pay = self.captured(amount=1000)
        with tx(A) as c: cid = case(c, A, pay, 'REQUESTED', 100)
        for sql in ("percent = 50", "reason_code = 'NO_SHOW'", "cause_class = 'BETREIBER'", "payment_id = gen_random_uuid()", "case_key = gen_random_uuid()", "seq = 99", "requested_minor = 90", "command_ref = 'cmd-refund-anders'"):
            with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.ForeignKeyViolation), msg=sql):
                with tx(A) as c: c.execute(f"UPDATE taktovia.refund_case SET {sql} WHERE id=%s", (cid,))
        with tx(B) as c: self.assertEqual(c.execute("SELECT count(*) FROM taktovia.refund_case WHERE id=%s", (cid,)).fetchone()[0], 0)
        with tx(None) as c: self.assertEqual(c.execute("SELECT count(*) FROM taktovia.refund_case").fetchone()[0], 0)
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(A) as c: c.execute("DELETE FROM taktovia.refund_case WHERE id=%s", (cid,))
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):   # fremder Mandant im Fall (WITH CHECK)
            with tx(B) as c: case(c, A, pay)


if __name__ == '__main__':
    unittest.main()
