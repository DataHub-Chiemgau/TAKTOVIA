"""WP-M3-08: Sperr- und Freigabekern in der Datenbank unter der Runtime-Rolle (Wächter, Unique-Indizes), auch bei direktem SQL.
Sperrzeilen werden nur geschlossen (ACTIVE -> LIFTED), dieselbe Sperre ist höchstens einmal aktiv, eine Einsatzfreigabe wird nur widerrufen (nie wiederhergestellt, nie gelöscht, nie
geändert), je Prüflauf gibt es höchstens eine, und die safety_revision des Fahrzeugs steigt nur."""
import unittest

import psycopg

from helpers import admin, tx, uid, seed_tenant

A = uid()
B = uid()
ACTOR = 'user:11111111-1111-4111-8111-111111111111'


def vehicle(c, tenant=A):
    vid = uid()
    c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'o','{}'::jsonb)", (tenant, vid))
    return vid


def block(c, vid, ref=None, source='SAFETY', reason='DEFECT_CRITICAL'):
    bid = uid()
    c.execute("INSERT INTO taktovia.vehicle_block(tenant_id,id,vehicle_id,source,reason_code,reason_ref,set_by) VALUES (%s,%s,%s,%s,%s,%s,%s)", (A, bid, vid, source, reason, ref, ACTOR))
    return bid


def release(c, vid, inspection=None, shift=None):
    rid = uid()
    shift = shift or uid()
    c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (%s,%s,%s,%s,now(),now()+interval '4 hours','Europe/Berlin',0,'PUBLISHED') ON CONFLICT DO NOTHING", (A, shift, uid(), vid))
    c.execute("INSERT INTO taktovia.resource_release(tenant_id,id,vehicle_id,shift_id,safety_revision,inspection_ref,qualification_revision,valid_until,issued_by) VALUES (%s,%s,%s,%s,1,%s,1,now()+interval '4 hours',%s)",
              (A, rid, vid, shift, inspection or uid(), ACTOR))
    return rid


class SperrFreigabeKern(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm308-a')
        seed_tenant(B, 'm308-b')

    def test_dieselbe_sperre_nur_einmal_aktiv(self):
        with tx(A) as c:
            v = vehicle(c); block(c, v, 'defect-1')
            block(c, v, 'defect-2')   # anderer Bezug: eigener Grund
            block(c, v, None, 'OPERATIONS', 'MANUAL')
            with self.assertRaises(psycopg.errors.UniqueViolation):
                with c.transaction():
                    block(c, v, 'defect-1')
            with self.assertRaises(psycopg.errors.UniqueViolation):
                with c.transaction():
                    block(c, v, None, 'OPERATIONS', 'MANUAL')   # ohne Bezug zählt als gleicher Bezug

    def test_sperre_wird_nur_geschlossen_nie_geoeffnet_nie_geloescht(self):
        with tx(A) as c:
            v = vehicle(c); b = block(c, v, 'x')
            c.execute("UPDATE taktovia.vehicle_block SET status='LIFTED', lifted_by=%s, lifted_at=now() WHERE id=%s", (ACTOR, b))
            block(c, v, 'x')   # nach dem Aufheben ist dieselbe Sperre wieder neu setzbar (neue Zeile)
            with self.assertRaises(psycopg.errors.CheckViolation):
                with c.transaction():
                    c.execute("UPDATE taktovia.vehicle_block SET status='ACTIVE', lifted_by=NULL, lifted_at=NULL WHERE id=%s", (b,))
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                with c.transaction():
                    c.execute("DELETE FROM taktovia.vehicle_block WHERE id=%s", (b,))
        with admin() as a:   # auch die Administration kann nicht löschen (Wächter)
            with self.assertRaises(psycopg.errors.CheckViolation):
                a.execute("DELETE FROM taktovia.vehicle_block WHERE id=%s", (b,))

    def test_freigabe_nur_widerrufbar_je_pruefung_eine(self):
        with tx(A) as c:
            v = vehicle(c); insp = uid(); shift = uid(); r = release(c, v, insp, shift)
            with self.assertRaises(psycopg.errors.UniqueViolation):
                with c.transaction():
                    release(c, v, insp, shift)   # zweite Freigabe zum selben Prüflauf
            with self.assertRaises(psycopg.errors.CheckViolation):
                with c.transaction():
                    c.execute("UPDATE taktovia.resource_release SET valid_until = valid_until + interval '1 day' WHERE id=%s", (r,))
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                with c.transaction():
                    c.execute("DELETE FROM taktovia.resource_release WHERE id=%s", (r,))
            self.assertEqual(c.execute("UPDATE taktovia.resource_release SET revoked_at=now(), revoked_by=%s, revoke_reason='MANUAL' WHERE id=%s", (ACTOR, r)).rowcount, 1)
            with self.assertRaises(psycopg.errors.CheckViolation):
                with c.transaction():
                    c.execute("UPDATE taktovia.resource_release SET revoked_at=NULL, revoke_reason=NULL WHERE id=%s", (r,))   # keine Wiederaufnahme
            with self.assertRaises(psycopg.errors.CheckViolation):
                with c.transaction():
                    c.execute("UPDATE taktovia.resource_release SET revoke_reason='BLOCK_SET' WHERE id=%s", (r,))
            with self.assertRaises(psycopg.errors.CheckViolation):
                with c.transaction():
                    c.execute("INSERT INTO taktovia.resource_release(tenant_id,id,vehicle_id,shift_id,safety_revision,inspection_ref,qualification_revision,valid_until,revoke_reason) "
                              "SELECT tenant_id,%s,vehicle_id,shift_id,safety_revision,%s,1,valid_until,'NOPE' FROM taktovia.resource_release WHERE id=%s", (uid(), uid(), r))

    def test_safety_revision_steigt_nur(self):
        with tx(A) as c:
            v = vehicle(c)
            c.execute("UPDATE taktovia.vehicle SET safety_revision = safety_revision + 1 WHERE id=%s", (v,))
            with self.assertRaises(psycopg.errors.CheckViolation):
                with c.transaction():
                    c.execute("UPDATE taktovia.vehicle SET safety_revision = 1 WHERE id=%s", (v,))
            self.assertEqual(c.execute("SELECT safety_revision FROM taktovia.vehicle WHERE id=%s", (v,)).fetchone()[0], 2)

    def test_fremder_mandant_sieht_keine_sperren_und_freigaben(self):
        with tx(A) as c:
            v = vehicle(c); b = block(c, v, 'iso'); r = release(c, v)
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.vehicle_block WHERE id=%s", (b,)).fetchone()[0], 0)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.resource_release WHERE id=%s", (r,)).fetchone()[0], 0)
            self.assertEqual(c.execute("UPDATE taktovia.resource_release SET revoked_at=now() WHERE id=%s", (r,)).rowcount, 0)


if __name__ == '__main__':
    unittest.main()
