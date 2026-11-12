"""WP-M4-40 (FCO-002): Kostenartenkatalog `cost_category` mit den sechs Fahrzeugkostengruppen je Mandant (Seed per Funktion, nur unter dem eigenen Mandanten), RLS, kein Ändern/Löschen für die Runtime-Rolle;
manuelles Kostenereignis `manual_cost_event` mit Rollentrennung in der Datenbank. Alles unter der echten Runtime-Rolle."""
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant
from m4_belege_helpers import legal_entity

A, B = uid(), uid()
GROUPS = {'ACQUISITION_LEASE', 'INSURANCE', 'VEHICLE_TAX', 'ENERGY', 'MAINTENANCE', 'REPAIR'}


class Kostenartenkatalog(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm440-a'); seed_tenant(B, 'm440-b')
        cls.le = legal_entity(A)

    def test_seed_sechs_gruppen_und_mandantentrennung(self):
        with tx(A) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.cost_category').fetchone()[0], 0, 'ohne Seed leer')
            c.execute('SELECT taktovia.seed_cost_categories()')
            c.execute('SELECT taktovia.seed_cost_categories()')   # wiederholbar
            rows = c.execute('SELECT category, cost_group, default_behavior, default_attribution FROM taktovia.cost_category ORDER BY category').fetchall()
        self.assertEqual({r[1] for r in rows}, GROUPS, rows)
        self.assertTrue(GROUPS <= {r[0] for r in rows})
        self.assertTrue(all(r[2] and r[3] for r in rows), 'jede Kostenart hat ein Standardverhalten')
        with tx(B) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.cost_category').fetchone()[0], 0, 'der Katalog eines Mandanten ist für andere unsichtbar')
        with tx(B) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):   # Zeilen für einen fremden Mandanten
                c.execute("INSERT INTO taktovia.cost_category(tenant_id,category,label,cost_group,default_behavior,default_attribution) VALUES (%s,'ENERGY','x','ENERGY','VARIABLE','DIRECT')", (A,))
        with tx(A) as c:
            for stmt in ("UPDATE taktovia.cost_category SET label='x' WHERE category='ENERGY'", "DELETE FROM taktovia.cost_category WHERE category='ENERGY'"):
                with self.assertRaises(psycopg.errors.InsufficientPrivilege): c.execute(stmt)
                c.connection.rollback(); c.execute("SELECT set_config('app.tenant_id', %s, true)", (str(A),))

    def test_wertebereiche_der_kostenart(self):
        for vals in (('bad key', 'x', None, None, None), ('KEY1', 'x', 'OTHER', None, None), ('KEY2', 'x', None, 'FIXED', None), ('KEY3', 'x', None, None, 'DIRECT'), ('KEY4', '', None, None, None)):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation):
                    c.execute("INSERT INTO taktovia.cost_category(tenant_id,category,label,cost_group,default_behavior,default_attribution) VALUES (%s,%s,%s,%s,%s,%s)", (A, *vals[:2], vals[2], vals[3], vals[4]))
        with tx(A) as c:   # eigene Kostenart ohne Gruppe und ohne Standardverhalten ist erlaubt (z. B. Gemeinkosten); wird als nicht klassifiziert gebucht
            c.execute("INSERT INTO taktovia.cost_category(tenant_id,category,label) VALUES (%s,'OVERHEAD','Gemeinkosten')", (A,))


def manual(c, tenant, le, obj, state='IN_REVIEW', by='u-1', key=None, category='ENERGY'):
    mid = uid()
    c.execute("INSERT INTO taktovia.manual_cost_event(tenant_id,id,legal_entity_id,vehicle_id,cost_object_id,category,currency,net_minor,tax_minor,gross_minor,tax_treatment,accounting_date,origin_key,state,revision,created_by) "
              "VALUES (%s,%s,%s,%s,%s,%s,'EUR',1000,0,1000,'FULLY_DEDUCTIBLE','2026-09-15',%s,%s,1,%s)", (tenant, mid, le, obj[0], obj[1], category, key or str(mid), state, by))
    return mid


def vehicle_object(tenant, le):
    v, o = uid(), uid()
    with admin() as c:
        c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'org','{}'::jsonb)", (tenant, v))
        c.execute("INSERT INTO taktovia.cost_object(tenant_id,id,legal_entity_id,kind,business_ref,vehicle_id) VALUES (%s,%s,%s,'VEHICLE',%s,%s)", (tenant, o, le, f'{v}/{le}', v))
    return v, o


def step(c, tenant, mid, step, decision='APPROVED', by='user:freigeber', revision=1):
    c.execute("INSERT INTO taktovia.manual_cost_event_step(tenant_id,id,manual_cost_event_id,revision,step,decision,decided_by,reason) VALUES (%s,%s,%s,%s,%s,%s,%s,'Begründung')", (tenant, uid(), mid, revision, step, decision, by))


class ManuellesKostenereignis(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm440-a2')
        cls.le = legal_entity(A)
        cls.obj = vehicle_object(A, cls.le)
        with tx(A) as c: c.execute('SELECT taktovia.seed_cost_categories()')

    def test_ursprungsschluessel_eindeutig_und_unbekannte_kostenart_abgewiesen(self):
        with tx(A) as c: manual(c, A, self.le, self.obj, key='k-1')
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation): manual(c, A, self.le, self.obj, key='k-1')
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.ForeignKeyViolation): manual(c, A, self.le, self.obj, category='NO_SUCH')

    def test_zustandsautomat_und_unveraenderlicher_inhalt(self):
        with tx(A) as c: m = manual(c, A, self.le, self.obj)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.manual_cost_event SET gross_minor=2000, net_minor=2000 WHERE id=%s", (m,))   # Inhalt unveränderlich
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.manual_cost_event SET state='POSTED' WHERE id=%s", (m,))   # Sprung über Prüfung und Freigabe
        with tx(A) as c:
            for s in ('REVIEWED', 'WAITING_SECOND_APPROVAL', 'REJECTED'):
                pass
            c.execute("UPDATE taktovia.manual_cost_event SET state='REVIEWED', revision=2 WHERE id=%s", (m,))
            c.execute("UPDATE taktovia.manual_cost_event SET state='REJECTED', revision=3 WHERE id=%s", (m,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.manual_cost_event SET state='REVIEWED' WHERE id=%s", (m,))   # REJECTED ist endgültig
        with tx(A) as c:
            with self.assertRaises((psycopg.errors.InsufficientPrivilege, psycopg.errors.CheckViolation)): c.execute("DELETE FROM taktovia.manual_cost_event WHERE id=%s", (m,))

    def test_rollentrennung_haelt_in_der_datenbank(self):
        with tx(A) as c: m = manual(c, A, self.le, self.obj, by='u-1')
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): step(c, A, m, 'BUDGET', by='u-1')   # der Erfasser gibt nicht selbst frei
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): step(c, A, m, 'SECOND_APPROVAL', by='user:zweit')   # ohne Erstfreigabe
        with tx(A) as c: step(c, A, m, 'BUDGET', by='user:erst')
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): step(c, A, m, 'SECOND_APPROVAL', by='user:erst')   # nicht dieselbe Person
        with tx(A) as c: step(c, A, m, 'SECOND_APPROVAL', by='user:zweit')
        with tx(A) as c:   # append-only
            with self.assertRaises((psycopg.errors.InsufficientPrivilege, psycopg.errors.CheckViolation)): c.execute("UPDATE taktovia.manual_cost_event_step SET reason='anders' WHERE manual_cost_event_id=%s", (m,))


if __name__ == '__main__':
    unittest.main()
