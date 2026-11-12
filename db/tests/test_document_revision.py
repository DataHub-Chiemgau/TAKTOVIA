"""WP-M4-23 (DOC-007): document_revision ist unter der Runtime-Rolle append-only; Feldstand und Revision gehören zusammen; gebuchte Belege sind nicht bearbeitbar."""
import json, threading, unittest
import psycopg
from helpers import tx, uid, seed_tenant
from m4_belege_helpers import legal_entity, stored_document

A, B = uid(), uid()


def add_revision(c, tenant, doc, rev, values, by='u-1'):
    rid = uid()
    c.execute("INSERT INTO taktovia.document_revision(tenant_id,id,document_id,revision,field_values,edited_by,reason) VALUES (%s,%s,%s,%s,%s::jsonb,%s,'Korrektur')", (tenant, rid, doc, rev, json.dumps(values), by))
    return rid


class BelegRevision(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm423-a'); seed_tenant(B, 'm423-b')
        cls.le_a = legal_entity(A)

    def test_revision_unveraenderlich_unter_runtime_rolle(self):  # AK 2, 3
        d = stored_document(A, self.le_a)
        with tx(A) as c: rid = add_revision(c, A, d, 2, {'amount_minor': {'old': None, 'new': 8900}})
        for stmt in ("UPDATE taktovia.document_revision SET field_values='{}'::jsonb WHERE id=%s", "UPDATE taktovia.document_revision SET edited_by='x' WHERE id=%s", 'DELETE FROM taktovia.document_revision WHERE id=%s'):
            with tx(A) as c:
                with self.assertRaises((psycopg.errors.InsufficientPrivilege, psycopg.errors.CheckViolation), msg=stmt): c.execute(stmt, (rid,))
        with tx(A) as c:   # auch ein berechtigtes UPDATE (Trigger) bliebe wirkungslos: Eigentümerrechte hat die Runtime nicht
            self.assertEqual(c.execute('SELECT field_values->\'amount_minor\'->>\'new\' FROM taktovia.document_revision WHERE id=%s', (rid,)).fetchone()[0], '8900')

    def test_revision_je_beleg_eindeutig_und_mandantengetrennt(self):
        d = stored_document(A, self.le_a)
        with tx(A) as c: add_revision(c, A, d, 2, {})
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation): add_revision(c, A, d, 2, {})
        with tx(B) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.document_revision').fetchone()[0], 0)
            with self.assertRaises((psycopg.errors.InsufficientPrivilege, psycopg.errors.ForeignKeyViolation)): add_revision(c, A, d, 3, {})

    def test_feldstand_nur_mit_revision_und_nur_im_entwurf(self):  # AK 4
        d = stored_document(A, self.le_a)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.expense_document SET current_fields='{\"amount_minor\":1}'::jsonb WHERE id=%s", (d,))   # ohne Revision
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.expense_document SET revision=3 WHERE id=%s", (d,))     # Sprung
        with tx(A) as c:
            c.execute("UPDATE taktovia.expense_document SET current_fields='{\"amount_minor\":8900}'::jsonb, revision=2 WHERE id=%s", (d,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.expense_document SET revision=1 WHERE id=%s", (d,))      # rückwärts
        with tx(A) as c: c.execute("UPDATE taktovia.expense_document SET state='POSTED' WHERE id=%s", (d,))
        with tx(A) as c:   # gebucht: keine freie Betragsbearbeitung mehr
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.expense_document SET current_fields='{\"amount_minor\":3900}'::jsonb, revision=3 WHERE id=%s", (d,))

    def test_konkurrierende_korrektur_derselben_revision_genau_eine(self):
        d = stored_document(A, self.le_a)
        results = []
        def go():
            with tx(A) as c:
                n = c.execute("UPDATE taktovia.expense_document SET current_fields=%s::jsonb, revision=revision+1 WHERE id=%s AND revision=1 AND state='STORED'", (json.dumps({'amount_minor': 1}), d)).rowcount
                if n == 1: add_revision(c, A, d, 2, {'amount_minor': {'old': None, 'new': 1}})
                results.append(n)
        ts = [threading.Thread(target=go) for _ in range(20)]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual(sorted(results), [0] * 19 + [1])


if __name__ == '__main__':
    unittest.main()
