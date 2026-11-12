"""WP-M4-21: upload_ticket und die Quarantäne-Erweiterung von expense_document unter der echten Runtime-Rolle: Mandantentrennung, einmalige Verbrauchbarkeit
des Tickets (auch bei parallelem Abschluss), Unveränderlichkeit des Originals, endgültige Ablehnung aus der Quarantäne."""
import threading, unittest
import psycopg
from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()
SHA = 'a' * 64


def legal_entity(tenant):
    le = uid()
    with admin() as c:
        c.execute("INSERT INTO taktovia.legal_entity(tenant_id,id,name,country) VALUES (%s,%s,'Test GmbH','DE')", (tenant, le))
    return le


def ticket(c, tenant, le, state='ISSUED', expires="now() + interval '15 minutes'", tid=None, mime='image/jpeg', size=1000, key=None):
    tid = tid or uid()
    c.execute(f"INSERT INTO taktovia.upload_ticket(tenant_id,id,legal_entity_id,purpose,declared_mime,declared_size,declared_sha256,quarantine_key,state,issued_by,expires_at) "
              f"VALUES (%s,%s,%s,'RECEIPT',%s,%s,%s,%s,%s,'u-1',{expires})", (tenant, tid, le, mime, size, SHA, key or f'receipts/quarantine/{tid}', state))
    return tid


def document(c, tenant, le, tid, state='QUARANTINED'):
    did = uid()
    c.execute("INSERT INTO taktovia.expense_document(tenant_id,id,legal_entity_id,object_key,original_sha256,mime,state,revision,created_by,original_received_at,upload_ticket_id,size_bytes) "
              "VALUES (%s,%s,%s,%s,%s,'image/jpeg',%s,1,'u-1',now(),%s,1000)", (tenant, did, le, f'receipts/originals/{did}', SHA, state, tid))
    return did


class UploadTicket(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm421-a'); seed_tenant(B, 'm421-b')
        cls.le_a, cls.le_b = legal_entity(A), legal_entity(B)

    def test_rls_und_zusammengesetzter_schluessel(self):  # AK 3 (Datenbankteil)
        with tx(A) as c: t = ticket(c, A, self.le_a)
        with tx(B) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.upload_ticket WHERE id=%s', (t,)).fetchone()[0], 0)
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): ticket(c, A, self.le_a)   # WITH CHECK
        with tx(B) as c:
            with self.assertRaises(psycopg.errors.ForeignKeyViolation): ticket(c, B, self.le_a)    # Rechtsträger des Mandanten A
        with tx(None) as c:
            self.assertEqual(c.execute('SELECT count(*) FROM taktovia.upload_ticket').fetchone()[0], 0)

    def test_pruefungen_am_ticket(self):  # AK 1 (Größe, MIME, Hash im Ticket)
        for kw in (dict(mime='application/x-msdownload'), dict(size=0), dict(size=-5), dict(state='DONE'), dict(key='../x')):
            with tx(A) as c:
                with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.NotNullViolation), msg=str(kw)): ticket(c, A, self.le_a, **kw)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation): ticket(c, A, self.le_a, expires="now() - interval '1 minute'")   # Ablauf liegt nach der Ausstellung

    def test_ticket_ist_einmal_verbrauchbar_und_endgueltig(self):  # AK 5
        with tx(A) as c: t = ticket(c, A, self.le_a)
        with tx(A) as c:
            self.assertEqual(c.execute("UPDATE taktovia.upload_ticket SET state='COMPLETING' WHERE id=%s AND state='ISSUED' AND expires_at > now()", (t,)).rowcount, 1)
        with tx(A) as c:
            self.assertEqual(c.execute("UPDATE taktovia.upload_ticket SET state='COMPLETING' WHERE id=%s AND state='ISSUED' AND expires_at > now()", (t,)).rowcount, 0)
            with self.assertRaises(psycopg.errors.CheckViolation):   # Rückweg ISSUED verboten
                c.execute("UPDATE taktovia.upload_ticket SET state='ISSUED' WHERE id=%s", (t,))
        with tx(A) as c:
            d = document(c, A, self.le_a, t)
            c.execute("UPDATE taktovia.upload_ticket SET state='CONSUMED', document_id=%s WHERE id=%s", (d, t))
        with tx(A) as c:
            for stmt in ("UPDATE taktovia.upload_ticket SET state='ISSUED' WHERE id=%s", "UPDATE taktovia.upload_ticket SET declared_size=1 WHERE id=%s",
                         "UPDATE taktovia.upload_ticket SET expires_at=expires_at + interval '1 day' WHERE id=%s"):
                with self.assertRaises(psycopg.errors.CheckViolation, msg=stmt):
                    with c.transaction(): c.execute(stmt, (t,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege): c.execute('DELETE FROM taktovia.upload_ticket WHERE id=%s', (t,))

    def test_ein_ticket_hoechstens_ein_dokument(self):  # AK 5 (Datenbankebene)
        with tx(A) as c:
            t = ticket(c, A, self.le_a); document(c, A, self.le_a, t)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation): document(c, A, self.le_a, t)

    def test_konkurrierender_abschluss_genau_ein_gewinner(self):  # konkurrierend (Sicherheit)
        with tx(A) as c: t = ticket(c, A, self.le_a)
        wins = []
        def go():
            with tx(A) as c:
                wins.append(c.execute("UPDATE taktovia.upload_ticket SET state='COMPLETING' WHERE id=%s AND state='ISSUED' AND expires_at > now()", (t,)).rowcount)
        threads = [threading.Thread(target=go) for _ in range(40)]
        [x.start() for x in threads]; [x.join() for x in threads]
        self.assertEqual(sum(wins), 1); self.assertEqual(len(wins), 40)

    def test_original_ist_unveraenderlich_und_ablehnung_endgueltig(self):  # AK 2, 4
        with tx(A) as c:
            t = ticket(c, A, self.le_a); d = document(c, A, self.le_a, t)
        for stmt in ("UPDATE taktovia.expense_document SET original_sha256=%s WHERE id=%s", "UPDATE taktovia.expense_document SET object_key='x/y' WHERE id=%s",
                     "UPDATE taktovia.expense_document SET mime='application/pdf' WHERE id=%s", "UPDATE taktovia.expense_document SET size_bytes=5 WHERE id=%s",
                     "UPDATE taktovia.expense_document SET created_by='evil' WHERE id=%s", "UPDATE taktovia.expense_document SET original_received_at=now() - interval '1 year' WHERE id=%s",
                     "UPDATE taktovia.expense_document SET state='APPROVED' WHERE id=%s"):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation, msg=stmt):
                    c.execute(stmt, ('b' * 64, d) if '%s,' in stmt or stmt.count('%s') == 2 else (d,))
        with tx(A) as c:   # Quarantäne bestanden
            c.execute("UPDATE taktovia.expense_document SET state='STORED', scan_engine='clamd', scanned_at=now() WHERE id=%s", (d,))
        with tx(A) as c:
            t2 = ticket(c, A, self.le_a); d2 = document(c, A, self.le_a, t2)
            c.execute("UPDATE taktovia.expense_document SET state='QUARANTINE_REJECTED', scan_engine='clamd', scanned_at=now(), scan_signature='Eicar-Test-Signature' WHERE id=%s", (d2,))
        with tx(A) as c:   # abgelehnt bleibt abgelehnt: nie freigeben
            for stmt in ("UPDATE taktovia.expense_document SET state='STORED' WHERE id=%s", "UPDATE taktovia.expense_document SET state='QUARANTINED' WHERE id=%s"):
                with self.assertRaises(psycopg.errors.CheckViolation, msg=stmt):
                    with c.transaction(): c.execute(stmt, (d2,))

    def test_bestehende_belege_ohne_ticket_bleiben_unberuehrt(self):  # expand/contract: neue Spalten nullbar
        did = uid()
        with tx(A) as c:
            c.execute("INSERT INTO taktovia.expense_document(tenant_id,id,legal_entity_id,object_key,original_sha256,mime,state,revision,created_by,original_received_at) "
                      "VALUES (%s,%s,%s,%s,%s,'application/pdf','NEW',1,'u',now())", (A, did, self.le_a, f'legacy/{did}', SHA))
        with tx(A) as c:
            c.execute("UPDATE taktovia.expense_document SET state='APPROVED', revision=2 WHERE id=%s", (did,))


if __name__ == '__main__':
    unittest.main()
