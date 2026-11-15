"""WP-M4-58 / LH-6.14-ANA-003: Dimensionsspalten der Faktentabellen (Betreiber, Region, Bediengebiet, Fahrzeug, Fahrzeugtyp), RLS und Indizes.
Unter der echten Runtime-Rolle: jede Faktentabelle isoliert die Mandanten, die Dimensionsspalten existieren mit den vorgesehenen Typen, jeder Index beginnt mit tenant_id,
ein fremder Mandant im Insert scheitert an WITH CHECK, und die Dimensionsfilter selbst liefern nie Zeilen eines anderen Mandanten (auch nicht mit fremden Kennungen)."""
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()
FACT_TABLES = ['fact_attempt', 'fact_offer', 'fact_booking', 'fact_stop_event', 'fact_vehicle_state', 'dataset_watermark', 'aggregate_snapshot', 'analytics_event_inbox']
DIMENSIONS = {
    'fact_attempt': {'operator_id': 'uuid', 'region_id': 'uuid', 'service_area_id': 'uuid'},
    'fact_offer': {'operator_id': 'uuid', 'region_id': 'uuid', 'service_area_id': 'uuid'},
    'fact_booking': {'operator_id': 'uuid', 'region_id': 'uuid', 'service_area_id': 'uuid', 'vehicle_id': 'uuid', 'vehicle_type': 'text'},
}
OPERATOR_A, REGION_A, AREA_A, SERVICE_A = uid(), uid(), uid(), uid()
OPERATOR_B, REGION_B, AREA_B, SERVICE_B = uid(), uid(), uid(), uid()
ATTEMPT = ("INSERT INTO taktovia.fact_attempt(tenant_id,attempt_id,service_id,outcome,time_mode,requested_at,passengers,operator_id,region_id,service_area_id) "
           "VALUES (taktovia.current_tenant(),%s,%s,'RECORDED','DEPARTURE','2026-12-01T07:10:00Z',1,%s,%s,%s)")


class AnalyticsFaktenRls(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm458-a'); seed_tenant(B, 'm458-b')
        for t, op, rg, ar, sv in ((A, OPERATOR_A, REGION_A, AREA_A, SERVICE_A), (B, OPERATOR_B, REGION_B, AREA_B, SERVICE_B)):
            with tx(t) as c:
                for _ in range(4): c.execute(ATTEMPT, (uid(), sv, op, rg, ar))
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.fact_booking(tenant_id,booking_id,service_id,state,operator_id,region_id,vehicle_id,vehicle_type) VALUES (taktovia.current_tenant(),%s,%s,'CONFIRMED',%s,%s,%s,'KLEINBUS')",
                      (uid(), SERVICE_A, OPERATOR_A, REGION_A, uid()))

    def test_faktentabellen_rls_isoliert_mandanten(self):
        with admin() as a:
            for t in FACT_TABLES:
                row = a.execute("SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE oid = to_regclass(%s)", (f'taktovia.{t}',)).fetchone()
                self.assertEqual(row, (True, True), f'{t}: RLS aktiv und erzwungen')
                pol = a.execute("SELECT count(*) FROM pg_policies WHERE schemaname='taktovia' AND tablename=%s AND qual LIKE %s", (t, '%tenant_id%')).fetchone()[0]
                self.assertGreaterEqual(pol, 1, f'{t}: Mandantenpolicy')
        # jede Faktentabelle zeigt unter dem Mandanten B nichts von A und umgekehrt (Dimensionsfilter mit fremden Kennungen eingeschlossen)
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.fact_attempt WHERE service_id=%s OR operator_id=%s OR region_id=%s OR service_area_id=%s", (SERVICE_A, OPERATOR_A, REGION_A, AREA_A)).fetchone()[0], 0)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.fact_booking WHERE vehicle_type='KLEINBUS' OR service_id=%s", (SERVICE_A,)).fetchone()[0], 0)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.fact_attempt").fetchone()[0], 4)
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.fact_attempt WHERE region_id=%s", (REGION_A,)).fetchone()[0], 4)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.fact_attempt WHERE region_id=%s", (REGION_B,)).fetchone()[0], 0)
        # ohne Mandantenkontext keine Zeile
        with tx(None) as c:
            for t in ('fact_attempt', 'fact_booking'):
                self.assertEqual(c.execute(f"SELECT count(*) FROM taktovia.{t}").fetchone()[0], 0, t)
        # WITH CHECK: ein fremder Mandant im Insert scheitert
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                c.execute("INSERT INTO taktovia.fact_attempt(tenant_id,attempt_id,service_id,outcome,time_mode,requested_at,passengers,region_id) VALUES (%s,%s,%s,'RECORDED','DEPARTURE',now(),1,%s)", (B, uid(), SERVICE_B, REGION_B))

    def test_dimensionsspalten_vorhanden_mit_typ(self):
        with admin() as a:
            for table, cols in DIMENSIONS.items():
                have = dict(a.execute("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema='taktovia' AND table_name=%s", (table,)).fetchall())
                for col, typ in cols.items():
                    self.assertEqual(have.get(col), typ, f'{table}.{col}')

    def test_jeder_index_der_faktentabellen_beginnt_mit_tenant_id(self):
        with admin() as a:
            rows = a.execute("""SELECT c.relname, i.relname, (SELECT a.attname FROM pg_attribute a WHERE a.attrelid = x.indrelid AND a.attnum = x.indkey[0])
                                FROM pg_index x JOIN pg_class c ON c.oid = x.indrelid JOIN pg_class i ON i.oid = x.indexrelid JOIN pg_namespace n ON n.oid = c.relnamespace
                                WHERE n.nspname = 'taktovia' AND c.relname = ANY(%s)""", (list(FACT_TABLES),)).fetchall()
        self.assertTrue(rows)
        for table, index, first in rows:
            # Ausdrucksindizes haben keine Spalte an erster Stelle; die Berichtsindizes dieses Pakets sind Spaltenindizes
            if first is not None or index.startswith('fact_'):
                self.assertEqual(first, 'tenant_id', f'{table}.{index}')
        names = {r[1] for r in rows}
        for expected in ('fact_attempt_by_operator', 'fact_attempt_by_region', 'fact_attempt_by_area', 'fact_booking_by_vehicle'):
            self.assertIn(expected, names)


if __name__ == '__main__':
    unittest.main()
