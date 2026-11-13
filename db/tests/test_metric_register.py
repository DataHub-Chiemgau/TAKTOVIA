"""WP-M4-46 / LH-I.6-KPI-010: Kennzahlregister unter der echten Runtime-Rolle: Seed der P1-Definitionen, Unveränderlichkeit freigegebener Definitionen, lückenlose Versionsfolge auch bei gleichzeitiger
Freigabe, Pflichtfelder der Freigabe, Snapshots behalten ihre Version, Nullnenner ist nie 0 oder 100 %, unvollständige Quellen sind vorläufig, Mandantentrennung."""
import threading
import unittest
import psycopg
from helpers import tx, uid, seed_tenant

A, B = uid(), uid()
HASH = 'a' * 64
P1_IDS = {'requests', 'technical_request_errors', 'capacity_rejections', 'personnel_rejections', 'equipment_rejections', 'area_rejections', 'time_window_rejections', 'offer_rate', 'booking_conversion',
          'cancellation_rate_passenger', 'cancellation_rate_operator', 'no_show_rate', 'pickup_on_time', 'arrival_on_time', 'mean_pickup_delay', 'mean_arrival_delay', 'empty_km_rate', 'productive_km',
          'capacity_utilization_distance_weighted', 'seat_time_utilization', 'passenger_km'}


def seed(tenant):
    with tx(tenant) as c:
        return c.execute('SELECT taktovia.seed_metric_definitions()').fetchone()[0]


def draft(c, tenant, metric, **over):
    cols = dict(name='Test', value_kind='COUNT', unit='n', numerator_definition='z', denominator_definition='n', exclusions='keine', time_basis='t', filter_dimensions=['service_id'], executable=False,
                not_executable_reason='NOT_EXECUTABLE_IN_THIS_RELEASE')
    cols.update(over)
    names = ['tenant_id', 'metric_id'] + list(cols)
    c.execute(f"INSERT INTO taktovia.metric_definition_revision({','.join(names)}) VALUES ({','.join(['%s'] * len(names))}) RETURNING version", [tenant, metric] + list(cols.values()))


def snapshot(c, tenant, metric='requests', version=1, kind='COUNT', state='OK', reason=None, value=None, numerator=None, denominator=None, coverage=None, flt=HASH):
    c.execute("""INSERT INTO taktovia.aggregate_snapshot(tenant_id,id,metric_id,definition_version,value_kind,filter,filter_hash,period_from,period_to,reporting_timezone,state,reason,value,numerator,denominator,coverage,data_as_of)
                 VALUES (%s,%s,%s,%s,%s,'{}'::jsonb,%s,'2026-12-01Z','2026-12-02Z','Europe/Berlin',%s,%s,%s,%s,%s,%s,'2026-12-01T05:00:00Z')""",
              (tenant, uid(), metric, version, kind, flt, state, reason, value, numerator, denominator, coverage))


class Kennzahlregister(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm446-a'); seed_tenant(B, 'm446-b')
        cls.seeded = seed(A)

    def test_definition_unveraenderlich_und_nullnenner_nicht_berechenbar(self):
        self.assertEqual(self.seeded, 22, '21 P1-Kennzahlen plus die Definition der technischen Partnerablehnungen')
        with tx(A) as c:
            rows = c.execute("SELECT metric_id, version, state, executable FROM taktovia.metric_definition_revision").fetchall()
            self.assertTrue(P1_IDS <= {r[0] for r in rows}); self.assertTrue(all(r[1] == 1 and r[2] == 'APPROVED' for r in rows))
            self.assertEqual({r[0] for r in rows if r[3]}, {'requests', 'technical_request_errors', 'capacity_rejections', 'personnel_rejections', 'equipment_rejections', 'area_rejections', 'time_window_rejections'})
            self.assertEqual(c.execute('SELECT taktovia.seed_metric_definitions()').fetchone()[0], 0, 'Seed ist wiederholbar')
        # freigegebene Definition: UPDATE scheitert (Trigger), DELETE ist der Runtime-Rolle nicht erlaubt
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.metric_definition_revision SET exclusions = 'geändert' WHERE metric_id = 'requests'")
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): c.execute("DELETE FROM taktovia.metric_definition_revision WHERE metric_id = 'requests'")
        # Nullnenner: nie 0 oder 100 %, sondern nicht berechenbar mit Grund
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation, msg='Quote mit Nenner 0 und Wert 0'): snapshot(c, A, 'technical_request_errors', kind='RATIO', value=0, numerator=0, denominator=0)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation, msg='Quote mit Nenner 0 und Wert 1'): snapshot(c, A, 'technical_request_errors', kind='RATIO', value=1, numerator=0, denominator=0)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation, msg='nicht berechenbar mit Wert'): snapshot(c, A, 'technical_request_errors', kind='RATIO', state='NOT_COMPUTABLE', reason='ZERO_DENOMINATOR', value=0)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation, msg='nicht berechenbar ohne Grund'): snapshot(c, A, 'technical_request_errors', kind='RATIO', state='NOT_COMPUTABLE')
        with tx(A) as c:
            snapshot(c, A, 'technical_request_errors', kind='RATIO', state='NOT_COMPUTABLE', reason='ZERO_DENOMINATOR', denominator=0)
            snapshot(c, A, 'technical_request_errors', kind='RATIO', value='0.2', numerator=2, denominator=10, flt='b' * 64)

    def test_snapshot_vorlaeufig_bei_unvollstaendiger_quelle_und_nur_anhaengen(self):
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation, msg='unvollständige Quelle muss vorläufig sein'): snapshot(c, A, value=8, numerator=8, coverage='0.8000', flt='c' * 64)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation, msg='vorläufig ohne Abdeckungsgrad'): snapshot(c, A, state='PROVISIONAL', value=8, numerator=8, flt='d' * 64)
        with tx(A) as c:
            snapshot(c, A, state='PROVISIONAL', value=8, numerator=8, coverage='0.8000', flt='e' * 64)
            snapshot(c, A, value=10, numerator=10, coverage='1.0000', flt='f' * 64)
        with tx(A) as c:
            for stmt in ("UPDATE taktovia.aggregate_snapshot SET value = 99", 'DELETE FROM taktovia.aggregate_snapshot'):
                with self.assertRaises(psycopg.errors.InsufficientPrivilege): c.execute(stmt)
                c.rollback(); c.execute("SELECT set_config('app.tenant_id', %s, true)", (str(A),))

    def test_dieselbe_auswertung_auf_demselben_datenstand_ist_ein_snapshot(self):
        with tx(A) as c:
            snapshot(c, A, value=3, numerator=3, flt='1' * 64)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation): snapshot(c, A, value=3, numerator=3, flt='1' * 64)

    def test_neue_version_snapshot_behaelt_seine_version_und_pflichtfelder(self):
        with tx(A) as c:
            snapshot(c, A, 'capacity_rejections', 1, value=2, numerator=2, flt='2' * 64)
            draft(c, A, 'capacity_rejections', exclusions='geändert')
            v = c.execute("SELECT max(version) FROM taktovia.metric_definition_revision WHERE metric_id = 'capacity_rejections'").fetchone()[0]
            self.assertEqual(v, 2, 'geänderte Definition ist eine neue Version')
            c.execute("UPDATE taktovia.metric_definition_revision SET state = 'APPROVED', approved_by = 'test' WHERE metric_id = 'capacity_rejections' AND version = 2")
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.aggregate_snapshot WHERE metric_id = 'capacity_rejections' AND definition_version = 1").fetchone()[0], 1)
            self.assertEqual(c.execute("SELECT exclusions FROM taktovia.metric_definition_revision WHERE metric_id = 'capacity_rejections' AND version = 1").fetchone()[0], 'Testanfragen; technische Fehler (keine fachliche Ablehnung).')
        for missing in ('numerator_definition', 'denominator_definition', 'exclusions', 'time_basis', 'filter_dimensions'):
            with tx(A) as c:
                draft(c, A, 'passenger_km', **{missing: None})
                with self.assertRaises(psycopg.errors.CheckViolation, msg=f'Freigabe ohne {missing}'):
                    c.execute("UPDATE taktovia.metric_definition_revision SET state = 'APPROVED', approved_by = 'test' WHERE metric_id = 'passenger_km' AND version = (SELECT max(version) FROM taktovia.metric_definition_revision WHERE metric_id = 'passenger_km')")
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation, msg='Version nicht die nächste'): draft(c, A, 'seat_time_utilization', version=7)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation, msg='unbekannte Filterdimension'): draft(c, A, 'seat_time_utilization', filter_dimensions=['passenger_name'])

    def test_gleichzeitige_freigabe_ergibt_eindeutige_versionsfolge(self):
        errors, versions = [], []

        def worker(i):
            try:
                with tx(A) as c:
                    draft(c, A, 'mean_pickup_delay', exclusions=f'Variante {i}')
                    v = c.execute("SELECT max(version) FROM taktovia.metric_definition_revision WHERE metric_id = 'mean_pickup_delay'").fetchone()[0]
                    c.execute("UPDATE taktovia.metric_definition_revision SET state = 'APPROVED', approved_by = 'test' WHERE metric_id = 'mean_pickup_delay' AND exclusions = %s", (f'Variante {i}',))
                    versions.append(v)
            except Exception as e:   # eine ältere Version darf nach einer neueren nicht mehr freigegeben werden
                errors.append(e)
        threads = [threading.Thread(target=worker, args=(i,)) for i in range(6)]
        [t.start() for t in threads]; [t.join() for t in threads]
        with tx(A) as c:
            rows = c.execute("SELECT version, state, approved_at FROM taktovia.metric_definition_revision WHERE metric_id = 'mean_pickup_delay' ORDER BY version").fetchall()
        nums = [r[0] for r in rows]
        self.assertEqual(nums, list(range(1, len(nums) + 1)), 'lückenlose, eindeutige Versionsfolge')
        self.assertGreaterEqual(len(nums), 2, 'mindestens die Seed-Version und eine neue')
        approved = [r for r in rows if r[1] == 'APPROVED']
        self.assertEqual([r[2] for r in approved], sorted(r[2] for r in approved), 'Freigaben folgen der Versionsfolge')
        for e in errors: self.assertIsInstance(e, psycopg.errors.CheckViolation, str(e))

    def test_mandantentrennung(self):
        seed(B)
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(DISTINCT tenant_id) FROM taktovia.metric_definition_revision").fetchone()[0], 1)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.aggregate_snapshot WHERE tenant_id = %s", (A,)).fetchone()[0], 0)
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): snapshot(c, A, value=1, numerator=1, flt='9' * 64)


if __name__ == '__main__':
    unittest.main()
