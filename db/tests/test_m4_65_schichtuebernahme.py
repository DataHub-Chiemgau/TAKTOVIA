"""WP-M4-65: Übernahme der Schichtzuweisungen aus M3 in veröffentlichte Dienstplanrevisionen (`adopt_legacy_staff_shifts`, V40650) gegen die echte Datenbank.
Die Altzeilen entstehen unter der Runtime-Rolle wie in M3 (`roster_id` NULL, `roster_revision` 0, PLANNED/STARTED/CANCELLED); die Übernahme läuft als Eigentümer (Administrator)."""
import threading
import time
import unittest
import psycopg
from helpers import admin, migrator, tx, uid, seed_tenant

A, B = uid(), uid()
EMPTY = '{"schema_version": "1.0", "breaks": []}'


def vehicle(c, tenant):
    v = uid()
    c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'fixture','{}')", (tenant, v))
    return v


def legacy(c, tenant, veh, start, end, state='PLANNED', tz='Europe/Berlin', exception=False):
    s = uid()
    c.execute("""INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state,overlap_exception_by,overlap_exception_reason,overlap_exception_at)
                 VALUES (%s,%s,%s,%s,%s,%s,%s,0,%s,%s,%s,%s)""",
              (tenant, s, uid(), veh, start, end, tz, state, 'Leitung' if exception else None, 'Doppelschicht genehmigt' if exception else None, '2026-11-01T00:00:00Z' if exception else None))
    return s


def release(c, tenant, veh, shift):
    r = uid()
    c.execute("""INSERT INTO taktovia.resource_release(tenant_id,id,vehicle_id,shift_id,safety_revision,inspection_ref,qualification_revision,valid_until)
                 VALUES (%s,%s,%s,%s,1,%s,1,'2026-12-31T00:00:00Z')""", (tenant, r, veh, shift, uid()))
    return r


def adopt(tenant):
    with admin() as c:
        return c.execute("SELECT taktovia.adopt_legacy_staff_shifts(%s)", (tenant,)).fetchone()[0]


class Schichtuebernahme(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm465-a'); seed_tenant(B, 'm465-b')

    def test_jede_altschicht_gehoert_danach_zu_genau_einer_veroeffentlichten_revision_und_freigaben_bleiben(self):
        t = uid(); seed_tenant(t, f'm465-{t}')
        with tx(t) as c:
            v1 = vehicle(c, t); v2 = vehicle(c, t)
            planned = legacy(c, t, v1, '2026-12-01T05:00:00Z', '2026-12-01T13:00:00Z')
            started = legacy(c, t, v2, '2026-12-02T05:00:00Z', '2026-12-02T13:00:00Z', 'STARTED')
            cancelled = legacy(c, t, v1, '2026-12-03T05:00:00Z', '2026-12-03T13:00:00Z', 'CANCELLED')
            nextweek = legacy(c, t, v1, '2026-12-08T05:00:00Z', '2026-12-08T13:00:00Z')
            rel = release(c, t, v1, planned)
        before = None
        with admin() as c:
            before = c.execute("SELECT id, vehicle_id, shift_id, safety_revision, valid_until, revoked_at FROM taktovia.resource_release WHERE tenant_id=%s", (t,)).fetchall()
        self.assertEqual(adopt(t), 4)
        with tx(t) as c:
            c.execute("SELECT set_config('app.roster_draft_access','on',true)")
            rows = c.execute("""SELECT s.id, s.state, s.roster_revision, s.roster_id, s.depot_id, s.lineage_id FROM taktovia.staff_shift s WHERE s.id = ANY(%s)""", ([planned, started, cancelled, nextweek],)).fetchall()
            by = {r[0]: r for r in rows}
            self.assertEqual(by[planned][1], 'PUBLISHED', 'PLANNED wird verbindlich'); self.assertEqual(by[started][1], 'STARTED'); self.assertEqual(by[cancelled][1], 'CANCELLED')
            self.assertTrue(all(r[2] == 1 and r[3] is not None and r[4] is not None and r[5] == r[0] for r in rows))
            self.assertEqual(by[planned][3], by[started][3], 'Dienstag und Donnerstag derselben Woche: ein Dienstplan'); self.assertNotEqual(by[planned][3], by[nextweek][3], 'andere Woche: anderer Dienstplan')
            revs = c.execute("SELECT roster_id, revision, state, published_by, week_start, depot_id FROM taktovia.roster_revision WHERE tenant_id=%s ORDER BY week_start", (t,)).fetchall()
            self.assertEqual([(str(r[4]), r[1], r[2], r[3]) for r in revs], [('2026-11-30', 1, 'PUBLISHED', 'system:migration-wp-m4-65'), ('2026-12-07', 1, 'PUBLISHED', 'system:migration-wp-m4-65')])
            self.assertEqual({r[5] for r in revs}, {by[planned][4]}, 'ein Übernahmedepot je Mandant')
            # jede Schicht genau einer veröffentlichten Revision (Fremdschlüssel auf roster_id/revision, nie mehrere)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.staff_shift s JOIN taktovia.roster_revision r ON (r.tenant_id,r.roster_id,r.revision) = (s.tenant_id,s.roster_id,s.roster_revision) WHERE r.state='PUBLISHED' AND s.tenant_id=%s", (t,)).fetchone()[0], 4)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.roster_shift_change WHERE tenant_id=%s AND change_kind='PUBLISHED' AND actor='system:migration-wp-m4-65'", (t,)).fetchone()[0], 4, 'Änderungsprotokoll je Schicht')
            after = c.execute("SELECT id, vehicle_id, shift_id, safety_revision, valid_until, revoked_at FROM taktovia.resource_release WHERE tenant_id=%s", (t,)).fetchall()
        self.assertEqual(after, before, 'keine Freigabe verliert ihren Schichtbezug oder ihre Gültigkeit'); self.assertEqual(after[0][0], rel)

    def test_woche_folgt_dem_lokalen_beginn_nicht_utc(self):
        t = uid(); seed_tenant(t, f'm465-{t}')
        with tx(t) as c:
            v = vehicle(c, t)
            sunday_night = legacy(c, t, v, '2026-12-06T22:30:00Z', '2026-12-07T06:30:00Z')   # Berlin: Montag 07.12. 23:30 bis 07:30 -> Beginn Sonntag 23:30 Ortszeit, Woche ab 30.11.
        self.assertEqual(adopt(t), 1)
        with admin() as c:
            week = c.execute("SELECT r.week_start FROM taktovia.staff_shift s JOIN taktovia.roster_revision r ON (r.tenant_id,r.roster_id,r.revision)=(s.tenant_id,s.roster_id,s.roster_revision) WHERE s.id=%s", (sunday_night,)).fetchone()[0]
        self.assertEqual(str(week), '2026-11-30')

    def test_uebernahme_ist_wiederholbar_und_aendert_nichts_zweimal(self):
        t = uid(); seed_tenant(t, f'm465-{t}')
        with tx(t) as c:
            v = vehicle(c, t); legacy(c, t, v, '2026-12-01T05:00:00Z', '2026-12-01T13:00:00Z')
        self.assertEqual(adopt(t), 1); self.assertEqual(adopt(t), 0)
        with admin() as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.roster_revision WHERE tenant_id=%s", (t,)).fetchone()[0], 1)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.depot WHERE tenant_id=%s", (t,)).fetchone()[0], 1)

    def test_doppelschicht_bleibt_bestehen_und_neue_ueberlappung_ist_weiter_ausgeschlossen(self):
        t = uid(); seed_tenant(t, f'm465-{t}')
        with tx(t) as c:
            v = vehicle(c, t)
            first = legacy(c, t, v, '2026-12-01T05:00:00Z', '2026-12-01T13:00:00Z')
            second = legacy(c, t, v, '2026-12-01T12:00:00Z', '2026-12-01T20:00:00Z', exception=True)   # dokumentierte Doppelschicht (PA-03)
        self.assertEqual(adopt(t), 2)
        with admin() as c:
            self.assertEqual({r[0] for r in c.execute("SELECT state FROM taktovia.staff_shift WHERE id = ANY(%s)", ([first, second],)).fetchall()}, {'PUBLISHED'})
        # Zusage aus WP-M3-07 gilt weiter: eine weitere überlappende verbindliche Schicht desselben Fahrzeugs wird abgewiesen
        with self.assertRaises(psycopg.errors.ExclusionViolation):
            with tx(t) as c:
                s = uid()
                c.execute("""INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state)
                             VALUES (%s,%s,%s,%s,'2026-12-01T06:00:00Z','2026-12-01T07:00:00Z','Europe/Berlin',0,'PUBLISHED')""", (t, s, uid(), v))
        # und die Wächter sind nach der Übernahme wieder aktiv
        with admin() as c:
            self.assertEqual(c.execute("SELECT count(*) FROM pg_trigger WHERE tgrelid = 'taktovia.staff_shift'::regclass AND tgname IN ('staff_shift_overlap_guard','staff_shift_state_guard') AND tgenabled = 'O'").fetchone()[0], 2)

    def test_mandanten_sind_getrennt(self):
        t = uid(); seed_tenant(t, f'm465-{t}')
        with tx(t) as c:
            v = vehicle(c, t); s = legacy(c, t, v, '2026-12-01T05:00:00Z', '2026-12-01T13:00:00Z')
        adopt(t)
        with tx(B) as c:
            c.execute("SELECT set_config('app.roster_draft_access','on',true)")
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.roster_revision WHERE tenant_id=%s", (t,)).fetchone()[0], 0)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.depot WHERE tenant_id=%s", (t,)).fetchone()[0], 0)
        with tx(t) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.staff_shift WHERE id=%s AND state='PUBLISHED'", (s,)).fetchone()[0], 1)

    def test_migrationsweg_als_eigentuemer_hebt_force_nur_kurz_auf(self):
        # Der Weg der Migration V40650: Eigentümer (nicht Superuser) hebt FORCE ROW LEVEL SECURITY für die Dauer der Übernahme auf und stellt es wieder her.
        t = uid(); seed_tenant(t, f'm465-{t}')
        with tx(t) as c:
            v = vehicle(c, t); s = legacy(c, t, v, '2026-12-01T05:00:00Z', '2026-12-01T13:00:00Z')
        tables = ('tenant', 'region', 'depot', 'roster_revision', 'roster_shift_change', 'staff_shift')
        m = migrator()
        try:
            m.execute('BEGIN')
            for tb in tables: m.execute(f'ALTER TABLE taktovia.{tb} NO FORCE ROW LEVEL SECURITY')
            n = m.execute('SELECT taktovia.adopt_legacy_staff_shifts(%s)', (t,)).fetchone()[0]
            for tb in tables: m.execute(f'ALTER TABLE taktovia.{tb} FORCE ROW LEVEL SECURITY')
            m.execute('COMMIT')
        finally:
            m.close()
        self.assertEqual(n, 1)
        with admin() as c:
            self.assertEqual(c.execute("SELECT count(*) FROM pg_class WHERE oid = ANY(%s::regclass[]) AND relforcerowsecurity", ([f'taktovia.{x}' for x in tables],)).fetchone()[0], len(tables))
            self.assertEqual(c.execute("SELECT state, roster_revision FROM taktovia.staff_shift WHERE id=%s", (s,)).fetchone(), ('PUBLISHED', 1))

    def test_runtime_kann_die_uebernahme_nicht_aufrufen(self):
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(A) as c:
                c.execute("SELECT taktovia.adopt_legacy_staff_shifts(%s)", (A,))

    def test_uebernahme_parallel_zu_laufendem_hold_laesst_freigaben_gueltig(self):
        # Ein Hold liest Schicht und Freigabe unter Sperre (FOR SHARE); die Übernahme wartet auf dessen Ende und ändert Freigaben nie.
        t = uid(); seed_tenant(t, f'm465-{t}')
        with tx(t) as c:
            v = vehicle(c, t); s = legacy(c, t, v, '2026-12-01T05:00:00Z', '2026-12-01T13:00:00Z'); rel = release(c, t, v, s)
        locked = threading.Event(); proceed = threading.Event(); out = {}

        def hold():
            with tx(t) as c:
                c.execute("SELECT id FROM taktovia.staff_shift WHERE id=%s FOR SHARE", (s,))
                out['release_before'] = c.execute("SELECT valid_until, revoked_at, shift_id FROM taktovia.resource_release WHERE id=%s", (rel,)).fetchone()
                locked.set(); proceed.wait(20)
                out['release_in_hold'] = c.execute("SELECT valid_until, revoked_at, shift_id FROM taktovia.resource_release WHERE id=%s", (rel,)).fetchone()

        def migrate():
            locked.wait(20); out['adopted'] = adopt(t); out['adopted_at'] = time.monotonic()

        th = [threading.Thread(target=hold), threading.Thread(target=migrate)]
        for x in th: x.start()
        locked.wait(20); time.sleep(0.6)
        self.assertNotIn('adopted', out, 'die Übernahme wartet auf die Sperre des laufenden Holds')
        out['hold_end'] = time.monotonic(); proceed.set()
        for x in th: x.join(30)
        self.assertEqual(out['adopted'], 1); self.assertGreaterEqual(out['adopted_at'], out['hold_end'])
        self.assertEqual(out['release_before'], out['release_in_hold'])
        with tx(t) as c:
            self.assertEqual(c.execute("SELECT shift_id, revoked_at FROM taktovia.resource_release WHERE id=%s", (rel,)).fetchone(), (s, None))
            self.assertEqual(c.execute("SELECT state FROM taktovia.staff_shift WHERE id=%s", (s,)).fetchone()[0], 'PUBLISHED')


if __name__ == '__main__':
    unittest.main()
