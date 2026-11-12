"""WP-M4-30 (DOC-005): Tankvorgang je Beleg. Menge und Preis ohne Gleitkomma, Einheit aus der Kraftstoffart, nur Belege der Art FUEL_RECEIPT im Entwurfszustand, Brutto und Währung
folgen dem Belegstand, Fahrzeug nur im eigenen Mandanten. Alles unter der echten Runtime-Rolle."""
import json
import unittest
from decimal import Decimal
import psycopg
from helpers import admin, tx, uid, seed_tenant
from m4_belege_helpers import legal_entity, stored_document

A, B = uid(), uid()


def vehicle(tenant):
    vid = uid()
    with admin() as c:
        c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'org',%s::jsonb)", (tenant, vid, '{}'))
    return vid


def fuel(c, tenant, doc, **v):
    cols = {'vehicle_id': None, 'fuel_type': 'DIESEL', 'quantity': Decimal('40.5'), 'unit_price': Decimal('1.6990'), 'odometer_km': 120000, **v}
    fid = uid()
    c.execute("INSERT INTO taktovia.fuel_transaction(tenant_id,id,document_id,vehicle_id,fuel_type,quantity,unit_price,odometer_km,document_revision,recorded_by) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,1,'u-1')",
              (tenant, fid, doc, cols['vehicle_id'], cols['fuel_type'], cols['quantity'], cols['unit_price'], cols['odometer_km']))
    return fid


class Tankvorgang(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm430-a'); seed_tenant(B, 'm430-b')
        cls.le_a, cls.le_b = legal_entity(A), legal_entity(B)

    def fuel_doc(self, tenant=None, le=None):
        return stored_document(tenant or A, le or self.le_a, kind='FUEL_RECEIPT', document_kind='FUEL_RECEIPT')

    def test_negative_menge_und_fremdes_fahrzeug_abgewiesen(self):
        d = self.fuel_doc()
        for bad in (Decimal('0'), Decimal('-1.5')):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation): fuel(c, A, d, quantity=bad)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): fuel(c, A, d, unit_price=Decimal('0'))
        # Fahrzeug eines anderen Mandanten: zusammengesetzter Fremdschlüssel, weder unter A noch unter B möglich
        fremd = vehicle(B)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.ForeignKeyViolation): fuel(c, A, d, vehicle_id=fremd)
        with tx(A) as c:
            with self.assertRaises((psycopg.errors.ForeignKeyViolation, psycopg.errors.InsufficientPrivilege)): fuel(c, B, d, vehicle_id=fremd)   # Mandantenschlüssel gefälscht: Trigger bzw. WITH CHECK
        # eigenes Fahrzeug funktioniert
        with tx(A) as c:
            fuel(c, A, d, vehicle_id=vehicle(A))

    def test_menge_und_preis_sind_dezimal_ohne_gleitkomma(self):
        with admin() as c:
            rows = c.execute("SELECT column_name, data_type, numeric_precision, numeric_scale FROM information_schema.columns WHERE table_schema='taktovia' AND table_name='fuel_transaction'").fetchall()
        types = {r[0]: r for r in rows}
        for name in ('quantity', 'unit_price'):
            self.assertEqual((types[name][1], types[name][2], types[name][3]), ('numeric', 20, 8), name)
        self.assertEqual(types['gross_minor'][1], 'bigint'); self.assertEqual(types['odometer_km'][1], 'bigint')
        self.assertFalse([r for r in rows if r[1] in ('real', 'double precision')], 'kein Gleitkommatyp')
        d = self.fuel_doc()
        with tx(A) as c:
            fid = fuel(c, A, d, quantity=Decimal('33.33333333'), unit_price=Decimal('1.79900000'))
        with tx(A) as c:
            q, p = c.execute('SELECT quantity, unit_price FROM taktovia.fuel_transaction WHERE id=%s', (fid,)).fetchone()
        self.assertEqual((q, p), (Decimal('33.33333333'), Decimal('1.799')))   # exakt, keine Rundungsartefakte

    def test_einheit_folgt_der_kraftstoffart_liter_und_kwh_getrennt(self):
        d1, d2, d3 = self.fuel_doc(), self.fuel_doc(), self.fuel_doc()
        with tx(A) as c:
            f1 = fuel(c, A, d1, fuel_type='DIESEL'); f2 = fuel(c, A, d2, fuel_type='ELECTRIC'); f3 = fuel(c, A, d3, fuel_type=None)
        with tx(A) as c:
            units = dict(c.execute('SELECT id, unit FROM taktovia.fuel_transaction WHERE id = ANY(%s)', ([f1, f2, f3],)).fetchall())
        self.assertEqual((units[f1], units[f2], units[f3]), ('LITER', 'KWH', None))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): fuel(c, A, self.fuel_doc(), fuel_type='KEROSIN')

    def test_nur_fuer_tankbelege_nur_ein_tankvorgang_je_beleg(self):
        plain = stored_document(A, self.le_a)   # Art RECEIPT
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): fuel(c, A, plain)
        d = self.fuel_doc()
        with tx(A) as c: fuel(c, A, d)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation): fuel(c, A, d)

    def test_brutto_und_waehrung_folgen_dem_belegstand(self):
        d = self.fuel_doc()
        with tx(A) as c:
            fid = fuel(c, A, d)
            c.execute("UPDATE taktovia.expense_document SET current_fields=%s::jsonb, revision=2 WHERE id=%s", (json.dumps({'amount_minor': 6877, 'currency': 'EUR'}), d))
        with tx(A) as c:
            self.assertEqual(c.execute('SELECT gross_minor, currency, document_revision FROM taktovia.fuel_transaction WHERE id=%s', (fid,)).fetchone(), (6877, 'EUR', 2))
            c.execute("UPDATE taktovia.expense_document SET current_fields=%s::jsonb, revision=3 WHERE id=%s", (json.dumps({'amount_minor': 7000, 'currency': 'EUR'}), d))
            self.assertEqual(c.execute('SELECT gross_minor FROM taktovia.fuel_transaction WHERE id=%s', (fid,)).fetchone()[0], 7000)

    def test_nach_der_freigabe_unveraenderlich_und_keine_loeschung(self):
        d = self.fuel_doc()
        with tx(A) as c: fid = fuel(c, A, d)
        with tx(A) as c:
            c.execute("UPDATE taktovia.expense_document SET state='APPROVED', approved_by='user:x' WHERE id=%s", (d,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute('UPDATE taktovia.fuel_transaction SET quantity=1 WHERE id=%s', (fid,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): c.execute('DELETE FROM taktovia.fuel_transaction WHERE id=%s', (fid,))

    def test_mandantentrennung(self):
        d = self.fuel_doc()
        with tx(A) as c: fid = fuel(c, A, d)
        with tx(B) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.fuel_transaction WHERE id=%s', (fid,)).fetchone()[0], 0)
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): c.execute('INSERT INTO taktovia.finance_policy(tenant_id,updated_by) VALUES (%s,%s)', (A, 'x'))


if __name__ == '__main__':
    unittest.main()
