"""WP-M4-05 / LH-6.23-SCH-008: offene Schichten in `staff_shift` unter der echten Runtime-Rolle. Eine offene Schicht hat weder Fahrer (`driver_ref`) noch Fahrzeug (`vehicle_id`);
ein Fahrzeug ohne Person ist unzulässig (keine verbindliche Zuordnung vortäuschen); offene Schichten blockieren weder Person noch Fahrzeug im Überlappungsverbot;
`updated_at` steigt nur mit inhaltlicher Änderung; Index `(tenant_id, state, updated_at)` für die Abfrage offener Schichten."""
import unittest
import psycopg
from helpers import admin, tx as _tx, uid, seed_tenant
from contextlib import contextmanager

A = uid()
DEPOT = uid(); REGION = uid()


@contextmanager
def tx(tenant):
    with _tx(tenant) as c:
        c.execute("SELECT set_config('app.roster_draft_access', 'on', true)")   # Entwurfszeilen nur mit Planerrecht (WP-M4-04)
        yield c


def shift(c, driver=None, vehicle=None, state='PUBLISHED', start='2026-12-01T06:00:00Z', end='2026-12-01T14:00:00Z'):
    sid = uid()
    c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state,depot_id,shift_function,created_by) "
              "VALUES (%s,%s,%s,%s,%s,%s,'Europe/Berlin',0,%s,%s,'Fahrdienst','user:planer')", (A, sid, driver, vehicle, start, end, state, DEPOT))
    return sid


class OffeneSchichten(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm405-a')
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.region(tenant_id,id,name,created_by) VALUES (%s,%s,'R','user:t')", (A, REGION))
            c.execute("INSERT INTO taktovia.depot(tenant_id,id,region_id,name,created_by) VALUES (%s,%s,%s,'D','user:t')", (A, DEPOT, REGION))
            cls.vehicle = uid()
            c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'fixture','{}')", (A, cls.vehicle))

    def test_offene_schicht_ohne_driver_ref_und_vehicle_id_zulaessig(self):  # AK 1, 2 (SCH-008)
        with tx(A) as c:
            sid = shift(c)
            self.assertEqual(c.execute('SELECT driver_ref, vehicle_id FROM taktovia.staff_shift WHERE id=%s', (sid,)).fetchone(), (None, None))
        with tx(A) as c:   # draft ebenso
            shift(c, state='DRAFT')

    def test_fahrzeug_ohne_person_ist_keine_offene_schicht(self):  # AK 2: keine verbindliche Fahrzeugzuordnung an einer offenen Schicht
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): shift(c, vehicle=self.vehicle)
        with tx(A) as c:
            sid = shift(c)
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute('UPDATE taktovia.staff_shift SET vehicle_id=%s WHERE id=%s', (self.vehicle, sid))
        with tx(A) as c:   # Person entziehen, Fahrzeug bleibt: ebenfalls abgewiesen
            sid = shift(c, driver=uid(), vehicle=self.vehicle, start='2026-12-05T06:00:00Z', end='2026-12-05T14:00:00Z')
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute('UPDATE taktovia.staff_shift SET driver_ref=NULL WHERE id=%s', (sid,))

    def test_offene_schichten_blockieren_das_ueberlappungsverbot_nicht(self):
        person = uid()
        with tx(A) as c:
            shift(c); shift(c)                                      # zwei offene, überlappende Schichten
            shift(c, driver=person, start='2026-12-02T06:00:00Z', end='2026-12-02T14:00:00Z')
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.ExclusionViolation): shift(c, driver=person, start='2026-12-02T10:00:00Z', end='2026-12-02T18:00:00Z')

    def test_besetzen_aendert_updated_at_nur_bei_inhaltlicher_aenderung(self):
        with tx(A) as c:
            sid = shift(c)
            t0 = c.execute('SELECT updated_at FROM taktovia.staff_shift WHERE id=%s', (sid,)).fetchone()[0]
        with tx(A) as c:
            c.execute('UPDATE taktovia.staff_shift SET staffing_need = staffing_need WHERE id=%s', (sid,))   # nichts geändert
            self.assertEqual(c.execute('SELECT updated_at, shift_revision FROM taktovia.staff_shift WHERE id=%s', (sid,)).fetchone(), (t0, 1))
        with tx(A) as c:
            c.execute('UPDATE taktovia.staff_shift SET driver_ref=%s WHERE id=%s', (uid(), sid))
            t1, rev = c.execute('SELECT updated_at, shift_revision FROM taktovia.staff_shift WHERE id=%s', (sid,)).fetchone()
            self.assertGreater(t1, t0); self.assertEqual(rev, 2)

    def test_index_tenant_state_updated_at_vorhanden(self):
        with admin() as c:
            defs = [r[0] for r in c.execute("SELECT indexdef FROM pg_indexes WHERE schemaname='taktovia' AND tablename='staff_shift'").fetchall()]
        self.assertTrue(any('(tenant_id, state, updated_at)' in d for d in defs), defs)


if __name__ == '__main__':
    unittest.main()
