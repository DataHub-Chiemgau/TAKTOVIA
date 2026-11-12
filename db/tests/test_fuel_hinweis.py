"""WP-M4-31 (DOC-012): Plausibilitätshinweise je Regel und Belegrevision, Quittierung als eigener append-only Eintrag, alles mandantengetrennt unter der echten Runtime-Rolle."""
import json
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant
from m4_belege_helpers import legal_entity, stored_document

A, B = uid(), uid()


def hint(c, tenant, doc, rule='FUEL_VOLUME', revision=1, hid=None):
    hid = hid or uid()
    c.execute("INSERT INTO taktovia.expense_hint(tenant_id,id,document_id,document_revision,rule,threshold,actual_value,basis) VALUES (%s,%s,%s,%s,%s,'80','95',%s::jsonb)",
              (tenant, hid, doc, revision, rule, json.dumps({'comparison_value': '80', 'threshold': '80', 'source': 'test'})))
    return hid


def ack(c, tenant, hid, reason='Begründung ok'):
    c.execute("INSERT INTO taktovia.expense_hint_ack(tenant_id,hint_id,acked_by,reason) VALUES (%s,%s,'user:x',%s)", (tenant, hid, reason))


class Hinweise(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm431-a'); seed_tenant(B, 'm431-b')
        cls.le_a, cls.le_b = legal_entity(A), legal_entity(B)

    def test_hinweis_mandantengetrennt_und_quittierung_append_only(self):
        d = stored_document(A, self.le_a, kind='FUEL_RECEIPT', document_kind='FUEL_RECEIPT')
        with tx(A) as c: h = hint(c, A, d)
        # ein Hinweis je Regel und Revision, auch bei wiederholtem Prüflauf; eine neue Revision darf erneut warnen
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation): hint(c, A, d)
        with tx(A) as c: hint(c, A, d, revision=2); hint(c, A, d, rule='FUEL_PRICE')
        # Hinweis und Quittierung sind unveränderlich
        for stmt in ("UPDATE taktovia.expense_hint SET actual_value='1' WHERE id=%s", "DELETE FROM taktovia.expense_hint WHERE id=%s"):
            with tx(A) as c:
                with self.assertRaises((psycopg.errors.InsufficientPrivilege, psycopg.errors.CheckViolation)): c.execute(stmt, (h,))
        with tx(A) as c: ack(c, A, h)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation): ack(c, A, h, 'zweite Quittierung')   # höchstens eine Quittierung je Hinweis
        for stmt in ("UPDATE taktovia.expense_hint_ack SET reason='anders' WHERE hint_id=%s", "DELETE FROM taktovia.expense_hint_ack WHERE hint_id=%s"):
            with tx(A) as c:
                with self.assertRaises((psycopg.errors.InsufficientPrivilege, psycopg.errors.CheckViolation)): c.execute(stmt, (h,))
        # Begründung 3 bis 1000 Zeichen, unbekannte Regel abgewiesen
        with tx(A) as c:
            h2 = hint(c, A, d, rule='FUEL_CARD_MISMATCH')
            with self.assertRaises(psycopg.errors.CheckViolation): ack(c, A, h2, 'ab')
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): hint(c, A, d, rule='FUEL_MAGIC', revision=9)
        # Mandantentrennung: Mandant B sieht nichts und kann weder lesen noch quittieren noch einen Hinweis zu fremdem Beleg anlegen
        with tx(B) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.expense_hint').fetchone()[0], 0)
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.expense_hint_ack').fetchone()[0], 0)
            with self.assertRaises((psycopg.errors.ForeignKeyViolation, psycopg.errors.InsufficientPrivilege)): ack(c, B, h)
        with tx(B) as c:
            with self.assertRaises((psycopg.errors.ForeignKeyViolation, psycopg.errors.InsufficientPrivilege)): hint(c, B, d)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): hint(c, B, d)   # Mandantenschlüssel gefälscht

    def test_parameter_je_fahrzeug_mandantengetrennt(self):
        v = uid()
        with admin() as c: c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'org','{}'::jsonb)", (A, v))
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.fuel_vehicle_policy(tenant_id,vehicle_id,fuel_max_liters,fuel_use_alert_pct,updated_by) VALUES (%s,%s,80,20,'u')", (A, v))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.fuel_vehicle_policy SET fuel_max_liters=0 WHERE vehicle_id=%s", (v,))
        with tx(B) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.fuel_vehicle_policy').fetchone()[0], 0)
            with self.assertRaises((psycopg.errors.ForeignKeyViolation, psycopg.errors.InsufficientPrivilege)):
                c.execute("INSERT INTO taktovia.fuel_vehicle_policy(tenant_id,vehicle_id,updated_by) VALUES (%s,%s,'u')", (B, v))   # fremdes Fahrzeug

    def test_schwellenparameter_sind_dezimal_und_begrenzt(self):
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.finance_policy(tenant_id,updated_by,fuel_max_liters,fuel_price_alert_pct,fuel_use_alert_pct,fuel_price_reference,fuel_card_mapping) VALUES (%s,'u',80.5,15.25,20,'{\"DIESEL\":\"1.65\"}'::jsonb,'[]'::jsonb)", (A,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.finance_policy SET fuel_price_alert_pct=-1 WHERE tenant_id=%s", (A,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.finance_policy SET fuel_card_mapping='{}'::jsonb WHERE tenant_id=%s", (A,))
        with tx(A) as c:
            self.assertEqual(str(c.execute('SELECT fuel_max_liters FROM taktovia.finance_policy').fetchone()[0]), '80.500')


if __name__ == '__main__':
    unittest.main()
