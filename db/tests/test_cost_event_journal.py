"""WP-M4-33: Kostenereignis, Journal und einmaliges Posting auf Datenbankebene (unter der Runtime-Rolle): Ereignis unveränderlich und einmalig, Journal ausgeglichen mit mindestens zwei
Zeilen, offene Periode, gebuchtes Journal unveränderlich, Summengleichheit mit dem Ereignis, keine mandantenübergreifende Ausgeglichenheit."""
import datetime
import threading
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant
from m4_belege_helpers import legal_entity, stored_document

A, B = uid(), uid()
D = datetime.date


def period(tenant, le, starts=D(2026, 1, 1), ends=D(2027, 1, 1), closed=False):
    pid = uid()
    with admin() as c:
        c.execute("INSERT INTO taktovia.fiscal_period(tenant_id,id,legal_entity_id,starts_on,ends_on,closed_at) VALUES (%s,%s,%s,%s,%s,%s)", (tenant, pid, le, starts, ends, datetime.datetime.now(datetime.timezone.utc) if closed else None))
    return pid


def event(c, tenant, le, ref=None, net=10000, tax=1900, recoverable=1900, doc=None, reverses=None, scenario='ACTUAL', cur='EUR'):
    eid = uid()
    c.execute("INSERT INTO taktovia.economic_event(tenant_id,id,legal_entity_id,source_system,source_ref,event_type,scenario,category,currency,net_minor,tax_minor,gross_minor,recoverable_tax_minor,tax_rule_revision,occurred_at,booked_at,document_id,reverses_id) "
              "VALUES (%s,%s,%s,'EXPENSE',%s,'EXPENSE_COST',%s,'FUEL',%s,%s,%s,%s,%s,'TEST-1',now(),now(),%s,%s)",
              (tenant, eid, le, ref or str(uid()), scenario, cur, net, tax, net + tax, recoverable, doc, reverses))
    return eid


def draft(c, tenant, le, eid, per, cur='EUR', date=D(2026, 6, 1)):
    jid = uid()
    c.execute("INSERT INTO taktovia.journal(tenant_id,id,event_id,legal_entity_id,currency,period_id,accounting_date,state) VALUES (%s,%s,%s,%s,%s,%s,%s,'DRAFT')", (tenant, jid, eid, le, cur, per, date))
    return jid


def line(c, tenant, jid, account, side, amount):
    c.execute("INSERT INTO taktovia.journal_line(tenant_id,id,journal_id,account_code,side,amount_minor) VALUES (%s,%s,%s,%s,%s,%s)", (tenant, uid(), jid, account, side, amount))


def post(c, jid, by='user:test'):
    c.execute("UPDATE taktovia.journal SET state='POSTED', posted_by=%s WHERE id=%s", (by, jid))


def balanced(c, tenant, jid, gross=11900, tax=1900):
    line(c, tenant, jid, '6600', 'D', gross - tax); line(c, tenant, jid, '1576', 'D', tax); line(c, tenant, jid, '1600', 'C', gross)


class KostenereignisJournal(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm433-a'); seed_tenant(B, 'm433-b')
        cls.le_a, cls.le_b = legal_entity(A), legal_entity(B)
        cls.per_a, cls.per_b = period(A, cls.le_a), period(B, cls.le_b)

    def posted(self, **kw):
        with tx(A) as c:
            e = event(c, A, self.le_a, **kw); j = draft(c, A, self.le_a, e, self.per_a); balanced(c, A, j, kw.get('net', 10000) + kw.get('tax', 1900), kw.get('tax', 1900)); post(c, j)
        return e, j

    def test_posting_tankung_kostenbasis_vorsteuer_verbindlichkeit(self):  # AK 1
        e, j = self.posted()
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT net_minor,tax_minor,gross_minor,recoverable_tax_minor,cost_basis_minor FROM taktovia.economic_event WHERE id=%s", (e,)).fetchone(), (10000, 1900, 11900, 1900, 10000))
            self.assertEqual(c.execute("SELECT account_code,side,amount_minor FROM taktovia.journal_line WHERE journal_id=%s ORDER BY account_code", (j,)).fetchall(), [('1576', 'D', 1900), ('1600', 'C', 11900), ('6600', 'D', 10000)])

    def test_ereignis_ist_unveraenderlich(self):  # AK 4
        e, _ = self.posted()
        for stmt in ("UPDATE taktovia.economic_event SET category='X' WHERE id=%s", "DELETE FROM taktovia.economic_event WHERE id=%s"):
            with tx(A) as c:
                with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InsufficientPrivilege), msg=stmt): c.execute(stmt, (e,))

    def test_gebuchtes_journal_ist_unveraenderlich(self):  # AK 4
        _, j = self.posted()
        for stmt in ("UPDATE taktovia.journal SET accounting_date=accounting_date + 1 WHERE id=%s", "DELETE FROM taktovia.journal WHERE id=%s",
                     "UPDATE taktovia.journal_line SET amount_minor = amount_minor + 1 WHERE journal_id=%s", "DELETE FROM taktovia.journal_line WHERE journal_id=%s"):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.RaiseException, msg=stmt): c.execute(stmt, (j,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.RaiseException): line(c, A, j, '6600', 'D', 1)

    def test_ein_ereignis_je_ursprung_und_je_beleg(self):  # AK 2
        with tx(A) as c: event(c, A, self.le_a, ref='ursprung-1')
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation): event(c, A, self.le_a, ref='ursprung-1')
        d = stored_document(A, self.le_a)
        with tx(A) as c: e = event(c, A, self.le_a, doc=d)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation): event(c, A, self.le_a, doc=d)   # zweites Ist-Ereignis zum selben Beleg
        with tx(A) as c: event(c, A, self.le_a, doc=d, reverses=e, net=-10000, tax=-1900, recoverable=-1900)   # ein Gegenereignis ist erlaubt
        with tx(A) as c: event(c, A, self.le_a, doc=d, scenario='PLAN')                                       # Plan ist ein eigenes Szenario

    def test_journal_ausgeglichen_und_mindestens_zwei_zeilen(self):  # AK 3
        with tx(A) as c:
            e = event(c, A, self.le_a); j = draft(c, A, self.le_a, e, self.per_a)
            line(c, A, j, '6600', 'D', 11900); line(c, A, j, '1600', 'C', 8000)
            with self.assertRaises(psycopg.errors.RaiseException): post(c, j)    # unausgeglichen
        with tx(A) as c:
            e = event(c, A, self.le_a, net=0, tax=0, recoverable=0); j = draft(c, A, self.le_a, e, self.per_a)
            with self.assertRaises(psycopg.errors.RaiseException): post(c, j)    # keine Zeilen
        with tx(A) as c:
            e = event(c, A, self.le_a, net=100, tax=0, recoverable=0); j = draft(c, A, self.le_a, e, self.per_a)
            line(c, A, j, '6600', 'D', 100)
            with self.assertRaises(psycopg.errors.RaiseException): post(c, j)    # eine Zeile
        with tx(A) as c:
            e = event(c, A, self.le_a); j = draft(c, A, self.le_a, e, self.per_a)
            with self.assertRaises(psycopg.errors.CheckViolation): line(c, A, j, '6600', 'D', 0)   # Betrag 0
        with tx(A) as c:
            e = event(c, A, self.le_a); j = draft(c, A, self.le_a, e, self.per_a)
            with self.assertRaises(psycopg.errors.RaiseException): c.execute("INSERT INTO taktovia.journal(tenant_id,id,event_id,legal_entity_id,currency,period_id,accounting_date,state,posted_by) VALUES (%s,%s,%s,%s,'EUR',%s,%s,'POSTED','x')", (A, uid(), event(c, A, self.le_a), self.le_a, self.per_a, D(2026, 6, 1)))   # direkt gebucht

    def test_nur_mandantenuebergreifend_ausgeglichen_wird_abgewiesen(self):  # AK 3, PH-AT-013
        with tx(B) as c:
            eb = event(c, B, self.le_b); jb = draft(c, B, self.le_b, eb, self.per_b); line(c, B, jb, '1600', 'C', 11900)
        with tx(A) as c:
            e = event(c, A, self.le_a); j = draft(c, A, self.le_a, e, self.per_a); line(c, A, j, '6600', 'D', 11900)
            # die Gegenzeile liegt im Mandanten B: weder am Journal von A noch unter B anlegbar
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): c.execute("INSERT INTO taktovia.journal_line(tenant_id,id,journal_id,account_code,side,amount_minor) VALUES (%s,%s,%s,'1600','C',11900)", (B, uid(), j))
        with tx(A) as c:
            e = event(c, A, self.le_a); j = draft(c, A, self.le_a, e, self.per_a); line(c, A, j, '6600', 'D', 11900)
            with self.assertRaises(psycopg.errors.RaiseException): post(c, j)    # nur Soll: nicht ausgeglichen, auch wenn B die Gegenseite hält
        with tx(B) as c:
            with self.assertRaises(psycopg.errors.ForeignKeyViolation): line(c, B, j, '1600', 'C', 11900)    # Journal von A ist unter B nicht sichtbar

    def test_geschlossene_periode_und_datum_ausserhalb(self):
        closed = period(A, self.le_a, D(2025, 1, 1), D(2026, 1, 1), closed=True)
        with tx(A) as c:
            e = event(c, A, self.le_a); j = draft(c, A, self.le_a, e, closed, date=D(2025, 6, 1)); balanced(c, A, j)
            with self.assertRaises(psycopg.errors.RaiseException): post(c, j)
        with tx(A) as c:
            e = event(c, A, self.le_a); j = draft(c, A, self.le_a, e, self.per_a, date=D(2027, 1, 1)); balanced(c, A, j)
            with self.assertRaises(psycopg.errors.RaiseException): post(c, j)    # Datum außerhalb der Periode (Ende exklusiv)

    def test_ereignisinvarianten(self):
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):   # netto + steuer <> brutto
                c.execute("INSERT INTO taktovia.economic_event(tenant_id,id,legal_entity_id,source_system,source_ref,event_type,scenario,category,currency,net_minor,tax_minor,gross_minor,tax_rule_revision,occurred_at,booked_at) VALUES (%s,%s,%s,'EXPENSE','x','EXPENSE_COST','ACTUAL','FUEL','EUR',100,19,100,'T',now(),now())", (A, uid(), self.le_a))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): event(c, A, self.le_a, net=9007199254740991, tax=1, recoverable=0)   # außerhalb des sicheren Integerbereichs
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): event(c, A, self.le_a, recoverable=2000)   # abziehbare Steuer > Steuer

    def test_paralleles_posting_desselben_ursprungs_genau_ein_ereignis(self):
        """Zwei parallele Transaktionen legen dasselbe Ereignis an: genau eine gewinnt, die zweite scheitert an der Eindeutigkeit (die Anwendung liefert dann das vorhandene Ereignis)."""
        ref = str(uid()); barrier = threading.Barrier(2); out = []

        def run():
            with tx(A) as c:
                barrier.wait()
                try:
                    e = event(c, A, self.le_a, ref=ref); j = draft(c, A, self.le_a, e, self.per_a); balanced(c, A, j); post(c, j); out.append('ok')
                except psycopg.errors.UniqueViolation:
                    out.append('dupl')
        ts = [threading.Thread(target=run) for _ in range(2)]; [t.start() for t in ts]; [t.join(30) for t in ts]
        self.assertEqual(sorted(out), ['dupl', 'ok'])
        with tx(A) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.economic_event WHERE source_ref=%s", (ref,)).fetchone()[0], 1)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.journal j JOIN taktovia.economic_event e ON e.id=j.event_id WHERE e.source_ref=%s", (ref,)).fetchone()[0], 1)

    def test_kontierungsregeln_mandantengetrennt_und_pruefung(self):
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.accounting_mapping(tenant_id,legal_entity_id,category,expense_account,input_tax_account,payable_account,updated_by) VALUES (%s,%s,'FUEL','6600','1576','1600','user:t')", (A, self.le_a))
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.accounting_mapping").fetchone()[0], 0)
            with self.assertRaises(psycopg.errors.ForeignKeyViolation):   # Rechtsträger von A in B
                c.execute("INSERT INTO taktovia.accounting_mapping(tenant_id,legal_entity_id,category,expense_account,input_tax_account,payable_account,updated_by) VALUES (%s,%s,'FUEL','6600','1576','1600','user:t')", (B, self.le_a))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.accounting_mapping SET payable_account=expense_account")


if __name__ == '__main__':
    unittest.main()
