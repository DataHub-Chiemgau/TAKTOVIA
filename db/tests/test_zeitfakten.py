"""WP-M4-52 / LH-I.6-KPI-006: Zeitfakten (geplant, angekündigt, zugesagt, prognostiziert, tatsächlich) unter der echten Runtime-Rolle.
Die Zeitart `announced` ist bis zur Festlegung OP 28 nur mit Testwert belegt (Quelle `TEST_OP28`)."""
import threading
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()
INS = ("INSERT INTO taktovia.time_fact(tenant_id,booking_id,stop_event,time_kind,value_at,window_earliest,source,source_ref,version_key,"
       "promise_version,plan_revision,forecast_version,device_time,server_time,offline_recorded) "
       "VALUES (taktovia.current_tenant(),%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) ON CONFLICT DO NOTHING")


def put(c, booking, stop, kind, at, *, window=None, source='TEST', ref=None, key=None, promise=None, plan=None, forecast=None, device=None, server=None, offline=False):
    return c.execute(INS, (booking, stop, kind, at, window, source, ref or uid(), key or f'{kind}:{promise or plan or forecast or 0}', promise, plan, forecast, device, server, offline)).rowcount


def delay(c, booking):
    return {r[0]: r[1:] for r in c.execute("SELECT stop_event, promise_version, target_at, actual_at, delay_seconds, not_assessable_reason FROM taktovia.time_fact_delay(%s)", (booking,))}


class Zeitfakten(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm452-a'); seed_tenant(B, 'm452-b')

    def test_verspaetung_gegen_zusageversion_nach_umplanung(self):
        b = uid()
        with tx(A) as c:
            put(c, b, 'PICKUP', 'PROMISED', '2026-12-01T10:15:00Z', window='2026-12-01T10:00:00Z', promise=1, source='BOOKING_CONFIRMED')
            put(c, b, 'PICKUP', 'PLANNED', '2026-12-01T10:05:00Z', plan=1)
            put(c, b, 'PICKUP', 'ANNOUNCED', '2026-12-01T10:10:00Z', source='TEST_OP28')
            put(c, b, 'PICKUP', 'FORECAST', '2026-12-01T10:12:00Z', forecast=1)
            put(c, b, 'PICKUP', 'ACTUAL', '2026-12-01T10:20:00Z', server='2026-12-01T10:20:01Z')
            put(c, b, 'ARRIVAL', 'PROMISED', '2026-12-01T10:45:00Z', promise=1)
        with tx(A) as c:
            self.assertEqual(delay(c, b)['PICKUP'][3], 300, "Ist 10:20 minus Ende des Zusagefensters 10:15")
        # Umplanung und neue Prognose nach der Zusage: die Vergleichsbasis bleibt
        with tx(A) as c:
            put(c, b, 'PICKUP', 'PLANNED', '2026-12-01T10:40:00Z', plan=2); put(c, b, 'PICKUP', 'PLANNED', '2026-12-01T11:10:00Z', plan=3)
            put(c, b, 'PICKUP', 'FORECAST', '2026-12-01T11:30:00Z', forecast=2)
        with tx(A) as c:
            promise_version, target, _, seconds, reason = delay(c, b)['PICKUP']
            self.assertEqual((promise_version, seconds, reason), (1, 300, None))
            self.assertEqual(str(target.isoformat()), '2026-12-01T10:15:00+00:00')
            kinds = [r[0] for r in c.execute("SELECT DISTINCT time_kind FROM taktovia.time_fact WHERE booking_id=%s AND stop_event='PICKUP' ORDER BY 1", (b,))]
            self.assertEqual(kinds, ['ACTUAL', 'ANNOUNCED', 'FORECAST', 'PLANNED', 'PROMISED'], "alle fünf Zeitarten getrennt gespeichert")
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.time_fact WHERE booking_id=%s AND time_kind='PLANNED'", (b,)).fetchone()[0], 3, "jede Planrevision ist eine eigene Zeile")
        # einvernehmliche Änderung der Zusage: neue Zusageversion, die alte bleibt lesbar
        with tx(A) as c:
            put(c, b, 'PICKUP', 'PROMISED', '2026-12-01T10:30:00Z', window='2026-12-01T10:15:00Z', promise=2, source='PROMISE_CHANGE')
        with tx(A) as c:
            promise_version, _, _, seconds, _ = delay(c, b)['PICKUP']
            self.assertEqual((promise_version, seconds), (2, -600))
            self.assertEqual([r[0] for r in c.execute("SELECT promise_version FROM taktovia.time_fact WHERE booking_id=%s AND time_kind='PROMISED' AND stop_event='PICKUP' ORDER BY 1", (b,))], [1, 2])
            self.assertEqual(delay(c, b)['ARRIVAL'][4], 'NO_ACTUAL')

    def test_fehlende_ist_zeit_und_fehlende_zusage_sind_nicht_bewertbar_nie_null_sekunden(self):
        b, b2 = uid(), uid()
        with tx(A) as c:
            put(c, b, 'PICKUP', 'PROMISED', '2026-12-01T10:15:00Z', promise=1)
            put(c, b2, 'PICKUP', 'ACTUAL', '2026-12-01T10:15:00Z', server='2026-12-01T10:15:00Z')
        with tx(A) as c:
            self.assertEqual(delay(c, b)['PICKUP'], (1, c.execute("SELECT '2026-12-01T10:15:00Z'::timestamptz").fetchone()[0], None, None, 'NO_ACTUAL'))
            self.assertEqual(delay(c, b2)['PICKUP'][3:], (None, 'NO_PROMISE'))
            self.assertEqual(delay(c, uid()), {})

    def test_zeile_ist_unveraenderlich_update_und_delete_abgewiesen(self):
        b = uid()
        with tx(A) as c: put(c, b, 'PICKUP', 'PROMISED', '2026-12-01T10:15:00Z', promise=1)
        for sql in ("UPDATE taktovia.time_fact SET value_at=now() WHERE booking_id=%s", "DELETE FROM taktovia.time_fact WHERE booking_id=%s"):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=sql): c.execute(sql, (b,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): c.execute("TRUNCATE taktovia.time_fact")
        with admin() as a:   # auch der Eigentümer: der Trigger weist ab; Rechte der Runtime-Rolle genau SELECT, INSERT
            with self.assertRaises(psycopg.errors.Error): a.execute("UPDATE taktovia.time_fact SET value_at=now() WHERE booking_id=%s", (b,))
            with self.assertRaises(psycopg.errors.Error): a.execute("DELETE FROM taktovia.time_fact WHERE booking_id=%s", (b,))
            privs = {r[0] for r in a.execute("SELECT privilege_type FROM information_schema.role_table_grants WHERE grantee='taktovia_runtime' AND table_name='time_fact'")}
        self.assertEqual(privs, {'SELECT', 'INSERT'})

    def test_offline_ist_zeit_behaelt_geraete_und_serverzeit(self):
        b = uid()
        with tx(A) as c:
            put(c, b, 'PICKUP', 'ACTUAL', '2026-12-01T10:20:00Z', device='2026-12-01T10:20:00Z', server='2026-12-01T10:52:00Z', offline=True)
        with tx(A) as c:
            row = c.execute("SELECT value_at, device_time, server_time, offline_recorded FROM taktovia.time_fact WHERE booking_id=%s", (b,)).fetchone()
        self.assertNotEqual(row[1], row[2]); self.assertEqual((row[0], row[3]), (row[1], True))
        with tx(A) as c:   # offline ohne Gerätezeit, Ist ohne Serverzeit, Gerätezeit an Nicht-Ist-Zeile: abgewiesen
            for kw in ({'server': '2026-12-01T10:52:00Z', 'offline': True}, {}):
                with self.assertRaises(psycopg.errors.CheckViolation):
                    c.execute("SAVEPOINT s"); put(c, uid(), 'PICKUP', 'ACTUAL', '2026-12-01T10:20:00Z', **kw)
                c.execute("ROLLBACK TO s")
            with self.assertRaises(psycopg.errors.CheckViolation): put(c, uid(), 'PICKUP', 'FORECAST', '2026-12-01T10:20:00Z', forecast=1, device='2026-12-01T10:20:00Z')

    def test_versionen_pflicht_je_zeitart(self):
        for kind, kw in (('PROMISED', {}), ('PLANNED', {}), ('FORECAST', {}), ('PLANNED', {'promise': 1, 'plan': 1})):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation, msg=f'{kind}{kw}'): put(c, uid(), 'PICKUP', kind, '2026-12-01T10:20:00Z', **kw)

    def test_dst_doppelstunde_ergibt_zwei_eindeutige_zeitpunkte(self):
        b = uid()
        with tx(A) as c:   # 25.10.2026, 02:30 Ortszeit Europe/Berlin existiert zweimal (+02:00 und +01:00)
            put(c, b, 'PICKUP', 'ACTUAL', '2026-10-25T02:30:00+02:00', key='ACTUAL:1', server='2026-10-25T02:30:05+02:00')
            put(c, b, 'PICKUP', 'ACTUAL', '2026-10-25T02:30:00+01:00', key='ACTUAL:2', server='2026-10-25T02:30:05+01:00')
        with tx(A) as c:
            vals = [r[0].isoformat() for r in c.execute("SELECT value_at AT TIME ZONE 'UTC' FROM taktovia.time_fact WHERE booking_id=%s ORDER BY seq", (b,))]
            self.assertEqual(vals, ['2026-10-25T00:30:00', '2026-10-25T01:30:00'])
            self.assertEqual(c.execute("SELECT count(DISTINCT value_at) FROM taktovia.time_fact WHERE booking_id=%s", (b,)).fetchone()[0], 2)

    def test_parallele_umplanungen_und_prognose_lassen_zusage_unveraendert_jede_revision_einmal(self):
        b = uid()
        with tx(A) as c: put(c, b, 'PICKUP', 'PROMISED', '2026-12-01T10:15:00Z', promise=1)
        snap = "SELECT string_agg(f::text, ';' ORDER BY seq) FROM taktovia.time_fact f WHERE booking_id=%s AND time_kind='PROMISED'"
        with tx(A) as c: before = c.execute(snap, (b,)).fetchone()[0]
        go, errors, inserted = threading.Barrier(9), [], []

        def worker(kind, **kw):
            try:
                go.wait(10)
                with tx(A) as c: inserted.append((kind, kw, put(c, b, 'PICKUP', kind, '2026-12-01T11:00:00Z', **kw)))
            except Exception as e:   # noqa: BLE001
                errors.append(e)
        jobs = [('PLANNED', {'plan': 2}), ('PLANNED', {'plan': 2}), ('PLANNED', {'plan': 3}), ('PLANNED', {'plan': 3}), ('FORECAST', {'forecast': 2}), ('FORECAST', {'forecast': 2}), ('PLANNED', {'plan': 4}), ('FORECAST', {'forecast': 3})]
        ts = [threading.Thread(target=worker, args=(k,), kwargs=kw) for k, kw in jobs]
        [t.start() for t in ts]; go.wait(10); [t.join(30) for t in ts]
        self.assertEqual(errors, [])
        with tx(A) as c:
            self.assertEqual(c.execute(snap, (b,)).fetchone()[0], before, "Zusageversion unverändert")
            self.assertEqual([r[0] for r in c.execute("SELECT plan_revision FROM taktovia.time_fact WHERE booking_id=%s AND time_kind='PLANNED' ORDER BY 1", (b,))], [2, 3, 4], "jede Revision genau einmal")
            self.assertEqual([r[0] for r in c.execute("SELECT forecast_version FROM taktovia.time_fact WHERE booking_id=%s AND time_kind='FORECAST' ORDER BY 1", (b,))], [2, 3])
        self.assertEqual(sum(n for _, _, n in inserted), 5)

    def test_fakten_eines_fremden_mandanten_sind_nicht_sichtbar(self):
        b = uid()
        with tx(A) as c: put(c, b, 'PICKUP', 'PROMISED', '2026-12-01T10:15:00Z', promise=1)
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.time_fact WHERE booking_id=%s", (b,)).fetchone()[0], 0)
            self.assertEqual(delay(c, b), {})
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):   # WITH CHECK: fremder Mandant im Insert
                c.execute(INS.replace('taktovia.current_tenant()', "'%s'" % A), (b, 'PICKUP', 'PROMISED', '2026-12-01T10:15:00Z', None, 'TEST', uid(), 'x', 1, None, None, None, None, False))


if __name__ == '__main__':
    unittest.main()
