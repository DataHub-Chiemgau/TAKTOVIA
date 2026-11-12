"""WP-M2-22 / LH-6.5-OPT-009: `capacity_hold` unter der echten Runtime-Rolle: Zustandsautomat `HELD -> CONSUMED | RELEASED | EXPIRED` auch gegen direktes SQL (Endzustände endgültig),
Kapazität, Revision, Abschnitte und Frist nach dem Anlegen unveränderlich (keine Verlängerung in P1), je Referenz höchstens ein Hold, Mandantentrennung."""
import json
import unittest

import psycopg
from helpers import tx, uid, seed_tenant, admin
from test_route_stop import make_run

A, B = uid(), uid()
VEC = json.dumps({'schema_version': '1.0', 'seats': 1, 'wheelchair': 0, 'equipment': []})


def hold(c, tenant, run, ref=None):
    hid = uid()
    c.execute("INSERT INTO taktovia.capacity_hold(tenant_id,id,run_id,run_revision,federation_ref,capacity_vector,interval_from,interval_to,expires_at,state) "
              "VALUES (%s,%s,%s,1,%s,%s::jsonb,0,2,clock_timestamp()+interval '3 minutes','HELD')", (tenant, hid, run, ref or f"ref-{hid}", VEC))
    return hid


class HoldState(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm222-a'); seed_tenant(B, 'm222-b')

    def test_zustandsautomat_und_unveraenderliche_kapazitaet(self):
        with tx(A) as c:
            run, _, _ = make_run(c, A)
            h = hold(c, A, run)
        # Kapazität, Revision, Abschnitte, Frist und Referenz sind unveränderlich, auch für die Administration
        for sql in ("expires_at = expires_at + interval '10 minutes'", "interval_to = 3", "run_revision = 2", "capacity_vector = '{\"seats\": 0}'::jsonb", "federation_ref = 'anders'"):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=sql):
                with admin() as a: a.execute(f"UPDATE taktovia.capacity_hold SET {sql} WHERE id=%s", (h,))
        # HELD -> je ein Endzustand; danach kein Weg zurück und kein Wechsel zwischen Endzuständen
        for final in ('CONSUMED', 'RELEASED', 'EXPIRED'):
            with tx(A) as c:
                hh = hold(c, A, run)
                c.execute("UPDATE taktovia.capacity_hold SET state=%s WHERE id=%s", (final, hh))
            for target in ('HELD', 'CONSUMED', 'RELEASED', 'EXPIRED'):
                if target == final: continue
                with self.assertRaises(psycopg.errors.CheckViolation, msg=f"{final} -> {target}"):
                    with tx(A) as c: c.execute("UPDATE taktovia.capacity_hold SET state=%s WHERE id=%s", (target, hh))
        with self.assertRaises(psycopg.errors.CheckViolation):                                         # unbekannter Zustand
            with tx(A) as c: c.execute("UPDATE taktovia.capacity_hold SET state='DONE' WHERE id=%s", (h,))

    def test_je_referenz_hoechstens_ein_hold_und_mandantentrennung(self):
        with tx(A) as c:
            run, _, _ = make_run(c, A)
            hold(c, A, run, 'offer:eins')
        with self.assertRaises(psycopg.errors.UniqueViolation):
            with tx(A) as c: hold(c, A, run, 'offer:eins')
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.capacity_hold WHERE federation_ref='offer:eins'").fetchone()[0], 0)


if __name__ == '__main__':
    unittest.main()
