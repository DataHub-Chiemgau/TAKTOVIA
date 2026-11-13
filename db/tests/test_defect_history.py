"""WP-M3-48 / LH-6.22-DMG-008: unveränderliche Historie von Meldungen und Anhängen in der Datenbank unter der echten Runtime-Rolle. Originalabnahme: vorherige Inhalte bleiben lesbar;
UPDATE/DELETE auf Meldungs-, Revisions- und Evidenzzeilen scheitern auf Datenbankebene; Nachträge haben fortlaufende Versionen; ein ersetzter Anhang bleibt erhalten."""
import hashlib
import json
import threading
import unittest

import psycopg

from helpers import admin, tx, uid, seed_tenant

A, B = uid(), uid()
ACTOR = 'user:11111111-1111-4111-8111-111111111111'


def case(c, tenant=A):
    vid, cid = uid(), uid()
    c.execute("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (%s,%s,'o','{}'::jsonb)", (tenant, vid))
    c.execute("INSERT INTO taktovia.defect_case(tenant_id,id,case_number,vehicle_id,reported_by,occurred_at,category_code,description,created_by) VALUES (%s,%s,%s,%s,%s,clock_timestamp(),'BODY','Kratzer',%s)",
              (tenant, cid, c.execute('SELECT taktovia.next_defect_case_number()').fetchone()[0], vid, uid(), ACTOR))
    revision(c, cid, 1, tenant, 'Erstmeldung')
    return cid


def revision(c, cid, version, tenant=A, reason='Nachtrag', snapshot=None):
    c.execute("INSERT INTO taktovia.defect_case_revision(tenant_id,id,defect_id,version,snapshot,author,change_reason) VALUES (%s,%s,%s,%s,%s::jsonb,%s,%s)",
              (tenant, uid(), cid, version, json.dumps(snapshot or {'description': f'v{version}'}), ACTOR, reason))


def evidence(c, cid, label, replaces=None, tenant=A):
    eid = uid(); sha = hashlib.sha256(label.encode()).hexdigest()
    c.execute("INSERT INTO taktovia.defect_evidence(tenant_id,id,defect_id,media_ref,sha256,captured_at,created_by,replaces_evidence_id) VALUES (%s,%s,%s,%s,%s,clock_timestamp(),%s,%s)",
              (tenant, eid, cid, f'defect-evidence-{eid}', sha, ACTOR, replaces))
    return eid


class SchadenHistorie(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        seed_tenant(A, 'm348-a'); seed_tenant(B, 'm348-b')

    def test_update_delete_auf_revision_und_evidenz_scheitert(self):
        with tx(A) as c:
            cid = case(c); eid = evidence(c, cid, 'e1')
            rid = c.execute("SELECT id FROM taktovia.defect_case_revision WHERE defect_id=%s", (cid,)).fetchone()[0]
        # Runtime-Rolle: kein UPDATE und kein DELETE (Rechte), auch nicht auf der Meldung selbst
        for sql, arg in (("UPDATE taktovia.defect_case_revision SET change_reason='x' WHERE id=%s", rid), ("DELETE FROM taktovia.defect_case_revision WHERE id=%s", rid),
                         ("UPDATE taktovia.defect_evidence SET media_ref='x' WHERE id=%s", eid), ("DELETE FROM taktovia.defect_evidence WHERE id=%s", eid),
                         ("DELETE FROM taktovia.defect_case WHERE id=%s", cid)):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.InsufficientPrivilege, msg=sql):
                    c.execute(sql, (arg,))
        # auch die Administration scheitert an den Triggern (SQLSTATE 23514)
        for sql, arg in (("UPDATE taktovia.defect_case_revision SET change_reason='x' WHERE id=%s", rid), ("DELETE FROM taktovia.defect_case_revision WHERE id=%s", rid),
                         ("UPDATE taktovia.defect_evidence SET media_ref='x' WHERE id=%s", eid), ("DELETE FROM taktovia.defect_evidence WHERE id=%s", eid),
                         ("DELETE FROM taktovia.defect_case WHERE id=%s", cid)):
            with admin() as a:
                with self.assertRaises(psycopg.errors.CheckViolation, msg=sql):
                    a.execute(sql, (arg,))

    def test_meldungsinhalt_nur_mit_revisionszeile_und_identitaet_unveraenderlich(self):
        with tx(A) as c:
            cid = case(c)
            with self.assertRaises(psycopg.errors.CheckViolation):      # Inhalt direkt ändern: abgewiesen
                c.execute("UPDATE taktovia.defect_case SET description='ueberschrieben' WHERE id=%s", (cid,))
        with tx(A) as c:
            cid = case(c)
            with self.assertRaises(psycopg.errors.CheckViolation):      # Versionssprung ohne Revisionszeile
                c.execute("UPDATE taktovia.defect_case SET description='neu', content_version=2 WHERE id=%s", (cid,))
        with tx(A) as c:
            cid = case(c)
            with self.assertRaises(psycopg.errors.CheckViolation):      # Version 3 statt 2, obwohl Zeile 3 existiert
                revision(c, cid, 3)
                c.execute("UPDATE taktovia.defect_case SET description='neu', content_version=3 WHERE id=%s", (cid,))
        for col, val in (("case_number", "'SCH-1999-000001'"), ("reported_at", "now()"), ("reported_by", "gen_random_uuid()"), ("created_by", "'user:x'")):
            with tx(A) as c:
                cid = case(c)
                with self.assertRaises(psycopg.errors.CheckViolation, msg=col):
                    c.execute(f"UPDATE taktovia.defect_case SET {col}={val} WHERE id=%s", (cid,))
        with tx(A) as c:   # mit Revisionszeile gelingt der Nachtrag; frühere Fassung bleibt lesbar
            cid = case(c)
            revision(c, cid, 2, reason='Beschreibung präzisiert', snapshot={'description': 'Kratzer links'})
            c.execute("UPDATE taktovia.defect_case SET description='Kratzer links', content_version=2, revision=revision+1 WHERE id=%s", (cid,))
            rows = c.execute("SELECT version, snapshot->>'description' FROM taktovia.defect_case_revision WHERE defect_id=%s ORDER BY version", (cid,)).fetchall()
            self.assertEqual(rows, [(1, 'v1'), (2, 'Kratzer links')])
        with tx(A) as c:   # Statuswechsel und Zuständigkeit bleiben ohne Version möglich
            cid = case(c)
            c.execute("UPDATE taktovia.defect_case SET severity='COSMETIC', severity_rule_version='test-v1', severity_source='TRIAGE', assignee_ref=gen_random_uuid(), updated_at=clock_timestamp() WHERE id=%s", (cid,))

    def test_version_unique_je_akte(self):
        with tx(A) as c:
            cid = case(c)
            revision(c, cid, 2)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.UniqueViolation):
                revision(c, cid, 2)
        # Version 0, leerer oder blanker Grund und Nicht-Objekt-Snapshot sind nicht speicherbar
        for version, reason, snap in ((0, 'x', {}), (3, '', {}), (3, '   ', {})):
            with tx(A) as c:
                with self.assertRaises(psycopg.errors.CheckViolation, msg=f'{version}/{reason!r}'):
                    revision(c, cid, version, reason=reason, snapshot=snap)
        with tx(A) as c:
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("INSERT INTO taktovia.defect_case_revision(tenant_id,id,defect_id,version,snapshot,author,change_reason) VALUES (%s,%s,%s,9,'[]'::jsonb,%s,'x')", (A, uid(), cid, ACTOR))
        # parallele Vergabe derselben Version: genau eine
        with tx(A) as c: cid2 = case(c)
        results = []
        barrier = threading.Barrier(5)

        def work():
            try:
                barrier.wait()
                with tx(A) as c: revision(c, cid2, 2)
                results.append('ok')
            except psycopg.errors.UniqueViolation:
                results.append('dup')
        ts = [threading.Thread(target=work) for _ in range(5)]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.assertEqual(sorted(results), ['dup'] * 4 + ['ok'])

    def test_ersetzter_anhang_bleibt_erhalten_und_wird_hoechstens_einmal_ersetzt(self):
        with tx(A) as c:
            cid = case(c); e1 = evidence(c, cid, 'alt'); e2 = evidence(c, cid, 'neu', replaces=e1)
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.defect_evidence WHERE defect_id=%s", (cid,)).fetchone()[0], 2)
            self.assertEqual(c.execute("SELECT replaces_evidence_id FROM taktovia.defect_evidence WHERE id=%s", (e2,)).fetchone()[0], e1)
        with tx(A) as c:   # derselbe Vorgänger kein zweites Mal ersetzbar
            with self.assertRaises(psycopg.errors.UniqueViolation):
                evidence(c, cid, 'noch-neuer', replaces=e1)
        with tx(A) as c:   # Vorgänger eines anderen Mandanten ist nicht referenzierbar
            with self.assertRaises(psycopg.errors.ForeignKeyViolation):
                evidence(c, cid, 'fremd', replaces=uid())

    def test_rls_mandantenisolation_der_revisionen(self):
        with tx(A) as c: cid = case(c)
        with tx(B) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.defect_case_revision WHERE defect_id=%s", (cid,)).fetchone()[0], 0)
        with tx(None) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.defect_case_revision").fetchone()[0], 0)


if __name__ == '__main__':
    unittest.main()
