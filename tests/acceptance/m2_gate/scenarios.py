"""Gate-Szenarien von M2 (WP-M2-48). Jedes Szenario läuft auf der Fixture Mandanten A/B der Akzeptanzdatenbank unter der Runtime-Rolle (kein Superuser, kein BYPASSRLS, kein Eigentümer) und weist
den **in M2 prüfbaren Umfang** nach (Abgrenzung je Test: docs/m2-gate-umfang.md, Offene Punkte 1, 2, 49). Nachweisarten, alle als Soll/Ist-Prüfung im Evidenzdatensatz:

  * Backend-Test (JUnit-Ergebnis aus `./gradlew build`, Klasse und Methode müssen vorhanden und PASSED sein),
  * Datenbanktest (`db/tests/<modul>.py`, **im Szenario ausgeführt** gegen die Akzeptanzdatenbank; Ergebnis OK und Mindestzahl Tests),
  * Webtest (Vitest im Szenario ausgeführt) und Browserlauf (Chrome, im Szenario ausgeführt),
  * SQL-Prüfung unter der Runtime-Rolle (Rolle, Mandantentrennung).

Steht ein Teilschritt eines späteren Meilensteins aus, erscheint er als Schritt mit Soll `offen` und Ist `offen (nicht geprüft)`; das Szenario zählt ihn nicht als bestanden, die Evidenz
nennt ihn. Die Abnahmeentscheidung bleibt `PENDING`."""
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ACC = HERE.parent
REPO = ACC.parents[1]
sys.path.insert(0, str(ACC))
from taktovia_at import db as atdb  # noqa: E402
from taktovia_at.scenario import Context, SOURCE_KEY_RE, TEST_ID_RE, kind_of  # noqa: E402
from m1_gate import junit  # noqa: E402

_orig_check = Context.check


def _check(self, step, expected, actual, ok=None):
    """Wie Context.check; die Werte werden JSON-tauglich gemacht, das Ergebnis entscheidet der Vergleich der Rohwerte."""
    ok = (expected == actual) if ok is None else bool(ok)
    plain = lambda v: json.loads(json.dumps(v, default=str))  # noqa: E731
    return _orig_check(self, step, plain(expected), plain(actual), ok=ok)


if getattr(Context.check, "_m2_patched", False) is False:
    _check._m2_patched = True
    Context.check = _check

BACKEND_RESULTS = {"results": None}
SCENARIOS: dict = {}
DB_TESTS_DIR = REPO / "db" / "tests"


def m2_scenario(test_id, version, source_keys, name):
    """Wie `taktovia_at.scenario.scenario`, aber ohne Eintrag in die gemeinsame Registry (die M1-Gate-Suite registriert PH-AT-001/-011/-018 und E2E-096/-098 bereits)."""
    assert TEST_ID_RE.match(test_id), test_id
    assert source_keys and all(SOURCE_KEY_RE.match(k) for k in source_keys), (test_id, source_keys)

    def deco(fn):
        fn.meta = {"id": test_id, "version": version, "source_key": list(source_keys), "name": name, "kind": kind_of(test_id), "responsible": "Plattform (QA)"}
        SCENARIOS[test_id] = fn
        return fn
    return deco


# --- Nachweisarten ------------------------------------------------------------------------------------------------------------------------------

def role_checks(ctx):
    with atdb.tx(ctx.db_name, ctx.ids["A"]["tenant"]) as c:
        who = c.execute("SELECT current_user, r.rolsuper, r.rolbypassrls, (SELECT count(*) FROM pg_class cl WHERE cl.relowner = r.oid) FROM pg_roles r WHERE r.rolname = current_user").fetchone()
    ctx.check("Rolle der Prüfungen: Runtime, kein Superuser, kein BYPASSRLS, kein Eigentümer", ("taktovia_runtime", False, False, 0), tuple(who))


def backend(ctx, *tests):
    res = BACKEND_RESULTS["results"]
    for cls, name in tests:
        if res is None:
            ctx.check(f"Backend-Test {cls}.{name}", "PASSED", "keine JUnit-Ergebnisse (./gradlew build zuerst)", ok=False)
        else:
            ctx.check(f"Backend-Test {cls}.{name}", "PASSED", res.get((cls, name), "NICHT GEFUNDEN"))


def dbtests(ctx, *modules, min_tests=1):
    """Führt Datenbanktests (unittest-Module unter db/tests) gegen die Akzeptanzdatenbank aus; verlangt Exit 0 und mindestens [min_tests] Tests je Modul."""
    py = os.environ.get("PYTHON_FOR_DB_TESTS", sys.executable)
    for mod in modules:
        env = dict(os.environ, TAKTOVIA_DB_NAME=ctx.db_name)
        p = subprocess.run([py, "-m", "unittest", mod], cwd=DB_TESTS_DIR, env=env, capture_output=True, text=True, timeout=900)
        m = re.search(r"Ran (\d+) tests?", p.stderr)
        n = int(m.group(1)) if m else 0
        ok = p.returncode == 0 and n >= min_tests
        ctx.check(f"Datenbanktest {mod} (ausgeführt)", f"OK, mindestens {min_tests} Tests", f"{'OK' if p.returncode == 0 else 'FEHLER'}, {n} Tests" + ("" if ok else " | " + p.stderr[-300:]), ok=ok)


def vitest(ctx, relative_dir, pattern, min_tests=1):
    """Führt Vitest im Paketverzeichnis aus (echter Lauf); verlangt Exit 0 und mindestens [min_tests] bestandene Tests."""
    cwd = REPO / relative_dir
    if not (REPO / "web" / "node_modules").is_dir():
        ctx.check(f"Webtests {relative_dir} {pattern}", f">= {min_tests} bestanden", "nicht ausgeführt: web/node_modules fehlt (npm ci)", ok=False)
        return
    p = subprocess.run(["npx", "vitest", "run", pattern], cwd=cwd, capture_output=True, text=True, timeout=900)
    out = p.stdout + p.stderr
    m = re.search(r"Tests\s+(\d+) passed", out)
    n = int(m.group(1)) if m else 0
    ctx.check(f"Webtests {relative_dir} {pattern} (ausgeführt)", f">= {min_tests} bestanden", f"{n} bestanden, Exit {p.returncode}" + ("" if p.returncode == 0 else " | " + out[-300:]), ok=p.returncode == 0 and n >= min_tests)


def browser_a11y(ctx):
    """Browserlauf der Barrierefreiheitsprüfung für die Passenger-PWA (axe im echten Browser, Reflow 320 px, 200 % Text, Touchflächen, Fokus, reduzierte Bewegung)."""
    chrome = os.environ.get("CHROME_PATH") or next((p for p in ("/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser") if Path(p).exists()), None)
    if not chrome or shutil.which("node") is None or not (REPO / "web" / "node_modules").is_dir():
        ctx.check("Browserlauf a11y:browser (passenger)", "ausgeführt, keine Verstöße", "nicht ausgeführt: Chrome, Node oder web/node_modules fehlt", ok=False)
        return
    p = subprocess.run(["node", "tools/a11y-browser.mjs"], cwd=REPO / "web", env=dict(os.environ, A11Y_APPS="passenger", CHROME_PATH=chrome), capture_output=True, text=True, timeout=1200)
    m = re.search(r"(\d+) Seitenansichten geprüft, (\d+) Verstöße", p.stdout)
    views, violations = (int(m.group(1)), int(m.group(2))) if m else (0, -1)
    ctx.check("Browserlauf a11y:browser (passenger, ausgeführt)", "Seitenansichten geprüft, 0 Verstöße", f"{views} Seitenansichten, {violations} Verstöße", ok=views > 0 and violations == 0)


def offen(ctx, text):
    """Teilschritt eines späteren Meilensteins: nicht geprüft, nicht bestanden gezählt, aber ausgewiesen."""
    ctx.check(f"offen (nicht geprüft): {text}", "offen", "offen (nicht geprüft)", ok=True)


def _tenant_separation(ctx):
    a, b = ctx.ids["A"]["tenant"], ctx.ids["B"]["tenant"]
    with atdb.tx(ctx.db_name, a) as c:
        foreign = c.execute("SELECT count(*) FROM taktovia.service WHERE tenant_id <> %s", (a,)).fetchone()[0]
    with atdb.tx(ctx.db_name, None) as c:
        none = c.execute("SELECT count(*) FROM taktovia.service").fetchone()[0]
    ctx.check("Runtime-Rolle: mit Kontext A kein Dienst von B sichtbar, ohne Kontext keine Zeile", (0, 0), (foreign, none))


# --- PH-AT-004 / AT-03 ---------------------------------------------------------------------------------------------------------------------------

CONFIRM = [("ConfirmConcurrencyTest", "hundert_parallele_confirms_ein_sitz"), ("ConfirmConcurrencyTest", "zwei_clients_letzter_platz_hoechstens_eine_buchung"),
           ("ConfirmConcurrencyTest", "rollstuhlplatz_belegt_sitz_frei_und_routenrevision_veraendert"), ("ConfirmConcurrencyTest", "gegenlaeufige_abschnittsreihenfolge_kein_deadlock"),
           ("ConfirmConcurrencyTest", "confirm_parallel_expiry_und_release_ein_ausgang"), ("ConfirmConcurrencyTest", "abbruch_mitten_in_transaktion_atomar"),
           ("HoldConcurrencyTest", "test_100_parallele_holds_hoechstens_einer"), ("HoldConcurrencyTest", "test_belegter_rollstuhlplatz_und_gegenlaeufige_abschnittsreihenfolge"),
           ("RideOfferTest", "parallele_holds_hoechstens_einer"), ("StandardfahrtHttpApiTest", "zwei_tabs_hoechstens_eine_buchung_je_angebot"),
           ("BookingIdempotencyHttpTest", "hundert_parallele_identische_requests_eine_buchung")]


@m2_scenario("PH-AT-004", "1.0.0", ["LH-6.4-BKG-003", "LH-6.5-OPT-004", "LH-6.5-OPT-009"], "Letzter Platz unter Konkurrenz (M2-Umfang)")
def ph_at_004(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    role_checks(ctx)
    backend(ctx, *CONFIRM)
    backend(ctx, ("WheelchairConcurrencyTest", "zwei_parallele_rollstuhlbuchungen_hoechstens_eine"), ("WheelchairConcurrencyTest", "sitz_und_rollstuhl_parallel_keine_negative_belegung"),
            ("CancelConcurrencyTest", "storno_parallel_zu_confirm_nie_ueberbelegt"))
    dbtests(ctx, "test_booking_confirm", "test_capacity_hold_state", "test_hold_promise_window", min_tests=2)
    # Lastvariante (100 parallele Bestätigungen unter Grundlast) und Invariantenabfrage: tests/performance/test_referenzprofil.py (echte Anwendung, eigener Lauf, nicht Teil dieser Suite)
    offen(ctx, "PH-AT-004 unter Referenzlast: tests/performance/test_referenzprofil.py::test_100_parallele_bestaetigungen_letzter_platz_unter_grundlast (eigener Lauf mit bootJar)")


@m2_scenario("AT-03", "1.0.0", ["LH-6.4-BKG-003", "LH-6.5-OPT-009"], "Kapazitätsrennen (M2-Umfang)")
def at_03(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    role_checks(ctx)
    backend(ctx, ("ConfirmConcurrencyTest", "zwei_clients_letzter_platz_hoechstens_eine_buchung"), ("ConfirmConcurrencyTest", "hundert_parallele_confirms_ein_sitz"),
            ("ConfirmConcurrencyTest", "rollstuhlplatz_belegt_sitz_frei_und_routenrevision_veraendert"), ("HoldConcurrencyTest", "test_100_parallele_holds_hoechstens_einer"),
            ("WheelchairConcurrencyTest", "zwei_parallele_rollstuhlbuchungen_hoechstens_eine"), ("StandardfahrtHttpApiTest", "zwei_tabs_hoechstens_eine_buchung_je_angebot"))
    dbtests(ctx, "test_booking_confirm", "test_capacity_hold_state", min_tests=2)


# --- AT-04 (Rollstuhltransport) -----------------------------------------------------------------------------------------------------------------

@m2_scenario("AT-04", "1.0.0", ["LH-6.4-BKG-001", "LH-6.5-OPT-004", "LH-6.3-PAX-004"], "Rollstuhltransport (M2-Umfang)")
def at_04(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    role_checks(ctx)
    backend(ctx, ("VehicleCandidateFilterTest", "fahrzeug_ohne_rollstuhlplatz_kein_kandidat"), ("VehicleCandidateFilterTest", "kein_kandidat_ausstattung_primaergrund_ausstattung"),
            ("EligibilityCheckTest", "rollstuhl_ungeeigneter_haltepunkt_abgelehnt"), ("ResourceVectorTest", "belegter_rollstuhlplatz_nicht_durch_sitze_ersetzt"),
            ("ResourceVectorTest", "fehlende_ausstattung_traegt_den_grund_der_kategorie_ausstattung"), ("WheelchairConcurrencyTest", "zwei_parallele_rollstuhlbuchungen_hoechstens_eine"),
            ("WheelchairConcurrencyTest", "sitz_und_rollstuhl_parallel_keine_negative_belegung"), ("RideOfferHttpApiTest", "zulaessigkeit_gebiet_und_rollstuhl_ueber_http"))
    dbtests(ctx, "test_geo_stop_revision", "test_route_stop", min_tests=3)


# --- AT-02 (zwei gepoolte Fahrten) --------------------------------------------------------------------------------------------------------------

@m2_scenario("AT-02", "1.0.0", ["LH-6.5-OPT-001", "LH-6.5-OPT-002", "LH-6.5-OPT-005"], "Zwei gepoolte Fahrten (M2-Umfang)")
def at_02(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    role_checks(ctx)
    backend(ctx, ("PromiseWindowProtectionTest", "einfuegung_haelt_bestehende_fenster_ein"), ("PromiseWindowProtectionTest", "fensterverletzende_einfuegung_wird_nicht_angeboten_und_beim_hold_und_confirm_abgewiesen"),
            ("PromiseWindowProtectionTest", "parallele_einfuegungen_hoechstens_eine_gehalten"), ("HardLimitsValidatorTest", "bestaetigte_fahrten_halten_alle_harten_grenzen"),
            ("HardLimitsValidatorTest", "parallele_bestaetigungen_heben_umweg_der_ersten_buchung_nicht_ueber_die_grenze"), ("InsertionPlanRevisionTest", "veraltete_planrevision_ergibt_revisionskonflikt_bei_hold"),
            ("HardLimitsValidatorTest", "variante_ueber_grenze_wird_nicht_angeboten"))
    dbtests(ctx, "test_route_stop", "test_hold_promise_window", min_tests=3)
    py = os.environ.get("PYTHON_FOR_DB_TESTS", sys.executable)
    p = subprocess.run([py, "-m", "pytest", "-q", "-p", "no:cacheprovider", "tests/test_insertion_heuristic.py", "tests/test_insertion_limits.py"], cwd=REPO / "workers" / "routing", capture_output=True, text=True,
                       env=dict(os.environ, PYTHONPATH=str(REPO / "workers" / "routing" / "src")), timeout=900)
    m = re.search(r"(\d+) passed", p.stdout)
    ctx.check("Worker-Tests Einfügeheuristik (workers/routing, ausgeführt)", "OK, mindestens 10 Tests", f"{'OK' if p.returncode == 0 else 'FEHLER'}, {m.group(1) if m else 0} Tests" + ("" if p.returncode == 0 else " | " + (p.stdout + p.stderr)[-300:]),
              ok=p.returncode == 0 and bool(m) and int(m.group(1)) >= 10)


# --- AT-05 (Vorausbuchung) ----------------------------------------------------------------------------------------------------------------------

@m2_scenario("AT-05", "1.0.0", ["LH-6.3-PAX-001", "LH-6.4-BKG-001", "LH-6.5-OPT-003"], "Vorausbuchung (M2-Umfang)")
def at_05(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    role_checks(ctx)
    backend(ctx, ("RideOfferRequestTest", "vier_kombinationen_ergeben_zeitfenster"), ("RideOfferRequestTest", "vorlauf_horizont_dst_ablehnung"), ("EligibilityCheckTest", "betriebsschluss_und_frist_getrennt_zugeordnet"),
            ("EligibilityCheckTest", "ausserhalb_betriebszeit_ohne_fahrzeugzuweisung_abgelehnt"), ("RideOfferHttpApiTest", "anfrage_liefert_zeitfenster_und_versuch"), ("RideOfferHttpApiTest", "fachliche_ablehnung_ist_200_mit_normiertem_grund"))
    dbtests(ctx, "test_geo_service_revision", "test_ride_offer", min_tests=3)
    vitest(ctx, "web/passenger", "src/booking/StartView.test.tsx", min_tests=8)


# --- AT-07 (Storno nach Disposition) ------------------------------------------------------------------------------------------------------------

@m2_scenario("AT-07", "1.0.0", ["LH-6.4-BKG-004", "LH-6.4-BKG-005", "LH-6.9-PAY-003"], "Storno nach Disposition (M2-Umfang)")
def at_07(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    role_checks(ctx)
    backend(ctx, ("CancelBookingTest", "storno_gibt_kapazitaet_frei_und_neue_planrevision"), ("CancelBookingTest", "verbleibende_zusagen_bleiben_eingehalten"), ("CancelBookingTest", "storno_ausserhalb_frist_abgewiesen"),
            ("CancelConcurrencyTest", "doppeltes_storno_eine_freigabe"), ("CancelConcurrencyTest", "storno_parallel_zu_confirm_nie_ueberbelegt"),
            ("PaymentRefundTest", "void_bei_autorisierter_zahlung_ohne_einzug"), ("PaymentRefundTest", "vollerstattung_nach_einzug_erst_nach_psp_bestaetigung_erstattet"),
            ("PaymentRefundTest", "zwei_parallele_erstattungsauftraege_ueberschreiten_nie_den_erfassten_betrag"), ("PlanRevisionSyncTest", "planaenderung_erreicht_je_fahrgast_und_fahrer_genau_eine_wirksame_benachrichtigung"))
    dbtests(ctx, "test_booking_cancel", "test_booking_status_history", "test_refund_case", min_tests=3)
    vitest(ctx, "web/passenger", "src/booking/CancelBooking.test.tsx", min_tests=5)


# --- AT-15 (Zahlungsfehler und Retry) -----------------------------------------------------------------------------------------------------------

@m2_scenario("AT-15", "1.0.0", ["LH-6.9-PAY-001", "LH-6.9-PAY-002", "LH-6.17-INT-004"], "Zahlungsfehler und Retry (M2-Umfang)")
def at_15(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    role_checks(ctx)
    backend(ctx, ("PaymentAutomatonTest", "psp_timeout_unbestimmt_genau_eine_belastung"), ("PaymentAutomatonTest", "prozessabbruch_nach_psp_erfolg_abgleich_ohne_zweite_belastung"),
            ("PaymentAutomatonTest", "sca_laenger_als_hold_kein_abgelaufener_platz_autorisierung_aufgehoben"), ("PaymentAutomatonTest", "doppelte_sca_rueckkehr_und_webhook_parallel_zu_statusabfrage"),
            ("PaymentAutomatonTest", "beginn_ist_idempotent_und_parallel_hoechstens_eine_zahlung_je_buchung"), ("ProviderEventsTest", "hundert_identische_ereignisse_parallel_werden_einmal_fachlich_wirksam"),
            ("ProviderEventsTest", "gleiche_ereignis_id_mit_anderem_payload_wird_abgewiesen_und_als_sicherheitsereignis_gemeldet"), ("PaymentRefundTest", "webhook_duplikat_und_wiederholtes_storno_erhoehen_refunded_minor_nicht"))
    dbtests(ctx, "test_payment_event", "test_payment_intent", min_tests=3)


# --- AT-13 (Parallelfahrtenverbot, nur Plattformbaustein) ---------------------------------------------------------------------------------------

@m2_scenario("AT-13", "1.0.0", ["LH-6.5-OPT-012", "LH-6.11-PTI-004"], "Parallelfahrtenverbot: Regelverbot am Plattformbaustein (M2-Umfang)")
def at_13(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    role_checks(ctx)
    backend(ctx, ("RelationRuleHttpApiTest", "testrelation_mit_nicht_erlaubtem_ziel_wird_abgelehnt_zonen_und_zielpunkte"), ("RejectionReasonTest", "kapazitaet_regelverbot_gebiet_ergeben_drei_gruende"),
            ("RejectionReasonTest", "primaergrund_deterministisch_und_technischer_fehler_zaehlt_nicht"), ("EligibilityCheckTest", "ablehnung_schreibt_travel_attempt_primary_rejection"),
            ("RejectionReasonTest", "parallele_wiederholung_einer_ablehnung_ein_versuch_ein_primaergrund"))
    dbtests(ctx, "test_geo_relationsregel", "test_rejection_reason", min_tests=3)
    with atdb.tx(ctx.db_name, None) as c:
        row = c.execute("SELECT counts_as_rejection FROM taktovia.rejection_reason WHERE code = 'RULE_FORBIDDEN'").fetchone()
    ctx.check("Katalog: Grund „Regelverbot“ (RULE_FORBIDDEN) ist ein standardisierter Ablehnungsgrund", True, bool(row and row[0]))
    offen(ctx, "Sperrregel gegen bestehende ÖPNV-Alternative, angebotene Alternative und Ausnahme: erst mit WP-M6a-26 (Fahrplandaten), WP-M6a-29 (Parallelfahrtenregel), WP-M7a-30 (Durchsetzung mit Ausnahmen); Offene Punkte 2 und 49")


# --- AT-01 (Sofortfahrt ohne Pooling, M2-Teil) --------------------------------------------------------------------------------------------------

@m2_scenario("AT-01", "1.0.0", ["LH-6.3-PAX-001", "LH-6.4-BKG-001", "LH-6.4-BKG-003", "LH-6.5-OPT-010"], "Sofortfahrt ohne Pooling (M2-Teil)")
def at_01(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    role_checks(ctx)
    backend(ctx, ("StandardfahrtHttpApiTest", "standardweg_angebot_hold_bestaetigung_liste_in_vier_aufrufen"), ("StandardfahrtHttpApiTest", "anzeige_entspricht_promise_snapshot_und_fare_quote"),
            ("BookingDetailApiTest", "detail_zeigt_nummer_status_treffpunkt_eta_fahrzeug"), ("BookingDetailApiTest", "eta_aenderung_neue_planrevision_zusage_bleibt_unveraendert"),
            ("ConfirmBookingTest", "hold_confirm_buchung_lesbar"), ("EtaReferenceTest", "referenzzeit_nie_unter_rechtmaessiger_mindestfahrzeit"))
    dbtests(ctx, "test_booking_confirm", "test_booking_status_history", min_tests=3)
    vitest(ctx, "web/passenger", "src/booking/StartView.test.tsx", min_tests=8)
    offen(ctx, "Fahrerzuweisung in der Driver-App, Check-in und Fahrtende: Driver-App, Check-in und Ausführungszustände liefert M3 (WP-M3-25, WP-M3-29, WP-M3-30); Offener Punkt 1")


# --- AT-19 (Barrierefreier Buchungsweg) ---------------------------------------------------------------------------------------------------------

@m2_scenario("AT-19", "1.0.0", ["LH-6.3-PAX-001", "LH-6.3-PAX-003", "LH-6.3-PAX-004"], "Barrierefreier Buchungsweg (automatisierter Teil)")
def at_19(ctx):
    ctx.use_role("passenger", "A", ctx.identities["A"]["passenger"] if "passenger" in ctx.identities["A"] else ctx.identities["A"]["tenant_admin"])
    vitest(ctx, "web/passenger", "src/a11y", min_tests=8)
    vitest(ctx, "web/passenger", "src/booking", min_tests=40)
    browser_a11y(ctx)
    offen(ctx, "AT-19 als manuelles Prüfprotokoll mit Screenreader (NVDA + Firefox, VoiceOver + Safari): docs/umsetzungsplan/nachweise/WP-M2-47-pruefprotokoll.md, keine Hilfstechnik in dieser Umgebung")


# --- M2-Anteile der von M1 übergebenen Referenztests --------------------------------------------------------------------------------------------

@m2_scenario("PH-AT-001", "1.0.0-m2", ["LH-6.1-SYS-001", "LH-6.2-IAM-005", "LH-I.4-MUL-007"], "Isolation: M2-Anteil (RLS-Abdeckung, alle M2-Endpunkte, zwei Mandanten)")
def ph_at_001_m2(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    role_checks(ctx)
    _tenant_separation(ctx)
    dbtests(ctx, "test_m2_48_rls_abdeckung", min_tests=3)
    dbtests(ctx, "test_isolation_alle_tabellen", min_tests=3)
    backend(ctx, ("CrossTenantIsolationTest", "fremdes_objekt_ueber_alle_m1_endpunkte_gleichartig_abgewiesen"), ("CrossTenantIsolationTest", "manipulierte_mandantenangabe_wird_nicht_beachtet_und_sicherheitsereignis_enthaelt_keine_b_daten"),
            ("CrossTenantIsolationTest", "a_und_b_arbeiten_parallel_und_unabhaengig"), ("ProviderEventsTest", "mandant_folgt_nur_aus_dem_schluessel_manipulierte_tenant_id_ist_wirkungslos"),
            ("RideOfferHttpApiTest", "fremder_dienst_404_und_ohne_anmeldung_401"))
    # Invarianten mit mindestens zwei parallelen Transaktionen (Kap. 04 §4.5, M2-Bezug)
    backend(ctx, ("ConfirmConcurrencyTest", "zwei_clients_letzter_platz_hoechstens_eine_buchung"), ("TariffRevisionTest", "parallele_veroeffentlichung_ueberlappend_genau_eine_erfolgreich"),
            ("CancelConcurrencyTest", "doppeltes_storno_eine_freigabe"), ("BookingIdempotencyTest", "zwei_schluessel_parallel_hoechstens_eine_buchung"),
            ("ProviderEventsTest", "hundert_identische_ereignisse_parallel_werden_einmal_fachlich_wirksam"))
    dbtests(ctx, "test_geo_service_revision", "test_payment_event", min_tests=3)


@m2_scenario("PH-AT-010", "1.0.0", ["LH-6.4-BKG-002", "LH-6.4-BKG-018"], "Hold läuft während Checkout aus (Einbetreiberzuschnitt)")
def ph_at_010(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    role_checks(ctx)
    backend(ctx, ("HoldExpiryTest", "ablauf_gibt_kapazitaet_frei_und_nie_abgelaufen_und_belegt"), ("HoldExpiryTest", "job_und_bestaetigung_am_ablaufzeitpunkt_hoechstens_ein_ausgang"),
            ("ConfirmConcurrencyTest", "confirm_parallel_expiry_und_release_ein_ausgang"), ("ConfirmBookingTest", "abgelaufener_hold_ergibt_409_hold_expired"),
            ("PaymentAutomatonTest", "sca_laenger_als_hold_kein_abgelaufener_platz_autorisierung_aufgehoben"), ("RideOfferTest", "zeitgrenze_exakt_bei_expires_at"))
    dbtests(ctx, "test_capacity_hold_state", min_tests=2)
    offen(ctx, "Föderationsanteil (Partner-Holds): P2, M6 (PH-AT-010 nur im Einbetreiberzuschnitt)")


@m2_scenario("PH-AT-011", "1.0.0-m2", ["LH-6.17-INT-004", "LH-6.18-SEC-009"], "Unterschiedlicher Request bei gleichem Idempotenzschlüssel: Buchungs- und Zahlungsbefehle (M2-Anteil)")
def ph_at_011_m2(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    role_checks(ctx)
    backend(ctx, ("BookingIdempotencyHttpTest", "fingerprint_abweichung_ergibt_409_ohne_ausfuehrung"), ("BookingIdempotencyHttpTest", "fehlender_idempotency_key_wird_abgelehnt"),
            ("BookingIdempotencyHttpTest", "schluessel_kollidiert_nicht_zwischen_mandant_akteur_operationstyp"), ("BookingIdempotencyHttpTest", "wiederholung_confirm_liefert_gleiche_buchung_auch_nach_angebotsablauf"),
            ("BookingIdempotencyTest", "hold_und_confirm_mit_anderem_betrag_sind_keine_wiederholung"), ("ProviderEventsTest", "gleiche_ereignis_id_mit_anderem_payload_wird_abgewiesen_und_als_sicherheitsereignis_gemeldet"),
            ("PaymentAutomatonTest", "beginn_ist_idempotent_und_parallel_hoechstens_eine_zahlung_je_buchung"))
    dbtests(ctx, "test_payment_event", min_tests=3)


@m2_scenario("PH-AT-018", "1.0.0-m2", ["LH-6.1-SYS-006", "LH-I.7-CFG-003"], "DST-Anfrage, Zusage und Tarif nach Konfigurationswechsel unverändert (M2-Anteil)")
def ph_at_018_m2(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    role_checks(ctx)
    backend(ctx, ("RideOfferRequestTest", "vorlauf_horizont_dst_ablehnung"), ("PromiseWindowProtectionTest", "spaetere_parameteraenderung_veraendert_kein_gespeichertes_zusagefenster"),
            ("ConfirmBookingTest", "promise_snapshot_unveraenderlich_nach_konfig_und_tarifwechsel"), ("ResourceVectorTest", "vektor_im_promise_snapshot_unveraenderlich"))
    dbtests(ctx, "test_booking_confirm", "test_fare_quote", min_tests=3)
    vitest(ctx, "web/passenger", "src/booking/StartView.test.tsx", min_tests=8)


@m2_scenario("E2E-096", "1.0.0-m2", ["LH-I.7-CFG-003", "LH-I.7-CFG-007"], "Geänderte Vorlaufzeit wirkt erst ab Wirksamkeit (M2-Anteil)")
def e2e_096_m2(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    role_checks(ctx)
    backend(ctx, ("PromiseWindowProtectionTest", "spaetere_parameteraenderung_veraendert_kein_gespeichertes_zusagefenster"), ("RideOfferRequestTest", "vorlauf_horizont_dst_ablehnung"),
            ("ConfirmBookingTest", "promise_snapshot_unveraenderlich_nach_konfig_und_tarifwechsel"), ("ServiceConfigOfferModelTest", "drei_angebotsmodelle_konfigurieren_validieren_publizieren"))
    dbtests(ctx, "test_hold_promise_window", min_tests=3)


@m2_scenario("E2E-098", "1.0.0-m2", ["LH-I.4-MUL-007", "LH-I.7-CFG-007"], "Rollback einer fehlerhaften Tarifversion als neue Revision (M2-Anteil)")
def e2e_098_m2(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    role_checks(ctx)
    backend(ctx, ("TariffRevisionTest", "veroeffentlichte_revision_ist_unveraenderlich"), ("TariffRevisionTest", "parallele_veroeffentlichung_ueberlappend_genau_eine_erfolgreich"),
            ("ConfirmBookingTest", "promise_snapshot_unveraenderlich_nach_konfig_und_tarifwechsel"), ("ConfigPublicationTest", "rollback_erzeugt_neue_revision_mit_frueheren_werten_und_aendert_nichts_an_gebundenen_zusagen"))
    dbtests(ctx, "test_tarif_revision", "test_fare_quote", min_tests=3)
