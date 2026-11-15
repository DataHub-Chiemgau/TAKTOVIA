"""WP-M4-54 / LH-I.6-KPI-008: Toleranzbindung der Ankunftspünktlichkeit unter der echten Runtime-Rolle.
Die Berechnung (Zähler, Klassifikation, getrennt von der Abholung) prüft backend/modules/analytics (ArrivalPunctualityTest); hier die Datenbankzusagen: die zum Vergleichsstichtag gebundene
Toleranz ist nur anhängbar und unveränderlich, gespeicherte Berichtstände bleiben bei einer späteren Änderung gleich, Mandantentrennung, Seed der ausführbaren Definitionen, Trennung von der Abholung."""
import json
import threading
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()
BIND = ("INSERT INTO taktovia.arrival_tolerance_binding(tenant_id,booking_id,promise_version,service_id,bound_at,tolerance_min,tolerance_level,tolerance_revision_id,tolerance_revision,"
        "grace_min,grace_level,grace_revision_id,grace_revision) VALUES (taktovia.current_tenant(),%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) ON CONFLICT DO NOTHING")
SNAP = ("INSERT INTO taktovia.aggregate_snapshot(tenant_id,id,metric_id,definition_version,value_kind,filter,filter_hash,period_from,period_to,reporting_timezone,state,value,numerator,denominator,coverage,"
        "data_as_of,source_events,parameter_versions) VALUES (taktovia.current_tenant(),%s,'arrival_on_time',2,'RATIO','{}',repeat('b',64),'2026-11-30T23:00:00Z','2026-12-01T23:00:00Z','Europe/Berlin','OK',1,1,1,1,"
        "'2026-12-01T08:45:00Z',3,%s::jsonb)")
TFACT = ("INSERT INTO taktovia.time_fact(tenant_id,booking_id,stop_event,time_kind,value_at,source,source_ref,version_key,promise_version,server_time,service_id,promised_at) "
         "VALUES (taktovia.current_tenant(),%s,%s,%s,%s,'TEST',%s,%s,%s,%s,%s,%s)")


def bind_args(booking, version=1, minutes=3, grace=0, rev=None, grace_rev=None, at='2026-12-01T06:00:00Z', service=None):
    return (booking, version, service or uid(), at, minutes, 'REGION', rev or uid(), 1, grace, 'GLOBAL' if grace_rev is None else 'SERVICE', grace_rev, None if grace_rev is None else 1)


class Ankunftspuenktlichkeit(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm454-a'); seed_tenant(B, 'm454-b')
        for t in (A, B):
            with tx(t) as c:
                c.execute("SELECT taktovia.seed_metric_definitions()"); c.execute("SELECT taktovia.seed_arrival_punctuality_definitions()")

    def test_fahrt_ohne_ankunftsereignis_nicht_im_nenner(self):
        """Die Datenbankfunktion der Verspätung nennt ohne Ist-Ankunft keinen Wert (NO_ACTUAL), und die Abholung derselben Fahrt bleibt davon getrennt bewertbar."""
        b = uid()
        with tx(A) as c:
            c.execute(TFACT, (b, 'ARRIVAL', 'PROMISED', '2026-12-01T08:45:00Z', uid(), 'P1', 1, None, uid(), '2026-12-01T06:00:00Z'))
            c.execute(TFACT, (b, 'PICKUP', 'PROMISED', '2026-12-01T08:15:00Z', uid(), 'P1', 1, None, uid(), '2026-12-01T06:00:00Z'))
            c.execute(TFACT, (b, 'PICKUP', 'ACTUAL', '2026-12-01T08:12:00Z', uid(), 'E:1', None, '2026-12-01T08:12:00Z', None, None))
        with tx(A) as c:
            rows = c.execute("SELECT stop_event, delay_seconds, not_assessable_reason FROM taktovia.time_fact_delay(%s) ORDER BY 1", (b,)).fetchall()
            self.assertEqual(rows, [('ARRIVAL', None, 'NO_ACTUAL'), ('PICKUP', -180, None)], "Ankunft ohne Ereignis ohne Wert, Abholung getrennt bewertet")
            # die Auswertungsabfrage der Ankunft (actual JOIN promised) liefert für diese Fahrt keine Zeile
            n = c.execute("SELECT count(*) FROM taktovia.time_fact a JOIN taktovia.time_fact p ON p.booking_id = a.booking_id AND p.stop_event = a.stop_event AND p.time_kind = 'PROMISED' "
                          "WHERE a.stop_event = 'ARRIVAL' AND a.time_kind = 'ACTUAL' AND a.booking_id = %s", (b,)).fetchone()[0]
            self.assertEqual(n, 0)

    def test_toleranzaenderung_aendert_gespeicherten_stand_nicht(self):
        b, rev1, rev2, snap = uid(), uid(), uid(), uid()
        with tx(A) as c:
            self.assertEqual(c.execute(BIND, bind_args(b, minutes=3, rev=rev1)).rowcount, 1)
            c.execute(SNAP, (snap, json.dumps([{'parameter': 'on_time_arrival_tolerance_min', 'config_revision_id': str(rev1), 'value': 3, 'bookings': 1}])))
        with tx(A) as c:   # eine später gültige Revision (Toleranz 2) ersetzt für dieselbe Zusageversion nie die gebundene
            self.assertEqual(c.execute(BIND, bind_args(b, minutes=2, rev=rev2, at='2026-12-01T09:00:00Z')).rowcount, 0)
        with tx(A) as c:
            row = c.execute("SELECT tolerance_min, tolerance_revision_id, applied_min FROM taktovia.arrival_tolerance_binding WHERE booking_id=%s", (b,)).fetchall()
            self.assertEqual(row, [(3, rev1, 3)], "die zum Vergleichsstichtag wirksame Revision bleibt gebunden")
            stored = c.execute("SELECT state, value, definition_version, parameter_versions->0->>'config_revision_id' FROM taktovia.aggregate_snapshot WHERE id=%s", (snap,)).fetchone()
            self.assertEqual(stored, ('OK', 1, 2, str(rev1)))
        for table, key, val in (('arrival_tolerance_binding', 'booking_id', b), ('aggregate_snapshot', 'id', snap)):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=table): c.execute(f"UPDATE taktovia.{table} SET tenant_id = tenant_id WHERE {key}=%s", (val,))
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=table): c.execute(f"DELETE FROM taktovia.{table} WHERE {key}=%s", (val,))
        with admin() as a:
            with self.assertRaises(psycopg.errors.Error): a.execute("UPDATE taktovia.arrival_tolerance_binding SET tolerance_min = 9 WHERE booking_id=%s", (b,))
            with self.assertRaises(psycopg.errors.Error): a.execute("DELETE FROM taktovia.arrival_tolerance_binding WHERE booking_id=%s", (b,))
            privs = {r[0] for r in a.execute("SELECT privilege_type FROM information_schema.role_table_grants WHERE grantee='taktovia_runtime' AND table_name='arrival_tolerance_binding'")}
        self.assertEqual(privs, {'SELECT', 'INSERT'})

    def test_wirksame_toleranz_ist_der_groessere_wert_beider_parameter(self):
        for tol, grace in ((2, 5), (5, 2), (4, 4), (0, 0)):
            b = uid()
            with tx(A) as c:
                c.execute(BIND, bind_args(b, minutes=tol, grace=grace, grace_rev=None if grace == 0 else uid()))
            with tx(A) as c:
                self.assertEqual(c.execute("SELECT applied_min FROM taktovia.arrival_tolerance_binding WHERE booking_id=%s", (b,)).fetchone()[0], max(tol, grace))
        with tx(A) as c:   # die berechnete Spalte lässt sich nicht beschreiben
            with self.assertRaises(psycopg.errors.Error):
                c.execute("INSERT INTO taktovia.arrival_tolerance_binding(tenant_id,booking_id,promise_version,bound_at,tolerance_min,tolerance_level,grace_min,grace_level,applied_min) "
                          "VALUES (taktovia.current_tenant(),%s,1,now(),1,'GLOBAL',1,'GLOBAL',99)", (uid(),))

    def test_gleichzeitige_bindung_je_zusageversion_genau_einmal(self):
        b, svc = uid(), uid()
        go, errors, rows = threading.Barrier(9), [], []

        def worker(minutes):
            try:
                go.wait(10)
                with tx(A) as c: rows.append(c.execute(BIND, bind_args(b, minutes=minutes, service=svc)).rowcount)
            except Exception as e:   # noqa: BLE001
                errors.append(e)
        ts = [threading.Thread(target=worker, args=(m,)) for m in range(1, 9)]
        [t.start() for t in ts]; go.wait(10); [t.join(30) for t in ts]
        self.assertEqual(errors, []); self.assertEqual(sum(rows), 1, "genau ein Schreiber gewinnt")
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.arrival_tolerance_binding WHERE booking_id=%s", (b,)).fetchone()[0], 1)

    def test_bindung_ist_je_mandant_getrennt_und_pruefregeln(self):
        b = uid()
        with tx(A) as c: c.execute(BIND, bind_args(b))
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.arrival_tolerance_binding WHERE booking_id=%s", (b,)).fetchone()[0], 0)
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):   # WITH CHECK: fremder Mandant im Insert
                c.execute("INSERT INTO taktovia.arrival_tolerance_binding(tenant_id,booking_id,promise_version,bound_at,tolerance_min,tolerance_level,grace_min,grace_level) VALUES (%s,%s,1,now(),3,'GLOBAL',0,'GLOBAL')", (A, uid()))
        bad = (bind_args(uid(), minutes=241), bind_args(uid(), minutes=-1), bind_args(uid(), grace=241), bind_args(uid(), grace=-1),
               (uid(), 1, None, '2026-12-01T06:00:00Z', 3, 'REGION', None, None, 0, 'GLOBAL', None, None),   # Ebene REGION ohne Revision
               (uid(), 1, None, '2026-12-01T06:00:00Z', 3, 'GLOBAL', uid(), 1, 0, 'GLOBAL', None, None),     # Ebene GLOBAL mit Revision
               (uid(), 1, None, '2026-12-01T06:00:00Z', 3, 'VEHICLE', uid(), 1, 0, 'GLOBAL', None, None))
        for args in bad:
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation, msg=str(args)): c.execute(BIND, args)

    def test_definitionen_ankunft_version_zwei_ausfuehrbar_seed_wiederholbar_und_abholung_unberuehrt(self):
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT taktovia.seed_arrival_punctuality_definitions()").fetchone()[0], 0)
            rows = c.execute("SELECT metric_id, version, executable, state, unit FROM taktovia.metric_definition_revision WHERE metric_id IN ('arrival_on_time','mean_arrival_delay') ORDER BY 1, 2").fetchall()
            pickup = c.execute("SELECT count(*) FROM taktovia.metric_definition_revision WHERE metric_id IN ('pickup_on_time','mean_pickup_delay') AND version > 1").fetchone()[0]
        self.assertEqual(rows, [('arrival_on_time', 1, False, 'APPROVED', 'Anteil'), ('arrival_on_time', 2, True, 'APPROVED', 'Anteil'),
                                ('mean_arrival_delay', 1, False, 'APPROVED', 'Sekunden'), ('mean_arrival_delay', 2, True, 'APPROVED', 'Minuten')])
        self.assertEqual(pickup, 0, "die Abholkennzahlen sind eigene Definitionen und bleiben von diesem Seed unberührt")

    def test_parameter_im_katalog_mit_grenzen_und_ebenen(self):
        with admin() as a:
            s = a.execute("SELECT json_schema FROM taktovia.policy_schema WHERE schema_version='1' AND parameter='on_time_arrival_tolerance_min'").fetchone()[0]
        self.assertEqual((s['type'], s['minimum'], s['maximum'], s['default'], s['x-unit']), ('integer', 0, 240, 0, 'MINUTES'))
        self.assertEqual(s['x-levels'], ['TENANT', 'REGION', 'SERVICE'])


if __name__ == '__main__':
    unittest.main()
