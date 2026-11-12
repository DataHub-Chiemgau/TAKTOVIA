"""WP-M3-50 / LH-6.22-DMG-011: Vertraulichkeit der Schadenakte in der Datenbank. `defect_case` führt keine Gesundheits-/Unfallpersonenfelder; personenbezogene Unfallangaben liegen
getrennt (`defect_personal_detail`) und sind für die Runtime-Rolle nicht lesbar (nur Modulzugang `taktovia_fields`); die Vertraulichkeitsstufe ist am Ticket unveränderlich und
an der Evidenz fest; Mandantentrennung (RLS) gilt auch für den Modulzugang."""
import hashlib
import re
import unittest

import psycopg

from helpers import admin, fields, runtime, set_tenant, tx, uid, seed_tenant

A, B = uid(), uid()
ACTOR = 'user:11111111-1111-4111-8111-111111111111'
VERBOTEN = re.compile(r'health|gesund|injur|verletz|medical|diagnos|illness|krank|person_inj|accident_party|witness|zeuge|insurance', re.I)


def case(c, tenant):
    vid, cid = uid(), uid()
    c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'o','{}'::jsonb)", (tenant, vid))
    c.execute("INSERT INTO taktovia.defect_case(tenant_id,id,case_number,vehicle_id,reported_by,occurred_at,category_code,description,created_by) VALUES (%s,%s,%s,%s,%s,clock_timestamp(),'BODY','x',%s)",
              (tenant, cid, c.execute('SELECT taktovia.next_defect_case_number()').fetchone()[0], vid, uid(), ACTOR))
    return cid


class Vertraulichkeit(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm350-a'); seed_tenant(B, 'm350-b')

    def test_defect_case_ohne_gesundheitsfelder_und_getrennter_bereich(self):
        with admin() as c:
            for table in ('defect_case', 'defect_evidence', 'defect_evidence_upload', 'defect_case_revision', 'defect_status_history'):
                cols = [r[0] for r in c.execute("SELECT column_name FROM information_schema.columns WHERE table_schema='taktovia' AND table_name=%s", (table,))]
                self.assertTrue(cols, table)
                self.assertEqual([x for x in cols if VERBOTEN.search(x)], [], f'{table} fuehrt ein Gesundheits-/Unfallpersonenfeld')
            # personenbezogene Unfallangaben liegen in einer eigenen Tabelle mit eigenem Zugriffsrecht, standardmaessig leer
        # standardmaessig leer: eine neu gemeldete Akte hat keine Unfallangaben
        with tx(A) as c:
            d0 = case(c, A)
        with admin() as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.defect_personal_detail WHERE defect_id=%s", (d0,)).fetchone()[0], 0)
        # Vertraulichkeitsstufe: nur STANDARD/CONFIDENTIAL, Altbestand/Standard = STANDARD
        with tx(A) as c:
            d = case(c, A); u = uid()
            c.execute("INSERT INTO taktovia.defect_evidence_upload(tenant_id,id,uploaded_by,declared_mime,declared_size,declared_sha256,captured_at,quarantine_key,expires_at) "
                      "VALUES (%s,%s,%s,'image/jpeg',100,%s,clock_timestamp(),%s,clock_timestamp()+interval '15 minutes')",
                      (A, u, ACTOR, hashlib.sha256(str(u).encode()).hexdigest(), f'quarantine/defect-evidence/{u}'))
            self.assertEqual(c.execute("SELECT confidentiality FROM taktovia.defect_evidence_upload WHERE id=%s", (u,)).fetchone()[0], 'STANDARD')
        c = runtime()
        try:
            set_tenant(c, A)
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("INSERT INTO taktovia.defect_evidence_upload(tenant_id,id,uploaded_by,declared_mime,declared_size,declared_sha256,captured_at,quarantine_key,expires_at,confidentiality) "
                          "VALUES (%s,%s,%s,'image/jpeg',100,%s,clock_timestamp(),%s,clock_timestamp()+interval '15 minutes','GEHEIM')",
                          (A, uid(), ACTOR, 'a' * 64, 'quarantine/defect-evidence/x'))
        finally:
            c.rollback(); c.close()
        # Stufe nach dem Ausstellen des Tickets unveraenderlich
        with tx(A) as c:
            u = uid()
            c.execute("INSERT INTO taktovia.defect_evidence_upload(tenant_id,id,uploaded_by,declared_mime,declared_size,declared_sha256,captured_at,quarantine_key,expires_at,confidentiality) "
                      "VALUES (%s,%s,%s,'image/jpeg',100,%s,clock_timestamp(),%s,clock_timestamp()+interval '15 minutes','CONFIDENTIAL')", (A, u, ACTOR, 'b' * 64, f'quarantine/defect-evidence/{u}'))
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.defect_evidence_upload SET confidentiality='STANDARD' WHERE id=%s", (u,))

    def test_runtime_rolle_ohne_recht_liest_getrennten_bereich_nicht(self):
        with tx(A) as c:
            d = case(c, A)
        # Modulzugang legt an und liest (mit Mandantenkontext), die Runtime-Rolle darf weder lesen noch schreiben
        f = fields()
        try:
            set_tenant(f, A)
            pid = uid()
            f.execute("INSERT INTO taktovia.defect_personal_detail(tenant_id,id,defect_id,detail_kind,content,recorded_by) VALUES (%s,%s,%s,'WITNESS','Aussage',%s)", (A, pid, d, ACTOR))
            self.assertEqual(f.execute("SELECT content FROM taktovia.defect_personal_detail WHERE id=%s", (pid,)).fetchone()[0], 'Aussage')
            f.commit()
        finally:
            f.close()
        r = runtime()
        try:
            set_tenant(r, A)
            # nur die Schlüsselspalten (tenant_id, id) sind sichtbar; Akte, Art, Inhalt und Urheber nicht
            self.assertEqual(r.execute("SELECT count(*) FROM taktovia.defect_personal_detail").fetchone()[0], 1)
            for stmt in ("SELECT * FROM taktovia.defect_personal_detail", "SELECT content FROM taktovia.defect_personal_detail", "SELECT defect_id FROM taktovia.defect_personal_detail",
                         "SELECT detail_kind, recorded_by FROM taktovia.defect_personal_detail", "SELECT count(*) FROM taktovia.defect_personal_detail WHERE content = 'Aussage'",
                         "UPDATE taktovia.defect_personal_detail SET content='x'", "DELETE FROM taktovia.defect_personal_detail"):
                with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=stmt):
                    r.execute(stmt)
                r.rollback(); set_tenant(r, A)
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                r.execute("INSERT INTO taktovia.defect_personal_detail(tenant_id,id,defect_id,detail_kind,content,recorded_by) VALUES (%s,%s,%s,'WITNESS','x',%s)", (A, uid(), d, ACTOR))
        finally:
            r.rollback(); r.close()
        # Mandantentrennung gilt auch fuer den Modulzugang; der Bereich ist append-only
        f = fields()
        try:
            set_tenant(f, B)
            self.assertEqual(f.execute("SELECT count(*) FROM taktovia.defect_personal_detail").fetchone()[0], 0)
            with self.assertRaises(psycopg.errors.Error):
                f.execute("INSERT INTO taktovia.defect_personal_detail(tenant_id,id,defect_id,detail_kind,content,recorded_by) VALUES (%s,%s,%s,'WITNESS','x',%s)", (A, uid(), d, ACTOR))
            f.rollback()
            set_tenant(f, A)
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                f.execute("UPDATE taktovia.defect_personal_detail SET content='y'")
        finally:
            f.rollback(); f.close()


if __name__ == '__main__':
    unittest.main()
