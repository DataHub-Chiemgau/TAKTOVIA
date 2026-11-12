"""WP-M4-26 (DOC-013): Client-UUID als Idempotenzschlüssel (Unique je Mandant), Erfassungs- und Eingangszeit getrennt, Original- und Erfassungsfelder unveränderlich."""
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant
from m4_belege_helpers import legal_entity, ticket, document, stored_document

A, B = uid(), uid()


class BelegClientUuid(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm426-a'); seed_tenant(B, 'm426-b')
        cls.le_a, cls.le_b = legal_entity(A), legal_entity(B)

    def test_unique_tenant_client_uuid(self):
        cu = uid()
        with tx(A) as c:
            t1 = ticket(c, A, self.le_a); document(c, A, self.le_a, t1, client_uuid=cu)
        with tx(A) as c:   # zweiter Beleg derselben Client-UUID im selben Mandanten: abgewiesen
            t2 = ticket(c, A, self.le_a)
            with self.assertRaises(psycopg.errors.UniqueViolation): document(c, A, self.le_a, t2, client_uuid=cu)
        with tx(B) as c:   # derselbe Wert in einem anderen Mandanten ist ein anderer Beleg (Unique nur je tenant_id)
            t3 = ticket(c, B, self.le_b); d = document(c, B, self.le_b, t3, client_uuid=cu)
            self.assertEqual(c.execute('SELECT client_uuid FROM taktovia.expense_document WHERE id=%s', (d,)).fetchone()[0], cu)
        with tx(B) as c:   # RLS: der Beleg des Mandanten A ist für B nicht sichtbar
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.expense_document WHERE client_uuid=%s', (cu,)).fetchone()[0], 1)
        with tx(A) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.expense_document WHERE client_uuid=%s', (cu,)).fetchone()[0], 1)

    def test_ohne_angabe_gilt_die_beleg_id(self):  # Web-Upload, E-Mail und Import brauchen keine Client-UUID; Altbestand ebenso (deterministisch)
        with tx(A) as c:
            t = ticket(c, A, self.le_a); d = document(c, A, self.le_a, t)
            self.assertEqual(c.execute('SELECT client_uuid = id, captured_at IS NULL FROM taktovia.expense_document WHERE id=%s', (d,)).fetchone(), (True, True))

    def test_erfassungszeit_und_eingangszeit_getrennt(self):
        with tx(A) as c:
            t = ticket(c, A, self.le_a)
            d = document(c, A, self.le_a, t, captured_at='2026-01-02T08:00:00+00:00')
            cap, rec = c.execute('SELECT captured_at, original_received_at FROM taktovia.expense_document WHERE id=%s', (d,)).fetchone()
            self.assertEqual(cap.isoformat(), '2026-01-02T08:00:00+00:00'); self.assertGreater(rec, cap)   # offline erfasst, später eingegangen

    def test_original_felder_unveraenderlich(self):
        d = stored_document(A, self.le_a, client_uuid=uid(), captured_at='2026-01-02T08:00:00+00:00')
        for stmt in ("UPDATE taktovia.expense_document SET object_key='receipts/originals/x' WHERE id=%s", "UPDATE taktovia.expense_document SET original_sha256=repeat('b',64) WHERE id=%s",
                     "UPDATE taktovia.expense_document SET client_uuid=gen_random_uuid() WHERE id=%s", "UPDATE taktovia.expense_document SET captured_at=now() WHERE id=%s",
                     "UPDATE taktovia.expense_document SET original_received_at=now() WHERE id=%s"):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation, msg=stmt): c.execute(stmt, (d,))
        with tx(A) as c:   # Ticket: Client-UUID und Erfassungszeit ebenfalls unveränderlich
            t = uid()
            c.execute("INSERT INTO taktovia.upload_ticket(tenant_id,id,legal_entity_id,purpose,declared_mime,declared_size,declared_sha256,quarantine_key,issued_by,expires_at,client_uuid,captured_at) "
                      "VALUES (%s,%s,%s,'RECEIPT','image/jpeg',10,%s,%s,'u-1',now()+interval '1 hour',%s,now())", (A, t, self.le_a, 'a' * 64, f'receipts/quarantine/{t}', uid()))
        for stmt in ("UPDATE taktovia.upload_ticket SET client_uuid=gen_random_uuid() WHERE id=%s", "UPDATE taktovia.upload_ticket SET captured_at=now() WHERE id=%s"):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation, msg=stmt): c.execute(stmt, (t,))

    def test_ticket_kann_als_dublette_enden(self):
        with tx(A) as c:
            t = ticket(c, A, self.le_a)
            c.execute("UPDATE taktovia.upload_ticket SET state='COMPLETING' WHERE id=%s", (t,))
            c.execute("UPDATE taktovia.upload_ticket SET state='REJECTED', reject_reason='DUPLICATE' WHERE id=%s", (t,))


if __name__ == '__main__':
    unittest.main()
