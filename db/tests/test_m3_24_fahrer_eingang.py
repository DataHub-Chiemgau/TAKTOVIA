"""WP-M3-24: driver_device und driver_event unter der echten Runtime-Rolle: Mandantentrennung (RLS, zusammengesetzte Schlüssel), Unveränderlichkeit
des Ereigniseingangs, endgültige Beendigung der Gerätebindung, Dedupe über den Primärschlüssel auch bei parallelem Einfügen."""
import threading, unittest
import psycopg
from helpers import admin, runtime, set_tenant, tx, uid, seed_tenant

A, B = uid(), uid()
ISS = 'https://idp.test/realms/m324'
T0 = '2026-11-12T08:00:00Z'


def fixture(tenant):
    """Fahrzeug, Schicht und Gerät eines Mandanten; liefert (vehicle, shift, device)."""
    veh, shift, dev = uid(), uid(), uid()
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'x','{}')", (tenant, veh))
        c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) "
                  "VALUES (%s,%s,%s,%s,now(),now()+interval '8 hours','Europe/Berlin',0,'PLANNED')", (tenant, shift, uid(), veh))
        c.execute("INSERT INTO taktovia.driver_device(tenant_id,id,issuer,subject,idp_session_id,platform,app_version) VALUES (%s,%s,%s,'driver-1','sid-12345678','ANDROID','1.0.0')", (tenant, dev, ISS))
    return veh, shift, dev


def event(c, tenant, veh, shift, dev, event_id=None, outcome='APPLIED', reason=None, etype='ARRIVAL'):
    eid = event_id or uid()
    c.execute("INSERT INTO taktovia.driver_event(tenant_id,event_id,device_id,shift_id,vehicle_id,event_type,payload_sha256,outcome,reason_code,plan_revision,device_recorded_at,occurred_at) "
              "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,1,%s,%s)", (tenant, eid, dev, shift, veh, etype, 'a' * 64, outcome, reason, T0, T0))
    return eid


class FahrerEingang(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm324-a'); seed_tenant(B, 'm324-b')
        cls.va, cls.sa, cls.da = fixture(A)
        cls.vb, cls.sb, cls.db = fixture(B)

    def test_rls_fremder_mandant_sieht_nichts_und_schreibt_nicht(self):
        with tx(A) as c: e = event(c, A, self.va, self.sa, self.da)
        with tx(B) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.driver_event WHERE event_id=%s', (e,)).fetchone()[0], 0)
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.driver_device WHERE id=%s', (self.da,)).fetchone()[0], 0)
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                event(c, A, self.va, self.sa, self.da)   # WITH CHECK
        with tx(None) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.driver_event').fetchone()[0], 0, 'ohne Kontext keine Zeile')

    def test_fremdes_geraet_oder_fremde_schicht_scheitert_am_zusammengesetzten_schluessel(self):
        with tx(B) as c:
            with self.assertRaises(psycopg.errors.ForeignKeyViolation):
                event(c, B, self.vb, self.sb, self.da)       # Gerät des Mandanten A
        with tx(B) as c:
            with self.assertRaises(psycopg.errors.ForeignKeyViolation):
                event(c, B, self.vb, self.sa, self.db)       # Schicht des Mandanten A

    def test_ereigniseingang_ist_unveraenderlich(self):
        with tx(A) as c: e = event(c, A, self.va, self.sa, self.da)
        for sql in ("UPDATE taktovia.driver_event SET outcome='REVIEW_REQUIRED', reason_code='STALE_PLAN' WHERE event_id=%s", 'DELETE FROM taktovia.driver_event WHERE event_id=%s'):
            with tx(A) as c:
                with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InsufficientPrivilege)):
                    c.execute(sql, (e,))

    def test_review_verlangt_grund_und_apply_keinen(self):
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): event(c, A, self.va, self.sa, self.da, outcome='REVIEW_REQUIRED')
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): event(c, A, self.va, self.sa, self.da, outcome='APPLIED', reason='STALE_PLAN')
        with tx(A) as c: event(c, A, self.va, self.sa, self.da, outcome='REVIEW_REQUIRED', reason='STALE_PLAN')

    def test_dedupe_hundert_parallele_einfuegungen_ergeben_eine_zeile(self):
        eid, results = uid(), []
        def go():
            try:
                with tx(A) as c:
                    n = c.execute("INSERT INTO taktovia.driver_event(tenant_id,event_id,device_id,shift_id,vehicle_id,event_type,payload_sha256,outcome,plan_revision,device_recorded_at,occurred_at) "
                                  "VALUES (%s,%s,%s,%s,%s,'ARRIVAL',%s,'APPLIED',1,%s,%s) ON CONFLICT (tenant_id,event_id) DO NOTHING", (A, eid, self.da, self.sa, self.va, 'b' * 64, T0, T0)).rowcount
                    results.append(n)
            except Exception as ex:  # noqa: BLE001
                results.append(ex)
        threads = [threading.Thread(target=go) for _ in range(100)]
        [t.start() for t in threads]; [t.join() for t in threads]
        self.assertEqual([r for r in results if not isinstance(r, int)], [])
        self.assertEqual(sum(results), 1, 'genau eine Einfügung wirkt')
        with tx(A) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.driver_event WHERE event_id=%s', (eid,)).fetchone()[0], 1)

    def test_geraet_identitaet_unveraenderlich_rebind_zaehlt_revision_beendigung_endgueltig(self):
        veh, shift, dev = fixture(A)
        with tx(A) as c:
            self.assertEqual(c.execute('SELECT revision FROM taktovia.driver_device WHERE id=%s', (dev,)).fetchone()[0], 1)
            c.execute("UPDATE taktovia.driver_device SET idp_session_id='sid-87654321' WHERE id=%s", (dev,))
            self.assertEqual(c.execute('SELECT revision FROM taktovia.driver_device WHERE id=%s', (dev,)).fetchone()[0], 2)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.driver_device SET subject='driver-2' WHERE id=%s", (dev,))
        with tx(A) as c:
            c.execute('UPDATE taktovia.driver_device SET revoked_at=clock_timestamp() WHERE id=%s', (dev,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute('UPDATE taktovia.driver_device SET revoked_at=NULL WHERE id=%s', (dev,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                c.execute('DELETE FROM taktovia.driver_device WHERE id=%s', (dev,))

    def test_rls_ist_erzwungen(self):
        with admin() as c:
            rows = c.execute("SELECT relname, relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname IN ('driver_device','driver_event') AND relnamespace='taktovia'::regnamespace").fetchall()
        self.assertEqual(len(rows), 2)
        self.assertTrue(all(r[1] and r[2] for r in rows), rows)


if __name__ == '__main__':
    unittest.main()
