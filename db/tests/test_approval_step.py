"""WP-M4-34 (DOC-010): Freigabeschritte append-only und mandantengetrennt; Rollentrennung (keine Selbstfreigabe, zweite Freigabe nie durch den Erstfreigeber) hält auch an der Anwendung vorbei;
der Exportstatus ist nur für gebuchte Belege und nur einmal setzbar. Alles unter der echten Runtime-Rolle."""
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant
from m4_belege_helpers import legal_entity, stored_document

A, B = uid(), uid()


def step(c, tenant, doc, step, decision='APPROVED', by='user:freigeber', revision=1, reason='Begründung'):
    sid = uid()
    c.execute("INSERT INTO taktovia.approval_step(tenant_id,id,document_id,document_revision,step,decision,decided_by,reason) VALUES (%s,%s,%s,%s,%s,%s,%s,%s)", (tenant, sid, doc, revision, step, decision, by, reason))
    return sid


class Freigabeschritte(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm434-a'); seed_tenant(B, 'm434-b')
        cls.le_a, cls.le_b = legal_entity(A), legal_entity(B)

    def doc(self, tenant=None, le=None):
        return stored_document(tenant or A, le or self.le_a)   # created_by = 'u-1'

    def test_schritte_append_only_und_mandantengetrennt(self):
        d = self.doc()
        with tx(A) as c:
            capture = step(c, A, d, 'CAPTURE', by='u-1'); step(c, A, d, 'REVIEW', by='user:pruefer'); step(c, A, d, 'BUDGET', by='user:erst')
        for stmt in ("UPDATE taktovia.approval_step SET reason='anders' WHERE id=%s", "DELETE FROM taktovia.approval_step WHERE id=%s"):
            with tx(A) as c:
                with self.assertRaises((psycopg.errors.InsufficientPrivilege, psycopg.errors.CheckViolation)): c.execute(stmt, (capture,))
        # Wertebereiche: Schritt, Entscheidung, Begründung 3..1000, Erfassung nie abgelehnt
        for kw in ({'step': 'EXPORT'}, {'decision': 'MAYBE'}, {'reason': 'ab'}, {'reason': 'x' * 1001}):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation): step(c, A, d, **{'step': 'REVIEW', **kw})
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): step(c, A, d, 'CAPTURE', 'REJECTED', by='u-1')
        # Mandantentrennung: nichts lesbar, kein Schritt für fremde Belege, Mandantenschlüssel nicht fälschbar
        with tx(B) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.approval_step').fetchone()[0], 0)
            with self.assertRaises((psycopg.errors.ForeignKeyViolation, psycopg.errors.InsufficientPrivilege)): step(c, B, d, 'REVIEW')
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): step(c, B, d, 'REVIEW')
        with tx(A) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.approval_step WHERE document_id=%s', (d,)).fetchone()[0], 3)

    def test_rollentrennung_haelt_in_der_datenbank(self):
        d = self.doc()   # Erfasser: 'u-1'
        with tx(A) as c:   # Selbstfreigabe durch den Erfasser: weder Budgetfreigabe noch zweite Freigabe
            with self.assertRaises(psycopg.errors.CheckViolation): step(c, A, d, 'BUDGET', by='u-1')
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): step(c, A, d, 'SECOND_APPROVAL', by='u-1')
        with tx(A) as c:   # eine Ablehnung durch den Erfasser ist keine Freigabe und bleibt möglich
            step(c, A, d, 'BUDGET', 'REJECTED', by='u-1')
        with tx(A) as c:   # zweite Freigabe ohne Erstfreigabe dieser Revision
            with self.assertRaises(psycopg.errors.CheckViolation): step(c, A, d, 'SECOND_APPROVAL', by='user:zweit')
        with tx(A) as c: step(c, A, d, 'BUDGET', by='user:erst')
        with tx(A) as c:   # dieselbe Person in beiden Schritten
            with self.assertRaises(psycopg.errors.CheckViolation): step(c, A, d, 'SECOND_APPROVAL', by='user:erst')
        with tx(A) as c:   # Erstfreigabe einer anderen Revision zählt nicht
            with self.assertRaises(psycopg.errors.CheckViolation): step(c, A, d, 'SECOND_APPROVAL', by='user:zweit', revision=2)
        with tx(A) as c: step(c, A, d, 'SECOND_APPROVAL', by='user:zweit')   # eine andere Person: erlaubt

    def test_exportstatus_nur_fuer_gebuchte_belege_und_nur_einmal(self):
        d = self.doc()
        job = lambda c: c.execute("SELECT set_config('app.export_job', 'on', true)")   # seit WP-M4-37 setzt nur der Exportjob den Status (transaktionslokaler Schalter)
        with tx(A) as c:
            job(c)
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.expense_document SET export_state='EXPORTED', exported_at=now(), exported_by='svc' WHERE id=%s", (d,))
        with tx(A) as c: c.execute("UPDATE taktovia.expense_document SET state='POSTED', approved_by='user:x' WHERE id=%s", (d,))
        with tx(A) as c:
            job(c)
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.expense_document SET export_state='EXPORTED' WHERE id=%s", (d,))   # Zeit und Akteur fehlen
        with tx(A) as c:   # ohne den Schalter des Exportjobs kann niemand den Status setzen, auch nicht mit Zeit und Akteur
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.expense_document SET export_state='EXPORTED', exported_at=now(), exported_by='svc' WHERE id=%s", (d,))
        with tx(A) as c:
            job(c); c.execute("UPDATE taktovia.expense_document SET export_state='EXPORTED', exported_at=now(), exported_by='svc' WHERE id=%s", (d,))
        with tx(A) as c:
            job(c)
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.expense_document SET export_state='NOT_EXPORTED', exported_at=NULL, exported_by=NULL WHERE id=%s", (d,))
        with tx(A) as c:   # bereitgestellt -> bestätigt, danach endgültig
            job(c); c.execute("UPDATE taktovia.expense_document SET export_state='CONFIRMED', export_confirmed_at=now() WHERE id=%s", (d,))
        with tx(A) as c:
            job(c)
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.expense_document SET export_state='EXPORTED', export_confirmed_at=NULL WHERE id=%s", (d,))

    def test_schwelle_ist_begrenzt(self):
        with tx(A) as c: c.execute("INSERT INTO taktovia.finance_policy(tenant_id,updated_by) VALUES (%s,'u') ON CONFLICT DO NOTHING", (A,))
        for bad in (0, -5, 9007199254740992):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.finance_policy SET approval_second_threshold_minor=%s WHERE tenant_id=%s", (bad, A))
        with tx(A) as c: c.execute("UPDATE taktovia.finance_policy SET approval_second_threshold_minor=10000 WHERE tenant_id=%s", (A,))


if __name__ == '__main__':
    unittest.main()
