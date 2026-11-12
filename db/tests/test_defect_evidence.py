"""WP-M3-44 / LH-6.22-DMG-004: Uploadticket und Evidenz der Schadenakte in der Datenbank unter der echten Runtime-Rolle: Zustand nur vorwärts, Sachfelder unveränderlich,
eine Datei je Urheber/Akte höchstens einmal (Dublettenschutz nach Wiederholung), Evidenz unveränderlich, Mandantenisolation."""
import hashlib
import unittest

import psycopg

from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()
ACTOR = 'user:11111111-1111-4111-8111-111111111111'
H1 = hashlib.sha256(b'foto-1').hexdigest()
H2 = hashlib.sha256(b'foto-2').hexdigest()


def upload(c, tenant, sha=H1, state='ISSUED', actor=ACTOR):
    uid_ = uid()
    c.execute("INSERT INTO taktovia.defect_evidence_upload(tenant_id,id,uploaded_by,declared_mime,declared_size,declared_sha256,captured_at,quarantine_key,state,expires_at) "
              "VALUES (%s,%s,%s,'image/jpeg',100,%s,clock_timestamp(),%s,%s,clock_timestamp()+interval '15 minutes')", (tenant, uid_, actor, sha, f'quarantine/defect-evidence/{uid_}', state))
    return uid_


def case(c, tenant):
    vid, cid = uid(), uid()
    c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'o','{}'::jsonb)", (tenant, vid))
    c.execute("INSERT INTO taktovia.defect_case(tenant_id,id,case_number,vehicle_id,reported_by,occurred_at,category_code,description,created_by) VALUES (%s,%s,%s,%s,%s,clock_timestamp(),'BODY','x',%s)",
              (tenant, cid, c.execute('SELECT taktovia.next_defect_case_number()').fetchone()[0], vid, uid(), ACTOR))
    return cid


class SchadenEvidenz(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm344-a'); seed_tenant(B, 'm344-b')

    def test_zustand_nur_vorwaerts_und_sachfelder_unveraenderlich(self):
        with tx(A) as c:
            u = upload(c, A)
            c.execute("UPDATE taktovia.defect_evidence_upload SET state='IN_REVIEW' WHERE id=%s", (u,))
            self.assertEqual(c.execute("SELECT revision FROM taktovia.defect_evidence_upload WHERE id=%s", (u,)).fetchone()[0], 2)
        with tx(A) as c:   # kein Rücksprung, kein Überspringen der Prüfung
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.defect_evidence_upload SET state='ISSUED' WHERE id=%s", (u,))
        with tx(A) as c:
            u2 = upload(c, A, H2)
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.defect_evidence_upload SET state='RELEASED', object_key='x', stored_sha256=%s, stored_size=1 WHERE id=%s", (H2, u2))
        with tx(A) as c:   # Sachfeld
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.defect_evidence_upload SET declared_sha256=%s WHERE id=%s", (H2, u))
        with tx(A) as c:   # Prüfergebnis endgültig
            c.execute("UPDATE taktovia.defect_evidence_upload SET state='REJECTED', reject_reason='MALWARE' WHERE id=%s", (u,))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.defect_evidence_upload SET state='RELEASED', object_key='x', stored_sha256=%s, stored_size=1 WHERE id=%s", (H2, u))
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                c.execute("DELETE FROM taktovia.defect_evidence_upload")

    def test_eine_datei_je_urheber_und_ungueltige_werte(self):
        with tx(A) as c:
            upload(c, A, hashlib.sha256(b'einmalig').hexdigest())
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation):
                upload(c, A, hashlib.sha256(b'einmalig').hexdigest())
        with tx(A) as c:   # anderer Urheber: zulässig; abgewiesene Datei darf neu hochgeladen werden
            upload(c, A, hashlib.sha256(b'einmalig').hexdigest(), actor='user:22222222-2222-4222-8222-222222222222')
            u = upload(c, A, hashlib.sha256(b'abgewiesen').hexdigest())
            c.execute("UPDATE taktovia.defect_evidence_upload SET state='REJECTED', reject_reason='HASH_MISMATCH' WHERE id=%s", (u,))
            upload(c, A, hashlib.sha256(b'abgewiesen').hexdigest())
        for col, val in (('declared_mime', 'application/x-msdownload'), ('declared_size', 10485761), ('declared_sha256', 'abc')):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation, msg=col):
                    u = uid()
                    vals = {'declared_mime': 'image/png', 'declared_size': 5, 'declared_sha256': H1}; vals[col] = val
                    c.execute("INSERT INTO taktovia.defect_evidence_upload(tenant_id,id,uploaded_by,declared_mime,declared_size,declared_sha256,captured_at,quarantine_key,expires_at) "
                              "VALUES (%s,%s,%s,%s,%s,%s,clock_timestamp(),%s,clock_timestamp()+interval '1 minute')", (A, u, ACTOR, vals['declared_mime'], vals['declared_size'], vals['declared_sha256'], f'q/{u}'))

    def test_evidenz_unveraenderlich_und_je_akte_hoechstens_einmal(self):
        with tx(A) as c:
            d = case(c, A); u = upload(c, A, hashlib.sha256(b'e1').hexdigest())
            e = uid()
            c.execute("INSERT INTO taktovia.defect_evidence(tenant_id,id,defect_id,media_ref,sha256,captured_at,created_by,mime,size_bytes,upload_id) VALUES (%s,%s,%s,'defect-evidence-x',%s,clock_timestamp(),%s,'image/jpeg',10,%s)",
                      (A, e, d, H1, ACTOR, u))
        with tx(A) as c:   # die Runtime-Rolle hat weder UPDATE noch DELETE; die Trigger sperren beides auch für die Administration
            for sql in ("UPDATE taktovia.defect_evidence SET media_ref='anders' WHERE id=%s", "DELETE FROM taktovia.defect_evidence WHERE id=%s"):
                with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                    c.execute(sql, (e,))
                c.connection.rollback()
        for sql in ("UPDATE taktovia.defect_evidence SET media_ref='anders' WHERE id=%s", "DELETE FROM taktovia.defect_evidence WHERE id=%s"):
            with admin() as a:
                with self.assertRaises(psycopg.errors.CheckViolation):
                    a.execute(sql, (e,))
        with tx(A) as c:   # dieselbe Datei an dieselbe Akte: ein Evidenzeintrag
            with self.assertRaises(psycopg.errors.UniqueViolation):
                c.execute("INSERT INTO taktovia.defect_evidence(tenant_id,id,defect_id,media_ref,sha256,captured_at,created_by) VALUES (%s,%s,%s,'defect-evidence-y',%s,clock_timestamp(),%s)",
                          (A, uid(), d, H1, ACTOR))

    def test_rls_mandantenisolation(self):
        with tx(A) as c: u = upload(c, A, hashlib.sha256(b'iso').hexdigest())
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.defect_evidence_upload WHERE id=%s", (u,)).fetchone()[0], 0)
        with tx(None) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.defect_evidence_upload").fetchone()[0], 0)


if __name__ == '__main__':
    unittest.main()
