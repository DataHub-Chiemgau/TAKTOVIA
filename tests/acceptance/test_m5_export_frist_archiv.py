"""WP-M5-19 (AT-25, AT-53, AT-61), Datenbankteil im Akzeptanzgerüst (Zwei-Mandanten-Seed, echte Runtime-Rolle): `export_job` ist mandantengebunden, idempotent und nach dem Ende unveränderlich;
veröffentlichte Dienstplanrevisionen und abgeschlossene Prüfläufe bleiben unverändert (Änderungsversuch scheitert); fremde Mandanten sehen weder Dienstplan noch Prüfung noch Auftrag.

Abgrenzung (ehrlich): Der Dienst- und HTTP-Teil (berechtigter Export, Empfängerrechte, Kategorien mit Status, Datenstand, kurzlebiger Link, Audit ohne Inhalt, 404 für fremde Aufträge) wird von
`backend/modules/analytics/.../ReportJobsTest` und `backend/app/.../ReportJobApiTest` nachgewiesen. **Nicht nachgewiesen:** die Freigabecheckliste des PBefG-Exports (im Pflichtenheft nicht enthalten,
OP-13: Arbeitsannahme), das Löschen nach Fristablauf für die exportierten Datenklassen (Fristen D-11 offen; Prüf- und Zeitnachweise sind nur anhängbar), Export parallel zu einem Löschjob und der Eintrag in
`e2e.csv`. AT-25, AT-53 und AT-61 bleiben deshalb `NOT_IMPLEMENTED` im Katalog."""
import sys
import unittest
import uuid
from pathlib import Path

import psycopg

sys.path.insert(0, str(Path(__file__).resolve().parent))
from taktovia_at import db as atdb, seed  # noqa: E402

DB = "taktovia_at_m5_export"


def setUpModule():
    atdb.reset_database(DB)
    seed.seed_two_tenants(DB)


def tearDownModule():
    atdb.drop_database(DB)


def job(c, tenant, key="k1", kind="INSPECTION_PROOF", requester="user:a1", scope='{}'):
    jid = uuid.uuid4()
    c.execute("""INSERT INTO taktovia.export_job(tenant_id,id,kind,scope,requested_by,requester_scopes,requester_roles,idempotency_key,request_hash,as_of)
                 VALUES (%s,%s,%s,%s::jsonb,%s,'{inspections:read}','{dispatcher}',%s,%s,now())""", (tenant, jid, kind, scope, requester, key, "a" * 64))
    return jid


def finish(c, jid):
    c.execute("UPDATE taktovia.export_job SET state='RUNNING', started_at=now() WHERE id=%s", (jid,))
    c.execute("""UPDATE taktovia.export_job SET state='SUCCEEDED', finished_at=now(), result='{"data":{}}'::jsonb, result_sha256=%s, result_bytes=12, categories='[]'::jsonb WHERE id=%s""", ("b" * 64, jid))


class ExportFristArchiv(unittest.TestCase):
    def test_export_job_ist_mandantengebunden_ohne_kontext_sieht_die_runtime_nichts(self):
        a, b = seed.TENANTS["A"], seed.TENANTS["B"]
        with atdb.tx(DB, a) as c:
            jid = job(c, a, key="iso-1")
        with atdb.tx(DB, b) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.export_job WHERE id=%s", (jid,)).fetchone()[0], 0, "Mandant B sieht den Auftrag von A nicht")
            with self.assertRaises(psycopg.errors.InsufficientPrivilege):
                c.execute("INSERT INTO taktovia.export_job(tenant_id,id,kind,scope,requested_by,requester_scopes,requester_roles,idempotency_key,request_hash,as_of) VALUES (%s,gen_random_uuid(),'TIME_PROOF','{}','user:b1','{}','{}','x',%s,now())", (a, "a" * 64))
        with atdb.tx(DB, None) as c:
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.export_job").fetchone()[0], 0, "ohne Tenantkontext keine Zeile")

    def test_gleicher_idempotenzschluessel_desselben_auftraggebers_ist_eindeutig(self):
        a = seed.TENANTS["A"]
        with atdb.tx(DB, a) as c:
            job(c, a, key="idem-1")
            c.execute("SAVEPOINT s")
            with self.assertRaises(psycopg.errors.UniqueViolation):
                job(c, a, key="idem-1")
            c.execute("ROLLBACK TO SAVEPOINT s")
            job(c, a, key="idem-1", requester="user:a2")     # anderer Auftraggeber: eigener Schlüsselraum

    def test_beendeter_auftrag_ist_unveraenderlich_und_nicht_loeschbar_zustaende_nur_vorwaerts(self):
        a = seed.TENANTS["A"]
        with atdb.tx(DB, a) as c:
            jid = job(c, a, key="life-1")
            with self.assertRaises(psycopg.errors.CheckViolation):
                c.execute("UPDATE taktovia.export_job SET state='SUCCEEDED' WHERE id=%s", (jid,))      # QUEUED -> SUCCEEDED nicht erlaubt
        with atdb.tx(DB, a) as c:
            jid = job(c, a, key="life-2"); finish(c, jid)
        with atdb.tx(DB, a) as c:
            for sql in ("UPDATE taktovia.export_job SET result='{}'::jsonb WHERE id=%s", "UPDATE taktovia.export_job SET as_of=now() WHERE id=%s", "UPDATE taktovia.export_job SET state='QUEUED' WHERE id=%s"):
                c.execute("SAVEPOINT s")
                with self.assertRaises(psycopg.errors.CheckViolation, msg=sql):
                    c.execute(sql, (jid,))
                c.execute("ROLLBACK TO SAVEPOINT s")
            with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InsufficientPrivilege)):
                c.execute("DELETE FROM taktovia.export_job WHERE id=%s", (jid,))

    def test_veroeffentlichte_revision_und_abgeschlossene_pruefung_bleiben_unveraendert_fremder_mandant_sieht_sie_nicht(self):
        a, b = seed.TENANTS["A"], seed.TENANTS["B"]
        region, depot, roster, rev = uuid.uuid4(), uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
        with atdb.tx(DB, a) as c:
            c.execute("SELECT set_config('app.roster_draft_access','on',true)")
            c.execute("INSERT INTO taktovia.region(tenant_id,id,name,created_by) VALUES (%s,%s,'R','user:t')", (a, region))
            c.execute("INSERT INTO taktovia.depot(tenant_id,id,region_id,name,created_by) VALUES (%s,%s,%s,'D','user:t')", (a, depot, region))
            c.execute("INSERT INTO taktovia.roster_revision(tenant_id,id,roster_id,depot_id,week_start,revision,author) VALUES (%s,%s,%s,%s,DATE '2026-09-28',1,'user:planer')", (a, rev, roster, depot))
            c.execute("UPDATE taktovia.roster_revision SET state='PUBLISHED', published_at=now(), published_by='user:planer', effective_from=now(), publish_reason='Freigabe' WHERE id=%s", (rev,))
        with atdb.tx(DB, a) as c:
            for sql in ("UPDATE taktovia.roster_revision SET publish_reason='geändert' WHERE id=%s", "DELETE FROM taktovia.roster_revision WHERE id=%s"):
                c.execute("SAVEPOINT s")
                with self.assertRaises((psycopg.errors.CheckViolation, psycopg.errors.InsufficientPrivilege), msg=sql):
                    c.execute(sql, (rev,))
                c.execute("ROLLBACK TO SAVEPOINT s")
            self.assertEqual(c.execute("SELECT publish_reason FROM taktovia.roster_revision WHERE id=%s", (rev,)).fetchone()[0], "Freigabe", "unverändert lesbar")
        with atdb.tx(DB, b) as c:
            c.execute("SELECT set_config('app.roster_draft_access','on',true)")
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.roster_revision WHERE id=%s", (rev,)).fetchone()[0], 0, "Zugriff aus anderem Mandanten: keine Zeile, kein Hinweis auf Existenz")
            self.assertEqual(c.execute("SELECT count(*) FROM taktovia.inspection_run").fetchone()[0], 0)


if __name__ == "__main__":
    unittest.main()
