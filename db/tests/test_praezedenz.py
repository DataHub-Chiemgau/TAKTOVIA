"""WP-M1-21 / LH-I.7-CFG-005: Fahrzeug als Scope von Konfigurationsrevisionen und die Freigabeliste der Fahrzeug-Overrides unter der echten Runtime-Rolle:
nur freigegebene Parameter sind auf Fahrzeugebene zulässig, das Fahrzeug muss zum Mandanten gehören, die Freigabeliste ist unveränderlich."""
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()
VA, VB = uid(), uid()


def revision(c, tenant, kind, scope, values, rev=1):
    r = uid()
    c.execute("INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,created_by,reason) "
              "VALUES (%s,%s,%s,%s,%s,'1',%s::jsonb,'2026-01-01','user:test','Test')", (tenant, r, kind, scope, rev, values))
    return r


class Praezedenz(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm121-a'); seed_tenant(B, 'm121-b')
        for t, v in ((A, VA), (B, VB)):
            with tx(t) as c:
                c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'x','{}')", (t, v))

    def test_freigegebener_parameter_auf_fahrzeugebene_zulaessig(self):
        with tx(A) as c:
            r = revision(c, A, 'VEHICLE', VA, '{"wheelchair_boarding_sec": 90}')
            self.assertEqual(c.execute("SELECT scope_kind FROM taktovia.config_revision WHERE id=%s", (r,)).fetchone()[0], 'VEHICLE')

    def test_nicht_freigegebener_parameter_auf_fahrzeugebene_abgewiesen(self):
        for values in ('{"max_wait_min": 20}', '{"pooling_allowed": true}', '{"wheelchair_boarding_sec": 90, "max_detour_pct": 10}', '{"unbekannt": 1}'):
            with self.assertRaises(psycopg.errors.CheckViolation, msg=values):
                with tx(A) as c: revision(c, A, 'VEHICLE', VA, values)

    def test_wertebereich_gilt_auch_auf_fahrzeugebene(self):
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: revision(c, A, 'VEHICLE', VA, '{"wheelchair_boarding_sec": 99999}')

    def test_fahrzeug_muss_zum_mandanten_gehoeren(self):
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):   # unbekannt
            with tx(A) as c: revision(c, A, 'VEHICLE', uid(), '{"wheelchair_boarding_sec": 90}')
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):   # Fahrzeug des Mandanten B
            with tx(A) as c: revision(c, A, 'VEHICLE', VB, '{"wheelchair_boarding_sec": 90}')

    def test_andere_scopes_unveraendert(self):
        with tx(A) as c:
            revision(c, A, 'TENANT', A, '{"max_wait_min": 20}')
            revision(c, A, 'REGION', uid(), '{"max_wait_min": 25}')
        with self.assertRaises(psycopg.errors.CheckViolation):   # wheelchair_boarding_sec ist weiterhin nur Mandant und Dienst
            with tx(A) as c: revision(c, A, 'REGION', uid(), '{"wheelchair_boarding_sec": 90}')

    def test_freigabeliste_ist_lesbar_aber_nicht_aenderbar(self):
        with tx(A) as c:
            self.assertEqual([r[0] for r in c.execute("SELECT parameter FROM taktovia.config_vehicle_override_release")], ['wheelchair_boarding_sec'])
        for sql in ("INSERT INTO taktovia.config_vehicle_override_release VALUES ('max_wait_min','Test')", "DELETE FROM taktovia.config_vehicle_override_release"):
            with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=sql):
                with tx(A) as c: c.execute(sql)
        with self.assertRaises(psycopg.errors.CheckViolation):   # auch für die Administration unveränderlich
            with admin() as c: c.execute("UPDATE taktovia.config_vehicle_override_release SET description = 'x y z'")


if __name__ == '__main__':
    unittest.main()
