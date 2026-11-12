"""WP-M4-37: Exportbatch auf Datenbankebene unter der Runtime-Rolle: nur gebuchte, vollständig freigegebene Journale des Rechtsträgers im Zeitraum, je Journal genau ein Batch (auch parallel), bereitgestellter Inhalt
und Hash unveränderlich, Bestätigungs-ID einmal, Exportstatus am Beleg nur durch den Exportjob."""
import datetime
import hashlib
import threading
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant
from m4_belege_helpers import legal_entity, stored_document

A = uid()
D = datetime.date


def operation(tenant):
    oid = uid()
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.operation(tenant_id,id,journey_id,kind,status,deadline_at) VALUES (%s,%s,NULL,'TEST_COMMAND','QUEUED',now() + interval '1 day')", (tenant, oid))
    return oid


_seq = [0]


def export(tenant, le, starts=D(2026, 9, 1), ends=None, state='REQUESTED'):
    """Ohne Ende: je Aufruf ein eigener Umfang (ein offener Auftrag je Umfang), der den 15.09.2026 einschließt."""
    if ends is None:
        _seq[0] += 1; ends = D(2026, 9, 16) + datetime.timedelta(days=_seq[0])
    eid = uid(); op = operation(tenant)
    with tx(tenant) as c:
        c.execute("INSERT INTO taktovia.ledger_export(tenant_id,id,legal_entity_id,target_system,period_from,period_to,state,operation_id,requested_by) VALUES (%s,%s,%s,'NEUTRAL',%s,%s,%s,%s,'user:t')", (tenant, eid, le, starts, ends, state, op))
    return eid


class Hauptbuchexport(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm437-a')
        cls.le, cls.le2 = legal_entity(A), legal_entity(A)
        with admin() as c:
            cls.period = uid()
            c.execute("INSERT INTO taktovia.fiscal_period(tenant_id,id,legal_entity_id,starts_on,ends_on) VALUES (%s,%s,%s,'2026-09-01','2026-10-01')", (A, cls.period, cls.le))

    def journal(self, approved=True, budget_step=True, posted=True, date=D(2026, 9, 15), doc_state='POSTED'):
        """Beleg, Ereignis und Journal; liefert (Journal, Beleg)."""
        d = stored_document(A, self.le)
        with admin() as c:
            c.execute("UPDATE taktovia.expense_document SET state=%s, approved_by=%s WHERE id=%s", (doc_state, 'user:freigeber' if approved else None, d))
            if budget_step:
                c.execute("INSERT INTO taktovia.approval_step(tenant_id,id,document_id,document_revision,step,decision,decided_by,reason) VALUES (%s,%s,%s,1,'BUDGET','APPROVED','user:freigeber','Freigegeben')", (A, uid(), d))
            e, j = uid(), uid()
            c.execute("INSERT INTO taktovia.economic_event(tenant_id,id,legal_entity_id,source_system,source_ref,event_type,scenario,category,currency,net_minor,tax_minor,gross_minor,recoverable_tax_minor,tax_rule_revision,occurred_at,booked_at,document_id) "
                      "VALUES (%s,%s,%s,'EXPENSE',%s,'EXPENSE_COST','ACTUAL','FUEL','EUR',10000,0,10000,0,'T',now(),now(),%s)", (A, e, self.le, str(e), d))
            c.execute("INSERT INTO taktovia.journal(tenant_id,id,event_id,legal_entity_id,currency,period_id,accounting_date,state) VALUES (%s,%s,%s,%s,'EUR',%s,%s,'DRAFT')", (A, j, e, self.le, self.period, date))
            c.execute("INSERT INTO taktovia.journal_line(tenant_id,id,journal_id,account_code,side,amount_minor) VALUES (%s,%s,%s,'6600','D',10000),(%s,%s,%s,'1600','C',10000)", (A, uid(), j, A, uid(), j))
            if posted: c.execute("UPDATE taktovia.journal SET state='POSTED', posted_by='user:t' WHERE id=%s", (j,))
        return j, d

    def line(self, c, ex, j, d):
        c.execute("INSERT INTO taktovia.ledger_export_line(tenant_id,journal_id,export_id,document_id) VALUES (%s,%s,%s,%s)", (A, j, ex, d))

    def test_nur_gebuchte_vollstaendig_freigegebene_journale_des_rechtstraegers_im_zeitraum(self):  # AK 2
        ex = export(A, self.le)
        good, gd = self.journal()
        with tx(A) as c: self.line(c, ex, good, gd)
        cases = {
            'Journal nur Entwurf': dict(posted=False), 'Beleg ohne Freigabe': dict(approved=False), 'Beleg ohne Budgetfreigabe-Schritt': dict(budget_step=False),
            'Beleg noch nicht gebucht (zweite Freigabe aussteht)': dict(doc_state='WAITING_SECOND_APPROVAL'),
        }
        for name, kw in cases.items():
            j, d = self.journal(**kw)
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation, msg=name): self.line(c, ex, j, d)
        other_period = export(A, self.le, D(2026, 8, 1), D(2026, 9, 1))
        j, d = self.journal()
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): self.line(c, other_period, j, d)   # Buchungsdatum liegt außerhalb des Batchzeitraums
        other_entity = export(A, self.le2)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): self.line(c, other_entity, j, d)   # anderer Rechtsträger

    def test_je_journal_genau_ein_batch_auch_parallel(self):  # AK 3, konkurrierend
        j, d = self.journal(); a, b = export(A, self.le), export(A, self.le, D(2026, 9, 1), D(2026, 11, 1))
        with tx(A) as c: self.line(c, a, j, d)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation): self.line(c, b, j, d)
        j2, d2 = self.journal(); x, y = export(A, self.le, D(2026, 9, 1), D(2026, 9, 30)), export(A, self.le, D(2026, 9, 2), D(2026, 10, 1)); barrier = threading.Barrier(2); out = []

        def run(ex):
            with tx(A) as c:
                barrier.wait()
                try:
                    self.line(c, ex, j2, d2); out.append('ok')
                except psycopg.errors.UniqueViolation:
                    out.append('dupl')
        ts = [threading.Thread(target=run, args=(e,)) for e in (x, y)]; [t.start() for t in ts]; [t.join(30) for t in ts]
        self.assertEqual(sorted(out), ['dupl', 'ok'])

    def test_bereitgestellter_batch_ist_unveraenderlich_und_bestaetigung_einmal(self):  # AK 1, 3
        ex = export(A, self.le); j, d = self.journal()
        with tx(A) as c: self.line(c, ex, j, d)
        content = b'{"batch":"x"}'; sha = hashlib.sha256(content).hexdigest()
        with tx(A) as c:   # Bereitstellung ohne vollständige Angaben scheitert
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.ledger_export SET state='PROVIDED' WHERE id=%s", (ex,))
        with tx(A) as c:
            c.execute("UPDATE taktovia.ledger_export SET state='PROVIDED', batch_ref='EXP-ABCDEF123456', format_name='neutral', format_version='1', media_type='application/json', content=%s, content_sha256=%s, content_size=%s, journal_count=1, provided_at=now() WHERE id=%s",
                      (content, sha, len(content), ex))
        for stmt, args in (("UPDATE taktovia.ledger_export SET content=%s WHERE id=%s", (b'{}', ex)), ("UPDATE taktovia.ledger_export SET content_sha256=%s WHERE id=%s", ('0' * 64, ex)), ("UPDATE taktovia.ledger_export SET batch_ref='EXP-XXXXXXXXXXXX' WHERE id=%s", (ex,)),
                           ("UPDATE taktovia.ledger_export SET period_to='2026-12-01' WHERE id=%s", (ex,)), ("UPDATE taktovia.ledger_export SET state='REQUESTED' WHERE id=%s", (ex,)), ("DELETE FROM taktovia.ledger_export WHERE id=%s", (ex,))):
            with tx(A) as c:
                with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InsufficientPrivilege), msg=stmt): c.execute(stmt, args)
        j2, d2 = self.journal()
        with tx(A) as c:   # dem bereitgestellten Batch kommen keine Zeilen mehr hinzu
            with self.assertRaises(psycopg.errors.CheckViolation): self.line(c, ex, j2, d2)
        with tx(A) as c:
            c.execute("UPDATE taktovia.ledger_export_line SET import_ref='ERP-4711', confirmed_at=now() WHERE journal_id=%s", (j,))
        for stmt in ("UPDATE taktovia.ledger_export_line SET import_ref='ERP-0815' WHERE journal_id=%s", "UPDATE taktovia.ledger_export_line SET import_ref=NULL, confirmed_at=NULL WHERE journal_id=%s", "DELETE FROM taktovia.ledger_export_line WHERE journal_id=%s"):
            with tx(A) as c:
                with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InsufficientPrivilege), msg=stmt): c.execute(stmt, (j,))
        with tx(A) as c:
            c.execute("UPDATE taktovia.ledger_export SET state='CONFIRMED', confirmed_at=now(), confirmed_by='user:t' WHERE id=%s", (ex,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.ledger_export SET state='PROVIDED', confirmed_at=NULL, confirmed_by=NULL WHERE id=%s", (ex,))

    def test_ein_offener_auftrag_je_umfang(self):  # Wiederholung liefert denselben Auftrag
        le = legal_entity(A); export(A, le, D(2031, 1, 1), D(2031, 2, 1))
        with self.assertRaises(psycopg.errors.UniqueViolation): export(A, le, D(2031, 1, 1), D(2031, 2, 1))
        export(A, le, D(2031, 1, 1), D(2031, 3, 1))   # anderer Umfang


if __name__ == '__main__':
    unittest.main()
