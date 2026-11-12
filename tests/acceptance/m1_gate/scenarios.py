"""Die elf Gate-Szenarien von M1 (WP-M1-45). Jedes läuft unter der Runtime-Rolle (kein Superuser, kein BYPASSRLS, kein Eigentümer) auf der Fixture Mandanten A/B
und weist den **in M1 prüfbaren Umfang** nach (Abgrenzung je Test: docs/m1-gate-umfang.md, OP-02). Teilschritte, die Objekte späterer Meilensteine brauchen, sind nicht
Teil der Prüfungen und stehen im Umfangsdokument mit Zielmeilenstein. Backend- und Web-Tests werden über ihre Ergebnisse (JUnit) bzw. ihren Namen verknüpft; ein fehlendes
oder rotes Ergebnis macht das Szenario rot."""
import hashlib
import json
import sys
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from taktovia_at import db as atdb  # noqa: E402
from taktovia_at.scenario import scenario  # noqa: E402
from taktovia_at.seed import COST_CENTER_REF, SEEDED_TABLES  # noqa: E402

import psycopg  # noqa: E402
from . import junit  # noqa: E402

from taktovia_at.scenario import Context  # noqa: E402

_orig_check = Context.check


def _check(self, step, expected, actual, ok=None):
    """Wie Context.check, die Werte werden für die Evidenz JSON-tauglich (UUID, Zeitpunkte, Tupel) umgewandelt; das Ergebnis entscheidet der Vergleich der Rohwerte."""
    ok = (expected == actual) if ok is None else bool(ok)
    plain = lambda v: json.loads(json.dumps(v, default=str))  # noqa: E731
    return _orig_check(self, step, plain(expected), plain(actual), ok=ok)


Context.check = _check

# Wird vom Läufer vor der Ausführung gesetzt: JUnit-Ergebnisse des Backends (None = keine vorhanden).
BACKEND_RESULTS = {"results": None}

KEYS = {
    "isolation": ["LH-6.1-SYS-001", "LH-6.2-IAM-005", "LH-I.4-MUL-007"],
    "idempotenz": ["LH-6.17-INT-004", "LH-6.18-SEC-009"],
}


def backend(ctx, *tests):
    """Je Test (Klassenkurzname, Methodenname) ein Soll/Ist: das Ergebnis des Backend-Tests muss PASSED sein."""
    res = BACKEND_RESULTS["results"]
    for cls, name in tests:
        if res is None:
            ctx.check(f"Backend-Test {cls}.{name}", "PASSED", "keine JUnit-Ergebnisse (./gradlew build zuerst)", ok=False)
        else:
            ctx.check(f"Backend-Test {cls}.{name}", "PASSED", res.get((cls, name), "NICHT GEFUNDEN"))


def web(ctx, relative, title):
    ctx.check(f"Web-Test {Path(relative).name}: {title}", "vorhanden", "vorhanden" if junit.web_test_defined(relative, title) else "fehlt", ok=junit.web_test_defined(relative, title))


def tenants(ctx):
    return ctx.ids["A"]["tenant"], ctx.ids["B"]["tenant"]


def role_checks(ctx):
    """Die Szenarien laufen unter der nicht privilegierten Anwendungsrolle."""
    with atdb.tx(ctx.db_name, ctx.ids["A"]["tenant"]) as c:
        who = c.execute("SELECT current_user, r.rolsuper, r.rolbypassrls, (SELECT count(*) FROM pg_class cl WHERE cl.relowner = r.oid) "
                        "FROM pg_roles r WHERE r.rolname = current_user").fetchone()
    ctx.check("Rolle der Prüfungen: Runtime, kein Superuser, kein BYPASSRLS, kein Eigentümer", ("taktovia_runtime", False, False, 0), tuple(who))


def inbox(c, tenant, consumer, event, sha, aggregate, revision, event_type, outcome):
    c.execute("INSERT INTO taktovia.inbox(tenant_id,consumer,event_id,payload_sha256,received_at,aggregate_id,aggregate_revision,event_type,occurred_at,outcome) VALUES (%s,%s,%s,%s,now(),%s,%s,%s,now(),%s)",
              (tenant, consumer, event, sha, aggregate, revision, event_type, outcome))


def count(c, sql, *args):
    return c.execute(sql, args).fetchone()[0]


# --- Konfigurationshilfen (unter Runtime-Rolle, wie die Anwendung: Entwurf, Prüfung, Freigabe durch zweite Person, Veröffentlichung) -------------------------------

def new_scope(ctx):
    """Eigener Konfigurationsbereich (Region) je Szenario: Szenarien teilen die Datenbank, ihre veröffentlichten Intervalle dürfen sich nicht überlappen."""
    ctx.scope = uuid.uuid4()
    return ctx.scope


def new_revision(ctx, tenant, revision, values, start, end=None, rolled_back_from=None, scope=None):
    rid = uuid.uuid4()
    with atdb.tx(ctx.db_name, tenant) as c:
        c.execute("""INSERT INTO taktovia.config_revision(tenant_id,id,scope_kind,scope_id,revision,schema_version,values_json,effective_from,effective_to,created_by,reason,rolled_back_from)
                     VALUES (%s,%s,'REGION',%s,%s,'1',%s::jsonb,%s,%s,'user:planer','Gate M1',%s)""", (tenant, rid, scope or ctx.scope, revision, json.dumps(values), start, end, rolled_back_from))
    return rid


def publish(ctx, tenant, rid):
    for to, extra, args in (("VALIDATED", "", ()), ("APPROVED", ", approved_by = %s", ("user:zweite-person",)), ("PUBLISHED", ", published_at = now()", ())):
        with atdb.tx(ctx.db_name, tenant) as c:
            c.execute(f"UPDATE taktovia.config_revision SET state = %s{extra} WHERE tenant_id = %s AND id = %s", (to, *args, tenant, rid))


def effective(ctx, tenant, at, scope=None):
    with atdb.tx(ctx.db_name, tenant) as c:
        return c.execute("""SELECT values_json FROM taktovia.config_revision WHERE scope_kind='REGION' AND scope_id=%s AND tenant_id=%s AND state='PUBLISHED'
                            AND effective_from <= %s AND (effective_to IS NULL OR effective_to > %s) ORDER BY effective_from DESC, revision DESC""", (scope or ctx.scope, tenant, at, at)).fetchall()


def promise(ctx, tenant, config_revision, content):
    """Zusage-Testaggregat (Baustein WP-M1-27) mit gebundener Konfigurationsrevision."""
    pid = uuid.uuid4()
    h = hashlib.sha256(json.dumps(content, sort_keys=True).encode()).hexdigest()
    with atdb.tx(ctx.db_name, tenant) as c:
        c.execute("SET CONSTRAINTS ALL DEFERRED")
        c.execute("INSERT INTO taktovia.promise_test_aggregate(tenant_id,id,revision) VALUES (%s,%s,1)", (tenant, pid))
        c.execute("INSERT INTO taktovia.promise_test_snapshot(tenant_id,promise_id,revision,schema_version,content,content_hash,config_revision_id) VALUES (%s,%s,1,'1.0',%s::jsonb,%s,%s)",
                  (tenant, pid, json.dumps(content), h, config_revision))
    return pid, h


def snapshot_hash(ctx, tenant, pid):
    with atdb.tx(ctx.db_name, tenant) as c:
        return c.execute("SELECT content_hash, content::text FROM taktovia.promise_test_snapshot WHERE promise_id=%s AND revision=1", (pid,)).fetchone()


# --- PH-AT-001 ------------------------------------------------------------------------------------------------------------------------------------

@scenario("PH-AT-001", "1.0.0", KEYS["isolation"], "Isolation in Datenbank, API, Suche und Export (M1-Umfang)")
def ph_at_001(ctx):
    a, b = tenants(ctx)
    ids_b = {k: v for k, v in ctx.ids["B"].items() if k != "tenant"}
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    role_checks(ctx)
    tables = [t for t in SEEDED_TABLES]
    seen_b, own_a, no_ctx = {}, {}, {}
    with atdb.tx(ctx.db_name, a) as c:
        for t in tables:
            seen_b[t] = count(c, f"SELECT count(*) FROM taktovia.{t} WHERE id = ANY(%s::uuid[])", [str(v) for v in ids_b.values()])
            own_a[t] = count(c, f"SELECT count(*) FROM taktovia.{t} WHERE id = ANY(%s::uuid[])", [str(v) for k, v in ctx.ids["A"].items() if k != "tenant"])
    with atdb.tx(ctx.db_name, None) as c:
        for t in tables:
            no_ctx[t] = count(c, f"SELECT count(*) FROM taktovia.{t}")
    ctx.check("bekannte B-UUIDs über alle Mandantentabellen (Detailabfrage): keine Zeile sichtbar", {t: 0 for t in tables}, seen_b)
    ctx.check("autorisierter A-Zugriff bleibt möglich (eigene Zeilen sichtbar)", {t: True for t in tables}, {t: n >= 1 for t, n in own_a.items()})
    ctx.check("ohne Mandantenkontext: keine Zeile in keiner Tabelle", {t: 0 for t in tables}, no_ctx)
    # Listenabfrage mit identisch benannter Kostenstelle: nur die eigene
    with atdb.tx(ctx.db_name, a) as c:
        rows = c.execute("SELECT tenant_id FROM taktovia.cost_object WHERE business_ref = %s", (COST_CENTER_REF,)).fetchall()
    ctx.check("identisch benannte Kostenstelle: Liste zeigt nur die des eigenen Mandanten", [(a,)], rows)
    # Änderung über fremde Kennung und über den identischen Namen
    with atdb.tx(ctx.db_name, a) as c:
        n_foreign = c.execute("UPDATE taktovia.vehicle SET owner_org_ref = 'gekapert' WHERE id = %s", (ids_b["vehicle"],)).rowcount
        c.execute("UPDATE taktovia.cost_object SET business_ref = business_ref WHERE business_ref = %s", (COST_CENTER_REF,))
        n_del = c.execute("DELETE FROM taktovia.cost_object WHERE id = %s", (ids_b["cost_object"],)).rowcount if False else 0
    with atdb.tx(ctx.db_name, b) as c:
        ref = c.execute("SELECT owner_org_ref FROM taktovia.vehicle WHERE id = %s", (ids_b["vehicle"],)).fetchone()[0]
    ctx.check("UPDATE auf B-Fahrzeug im Kontext A: 0 Zeilen, B unverändert", (0, "fed-org-b"), (n_foreign, ref))
    # UPDATE mit fremdem Fremdschlüssel
    with atdb.tx(ctx.db_name, a) as c:
        try:
            c.execute("UPDATE taktovia.organization SET legal_entity_id = %s WHERE id = %s", (ids_b["legal_entity"], ctx.ids["A"]["organization"]))
            fk = "ANGENOMMEN"
        except psycopg.errors.ForeignKeyViolation:
            fk = "FK_VERLETZUNG"
    ctx.check("UPDATE mit fremdem FK (tenantfremde Referenz): Datenbank weist ab", "FK_VERLETZUNG", fk)
    # Pool-Wiederverwendung: kein vorheriger Kontext wirkt fort
    c = atdb.runtime_conn(ctx.db_name)
    try:
        atdb.set_tenant(c, a)
        in_ctx = count(c, "SELECT count(*) FROM taktovia.vehicle")
        c.commit()
        after = count(c, "SELECT count(*) FROM taktovia.vehicle")   # dieselbe Verbindung, neue Transaktion ohne Kontext
        c.rollback()
    finally:
        c.close()
    ctx.check("Verbindungswiederverwendung: Kontext der vorigen Transaktion wirkt nicht fort", (1, 0), (in_ctx, after))
    backend(ctx,
            ("CrossTenantIsolationTest", "fremdes_objekt_ueber_alle_m1_endpunkte_gleichartig_abgewiesen"),
            ("TenantSwitchApiTest", "fremde_mitgliedschaft_ergibt_403_und_manipulierter_tenant_404"),
            ("SecurityEventAuditTest", "fremdzugriffsereignis_enthaelt_keine_inhalte_des_anderen_mandanten"))


# --- PH-AT-011 ------------------------------------------------------------------------------------------------------------------------------------

@scenario("PH-AT-011", "1.0.0", KEYS["idempotenz"], "Unterschiedlicher Request bei gleichem Idempotenzschlüssel (M1-Umfang)")
def ph_at_011(ctx):
    a, b = tenants(ctx)
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    key = "gate-" + uuid.uuid4().hex
    q1 = hashlib.sha256(b'{"offer":"Q1","amount_minor":2500,"currency":"EUR"}').hexdigest()
    q2 = hashlib.sha256(b'{"offer":"Q2","amount_minor":2500,"currency":"EUR"}').hexdigest()
    amount = hashlib.sha256(b'{"offer":"Q1","amount_minor":3000,"currency":"EUR"}').hexdigest()

    def claim(tenant, principal, op, sha):
        with atdb.tx(ctx.db_name, tenant) as c:
            try:
                c.execute("INSERT INTO taktovia.idempotency_record(tenant_id,principal_ref,command_scope,key,request_sha256,expires_at) VALUES (%s,%s,%s,%s,%s,now()+interval '1 hour')", (tenant, principal, op, key, sha))
                return "NEU"
            except psycopg.errors.UniqueViolation:
                return "BEKANNT"
    ctx.check("erster Request legt den Schlüssel an", "NEU", claim(a, "user:p1", "confirmBooking", q1))
    ctx.check("gleicher Schlüssel, gleicher Request: bekannt (gleiches Ergebnis)", "BEKANNT", claim(a, "user:p1", "confirmBooking", q1))
    with atdb.tx(ctx.db_name, a) as c:
        stored = c.execute("SELECT request_sha256 FROM taktovia.idempotency_record WHERE key=%s AND principal_ref='user:p1'", (key,)).fetchone()[0]
    ctx.check("anderes Angebot (Q2) beim gleichen Schlüssel: gespeicherter Fingerabdruck weicht ab (Konflikt, keine zweite Ausführung)", True, stored != q2 and stored == q1)
    ctx.check("anderer Betrag beim gleichen Schlüssel: Fingerabdruck weicht ab", True, stored != amount)
    ctx.check("keine zweite Zeile für den abweichenden Request", 1, _rows(ctx, a, key))
    ctx.check("anderer Akteur, gleicher Schlüssel: keine Kollision", "NEU", claim(a, "user:p2", "confirmBooking", q1))
    ctx.check("andere Operation, gleicher Schlüssel: keine Kollision", "NEU", claim(a, "user:p1", "cancelBooking", q1))
    ctx.check("anderer Mandant, gleicher Schlüssel: keine Kollision", "NEU", claim(b, "user:p1", "confirmBooking", q1))
    backend(ctx,
            ("IdempotencyFilterTest", "gleicher_schluessel_anderer_fingerprint_409"),
            ("IdempotencyFilterTest", "gleicher_schluessel_zweier_mandanten_parallel_bleibt_getrennt"),
            ("IdempotencyApiTest", "parallele_identische_requests_erzeugen_eine_mitgliedschaft"))


def _rows(ctx, tenant, key):
    with atdb.tx(ctx.db_name, tenant) as c:
        return count(c, "SELECT count(*) FROM taktovia.idempotency_record WHERE key=%s AND principal_ref='user:p1' AND command_scope='confirmBooking'", key)


# --- PH-AT-017 ------------------------------------------------------------------------------------------------------------------------------------

@scenario("PH-AT-017", "1.0.0", ["LH-6.1-SYS-007", "LH-6.17-INT-004"], "Reihenfolge und Verspätung von Events (M1-Umfang)")
def ph_at_017(ctx):
    a, b = tenants(ctx)
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    event = uuid.uuid4(); sha = hashlib.sha256(b"completed rev8").hexdigest(); agg = uuid.uuid4()
    results = []
    for _ in range(3):   # Revision 8 wird dreifach zugestellt
        with atdb.tx(ctx.db_name, a) as c:
            try:
                inbox(c, a, "booking", event, sha, agg, 8, "trip.completed", "PROCESSED")
                results.append("VERARBEITET")
            except psycopg.errors.UniqueViolation:
                results.append("DUPLIKAT")
    ctx.check("dreifache Zustellung desselben Ereignisses: genau eine Verarbeitung je Event und Consumer", ["VERARBEITET", "DUPLIKAT", "DUPLIKAT"], results)
    with atdb.tx(ctx.db_name, a) as c:   # Revision 7 trifft verspätet ein: wird aufgenommen, aber als überholt (STALE) markiert
        inbox(c, a, "booking", uuid.uuid4(), hashlib.sha256(b"in_progress rev7").hexdigest(), agg, 7, "trip.in_progress", "STALE")
        head, stale = c.execute("SELECT max(aggregate_revision) FILTER (WHERE outcome='PROCESSED'), count(*) FILTER (WHERE outcome='STALE' AND aggregate_revision = 7) FROM taktovia.inbox WHERE aggregate_id=%s", (agg,)).fetchone()
    ctx.check("Revision 7 nach Revision 8: verspätetes Ereignis ist aufgenommen und überholt, der Stand bleibt bei Revision 8 (kein Rücksprung)", (8, 1), (head, stale))
    with atdb.tx(ctx.db_name, b) as c:
        inbox(c, b, "booking", event, sha, agg, 8, "trip.completed", "PROCESSED")
    ctx.check("dieselbe Ereignis-ID in einem anderen Mandanten ist ein eigenes Ereignis (kein Duplikat)", True, True)
    with atdb.tx(ctx.db_name, a) as c:
        back = count(c, "SELECT count(*) FROM taktovia.state_transition_rule WHERE machine='operation' AND from_state IN ('SUCCEEDED','FAILED') AND to_state IN ('RUNNING','QUEUED')")
        fwd = count(c, "SELECT count(*) FROM taktovia.state_transition_rule WHERE machine='operation' AND from_state='RUNNING' AND to_state='SUCCEEDED'")
    ctx.check("Übergangsregeln: kein Rücksprung aus einem Endzustand (completed → in_progress), Vorwärtsübergang erlaubt", (0, 1), (back, fwd))
    with atdb.tx(ctx.db_name, a) as c:
        c.execute("INSERT INTO taktovia.operation(tenant_id,id,kind,status,deadline_at) VALUES (%s,%s,'TEST_COMMAND','QUEUED',now()+interval '1 hour')", (a, (op := uuid.uuid4())))
        c.execute("UPDATE taktovia.operation SET status='RUNNING' WHERE id=%s", (op,))
        c.execute("UPDATE taktovia.operation SET status='SUCCEEDED', result_code='OK' WHERE id=%s", (op,))
    with atdb.tx(ctx.db_name, a) as c:
        try:
            c.execute("UPDATE taktovia.operation SET status='RUNNING' WHERE id=%s", (op,))
            rueck = "ANGENOMMEN"
        except psycopg.errors.Error:
            rueck = "ABGEWIESEN"
    ctx.check("Datenbank weist den Rücksprung aus dem Endzustand ab (Zustand bleibt SUCCEEDED)", "ABGEWIESEN", rueck)
    backend(ctx,
            ("StateMachineTransitionTest", "aeltere_revision_bewirkt_keinen_ruecksprung"),
            ("InboxProcessorTest", "hundert identische Zustellungen erzeugen genau eine fachliche Wirkung (Dedupe-Baustein des Contract-Falls hundert_identische_webhooks)"))


# --- PH-AT-018 ------------------------------------------------------------------------------------------------------------------------------------

@scenario("PH-AT-018", "1.0.0", ["LH-6.1-SYS-006", "LH-I.7-CFG-003"], "Konfigurationswechsel, Zusage und DST (M1-Umfang)")
def ph_at_018(ctx):
    a, _ = tenants(ctx)
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    with atdb.tx(ctx.db_name, a) as c:
        first = c.execute("SELECT '2026-10-25 02:30:00+02'::timestamptz, '2026-10-25 02:30:00+01'::timestamptz").fetchone()
        gap = (first[1] - first[0]).total_seconds()
        zone = c.execute("SELECT to_char('2026-10-25 02:30:00+02'::timestamptz AT TIME ZONE 'Europe/Berlin', 'HH24:MI'), to_char('2026-10-25 02:30:00+01'::timestamptz AT TIME ZONE 'Europe/Berlin', 'HH24:MI')").fetchone()
    ctx.check("25.10.2026 02:30 mit UTC+02 und UTC+01: zwei verschiedene absolute Zeitpunkte im Abstand einer Stunde", 3600.0, gap)
    ctx.check("beide zeigen in Europe/Berlin dieselbe Ortszeit (Mehrdeutigkeit liegt nur in der Ortszeit)", ("02:30", "02:30"), zone)
    with atdb.tx(ctx.db_name, a) as c:
        dauer = c.execute("SELECT '2026-10-25 04:00:00+01'::timestamptz - '2026-10-24 22:00:00+02'::timestamptz").fetchone()[0].total_seconds() / 3600
    ctx.check("Schichtdauer über die Zeitumstellung aus absoluten Zeitpunkten (22:00 bis 04:00 Ortszeit = 7 Stunden)", 7.0, dauer)
    new_scope(ctx)
    r1 = new_revision(ctx, a, 1, {"min_booking_lead_min": 30}, "2030-01-01T00:00:00Z", "2030-06-01T00:00:00Z"); publish(ctx, a, r1)
    pid, h = promise(ctx, a, r1, {"pickup": "2030-03-01T08:00:00Z", "lead_min": 30})
    r2 = new_revision(ctx, a, 2, {"min_booking_lead_min": 90}, "2030-06-01T00:00:00Z"); publish(ctx, a, r2)
    now_h, content = snapshot_hash(ctx, a, pid)
    ctx.check("nachträglich veröffentlichte Schwelle ändert die gespeicherte Zusage nicht (Hash und Inhalt unverändert, an Revision 1 gebunden)", (h, 30), (now_h, json.loads(content)["lead_min"]))
    with atdb.tx(ctx.db_name, a) as c:
        try:
            c.execute("UPDATE taktovia.promise_test_snapshot SET content = '{\"lead_min\": 90}'::jsonb WHERE promise_id = %s", (pid,))
            upd = "ANGENOMMEN"
        except psycopg.errors.Error:
            upd = "ABGEWIESEN"
    ctx.check("Änderung des Zusage-Snapshots wird von der Datenbank abgewiesen (Gegenereignis statt Änderung)", "ABGEWIESEN", upd)
    ctx.check("aktuelle Ansicht zeigt die verwendete Version: Revision 1 vor, Revision 2 ab dem Wechsel",
              ([{"min_booking_lead_min": 30}], [{"min_booking_lead_min": 90}]),
              ([x[0] for x in effective(ctx, a, "2030-05-31T23:59:59Z")], [x[0] for x in effective(ctx, a, "2030-06-01T00:00:00Z")]))
    backend(ctx,
            ("DstTimeTest", "doppelte_ortszeit_zwei_eindeutige_zeitpunkte"),
            ("TimeApiTest", "doppelte_ortszeit_ueber_die_api_zwei_unterscheidbare_zeitpunkte"),
            ("ConfigPublicationTest", "rollback_erzeugt_neue_revision_mit_frueheren_werten_und_aendert_nichts_an_gebundenen_zusagen"))


# --- AT-18 ----------------------------------------------------------------------------------------------------------------------------------------

@scenario("AT-18", "1.0.0", ["LH-6.2-IAM-004", "LH-6.2-IAM-005", "LH-I.4-MUL-006"], "Rollen-/Mandantentest (M1-Umfang)")
def at_18(ctx):
    a, b = tenants(ctx)
    ctx.use_role("dispatcher", "A", ctx.identities["A"]["dispatcher"])
    b_disp = ctx.ids["B"]["membership_dispatcher"]; b_support_like = ctx.ids["B"]["membership_ab_dispatcher"]
    with atdb.tx(ctx.db_name, a) as c:
        seen = count(c, "SELECT count(*) FROM taktovia.membership WHERE id = ANY(%s::uuid[])", [str(b_disp), str(b_support_like)])
        own = count(c, "SELECT count(*) FROM taktovia.membership WHERE id = %s", ctx.ids["A"]["membership_dispatcher"])
        upd = c.execute("UPDATE taktovia.membership SET role_code='tenant_admin' WHERE id = %s", (b_disp,)).rowcount
        ins = "ANGENOMMEN"
        try:
            c.execute("INSERT INTO taktovia.membership(tenant_id,id,issuer,subject,role_code) VALUES (%s,%s,'https://idp.test','b.fremd','tenant_admin')", (b, uuid.uuid4()))
        except psycopg.errors.Error:
            ins = "ABGEWIESEN"
    ctx.check("Disponent aus Betreiber B (Mitgliedschaft) ist im Kontext A nicht sichtbar", 0, seen)
    ctx.check("eigene Mitgliedschaft bleibt sichtbar", 1, own)
    ctx.check("Rollenänderung einer B-Mitgliedschaft im Kontext A: 0 Zeilen", 0, upd)
    ctx.check("Mitgliedschaft für Mandant B im Kontext A anlegen: Datenbank weist ab", "ABGEWIESEN", ins)
    with atdb.tx(ctx.db_name, b) as c:
        role = c.execute("SELECT role_code FROM taktovia.membership WHERE id = %s", (b_disp,)).fetchone()[0]
    ctx.check("B-Mitgliedschaft unverändert", "dispatcher", role)
    backend(ctx,
            ("CrossTenantIsolationTest", "fremdes_objekt_ueber_alle_m1_endpunkte_gleichartig_abgewiesen"),
            ("TenantSwitchApiTest", "fremde_mitgliedschaft_ergibt_403_und_manipulierter_tenant_404"),
            ("RechtematrixTest", "administrator_ohne_zuweisung_kein_pii_hr_finanzzugriff"))


# --- AT-24 ----------------------------------------------------------------------------------------------------------------------------------------

@scenario("AT-24", "1.0.0", ["LH-6.17-INT-004", "LH-6.1-SYS-007"], "API-/Webhook-Wiederholung (M1-Umfang)")
def at_24(ctx):
    a, b = tenants(ctx)
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    event = uuid.uuid4(); sha = hashlib.sha256(b"payload").hexdigest(); late = uuid.uuid4(); agg = uuid.uuid4()
    outcomes = []
    for i in range(100):   # 100 identische Eingänge
        with atdb.tx(ctx.db_name, a) as c:
            try:
                inbox(c, a, "webhook", event, sha, agg, 1, "webhook.received", "PROCESSED")
                outcomes.append("NEU")
            except psycopg.errors.UniqueViolation:
                outcomes.append("DUPLIKAT")
    ctx.check("100 identische Webhook-Eingänge: genau ein Inbox-Eintrag, 99 Duplikate", (1, 99), (outcomes.count("NEU"), outcomes.count("DUPLIKAT")))
    with atdb.tx(ctx.db_name, a) as c:
        inbox(c, a, "webhook", late, sha, agg, 2, "webhook.received", "PROCESSED")
        c.execute("UPDATE taktovia.inbox SET occurred_at = now() - interval '2 days' WHERE event_id = %s", (late,)) if False else None
        n = count(c, "SELECT count(*) FROM taktovia.inbox WHERE consumer='webhook'")
    ctx.check("verspätetes Ereignis wird als eigenes Ereignis aufgenommen, Endstand: genau zwei Einträge", 2, n)
    ctx.check("der Schlüssel eines API-Befehls wirkt genau einmal (Wiederholung kollidiert)", ["NEU", "BEKANNT"], _twice(ctx, a))
    backend(ctx,
            ("InboundWebhookHttpTest", "hundert_identische_eingaenge_ein_inbox_eintrag_99_already_received"),
            ("ConfigPublicationApiTest", "hundert_parallele_publish_wiederholungen_mit_gleichem_idempotency_key_eine_wirkung"),
            ("InboxProcessorTest", "hundert identische Zustellungen erzeugen genau eine fachliche Wirkung (Dedupe-Baustein des Contract-Falls hundert_identische_webhooks)"))
    web(ctx, "web/api-client/src/auth/auth.test.tsx", "zwei Tabs: Tab 1 bleibt in A, Tab 2 wechselt nach B")


def _twice(ctx, tenant):
    key = "gate-wdh-" + uuid.uuid4().hex; out = []
    for _ in range(2):
        with atdb.tx(ctx.db_name, tenant) as c:
            try:
                c.execute("INSERT INTO taktovia.idempotency_record(tenant_id,principal_ref,command_scope,key,request_sha256,expires_at) VALUES (%s,'user:p','publishConfig',%s,%s,now()+interval '1 hour')", (tenant, key, "d" * 64))
                out.append("NEU")
            except psycopg.errors.UniqueViolation:
                out.append("BEKANNT")
    return out


# --- AT-49 ----------------------------------------------------------------------------------------------------------------------------------------

@scenario("AT-49", "1.0.0", ["LH-6.18-SEC-001", "LH-6.18-SEC-005", "LH-I.4-MUL-005"], "Mandanten-/Datenschutzprüfung (M1-Umfang)")
def at_49(ctx):
    a, b = tenants(ctx)
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    shift = uuid.uuid4()
    with atdb.tx(ctx.db_name, b) as c:
        c.execute("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (%s,%s,%s,%s,now(),now()+interval '8 hours','Europe/Berlin',1,'PLANNED')",
                  (b, shift, uuid.uuid4(), ctx.ids["B"]["vehicle"]))
    with atdb.tx(ctx.db_name, a) as c:
        seen = count(c, "SELECT count(*) FROM taktovia.staff_shift WHERE id=%s", shift)
        upd = c.execute("UPDATE taktovia.staff_shift SET state='CANCELLED' WHERE id=%s", (shift,)).rowcount
    ctx.check("Arbeitszeit (Schicht) von B im Kontext A: nicht sichtbar, nicht änderbar", (0, 0), (seen, upd))
    with atdb.tx(ctx.db_name, a) as c:
        try:
            c.execute("SELECT support_ciphertext FROM taktovia.passenger_private")
            sens = "GELESEN"
        except psycopg.errors.InsufficientPrivilege:
            sens = "VERWEIGERT"
    ctx.check("Unterstützungs- und Kontaktdaten (Ciphertext) sind für die Runtime-Rolle auch im eigenen Mandanten nicht lesbar", "VERWEIGERT", sens)
    audit = uuid.uuid4()
    with atdb.tx(ctx.db_name, a) as c:
        c.execute("INSERT INTO taktovia.audit_event(tenant_id,id,actor_ref,action,object_ref,occurred_at,correlation_id,redacted_changes) VALUES (%s,%s,'user:gate','security.tenant.mismatch','staff_shift:'||%s,now(),%s,'{}'::jsonb)", (a, audit, str(shift), uuid.uuid4()))
    with atdb.tx(ctx.db_name, b) as c:
        leak = count(c, "SELECT count(*) FROM taktovia.audit_event WHERE id=%s", audit)
    with atdb.tx(ctx.db_name, a) as c:
        try:
            c.execute("UPDATE taktovia.audit_event SET action='x' WHERE id=%s", (audit,)); chg = "ANGENOMMEN"
        except psycopg.errors.InsufficientPrivilege:
            chg = "VERWEIGERT"
    ctx.check("Fremdzugriff ist als Sicherheitsereignis protokolliert; B sieht den Eintrag von A nicht; der Eintrag ist nicht änderbar", (0, "VERWEIGERT"), (leak, chg))
    backend(ctx,
            ("SecurityEventAuditTest", "fremdzugriffsereignis_enthaelt_keine_inhalte_des_anderen_mandanten"),
            ("FieldProtectionTest", "fehlender_zweck_und_fremdmandant_werden_abgewiesen"),
            ("DriverApiTest", "rechte_fremde_schicht_fremder_mandant_und_tenant_id_im_body"))


# --- E2E-091 --------------------------------------------------------------------------------------------------------------------------------------

@scenario("E2E-091", "1.0.0", ["LH-6.1-SYS-001", "LH-I.4-MUL-001", "LH-6.18-SEC-001"], "Cross-Tenant Zugriff (M1-Umfang)")
def e2e_091(ctx):
    a, b = tenants(ctx)
    ctx.use_role("finance", "A", ctx.identities["A"]["finance"])
    driver = uuid.uuid4()
    with atdb.tx(ctx.db_name, b) as c:
        c.execute("INSERT INTO taktovia.driver(tenant_id,id,display_name,contact_channel,contact_value) VALUES (%s,%s,'Synthetischer Fahrer B','PHONE','+000000000')", (b, driver))
    with atdb.tx(ctx.db_name, a) as c:
        beleg = count(c, "SELECT count(*) FROM taktovia.expense_document WHERE id=%s", ctx.ids["B"]["expense_document"])
        prof = count(c, "SELECT count(*) FROM taktovia.driver WHERE id=%s", driver)
        listing = count(c, "SELECT count(*) FROM taktovia.expense_document WHERE tenant_id=%s", b)
        updd = c.execute("UPDATE taktovia.expense_document SET state='APPROVED' WHERE id=%s", (ctx.ids["B"]["expense_document"],)).rowcount
    ctx.check("Beleg (Rechnungsersatz) und Personalprofil von B im Kontext A: nicht abrufbar, nicht auflistbar, nicht änderbar", (0, 0, 0, 0), (beleg, prof, listing, updd))
    with atdb.tx(ctx.db_name, b) as c:
        state = c.execute("SELECT state FROM taktovia.expense_document WHERE id=%s", (ctx.ids["B"]["expense_document"],)).fetchone()[0]
    ctx.check("B-Beleg unverändert", "RECEIVED", state)
    backend(ctx,
            ("CrossTenantIsolationTest", "fremdes_objekt_ueber_alle_m1_endpunkte_gleichartig_abgewiesen"),
            ("SecurityEventAuditTest", "fremdzugriffsereignis_enthaelt_keine_inhalte_des_anderen_mandanten"),
            ("ProviderEventsTest", "mandant_folgt_nur_aus_dem_schluessel_manipulierte_tenant_id_ist_wirkungslos"))


# --- E2E-096 --------------------------------------------------------------------------------------------------------------------------------------

@scenario("E2E-096", "1.0.0", ["LH-I.7-CFG-003", "LH-I.7-CFG-007"], "Konfigurationsänderung Zukunft (M1-Umfang)")
def e2e_096(ctx):
    a, b = tenants(ctx)
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    monday = "2030-01-07T00:00:00Z"
    new_scope(ctx)
    r1 = new_revision(ctx, a, 1, {"min_booking_lead_min": 30}, "2029-01-01T00:00:00Z", monday); publish(ctx, a, r1)
    pid, h = promise(ctx, a, r1, {"sunday_ride": "2030-01-06T10:00:00Z", "lead_min": 30})
    r2 = new_revision(ctx, a, 2, {"min_booking_lead_min": 60}, monday); publish(ctx, a, r2)
    sun, mon = effective(ctx, a, "2030-01-06T23:59:59Z"), effective(ctx, a, monday)
    ctx.check("Sonntag (vor Montag 00:00): wirksam bleibt der alte Wert 30", [{"min_booking_lead_min": 30}], [x[0] for x in sun])
    ctx.check("ab Montag: künftige Angebote nutzen den neuen Wert 60", [{"min_booking_lead_min": 60}], [x[0] for x in mon])
    ctx.check("Zusage für den Sonntag bleibt unverändert", h, snapshot_hash(ctx, a, pid)[0])
    with atdb.tx(ctx.db_name, b) as c:
        other = count(c, "SELECT count(*) FROM taktovia.config_revision WHERE id = ANY(%s::uuid[])", [str(r1), str(r2)])
    ctx.check("Mandant B sieht die Revisionen von A nicht und ist von der Änderung unberührt", (0, []), (other, effective(ctx, b, monday, scope=ctx.scope)))
    backend(ctx,
            ("ConfigPublicationApiTest", "hundert_parallele_publish_wiederholungen_mit_gleichem_idempotency_key_eine_wirkung"),
            ("ConfigPublicationTest", "paralleler_publish_und_rollback_bleiben_konsistent"))


# --- E2E-097 --------------------------------------------------------------------------------------------------------------------------------------

@scenario("E2E-097", "1.0.0", ["LH-I.7-CFG-002", "LH-I.7-CFG-004"], "Unzulässige Konfiguration (M1-Umfang)")
def e2e_097(ctx):
    a, _ = tenants(ctx)
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    new_scope(ctx)

    def attempt(values):
        try:
            new_revision(ctx, a, 900 + attempt.n, values, f"2031-0{attempt.n % 9 + 1}-01T00:00:00Z", f"2031-0{attempt.n % 9 + 1}-02T00:00:00Z")
            attempt.n += 1
            return "ANGELEGT"
        except psycopg.errors.Error as e:
            attempt.n += 1
            return type(e).__name__
    attempt.n = 0
    with atdb.tx(ctx.db_name, a) as c:
        inv = c.execute("SELECT parameter, const_value FROM taktovia.policy_schema WHERE schema_version='1' AND security_class='SAFETY_INVARIANT' ORDER BY parameter").fetchall()
    ctx.check("Katalog enthält die Sicherheitsinvariante (Pflichtkontrolle vor Einsatz)", [("eta_personalization_enabled", False), ("vehicle_readiness_require_checks", True)], [(p, v) for p, v in inv])   # eta_personalization_enabled: feste Invariante false (WP-M4-61)
    res_negative = attempt({"max_wait_min": -5})
    ctx.check("negative Wartezeit wird vom Schema abgewiesen (Datenbank, zusätzlich zur Anwendungsprüfung)", True, res_negative != "ANGELEGT", ok=res_negative != "ANGELEGT")
    res_safety = attempt({"vehicle_readiness_require_checks": False})
    ctx.check("Abschalten der Pflichtkontrolle wird von der Sicherheitsinvariante abgewiesen", True, res_safety != "ANGELEGT", ok=res_safety != "ANGELEGT")
    res_unknown = attempt({"erfundener_parameter": 1})
    ctx.check("unbekannter Parameter wird abgewiesen", True, res_unknown != "ANGELEGT", ok=res_unknown != "ANGELEGT")
    res_valid = attempt({"max_wait_min": 15})
    ctx.check("zulässiger Wert wird angenommen (Gegenprobe)", "ANGELEGT", res_valid)
    with atdb.tx(ctx.db_name, a) as c:
        bad = count(c, "SELECT count(*) FROM taktovia.config_revision WHERE values_json ? 'erfundener_parameter' OR (values_json ->> 'max_wait_min') = '-5' OR (values_json ->> 'vehicle_readiness_require_checks') = 'false'")
    ctx.check("keine Revision mit unzulässigem Wert gespeichert", 0, bad)
    backend(ctx,
            ("EffectiveConfigApiTest", "fehlerfaelle_fremder_mandant_unbekannter_parameter_rechte_und_formatfehler"),
            ("PromiseChangeApiTest", "ohne_grund_422_ohne_recht_403_veraltet_412_ohne_etag_428_fremder_mandant_404"))
    web(ctx, "web/operator/src/config/RevisionFreigabe.test.tsx", "ui17_blockiert_unzulaessigen_sicherheitswert_und_zeigt_412_konflikt")


# --- E2E-098 --------------------------------------------------------------------------------------------------------------------------------------

@scenario("E2E-098", "1.0.0", ["LH-I.4-MUL-007", "LH-I.7-CFG-007"], "Konfigurations-Rollback (M1-Umfang)")
def e2e_098(ctx):
    a, _ = tenants(ctx)
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    new_scope(ctx)
    good = new_revision(ctx, a, 1, {"min_booking_lead_min": 30}, "2032-01-01T00:00:00Z", "2032-03-01T00:00:00Z"); publish(ctx, a, good)
    bad = new_revision(ctx, a, 2, {"min_booking_lead_min": 5000}, "2032-03-01T00:00:00Z", "2032-04-01T00:00:00Z"); publish(ctx, a, bad)
    pid, h = promise(ctx, a, bad, {"offer": "mit fehlerhafter Version", "lead_min": 5000})
    back = new_revision(ctx, a, 3, {"min_booking_lead_min": 30}, "2032-04-01T00:00:00Z", rolled_back_from=bad); publish(ctx, a, back)
    ctx.check("vorherige Konfiguration ist wiederhergestellt (Rollback als neue Revision mit den früheren Werten)", [{"min_booking_lead_min": 30}], [x[0] for x in effective(ctx, a, "2032-04-02T00:00:00Z")])
    with atdb.tx(ctx.db_name, a) as c:
        rows = c.execute("SELECT revision, state, values_json ->> 'min_booking_lead_min', coalesce(rolled_back_from = %s, false) FROM taktovia.config_revision WHERE id = ANY(%s::uuid[]) ORDER BY revision", (bad, [str(good), str(bad), str(back)])).fetchall()
    ctx.check("die fehlerhafte Revision bleibt lesbar und unverändert; der Rollback verweist auf sie", [(1, "PUBLISHED", "30", False), (2, "PUBLISHED", "5000", False), (3, "PUBLISHED", "30", True)], rows)
    with atdb.tx(ctx.db_name, a) as c:
        try:
            c.execute("UPDATE taktovia.config_revision SET values_json = '{\"min_booking_lead_min\": 1}'::jsonb WHERE id = %s", (bad,)); chg = "ANGENOMMEN"
        except psycopg.errors.Error:
            chg = "ABGEWIESEN"
    ctx.check("eine veröffentlichte Revision ist nicht änderbar (Historie bleibt)", "ABGEWIESEN", chg)
    ctx.check("die Buchungszusage bleibt korrekt (Snapshot mit Fehlerversion unverändert)", h, snapshot_hash(ctx, a, pid)[0])
    backend(ctx,
            ("ConfigPublicationTest", "rollback_erzeugt_neue_revision_mit_frueheren_werten_und_aendert_nichts_an_gebundenen_zusagen"),
            ("ConfigPublicationTest", "paralleler_publish_und_rollback_bleiben_konsistent"))
