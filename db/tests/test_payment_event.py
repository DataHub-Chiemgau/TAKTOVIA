"""WP-M2-29: payment_event (PSP-Ereignisse dedupliziert, Kap. 04 §4.4) unter der echten Runtime-Rolle. Eindeutigkeit je (Mandant, Provider, Ereignis-ID),
Payloadhash, Mandantentrennung (RLS, WITH CHECK), unveränderlich (kein UPDATE/DELETE), Wertebereiche, parallele identische Einfügungen."""
import threading
import unittest

import psycopg

from helpers import runtime, seed_tenant, tx, uid

A, B = uid(), uid()
H1, H2 = 'a' * 64, 'b' * 64

INSERT = ("INSERT INTO taktovia.payment_event(tenant_id,id,provider,event_id,payload_sha256,event_type,internal_kind,provider_sequence,provider_created_at,"
          "provider_ref,reported_state,authorized_minor,captured_minor,refunded_minor,currency,dispute_state) "
          "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,now(),%s,%s,%s,%s,%s,%s,%s)")


def row(tenant, provider='simpsp', event_id='evt_1', sha=H1, etype='payment.authorized', kind='AUTHORIZED', seq=1, ref='pi_1', state='AUTHORIZED',
        auth=1000, cap=0, ref_m=0, cur='EUR', dispute=None):
    return (tenant, uid(), provider, event_id, sha, etype, kind, seq, ref, state, auth, cap, ref_m, cur, dispute)


class PaymentEvent(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm229-a'); seed_tenant(B, 'm229-b')

    def test_gleiche_ereignis_id_nur_einmal_je_mandant_und_provider(self):
        with tx(A) as c: c.execute(INSERT, row(A, event_id='evt_dup'))
        with self.assertRaises(psycopg.errors.UniqueViolation):
            with tx(A) as c: c.execute(INSERT, row(A, event_id='evt_dup', sha=H2))
        with tx(A) as c: c.execute(INSERT, row(A, provider='andere', event_id='evt_dup'))   # anderer Provider: eigene ID-Menge
        with tx(B) as c: c.execute(INSERT, row(B, event_id='evt_dup'))                      # anderer Mandant: eigene ID-Menge

    def test_mandantentrennung_und_unveraenderlichkeit(self):
        with tx(A) as c: c.execute(INSERT, row(A, event_id='evt_rls'))
        with tx(B) as c: self.assertEqual(c.execute("SELECT count(*) FROM taktovia.payment_event WHERE event_id='evt_rls'").fetchone()[0], 0)
        with tx(None) as c: self.assertEqual(c.execute("SELECT count(*) FROM taktovia.payment_event WHERE event_id='evt_rls'").fetchone()[0], 0)
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):   # Zeile für fremden Mandanten (WITH CHECK)
            with tx(A) as c: c.execute(INSERT, row(B, event_id='evt_fremd'))
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):   # Ereignisse sind unveränderlich
            with tx(A) as c: c.execute("UPDATE taktovia.payment_event SET reported_state='VOIDED' WHERE event_id='evt_rls'")
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(A) as c: c.execute("DELETE FROM taktovia.payment_event WHERE event_id='evt_rls'")

    def test_ungueltige_werte_werden_abgewiesen(self):
        bad = [dict(provider='A'), dict(provider='x'), dict(event_id=''), dict(event_id='e' * 129), dict(sha='xyz'), dict(etype='refund.created'),
               dict(kind='ERFUNDEN'), dict(seq=0), dict(state='PAID'), dict(auth=-1), dict(cap=-1), dict(ref_m=-1), dict(cur='eur'), dict(dispute='LOST?'), dict(ref='')]
        for i, kw in enumerate(bad):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=str(kw)):
                with tx(A) as c: c.execute(INSERT, row(A, **{'event_id': f'evt_bad{i}', **kw}))

    def test_keine_kartendatenspalte_und_kein_rohpayload(self):
        with runtime() as c:
            cols = {r[0] for r in c.execute("SELECT column_name FROM information_schema.columns WHERE table_schema='taktovia' AND table_name='payment_event'")}
        self.assertFalse(cols & {'data', 'payload', 'raw_body', 'pan', 'card_number', 'cvc', 'expiry'}, cols)

    def test_100_parallele_identische_einfuegungen_genau_eine_zeile(self):
        wins, conflicts, errors = [], [], []
        threads, total = 30, 100   # 100 Einfügungen über 30 gleichzeitige Verbindungen (max_connections der Testdatenbank ist 100, andere Clients belegen Plätze)
        start = threading.Barrier(threads)
        counter = iter(range(total)); lock = threading.Lock()

        def worker():
            try:
                start.wait()
                while True:
                    with lock:
                        if next(counter, None) is None:
                            return
                    with tx(A) as c:
                        n = c.execute(INSERT + " ON CONFLICT (tenant_id, provider, event_id) DO NOTHING", row(A, event_id='evt_par')).rowcount
                    (wins if n == 1 else conflicts).append(1)
            except Exception as e:  # noqa: BLE001
                errors.append(e)

        ts = [threading.Thread(target=worker) for _ in range(threads)]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual(errors, [])
        self.assertEqual((len(wins), len(conflicts)), (1, 99))
        with tx(A) as c: self.assertEqual(c.execute("SELECT count(*) FROM taktovia.payment_event WHERE event_id='evt_par'").fetchone()[0], 1)


if __name__ == '__main__':
    unittest.main()
