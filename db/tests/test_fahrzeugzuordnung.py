"""WP-M3-06 / LH-6.8-FLT-002: Dienstzuordnung des Fahrzeugpools (`vehicle_pool_assignment`) unter der echten Runtime-Rolle: Mandantentrennung per zusammengesetztem
Schlüssel (Dienst, Gebiet, Fahrzeug), mehrere Dienste je Fahrzeug, Überlappungsverbot je (Dienst, Fahrzeug), halboffenes Intervall, Unveränderlichkeit."""
import threading, unittest
import psycopg
from geo_helpers import make_service, square
from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()
VEH_A, VEH_B = uid(), uid()
SVC_A, SVC_A2, SVC_B = None, None, None
AREA_A, AREA_A2, AREA_B = uid(), uid(), uid()


def area(c, tenant, service, aid, x0, vf='2026-01-01', vt=None):
    c.execute("INSERT INTO taktovia.service_area(tenant_id,id,service_id,revision,area,valid_from,valid_to) VALUES (%s,%s,%s,1,ST_GeomFromText(%s,4326),%s,%s)",
              (tenant, aid, service, square(x0, 49.0), vf, vt))


def assign(c, tenant, vehicle, service, vf, vt=None, area_id=None):
    pid = uid()
    c.execute("INSERT INTO taktovia.vehicle_pool_assignment(tenant_id,id,service_id,vehicle_id,service_area_id,valid_from,valid_to,created_by) VALUES (%s,%s,%s,%s,%s,%s,%s,'user:test')",
              (tenant, pid, service, vehicle, area_id, vf, vt))
    return pid


class Fahrzeugzuordnung(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        global SVC_A, SVC_A2, SVC_B
        seed_tenant(A, 'm306-a'); seed_tenant(B, 'm306-b')
        with tx(A) as c:
            SVC_A, SVC_A2 = make_service(c, A), make_service(c, A)
            c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'x','{}')", (A, VEH_A))
            area(c, A, SVC_A, AREA_A, 8.0); area(c, A, SVC_A2, AREA_A2, 9.0)
        with tx(B) as c:
            SVC_B = make_service(c, B)
            c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'x','{}')", (B, VEH_B))
            area(c, B, SVC_B, AREA_B, 8.0)

    def new_vehicle(self):
        v = uid()
        with tx(A) as c: c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'x','{}')", (A, v))
        return v

    def test_zuordnung_fremder_mandant_nicht_speicherbar(self):
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):  # Dienst des Mandanten B, Fahrzeug des Mandanten A
            with tx(A) as c: assign(c, A, VEH_A, SVC_B, '2030-01-01')
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):  # Fahrzeug des Mandanten B, Dienst des Mandanten A
            with tx(A) as c: assign(c, A, VEH_B, SVC_A, '2030-01-01')
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):  # Gebiet des Mandanten B
            with tx(A) as c: assign(c, A, VEH_A, SVC_A, '2030-01-01', area_id=AREA_B)
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):  # Gebiet eines anderen Dienstes desselben Mandanten
            with tx(A) as c: assign(c, A, VEH_A, SVC_A, '2030-01-01', area_id=AREA_A2)
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):  # fremder Mandant im Schreibkontext (RLS)
            with tx(A) as c: assign(c, B, VEH_B, SVC_B, '2030-01-01')
        with tx(A) as c:  # Zeilen des Mandanten B sind unsichtbar; zulässige Zuordnung mit Gebiet des eigenen Dienstes bleibt möglich
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.vehicle_pool_assignment WHERE tenant_id=%s', (B,)).fetchone()[0], 0)
            assign(c, A, VEH_A, SVC_A, '2031-01-01', '2031-02-01', area_id=AREA_A)

    def test_mehrere_dienste_gleichzeitig_aber_nicht_doppelt_im_selben_dienst(self):
        v = self.new_vehicle()
        with tx(A) as c:
            assign(c, A, v, SVC_A, '2026-01-01'); assign(c, A, v, SVC_A2, '2026-01-01')
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.vehicle_pool_assignment WHERE vehicle_id=%s', (v,)).fetchone()[0], 2)
        with self.assertRaises(psycopg.errors.ExclusionViolation):
            with tx(A) as c: assign(c, A, v, SVC_A, '2026-06-01', '2026-07-01')

    def test_halboffenes_intervall_und_ungueltige_intervalle(self):
        v = self.new_vehicle()
        with tx(A) as c:
            assign(c, A, v, SVC_A, '2026-01-01', '2026-06-01'); assign(c, A, v, SVC_A, '2026-06-01', '2026-09-01')  # Ende exklusiv: anschließend zulässig
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: assign(c, A, v, SVC_A, '2027-01-01', '2027-01-01')

    def test_paralleles_anlegen_im_selben_dienst_genau_ein_erfolg_verschiedene_dienste_beide(self):
        v, results, barrier = self.new_vehicle(), [], threading.Barrier(6)

        def run(svc):
            try:
                with tx(A) as c:
                    barrier.wait(); assign(c, A, v, svc, '2040-01-01'); results.append(svc)
            except (psycopg.errors.ExclusionViolation, psycopg.errors.DeadlockDetected):   # gleichzeitige Überschneidung: Exclusion Constraint oder Deadlockabbruch
                results.append('overlap')
        ts = [threading.Thread(target=run, args=(SVC_A,)) for _ in range(5)] + [threading.Thread(target=run, args=(SVC_A2,))]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual(results.count(SVC_A), 1, results); self.assertEqual(results.count(SVC_A2), 1, results); self.assertEqual(results.count('overlap'), 4, results)

    def test_unveraenderlichkeit_nur_ende_eines_laufenden_intervalls(self):
        v = self.new_vehicle()
        with tx(A) as c: pid = assign(c, A, v, SVC_A, '2026-01-01', area_id=AREA_A)
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):  # Runtime darf nur valid_to ändern (Spaltenrecht)
            with tx(A) as c: c.execute("UPDATE taktovia.vehicle_pool_assignment SET service_area_id=NULL WHERE id=%s", (pid,))
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):  # kein Löschen
            with tx(A) as c: c.execute("DELETE FROM taktovia.vehicle_pool_assignment WHERE id=%s", (pid,))
        with tx(A) as c:
            c.execute("UPDATE taktovia.vehicle_pool_assignment SET valid_to='2026-08-01' WHERE id=%s", (pid,))
            self.assertEqual(c.execute("SELECT revision FROM taktovia.vehicle_pool_assignment WHERE id=%s", (pid,)).fetchone()[0], 2)
        with self.assertRaises(psycopg.errors.CheckViolation):  # abgeschlossen: unveränderlich
            with tx(A) as c: c.execute("UPDATE taktovia.vehicle_pool_assignment SET valid_to='2026-09-01' WHERE id=%s", (pid,))
        with admin() as c:  # auch für die Administration: nie löschen
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("DELETE FROM taktovia.vehicle_pool_assignment WHERE id=%s", (pid,))


if __name__ == '__main__':
    unittest.main()
