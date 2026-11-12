"""WP-M4-28 (DOC-003): OCR-Vorschlag bleibt unveränderlich, Markierung (flag_reason) nur am Vorschlag, Pflichtfeld-Einstellung mit geschützten Feldern,
Feldart NET_AMOUNT. Echte Datenbank unter der Runtime-Rolle."""
import unittest
import psycopg
from helpers import tx, uid, seed_tenant
from m4_belege_helpers import legal_entity, stored_document
from test_extraction_tables import run, field

A = uid()


def flagged(c, tenant, doc, rid, kind='AMOUNT', flag='LOW_CONFIDENCE', norm='8900'):
    fid = uid()
    c.execute("INSERT INTO taktovia.extracted_field(tenant_id,id,document_id,run_id,field_kind,origin,status,normalized_value,confidence,flag_reason) VALUES (%s,%s,%s,%s,%s,'OCR','PROPOSED',%s,0.4,%s)",
              (tenant, fid, doc, rid, kind, norm, flag))
    return fid


class ExtractedFieldBestaetigung(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm428-a')
        cls.le = legal_entity(A)

    def test_ocr_vorschlag_bleibt_unveraendert(self):
        d = stored_document(A, self.le)
        with tx(A) as c: rid = run(c, A, d, status='RUNNING'); p = flagged(c, A, d, rid)
        # Korrektur 89,00 -> 39,00 ist eine eigene Zeile mit Bezug auf den Vorschlag
        with tx(A) as c: h = field(c, A, d, rid, origin='HUMAN', status='CORRECTED', corrects=p, norm='3900', by='u-1')
        for stmt in ("UPDATE taktovia.extracted_field SET normalized_value='3900' WHERE id=%s", "UPDATE taktovia.extracted_field SET flag_reason=NULL WHERE id=%s",
                     "UPDATE taktovia.extracted_field SET status='CONFIRMED' WHERE id=%s", "DELETE FROM taktovia.extracted_field WHERE id=%s"):
            with tx(A) as c:
                with self.assertRaises((psycopg.errors.InsufficientPrivilege, psycopg.errors.RaiseException, psycopg.errors.CheckViolation)):
                    c.execute(stmt, (p,))
        with tx(A) as c:
            self.assertEqual(c.execute('SELECT normalized_value, flag_reason FROM taktovia.extracted_field WHERE id=%s', (p,)).fetchone(), ('8900', 'LOW_CONFIDENCE'))
            self.assertEqual(c.execute('SELECT normalized_value, flag_reason, corrects_field_id FROM taktovia.extracted_field WHERE id=%s', (h,)).fetchone(), ('3900', None, p))

    def test_flag_reason_nur_bekannte_werte_und_nur_am_vorschlag(self):
        d = stored_document(A, self.le)
        with tx(A) as c: rid = run(c, A, d, status='RUNNING'); p = flagged(c, A, d, rid, flag='SUM_MISMATCH')
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): flagged(c, A, d, rid, kind='MERCHANT', flag='EGAL')
        with tx(A) as c:   # eine menschliche Entscheidung trägt keine Markierung
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("INSERT INTO taktovia.extracted_field(tenant_id,id,document_id,run_id,field_kind,origin,status,normalized_value,corrects_field_id,decided_by,decided_at,flag_reason) "
                          "VALUES (%s,%s,%s,%s,'AMOUNT','HUMAN','CONFIRMED','8900',%s,'u-1',now(),'LOW_CONFIDENCE')", (A, uid(), d, rid, p))

    def test_feldart_nettobetrag(self):
        d = stored_document(A, self.le)
        with tx(A) as c: rid = run(c, A, d, status='RUNNING'); field(c, A, d, rid, kind='NET_AMOUNT', norm='3278')
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): field(c, A, d, rid, kind='GEWICHT')

    def test_pflichtfelder_geschuetzt(self):
        t = uid(); seed_tenant(t, 'm428-s')
        base = "INSERT INTO taktovia.document_settings(tenant_id, updated_by, receipt_mandatory_fields) VALUES (%s,'u',CAST(%s AS text[]))"
        with tx(t) as c:   # Vorgabe: die steuerlich notwendigen Felder
            c.execute("INSERT INTO taktovia.document_settings(tenant_id, updated_by) VALUES (%s,'u')", (t,))
            self.assertEqual(sorted(c.execute('SELECT receipt_mandatory_fields FROM taktovia.document_settings').fetchone()[0]), ['amount_minor', 'currency', 'document_date', 'merchant'])
            c.execute("UPDATE taktovia.document_settings SET receipt_mandatory_fields = '{amount_minor,currency,document_date,merchant,document_number}'")   # erweitern ist erlaubt
        with tx(t) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.document_settings SET receipt_mandatory_fields = '{amount_minor,currency,merchant}'")   # Datum entfernt
        with tx(t) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.document_settings SET receipt_mandatory_fields = '{amount_minor,currency,document_date,merchant,gewicht}'")


if __name__ == '__main__':
    unittest.main()
