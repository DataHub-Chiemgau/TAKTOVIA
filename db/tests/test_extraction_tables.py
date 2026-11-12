"""WP-M4-27 (DOC-002): extraction_run und extracted_field unter der echten Runtime-Rolle: Mandantentrennung, ein Lauf je Beleg und OCR-Version, Zustandsübergänge und Fencing,
unveränderlicher OCR-Vorschlag, Bezug der menschlichen Entscheidung auf den Vorschlag."""
import threading, unittest
import psycopg
from helpers import tx, uid, seed_tenant
from m4_belege_helpers import legal_entity, stored_document

A, B = uid(), uid()


def run(c, tenant, doc, engine='scripted', version='1', status='QUEUED'):
    rid = uid()
    c.execute("INSERT INTO taktovia.extraction_run(tenant_id,id,document_id,document_revision,engine,ocr_version,status,requested_by) VALUES (%s,%s,%s,1,%s,%s,%s,'u')", (tenant, rid, doc, engine, version, status))
    return rid


def field(c, tenant, doc, rid, kind='AMOUNT', origin='OCR', status='PROPOSED', corrects=None, norm='8900', by=None):
    fid = uid()
    c.execute("INSERT INTO taktovia.extracted_field(tenant_id,id,document_id,run_id,field_kind,origin,status,normalized_value,corrects_field_id,decided_by,decided_at) "
              "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)", (tenant, fid, doc, rid, kind, origin, status, norm, corrects, by, 'now' if by else None))
    return fid


class Extraktion(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm427-a'); seed_tenant(B, 'm427-b')
        cls.le = legal_entity(A)

    def test_ein_lauf_je_beleg_und_ocr_version_und_mandantentrennung(self):
        d = stored_document(A, self.le)
        with tx(A) as c: rid = run(c, A, d)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation): run(c, A, d)
        with tx(A) as c: run(c, A, d, version='2')   # andere OCR-Version: eigener Lauf
        with tx(B) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.extraction_run').fetchone()[0], 0)
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): run(c, A, d, version='3')
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): c.execute('DELETE FROM taktovia.extraction_run WHERE id=%s', (rid,))

    def test_zustandsuebergaenge_und_fencing(self):
        d = stored_document(A, self.le)
        with tx(A) as c: rid = run(c, A, d)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.extraction_run SET status='COMPLETED', finished_at=now() WHERE id=%s", (rid,))   # QUEUED -> COMPLETED verboten
        with tx(A) as c: c.execute("UPDATE taktovia.extraction_run SET status='RUNNING', attempt=1, lease_until=now() WHERE id=%s", (rid,))
        with tx(A) as c:   # Übernahme nach abgelaufener Lease: attempt steigt ...
            c.execute("UPDATE taktovia.extraction_run SET attempt=2, lease_until=now() WHERE id=%s", (rid,))
        with tx(A) as c:   # ... und sinkt nie
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.extraction_run SET attempt=1 WHERE id=%s", (rid,))
        # Fencing: nur der aktuelle Versuch darf abschließen
        with tx(A) as c:
            self.assertEqual(c.execute("UPDATE taktovia.extraction_run SET status='COMPLETED', finished_at=now(), field_count=0 WHERE id=%s AND status='RUNNING' AND attempt=1", (rid,)).rowcount, 0)
            self.assertEqual(c.execute("UPDATE taktovia.extraction_run SET status='COMPLETED', finished_at=now(), field_count=0 WHERE id=%s AND status='RUNNING' AND attempt=2", (rid,)).rowcount, 1)
        with tx(A) as c:   # abgeschlossen ist endgültig
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.extraction_run SET status='RUNNING' WHERE id=%s", (rid,))
        with tx(A) as c: r2 = run(c, A, d, version='9', status='RUNNING')
        with tx(A) as c:   # Fehler braucht einen Code, Wiederholung aus FAILED
            with self.assertRaises(psycopg.errors.CheckViolation): c.execute("UPDATE taktovia.extraction_run SET status='FAILED', finished_at=now() WHERE id=%s", (r2,))
        with tx(A) as c:
            c.execute("UPDATE taktovia.extraction_run SET status='FAILED', failure_code='TIMEOUT', finished_at=now() WHERE id=%s", (r2,))
        with tx(A) as c: self.assertEqual(c.execute("UPDATE taktovia.extraction_run SET status='RUNNING', attempt=2, failure_code=NULL, finished_at=NULL WHERE id=%s", (r2,)).rowcount, 1)

    def test_konkurrierendes_beanspruchen_genau_ein_gewinner(self):
        d = stored_document(A, self.le)
        with tx(A) as c: rid = run(c, A, d)
        wins = []
        def go():
            with tx(A) as c:
                wins.append(c.execute("UPDATE taktovia.extraction_run SET status='RUNNING', attempt=attempt+1, lease_until=now() + interval '1 minute' WHERE id=%s AND status='QUEUED'", (rid,)).rowcount)
        ts = [threading.Thread(target=go) for _ in range(20)]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual(sum(wins), 1)

    def test_ocr_vorschlag_ist_unveraenderlich_und_die_entscheidung_verweist_auf_ihn(self):
        d = stored_document(A, self.le)
        with tx(A) as c:
            rid = run(c, A, d); proposal = field(c, A, d, rid)
        for stmt in ("UPDATE taktovia.extracted_field SET normalized_value='3900' WHERE id=%s", "DELETE FROM taktovia.extracted_field WHERE id=%s"):
            with tx(A) as c:
                with self.assertRaises((psycopg.errors.InsufficientPrivilege, psycopg.errors.CheckViolation), msg=stmt): c.execute(stmt, (proposal,))
        with tx(A) as c: field(c, A, d, rid, origin='HUMAN', status='CORRECTED', corrects=proposal, norm='3900', by='u')
        for kw in (dict(origin='HUMAN', status='CORRECTED', corrects=None, by='u'), dict(origin='HUMAN', status='PROPOSED', corrects=proposal, by='u'), dict(origin='OCR', status='CORRECTED'),
                   dict(origin='OCR', status='PROPOSED', corrects=proposal), dict(origin='HUMAN', status='CONFIRMED', corrects=proposal, by=None)):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation, msg=str(kw)): field(c, A, d, rid, **kw)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): field(c, A, d, rid, kind='UNBEKANNT')
        with tx(B) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.extracted_field').fetchone()[0], 0)
        with tx(A) as c:   # Vorschlag und Entscheidung bleiben nebeneinander lesbar
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.extracted_field WHERE document_id=%s AND field_kind='AMOUNT'", (d,)).fetchone()[0], 2)


if __name__ == '__main__':
    unittest.main()
