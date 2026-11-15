"""Gate-Szenarien von M3 (WP-M3-56). Jedes Szenario läuft auf der Fixture Mandanten A/B der Akzeptanzdatenbank unter der Runtime-Rolle (kein Superuser, kein BYPASSRLS, kein Eigentümer) mit **beiden**
Mandanten und weist den **in M3 prüfbaren Umfang** nach (Abgrenzung je Test: docs/m3-gate-umfang.md, Offene Punkte OP-12, OP-13, OP-21). Nachweisarten wie in der M2-Suite, alle als Soll/Ist-Prüfung im
Evidenzdatensatz:

  * Backend-Test (JUnit-Ergebnis aus `./gradlew build`; Klasse und Methode müssen vorhanden und PASSED sein; die Zwei-Mandanten-Fixtures, die Gerätesimulation für Offline-/Sync-Fälle und die
    Parallelitätsharness (`PhAt020SafetyHarnessTest`) sind Teil dieser Backend-Tests),
  * Datenbanktest (`db/tests/<modul>.py`, **im Szenario ausgeführt** gegen die Akzeptanzdatenbank),
  * Webtest (Vitest im Szenario ausgeführt),
  * SQL-Prüfung unter der Runtime-Rolle (Rolle, Mandantentrennung).

Steht ein Teilschritt eines späteren Meilensteins oder einer Fachentscheidung aus (oder ist er in dieser Umgebung nicht ausführbar: Flutter, Browser), erscheint er als Schritt mit Soll `offen` und Ist
`offen (nicht geprüft)`; das Szenario zählt ihn nicht als bestanden, die Evidenz nennt ihn. Die Abnahmeentscheidung bleibt `PENDING`."""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ACC = HERE.parent
REPO = ACC.parents[1]
sys.path.insert(0, str(ACC))
from taktovia_at import db as atdb  # noqa: E402
from taktovia_at.scenario import SOURCE_KEY_RE, TEST_ID_RE, kind_of  # noqa: E402
from m2_gate import scenarios as m2  # noqa: E402
from m2_gate.scenarios import backend, dbtests, offen, role_checks, vitest  # noqa: E402,F401

SCENARIOS: dict = {}


def m3_scenario(test_id, version, source_keys, name):
    """Wie `taktovia_at.scenario.scenario`, ohne Eintrag in die gemeinsame Registry (die M1- und M2-Suite registrieren ihre Szenarien dort)."""
    assert TEST_ID_RE.match(test_id), test_id
    assert source_keys and all(SOURCE_KEY_RE.match(k) for k in source_keys), (test_id, source_keys)

    def deco(fn):
        fn.meta = {"id": test_id, "version": version, "source_key": list(source_keys), "name": name, "kind": kind_of(test_id), "responsible": "Plattform (QA)"}
        SCENARIOS[test_id] = fn
        return fn
    return deco


def both_tenants(ctx):
    """Beide Mandanten der Fixture unter der Runtime-Rolle: A sieht nichts von B und umgekehrt, ohne Kontext keine Zeile (Akzeptanzkriterium 4)."""
    role_checks(ctx)
    a, b = ctx.ids["A"]["tenant"], ctx.ids["B"]["tenant"]
    seen = {}
    for me, other in ((a, b), (b, a)):
        with atdb.tx(ctx.db_name, me) as c:
            seen[str(me)[:8]] = (c.execute("SELECT count(*) FROM taktovia.service WHERE tenant_id <> %s", (me,)).fetchone()[0], c.execute("SELECT count(*) FROM taktovia.vehicle WHERE tenant_id <> %s", (me,)).fetchone()[0])
    ctx.check("Runtime-Rolle, beide Mandanten: kein Dienst und kein Fahrzeug des jeweils anderen Mandanten sichtbar", {str(a)[:8]: (0, 0), str(b)[:8]: (0, 0)}, seen)


def endpoint_contract(ctx):
    """Vertragsprüfungen je M3-Endpunkt (Kap. 05 §5.8): Manipulation `tenant_id`, fremde ID 404, fehlendes If-Match 428, veraltete Revision 412, gleicher Schlüssel mit anderem Inhalt 409, 100 identische Wiederholungen."""
    backend(ctx, ("CrossTenantIsolationTest", "fremdes_objekt_ueber_alle_m1_endpunkte_gleichartig_abgewiesen"), ("ContractCoverageTest", "alle_m1_endpunkte_positiv_und_fehlerfall"),
            ("DriverApiTest", "hundert_identische_wiederholungen_ueber_http_ergeben_eine_wirkung"), ("DriverApiTest", "manipulierte_ereignis_id_gleiche_id_anderer_inhalt_und_fremdes_ereignis"),
            ("DriverApiTest", "rechte_fremde_schicht_fremder_mandant_und_tenant_id_im_body"), ("InspectionSubmitApiTest", "if_match_fehlt_428_veraltet_412"),
            ("InspectionSubmitApiTest", "gleicher_schluessel_anderer_inhalt_ergibt_409_idempotency_conflict"), ("PlanRevisionDeliveryHttpTest", "bestaetigung_ueber_http_428_412_200_zwei_geraete_fremde_schicht_und_rolle"),
            ("OperationalBlockApiTest", "idempotente_wiederholung_und_zwei_disponenten_ein_aktiver_sperreintrag"), ("DriverSyncTest", "hundert_identische_wiederholungen_ergeben_eine_wirkung"),
            ("DriverEventEffectsHttpTest", "offline_checkin_auf_alter_planrevision_ist_review_required_ohne_befoerderung"))


def ui_cases(ctx, *, ui07=False, ui11=False):
    """UI-Abnahmefälle (Kap. 06 §6.9) als prüfbare Fachzustände der Operator-Oberfläche (Vitest, ausgeführt). UI-09/UI-10 (Driver-App) laufen in Flutter und sind hier nicht ausführbar."""
    if ui07:
        vitest(ctx, "web/operator", "src/alarm", min_tests=15)
        vitest(ctx, "web/operator", "src/vehicles/VehicleList.test.tsx", min_tests=5)
    if ui11:
        vitest(ctx, "web/operator", "src/flotte", min_tests=20)
        vitest(ctx, "web/operator", "src/inspection", min_tests=5)


def flutter_offen(ctx, what):
    offen(ctx, f"{what}: Driver-App (Flutter, UI-09/UI-10 mit Offline-Sync und 412-Konflikt ohne stilles Überschreiben): Dart-Tests unter mobile/driver/test sind vorhanden, Flutter ist in dieser Umgebung nicht installiert (nicht ausgeführt)")


# --- PH-AT-020 ---------------------------------------------------------------------------------------------------------------------------------

@m3_scenario("PH-AT-020", "1.0.0", ["LH-6.21-CHK-001", "LH-6.22-DMG-001", "LH-6.5-OPT-002", "LH-I.8-AUT-001"], "Sichere Fahrzeugfreigabe vor und während Disposition")
def ph_at_020(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    both_tenants(ctx)
    # Kapitel 04 §4.5: Kapazität, Qualifikation und Fahrzeugfreigabe unter Konkurrenz (Sperre, Qualifikationsentzug, Fahrzeugwechsel, Schichtüberlappung, Umplanung), je mit mindestens zwei parallelen Transaktionen
    backend(ctx, ("ReadinessTest", "sperre_und_dispositionspruefung_parallel_nie_zusage_auf_gesperrtes_fahrzeug"), ("ReadinessTest", "qualifikationsentzug_parallel_zur_freigabe_in_jeder_reihenfolge_nicht_disponierbar"),
            ("ReadinessTest", "sperre_widerruft_im_selben_commit_jede_freigabe_und_erhoeht_die_revision"), ("ReadinessTest", "je_fehlende_bedingung_keine_freigabe"), ("ReadinessTest", "freigabe_bei_erfuellten_bedingungen"),
            ("ReadinessHoldTest", "ohne_freigabe_keine_zusage_mit_freigabe_ja_nach_sperre_nein"), ("ReadinessHoldTest", "sperre_und_neue_zusage_parallel_nie_zusage_nach_der_sperre"),
            ("OperationalBlockHoldTest", "sperre_parallel_zu_hold_commit_keine_zusage"), ("VehicleSwitchTest", "wechsel_parallel_zu_zuweisung_keine_zusage_vor_pruefung"),
            ("VehicleSwitchTest", "pruefabschluss_parallel_zu_neuer_sperre_keine_freigabe"), ("CriticalDefectBlockTest", "submit_mit_kritischem_nein_parallel_zur_dispositionspruefung_keine_zusage"),
            ("ImmediateBlockTest", "schaden_und_zuweisung_parallel_keine_zusage"), ("VehicleServiceStatusRaceTest", "stoerung_parallel_zu_zuweisung_keine_zusage"),
            ("ReadinessGateTest", "bereitmeldung_parallel_zu_freigabewiderruf"), ("InspectionSubmitTest", "submit_parallel_zu_neuer_sperre_keine_freigabe"),
            ("OfflineInspectionSyncTest", "gleichzeitige_sperre_und_offline_sync_ergeben_nie_freigabe_trotz_sperre"), ("RepairReleaseTest", "freigabe_parallel_zu_neuer_kritischer_meldung_bleibt_das_fahrzeug_gesperrt"),
            ("ShiftTakeoverConcurrencyTest", "leitstelle_und_planer_weisen_dieselbe_person_parallel_zu_hoechstens_eine_verbindliche_schicht"), ("ManualReplanTest", "veraltete_revision_ergibt_412_mit_aktueller_revision_und_schreibt_nichts"),
            ("OutageReplacementTest", "umbuchung_parallel_zu_fahrgaststorno"), ("OutageReplacementTest", "zwei_disponenten_gleicher_incident_eine_entscheidung"))
    # Parallelitätsharness: mindestens 100 Durchläufe in beiden Reihenfolgen und im Wettlauf ohne Fehlzusage, dazu die Negativkontrolle (deaktivierter Safety-Check macht die Harness rot)
    backend(ctx, ("PhAt020SafetyHarnessTest", "hundertzwanzig_durchlaeufe_beide_reihenfolgen_und_wettlauf_ohne_fehlzusage"), ("PhAt020SafetyHarnessTest", "negativkontrolle_ohne_safety_check_wird_die_harness_rot"))
    dbtests(ctx, "test_m3_13_pruefstart", "test_driver_release", "test_defect_state", min_tests=3)
    endpoint_contract(ctx)


# --- AT-08 (Fahrer No-Show) ----------------------------------------------------------------------------------------------------------------------

@m3_scenario("AT-08", "1.0.0", ["LH-6.6-DRV-005", "LH-6.4-BKG-014", "LH-I.6-KPI-009"], "Fahrer No-Show (M3-Umfang)")
def at_08(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    both_tenants(ctx)
    backend(ctx, ("DriverEventServiceTest", "ankunft_startet_wartefrist"), ("DriverEventServiceTest", "no_show_vor_wartefrist_abgewiesen"), ("DriverEventServiceTest", "no_show_speichert_grund_wartezeit_und_regelstand"),
            ("DriverEventServiceTest", "no_show_ohne_ankunft_wird_zum_klaerfall"), ("DriverEventEffectsHttpTest", "no_show_nach_wartefrist_speichert_grund_wartezeit_und_regelstand_ohne_gebuehr"),
            ("DriverEventEffectsHttpTest", "no_show_vor_ablauf_der_wartefrist_ist_422_ohne_wirkung"), ("DriverEventEffectsHttpTest", "no_show_ohne_ankunft_ist_ein_klaerfall"),
            ("NotPerformedCauseTest", "fahrzeugausfall_und_no_show_getrennt_erfasst_und_ursache_offen_sichtbar"), ("NotPerformedCauseTest", "no_show_rate_ist_im_kennzahlregister_ausfuehrbar_mit_nenner_und_datenstand"))
    dbtests(ctx, "test_m3_24_fahrer_eingang", "test_trip_not_performed", min_tests=3)
    offen(ctx, "Abrechnung regelkonform (Gebühr bzw. Erstattung bei No-Show): Zahlungs- und Gebührenregel für No-Show ist im Pflichtenheft nicht festgelegt (OP-12); in M3 abnehmbar sind Wartefrist, Grund, Wartezeit, Regelstand und Ursache/Kategorie (kein Gebühreneinzug, `ohne_gebuehr`)")


# --- AT-09 (Fahrzeugausfall) ---------------------------------------------------------------------------------------------------------------------

OUTAGE = [("OutageReplacementTest", n) for n in ("gesperrtes_fahrzeug_loest_ersatzlogik_mit_vorschlaegen_aus", "verknuepfung_schadenfall_fahrten_beide_richtungen", "buchung_endet_umgebucht_storniert_oder_offener_punkt",
                                                  "ersatz_ohne_rollstuhlplatz_wird_nicht_vorgeschlagen", "eingestiegene_fahrgaeste_nicht_automatisch_umgebucht", "fremder_mandant_incident_404",
                                                  "zwei_disponenten_gleicher_incident_eine_entscheidung", "betreiberstorno_wiederholung_keine_doppelte_erstattung", "umbuchung_parallel_zu_fahrgaststorno",
                                                  "fahrgast_informieren_genau_einmal_je_aenderung")]


@m3_scenario("AT-09", "1.0.0", ["LH-6.22-DMG-010", "LH-I.8-AUT-001", "LH-I.8-AUT-002", "LH-6.7-OPS-005"], "Fahrzeugausfall: Sperrung, betroffene Buchungen, Ersatz-/Stornofluss")
def at_09(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    both_tenants(ctx)
    backend(ctx, *OUTAGE)
    backend(ctx, ("OutageHttpApiTest", "stoerungsansicht_zeigt_betroffene_fahrt_vorschlag_und_aktionen_und_umbuchung_schliesst_den_incident"),
            ("OutageHttpApiTest", "betreiberstorno_mit_ursache_fahrzeugausfall_idempotent_und_ohne_doppelte_erstattung"), ("OutageHttpApiTest", "ersatzoptionen_pruefen_und_incident_ohne_betroffene_fahrten"),
            ("OperationalBlockTest", "gesperrtes_fahrzeug_erhaelt_keine_neue_zuweisung"), ("OperationalBlockTest", "sperre_protokolliert_akteur_grund_zeitpunkt_und_erzeugt_ereignis_zugesagte_fahrten_bleiben_zugeordnet"),
            ("IncidentWorkflowTest", "fahrzeugausfall_ergibt_genau_einen_incident_und_eine_suche"), ("IncidentWorkflowTest", "drei_ereignisse_parallel_und_100_wiederzustellungen_ein_incident"),
            ("IncidentWorkflowTest", "fehlgeschlagene_suche_incident_bleibt_offen"), ("WorkflowLogTest", "parallele_folgeaktionen_alle_eintraege_geordnet"), ("WorkflowLogTest", "testvorfall_vollstaendig_rekonstruierbar"),
            ("DispatcherBookingTest", "anlegen_aendern_stornieren_protokolliert_akteur_und_grund"), ("ManualReplanTest", "buchung_von_fahrzeug_a_auf_b_verschieben_atomar_mit_begruendung_und_verteilung"))
    dbtests(ctx, "test_stoerungsablauf", "test_workflow_regelrevision", "test_ablaufprotokoll", min_tests=3)
    ui_cases(ctx, ui07=True)
    endpoint_contract(ctx)


# --- AT-10 (GPS-Ausfall) -------------------------------------------------------------------------------------------------------------------------

@m3_scenario("AT-10", "1.0.0", ["LH-6.6-DRV-006", "LH-6.7-OPS-001"], "GPS-Ausfall: veraltet gekennzeichnet, keine fiktive Liveposition")
def at_10(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    both_tenants(ctx)
    backend(ctx, ("PositionCaptureTest", "at_10_unterbrochener_strom_zeigt_veraltet_mit_letztem_stand_ohne_extrapolation"), ("PositionCaptureTest", "nachgelieferte_positionen_werden_mit_messzeit_gespeichert_und_nicht_als_aktuell_dargestellt"),
            ("PositionCaptureTest", "verspaetete_aeltere_position_ueberschreibt_die_neuere_letzte_position_nicht"), ("PositionCaptureTest", "laufender_strom_standortalter_unter_der_schwelle"),
            ("DriverPositionApiTest", "at_10_veraltet_nach_der_schwelle_mit_letztem_stand_und_nachgelieferte_punkte_ersetzen_nicht"), ("DriverPositionApiTest", "laufender_strom_aktuelle_position_ist_nicht_veraltet_und_nur_im_angetretenen_dienst"),
            ("LiveChannelHttpTest", "zustandsaenderung_erscheint_ohne_neuladen"))
    dbtests(ctx, "test_m3_34_position", "test_m3_35_live", min_tests=3)
    vitest(ctx, "web/operator", "src/vehicles/VehicleList.test.tsx", min_tests=5)
    vitest(ctx, "web/operator", "src/live", min_tests=3)


# --- AT-11 (Offline Driver App) ------------------------------------------------------------------------------------------------------------------

@m3_scenario("AT-11", "1.0.0", ["LH-6.6-DRV-003", "LH-6.6-DRV-004", "LH-6.6-DRV-005"], "Offline Driver App: Netzverlust, Wiederverbindung, Events dedupliziert")
def at_11(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    both_tenants(ctx)
    # Gerätesimulation für Offline-/Sync-Fälle: die Backend-Tests spielen Geräteereignisse (Gerätezeit, Offline-Kennzeichen, Wiederholungen, alte Planrevision) gegen die echte Datenbank
    backend(ctx, ("DriverSyncTest", "ereignis_wirkt_einmal_mit_serverzeit_und_outbox_ereignis_gegen_schema"), ("DriverSyncTest", "hundert_identische_wiederholungen_ergeben_eine_wirkung"),
            ("DriverSyncTest", "veralteter_plan_ist_ein_klaerfall_und_statusereignisse_nicht"), ("DriverEventServiceTest", "offline_checkin_alte_planrevision_review_required"),
            ("DriverEventEffectsHttpTest", "offline_checkin_auf_alter_planrevision_ist_review_required_ohne_befoerderung"), ("PlanRevisionDeliveryHttpTest", "verlorener_push_abruf_liefert_neue_revision_und_bedingter_abruf"),
            ("BoardingCodeCheckInTest", "offline_erfasster_code_gilt_erst_nach_serverpruefung"), ("BoardingCodeCheckInTest", "offline_doppelt_verwendeter_code_ein_checkin_ein_klaerfall"),
            ("VehicleServiceStatusTest", "offline_statusmeldung_genau_einmal_wirksam_ab_serverempfang"), ("DriverRunProjectionTest", "bedingter_abruf_etag_304"))
    dbtests(ctx, "test_m3_24_fahrer_eingang", min_tests=3)
    vitest(ctx, "web/operator", "src/network", min_tests=1)
    flutter_offen(ctx, "Letzte gültige Route sichtbar im Funkloch (Driver-App)")
    endpoint_contract(ctx)


# --- AT-26 (Pflichtcheck vor Disposition) --------------------------------------------------------------------------------------------------------

@m3_scenario("AT-26", "1.0.0", ["LH-6.21-CHK-001", "LH-6.21-CHK-002", "LH-6.21-CHK-007"], "Pflichtcheck vor Disposition")
def at_26(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    both_tenants(ctx)
    backend(ctx, ("ReadinessGateTest", "bereitmeldung_ohne_pruefung_wird_serverseitig_abgewiesen"), ("ReadinessGateTest", "pruefung_anderer_kombination_oder_offener_lauf_erfuellt_nicht"),
            ("ReadinessGateApiTest", "bereitmeldung_ohne_pruefung_wird_serverseitig_abgewiesen"), ("ReadinessTest", "je_fehlende_bedingung_keine_freigabe"),
            ("InspectionSubmitApiTest", "kritischer_mangel_und_fehlende_qualifikation_ergeben_keine_freigabe_mit_fehlenden_voraussetzungen"), ("MandatoryTemplateTest", "fahrzeugvorlage_enthaelt_acht_pflichtpunkte_und_erfasst_mangel_je_punkt"),
            ("MandatoryTemplateTest", "freigabe_ohne_pflichtpunkt_wird_je_schluessel_abgewiesen"), ("PassengerCompartmentCheckTest", "freigabe_ohne_einen_der_sechs_pflichtpunkte_wird_abgewiesen"))
    dbtests(ctx, "test_m3_13_pruefstart", "test_checklistenvorlage", min_tests=3)
    vitest(ctx, "web/operator", "src/inspection", min_tests=5)
    flutter_offen(ctx, "Liste der fehlenden Prüfschritte im Driver-Prüfpfad")


# --- AT-27 (Bestandener Check) -------------------------------------------------------------------------------------------------------------------

@m3_scenario("AT-27", "1.0.0", ["LH-6.21-CHK-003", "LH-6.21-CHK-005", "LH-6.21-CHK-007"], "Bestandener Check: unveränderlicher Nachweis, Freigabe")
def at_27(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    both_tenants(ctx)
    backend(ctx, ("InspectionSubmitTest", "abschluss_enthaelt_alle_pflichtangaben"), ("InspectionSubmitTest", "mutation_nach_abschluss_ueber_api_abgewiesen"), ("InspectionSubmitApiTest", "abschluss_liefert_ergebnis_freigabe_und_unveraenderlichen_nachweis"),
            ("ReadinessTest", "freigabe_bei_erfuellten_bedingungen"), ("ReadinessQualificationTest", "gueltige_qualifikation_und_ja_geben_frei_mit_verwendeter_qualifikationsnummer"),
            ("ShiftStartValuesTest", "plausible_werte_werden_je_pruefenlauf_gespeichert_und_spaeter_unveraendert_gelesen"), ("PassengerCompartmentCheckTest", "abschluss_mit_einzelstatus_und_dokumentiertem_mangel"),
            ("InspectionAmendmentTest", "nachtrag_aendert_weder_sperre_noch_freigabe_noch_ergebnis"))
    dbtests(ctx, "test_m3_13_pruefstart", min_tests=3)
    vitest(ctx, "web/operator", "src/inspection", min_tests=5)
    flutter_offen(ctx, "Prüfpfad mit Abschluss und Freigabeanzeige (Driver-App)")


# --- AT-28 (Kritischer Schaden) ------------------------------------------------------------------------------------------------------------------

@m3_scenario("AT-28", "1.0.0", ["LH-6.21-CHK-008", "LH-6.22-DMG-005", "LH-6.22-DMG-010"], "Kritischer Schaden: Sperre, Alarm, Ersatzdisposition")
def at_28(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    both_tenants(ctx)
    backend(ctx, ("CriticalDefectBlockTest", "kritisches_nein_sperrt_synchron_und_verweigert_freigabe"), ("CriticalDefectBlockTest", "scheitert_der_alarmpfad_wird_auch_das_ergebnis_nicht_gespeichert"),
            ("CriticalDefectBlockApiTest", "kritisches_nein_ueber_die_schnittstelle_sperrt_zeigt_vorgangsnummer_und_kontakt"), ("ImmediateBlockTest", "kritische_meldung_sperrt_und_alarmiert"),
            ("ImmediateBlockTest", "bestehende_zuweisungen_gehen_in_stoerungsablauf"), ("ImmediateBlockTest", "doppelmeldung_erzeugt_einen_alarm"), ("DefectTriageApiTest", "kritische_meldung_alarmiert_die_leitstelle_priorisiert_ohne_fahrgastdaten"),
            ("AlertFoundationTest", "kritische_alarme_umgehen_die_eskalationsverzoegerung_und_andere_eskalieren_nach_der_verzoegerung"),
            ("OutageReplacementTest", "gesperrtes_fahrzeug_loest_ersatzlogik_mit_vorschlaegen_aus"), ("OutageReplacementTest", "buchung_endet_umgebucht_storniert_oder_offener_punkt"))
    dbtests(ctx, "test_m3_52_alarm", "test_stoerungsablauf", min_tests=3)
    ui_cases(ctx, ui07=True)


# --- AT-29 (Bekanntschaden) ----------------------------------------------------------------------------------------------------------------------

@m3_scenario("AT-29", "1.0.0", ["LH-6.21-CHK-012", "LH-6.22-DMG-004"], "Bekanntschaden: Altfall referenziert, kein unbegründeter neuer Schadensfall")
def at_29(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    both_tenants(ctx)
    backend(ctx, ("KnownDamageAcknowledgementTest", "altschaeden_vor_abschluss_angezeigt_und_quittierbar"), ("KnownDamageAcknowledgementTest", "abschluss_ohne_quittierung_wird_abgewiesen"),
            ("KnownDamageAcknowledgementTest", "neuanlage_bei_offenen_faellen_zeigt_bestehenden_fall_vorher"), ("KnownDamageAcknowledgementTest", "quittierung_speichert_keine_verursacherangabe"),
            ("KnownDamageAcknowledgementTest", "doppelte_quittierung_zwei_geraete_genau_ein_eintrag"), ("KnownDefectApiTest", "altschaeden_anzeigen_quittieren_idempotent_ohne_verursacher"),
            ("KnownDefectApiTest", "neuanlage_eines_fahrers_bei_offenen_faellen_ohne_bestaetigung_409"))
    dbtests(ctx, "test_defect_case", "test_defect_origin", min_tests=3)
    ui_cases(ctx, ui11=True)
    flutter_offen(ctx, "Quittierung der Altschäden vor Schichtstart (Driver-App)")


# --- AT-30 (Fahrzeugwechsel) ---------------------------------------------------------------------------------------------------------------------

@m3_scenario("AT-30", "1.0.0", ["LH-6.21-CHK-006", "LH-6.21-CHK-007"], "Fahrzeugwechsel: neue fahrzeugspezifische Prüfung, keine Vorabfreigabe")
def at_30(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    both_tenants(ctx)
    backend(ctx, ("VehicleSwitchTest", "ersatzfahrzeug_bleibt_bis_pruefung_gesperrt"), ("VehicleSwitchTest", "bereitmeldung_ohne_neue_pruefung_abgewiesen"), ("VehicleSwitchTest", "pruefung_altes_fahrzeug_gilt_nicht_fuer_ersatzfahrzeug"),
            ("VehicleSwitchTest", "alte_freigabe_widerrufen_und_wechsel_im_audit"), ("VehicleSwitchTest", "wechsel_auf_gesperrtes_fahrzeug_abgewiesen"), ("VehicleSwitchTest", "zwei_gleichzeitige_wechsel_genau_ein_ersatzfahrzeug"),
            ("VehicleSwitchApiTest", "wechsel_fahrerlauf_neue_pruefung_dann_freigabe"), ("VehicleSwitchApiTest", "negativfaelle_rechte_fremde_schicht_mandant_ueberlappung_und_eingaben"))
    dbtests(ctx, "test_m3_13_pruefstart", min_tests=3)
    vitest(ctx, "web/operator", "src/fahrzeugwechsel", min_tests=3)


# --- AT-31 (Offline-Prüfung) ---------------------------------------------------------------------------------------------------------------------

@m3_scenario("AT-31", "1.0.0", ["LH-6.21-CHK-010", "LH-6.21-CHK-011"], "Offline-Prüfung: später Sync, Abschluss einmalig, keine unbestätigte Freigabe")
def at_31(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    both_tenants(ctx)
    backend(ctx, ("OfflineInspectionSyncTest", "offline_abschluss_haelt_geraetezeit_und_empfangszeit_getrennt_und_gibt_beim_empfang_frei"), ("OfflineInspectionSyncTest", "offline_abschluss_danach_sperre_dann_sync_ergibt_keine_freigabe"),
            ("OfflineInspectionSyncTest", "mehrfachuebertragung_wird_genau_einmal_wirksam"), ("OfflineInspectionSyncTest", "sync_derselben_pruefung_von_zwei_app_instanzen_wirkt_genau_einmal"),
            ("OfflineInspectionSyncTest", "zu_alter_offline_abschluss_wird_nicht_mit_frischer_gueltigkeit_freigegeben"), ("OfflineInspectionSyncTest", "fahrzeugwechsel_vor_dem_sync_gibt_nicht_frei"),
            ("OfflineInspectionSyncTest", "sync_nach_schichtende_gibt_nicht_frei_aber_der_nachweis_bleibt"))
    dbtests(ctx, "test_m3_13_pruefstart", min_tests=3)
    flutter_offen(ctx, "Prüfung im Funkloch abschließen und synchronisieren (Driver-App, lokale Speicherung)")


# --- AT-32 (Prüfversionen) -----------------------------------------------------------------------------------------------------------------------

@m3_scenario("AT-32", "1.0.0", ["LH-6.21-CHK-004", "LH-6.21-CHK-009"], "Prüfversionen: Historie zeigt ursprüngliche Fragen und Antworten")
def at_32(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    both_tenants(ctx)
    backend(ctx, ("InspectionSubmitTest", "historie_zeigt_urspruengliche_fragen_nach_vorlagenaenderung"), ("ChecklistTemplateTest", "freigabe_durch_den_ersteller_wird_abgewiesen_und_freigegebene_revision_ist_unveraenderlich"),
            ("ChecklistTemplateTest", "gleichzeitige_freigaben_genau_eine_revision"), ("InspectionAmendmentTest", "nachtrag_ueberschreibt_original_nicht"), ("InspectionAmendmentTest", "nachtrag_ohne_grund_wird_abgewiesen"),
            ("OfflineInspectionSyncTest", "neue_vorlagenrevision_nach_dem_start_aendert_den_laufstand_nicht"), ("InspectionAmendmentApiTest", "nachtrag_mit_pflichtgrund_rechten_und_unveraenderter_originalantwort"))
    dbtests(ctx, "test_checklistenvorlage", "test_m3_13_pruefstart", min_tests=3)
    ui_cases(ctx, ui11=True)


# --- AT-33 (Schadenfall mobil) -------------------------------------------------------------------------------------------------------------------

@m3_scenario("AT-33", "1.0.0", ["LH-6.22-DMG-001", "LH-6.22-DMG-002", "LH-6.22-DMG-008"], "Schadenfall mobil: Schadenakte mit Foto, Fallnummer, Medienprüfung (M3-Teil)")
def at_33(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    both_tenants(ctx)
    backend(ctx, ("DefectCaseCreateTest", "meldung_ohne_buchung_erhaelt_eindeutige_fallnummer"), ("DefectCaseCreateTest", "parallele_meldungen_erhalten_eindeutige_fallnummern"),
            ("DefectEvidenceUploadTest", "foto_wird_erst_nach_bestandener_medienpruefung_sichtbar_und_ohne_exif_unter_mandantenpraefix_gespeichert"), ("DefectEvidenceUploadTest", "falscher_hash_wird_abgewiesen"),
            ("DefectEvidenceUploadTest", "unzulaessiger_mime_uebergrosse_und_ausfuehrbare_datei_werden_abgewiesen"), ("DefectEvidenceApiTest", "musterfall_mit_foto_fallnummer_medienpruefung_und_signiertem_abruf"),
            ("DefectApiTest", "meldung_ohne_buchung_201_mit_fallnummer_etag_und_lesen"), ("DefectFromInspectionTest", "negativer_pruefschritt_erzeugt_schadenakte_mit_fotos"))
    dbtests(ctx, "test_defect_case", "test_defect_evidence", min_tests=3)
    ui_cases(ctx, ui11=True)
    offen(ctx, "Medienprüfung im vollen Umfang (Schadsoftware-Scan, Quarantäne, endgültige Freigabe): hängt von WP-M4-21 (Uploadticket, Quarantäne) ab; in M3 abnehmbar sind Typ-, Größen-, Hash- und Metadatenprüfung und die Sichtbarkeit erst nach bestandener Prüfung (OP-13)")
    flutter_offen(ctx, "Schadenmeldung mit Foto und Fahrzeugbereich (Driver-App)")


# --- AT-34 (Schadens-Triage) ---------------------------------------------------------------------------------------------------------------------

@m3_scenario("AT-34", "1.0.0", ["LH-6.22-DMG-003", "LH-6.22-DMG-005", "LH-6.22-DMG-006"], "Schadens-Triage: klassifizieren, sperren, zuweisen, Alarm nach Regelmatrix (M3-Teil)")
def at_34(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    both_tenants(ctx)
    backend(ctx, ("DefectTriageTest", "regelklassifikation_je_klasse_mit_regelversion"), ("DefectTriageTest", "sicherheitskritisch_sperrt_im_selben_commit"), ("DefectTriageTest", "hochstufung_loest_alarm_nach_alert_policy_revision_aus"),
            ("DefectTriageTest", "aktenhistorie_zeigt_klassifikation_sperre_und_zuweisung_mit_akteur_und_zeit"), ("DefectTriageTest", "hochstufung_parallel_zu_zuweisung"),
            ("DefectStateMachineTest", "jeder_zulaessige_uebergang_ist_ausfuehrbar_und_in_der_historie_mit_akteur_und_zeit"), ("DefectStateMachineTest", "unzulaessige_uebergaenge_ergeben_409"),
            ("DefectTriageApiTest", "triage_klassifiziert_sperrt_und_zeigt_aktenhistorie"), ("DefectTriageApiTest", "fehlerfaelle_428_412_403_422_404"))
    dbtests(ctx, "test_defect_state", "test_defect_history", min_tests=3)
    ui_cases(ctx, ui11=True)
    offen(ctx, "„Frist auslösen“: Fristen und Eskalationszeiten der Schadensbearbeitung sind im Pflichtenheft nicht festgelegt (OP-12); in M3 abnehmbar sind Klassifikation, Sperre, Zuweisung, Statushistorie und der Alarm nach der Regelmatrix")


# --- AT-35 (Reparaturfreigabe) -------------------------------------------------------------------------------------------------------------------

@m3_scenario("AT-35", "1.0.0", ["LH-6.22-DMG-007", "LH-6.22-DMG-009"], "Reparaturfreigabe: Fahrzeug bleibt gesperrt bis zur berechtigten Freigabe")
def at_35(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    both_tenants(ctx)
    backend(ctx, ("RepairReleaseTest", "werkstattabschluss_ohne_technische_freigabe_laesst_das_fahrzeug_gesperrt"), ("RepairReleaseTest", "jede_fehlende_bedingung_haelt_die_sperre"),
            ("RepairReleaseTest", "reparaturnachweis_technische_freigabe_und_neue_pruefung_machen_das_fahrzeug_wieder_disponierbar"), ("RepairReleaseTest", "zwei_gleichzeitige_freigaben_eine_entscheidung"),
            ("RepairReleaseApiTest", "wiederfreigabe_ueber_die_api_von_der_sperre_bis_zur_freigabe"), ("RepairReleaseApiTest", "fremde_objekte_ergeben_404_und_ohne_nachweis_409"))
    dbtests(ctx, "test_defect_state", "test_driver_release", min_tests=3)
    ui_cases(ctx, ui11=True)


# --- AT-36 (Dublette und Beweise) ----------------------------------------------------------------------------------------------------------------

@m3_scenario("AT-36", "1.0.0", ["LH-6.22-DMG-002", "LH-6.22-DMG-008", "LH-6.22-DMG-011"], "Dublette und Beweise: Hinweis auf offene Fälle, Integritätsprüfung (M3-Teil)")
def at_36(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    both_tenants(ctx)
    backend(ctx, ("KnownDamageAcknowledgementTest", "neuanlage_bei_offenen_faellen_zeigt_bestehenden_fall_vorher"), ("DefectCaseCreateTest", "gleiche_geraeteereignis_id_erzeugt_keine_zweite_akte"),
            ("DefectFromInspectionTest", "doppelte_uebermittlung_erzeugt_genau_eine_akte"), ("DefectFromInspectionTest", "zwei_geraete_parallel_selber_pruefschritt_genau_eine_akte"),
            ("DefectEvidenceUploadTest", "nachtraeglich_veraendertes_objekt_wird_von_der_integritaetspruefung_erkannt"), ("DefectEvidenceUploadTest", "doppelter_upload_ein_evidenzeintrag"),
            ("DefectEvidenceMetadataTest", "abrufbare_fassung_ist_im_metadatenvergleich_ohne_standort_und_geraetemetadaten"))
    dbtests(ctx, "test_defect_evidence", "test_defect_case", min_tests=3)
    offen(ctx, "Dublettenhinweis über den Hinweis auf offene Fälle desselben Fahrzeugs hinaus (Ähnlichkeitserkennung, Zusammenführung): Regel und Schwellen sind im Pflichtenheft nicht festgelegt (OP-13, OP-21)")


# --- AT-50 (Ende-zu-Ende-Fahrzeugausfall) --------------------------------------------------------------------------------------------------------

@m3_scenario("AT-50", "1.0.0", ["LH-6.22-DMG-010", "LH-6.21-CHK-006", "LH-I.8-AUT-001"], "Ende-zu-Ende-Fahrzeugausfall: Ersatzfahrzeug, neuer Check, Rebooking, Fahrgastkommunikation")
def at_50(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    both_tenants(ctx)
    backend(ctx, ("CriticalDefectBlockTest", "kritisches_nein_sperrt_synchron_und_verweigert_freigabe"), ("ImmediateBlockTest", "kritische_meldung_sperrt_und_alarmiert"), ("ImmediateBlockTest", "bestehende_zuweisungen_gehen_in_stoerungsablauf"),
            ("IncidentWorkflowTest", "fahrzeugausfall_ergibt_genau_einen_incident_und_eine_suche"), ("VehicleSwitchTest", "ersatzfahrzeug_bleibt_bis_pruefung_gesperrt"), ("VehicleSwitchTest", "pruefung_altes_fahrzeug_gilt_nicht_fuer_ersatzfahrzeug"),
            ("VehicleSwitchApiTest", "wechsel_fahrerlauf_neue_pruefung_dann_freigabe"))
    backend(ctx, *OUTAGE)
    backend(ctx, ("OutageHttpApiTest", "stoerungsansicht_zeigt_betroffene_fahrt_vorschlag_und_aktionen_und_umbuchung_schliesst_den_incident"),
            ("OutageHttpApiTest", "betreiberstorno_mit_ursache_fahrzeugausfall_idempotent_und_ohne_doppelte_erstattung"), ("ManualReplanTest", "buchung_von_fahrzeug_a_auf_b_verschieben_atomar_mit_begruendung_und_verteilung"),
            ("PlanRevisionSyncTest", "planaenderung_erreicht_je_fahrgast_und_fahrer_genau_eine_wirksame_benachrichtigung"), ("NotPerformedCauseTest", "fahrzeugausfall_und_no_show_getrennt_erfasst_und_ursache_offen_sichtbar"))
    dbtests(ctx, "test_stoerungsablauf", "test_m3_13_pruefstart", "test_trip_not_performed", min_tests=3)
    ui_cases(ctx, ui07=True)
    offen(ctx, "Inhalt der Fahrgastnachricht zur Umbuchung (Vorlagen und Kanäle, OP-27): in M3 abnehmbar ist die Anforderung genau einer Mitteilung je Änderung (Ereignis `outage.passenger_notice_requested`) und die Stornomitteilung über den M2-Weg")


# --- AT-51 (Sicherheitsfreigabe) -----------------------------------------------------------------------------------------------------------------

@m3_scenario("AT-51", "1.0.0", ["LH-6.22-DMG-007", "LH-6.7-OPS-005"], "Sicherheitsfreigabe: Leitstelle kann kritische Sperre nicht ohne Werkstattnachweis entfernen")
def at_51(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    both_tenants(ctx)
    backend(ctx, ("RepairReleaseTest", "leitstelle_kann_sicherheitssperre_nicht_ohne_werkstattnachweis_entfernen"), ("RepairReleaseTest", "freigabe_fuer_fremdes_fahrzeug_ohne_rolle_veraltet_und_ungueltig"),
            ("OperationalBlockTest", "aufhebungsversuch_kritischer_sperre_ueber_operativen_weg_scheitert"), ("OperationalBlockTest", "aufheben_operativer_sperre_laesst_kritische_sperre_bestehen"),
            ("OperationalBlockApiTest", "aufhebungsversuch_kritischer_sperre_ueber_operativen_weg_scheitert_und_operative_aufhebung_laesst_sie_bestehen"), ("RepairReleaseApiTest", "fremde_objekte_ergeben_404_und_ohne_nachweis_409"))
    dbtests(ctx, "test_defect_state", "test_driver_release", min_tests=3)
    ui_cases(ctx, ui07=True)


# --- AT-60 (Wiederinbetriebnahme) ----------------------------------------------------------------------------------------------------------------

@m3_scenario("AT-60", "1.0.0", ["LH-6.22-DMG-009", "LH-6.21-CHK-006"], "Wiederinbetriebnahme: Disposition erst nach Reparatur, technischer Freigabe und neuer Checkliste")
def at_60(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    both_tenants(ctx)
    backend(ctx, ("RepairReleaseTest", "reparaturnachweis_technische_freigabe_und_neue_pruefung_machen_das_fahrzeug_wieder_disponierbar"), ("RepairReleaseTest", "jede_fehlende_bedingung_haelt_die_sperre"),
            ("RepairReleaseTest", "werkstattabschluss_ohne_technische_freigabe_laesst_das_fahrzeug_gesperrt"), ("RepairReleaseApiTest", "wiederfreigabe_ueber_die_api_von_der_sperre_bis_zur_freigabe"),
            ("ReadinessHoldTest", "ohne_freigabe_keine_zusage_mit_freigabe_ja_nach_sperre_nein"), ("ReadinessTest", "freigabe_bei_erfuellten_bedingungen"))
    dbtests(ctx, "test_defect_state", "test_m3_13_pruefstart", min_tests=3)
    ui_cases(ctx, ui11=True)
