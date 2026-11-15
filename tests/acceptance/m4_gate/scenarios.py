"""Gate-Szenarien von M4 (WP-M4-63). Jedes Szenario läuft auf der Fixture Mandanten A/B der Akzeptanzdatenbank unter der Runtime-Rolle (kein Superuser, kein BYPASSRLS, kein Eigentümer) mit **beiden** Mandanten
und weist den **in M4 prüfbaren Umfang** nach (Abgrenzung je Test: docs/m4-gate-umfang.md, Offene Punkte 1 bis 7 des WP-Dokuments). Nachweisarten wie in der M2- und M3-Suite, alle als Soll/Ist-Prüfung im
Evidenzdatensatz:

  * Backend-Test (JUnit-Ergebnis aus `./gradlew build`; Klasse und Methode müssen vorhanden und PASSED sein; die Zwei-Mandanten-Fixtures und die Parallelitätstests mit mindestens zwei echten Transaktionen sind Teil davon),
  * Datenbanktest (`db/tests/<modul>.py`, **im Szenario ausgeführt** gegen die Akzeptanzdatenbank),
  * Webtest (Vitest im Szenario ausgeführt) und Browserlauf der Barrierefreiheitsprüfung (axe, Reflow 320 px, 200 % Textgröße; nur mit Chrome),
  * SQL-Prüfung unter der Runtime-Rolle (Rolle, Mandantentrennung).

Steht ein Teilschritt eines späteren Meilensteins, einer Fachentscheidung oder ein nicht ausführbarer Lauf (Flutter, Screenreader) aus, erscheint er als Schritt mit Soll `offen` und Ist `offen (nicht geprüft)`; das
Szenario zählt ihn nicht als bestanden, die Evidenz nennt ihn. Die Abnahmeentscheidung bleibt `PENDING`."""
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
from taktovia_at.scenario import SOURCE_KEY_RE, TEST_ID_RE, kind_of  # noqa: E402
from m2_gate.scenarios import backend, dbtests, offen, role_checks, vitest  # noqa: E402,F401
from m3_gate.scenarios import both_tenants  # noqa: E402

SCENARIOS: dict = {}


def m4_scenario(test_id, version, source_keys, name):
    """Wie `taktovia_at.scenario.scenario`, ohne Eintrag in die gemeinsame Registry (die M1- bis M3-Suiten registrieren ihre Szenarien dort bzw. in eigenen Tabellen)."""
    assert TEST_ID_RE.match(test_id), test_id
    assert source_keys and all(SOURCE_KEY_RE.match(k) for k in source_keys), (test_id, source_keys)

    def deco(fn):
        fn.meta = {"id": test_id, "version": version, "source_key": list(source_keys), "name": name, "kind": kind_of(test_id), "responsible": "Plattform (QA)"}
        SCENARIOS[test_id] = fn
        return fn
    return deco


def T(cls, *names):
    """Backend-Tests einer Klasse als Liste von (Klasse, Methode); Verwendung: `backend(ctx, *T(<Klasse>, <Methode>, ...))`."""
    return [(cls, n) for n in names]


def start(ctx):
    ctx.use_role("tenant_admin", "A", ctx.identities["A"]["tenant_admin"])
    both_tenants(ctx)


def browser_a11y_operator(ctx):
    """Browserlauf der Barrierefreiheitsprüfung für das Operator-Web (axe-core WCAG 2.2 AA, Reflow bei 320 CSS-Pixeln, 200 % Textgröße, Touchflächen, Fokus) über alle Bildschirme inklusive
    UI-12 (Dienstplan), UI-13 (Eigene Zeit), UI-14 (Belege), UI-15 (Fahrzeugkosten, Kostenübersicht) und Kennzahlen/Live-Betrieb."""
    chrome = os.environ.get("CHROME_PATH") or next((p for p in ("/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser") if Path(p).exists()), None)
    if not chrome or shutil.which("node") is None or not (REPO / "web" / "node_modules").is_dir():
        ctx.check("Browserlauf a11y:browser (operator)", "ausgeführt, keine Verstöße", "nicht ausgeführt: Chrome, Node oder web/node_modules fehlt", ok=False)
        return
    p = subprocess.run(["node", "tools/a11y-browser.mjs"], cwd=REPO / "web", env=dict(os.environ, A11Y_APPS="operator", CHROME_PATH=chrome), capture_output=True, text=True, timeout=1500)
    m = re.search(r"(\d+) Seitenansichten geprüft, (\d+) Verstöße", p.stdout)
    views, violations = (int(m.group(1)), int(m.group(2))) if m else (0, -1)
    ctx.check("Browserlauf a11y:browser (operator, ausgeführt)", "Seitenansichten geprüft, 0 Verstöße", f"{views} Seitenansichten, {violations} Verstöße", ok=views > 0 and violations == 0)


def screenreader_offen(ctx, what):
    offen(ctx, f"{what}: Prüfung mit echtem Screenreader (NVDA/VoiceOver) ist in dieser Umgebung nicht ausführbar; Tastaturbedienung, Rollen, Namen und Live-Region sind in den Vitest-Tests und im Browserlauf (axe) geprüft")


# --- Gemeinsame Backend-Gruppen ------------------------------------------------------------------------------------------------------------------

KOSTENEREIGNIS = [*T("CostEventPostingTest", "tankung_119_ergibt_genau_ein_kostenereignis_100_kostenbasis_19_vorsteuer_119_verbindlichkeit", "erneuter_posting_ausloeser_liefert_dasselbe_ereignis_ohne_summenaenderung",
                     "paralleles_posting_desselben_ursprungs_genau_ein_ereignis_und_ein_journal", "nicht_abziehbare_vorsteuer_geht_in_die_kostenbasis"),
                  *T("FuelReceiptTest", "parallele_posting_ausloeser_desselben_tankbelegs_ergeben_ein_kostenereignis", "vollstaendiger_tankbeleg_ist_nach_der_freigabe_als_kostenereignis_auswertbar"),
                  *T("ExpenseApprovalApiTest", "ablauf_erfassung_pruefung_freigabe_bucht_genau_einmal_und_ist_idempotent")]
DUBLETTE = T("DuplicateDetectionTest", "doppelter_import_ergibt_hinweis_und_ein_kostenereignis", "foto_und_tankkartentransaktion_sind_evidenzen_desselben_tankereignisses",
             "aehnlicher_beleg_anderer_betrag_ist_moeglicher_treffer_nicht_geloescht_nicht_zusammengefuehrt", "parallele_freigabe_identischer_belege_genau_ein_kostenereignis",
             "gleicher_hash_in_zweitem_mandanten_ohne_treffer_und_ohne_auskunft", "entscheidung_recht_begruendung_revision_wiederholung_und_audit")
ALLOKATION = T("AllocationServiceTest", "rechenkern_largest_remainder_ist_exakt_und_deterministisch", "drittelsplit_100_01_eur_ergibt_33_34_33_34_33_33", "prozent_und_betrag_ergeben_gleiche_zeilen_summe_100_pflicht",
               "negative_korrektur_hebt_ursprungsbetraege_exakt_auf_10000_minor", "summe_ungleich_basis_blockiert_freigabe", "parallele_allokationsrevisionen_genau_eine_aktiv",
               "allokation_erzeugt_kein_zusatzjournal_und_keine_verbindlichkeit", "geschlossene_periode_409_und_gebuchte_allokation_409", "allokation_parallel_zu_periodenabschluss_genau_eine_reihenfolge")
PERIODE = T("PeriodCloseTest", "september_abgeschlossen_gutschrift_im_oktober_journal_unveraendert_korrektur_mit_ursprungsbezug", "posting_in_geschlossene_periode_wird_abgewiesen_ohne_spuren",
            "gegenereignis_kehrt_die_gespeicherten_teilbetraege_der_aufteilung_negativ_um", "abschluss_parallel_zu_posting_allokation_und_abschluss_genau_eine_reihenfolge",
            "parallele_gegenereignisse_desselben_ursprungs_ueberschreiten_den_ursprung_nie", "perioden_ueberlappen_sich_nie_auch_parallel", "abschluss_braucht_eigenes_recht_steht_im_audit_und_ist_endgueltig")
VIER_AUGEN = [*T("ExpenseApprovalTest", "vier_augen_ueber_der_schwelle_zweite_person_erforderlich_und_kein_export_ohne_beide_freigaben", "selbstfreigabe_durch_den_erfasser_wird_abgewiesen",
                 "dieselbe_person_in_beiden_schritten_auch_parallel_abgewiesen", "zwei_freigeber_parallel_genau_ein_posting", "betrag_exakt_auf_schwelle_ohne_zweite_stufe"),
              *T("ExpenseApprovalApiTest", "vier_augen_ueber_der_schwelle_zwei_verschiedene_personen", "ohne_freigaberecht_403_ohne_datenpreisgabe_selbstfreigabe_403_fremder_mandant_404"),
              *T("LedgerExportTest", "nur_gebuchte_vollstaendig_freigegebene_journale_beleg_ohne_zweite_freigabe_erscheint_nicht")]
QUARANTAENE = [*T("UploadTicketTest", "malware_bleibt_in_quarantaene_wird_nicht_ausgeliefert_und_erzeugt_ein_sicherheitsereignis", "zwei_parallele_abschluesse_desselben_tickets_ergeben_genau_ein_dokument"),
               *T("ReceiptUploadApiTest", "malware_bleibt_in_quarantaene_mit_sicherheitsereignis_und_ist_nicht_auslieferbar"), *T("ExtractionRunTest", "quarantaene_beleg_nicht_extrahiert")]
INTEGRITAET = [*T("IntegrityCheckTest", "manipuliertes_original_wird_erkannt_und_protokolliert", "freigabe_parallel_zur_verletzung_bucht_nicht"),
               *T("DocumentRevisionTest", "korrektur_ueberschreibt_original_nie", "schreib_und_loeschversuche_auf_originale_und_revisionen_scheitern", "parallele_korrekturen_genau_eine_gewinnt")]
OCR = [*T("ExtractionRunTest", "lauf_liefert_unbestaetigte_vorschlaege_und_der_beleg_bleibt_unveraendert", "paralleler_lauf_genau_ein_wirksamer_lauf"),
       *T("OcrConfirmationTest", "freigabe_mit_unbestaetigtem_betrag_wird_abgewiesen", "alle_pflichtfelder_bestaetigt_ist_freigabefaehig", "korrektur_parallel_zur_freigabe_ergibt_412"),
       *T("ExtractedFieldKorrekturTest", "korrektur_erhaelt_ocr_vorschlag", "betrag_89_statt_39_korrigierbar_vorschlag_nachvollziehbar")]
ABLEHNUNG = T("RejectionStatsTest", "drei_ablehnungen_summe_primaergruende_drei", "kapazitaet_von_zeitfenster_und_tarif_unterscheidbar", "ablehnungsstatistik_nur_eigener_mandant", "parallele_ablehnungen_um_letzten_platz_ohne_doppelzaehlung")
FUNNEL = [*T("FunnelCountTest", "vier_zaehlgroessen_getrennt_nie_summe", "stufen_stimmen_mit_ereignisprotokoll_ueberein"), *T("FunnelApiTest", "trichter_weist_vier_zaehlgroessen_getrennt_und_endzustaende_aus")]
VERTRAG = [*T("CrossTenantIsolationTest", "fremdes_objekt_ueber_alle_m1_endpunkte_gleichartig_abgewiesen", "manipulierte_mandantenangabe_wird_nicht_beachtet_und_sicherheitsereignis_enthaelt_keine_b_daten",
              "a_und_b_arbeiten_parallel_und_unabhaengig"), *T("ContractCoverageTest", "alle_m1_endpunkte_positiv_und_fehlerfall")]


# --- PH-AT-012 / E2E-064 bis E2E-066 (Belege, OCR, Dublette, ein Kostenereignis) -------------------------------------------------------------------

@m4_scenario("PH-AT-012", "1.0.0", ["LH-I.1-DOC-003", "LH-I.1-DOC-004", "LH-I.1-DOC-005", "LH-I.1-DOC-006", "LH-I.1-DOC-009"], "Ein Kostenereignis trotz OCR, Tankkarte und Rechnung (M4-Umfang)")
def ph_at_012(ctx):
    start(ctx)
    backend(ctx, *KOSTENEREIGNIS, *DUBLETTE, *OCR, *T("DocumentRevisionTest", "korrektur_ueberschreibt_original_nie"))
    dbtests(ctx, "test_cost_event_journal", "test_fuel_transaction", "test_document_revision", min_tests=3)
    offen(ctx, "Tankkarten-Transaktion aus einem Importkanal und Rechnung als dritte Evidenz (mit E2E-070): der Tankkartenimport (LH-I.1-DOC-019) ist P2 (M8); in M4 ist die Transaktion nur als zweite Evidenz über den Importkanal abgebildet (OP 5), Teilabnahme mit dem Auftraggeber zu bestätigen (D-12)")


@m4_scenario("E2E-064", "1.0.0", ["LH-I.1-DOC-001", "LH-I.1-DOC-002", "LH-I.1-DOC-003", "LH-I.1-DOC-004", "LH-I.1-DOC-005", "LH-I.1-DOC-006", "LH-I.1-DOC-007", "LH-I.1-DOC-010"], "Belegscan mit OCR: Original erhalten, Kosten nach Freigabe einmal gebucht")
def e2e_064(ctx):
    start(ctx)
    backend(ctx, *T("ReceiptCaptureTest", "rohbild_und_bearbeitete_ansicht_abrufbar", "zuschnitt_und_drehung_lassen_original_hash_unveraendert"), *OCR, *KOSTENEREIGNIS,
            *T("OfflineReceiptSyncTest", "offline_beleg_erscheint_nach_sync_genau_einmal_mit_unveraendertem_hash"), *T("CostAssignmentTest", "zuordnung_zu_kostenstelle_und_fahrzeug_macht_beleg_freigabefaehig"))
    dbtests(ctx, "test_expense_cost_assignment", "test_cost_event_journal", min_tests=3)
    vitest(ctx, "web/operator", "src/belege", min_tests=15)
    offen(ctx, "Belegerfassung per Kamera in der Fahrer-App (Flutter, UI-09-Erweiterung): Dart-Tests liegen unter mobile/driver/test, Flutter ist in dieser Umgebung nicht installiert (nicht ausgeführt)")


@m4_scenario("E2E-065", "1.0.0", ["LH-I.1-DOC-006", "LH-I.1-DOC-009"], "Tankbeleg doppelt: Dublettenprüfung verknüpft, keine doppelte Kostenwirkung")
def e2e_065(ctx):
    start(ctx)
    backend(ctx, *DUBLETTE, *T("DuplicateApiTest", "moeglicher_treffer_ist_einsehbar_blockiert_und_wird_mit_begruendung_entschieden", "ohne_recht_403_fremder_mandant_404_und_kein_treffer_ueber_mandanten"),
            *T("CostEventPostingTest", "paralleles_posting_desselben_ursprungs_genau_ein_ereignis_und_ein_journal"))
    dbtests(ctx, "test_fuel_transaction", "test_cost_event_journal", min_tests=3)
    offen(ctx, "Echte Tankkartentransaktion aus dem Kartenimport: der Import (LH-I.1-DOC-019) ist P2 (M8); geprüft ist die Minimalform als zweite Evidenz über den Importkanal (OP 5)")


@m4_scenario("E2E-066", "1.0.0", ["LH-I.1-DOC-002", "LH-I.1-DOC-003", "LH-I.1-DOC-007"], "OCR falscher Betrag: Korrektur möglich, ursprünglicher Vorschlag nachvollziehbar")
def e2e_066(ctx):
    start(ctx)
    backend(ctx, *T("ExtractedFieldKorrekturTest", "betrag_89_statt_39_korrigierbar_vorschlag_nachvollziehbar", "korrektur_erhaelt_ocr_vorschlag", "mehrwertsteuer_bestaetigen_und_korrigieren_und_leere_felder_nicht_bestaetigbar"),
            *T("OcrConfirmationTest", "freigabe_mit_unbestaetigtem_betrag_wird_abgewiesen", "niedrige_konfidenz_wird_markiert_sichere_felder_nicht", "korrektur_parallel_zur_freigabe_ergibt_412"),
            *T("DocumentRevisionTest", "korrektur_ueberschreibt_original_nie", "parallele_korrekturen_genau_eine_gewinnt"))
    dbtests(ctx, "test_document_revision", "test_expense_integrity", min_tests=3)
    vitest(ctx, "web/operator", "src/belege", min_tests=15)


@m4_scenario("E2E-067", "1.0.0", ["LH-I.1-DOC-010", "LH-I.1-DOC-014", "LH-I.1-DOC-015"], "E-Rechnung XML/PDF-Differenz (in M4 nur Teilumfang)")
def e2e_067(ctx):
    start(ctx)
    # geprüfter Teilumfang: Summenabweichung zwischen Positionen und Gesamtbetrag ist sichtbar und prüfpflichtig; Originale werden nie überschrieben (kein E-Rechnungsimport in M4)
    backend(ctx, *T("OcrConfirmationTest", "summenabweichung_wird_markiert_und_konsistente_summe_nicht", "summenabweichung_blockiert_die_freigabe_bis_zur_korrektur"), *T("DocumentRevisionTest", "korrektur_ueberschreibt_original_nie"))
    dbtests(ctx, "test_document_revision", min_tests=3)
    offen(ctx, "XML führend, Abweichung zwischen XML und PDF-Betrag (XRechnung/ZUGFeRD, KoSIT-Validator): LH-I.1-DOC-014/-015 sind P2 (M8), in M4 ist kein E-Rechnungsimport gebaut (OP 4); Verschiebung ins Gate M8 oder Vorziehen ist per D-12 zu entscheiden")


@m4_scenario("E2E-068", "1.0.0", ["LH-I.1-DOC-010"], "Rechnungsfreigabe über Schwelle: zwei getrennte Berechtigungen, sonst kein Export")
def e2e_068(ctx):
    start(ctx)
    backend(ctx, *VIER_AUGEN, *T("ExpenseApprovalApiTest", "ablehnung_fuehrt_in_die_erfassung_zurueck_und_blockaden_haben_eigene_codes"))
    dbtests(ctx, "test_expense_cost_assignment", "test_ledger_export", min_tests=3)
    vitest(ctx, "web/operator", "src/belege", min_tests=15)


# --- PH-AT-013 / E2E-072 / PH-AT-015 / E2E-073 (Allokation, Periode) -----------------------------------------------------------------------------

@m4_scenario("PH-AT-013", "1.0.0", ["LH-I.1-DOC-011", "LH-6.19-NFR-012"], "Centgenaue Allokation und getrennte Rechtsträger (M4-Umfang)")
def ph_at_013(ctx):
    start(ctx)
    backend(ctx, *ALLOKATION, *T("AllocationApiTest", "gleicher_idempotency_key_ergibt_eine_revision", "summe_ungleich_basis_prozent_und_fremder_mandant"),
            *T("CostAssignmentTest", "kostenobjekt_eines_anderen_rechtstraegers_wird_abgewiesen"))
    dbtests(ctx, "test_allocation", "test_cost_event_journal", min_tests=3)
    offen(ctx, "Referenzfall Eigentümer X / Betreiber Y (Forderungs-/Verbindlichkeitsbezug, Journal je Rechtsträger über Mandanten, E2E-094): Verbundabrechnung und Clearing sind P2 (M6, I.9/COM-006/008); in M4 geprüft sind centgenaue Allokation, Determinismus, Summenpflicht und der Rechtsträgerbezug je Kostenobjekt")


@m4_scenario("E2E-072", "1.0.0", ["LH-I.1-DOC-011"], "Kosten auf zwei Kostenträger: 100 % Zuordnung, Summen ausgeglichen")
def e2e_072(ctx):
    start(ctx)
    backend(ctx, *ALLOKATION, *T("AllocationApiTest", "voraussetzungen_if_match_authentisierung_recht_und_409_codes", "summe_ungleich_basis_prozent_und_fremder_mandant"))
    dbtests(ctx, "test_allocation", min_tests=3)


@m4_scenario("PH-AT-015", "1.0.0", ["LH-I.2-FCO-023", "LH-I.1-DOC-030", "LH-I.6-KPI-027"], "Geschlossene Periode, späte Gutschrift, keine Doppelzählung (M4-Umfang)")
def ph_at_015(ctx):
    start(ctx)
    backend(ctx, *PERIODE, *T("CostClassificationTest", "original_und_umlage_werden_nicht_doppelt_gezaehlt", "kostenaufstellung_testfahrzeug_zeigt_vier_klassen_getrennt_und_in_summe"),
            *T("FiscalPeriodApiTest", "abschluss_audit_idempotenz_und_kein_wiederoeffnen_auf_irgendeinem_pfad", "gegenereignis_in_naechster_offener_periode_dublette_und_fehlerfaelle"),
            *T("CostEventPostingTest", "posting_parallel_zum_periodenabschluss_ist_konsistent"))
    dbtests(ctx, "test_fiscal_period", "test_cost_classification", min_tests=3)
    offen(ctx, "Restatement-Ansicht (ausdrücklich versioniert) und Plan/Ist-Vergleich mit Planbudget (LH-I.2-FCO-017): nicht in M4 gebaut (OP 6); geprüft sind Abschluss-Snapshot unverändert, Korrektur mit Ursprungsbezug und keine Doppelzählung von Original und Umlage")


@m4_scenario("E2E-073", "1.0.0", ["LH-I.2-FCO-023", "LH-I.1-DOC-030"], "Monatsabschluss: Abschluss unverändert, korrekter Korrekturbeleg")
def e2e_073(ctx):
    start(ctx)
    backend(ctx, *PERIODE, *T("FiscalPeriodApiTest", "perioden_anlegen_lesen_ueberlappung_und_authentisierung"))
    dbtests(ctx, "test_fiscal_period", min_tests=3)
    offen(ctx, "Periodenabschluss und Gegenbeleg sind P2-Anforderungen (FCO-023, DOC-030, M8), in M4 als Enabler (WP-M4-36) gebaut; Kap. 05 §5.7 nennt keinen Endpunkt für den Periodenabschluss (OP 6): geprüft über Dienstschicht, Datenbank und Fiskalperioden-API")


@m4_scenario("PH-AT-019", "1.0.0", ["LH-I.1-DOC-007", "LH-I.1-DOC-008", "LH-I.1-DOC-010"], "Belegfreigabe und Archivintegrität (M4-Umfang)")
def ph_at_019(ctx):
    start(ctx)
    backend(ctx, *VIER_AUGEN, *QUARANTAENE, *INTEGRITAET, *T("ExpenseApiTest", "manipuliertes_original_ergibt_409_mit_stabilem_code_und_sperrt_den_beleg"))
    dbtests(ctx, "test_m4_21_upload_ticket", "test_expense_integrity", "test_document_revision", min_tests=3)
    offen(ctx, "abweichendes strukturiertes XML gegen PDF-Darstellung (E-Rechnung, XML führend): LH-I.1-DOC-014/-015 sind P2 (M8), kein E-Rechnungsimport in M4 (OP 4); geprüft sind Selbstfreigabe blockiert, Original mit Hash unverändert, Schadsoftware in Quarantäne ohne OCR")


# --- COST-xx (Kap. 03 §7, als technische Szenarien) -----------------------------------------------------------------------------------------------

@m4_scenario("TECH-COST-01", "1.0.0", ["LH-I.1-DOC-009"], "COST-01: Foto, Tankkartenimport und Wiederholung des Freigabejobs liefern genau ein Tankereignis")
def cost_01(ctx):
    start(ctx)
    backend(ctx, *DUBLETTE, *T("CostEventPostingTest", "tankung_119_ergibt_genau_ein_kostenereignis_100_kostenbasis_19_vorsteuer_119_verbindlichkeit", "erneuter_posting_ausloeser_liefert_dasselbe_ereignis_ohne_summenaenderung",
                             "paralleles_posting_desselben_ursprungs_genau_ein_ereignis_und_ein_journal"), *T("FuelReceiptTest", "parallele_posting_ausloeser_desselben_tankbelegs_ergeben_ein_kostenereignis"))
    dbtests(ctx, "test_fuel_transaction", "test_cost_event_journal", min_tests=3)


@m4_scenario("TECH-COST-02", "1.0.0", ["LH-I.1-DOC-011"], "COST-02: 10.000 Minor Units im Drittelsplit, Korrektur hebt exakt auf, falsche Summe blockiert Posting")
def cost_02(ctx):
    start(ctx)
    backend(ctx, *T("AllocationServiceTest", "rechenkern_largest_remainder_ist_exakt_und_deterministisch", "drittelsplit_100_01_eur_ergibt_33_34_33_34_33_33", "negative_korrektur_hebt_ursprungsbetraege_exakt_auf_10000_minor",
                    "summe_ungleich_basis_blockiert_freigabe", "beleg_split_blockiert_bei_luecke_oder_differenz_und_wird_beim_posting_zur_ersten_umlagerevision"))
    dbtests(ctx, "test_allocation", min_tests=3)


@m4_scenario("TECH-COST-05", "1.0.0", ["LH-I.2-FCO-023"], "COST-05: Periodenschluss konkurriert mit Freigabe, genau eine Reihenfolge; Gutschrift in neuer Periode")
def cost_05(ctx):
    start(ctx)
    backend(ctx, *PERIODE, *T("CostEventPostingTest", "posting_parallel_zum_periodenabschluss_ist_konsistent"))
    dbtests(ctx, "test_fiscal_period", min_tests=3)


@m4_scenario("TECH-COST-07", "1.0.0", ["LH-I.1-DOC-010"], "COST-07: zwei Freigaben derselben Person erfüllen keine Vier-Augen-Freigabe; ungültiges XML/PDF-Abweichung (offen)")
def cost_07(ctx):
    start(ctx)
    backend(ctx, *VIER_AUGEN)
    dbtests(ctx, "test_expense_cost_assignment", min_tests=3)
    offen(ctx, "Ungültiges XML und PDF/XML-Abweichung mit konkreten Prüffehlern (LH-I.1-DOC-014/-015, P2, M8): in M4 nicht gebaut (OP 4)")


@m4_scenario("TECH-COST-08", "1.0.0", ["LH-I.2-FCO-001", "LH-I.2-FCO-004"], "COST-08: fehlende Energiedaten sind fehlend statt null Kosten; Original plus Umlage nie doppelt summiert")
def cost_08(ctx):
    start(ctx)
    backend(ctx, *T("EnergyCostMetricTest", "fehlende_energiedaten_ergeben_provisional_nicht_null_kosten", "unfreigegebener_beleg_wird_ausgeschlossen_und_benannt", "liter_und_kwh_werden_nicht_gemischt", "null_kilometer_ergibt_zero_denominator"),
            *T("CostClassificationTest", "original_und_umlage_werden_nicht_doppelt_gezaehlt", "mixed_eigene_spalte_und_nicht_klassifiziert_getrennt_ausgewiesen"))
    dbtests(ctx, "test_cost_classification", "test_cost_category", min_tests=3)
    offen(ctx, "Kosten je Fahrgast bei null Fahrgästen ist `null` (LH-I.2-FCO-027/KPI-027, P2): die Kennzahl Kosten je Fahrgast ist nicht Teil von M4")


# --- Dienstplan und Arbeitszeit (AT-37 bis AT-48) -------------------------------------------------------------------------------------------------

@m4_scenario("AT-37", "1.0.0", ["LH-6.23-SCH-001", "LH-6.23-SCH-002", "LH-6.23-SCH-003", "LH-6.23-SCH-004", "LH-6.23-SCH-005"], "Dienstplan Entwurf/Publikation: Fahrer sieht nur die veröffentlichte Revision")
def at_37(ctx):
    start(ctx)
    backend(ctx, *T("RosterDraftVisibilityTest", "fahrer_sieht_entwurf_nicht", "entwurf_aenderung_laesst_veroeffentlichten_plan_unveraendert", "entwurf_aenderung_erzeugt_keine_outbox_ereignisse", "zwei_planer_alter_etag_ergibt_412",
                    "parallele_entwurfsaenderung_und_eroeffnung_halten_den_stand_konsistent"),
            *T("RosterPublishTest", "fahrer_sieht_vor_veroeffentlichung_keine_aenderung", "parallele_publikation_genau_eine_gewinnt", "idempotenz_gleicher_schluessel_dieselbe_revision", "benachrichtigung_nur_an_geaenderte_personen",
               "fahrerabruf_waehrend_der_publikation_sieht_nie_einen_mischstand"),
            *T("RosterPublishApiTest", "veroeffentlichung_mit_if_match_idempotenz_und_fahrer_eigensicht"),
            *T("RosterRevisionHistoryTest", "fruehere_fassung_nach_zwei_weiteren_veroeffentlichungen_unveraendert_abrufbar", "parallele_veroeffentlichung_und_abruf_sehen_nur_vollstaendige_revisionen"))
    dbtests(ctx, "test_roster_revision", "test_m4_15_roster_history", "test_staff_shift", min_tests=3)
    vitest(ctx, "web/operator", "src/dienstplan", min_tests=8)
    offen(ctx, "Inhalt der „korrekten Meldung“ an den Fahrer und Wirkung von `shift_publish_notice_hours`: gezielte Mitteilungen sind P2 (LH-6.23-SCH-014), die Wirkung des Veröffentlichungsvorlaufs ist nicht festgelegt (OP 9); geprüft ist, dass genau die geänderten Personen ein Ereignis erhalten")


@m4_scenario("AT-39", "1.0.0", ["LH-6.23-SCH-003", "LH-6.23-SCH-006", "LH-6.8-FLT-003"], "Qualifikation und Doppelschicht: Sperre vor Veröffentlichung mit Konfliktursache")
def at_39(ctx):
    start(ctx)
    backend(ctx, *T("ShiftConflictCheckTest", "doppelbelegung_meldung_nennt_beide_schichten", "fehlende_oder_abgelaufene_qualifikation_meldung_nennt_qualifikation", "parallele_zuweisung_wird_spaetestens_bei_publikation_erkannt",
                    "abwesenheitsgenehmigung_parallel_zur_publikation_wertet_den_committeten_stand", "konfliktliste_enthaelt_nur_schichten_des_eigenen_mandanten", "angrenzende_schichten_kein_konflikt"),
            *T("ShiftAssignmentTest", "doppelschicht_ohne_ausnahme_ergibt_409", "parallele_ueberlappende_schichten_hoechstens_eine"),
            *T("RosterPublishTest", "publikation_mit_konflikt_409_ohne_revision_und_ereignis", "publikation_parallel_zu_konfliktausloesender_zuweisung_nie_veroeffentlichter_konflikt"),
            *T("RosterPublishApiTest", "konflikt_ergibt_409_mit_konfliktliste_und_hinweise_sperren_nicht"))
    dbtests(ctx, "test_staff_shift", "test_staff_shift_offen", min_tests=3)
    vitest(ctx, "web/operator", "src/dienstplan", min_tests=8)


@m4_scenario("AT-40", "1.0.0", ["LH-6.24-TIM-006", "ENABLER:WP-M4-13"], "Ruhe-/Pausenkonflikt: Warnhinweis (M4-Umfang)")
def at_40(ctx):
    start(ctx)
    backend(ctx, *T("RuheZeitHinweisTest", "ruhezeit_eine_minute_unter_grenze_erzeugt_warnhinweis", "ruhezeit_exakt_auf_grenze_kein_hinweis", "tagesarbeitszeit_ueberschreitung_erzeugt_hinweis", "pflichtpause_fehlt_erzeugt_hinweis_planungsannahme",
                    "regelkonformer_plan_ohne_hinweis", "hinweise_aendern_keine_plan_oder_ist_daten_und_regeln_sind_konfigurierbar", "hinweis_fuer_dritte_ohne_depotrecht_nicht_sichtbar"),
            *T("ConfigWorkRuleSourceTest", "wirksame_werte_ersetzen_die_gesetzlichen_standardwerte", "ohne_wirksamen_wert_gelten_die_standardwerte"))
    dbtests(ctx, "test_time_event", "test_staff_shift", min_tests=3)
    offen(ctx, "Warnung/Sperre „gemäß freigegebener Regelmatrix“: die Regelmatrix je Beschäftigungsmodell und die Trennung Sperre/Hinweis sind P2 (LH-6.23-SCH-017/-035), der Katalog kennt keinen Pausenparameter (OP 3); geprüft sind Warnhinweise gegen `min_rest_hours` und `max_daily_work_minutes` und die Pflichtpause als Planungsannahme")


@m4_scenario("AT-41", "1.0.0", ["LH-6.23-SCH-006", "ENABLER:WP-M4-06"], "Urlaub gegen Schicht: genehmigte Abwesenheit als Konfliktquelle (M4-Teilumfang)")
def at_41(ctx):
    start(ctx)
    backend(ctx, *T("AbsenceTest", "abwesenheit_anlegen_und_als_restriktion_lesen_mit_audit", "planer_erhaelt_die_art_nicht_und_darf_nicht_anlegen", "ruecknahme_mit_revision_wiederholbar_und_wirkt_auf_die_restriktion",
                    "parallele_ruecknahme_hat_genau_eine_wirkung", "fremdmandant_liest_weder_detail_noch_restriktion_noch_nimmt_er_zurueck"),
            *T("AbsenceApiTest", "personalrolle_legt_an_und_planer_sieht_nur_nicht_verfuegbar", "wiederholter_idempotency_key_legt_nur_eine_abwesenheit_an"),
            *T("ShiftConflictCheckTest", "abwesenheit_meldung_ohne_abwesenheitsart", "abwesenheitsgenehmigung_parallel_zur_publikation_wertet_den_committeten_stand"))
    dbtests(ctx, "test_m4_06_absence_request", "test_staff_shift", min_tests=3)
    offen(ctx, "Offene Ersatzschicht und Änderungsworkflow nach Urlaubsgenehmigung: Abwesenheitsantrag (LH-6.24-TIM-008), Abwesenheitsanzeige (LH-6.23-SCH-013) und Ersatzbedarf (LH-6.23-SCH-020) sind P2 (M7); Teilabnahme in M4 oder Verschiebung ins Gate M7 ist mit dem Auftraggeber zu klären (OP 1, D-12)")


@m4_scenario("AT-45", "1.0.0", ["LH-6.24-TIM-001", "LH-6.24-TIM-002", "LH-6.24-TIM-004"], "Soll-/Ist-Zeiten: Vorbereitung als Arbeitszeit, Pause, Soll/Ist korrekt")
def at_45(ctx):
    start(ctx)
    backend(ctx, *T("SollIstTrennungTest", "at45_vorbereitungszeit_vor_fahrzeugpruefung_ist_erfasste_arbeitszeit", "fehlende_ist_buchung_gekennzeichnet_nicht_mit_planwert_gefuellt", "umplanung_von_fahrten_aendert_ist_zeiten_nicht",
                    "neuveroeffentlichung_aendert_ist_zeiten_nicht", "neuveroeffentlichung_parallel_zu_stempel_beide_erhalten"),
            *T("TimeEventStempelnTest", "einstempeln_vor_fahrzeugfreigabe_wird_angenommen_ohne_freigabe_und_ohne_fahrt", "doppeltes_einstempeln_ist_ein_konflikt_und_wird_nicht_still_angenommen", "paralleler_stempel_gleiche_ereignis_id_genau_eine_zeile",
               "parallele_stempel_zweier_mandanten_bleiben_getrennt"),
            *T("TimeEventTest", "zwei_ist_zeit_ereignisse_zu_einer_schicht_bleiben_nachvollziehbar", "arbeitszeit_ohne_schicht_erfassbar_und_intervall_abzueglich_pausen"),
            *T("TimeIntervalsTest", "mehrere_pausen_werden_abgezogen_und_reihenfolge_der_eingabe_ist_egal"),
            *T("TimeEventOfflineSyncTest", "offline_stempel_genau_einmal_uebernommen", "sync_parallel_zu_online_stempel_derselben_person_verliert_keinen_stempel"),
            *T("TimeTargetActualApiTest", "soll_ist_je_tag_mit_vorbereitungszeit_und_fehlender_buchung"))
    dbtests(ctx, "test_time_event", "test_time_soll_ist_trennung", min_tests=3)
    vitest(ctx, "web/operator", "src/zeit", min_tests=3)
    offen(ctx, "Stempeln in der Fahrer-App (Flutter, UI-09-Erweiterung, Offline-Stempel): Dart-Tests liegen unter mobile/driver/test, Flutter ist in dieser Umgebung nicht installiert (nicht ausgeführt)")


@m4_scenario("AT-47", "1.0.0", ["LH-6.23-SCH-002", "LH-6.24-TIM-005", "LH-I.6-KPI-006"], "DST-Nachtschicht: absolute Zeit, lokale Darstellung, keine stille Verschiebung")
def at_47(ctx):
    start(ctx)
    backend(ctx, *T("ZeitrechnungDstTest", "nachtschicht_herbstumstellung_9_stunden_absolut", "nachtschicht_fruehjahrsumstellung_7_stunden_absolut", "schicht_ueber_mitternacht_dauer_und_bezugstag", "lokale_zeit_in_dst_luecke_wird_abgelehnt",
                    "doppelte_ortszeit_wird_mit_offset_dargestellt", "zwei_stempel_0230_utc_plus_2_und_plus_1_parallel_beide_gespeichert"),
            *T("DstTimeTest", "doppelte_ortszeit_zwei_eindeutige_zeitpunkte", "dauer_ueber_zeitumstellung_aus_absoluten_zeitpunkten", "dst_luecke_und_fehlender_offset_werden_abgewiesen"),
            *T("TimeEventZoneApiTest", "nachtschicht_ueber_herbstumstellung_ueber_http_9_stunden_und_lokale_darstellung", "doppelte_ortszeit_beide_gespeichert_und_luecke_abgelehnt"),
            *T("RosterShiftModelTest", "dst_luecke_wird_abgelehnt", "doppelte_ortszeit_ohne_offset_wird_abgelehnt"),
            *T("ShiftAssignmentTest", "dauer_ueber_sommerzeitende_aus_absoluten_zeitpunkten"), *T("RuheZeitHinweisTest", "ruhezeit_ueber_dst_umstellung_aus_absoluten_zeitpunkten"),
            *T("TimeFactsTest", "dst_doppelstunde_ergibt_zwei_eindeutige_zeitpunkte"))
    dbtests(ctx, "test_time_event", "test_zeitlint", min_tests=3)
    vitest(ctx, "web/operator", "src/dienstplan", min_tests=8)


@m4_scenario("AT-48", "1.0.0", ["LH-6.24-TIM-001", "ENABLER:WP-M4-08"], "Zeitkorrektur: Ist-Zeiten bleiben unverändert (M4-Teilumfang ohne Korrekturantrag)")
def at_48(ctx):
    start(ctx)
    backend(ctx, *T("TimeEventTest", "nacherfassung_eigene_quelle_mit_akteur_im_audit_ohne_ueberschreiben", "gleiche_ereignis_id_mit_anderem_inhalt_ist_409", "mitarbeiter_sieht_nur_eigene_ereignisse_fremder_mandant_abgewiesen"),
            *T("TimeEventApiTest", "ereignisse_sind_nicht_aenderbar_und_die_person_steht_nicht_im_body", "mitarbeiter_sieht_nur_eigene_ereignisse_nacherfassung_nur_personalrolle"),
            *T("TimeEventOfflineSyncTest", "manipulierte_geraetezeit_review_required_ohne_korrektur"))
    dbtests(ctx, "test_time_event", min_tests=3)
    offen(ctx, "Korrekturantrag des Mitarbeiters, Genehmigung/Ablehnung durch den Manager und Historie (`time_correction`, `/v1/time-corrections`): LH-6.24-TIM-007/-014 sind P2 (M7) und in M4 nicht gebaut (OP 2); AT-48 aus dem Gate M4 nehmen oder TIM-007 per D-12 vorziehen")


# --- Kennzahlen (E2E-080 bis E2E-084, PH-AT-021) --------------------------------------------------------------------------------------------------

@m4_scenario("E2E-080", "1.0.0", ["LH-6.14-ANA-001", "LH-6.5-OPT-002", "LH-I.6-KPI-003"], "Kapazitätsablehnung: Anfrage und Normgrund in Nachfrage- und Ablehnungsstatistik sichtbar")
def e2e_080(ctx):
    start(ctx)
    backend(ctx, *ABLEHNUNG, *FUNNEL, *T("RejectionResourceCodesTest", "fuenf_ressourcenfaelle_liefern_fuenf_unterschiedliche_primaergruende"), *T("RejectionCodesTest", "fuenf_faelle_ergeben_fuenf_codes_in_gleicher_berichtsspalte", "letzter_platz_parallel_genau_eine_zusage"),
            *T("RejectionCatalogTest", "jeder_der_sieben_gruende_ist_einzeln_auswertbar", "jeder_katalogcode_hat_deutschen_text_und_naechste_handlung"))
    dbtests(ctx, "test_rejection_reason", "test_funnel_fakten", min_tests=3)
    vitest(ctx, "web/operator", "src/kennzahlen/Kennzahlen.test.tsx", min_tests=8)


@m4_scenario("E2E-081", "1.0.0", ["LH-6.23-SCH-007", "LH-6.5-OPT-002", "LH-I.6-KPI-003"], "Fehlender Fahrer: Ablehnung primär Personalmangel, keine falsche Kapazitätszusage")
def e2e_081(ctx):
    start(ctx)
    backend(ctx, *T("RejectionResourceCodesTest", "freies_fahrzeug_ohne_einsatzbereiten_fahrer_ist_personalmangel_ohne_zusage", "zwei_zutreffende_gruende_ergeben_primaer_und_sekundaergrund_in_katalogreihenfolge",
                    "primaergrund_ist_unabhaengig_von_der_reihenfolge_der_feststellung"), *T("RejectionReasonTest", "primaergrund_deterministisch_und_technischer_fehler_zaehlt_nicht"), *T("RejectionCodesTest", "zwei_gruende_ergeben_primaer_und_sekundaergrund"),
            *T("ShiftStaffingPortTest", "offene_fahrerschicht_ergibt_fahrzeug_unbesetzt", "fehlende_qualifikation_ergibt_unbesetzt"))
    dbtests(ctx, "test_rejection_reason", min_tests=3)
    vitest(ctx, "web/operator", "src/kennzahlen/Kennzahlen.test.tsx", min_tests=8)


@m4_scenario("E2E-082", "1.0.0", ["LH-I.6-KPI-007"], "Verspätete Abholung: Zusage 08:10–08:15, Abholung 08:22, getrennt von der Ankunft")
def e2e_082(ctx):
    start(ctx)
    backend(ctx, *T("PickupPunctualityTest", "zusage_0810_bis_0815_abholung_0822_ergibt_sieben_minuten_getrennt_von_der_ankunft_e2e_082", "grenzfaelle_zwei_drei_vier_minuten_gegen_grenzwert_drei_und_zwei",
                    "grenzwert_wird_nach_dem_zusagezeitpunkt_geaendert_die_fahrt_bleibt_nach_dem_stichtagsgrenzwert_klassifiziert", "abholung_ohne_ist_zeit_ist_nicht_bewertbar_und_nicht_im_nenner"),
            *T("TimeFactsTest", "fuenf_zeitarten_getrennt_verspaetung_nutzt_zusageversion_trotz_umplanung", "fehlende_ist_zeit_ist_nicht_bewertbar"))
    dbtests(ctx, "test_pickup_punctuality", min_tests=3)
    vitest(ctx, "web/operator", "src/kennzahlen/Kennzahlen.test.tsx", min_tests=8)


@m4_scenario("E2E-083", "1.0.0", ["LH-I.6-KPI-008"], "Verspätete Ankunft: Abholung pünktlich, Ankunftsverspätung getrennt")
def e2e_083(ctx):
    start(ctx)
    backend(ctx, *T("ArrivalPunctualityTest", "verspaetete_ankunft_trotz_puenktlicher_abholung_eigene_kennzahl", "toleranzgrenzfaelle_genau_zugesagt_plus_toleranz_ist_puenktlich_eine_sekunde_spaeter_nicht",
                    "vertauschte_reihenfolge_von_abhol_und_ankunftsereignis_aendert_beide_kennzahlen_nicht", "fahrt_ohne_ankunftsereignis_ist_nicht_im_nenner"),
            *T("NotPerformedCauseTest", "fahrzeugausfall_und_no_show_getrennt_erfasst_und_ursache_offen_sichtbar"))
    dbtests(ctx, "test_arrival_punctuality", "test_trip_not_performed", min_tests=3)
    vitest(ctx, "web/operator", "src/kennzahlen/Kennzahlen.test.tsx", min_tests=8)


@m4_scenario("E2E-084", "1.0.0", ["LH-6.14-ANA-002", "LH-I.6-KPI-010", "LH-I.6-KPI-013"], "Statistik bei Datenlücke: Vollständigkeit gekennzeichnet, keine Scheingenauigkeit")
def e2e_084(ctx):
    start(ctx)
    backend(ctx, *T("DistanceMetricsTest", "gps_luecke_weist_vollstaendigkeit_aus", "nullnenner_ergibt_nicht_berechenbar"), *T("MetricRegisterTest", "zwanzig_prozent_fehlende_quelldaten_sind_vorlaeufig_mit_abdeckungsgrad", "nullnenner_und_fehlende_daten_sind_nicht_berechenbar_nie_null_oder_hundert_prozent"),
            *T("MetricsApiTest", "nullnenner_fehlende_daten_und_vorlaeufiger_wert"), *T("LiveDashboardTest", "ereignisstau_zeigt_datenalter_statt_scheinbar_aktuellem_wert"))
    dbtests(ctx, "test_metric_register", "test_kilometer_kennzahlen", min_tests=3)
    vitest(ctx, "web/operator", "src/kennzahlen", min_tests=14)


@m4_scenario("PH-AT-021", "1.0.0", ["LH-I.6-KPI-001", "LH-I.6-KPI-002", "LH-I.6-KPI-004", "LH-I.6-KPI-010", "LH-I.6-KPI-011"], "KPI-Nenner, Datenlücke und Aggregationsschutz (M4-Umfang)")
def ph_at_021(ctx):
    start(ctx)
    backend(ctx, *T("MetricRegisterTest", "fixture_ph_at_021_zehn_versuche_drei_ablehnungen", "nullnenner_und_fehlende_daten_sind_nicht_berechenbar_nie_null_oder_hundert_prozent", "zwanzig_prozent_fehlende_quelldaten_sind_vorlaeufig_mit_abdeckungsgrad",
                    "kleine_gruppen_werden_auch_per_differenzbildung_nicht_offengelegt", "drei_benutzer_identische_werte_und_datenstand", "mandantentrennung_der_kennzahlen"),
            *T("RejectionStatsTest", "drei_ablehnungen_summe_primaergruende_drei", "mehrere_gruende_ergeben_deterministisch_gleichen_primaergrund"), *T("AttemptCountTest", "zehn_anfragen_drei_ablehnungen_ergeben_zehn_nachfrageereignisse"),
            *T("ReportFilterTest", "kleingruppe_nicht_durch_differenzbildung_offengelegt", "manipulierte_tenant_id_liefert_keine_fremden_daten", "parallele_mandanten_abfragen_ohne_vermischung"),
            *T("ReportFilterApiTest", "kleine_gruppe_ist_unterdrueckt_nicht_null", "manipulierter_mandantenbezug_wird_nicht_ausgewertet"), *T("MetricsApiTest", "drei_benutzer_verschiedener_rollen_erhalten_identische_kennzahlen_samt_datenstand"))
    dbtests(ctx, "test_metric_register", "test_rejection_reason", "test_travel_attempt", min_tests=3)
    vitest(ctx, "web/operator", "src/kennzahlen", min_tests=14)


# --- Querschnitt: Live-Dashboard, Invarianten, Verträge, Isolation, Barrierefreiheit --------------------------------------------------------------

@m4_scenario("TECH-M4-LIVE", "1.0.0", ["LH-I.6-KPI-014"], "Live-Dashboard: Fahrzeugsperre innerhalb der Aktualisierungsfrist sichtbar, Datenalter bei Stau, zwei Mandanten getrennt")
def m4_live(ctx):
    start(ctx)
    backend(ctx, *T("LiveDashboardTest", "fahrzeugstatus_innerhalb_aktualisierungsfrist_sichtbar", "vier_getrennte_werte_offene_anfragen_kritische_verspaetung_lange_ausfaelle", "ereignisstau_zeigt_datenalter_statt_scheinbar_aktuellem_wert",
                    "fremder_dienst_und_fremder_mandant_erscheinen_nicht", "zwei_mandanten_gleichzeitige_statusaenderungen_ohne_vermischung", "aufgehobene_sperre_zaehlt_nicht_mehr_und_kapazitaet_kehrt_zurueck"),
            *T("LiveDashboardApiTest", "vier_getrennte_werte_mit_datenstand_und_sperre_sichtbar", "dienstbeschraenkter_benutzer_fremder_mandant_und_ungueltige_parameter"))
    vitest(ctx, "web/operator", "src/kennzahlen/LiveDashboard.test.tsx", min_tests=6)
    offen(ctx, "Aktualisierungsfrist des Pilots (30 s, Veraltung nach 120 s) und die Definitionen „offene Anfrage“, „verfügbare Kapazität“, „kritische Verspätung“: Planungsannahmen bis zum Beschluss D-05 (OP 32), das Ergebnis gilt nicht als Abnahme des Betriebs-Gates")


@m4_scenario("TECH-M4-INVARIANTEN", "1.0.0", ["ENABLER:WP-M4-33", "ENABLER:WP-M4-36", "ENABLER:WP-M4-35"], "Invarianten Kap. 04 §4.5 (M4): Gegenereignis, Journal, eine Kostenwirkung, Umlage mit mindestens zwei parallelen Transaktionen")
def m4_invarianten(ctx):
    start(ctx)
    backend(ctx, *T("PeriodCloseTest", "parallele_gegenereignisse_desselben_ursprungs_ueberschreiten_den_ursprung_nie", "abschluss_parallel_zu_posting_allokation_und_abschluss_genau_eine_reihenfolge", "perioden_ueberlappen_sich_nie_auch_parallel"),
            *T("CostEventPostingTest", "paralleles_posting_desselben_ursprungs_genau_ein_ereignis_und_ein_journal", "posting_parallel_zum_periodenabschluss_ist_konsistent", "ereignis_und_gebuchtes_journal_sind_unveraenderlich_auch_fuer_die_runtime_rolle"),
            *T("AllocationServiceTest", "parallele_allokationsrevisionen_genau_eine_aktiv", "allokation_parallel_zu_periodenabschluss_genau_eine_reihenfolge"),
            *T("DuplicateDetectionTest", "parallele_freigabe_identischer_belege_genau_ein_kostenereignis"), *T("ExpenseApprovalTest", "zwei_freigeber_parallel_genau_ein_posting", "ablehnung_parallel_zu_freigabe_ergibt_genau_einen_ausgang"),
            *T("LedgerExportTest", "parallele_exportlaeufe_ueberschneidender_zeitraeume_kein_journal_doppelt"), *T("CostClassificationTest", "zwei_parallele_umklassifikationen_genau_eine_neubuchung"))
    dbtests(ctx, "test_cost_event_journal", "test_fiscal_period", "test_allocation", min_tests=3)


@m4_scenario("TECH-M4-CONTRACT", "1.0.0", ["ENABLER:WP-M4-63"], "Contract-Fälle Kap. 05 §5.8: EUR-Rundungsrest, geschlossene Periode, Allokationssumme ≠ Basis, Manipulation tenant_id, unzulässige Fremd-ID")
def m4_contract(ctx):
    start(ctx)
    backend(ctx, *T("AllocationServiceTest", "drittelsplit_100_01_eur_ergibt_33_34_33_34_33_33"),                                                                         # EUR-Rundungsrest
            *T("PeriodCloseTest", "posting_in_geschlossene_periode_wird_abgewiesen_ohne_spuren"), *T("AllocationServiceTest", "geschlossene_periode_409_und_gebuchte_allokation_409"),  # geschlossene Periode
            *T("AllocationApiTest", "summe_ungleich_basis_prozent_und_fremder_mandant"), *T("AllocationServiceTest", "summe_ungleich_basis_blockiert_freigabe"),             # Allokationssumme ≠ Basis
            *T("ReportFilterApiTest", "manipulierter_mandantenbezug_wird_nicht_ausgewertet"), *T("ReportFilterTest", "manipulierte_tenant_id_liefert_keine_fremden_daten"),    # Manipulation tenant_id
            *T("CostAssignmentTest", "fremde_id_ergibt_404_ohne_existenzhinweis", "kostenobjekt_ausserhalb_der_objektberechtigung_ergibt_404"),                        # unzulässige Fremd-ID
            *T("CostAssignmentApiTest", "fremde_und_unzulaessige_zuordnung_wird_abgewiesen"), *T("ReceiptCaptureTest", "fremdes_fahrzeug_403_entwurf_bleibt_erhalten"), *VERTRAG)
    dbtests(ctx, "test_allocation", "test_fiscal_period", "test_expense_cost_assignment", min_tests=3)


@m4_scenario("TECH-M4-ISOLATION", "1.0.0", ["ENABLER:WP-M4-64"], "M4-Anteile von PH-AT-001, AT-49 und E2E-091: Isolation aller M4-Tabellen und -Endpunkte unter der Runtime-Rolle")
def m4_isolation(ctx):
    start(ctx)
    backend(ctx, *VERTRAG, *T("RosterDepotAuthorizationTest", "planer_fremdes_depot_lesen_aendern_veroeffentlichen_abgewiesen", "manipulierte_tenant_id_im_payload_wirkungslos"),
            *T("ReceiptCaptureTest", "fremder_mandant_kein_zugriff_auf_beleg"), *T("TimeEventStempelnTest", "parallele_stempel_zweier_mandanten_bleiben_getrennt"), *T("MetricRegisterTest", "mandantentrennung_der_kennzahlen"))
    dbtests(ctx, "test_m4_64_katalog", "test_m4_64_modulzugaenge", "test_isolation_alle_tabellen", min_tests=2)
    dbtests(ctx, "test_analytics_rls", "test_analytics_fakten_rls", min_tests=1)


@m4_scenario("TECH-M4-A11Y", "1.0.0", ["ENABLER:WP-M4-18", "ENABLER:WP-M4-20", "ENABLER:WP-M4-38", "ENABLER:WP-M4-44", "ENABLER:WP-M4-59"], "Barrierefreiheitsnachweis UI-12 bis UI-15 und Kennzahlenansicht (Tastatur, Zoom, Reflow; Kap. 06 §6.1)")
def m4_a11y(ctx):
    start(ctx)
    vitest(ctx, "web/operator", "src/dienstplan", min_tests=8)     # UI-12 (axe, Tastaturbedienung der Planungsfläche)
    vitest(ctx, "web/operator", "src/zeit", min_tests=3)           # UI-13
    vitest(ctx, "web/operator", "src/belege", min_tests=15)        # UI-14
    vitest(ctx, "web/operator", "src/kosten", min_tests=3)         # UI-15
    vitest(ctx, "web/operator", "src/kennzahlen", min_tests=14)    # Kennzahlenansicht und Live-Betrieb
    browser_a11y_operator(ctx)
    screenreader_offen(ctx, "UI-12 bis UI-15 und Kennzahlenansicht")
