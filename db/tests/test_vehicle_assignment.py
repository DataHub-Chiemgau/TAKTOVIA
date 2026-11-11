"""WP-M1-34 / LH-I.4-MUL-004: Fahrzeugrollen mit Gültigkeitsintervall unter der echten Runtime-Rolle (Überlappungsverbot je Rolle und Fahrzeug,
Mandantentrennung per zusammengesetztem Schlüssel, Unveränderlichkeit abgeschlossener Intervalle)."""
import threading, unittest
import psycopg
from helpers import admin, runtime, tx, uid, seed_tenant

A, B = uid(), uid()
VA, VB = uid(), uid()
ORG_A, ORG_A2, ORG_B = uid(), uid(), uid()
ROLES = ['OWNER', 'OPERATOR', 'DISPATCH_PROVIDER', 'BILLING_PARTNER']


def assign(c, tenant, vehicle, org, role, vf, vt=None):
    aid = uid()
    c.execute("INSERT INTO taktovia.vehicle_assignment(tenant_id,id,vehicle_id,organization_id,role,valid_from,valid_to,created_by) "
              "VALUES (%s,%s,%s,%s,%s,%s,%s,'user:test')", (tenant, aid, vehicle, org, role, vf, vt))
    return aid


class Fahrzeugrollen(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm134-a'); seed_tenant(B, 'm134-b')
        for t, veh, orgs in ((A, VA, (ORG_A, ORG_A2)), (B, VB, (ORG_B,))):
            le = uid()
            with tx(t) as c:
                c.execute("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (%s,%s,'LE','DE')", (t, le))
                for o in orgs:
                    c.execute("INSERT INTO taktovia.organization(tenant_id,id,legal_entity_id,name,federation_ref,roles) VALUES (%s,%s,%s,'Org',%s,%s)", (t, o, le, f'f-{o}', ['TRANSPORT_COMPANY']))
                c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'x','{}')", (t, veh))

    def test_vier_rollen_gleichzeitig_unabhaengig(self):
        veh = uid()
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'x','{}')", (A, veh))
            for r in ROLES: assign(c, A, veh, ORG_A, r, '2026-01-01')
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.vehicle_assignment WHERE vehicle_id=%s', (veh,)).fetchone()[0], 4)

    def test_ueberlappung_gleiche_rolle_abgewiesen(self):
        veh = uid()
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'x','{}')", (A, veh))
            assign(c, A, veh, ORG_A, 'OWNER', '2026-01-01', '2026-06-01')
        for vf, vt in (('2026-05-31', '2026-07-01'), ('2025-12-01', '2026-01-02'), ('2026-02-01', '2026-03-01'), ('2026-03-01', None)):
            with self.assertRaises(psycopg.errors.ExclusionViolation, msg=f'{vf}..{vt}'):
                with tx(A) as c: assign(c, A, veh, ORG_A2, 'OWNER', vf, vt)
        with tx(A) as c:  # halboffen: das Ende ist exklusiv, ein anschließendes Intervall ist zulässig; andere Rollen sind unabhängig
            assign(c, A, veh, ORG_A2, 'OWNER', '2026-06-01'); assign(c, A, veh, ORG_A2, 'OPERATOR', '2026-02-01', '2026-03-01')
        with self.assertRaises(psycopg.errors.CheckViolation):  # Ende nicht nach Beginn
            with tx(A) as c: assign(c, A, veh, ORG_A, 'BILLING_PARTNER', '2026-03-01', '2026-03-01')
        with self.assertRaises(psycopg.errors.CheckViolation):  # unbekannte Rolle
            with tx(A) as c: assign(c, A, veh, ORG_A, 'MIETER', '2026-03-01')

    def test_paralleles_einfuegen_gleicher_rolle_genau_ein_erfolg(self):
        veh, results, barrier = uid(), [], threading.Barrier(6)
        with tx(A) as c: c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'x','{}')", (A, veh))

        def run():
            try:
                with tx(A) as c:
                    barrier.wait(); assign(c, A, veh, ORG_A, 'OWNER', '2026-09-01'); results.append('ok')
            except psycopg.errors.ExclusionViolation:
                results.append('overlap')
        ts = [threading.Thread(target=run) for _ in range(6)]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual((results.count('ok'), results.count('overlap')), (1, 5), results)

    def test_organisation_fremder_mandant_abgewiesen(self):
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):  # Organisation des Mandanten B, Fahrzeug des Mandanten A
            with tx(A) as c: assign(c, A, VA, ORG_B, 'OWNER', '2030-01-01')
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):  # Fahrzeug des Mandanten B
            with tx(A) as c: assign(c, A, VB, ORG_A, 'OWNER', '2030-01-01')
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):  # fremder Mandant im Schreibkontext (RLS)
            with tx(A) as c: assign(c, B, VB, ORG_B, 'OWNER', '2030-01-01')
        with tx(A) as c:  # Zeilen des Mandanten B sind unsichtbar
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.vehicle_assignment WHERE tenant_id=%s', (B,)).fetchone()[0], 0)

    def test_abgeschlossenes_intervall_unveraenderlich_unter_runtime_rolle(self):
        veh = uid()
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'x','{}')", (A, veh))
            closed = assign(c, A, veh, ORG_A, 'OWNER', '2026-01-01', '2026-06-01'); running = assign(c, A, veh, ORG_A2, 'OPERATOR', '2026-01-01')
        for stmt in ("UPDATE taktovia.vehicle_assignment SET valid_to = valid_to + interval '1 day' WHERE id=%s", "UPDATE taktovia.vehicle_assignment SET valid_to = NULL WHERE id=%s",
                     'DELETE FROM taktovia.vehicle_assignment WHERE id=%s'):
            with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InsufficientPrivilege), msg=stmt):
                with tx(A) as c: c.execute(stmt, (closed,))
        for col, val in (('organization_id', ORG_A), ('role', "'OWNER'"), ('valid_from', "'2025-01-01'")):  # Spaltenrecht: nur valid_to
            with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=col):
                with tx(A) as c: c.execute(f'UPDATE taktovia.vehicle_assignment SET {col}=%s WHERE id=%s' if col == 'organization_id' else f'UPDATE taktovia.vehicle_assignment SET {col}={val} WHERE id=%s', (ORG_A, running) if col == 'organization_id' else (running,))
        with self.assertRaises(psycopg.errors.InsufficientPrivilege):
            with tx(A) as c: c.execute('DELETE FROM taktovia.vehicle_assignment WHERE id=%s', (running,))
        # auch die Administration kann Identität und Geschlossenes nicht verändern (Trigger unabhängig von Rechten)
        with admin() as a:
            for stmt in ("UPDATE taktovia.vehicle_assignment SET organization_id=%s WHERE id=%s", "UPDATE taktovia.vehicle_assignment SET valid_to=NULL WHERE id=%s"):
                with self.assertRaises(psycopg.errors.CheckViolation):
                    a.execute(stmt, (ORG_A2, running) if 'organization_id' in stmt else (closed,))
            with self.assertRaises(psycopg.errors.CheckViolation):
                a.execute('DELETE FROM taktovia.vehicle_assignment WHERE id=%s', (closed,))
        # Schließen eines laufenden Intervalls ist die einzige zulässige Änderung (einmal, Revision +1)
        with tx(A) as c:
            self.assertEqual(c.execute("UPDATE taktovia.vehicle_assignment SET valid_to='2026-12-01' WHERE id=%s", (running,)).rowcount, 1)
            self.assertEqual(c.execute('SELECT revision FROM taktovia.vehicle_assignment WHERE id=%s', (running,)).fetchone()[0], 2)
        with self.assertRaises(psycopg.errors.CheckViolation):
            with tx(A) as c: c.execute("UPDATE taktovia.vehicle_assignment SET valid_to='2027-01-01' WHERE id=%s", (running,))


if __name__ == '__main__':
    unittest.main()
