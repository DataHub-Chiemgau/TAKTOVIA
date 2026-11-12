"""WP-M4-24 (DOC-008): Integritätsvorfälle sind append-only; eine festgestellte Verletzung ist endgültig und sperrt die Freigabe auf Datenbankebene."""
import unittest
import psycopg
from helpers import tx, uid, seed_tenant
from m4_belege_helpers import legal_entity, stored_document

A, B = uid(), uid()
H1, H2 = 'a' * 64, 'b' * 64


def incident(c, tenant, doc, failure='HASH_MISMATCH', actual=H2, trigger='SCHEDULED'):
    iid = uid()
    c.execute("INSERT INTO taktovia.document_integrity_incident(tenant_id,id,document_id,trigger_kind,failure,expected_sha256,actual_sha256,detected_by) VALUES (%s,%s,%s,%s,%s,%s,%s,'integrity-job')",
              (tenant, iid, doc, trigger, failure, H1, actual))
    return iid


class BelegIntegritaet(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm424-a'); seed_tenant(B, 'm424-b')
        cls.le_a = legal_entity(A)

    def test_vorfall_ist_append_only(self):  # AK 1: Vorfall bleibt erhalten
        d = stored_document(A, self.le_a)
        with tx(A) as c: i = incident(c, A, d)
        for stmt in ("UPDATE taktovia.document_integrity_incident SET failure='OBJECT_MISSING' WHERE id=%s", 'DELETE FROM taktovia.document_integrity_incident WHERE id=%s'):
            with tx(A) as c:
                with self.assertRaises((psycopg.errors.InsufficientPrivilege, psycopg.errors.CheckViolation), msg=stmt): c.execute(stmt, (i,))
        with tx(B) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.document_integrity_incident').fetchone()[0], 0)

    def test_vorfallschema_prueft_hashes(self):
        d = stored_document(A, self.le_a)
        for kw in (dict(failure='OBJECT_MISSING', actual=H2), dict(failure='HASH_MISMATCH', actual=None), dict(failure='HASH_MISMATCH', actual=H1), dict(failure='HASH_MISMATCH', actual='xyz'), dict(trigger='NIE')):
            with tx(A) as c:
                with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.NotNullViolation), msg=str(kw)): incident(c, A, d, **kw)
        with tx(A) as c: incident(c, A, d, failure='OBJECT_MISSING', actual=None)

    def test_verletzung_ist_endgueltig_und_sperrt_die_freigabe(self):  # AK 2
        d = stored_document(A, self.le_a)
        with tx(A) as c: c.execute("UPDATE taktovia.expense_document SET integrity_state='VERIFIED', integrity_checked_at=now() WHERE id=%s", (d,))
        with tx(A) as c: c.execute("UPDATE taktovia.expense_document SET integrity_state='VIOLATED', integrity_checked_at=now() WHERE id=%s", (d,))
        for stmt in ("UPDATE taktovia.expense_document SET integrity_state='VERIFIED' WHERE id=%s", "UPDATE taktovia.expense_document SET integrity_state='UNCHECKED' WHERE id=%s",
                     "UPDATE taktovia.expense_document SET state='APPROVED' WHERE id=%s", "UPDATE taktovia.expense_document SET state='POSTED' WHERE id=%s"):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation, msg=stmt): c.execute(stmt, (d,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.expense_document SET integrity_state='SCHLECHT' WHERE id=%s", (d,))

    def test_unveraenderte_belege_bleiben_verifizierbar(self):  # AK 3: wiederholte Prüfung ohne Vorfall
        d = stored_document(A, self.le_a)
        for _ in range(3):
            with tx(A) as c: c.execute("UPDATE taktovia.expense_document SET integrity_state='VERIFIED', integrity_checked_at=now() WHERE id=%s", (d,))
        with tx(A) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.document_integrity_incident WHERE document_id=%s', (d,)).fetchone()[0], 0)


if __name__ == '__main__':
    unittest.main()
