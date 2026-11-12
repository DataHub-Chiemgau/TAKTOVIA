"""WP-M4-08 / LH-6.24-TIM-001: time_event unter der echten Runtime-Rolle: Mandantentrennung, Unveränderlichkeit (UPDATE/DELETE scheitern), Eindeutigkeit der Ereignis-ID
(auch bei parallelem Einfügen), optionale Schicht, Erfassungsquelle mit Akteur und Begründung bei Nacherfassung."""
import threading, unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()
T0 = '2026-12-01T06:00:00Z'


def fixture(tenant):
    veh, shift = uid(), uid()
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'x','{}')", (tenant, veh))
        c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) "
                  "VALUES (%s,%s,%s,%s,now(),now()+interval '8 hours','Europe/Berlin',0,'PLANNED')", (tenant, shift, uid(), veh))
    return shift


def ev(c, tenant, eid=None, person='emp-1', kind='WORK_START', source='APP', by=None, shift=None, reason=None, at=T0, device=T0):
    eid = eid or uid()
    c.execute("INSERT INTO taktovia.time_event(tenant_id,id,person_ref,kind,occurred_at,device_recorded_at,source,recorded_by,shift_id,backfill_reason,payload_sha256) "
              "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)", (tenant, eid, person, kind, at, device, source, by or person, shift, reason, 'a' * 64))
    return eid


class Zeitereignis(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm408-a'); seed_tenant(B, 'm408-b')
        cls.shift_a, cls.shift_b = fixture(A), fixture(B)

    def test_time_event_append_only_und_idempotent(self):  # AK 3
        with tx(A) as c: e = ev(c, A)
        for sql in ("UPDATE taktovia.time_event SET kind='WORK_END' WHERE id=%s", "UPDATE taktovia.time_event SET occurred_at=now() WHERE id=%s", 'DELETE FROM taktovia.time_event WHERE id=%s'):
            with tx(A) as c:
                with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InsufficientPrivilege), msg=sql):
                    c.execute(sql, (e,))
        with tx(A) as c:   # dieselbe Ereignis-ID: zweiter Eintrag scheitert, mit ON CONFLICT bleibt es bei einer Zeile
            with self.assertRaises(psycopg.errors.UniqueViolation): ev(c, A, eid=e)
        with tx(A) as c:
            n = c.execute("INSERT INTO taktovia.time_event(tenant_id,id,person_ref,kind,occurred_at,device_recorded_at,source,recorded_by,payload_sha256) "
                          "VALUES (%s,%s,'emp-1','WORK_START',%s,%s,'APP','emp-1',%s) ON CONFLICT (tenant_id,id) DO NOTHING", (A, e, T0, T0, 'a' * 64)).rowcount
            self.assertEqual(n, 0)
        with tx(A) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.time_event WHERE id=%s', (e,)).fetchone()[0], 1)

    def test_rls_fremder_mandant_sieht_nichts_und_schreibt_nicht(self):
        with tx(A) as c: e = ev(c, A)
        with tx(B) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.time_event WHERE id=%s', (e,)).fetchone()[0], 0)
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): ev(c, A)   # WITH CHECK
        with tx(None) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.time_event').fetchone()[0], 0)

    def test_schicht_ist_optional_und_nur_aus_dem_eigenen_mandanten(self):  # AK 2
        with tx(A) as c: ev(c, A)                       # ohne Schicht
        with tx(A) as c: ev(c, A, shift=self.shift_a)   # mit Schicht
        with tx(B) as c:
            with self.assertRaises(psycopg.errors.ForeignKeyViolation): ev(c, B, shift=self.shift_a)   # Schicht des Mandanten A

    def test_zwei_ereignisse_zur_selben_schicht_bleiben_beide_erhalten(self):  # AK 1
        with tx(A) as c:
            e1 = ev(c, A, kind='WORK_START', shift=self.shift_a, at='2026-12-02T06:00:00Z')
            e2 = ev(c, A, kind='WORK_END', shift=self.shift_a, at='2026-12-02T14:00:00Z', device='2026-12-02T14:00:01Z')
        with tx(A) as c:
            rows = c.execute("SELECT id, source, recorded_by FROM taktovia.time_event WHERE shift_id=%s AND person_ref='emp-1' ORDER BY occurred_at", (self.shift_a,)).fetchall()
            self.assertIn(e1, [r[0] for r in rows]); self.assertIn(e2, [r[0] for r in rows])

    def test_quelle_akteur_und_nacherfassung(self):  # AK 5
        with tx(A) as c:   # App/Web: Urheber ist die Person selbst
            with self.assertRaises(psycopg.errors.CheckViolation): ev(c, A, source='APP', by='someone-else')
        with tx(A) as c:   # Nacherfassung: Begründung Pflicht, Urheber ist der Akteur
            with self.assertRaises(psycopg.errors.CheckViolation): ev(c, A, source='BACKFILL', by='hr-1', reason=None)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): ev(c, A, source='APP', reason='nachgetragen')   # Begründung nur bei Nacherfassung
        with tx(A) as c:
            e = ev(c, A, source='BACKFILL', by='hr-1', reason='Stempeln vergessen', device=None)
            self.assertEqual(c.execute('SELECT source, recorded_by, person_ref FROM taktovia.time_event WHERE id=%s', (e,)).fetchone(), ('BACKFILL', 'hr-1', 'emp-1'))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): ev(c, A, source='APP', device=None)   # App/Web tragen die Gerätezeit
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): ev(c, A, source='MANUAL')
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): ev(c, A, kind='BREAK')

    def test_serverempfangszeit_setzt_die_datenbank(self):
        with tx(A) as c:
            e = ev(c, A, at='2020-01-01T00:00:00Z', device='2020-01-01T00:00:00Z')
            r = c.execute("SELECT received_at > now() - interval '1 minute', device_recorded_at < received_at FROM taktovia.time_event WHERE id=%s", (e,)).fetchone()
            self.assertEqual(r, (True, True))

    def test_hundert_parallele_einfuegungen_derselben_ereignis_id_ergeben_eine_zeile(self):  # konkurrierend
        eid, results = uid(), []
        def go():
            try:
                with tx(A) as c:
                    results.append(c.execute("INSERT INTO taktovia.time_event(tenant_id,id,person_ref,kind,occurred_at,device_recorded_at,source,recorded_by,payload_sha256) "
                                             "VALUES (%s,%s,'emp-9','WORK_START',%s,%s,'APP','emp-9',%s) ON CONFLICT (tenant_id,id) DO NOTHING", (A, eid, T0, T0, 'b' * 64)).rowcount)
            except Exception as ex:  # noqa: BLE001
                results.append(ex)
        threads = [threading.Thread(target=go) for _ in range(100)]
        [t.start() for t in threads]; [t.join() for t in threads]
        self.assertEqual([r for r in results if not isinstance(r, int)], [])
        self.assertEqual(sum(results), 1)
        with admin() as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.time_event WHERE id=%s', (eid,)).fetchone()[0], 1)

    def test_ereignis_id_eindeutig_je_mandant(self):  # TIM-003: dauerhafte Deduplikation, unabhängig vom Response-Cache
        eid = uid()
        with tx(A) as c: ev(c, A, eid=eid)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation): ev(c, A, eid=eid, person='emp-2')   # auch für eine andere Person im selben Mandanten
        with tx(B) as c: ev(c, B, eid=eid)   # die ID ist je Mandant eindeutig, nicht global

    def test_herkunft_und_konfliktmarke(self):  # TIM-003 AK 2 und 3
        def ins(c, origin=None, flag=None, source='APP', by='emp-1', reason=None, device=T0):
            cols = "tenant_id,id,person_ref,kind,occurred_at,device_recorded_at,source,recorded_by,backfill_reason,payload_sha256" + (",origin" if origin else "") + (",conflict_flag" if flag else "")
            vals = [A, uid(), 'emp-1', 'WORK_START', T0, device, source, by, reason, 'c' * 64] + ([origin] if origin else []) + ([flag] if flag else [])
            c.execute(f"INSERT INTO taktovia.time_event({cols}) VALUES ({','.join(['%s'] * len(vals))})", vals)
        with tx(A) as c: ins(c)                                   # Vorgabe: ONLINE, keine Marke
        with tx(A) as c: ins(c, origin='OFFLINE', flag='CLOCK_SKEW')
        with tx(A) as c: ins(c, origin='OFFLINE', flag='INVALID_ORDER')
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT origin, conflict_flag FROM taktovia.time_event WHERE person_ref='emp-1' AND recorded_by='emp-1' AND payload_sha256=%s ORDER BY origin LIMIT 1", ('c' * 64,)).fetchone()[0], 'OFFLINE')
        for origin, flag in (('SYNC', None), ('OFFLINE', 'MAYBE')):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation): ins(c, origin=origin, flag=flag)
        with tx(A) as c:   # Nacherfassung ist nie offline und nie markiert
            with self.assertRaises(psycopg.errors.CheckViolation): ins(c, origin='OFFLINE', source='BACKFILL', by='hr-1', reason='vergessen', device=None)
        with tx(A) as c:   # Marke nachträglich entfernen scheitert (unveränderlich)
            with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InsufficientPrivilege)):
                c.execute("UPDATE taktovia.time_event SET conflict_flag = NULL WHERE person_ref='emp-1'")


if __name__ == '__main__':
    unittest.main()
