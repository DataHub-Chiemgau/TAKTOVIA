"""WP-M4-53 / LH-I.6-KPI-007: Grenzwertbindung der Abholpünktlichkeit unter der echten Runtime-Rolle.
Die Berechnung selbst (Zähler, Klassifikation) prüft backend/modules/analytics (PickupPunctualityTest); hier die Datenbankzusagen: die zum Vergleichsstichtag gebundene Konfigurationsrevision
ist nur anhängbar und unveränderlich, gespeicherte Berichtstände bleiben bei einer späteren Grenzwertänderung gleich, Mandantentrennung, Seed der ausführbaren Definitionen."""
import json
import threading
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()
BIND = ("INSERT INTO taktovia.pickup_tolerance_binding(tenant_id,booking_id,promise_version,service_id,bound_at,tolerance_min,config_level,config_revision_id,config_revision) "
        "VALUES (taktovia.current_tenant(),%s,%s,%s,%s,%s,%s,%s,%s) ON CONFLICT DO NOTHING")
SNAP = ("INSERT INTO taktovia.aggregate_snapshot(tenant_id,id,metric_id,definition_version,value_kind,filter,filter_hash,period_from,period_to,reporting_timezone,state,value,numerator,denominator,coverage,"
        "data_as_of,source_events,parameter_versions) VALUES (taktovia.current_tenant(),%s,'pickup_on_time',2,'RATIO','{}',repeat('a',64),'2026-11-30T23:00:00Z','2026-12-01T23:00:00Z','Europe/Berlin','OK',1,1,1,1,"
        "'2026-12-01T08:22:00Z',3,%s::jsonb)")


class Abholpuenktlichkeit(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm453-a'); seed_tenant(B, 'm453-b')
        for t in (A, B):
            with tx(t) as c:
                c.execute("SELECT taktovia.seed_metric_definitions()"); c.execute("SELECT taktovia.seed_pickup_punctuality_definitions()")

    def test_grenzwertaenderung_aendert_gespeicherten_stand_nicht(self):
        b, rev1, rev2, snap = uid(), uid(), uid(), uid()
        with tx(A) as c:
            self.assertEqual(c.execute(BIND, (b, 1, uid(), '2026-12-01T06:00:00Z', 3, 'REGION', rev1, 1)).rowcount, 1)
            c.execute(SNAP, (snap, json.dumps([{'parameter': 'on_time_pickup_tolerance_min', 'config_revision_id': str(rev1), 'value': 3, 'bookings': 1}])))
        # eine später gültige Revision (Grenzwert 2) steht für dieselbe Zusageversion nie an der Stelle der gebundenen: der zweite Eintrag wird nicht übernommen
        with tx(A) as c:
            self.assertEqual(c.execute(BIND, (b, 1, uid(), '2026-12-01T09:00:00Z', 2, 'REGION', rev2, 2)).rowcount, 0)
        with tx(A) as c:
            row = c.execute("SELECT tolerance_min, config_revision_id, config_revision FROM taktovia.pickup_tolerance_binding WHERE booking_id=%s", (b,)).fetchall()
            self.assertEqual(row, [(3, rev1, 1)], "die zum Vergleichsstichtag wirksame Revision bleibt gebunden")
            stored = c.execute("SELECT state, value, definition_version, parameter_versions->0->>'config_revision_id' FROM taktovia.aggregate_snapshot WHERE id=%s", (snap,)).fetchone()
            self.assertEqual(stored, ('OK', 1, 2, str(rev1)), "der gespeicherte Berichtsstand nennt seine Grenzwertversion")
        # Bindung und Berichtsstand sind weder zu ändern noch zu löschen (Runtime-Rolle und Eigentümer)
        for table, key in (('pickup_tolerance_binding', 'booking_id'), ('aggregate_snapshot', 'id')):
            val = b if table == 'pickup_tolerance_binding' else snap
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=table): c.execute(f"UPDATE taktovia.{table} SET tenant_id = tenant_id WHERE {key}=%s", (val,))
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=table): c.execute(f"DELETE FROM taktovia.{table} WHERE {key}=%s", (val,))
        with admin() as a:
            with self.assertRaises(psycopg.errors.Error): a.execute("UPDATE taktovia.pickup_tolerance_binding SET tolerance_min = 9 WHERE booking_id=%s", (b,))
            with self.assertRaises(psycopg.errors.Error): a.execute("DELETE FROM taktovia.pickup_tolerance_binding WHERE booking_id=%s", (b,))
            privs = {r[0] for r in a.execute("SELECT privilege_type FROM information_schema.role_table_grants WHERE grantee='taktovia_runtime' AND table_name='pickup_tolerance_binding'")}
        self.assertEqual(privs, {'SELECT', 'INSERT'})

    def test_neue_zusageversion_bekommt_eine_eigene_bindung_die_alte_bleibt(self):
        b, rev1, rev2 = uid(), uid(), uid()
        with tx(A) as c:
            c.execute(BIND, (b, 1, uid(), '2026-12-01T06:00:00Z', 3, 'TENANT', rev1, 1)); c.execute(BIND, (b, 2, uid(), '2026-12-01T07:00:00Z', 2, 'TENANT', rev2, 2))
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT promise_version, tolerance_min FROM taktovia.pickup_tolerance_binding WHERE booking_id=%s ORDER BY 1", (b,)).fetchall(), [(1, 3), (2, 2)])

    def test_gleichzeitige_bindung_je_zusageversion_genau_einmal(self):
        b, svc = uid(), uid()
        go, errors, rows = threading.Barrier(9), [], []

        def worker(minutes, rev):
            try:
                go.wait(10)
                with tx(A) as c: rows.append(c.execute(BIND, (b, 1, svc, '2026-12-01T06:00:00Z', minutes, 'SERVICE', rev, minutes)).rowcount)
            except Exception as e:   # noqa: BLE001
                errors.append(e)
        ts = [threading.Thread(target=worker, args=(m, uid())) for m in (1, 2, 3, 4, 5, 6, 7, 8)]
        [t.start() for t in ts]; go.wait(10); [t.join(30) for t in ts]
        self.assertEqual(errors, []); self.assertEqual(sum(rows), 1, "genau ein Schreiber gewinnt")
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.pickup_tolerance_binding WHERE booking_id=%s", (b,)).fetchone()[0], 1)

    def test_bindung_ist_je_mandant_getrennt_und_pruefregeln(self):
        b, rev = uid(), uid()
        with tx(A) as c: c.execute(BIND, (b, 1, uid(), '2026-12-01T06:00:00Z', 3, 'SERVICE', rev, 1))
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.pickup_tolerance_binding WHERE booking_id=%s", (b,)).fetchone()[0], 0)
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):   # WITH CHECK: fremder Mandant im Insert
                c.execute("INSERT INTO taktovia.pickup_tolerance_binding(tenant_id,booking_id,promise_version,bound_at,tolerance_min,config_level) VALUES (%s,%s,1,now(),3,'GLOBAL')", (A, uid()))
        for args in ((uid(), 1, None, '2026-12-01T06:00:00Z', 241, 'GLOBAL', None, None), (uid(), 1, None, '2026-12-01T06:00:00Z', -1, 'GLOBAL', None, None),
                     (uid(), 1, None, '2026-12-01T06:00:00Z', 3, 'GLOBAL', uid(), 1), (uid(), 1, None, '2026-12-01T06:00:00Z', 3, 'REGION', None, None), (uid(), 1, None, '2026-12-01T06:00:00Z', 3, 'VEHICLE', uid(), 1)):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation, msg=str(args)): c.execute(BIND, args)

    def test_zusagezeile_traegt_dienst_und_zusagezeitpunkt_andere_zeitarten_nicht(self):
        ins = ("INSERT INTO taktovia.time_fact(tenant_id,booking_id,stop_event,time_kind,value_at,source,source_ref,version_key,promise_version,forecast_version,service_id,promised_at) "
               "VALUES (taktovia.current_tenant(),%s,'PICKUP',%s,'2026-12-01T08:15:00Z','TEST',%s,%s,%s,%s,%s,%s)")
        with tx(A) as c:
            c.execute(ins, (uid(), 'PROMISED', uid(), 'P1', 1, None, uid(), '2026-12-01T06:00:00Z'))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute(ins, (uid(), 'FORECAST', uid(), 'F1', None, 1, uid(), None))

    def test_definitionen_pickup_version_zwei_ausfuehrbar_seed_wiederholbar(self):
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT taktovia.seed_pickup_punctuality_definitions()").fetchone()[0], 0)
            rows = c.execute("SELECT metric_id, version, executable, state, unit FROM taktovia.metric_definition_revision WHERE metric_id IN ('pickup_on_time','mean_pickup_delay') ORDER BY 1, 2").fetchall()
        self.assertEqual(rows, [('mean_pickup_delay', 1, False, 'APPROVED', 'Sekunden'), ('mean_pickup_delay', 2, True, 'APPROVED', 'Minuten'),
                                ('pickup_on_time', 1, False, 'APPROVED', 'Anteil'), ('pickup_on_time', 2, True, 'APPROVED', 'Anteil')])

    def test_parameter_im_katalog_mit_grenzen_und_ebenen(self):
        with admin() as a:
            s = a.execute("SELECT json_schema FROM taktovia.policy_schema WHERE schema_version='1' AND parameter='on_time_pickup_tolerance_min'").fetchone()[0]
        self.assertEqual((s['type'], s['minimum'], s['maximum'], s['default'], s['x-unit']), ('integer', 0, 240, 0, 'MINUTES'))
        self.assertEqual(s['x-levels'], ['TENANT', 'REGION', 'SERVICE'])


if __name__ == '__main__':
    unittest.main()
