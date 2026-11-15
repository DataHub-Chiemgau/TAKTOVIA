"""Gate-Szenarien AT-25, AT-53, AT-54, AT-61 im P1-Mindestumfang (WP-M5-31). Die Fachlogik liefern WP-M5-18 (AT-54) und WP-M5-19 (AT-25, AT-53, AT-61); hier werden ihre ausführbaren Tests unter dem
Akzeptanzgerüst (Zwei-Mandanten-Seed, Runtime-Rolle) ausgeführt bzw. über ihre JUnit-Ergebnisse verknüpft und als Evidenz nach Kap. 08 §8.4 festgehalten.

Ehrlich: Nicht Teil der Prüfung und als `offen (nicht geprüft)` ausgewiesen sind die Freigabecheckliste des PBefG-Exports (im Pflichtenheft nicht enthalten, OP-13), das Löschen nach Fristablauf (D-11),
Export parallel zu einem Löschjob sowie alle Screenreader- und Gerätedurchläufe von AT-54 (NVDA, VoiceOver, TalkBack; Flutter nicht ausführbar). Die Szenarien gelten deshalb als „Teilweise bestanden“."""
import sys
import uuid
from pathlib import Path

import psycopg

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from taktovia_at import db as atdb  # noqa: E402
from taktovia_at.scenario import scenario  # noqa: E402
from m1_gate.scenarios import BACKEND_RESULTS, backend  # noqa: E402,F401
from m2_gate.scenarios import offen, role_checks, vitest  # noqa: E402

SCENARIOS = {}


def _reg(test_id, version, keys, name):
    def deco(fn):
        SCENARIOS[test_id] = scenario(test_id, version, keys, name)(fn)
        return fn
    return deco


def _job(c, tenant, key, kind="INSPECTION_PROOF", requester="user:a1"):
    jid = uuid.uuid4()
    c.execute("""INSERT INTO taktovia.export_job(tenant_id,id,kind,scope,requested_by,requester_scopes,requester_roles,idempotency_key,request_hash,as_of)
                 VALUES (%s,%s,%s,'{}'::jsonb,%s,'{inspections:read}','{dispatcher}',%s,%s,now())""", (tenant, jid, kind, requester, key, "a" * 64))
    return jid


def _finish(c, jid):
    c.execute("UPDATE taktovia.export_job SET state='RUNNING', started_at=now() WHERE id=%s", (jid,))
    c.execute("""UPDATE taktovia.export_job SET state='SUCCEEDED', finished_at=now(), result='{"data":{}}'::jsonb, result_sha256=%s, result_bytes=12, categories='[]'::jsonb WHERE id=%s""", ("b" * 64, jid))


def _versuch(c, sql, args, fehler):
    """Führt eine unzulässige Anweisung aus; Ergebnis: Name der abgewiesenen Fehlerklasse oder 'ANGENOMMEN'."""
    c.execute("SAVEPOINT s")
    try:
        c.execute(sql, args)
        ergebnis = "ANGENOMMEN"
    except fehler as e:
        ergebnis = type(e).__name__
    c.execute("ROLLBACK TO SAVEPOINT s")
    return ergebnis


ABGEWIESEN = ("CheckViolation", "InsufficientPrivilege")


@_reg("AT-25", "1.0.0", ["ENABLER:WP-M5-19"], "PBefG-Datenexport (P1-Mindestumfang)")
def at_25(ctx):
    role_checks(ctx)
    backend(ctx,
            ("ReportJobsTest", "berechtigter_export_pruefnachweis_mit_hash_revision_zeitstempel_und_audit"),
            ("ReportJobsTest", "pbefg_export_weist_fehlende_und_nicht_berechtigte_kategorien_aus"),
            ("ReportJobsTest", "export_ohne_recht_wird_verweigert"),
            ("ReportJobApiTest", "auftrag_anlegen_verarbeiten_lesen_und_ueber_kurzlebigen_link_abrufen"))
    a = ctx.ids["A"]["tenant"]
    with atdb.tx(ctx.db_name, a) as c:
        jid = _job(c, a, f"at25-{uuid.uuid4().hex[:8]}", kind="TIME_PROOF")
        _finish(c, jid)
        row = c.execute("SELECT state, result_sha256, as_of IS NOT NULL FROM taktovia.export_job WHERE id=%s", (jid,)).fetchone()
        ctx.check("Exportauftrag: Ergebnis mit Prüfsumme und Datenstand (as_of) festgehalten", ("SUCCEEDED", "b" * 64, True), tuple(row))
    offen(ctx, "Vollständigkeit nach Freigabecheckliste des PBefG-Exports (Checkliste im Pflichtenheft nicht enthalten, OP-13; Arbeitsannahme im Backend)")


@_reg("AT-53", "1.0.0", ["ENABLER:WP-M5-19"], "Export und Fristen (P1-Mindestumfang)")
def at_53(ctx):
    role_checks(ctx)
    backend(ctx,
            ("ReportJobsTest", "vertrauliche_teile_der_schadenakte_und_hr_zeitdaten_nur_mit_feldrecht"),
            ("ReportJobsTest", "berechtigter_export_pruefnachweis_mit_hash_revision_zeitstempel_und_audit"),
            ("ReportJobsTest", "beendeter_auftrag_ist_unveraenderlich_und_nicht_loeschbar"),
            ("ReportJobsTest", "gleicher_idempotenzschluessel_liefert_denselben_auftrag_anderer_umfang_ist_konflikt_auch_parallel"))
    a = ctx.ids["A"]["tenant"]
    with atdb.tx(ctx.db_name, a) as c:
        jid = _job(c, a, f"at53a-{uuid.uuid4().hex[:8]}")
        ctx.check("Zustand nur vorwärts: QUEUED -> SUCCEEDED wird abgewiesen", "CheckViolation", _versuch(c, "UPDATE taktovia.export_job SET state='SUCCEEDED' WHERE id=%s", (jid,), psycopg.errors.CheckViolation))
    with atdb.tx(ctx.db_name, a) as c:
        jid = _job(c, a, f"at53b-{uuid.uuid4().hex[:8]}")
        _finish(c, jid)
    with atdb.tx(ctx.db_name, a) as c:
        for sql in ("UPDATE taktovia.export_job SET result='{}'::jsonb WHERE id=%s", "UPDATE taktovia.export_job SET as_of=now() WHERE id=%s", "UPDATE taktovia.export_job SET state='QUEUED' WHERE id=%s",
                    "DELETE FROM taktovia.export_job WHERE id=%s"):
            ctx.check(f"beendeter Auftrag unveränderlich und nicht löschbar: {sql.split(' SET')[0].split(' WHERE')[0]}", True, _versuch(c, sql, (jid,), (psycopg.errors.CheckViolation, psycopg.errors.InsufficientPrivilege)) in ABGEWIESEN)
        k = f"at53c-{uuid.uuid4().hex[:8]}"
        _job(c, a, k)
        ctx.check("gleicher Idempotenzschlüssel desselben Auftraggebers: eindeutig", "UniqueViolation", _versuch(c, "INSERT INTO taktovia.export_job(tenant_id,id,kind,scope,requested_by,requester_scopes,requester_roles,idempotency_key,request_hash,as_of) "
                                                                                                       "VALUES (%s,gen_random_uuid(),'INSPECTION_PROOF','{}'::jsonb,'user:a1','{}','{}',%s,%s,now())", (a, k, "a" * 64), psycopg.errors.UniqueViolation))
    offen(ctx, "Löschen nach Fristablauf für die exportierten Datenklassen (Fristen offen, D-11; Prüf- und Zeitnachweise sind nur anhängbar)")
    offen(ctx, "Export parallel zu einem Löschjob (kein Löschlauf mit Fristen vorhanden)")


@_reg("AT-61", "1.0.0", ["ENABLER:WP-M5-19"], "Archiv-/Exportberechtigungen (P1-Mindestumfang)")
def at_61(ctx):
    role_checks(ctx)
    backend(ctx,
            ("ReportJobsTest", "fremde_objekt_id_und_manipulierter_mandant_im_umfang_werden_abgewiesen"),
            ("ReportJobsTest", "zwei_mandanten_parallel_exportieren_ohne_vermischung"),
            ("ReportJobsTest", "datenstand_as_of_spaetere_daten_fehlen_und_alter_plan_bleibt_unveraendert_lesbar"),
            ("ReportJobsTest", "abruflink_ist_kurzlebig_auftragsgebunden_und_nur_fuer_den_auftraggeber"),
            ("ReportJobApiTest", "ohne_rolle_fremder_mandant_manipulierter_link_und_manipulierter_umfang"))
    a, b = ctx.ids["A"]["tenant"], ctx.ids["B"]["tenant"]
    with atdb.tx(ctx.db_name, a) as c:
        jid = _job(c, a, f"at61-{uuid.uuid4().hex[:8]}")
    with atdb.tx(ctx.db_name, b) as c:
        ctx.check("Mandant B sieht den Exportauftrag von A nicht", 0, c.execute("SELECT count(*) FROM taktovia.export_job WHERE id=%s", (jid,)).fetchone()[0])
        ctx.check("Mandant B kann keinen Auftrag für A anlegen", "InsufficientPrivilege", _versuch(c, "INSERT INTO taktovia.export_job(tenant_id,id,kind,scope,requested_by,requester_scopes,requester_roles,idempotency_key,request_hash,as_of) "
                                                                                                    "VALUES (%s,gen_random_uuid(),'TIME_PROOF','{}','user:b1','{}','{}','x',%s,now())", (a, "a" * 64), psycopg.errors.InsufficientPrivilege))
    with atdb.tx(ctx.db_name, None) as c:
        ctx.check("ohne Tenantkontext sieht die Runtime-Rolle keinen Exportauftrag", 0, c.execute("SELECT count(*) FROM taktovia.export_job").fetchone()[0])
    region, depot, roster, rev = uuid.uuid4(), uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
    with atdb.tx(ctx.db_name, a) as c:
        c.execute("SELECT set_config('app.roster_draft_access','on',true)")
        c.execute("INSERT INTO taktovia.region(tenant_id,id,name,created_by) VALUES (%s,%s,'R','user:t')", (a, region))
        c.execute("INSERT INTO taktovia.depot(tenant_id,id,region_id,name,created_by) VALUES (%s,%s,%s,'D','user:t')", (a, depot, region))
        c.execute("INSERT INTO taktovia.roster_revision(tenant_id,id,roster_id,depot_id,week_start,revision,author) VALUES (%s,%s,%s,%s,DATE '2026-09-28',1,'user:planer')", (a, rev, roster, depot))
        c.execute("UPDATE taktovia.roster_revision SET state='PUBLISHED', published_at=now(), published_by='user:planer', effective_from=now(), publish_reason='Freigabe' WHERE id=%s", (rev,))
    with atdb.tx(ctx.db_name, a) as c:
        for sql in ("UPDATE taktovia.roster_revision SET publish_reason='geändert' WHERE id=%s", "DELETE FROM taktovia.roster_revision WHERE id=%s"):
            ctx.check(f"veröffentlichte Dienstplanrevision bleibt unverändert: {sql.split(' SET')[0].split(' WHERE')[0]}", True, _versuch(c, sql, (rev,), (psycopg.errors.CheckViolation, psycopg.errors.InsufficientPrivilege)) in ABGEWIESEN)
        ctx.check("eigene Revision unverändert lesbar", "Freigabe", c.execute("SELECT publish_reason FROM taktovia.roster_revision WHERE id=%s", (rev,)).fetchone()[0])
    with atdb.tx(ctx.db_name, b) as c:
        c.execute("SELECT set_config('app.roster_draft_access','on',true)")
        ctx.check("Revision von A aus Mandant B: keine Zeile", 0, c.execute("SELECT count(*) FROM taktovia.roster_revision WHERE id=%s", (rev,)).fetchone()[0])
    offen(ctx, "Archiv- und Prüfläufe anderer Mandanten über Suche und Objektspeicher (nur lokaler Ersatz, kein Staging)")


@_reg("AT-54", "1.0.0", ["ENABLER:WP-M5-18"], "Barrierefreiheit Fahrer/Planer (nur automatisierte Anteile)")
def at_54(ctx):
    role_checks(ctx)
    vitest(ctx, "web/operator", "src/dienstplan", min_tests=8)
    vitest(ctx, "web/operator", "src/a11y", min_tests=1)
    offen(ctx, "Pflichtschritte mit Screenreader (NVDA, VoiceOver, TalkBack) je Kernreise: keine Hilfstechnik in dieser Umgebung")
    offen(ctx, "Driver-App (UI-09, UI-10): Flutter nicht ausführbar, keine Zielgeräte (OP-27)")
    offen(ctx, "Abnahmemaßstab WCAG 2.2 AA noch vom Auftraggeber zu bestätigen (OP-14)")
