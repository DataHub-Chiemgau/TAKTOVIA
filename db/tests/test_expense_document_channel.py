"""WP-M4-22 (DOC-006): Herkunftskanal, Belegart, Vorgangsbezug und Eingangszeit am Beleg unter der echten Runtime-Rolle; Mandantentrennung der Einstellungen."""
import unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant
from m4_belege_helpers import legal_entity, ticket, document, stored_document

A, B = uid(), uid()
CHANNELS = ('DRIVER_APP', 'DISPATCH_WEB', 'EMAIL_INBOUND', 'INVOICE_IMPORT')


class BelegKanal(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm422-a'); seed_tenant(B, 'm422-b')
        cls.le_a, cls.le_b = legal_entity(A), legal_entity(B)

    def test_kanal_und_eingangszeit_pflicht(self):  # AK 1: gesetzter Herkunftskanal und Eingangszeit
        for kw, exc in ((dict(source_channel=None), psycopg.errors.NotNullViolation), (dict(source_channel='FAX'), psycopg.errors.CheckViolation),
                        (dict(document_kind=None), psycopg.errors.NotNullViolation), (dict(document_kind='SONSTIGES'), psycopg.errors.CheckViolation)):
            with tx(A) as c:
                t = ticket(c, A, self.le_a)
                with self.assertRaises(exc, msg=str(kw)): document(c, A, self.le_a, t, **kw)
        with tx(A) as c:   # Eingangszeit NOT NULL
            t = ticket(c, A, self.le_a); did = uid()
            with self.assertRaises(psycopg.errors.NotNullViolation):
                c.execute("INSERT INTO taktovia.expense_document(tenant_id,id,legal_entity_id,object_key,original_sha256,mime,state,revision,created_by,original_received_at,upload_ticket_id) "
                          "VALUES (%s,%s,%s,%s,%s,'image/jpeg','QUARANTINED',1,'u',NULL,%s)", (A, did, self.le_a, f'k/{did}', 'a' * 64, t))

    def test_alle_vier_kanaele_gleiche_struktur(self):  # AK 1 (Datenbankteil): je Kanal ein Beleg mit denselben Spalten
        shapes = set()
        for ch in CHANNELS:
            email = ch == 'EMAIL_INBOUND'
            with tx(A) as c:
                t = ticket(c, A, self.le_a, channel=ch, source_ref=f'ref-{ch}-{uid()}', sender_verified=True if email else None)
                d = document(c, A, self.le_a, t, source_channel=ch, **({'sender_verified': True} if email else {}))
                row = c.execute('SELECT source_channel, document_kind, original_received_at IS NOT NULL, state FROM taktovia.expense_document WHERE id=%s', (d,)).fetchone()
            self.assertEqual(row[0], ch); self.assertTrue(row[2])
            shapes.add((row[1], row[3]))
        self.assertEqual(shapes, {('RECEIPT', 'QUARANTINED')})

    def test_absenderpruefung_nur_beim_email_eingang(self):  # AK 3 (Datenbankteil)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): ticket(c, A, self.le_a, channel='EMAIL_INBOUND')            # E-Mail ohne Absenderergebnis
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): ticket(c, A, self.le_a, channel='DRIVER_APP', sender_verified=False)

    def test_kanal_ist_nach_der_anlage_unveraenderlich(self):
        d = stored_document(A, self.le_a)
        for stmt in ("UPDATE taktovia.expense_document SET source_channel='EMAIL_INBOUND', sender_verified=true WHERE id=%s", "UPDATE taktovia.expense_document SET document_kind='INVOICE' WHERE id=%s",
                     "UPDATE taktovia.expense_document SET source_ref='x' WHERE id=%s"):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation, msg=stmt): c.execute(stmt, (d,))
        with tx(A) as c:   # Der Vorgangsbezug darf später zugeordnet werden
            self.assertEqual(c.execute("UPDATE taktovia.expense_document SET case_ref_kind='VEHICLE', case_ref_id=%s WHERE id=%s", (uid(), d)).rowcount, 1)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.expense_document SET case_ref_kind=NULL WHERE id=%s", (d,))   # Art und ID nur zusammen

    def test_gleiche_quelle_hoechstens_ein_ticket_und_ein_beleg(self):  # konkurrierend: dieselbe Zustellung zweimal
        ref = f'<msg-{uid()}@mail.test>'
        with tx(A) as c: t = ticket(c, A, self.le_a, channel='EMAIL_INBOUND', source_ref=ref, sender_verified=True)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation): ticket(c, A, self.le_a, channel='EMAIL_INBOUND', source_ref=ref, sender_verified=True)
        with tx(A) as c:   # ein abgewiesenes Ticket gibt die Quelle wieder frei
            c.execute("UPDATE taktovia.upload_ticket SET state='COMPLETING' WHERE id=%s", (t,))
            c.execute("UPDATE taktovia.upload_ticket SET state='REJECTED', reject_reason='HASH_MISMATCH' WHERE id=%s", (t,))
        with tx(A) as c: ticket(c, A, self.le_a, channel='EMAIL_INBOUND', source_ref=ref, sender_verified=True)
        with tx(B) as c:   # andere Mandanten haben ihren eigenen Raum
            ticket(c, B, self.le_b, channel='EMAIL_INBOUND', source_ref=ref, sender_verified=True)

    def test_einstellungen_mandantengetrennt_und_geprueft(self):
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.document_settings(tenant_id,receipt_ocr_enabled,invoice_email_inbound,verified_sender_domains,updated_by) VALUES (%s,false,'rechnung@firma.test','{firma.test}','u')", (A,))
        with tx(B) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.document_settings').fetchone()[0], 0)
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): c.execute("INSERT INTO taktovia.document_settings(tenant_id,updated_by) VALUES (%s,'u')", (A,))
        with tx(B) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("INSERT INTO taktovia.document_settings(tenant_id,invoice_email_inbound,updated_by) VALUES (%s,'kein-mail','u')", (B,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): c.execute('DELETE FROM taktovia.document_settings')


if __name__ == '__main__':
    unittest.main()
