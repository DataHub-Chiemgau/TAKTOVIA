"""WP-M2-19 / LH-6.5-OPT-006: `capacity_hold.promise_window` unter der echten Runtime-Rolle: Form und Katalogbereiche (Datenbanksicherung), nach dem Anlegen
unveränderlich (eine spätere Parameteränderung berührt kein gespeichertes Zusagefenster), Holds ohne Fenster bleiben gültig."""
import json
import unittest

import psycopg
from helpers import tx, uid, seed_tenant
from test_route_stop import make_run

A = uid()
VEC = json.dumps({'schema_version': '1.0', 'seats': 1, 'wheelchair': 0, 'equipment': []})


def window(**over):
    w = {'booking_id': str(uid()), 'pickup_earliest': '2026-12-01T08:00:00Z', 'pickup_latest': '2026-12-01T08:15:00Z', 'arrival_earliest': '2026-12-01T08:30:00Z',
         'arrival_latest': '2026-12-01T08:50:00Z', 'wait_reference': '2026-12-01T08:00:00Z', 'max_wait_min': 20, 'max_detour_pct': 50, 'arrival_grace_min': 5,
         'config_revision_id': 'rev-1', 'direct_sec': 1200, 'pickup_dwell_sec': 60}
    w.update(over)
    return json.dumps(w)


def hold(c, run, promise):
    hid = uid()
    c.execute("INSERT INTO taktovia.capacity_hold(tenant_id,id,run_id,run_revision,federation_ref,capacity_vector,interval_from,interval_to,expires_at,state,promise_window) "
              "VALUES (%s,%s,%s,1,%s,%s::jsonb,0,1,clock_timestamp()+interval '3 minutes','HELD',%s::jsonb)", (A, hid, run, f"ref-{hid}", VEC, promise))
    return hid


class HoldPromiseWindow(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm219-a')

    def test_fenster_speichern_und_lesen(self):
        with tx(A) as c:
            run, _, _ = make_run(c, A)
            h = hold(c, run, window())
            self.assertEqual(c.execute("SELECT (promise_window->>'max_wait_min')::int FROM taktovia.capacity_hold WHERE id=%s", (h,)).fetchone()[0], 20)

    def test_hold_ohne_fenster_bleibt_gueltig(self):
        with tx(A) as c:
            run, _, _ = make_run(c, A)
            h = hold(c, run, None)
            self.assertIsNone(c.execute("SELECT promise_window FROM taktovia.capacity_hold WHERE id=%s", (h,)).fetchone()[0])

    def test_fenster_nach_anlage_unveraenderlich_zustand_aenderbar(self):
        with tx(A) as c:
            run, _, _ = make_run(c, A)
            h = hold(c, run, window())
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c:
                c.execute("UPDATE taktovia.capacity_hold SET promise_window = %s::jsonb WHERE id=%s", (window(max_wait_min=30), h))
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c:
                c.execute("UPDATE taktovia.capacity_hold SET promise_window = NULL WHERE id=%s", (h,))
        with tx(A) as c:  # der Zustand bleibt änderbar (Hold wird bestätigt oder läuft ab)
            c.execute("UPDATE taktovia.capacity_hold SET state='CONSUMED' WHERE id=%s", (h,))

    def test_form_und_katalogbereiche(self):
        for bad in (window(max_wait_min=241), window(max_detour_pct=201), window(arrival_grace_min=241), window(direct_sec=0)):
            with self.assertRaises(psycopg.errors.CheckViolation):
                with tx(A) as c:
                    run, _, _ = make_run(c, A)
                    hold(c, run, bad)
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c:
                run, _, _ = make_run(c, A)
                hold(c, run, json.dumps({'booking_id': str(uid())}))


if __name__ == '__main__':
    unittest.main()
