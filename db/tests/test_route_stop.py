"""WP-M2-16 / LH-6.5-OPT-004: Fahrtenplanmodell unter der echten Runtime-Rolle: Stoppsequenz je Run-Revision eindeutig, frühere Revisionen unveränderlich lesbar,
ein Umlauf je Schicht, Revision monoton, Zustandsautomat gehärtet, Mandantentrennung (fremde Runs weder lesbar noch referenzierbar)."""
import json
import threading
import unittest

import psycopg
from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()
VEC = {'schema_version': '1.0', 'seats': 1, 'wheelchair': 0, 'equipment': []}


def make_run(c, tenant, state='ACTIVE'):
    """Fahrzeug, Schicht und Umlauf (Revision 1) im Mandanten; liefert (run, vehicle, shift)."""
    veh, shift, run = uid(), uid(), uid()
    c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'x',%s::jsonb)", (tenant, veh, json.dumps({'seats': 4})))
    c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) "
              "VALUES (%s,%s,%s,%s,'2026-12-01 04:00+00','2026-12-01 14:00+00','Europe/Berlin',1,'PUBLISHED')", (tenant, shift, uid(), veh))
    c.execute("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (%s,%s,%s,%s,1,%s)", (tenant, run, veh, shift, state))
    return run, veh, shift


def make_stop(c, tenant):
    rev = uid()
    c.execute("INSERT INTO taktovia.stop_revision(tenant_id,id,stop_id,revision,point,name,accessibility,approved_by,valid_from) "
              "VALUES (%s,%s,%s,1,ST_SetSRID(ST_MakePoint(13.7,51.0),4326),'Halt','{}'::jsonb,'test','2026-01-01T00:00:00Z')", (tenant, rev, uid()))
    return rev


def add_stop(c, tenant, run, revision, seq, stop_rev, boardings=(), alightings=(), leg=(60, 500)):
    sid = uid()
    c.execute("INSERT INTO taktovia.route_stop(tenant_id,id,run_id,run_revision,seq,stop_revision_id,planned_at,boardings,alightings,leg_duration_sec,leg_distance_m) "
              "VALUES (%s,%s,%s,%s,%s,%s,'2026-12-01 06:00+00',%s::jsonb,%s::jsonb,%s,%s)",
              (tenant, sid, run, revision, seq, stop_rev, json.dumps(list(boardings)), json.dumps(list(alightings)), leg[0] if leg else None, leg[1] if leg else None))
    return sid


def bump(c, tenant, run, to):
    c.execute("UPDATE taktovia.vehicle_run SET revision=%s WHERE tenant_id=%s AND id=%s", (to, tenant, run))


class RouteStop(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm216-a'); seed_tenant(B, 'm216-b')

    def test_sequenz_eindeutig_je_runrevision(self):
        with tx(A) as c:
            run, _, _ = make_run(c, A); st = make_stop(c, A)
            bump(c, A, run, 2)
            add_stop(c, A, run, 2, 0, st); add_stop(c, A, run, 2, 1, st, leg=None)
        with self.assertRaises(psycopg.errors.UniqueViolation):
            with tx(A) as c: add_stop(c, A, run, 2, 1, st, leg=None)
        with tx(A) as c:  # dieselbe Sequenznummer in einer anderen Revision ist zulässig, die frühere Revision bleibt lesbar
            bump(c, A, run, 3); add_stop(c, A, run, 3, 0, st)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.route_stop WHERE run_id=%s AND run_revision=2", (run,)).fetchone()[0], 2)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.route_stop WHERE run_id=%s AND run_revision=3", (run,)).fetchone()[0], 1)

    def test_fremder_mandant_run_nicht_referenzierbar(self):
        with tx(A) as c: run_a, _, _ = make_run(c, A)
        with tx(B) as c: st_b = make_stop(c, B)
        # Stopp in Mandant B auf einen Run von A: Composite-FK (tenant_id, run_id) findet keinen Run in B
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):
            with tx(B) as c: add_stop(c, B, run_a, 1, 0, st_b)
        # Stopp mit tenant_id A aus Kontext B: RLS WITH CHECK
        with tx(A) as c: st_a = make_stop(c, A)
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(B) as c: add_stop(c, A, run_a, 1, 0, st_a)
        # Haltepunktrevision eines fremden Mandanten ist ebenfalls nicht referenzierbar
        with tx(B) as c: run_b, _, _ = make_run(c, B)
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):
            with tx(B) as c: add_stop(c, B, run_b, 1, 0, st_a)
        # und ein Capacity-Hold von B auf einen Run von A
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):
            with tx(B) as c:
                c.execute("INSERT INTO taktovia.capacity_hold(tenant_id,id,run_id,run_revision,federation_ref,capacity_vector,interval_from,interval_to,expires_at,state) "
                          "VALUES (%s,%s,%s,1,%s,'{}'::jsonb,0,1,now() + interval '1 minute','HELD')", (B, uid(), run_a, f'fr-{uid()}'))

    def test_fremder_mandant_run_nicht_lesbar(self):
        with tx(A) as c:
            run, _, _ = make_run(c, A); st = make_stop(c, A); bump(c, A, run, 2); add_stop(c, A, run, 2, 0, st)
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.vehicle_run WHERE id=%s", (run,)).fetchone()[0], 0)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.route_stop WHERE run_id=%s", (run,)).fetchone()[0], 0)
            self.assertEqual(c.execute("UPDATE taktovia.vehicle_run SET state='LOCKED' WHERE id=%s", (run,)).rowcount, 0)
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.route_stop WHERE run_id=%s", (run,)).fetchone()[0], 1)

    def test_planrevisionen_sind_unveraenderlich(self):
        with tx(A) as c:
            run, _, _ = make_run(c, A); st = make_stop(c, A); bump(c, A, run, 2); sid = add_stop(c, A, run, 2, 0, st)
        for sql in ("UPDATE taktovia.route_stop SET seq=5 WHERE id=%s", "DELETE FROM taktovia.route_stop WHERE id=%s"):
            with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=sql):
                with tx(A) as c: c.execute(sql, (sid,))
            with admin() as a:  # auch der Administrator wird vom Trigger abgewiesen
                with self.assertRaises(psycopg.errors.CheckViolation, msg=sql): a.execute(sql, (sid,))

    def test_stopp_nicht_vor_erreichter_revision(self):
        with tx(A) as c: run, _, _ = make_run(c, A); st = make_stop(c, A)
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: add_stop(c, A, run, 2, 0, st)   # Umlauf steht auf Revision 1

    def test_ein_run_je_schicht_auch_parallel(self):
        with tx(A) as c: _, veh, shift = make_run(c, A)
        with self.assertRaises(psycopg.errors.UniqueViolation):
            with tx(A) as c: c.execute("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (%s,%s,%s,%s,1,'PLANNED')", (A, uid(), veh, shift))
        with tx(A) as c:
            veh2, shift2 = uid(), uid()
            c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'x','{}')", (A, veh2))
            c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) "
                      "VALUES (%s,%s,%s,%s,'2026-12-01 04:00+00','2026-12-01 14:00+00','Europe/Berlin',1,'PUBLISHED')", (A, shift2, uid(), veh2))
        results = []
        def create():
            try:
                with tx(A) as c:
                    c.execute("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (%s,%s,%s,%s,1,'PLANNED') ON CONFLICT (tenant_id, shift_id) DO NOTHING", (A, uid(), veh2, shift2))
                results.append('ok')
            except Exception as e:  # pragma: no cover
                results.append(repr(e))
        ts = [threading.Thread(target=create) for _ in range(6)]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual(results, ['ok'] * 6)
        with tx(A) as c: self.assertEqual(c.execute("SELECT count(*) FROM taktovia.vehicle_run WHERE shift_id=%s", (shift2,)).fetchone()[0], 1)

    def test_revision_steigt_nur_um_eins(self):
        with tx(A) as c: run, _, _ = make_run(c, A)
        for bad in (3, 0):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=str(bad)):
                with tx(A) as c: bump(c, A, run, bad)
        with tx(A) as c: bump(c, A, run, 2)
        with self.assertRaises(psycopg.errors.CheckViolation):  # kein Rücksprung
            with tx(A) as c: bump(c, A, run, 1)

    def test_zustand_nur_ueber_erlaubte_uebergaenge(self):
        with tx(A) as c: run, _, _ = make_run(c, A, state='PLANNED')
        with self.assertRaises(psycopg.errors.CheckViolation):  # PLANNED -> COMPLETED
            with tx(A) as c: c.execute("UPDATE taktovia.vehicle_run SET state='COMPLETED' WHERE id=%s", (run,))
        with self.assertRaises(psycopg.errors.CheckViolation):  # unbekannter Wert
            with tx(A) as c: c.execute("UPDATE taktovia.vehicle_run SET state='GIBTS_NICHT' WHERE id=%s", (run,))
        with tx(A) as c:
            for s in ('ACTIVE', 'LOCKED', 'ACTIVE', 'IN_PROGRESS', 'COMPLETED'):
                c.execute("UPDATE taktovia.vehicle_run SET state=%s WHERE id=%s", (s, run))
        with self.assertRaises(psycopg.errors.CheckViolation):  # Endzustand
            with tx(A) as c: c.execute("UPDATE taktovia.vehicle_run SET state='ACTIVE' WHERE id=%s", (run,))


if __name__ == '__main__':
    unittest.main()
