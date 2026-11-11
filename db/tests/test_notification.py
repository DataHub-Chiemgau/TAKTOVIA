"""WP-M2-32 / LH-6.12-COM-003: notification und notification_attempt unter der echten Runtime-Rolle (fachlicher Unique-Schlüssel, RLS,
Zustandsautomat per Trigger, Zustellversuche nur anfügen, Kontaktdaten nur als Referenz)."""
import threading, unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()
PA, PB = uid(), uid()


def passenger(t, p):
    with tx(t) as c:
        c.execute("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,retention_until) VALUES (%s,%s,%s,now() + interval '1 year')", (t, p, f'tok-{p}'))


def notification(c, t, p, event=None, channel='SMS', nid=None, event_type='booking.confirmed'):
    nid = nid or uid()
    c.execute("INSERT INTO taktovia.notification(tenant_id,id,event_id,recipient_ref,channel,event_type) VALUES (%s,%s,%s,%s,%s,%s)",
              (t, nid, event or uid(), p, channel, event_type))
    return nid


class Nachrichtenauftrag(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm232-a'); seed_tenant(B, 'm232-b')
        passenger(A, PA); passenger(B, PB)

    def test_unique_fachlicher_schluessel_und_rls(self):
        ev = uid()
        with tx(A) as c: notification(c, A, PA, event=ev)
        with self.assertRaises(psycopg.errors.UniqueViolation):          # gleiches Ereignis, Empfänger, Kanal
            with tx(A) as c: notification(c, A, PA, event=ev)
        with tx(A) as c: notification(c, A, PA, event=ev, channel='EMAIL')   # anderer Kanal: zulässig
        # RLS: der andere Mandant sieht nichts; Einfügen mit fremdem Mandanten wird abgewiesen
        with tx(B) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.notification').fetchone()[0], 0)
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(B) as c: notification(c, A, PA)
        # Empfänger eines anderen Mandanten ist als Referenz nicht möglich (zusammengesetzter Fremdschlüssel)
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):
            with tx(A) as c: notification(c, A, PB)
        # parallel: dasselbe Ereignis, genau ein Auftrag
        ev2, results, barrier = uid(), [], threading.Barrier(6)

        def run():
            try:
                with tx(A) as c:
                    barrier.wait(); notification(c, A, PA, event=ev2); results.append('ok')
            except psycopg.errors.UniqueViolation:
                results.append('dup')
        ts = [threading.Thread(target=run) for _ in range(6)]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual((results.count('ok'), results.count('dup')), (1, 5))

    def test_keine_klartextkontakte_spalten(self):
        with admin() as c:
            cols = {r[0] for r in c.execute("SELECT column_name FROM information_schema.columns WHERE table_schema='taktovia' AND table_name='notification'")}
        for verboten in ('address', 'email', 'phone', 'contact', 'body', 'content', 'text'):
            self.assertFalse([k for k in cols if verboten in k], f'Spalte mit {verboten}')

    def test_zustandsautomat_per_trigger(self):
        with tx(A) as c: n = notification(c, A, PA)
        def upd(set_sql, *args):
            with tx(A) as c: c.execute(f"UPDATE taktovia.notification SET {set_sql} WHERE id=%s", (*args, n))
        with self.assertRaises(psycopg.errors.CheckViolation):           # QUEUED -> DELIVERED nicht erlaubt
            upd("state='DELIVERED', delivered_at=now()")
        upd("state='SENDING'")
        upd("state='UNKNOWN'")
        with self.assertRaises(psycopg.errors.CheckViolation):           # UNKNOWN -> SENDING nicht erlaubt (erst Abgleich, dann RETRY_WAIT)
            upd("state='SENDING'")
        upd("state='RETRY_WAIT'")
        upd("state='SENDING'")
        upd("state='DELIVERED', delivered_at=now()")
        for target in ("state='QUEUED'", "state='FAILED', failure_reason='PROVIDER_ERROR', dead_lettered_at=now()"):
            with self.assertRaises(psycopg.errors.CheckViolation):       # Endzustand ist endgültig
                upd(target)
        with tx(A) as c:
            rev = c.execute('SELECT revision FROM taktovia.notification WHERE id=%s', (n,)).fetchone()[0]
        self.assertEqual(rev, 6)                                         # Anlage 1 + fünf Zustandswechsel
        with self.assertRaises(psycopg.errors.CheckViolation):           # Identität unveränderlich
            upd("channel='EMAIL'")
        with self.assertRaises(psycopg.errors.CheckViolation):           # Fehlschlag verlangt Grund (Dokumentation statt stillem Verlust)
            with tx(A) as c:
                m = notification(c, A, PA); c.execute("UPDATE taktovia.notification SET state='SENDING' WHERE id=%s", (m,))
                c.execute("UPDATE taktovia.notification SET state='FAILED', dead_lettered_at=now() WHERE id=%s", (m,))

    def test_zustellversuche_nur_anfuegen(self):
        with tx(A) as c: n = notification(c, A, PA)
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.notification_attempt(tenant_id,notification_id,attempt_no,started_at,finished_at,outcome,error_code) VALUES (%s,%s,1,now(),now(),'TIMED_OUT','timeout')", (A, n))
        with self.assertRaises(psycopg.errors.UniqueViolation):
            with tx(A) as c:
                c.execute("INSERT INTO taktovia.notification_attempt(tenant_id,notification_id,attempt_no,started_at,finished_at,outcome) VALUES (%s,%s,1,now(),now(),'ACCEPTED')", (A, n))
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(A) as c: c.execute("UPDATE taktovia.notification_attempt SET outcome='ACCEPTED' WHERE notification_id=%s", (n,))
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(A) as c: c.execute("DELETE FROM taktovia.notification_attempt WHERE notification_id=%s", (n,))
        with self.assertRaises(psycopg.errors.CheckViolation):           # auch der Eigentümer kann Versuche nicht ändern (Trigger)
            with admin() as c: c.execute("UPDATE taktovia.notification_attempt SET outcome='ACCEPTED' WHERE notification_id=%s", (n,))
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c:   # ungültiger Fehlercode (Meldungstext statt technischem Code) wird abgewiesen
                c.execute("INSERT INTO taktovia.notification_attempt(tenant_id,notification_id,attempt_no,started_at,finished_at,outcome,error_code) VALUES (%s,%s,2,now(),now(),'REJECTED','Fehler: Rufnummer +491701234567')", (A, n))
        with tx(B) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.notification_attempt').fetchone()[0], 0)

    def test_kein_delete_auf_auftrag(self):
        with tx(A) as c: n = notification(c, A, PA)
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(A) as c: c.execute('DELETE FROM taktovia.notification WHERE id=%s', (n,))


if __name__ == '__main__':
    unittest.main()
