"""WP-M3-25 / LH-6.6-DRV-001: Dienstantritt an der Schichtzuweisung unter der echten Runtime-Rolle. Zustandswächter (`staff_shift_state_guard`): zulässige Übergänge
PLANNED -> STARTED -> ENDED und Entzug, ENDED endgültig, Antritt nur mit Zeit und Gerät, Gerät nur des eigenen Mandanten; Zustände des Dienstplans (PUBLISHED) bleiben frei."""
import unittest
import psycopg
from helpers import tx, uid, seed_tenant

A, B = uid(), uid()
ISS = 'https://idp.test/realms/m325'


def fixture(tenant, state='PLANNED'):
    veh, shift, dev = uid(), uid(), uid()
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'x','{}')", (tenant, veh))
        c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) "
                  "VALUES (%s,%s,%s,%s,now(),now()+interval '8 hours','Europe/Berlin',0,%s)", (tenant, shift, uid(), veh, state))
        c.execute("INSERT INTO taktovia.driver_device(tenant_id,id,issuer,subject,idp_session_id,platform,app_version) VALUES (%s,%s,%s,'driver-1','sid-12345678','ANDROID','1.0.0')", (tenant, dev, ISS))
    return veh, shift, dev


class Dienstantritt(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm325-a'); seed_tenant(B, 'm325-b')

    def test_antritt_und_ende_mit_zeit_und_geraet(self):
        _, s, d = fixture(A)
        with tx(A) as c:
            c.execute("UPDATE taktovia.staff_shift SET state='STARTED', started_at=now(), started_device_id=%s WHERE id=%s", (d, s))
            c.execute("UPDATE taktovia.staff_shift SET state='ENDED', ended_at=now() WHERE id=%s", (s,))
            self.assertEqual(c.execute('SELECT state, revision FROM taktovia.staff_shift WHERE id=%s', (s,)).fetchone(), ('ENDED', 3))

    def test_antritt_ohne_zeit_oder_geraet_scheitert(self):
        _, s, d = fixture(A)
        for sql, args in (("UPDATE taktovia.staff_shift SET state='STARTED' WHERE id=%s", (s,)),
                          ("UPDATE taktovia.staff_shift SET state='STARTED', started_at=now() WHERE id=%s", (s,)),
                          ("UPDATE taktovia.staff_shift SET state='STARTED', started_device_id=%s WHERE id=%s", (d, s))):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation): c.execute(sql, args)
        with tx(A) as c:
            c.execute("UPDATE taktovia.staff_shift SET state='STARTED', started_at=now(), started_device_id=%s WHERE id=%s", (d, s))
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.staff_shift SET state='ENDED' WHERE id=%s", (s,))

    def test_unzulaessige_uebergaenge(self):
        _, s, d = fixture(A)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation) as e: c.execute("UPDATE taktovia.staff_shift SET state='ENDED', started_at=now(), started_device_id=%s, ended_at=now() WHERE id=%s", (d, s))   # PLANNED -> ENDED
        self.assertEqual(e.exception.diag.constraint_name, 'staff_shift_state_transition')
        _, s2, d2 = fixture(A)
        with tx(A) as c:
            c.execute("UPDATE taktovia.staff_shift SET state='STARTED', started_at=now(), started_device_id=%s WHERE id=%s", (d2, s2))
            c.execute("UPDATE taktovia.staff_shift SET state='ENDED', ended_at=now() WHERE id=%s", (s2,))
        for target in ('PLANNED', 'STARTED', 'CANCELLED'):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.staff_shift SET state=%s WHERE id=%s", (target, s2))    # ENDED ist endgültig

    def test_entzug_aus_geplant_und_angetreten_und_ruecknahme(self):
        _, s, d = fixture(A)
        with tx(A) as c:
            c.execute("UPDATE taktovia.staff_shift SET state='CANCELLED' WHERE id=%s", (s,))
            c.execute("UPDATE taktovia.staff_shift SET state='PLANNED' WHERE id=%s", (s,))
            c.execute("UPDATE taktovia.staff_shift SET state='STARTED', started_at=now(), started_device_id=%s WHERE id=%s", (d, s))
            c.execute("UPDATE taktovia.staff_shift SET state='CANCELLED' WHERE id=%s", (s,))

    def test_dienstplanzustand_bleibt_frei(self):
        _, s, _ = fixture(A, state='PUBLISHED')
        with tx(A) as c:
            c.execute("UPDATE taktovia.staff_shift SET state='CANCELLED' WHERE id=%s", (s,))     # PUBLISHED gehört dem Dienstplan (M4): der Wächter greift nicht

    def test_geraet_eines_fremden_mandanten_ist_nicht_referenzierbar_und_ende_nicht_vor_beginn(self):
        _, s, _ = fixture(A); _, _, db = fixture(B)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.ForeignKeyViolation):
                c.execute("UPDATE taktovia.staff_shift SET state='STARTED', started_at=now(), started_device_id=%s WHERE id=%s", (db, s))
        _, s2, d2 = fixture(A)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.staff_shift SET state='STARTED', started_at=now(), started_device_id=%s, ended_at=now()-interval '1 hour' WHERE id=%s", (d2, s2))


if __name__ == '__main__':
    unittest.main()
