"""WP-M4-43 / LH-I.2-FCO-004: Kennzahl-Snapshot der Energiekosten unter der echten Runtime-Rolle: die beiden neuen Definitionen (Seed, wiederholbar, Mandantentrennung), Ausschlussliste und Kennzeichnung der
Erwerbsnäherung im Snapshot, Wertebereich (nicht berechenbar nie mit Wert), Mandantentrennung (RLS) des Snapshots, Snapshot bleibt unveränderlich."""
import json
import unittest
import psycopg
from helpers import tx, uid, seed_tenant

A, B = uid(), uid()
ENERGY = {'energy_cost_per_100km', 'energy_cost_per_operating_hour'}


def snapshot(c, tenant, metric='energy_cost_per_100km', state='OK', reason=None, value=8108, numerator=15000, denominator=185, coverage=1, exclusions=(), approximation='ACQUISITION_BASIS_P1', flt='{"energy":"FUEL"}', h='e' * 64):
    sid = uid()
    c.execute("""INSERT INTO taktovia.aggregate_snapshot(tenant_id,id,metric_id,definition_version,value_kind,filter,filter_hash,period_from,period_to,reporting_timezone,state,reason,value,numerator,denominator,
                 coverage,data_as_of,source_events,exclusions,approximation)
                 VALUES (%s,%s,%s,1,'RATIO',%s::jsonb,%s,'2026-11-30T23:00Z','2026-12-01T23:00Z','Europe/Berlin',%s,%s,%s,%s,%s,%s,'2026-12-01T09:00Z',4,%s::jsonb,%s)""",
              (tenant, sid, metric, flt, h, state, reason, value, numerator, denominator, coverage, json.dumps(list(exclusions) if isinstance(exclusions, (list, tuple)) else exclusions), approximation))
    return sid


class EnergieSnapshot(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm443-a'); seed_tenant(B, 'm443-b')
        for t in (A, B):
            with tx(t) as c:
                c.execute('SELECT taktovia.seed_metric_definitions()'); c.execute('SELECT taktovia.seed_energy_metric_definitions()')

    def test_definitionen_sind_freigegeben_vollstaendig_und_der_seed_wiederholbar(self):
        with tx(A) as c:
            rows = c.execute("SELECT metric_id, version, state, executable, value_kind, filter_dimensions, source FROM taktovia.metric_definition_revision WHERE metric_id = ANY(%s)", (list(ENERGY),)).fetchall()
            self.assertEqual({r[0] for r in rows}, ENERGY)
            for r in rows:
                self.assertEqual((r[1], r[2], r[3], r[4]), (1, 'APPROVED', True, 'RATIO')); self.assertEqual(sorted(r[5]), ['currency', 'energy', 'vehicle_id']); self.assertIsNone(r[6])
            self.assertEqual(c.execute('SELECT taktovia.seed_energy_metric_definitions()').fetchone()[0], 0, 'Seed ist wiederholbar')
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.metric_definition_revision WHERE numerator_definition ILIKE '%Kostenbasis%' AND time_basis ILIKE '%Erwerbsnäherung%'").fetchone()[0], 2,
                             'Formel (Kostenbasis) und die Kennzeichnung der P1-Erwerbsnäherung stehen in der Definition')

    def test_snapshot_traegt_ausschlussliste_und_naeherung_und_bleibt_unveraenderlich(self):
        doc = str(uid())
        with tx(A) as c:
            sid = snapshot(c, A, exclusions=[{'document_id': doc, 'vehicle_id': None, 'reason': 'NOT_RELEASED'}], coverage='0.5000', state='PROVISIONAL', h='1' * 64)
            ex, ap = c.execute('SELECT exclusions, approximation FROM taktovia.aggregate_snapshot WHERE id = %s', (sid,)).fetchone()
            self.assertEqual(ex[0]['reason'], 'NOT_RELEASED'); self.assertEqual(ap, 'ACQUISITION_BASIS_P1')
        for stmt in ("UPDATE taktovia.aggregate_snapshot SET value = 1 WHERE id = %s", "DELETE FROM taktovia.aggregate_snapshot WHERE id = %s"):
            with tx(A) as c:
                with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InsufficientPrivilege)): c.execute(stmt, (sid,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation, msg='Ausschlussliste ist ein Array'): snapshot(c, A, exclusions={'a': 1}, h='2' * 64)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation, msg='unbekannte Näherung'): snapshot(c, A, approximation='ANDERE', h='3' * 64)

    def test_nicht_berechenbar_hat_nie_einen_wert(self):
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation, msg='Nenner 0 mit Wert'): snapshot(c, A, value=0, numerator=15000, denominator=0, h='4' * 64)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation, msg='nicht berechenbar mit Wert'): snapshot(c, A, state='NOT_COMPUTABLE', reason='ZERO_DENOMINATOR', value=0, denominator=0, h='5' * 64)
        with tx(A) as c:
            snapshot(c, A, state='NOT_COMPUTABLE', reason='ZERO_DENOMINATOR', value=None, denominator=0, coverage=None, h='6' * 64)
            snapshot(c, A, state='NOT_COMPUTABLE', reason='NO_DATA', value=None, numerator=None, denominator=None, coverage=None, h='7' * 64)

    def test_rls_trennt_mandanten(self):
        with tx(A) as c:
            sid = snapshot(c, A, h='8' * 64, exclusions=[{'document_id': str(uid()), 'vehicle_id': None, 'reason': 'REVERSED'}])
        with tx(B) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.aggregate_snapshot WHERE id = %s', (sid,)).fetchone()[0], 0, 'Mandant B sieht den Snapshot von A nicht')
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.aggregate_snapshot WHERE tenant_id = %s', (A,)).fetchone()[0], 0)
            self.assertEqual(c.execute('SELECT count(DISTINCT tenant_id) FROM taktovia.metric_definition_revision WHERE metric_id = ANY(%s)', (list(ENERGY),)).fetchone()[0], 1)
        with tx(B) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg='B schreibt nicht in den Mandanten A'): snapshot(c, A, h='9' * 64)
        with tx(A) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.aggregate_snapshot WHERE id = %s', (sid,)).fetchone()[0], 1)

    def test_parallele_mandanten_ohne_vermischung(self):
        import threading
        out = {}
        def work(t, tag):
            with tx(t) as c:
                snapshot(c, t, h=(tag * 64)[:64], flt='{"energy":"FUEL","currency":"%s"}' % ('EUR' if t == A else 'CHF'))
                out[t] = c.execute("SELECT count(*), min(filter->>'currency') FROM taktovia.aggregate_snapshot WHERE filter_hash = %s", ((tag * 64)[:64],)).fetchone()
        ts = [threading.Thread(target=work, args=(t, tag)) for t, tag in ((A, 'a'), (B, 'b'))]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual(out[A], (1, 'EUR')); self.assertEqual(out[B], (1, 'CHF'))


if __name__ == '__main__':
    unittest.main()
