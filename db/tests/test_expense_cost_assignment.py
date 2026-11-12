"""WP-M4-29 (DOC-004): Kostenzuordnung Beleg <-> Kostenobjekt. Der Rechtsträger gehört zum Beleg, die Zuordnung zu Kostenstelle oder Fahrzeug desselben Mandanten UND Rechtsträgers
erzwingen zusammengesetzte Fremdschlüssel; inaktive Kostenobjekte nehmen keine Zuordnung mehr an. Alles unter der echten Runtime-Rolle."""
import threading
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant, runtime, set_tenant
from m4_belege_helpers import legal_entity, stored_document

A, B = uid(), uid()


def cost_object(tenant, le, kind='COST_CENTER', ref=None, vehicle=None):
    cid = uid()
    with admin() as c:
        c.execute("INSERT INTO taktovia.cost_object(tenant_id,id,legal_entity_id,kind,business_ref,vehicle_id) VALUES (%s,%s,%s,%s,%s,%s)", (tenant, cid, le, kind, ref or f'K-{cid}', vehicle))
    return cid


def vehicle(tenant):
    vid = uid()
    with admin() as c:
        c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'org',%s::jsonb)", (tenant, vid, '{}'))
    return vid


def assign(c, tenant, doc, obj, le, kind='COST_CENTER'):
    c.execute("INSERT INTO taktovia.expense_cost_assignment(tenant_id,document_id,cost_object_id,legal_entity_id,cost_object_kind,assigned_by) VALUES (%s,%s,%s,%s,%s,'u-1')", (tenant, doc, obj, le, kind))


class Kostenzuordnung(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm429-a'); seed_tenant(B, 'm429-b')
        cls.le_a, cls.le_a2, cls.le_b = legal_entity(A), legal_entity(A), legal_entity(B)

    def test_zuordnung_kostenstelle_und_fahrzeug(self):
        d = stored_document(A, self.le_a)
        ks = cost_object(A, self.le_a)
        v = vehicle(A); fz = cost_object(A, self.le_a, 'VEHICLE', vehicle=v)
        with tx(A) as c:
            assign(c, A, d, ks, self.le_a); assign(c, A, d, fz, self.le_a, 'VEHICLE')
        with tx(A) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.expense_cost_assignment WHERE document_id=%s', (d,)).fetchone()[0], 2)

    def test_cross_tenant_zuordnung_scheitert_am_fk(self):
        d = stored_document(A, self.le_a)
        fremd = cost_object(B, self.le_b)
        # Zuordnung eines Kostenobjekts des Mandanten B zu einem Beleg des Mandanten A: weder unter A noch unter B möglich
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.ForeignKeyViolation): assign(c, A, d, fremd, self.le_a)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.ForeignKeyViolation): assign(c, A, d, fremd, self.le_b)
        with tx(B) as c:   # Zeile im Mandanten B mit fremdem Beleg: RLS-Policy und FK greifen
            with self.assertRaises((psycopg.errors.ForeignKeyViolation, psycopg.errors.InsufficientPrivilege)): assign(c, B, d, fremd, self.le_b)
        with tx(A) as c:   # Mandantenschlüssel selbst gefälscht: WITH CHECK der Policy
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): assign(c, B, d, fremd, self.le_b)

    def test_kostenobjekt_eines_anderen_rechtstraegers_scheitert(self):
        d = stored_document(A, self.le_a)
        ks2 = cost_object(A, self.le_a2)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.ForeignKeyViolation): assign(c, A, d, ks2, self.le_a)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.ForeignKeyViolation): assign(c, A, d, ks2, self.le_a2)   # Beleg gehört zu le_a

    def test_nur_kostenstelle_und_fahrzeug_zulaessig(self):
        d = stored_document(A, self.le_a)
        proj = cost_object(A, self.le_a, 'PROJECT')
        with tx(A) as c:
            with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.ForeignKeyViolation)): assign(c, A, d, proj, self.le_a, 'PROJECT')
        ks = cost_object(A, self.le_a)
        with tx(A) as c:   # falsch deklarierte Art
            with self.assertRaises(psycopg.errors.ForeignKeyViolation): assign(c, A, d, ks, self.le_a, 'VEHICLE')

    def test_fahrzeug_kostenobjekt_braucht_fahrzeug_des_mandanten(self):
        fremdes = vehicle(B)
        with self.assertRaises(psycopg.errors.ForeignKeyViolation):
            cost_object(A, self.le_a, 'VEHICLE', vehicle=fremdes)
        with self.assertRaises(psycopg.errors.CheckViolation):
            cost_object(A, self.le_a, 'VEHICLE')              # Fahrzeug ohne Fahrzeugbezug
        with self.assertRaises(psycopg.errors.CheckViolation):
            cost_object(A, self.le_a, 'COST_CENTER', vehicle=vehicle(A))

    def test_inaktives_kostenobjekt_nimmt_keine_zuordnung_an(self):
        d = stored_document(A, self.le_a); ks = cost_object(A, self.le_a)
        with tx(A) as c: c.execute('UPDATE taktovia.cost_object SET active=false WHERE id=%s', (ks,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): assign(c, A, d, ks, self.le_a)
        with tx(A) as c:   # keine Reaktivierung
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute('UPDATE taktovia.cost_object SET active=true, deactivated_at=NULL WHERE id=%s', (ks,))

    def test_zuordnung_nur_im_entwurfszustand(self):
        d = stored_document(A, self.le_a); ks = cost_object(A, self.le_a)
        with tx(A) as c: assign(c, A, d, ks, self.le_a)
        with admin() as c: c.execute("UPDATE taktovia.expense_document SET state='APPROVED' WHERE id=%s", (d,))
        ks2 = cost_object(A, self.le_a)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): assign(c, A, d, ks2, self.le_a)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute('DELETE FROM taktovia.expense_cost_assignment WHERE document_id=%s', (d,))

    def test_doppelte_zuordnung_und_keine_aenderung(self):
        d = stored_document(A, self.le_a); ks = cost_object(A, self.le_a)
        with tx(A) as c: assign(c, A, d, ks, self.le_a)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation): assign(c, A, d, ks, self.le_a)
        with tx(A) as c:
            with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InsufficientPrivilege)): c.execute("UPDATE taktovia.expense_cost_assignment SET assigned_by='x' WHERE document_id=%s", (d,))

    def test_zuordnungen_sind_mandantengetrennt(self):
        d = stored_document(A, self.le_a); ks = cost_object(A, self.le_a)
        with tx(A) as c: assign(c, A, d, ks, self.le_a)
        with tx(B) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.expense_cost_assignment').fetchone()[0], 0)

    def test_deaktivierung_parallel_zur_zuordnung_ist_konsistent(self):
        """Konkurrierend: Zuordnung und Deaktivierung laufen gleichzeitig gegen die Datenbank. Ergebnis: Zuordnung abgewiesen ODER Zuordnung vorhanden bei inaktivem Objekt
        (dann nicht freigabefähig); nie ein Fehlerzustand und nie eine Zuordnung zu einem schon zuvor inaktiven Objekt."""
        for _ in range(8):
            d = stored_document(A, self.le_a); ks = cost_object(A, self.le_a)
            barrier = threading.Barrier(2); out = {}

            def do_assign():
                with tx(A) as c:
                    barrier.wait()
                    try: assign(c, A, d, ks, self.le_a); out['assign'] = 'ok'
                    except psycopg.errors.CheckViolation: out['assign'] = 'abgewiesen'

            def do_deactivate():
                with tx(A) as c:
                    barrier.wait()
                    c.execute('UPDATE taktovia.cost_object SET active=false WHERE id=%s', (ks,)); out['deactivate'] = 'ok'
            ts = [threading.Thread(target=do_assign), threading.Thread(target=do_deactivate)]
            [t.start() for t in ts]; [t.join(30) for t in ts]
            self.assertEqual(out.get('deactivate'), 'ok', out); self.assertIn(out.get('assign'), ('ok', 'abgewiesen'), out)
            with tx(A) as c:
                n = c.execute('SELECT count(*) FROM taktovia.expense_cost_assignment WHERE document_id=%s', (d,)).fetchone()[0]
                self.assertEqual(n, 1 if out['assign'] == 'ok' else 0)
                self.assertFalse(c.execute('SELECT active FROM taktovia.cost_object WHERE id=%s', (ks,)).fetchone()[0])


if __name__ == '__main__':
    unittest.main()
