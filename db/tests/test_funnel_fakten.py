"""WP-M4-51 / LH-I.6-KPI-005, LH-6.14-ANA-001: Nachfragetrichter aus den Fakten (funnel_counts) und Abgleich gegen das Ereignisprotokoll (funnel_reconciliation) unter der echten Runtime-Rolle.
Die Fakten werden hier direkt geschrieben, jeweils mit dem passenden Eingangsereignis (analytics_event_inbox); die Projektion aus Ereignissen prüfen die Kotlin-Tests (FunnelCountTest, FunnelProjectionTest)."""
import json
import unittest
from helpers import tx, uid, seed_tenant

A, B = uid(), uid()
FROM, TO = '2026-12-01T00:00:00Z', '2026-12-02T00:00:00Z'
SERVICE = uid()


def inbox(c, source, etype, aggregate, at, payload, revision=1):
    c.execute("INSERT INTO taktovia.analytics_event_inbox(tenant_id,event_id,source,event_type,aggregate_id,aggregate_revision,occurred_at,recorded_at,payload_sha256,payload) "
              "VALUES (taktovia.current_tenant(),%s,%s,%s,%s,%s,%s,%s,%s,%s::jsonb)", (uid(), source, etype, aggregate, revision, at, at, 'a' * 64, json.dumps(payload)))


def attempt(c, outcome, primary=None, at='2026-12-01T05:00:00Z', test=False):
    a = uid()
    c.execute("INSERT INTO taktovia.fact_attempt(tenant_id,attempt_id,service_id,outcome,primary_reason,time_mode,requested_at,passengers,channel,is_test) "
              "VALUES (taktovia.current_tenant(),%s,%s,%s,%s,'DEPARTURE',%s,1,'WEB',%s)", (a, SERVICE, outcome, primary, at, test))
    p = {'attempt_id': str(a), 'service_id': str(SERVICE), 'outcome': outcome, 'requested_at': at}
    if test:
        p['is_test'] = True
    inbox(c, 'ATTEMPT', 'booking.attempt_recorded', a, at, p)
    return a


def offer(c, att, at='2026-12-01T05:01:00Z'):
    o = uid()
    c.execute("INSERT INTO taktovia.fact_offer(tenant_id,offer_id,attempt_id,service_id,created_at,expires_at,pickup_earliest,pickup_latest,gross_minor,currency) "
              "VALUES (taktovia.current_tenant(),%s,%s,%s,%s,%s,%s,%s,500,'EUR')", (o, att, SERVICE, at, at, at, at))
    inbox(c, 'OFFER', 'booking.offer_created', o, at, {'offer_id': str(o), 'attempt_id': str(att)})
    return o


def booking(c, off, state='CONFIRMED', at='2026-12-01T05:02:00Z', **cols):
    b = uid()
    cols = dict(cols, state=state)
    names = ['tenant_id', 'booking_id', 'service_id', 'confirmed_at', 'offer_id'] + list(cols)
    c.execute(f"INSERT INTO taktovia.fact_booking({','.join(names)}) VALUES (taktovia.current_tenant(),%s,%s,%s,%s{',%s' * len(cols)})", [b, SERVICE, at, off] + list(cols.values()))
    inbox(c, 'BOOKING', 'booking.confirmed', b, at, {'booking_id': str(b), 'service_id': str(SERVICE)})
    return b


def counts(c, service=None, channel=None):
    cur = c.execute('SELECT * FROM taktovia.funnel_counts(%s,%s,%s,%s)', (FROM, TO, service, channel))
    return dict(zip([d.name for d in cur.description], cur.fetchone()))


def recon(c):
    return {r[0]: (r[1], r[2]) for r in c.execute('SELECT stage, fact_count, log_count FROM taktovia.funnel_reconciliation(%s,%s)', (FROM, TO))}


class Nachfragetrichter(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm451-a'); seed_tenant(B, 'm451-b')
        with tx(A) as c:
            for _ in range(3):
                attempt(c, 'REJECTED', 'NO_CAPACITY')
            attempt(c, 'TECHNICAL_ERROR'); attempt(c, 'RECORDED', test=True)
            offers = [offer(c, attempt(c, 'RECORDED')) for _ in range(6)]
            offer(c, attempt(c, 'RECORDED', test=True))      # Testanfrage: auch ihr Angebot ist ausgeschlossen
            # vier bestätigte Buchungen auf den ersten vier Angeboten: abgeschlossen, Fahrgaststorno, Betreiberstorno, Zahlung fehlgeschlagen (danach storniert)
            b1 = booking(c, offers[0], 'COMPLETED', alighted_at='2026-12-01T06:00:00Z', boarded_at='2026-12-01T05:50:00Z')
            for kind, at in (('CHECK_IN', '2026-12-01T05:50:00Z'), ('CHECK_OUT', '2026-12-01T06:00:00Z')):
                inbox(c, 'DRIVER_EVENT', 'driver.event_recorded', uid(), at, {'booking_id': str(b1), 'event_type': kind, 'outcome': 'APPLIED', 'effective_at': at})
            booking(c, offers[1], 'CANCELED', cancelled_at='2026-12-01T05:30:00Z', cancel_reason='PASSENGER_REQUEST', cause_class='FAHRGAST', cancel_initiator='PASSENGER')
            booking(c, offers[2], 'CANCELED', cancelled_at='2026-12-01T05:31:00Z', cancel_reason='OPERATOR_CANCELLATION', cause_class='BETREIBER', cancel_initiator='OPERATOR')
            b4 = booking(c, offers[3], 'CANCELED', cancelled_at='2026-12-01T05:32:00Z', cancel_reason='PAYMENT_FAILED', cause_class='FAHRGAST', cancel_initiator='SYSTEM',
                    payment_failed_at='2026-12-01T05:31:30Z', payment_failure_code='DECLINED')
            inbox(c, 'BOOKING', 'payment.authorization_failed', uid(), '2026-12-01T05:31:30Z', {'booking_id': str(b4), 'failure_code': 'DECLINED'})

    def test_vier_stufen_getrennt_und_ereignisabgleich(self):
        with tx(A) as c:
            n = counts(c)
            # vier getrennte Zählgrößen, nie eine Summe
            self.assertEqual((n['requests'], n['offers'], n['confirmed'], n['completed']), (10, 6, 4, 1))
            self.assertEqual((n['rejected'], n['technical'], n['offers_not_booked'], n['payment_failed']), (3, 1, 2, 1))
            self.assertEqual((n['cancel_passenger'], n['cancel_operator'], n['cancel_other']), (1, 1, 0), 'Zahlungsfehlschlag ist weder Fahrgast- noch Betreiberstorno')
            self.assertEqual(n['unlinked_bookings'], 0)
            # Abgleich: Fakten und Protokoll stimmen je Stufe überein (Differenz 0)
            r = recon(c)
            self.assertEqual(set(r), {'requests', 'offers', 'confirmed_bookings', 'completed_bookings', 'payment_failed'})
            for stage, (fact, log) in r.items():
                self.assertEqual(fact, log, stage)
            self.assertEqual(r['requests'][0], 10)

    def test_abgleich_meldet_fehlende_projektion(self):
        t = uid(); seed_tenant(t, 'm451-c')
        with tx(t) as c:
            attempt(c, 'RECORDED'); a2 = attempt(c, 'RECORDED')
            c.execute("DELETE FROM taktovia.fact_attempt WHERE attempt_id = %s", (a2,))      # Fakt geht verloren, das Ereignis bleibt im Protokoll
            fact, log = recon(c)['requests']
            self.assertEqual((fact, log), (1, 2), 'Differenz sichtbar')

    def test_storno_nach_abschluss_aendert_stufe_nicht(self):
        t = uid(); seed_tenant(t, 'm451-d')
        with tx(t) as c:
            booking(c, None, 'COMPLETED', alighted_at='2026-12-01T06:00:00Z', cancelled_at='2026-12-01T07:00:00Z', cancel_reason='PASSENGER_REQUEST', cause_class='FAHRGAST')
            n = counts(c)
            self.assertEqual((n['completed'], n['cancel_passenger'], n['cancel_operator'], n['cancel_other']), (1, 0, 0, 0))

    def test_service_filter_und_kleine_gruppen_komplement(self):
        with tx(A) as c:
            self.assertEqual(counts(c, service=uid())['requests'], 0)
            self.assertEqual(counts(c, service=SERVICE)['requests'], 10)
            self.assertEqual(counts(c, channel='APP')['requests'], 0)

    def test_fremder_mandant_sieht_keine_funnelfakten(self):
        with tx(B) as c:
            n = counts(c)
            self.assertFalse(n['has_data'])
            self.assertEqual([n[k] for k in ('requests', 'offers', 'confirmed', 'completed', 'rejected', 'payment_failed', 'cancel_passenger')], [0] * 7)
            for table in ('fact_attempt', 'fact_offer', 'fact_booking', 'analytics_event_inbox'):
                self.assertEqual(c.execute(f'SELECT count(*) FROM taktovia.{table}').fetchone()[0], 0, f'RLS: {table}')
            self.assertTrue(all(f == 0 and l == 0 for f, l in recon(c).values()))
        # ohne Mandantenkontext: nichts
        with tx(None) as c:
            self.assertEqual(counts(c)['requests'], 0)


if __name__ == '__main__':
    unittest.main()
