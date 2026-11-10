# M6c – Checkout, Zahlung P2 und Partnerclearing

- **Meilenstein:** M6 – Föderation, gemeinsamer Checkout, Clearing
- **Phase:** P2 (Originalphase aller 23 Anforderungen; unverändert)
- **Anzahl Anforderungen:** 23 (15 MUSS, 8 SOLL) – zwölf aus Abschnitt 6.10 (PAY-005 bis PAY-016) und elf aus Anhang I.9 (COM-001 bis COM-009, COM-011, COM-012)
- **Anzahl WPs:** 32 (23 mit Anforderungen, 9 Enabler)

**Ziel:** Nach M6c kann eine Reise mit Segmenten mehrerer Betreiber in einem Checkout bezahlt werden, ohne dass rohe Zahlungsdaten das System erreichen; Gutscheine, Guthaben, Tarifberechtigungen, Trinkgeld und dienstabhängige Zahlungsarten wirken centgenau und konkurrenzsicher. Storno und Teilstorno erzeugen den vorgesehenen Erstattungsfall mit Refund-Obergrenze. Aus bestätigten Segmentleistungen und der eingefrorenen Vertragsversion entstehen getrennte Ansprüche (Kundenbuchung, Entgeltanspruch des Beförderers, Vermittlungsprovision), monatliche Gegenberichte, Auszahlung bzw. Rückforderung, Abgleich über vier Ebenen, Streitfälle und eine lückenlose Nachweiskette. Fachgrundlage ist Kap. 03 §4 (Mehrere Betreiber, ein Checkout), §5 (Rechenbeispiel A), §7 (COST-03/04/09/10) sowie Kap. 02 §5 (Vertrag, Pause, Widerruf).

**Abgrenzung der Einheit:** M6a liefert Reiseauskunft, Saga (Hold/Confirm/Cancel), Stornoangebot und Ausführungsnachweis; M6b liefert Grants, Rechtsträgerrollen, Projektionen und Integrationsbausteine. M6c liefert die Geld- und Anspruchsseite. Tabellen- und Pfadnamen, die im Pflichtenheft **nicht** vorkommen, sind in dieser Datei ausdrücklich als „Vorschlag“ gekennzeichnet und in „Offene Punkte“ aufgeführt.

## Übersicht

| WP-ID | Titel | Anforderungen (Anzahl) | Größe | hängt ab von |
|---|---|---:|---|---|
| WP-M6c-01 | Detailverträge Zahlung P2, Guthaben und Clearing | 0 | M | WP-M0-13, WP-M0-31, WP-M1-02, WP-M1-03, WP-M1-07, WP-M2-29, WP-M2-30, WP-M6a-01, WP-M6b-01 |
| WP-M6c-02 | Schema-Migration Zahlung P2 und Clearing | 0 | M | WP-M6c-01, WP-M0-15, WP-M0-16, WP-M0-18, WP-M2-07, WP-M2-29, WP-M4-33, WP-M4-36, WP-M6a-02 |
| WP-M6c-03 | PSP-Simulator für Mehrparteienzahlung und Referenzfixtures | 0 | M | WP-M6c-01, WP-M0-22, WP-M0-23, WP-M6a-03 |
| WP-M6c-04 | Tokenisierte Onlinezahlung ohne rohe Zahlungsdaten | 1 | S | WP-M6c-01, WP-M1-13, WP-M1-37, WP-M2-29, WP-M2-30, WP-M2-42, WP-M5-06 |
| WP-M6c-05 | Dienstabhängige Zahlungsarten einschließlich Barzahlung beim Fahrer | 1 | M | WP-M6c-01, WP-M6c-02, WP-M1-20, WP-M1-21, WP-M1-22, WP-M2-21, WP-M2-23, WP-M2-42, WP-M3-24, WP-M3-29 |
| WP-M6c-06 | Preiskomponenten mit Rollen und centgenauer Rabattverteilung | 0 | M | WP-M6c-02, WP-M2-08, WP-M2-09, WP-M4-35, WP-M6a-23, WP-M6b-12 |
| WP-M6c-07 | Mehrparteien-Checkout: Autorisierung, Capture-Regel, Void | 0 | L | WP-M6c-03, WP-M6c-04, WP-M6c-06, WP-M1-13, WP-M1-14, WP-M1-15, WP-M2-29, WP-M2-30, WP-M2-31, WP-M2-32, WP-M6a-13, WP-M6a-14, WP-M6a-15, WP-M6a-16 |
| WP-M6c-08 | UI-03 Mehrparteien-Checkout: Zahlungsauswahl und Rechnungsausweis | 0 | M | WP-M6c-05, WP-M6c-07, WP-M2-35, WP-M2-42, WP-M2-43, WP-M6a-11, WP-M7b-17, WP-M7b-19, WP-M7b-35 |
| WP-M6c-09 | Gutschein- und Promo-Code-Regeln | 1 | M | WP-M6c-02, WP-M6c-06, WP-M6c-08, WP-M1-07, WP-M1-46, WP-M2-07, WP-M2-21, WP-M2-23 |
| WP-M6c-10 | Guthaben und Pässe mit Zustandsautomat | 1 | M | WP-M6c-02, WP-M6c-06, WP-M6c-08, WP-M1-06, WP-M1-13, WP-M1-14, WP-M2-23, WP-M2-36, WP-M4-33 |
| WP-M6c-11 | Tarifberechtigungen per Nachweis- oder API-Prüfung | 1 | M | WP-M6c-02, WP-M6c-06, WP-M6c-08, WP-M1-07, WP-M1-31, WP-M1-33, WP-M2-07, WP-M2-36, WP-M4-21, WP-M6a-24 |
| WP-M6c-12 | Freunde-werben-Freunde mit Betrugsgrenzen | 1 | M | WP-M6c-10, WP-M1-20, WP-M1-28 |
| WP-M6c-13 | Trinkgeld als getrennte Zahlung | 1 | M | WP-M6c-06, WP-M6c-07, WP-M2-29, WP-M2-43, WP-M3-29 |
| WP-M6c-14 | Stornierungs- und Erstattungsregeln, Erstattungsfall und Refund-Obergrenze | 1 | L | WP-M6c-02, WP-M6c-06, WP-M6c-07, WP-M6c-09, WP-M6c-10, WP-M2-26, WP-M2-31, WP-M2-46, WP-M3-29, WP-M3-39, WP-M3-53, WP-M3-54, WP-M4-36, WP-M6a-22 |
| WP-M6c-15 | Kulanzgutschriften mit Rollenprüfung und Begründung | 1 | S | WP-M6c-10, WP-M6c-14, WP-M1-07, WP-M1-12, WP-M1-13, WP-M1-46 |
| WP-M6c-16 | Quittungen und Belege je Zahlungsmodell | 1 | M | WP-M6c-05, WP-M6c-06, WP-M6c-07, WP-M6c-13, WP-M6c-14, WP-M1-08, WP-M1-11, WP-M1-31, WP-M1-37, WP-M2-32, WP-M2-43 |
| WP-M6c-17 | Kooperationsvertrag: Parteien, Gebiet, Servicezeiten, Leistungsarten | 1 | M | WP-M6c-01, WP-M6c-02, WP-M1-05, WP-M1-22, WP-M1-46, WP-M6a-04, WP-M6a-05, WP-M6b-05, WP-M6b-06, WP-M6b-07, WP-M6b-08 |
| WP-M6c-18 | Vertragsablauf und dokumentierte Auslaufregel | 1 | M | WP-M6c-17, WP-M6a-04, WP-M6a-15, WP-M6b-05, WP-M6b-06, WP-M6b-07 |
| WP-M6c-19 | Clearingvereinbarung und Verteilungsabrechnung je Segment | 1 | M | WP-M6c-17, WP-M1-19, WP-M1-20, WP-M1-22, WP-M4-35 |
| WP-M6c-20 | Anspruchsledger: getrennte Transaktionshistorie und Stornokorrektur | 1 | L | WP-M6c-02, WP-M6c-07, WP-M6c-14, WP-M6c-19, WP-M1-14, WP-M4-33, WP-M4-36, WP-M6a-25 |
| WP-M6c-21 | Steuer- und Rechnungsstellungsmodell je Leistungsart und Vertragsbeziehung | 1 | M | WP-M6c-19, WP-M6c-20, WP-M1-05, WP-M1-20, WP-M1-22, WP-M4-33, WP-M4-37, WP-M6b-12 |
| WP-M6c-22 | Auszahlungsanweisung, Transferreversal und Rückforderung | 0 | M | WP-M6c-03, WP-M6c-20, WP-M6c-23, WP-M1-14, WP-M1-15, WP-M2-29 |
| WP-M6c-23 | Clearingdatensätze und monatliche Gegenberichte | 1 | M | WP-M6c-20, WP-M6c-21, WP-M1-02, WP-M1-18, WP-M4-36, WP-M4-37, WP-M6a-25, WP-M8c-27 |
| WP-M6c-24 | Abrechnung von Subunternehmerleistungen | 1 | M | WP-M6c-19, WP-M6c-20, WP-M6c-23, WP-M4-02, WP-M4-03, WP-M4-08, WP-M4-42, WP-M6b-12, WP-M6b-33 |
| WP-M6c-25 | Rechnungs- und Sammelzahlmodelle für Unternehmen und Partner | 1 | M | WP-M6c-05, WP-M6c-16, WP-M6c-21, WP-M1-05, WP-M1-08, WP-M4-33, WP-M4-36, WP-M4-37 |
| WP-M6c-26 | Zahlungsreconciliation über vier Ebenen | 1 | L | WP-M6c-03, WP-M6c-16, WP-M6c-20, WP-M6c-22, WP-M6c-23, WP-M1-14, WP-M1-15, WP-M2-29, WP-M3-52, WP-M4-36, WP-M6a-15 |
| WP-M6c-27 | Abgleich von Drittplattformdaten mit Fahrzeugereignissen | 1 | M | WP-M6c-23, WP-M6c-26, WP-M3-29, WP-M6a-17, WP-M6a-25 |
| WP-M6c-28 | Streitfälle mit gesperrtem strittigem Betrag | 1 | M | WP-M6c-20, WP-M6c-22, WP-M6c-23, WP-M1-08, WP-M1-12, WP-M1-46, WP-M4-21 |
| WP-M6c-29 | Unveränderliches Auditprotokoll und Nachweiskette der Partnerabrechnung | 1 | M | WP-M6c-20, WP-M6c-22, WP-M6c-23, WP-M6c-28, WP-M1-12, WP-M1-14, WP-M1-46, WP-M4-33, WP-M8c-27 |
| WP-M6c-30 | UI-16 Arbeitsplatz Clearing und Abgleich | 0 | M | WP-M6c-23, WP-M6c-26, WP-M6c-28, WP-M1-07, WP-M1-43, WP-M4-38, WP-M4-44 |
| WP-M6c-31 | Partnerportal: eigene Forderungen, Gutschriften, Belastungen, Status | 1 | M | WP-M6c-18, WP-M6c-22, WP-M6c-23, WP-M6c-28, WP-M1-01, WP-M1-07, WP-M1-08, WP-M1-11, WP-M6b-05, WP-M6b-09 |
| WP-M6c-32 | Gate-Nachweis M6c und semantische Contract-Fälle | 0 | M | WP-M6c-04 bis WP-M6c-31, WP-M0-22, WP-M0-32, WP-M6a-03, WP-M6a-13, WP-M6a-14, WP-M6a-22, WP-M6b-05, WP-M6b-07, WP-M6b-09 |

## Arbeitspakete

### WP-M6c-01 – Detailverträge Zahlung P2, Guthaben und Clearing

- **Ziel:** Vor Codebeginn liegen OpenAPI-, DDL-, Zustandsautomaten- und JSON-Schema-Verträge für alle 23 Anforderungen der Einheit vor (Leitlinie „Vertrag vor Code“, Kap. 08 §8.4), sodass die folgenden WPs gegen feste Schnittstellen arbeiten.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - API: OpenAPI-3.1-Dateien unter `api/` für Modul `fare-payment` (Erweiterung um Zahlungsarten, Gutscheine, Guthaben, Berechtigungen, Trinkgeld, Erstattungsfälle, Quittungen; Pfadnamen sind Vorschläge, da Kap. 05 §5.7 dafür keine Pfade nennt) und Modul `documents-finance` (`/v1/clearing/statements` aus Kap. 05 §5.7 samt Unterressourcen für Zeilen, Streitfälle, Auszahlungen, Abgleichläufe). Präzisierung von `confirmJourney` (`/v1/federation/journeys/{journey_id}/confirm`), `quoteCancellation` (`/v1/federation/journeys/{journey_id}/cancellation-quotes`) und `cancelJourney` (`/v1/federation/journeys/{journey_id}/cancel`) um Zahlungsart, Guthaben-/Gutscheineinsatz und Erstattungsaufstellung, abgestimmt mit M6a.
  - Zustandsautomaten (Dokument + Transitionstabelle): Zahlung `CREATED → REQUIRES_ACTION → AUTHORIZED → CAPTURE_PENDING → CAPTURED` mit Nebenpfaden `AUTHORIZATION_FAILED`, `VOID_PENDING → VOIDED`, `CAPTURE_UNKNOWN → RECONCILING`, `REFUND_PENDING → PARTIALLY_REFUNDED | REFUNDED`, Streitstatus `DISPUTED` separat (Kap. 03 §4.2); je eigener Automat für Refund, Transfer, Payout (Kap. 03 §4.2: „unterschiedliche Objekte/Zustandsautomaten“); `settlement_line` `PROVISIONAL → ELIGIBLE → STATEMENTED → ACCEPTED → PAYOUT_PENDING → PAID` mit separatem Streitfallstatus (Kap. 03 §4.3); Vertrag `DRAFT → REVIEWED → ACTIVE → PAUSED/EXPIRED/REVOKED` (Kap. 02 §5); Guthaben/Pass `aktiv`, `gesperrt`, `abgelaufen`, `verbraucht` (aus `LH-6.10-PAY-009`); Erstattungsfall; Streitfall.
  - JSON-Schemas: `partner_contract_version.terms` (Parteien, Genehmigungsgebiet, Servicezeiten, Leistungsarten, Ansprechpartner, Clearingregel, Haftungs-/Stornoregel, Auslaufregel); `price_components` (Kap. 03 §4.1); Regelschemas für `partner_settlement_model`, `partner_liability_policy`, `cost_split_rule`.
  - Datenbank: DDL-Entwurf für WP-M6c-02 einschließlich der Erweiterung von `settlement_line` um die Pflichtinhalte aus Kap. 03 §2.
  - Ereignisse/Outbox: Ereigniskatalog M6c (`event_type`, `schema_version`, Payload) für Zahlung, Erstattung, Guthaben, Anspruch, Statement, Auszahlung, Streitfall, Vertrag.
  - Rechtematrix: Rollen für Finanzprüfung, Clearingfreigabe, Kulanzgutschrift, Vertragsverwaltung, Partnerleser (Ergänzung der Rechtematrix aus M1).
- **Detailverträge vorab:** Dieses WP ist der Detailvertrag. Eingang: Beschlussstand zu D-01, D-03, D-04, D-06 (ohne Beschluss gelten die Favoriten aus Kap. 07 als Sandbox-Annahme und werden so gekennzeichnet).
- **Akzeptanzkriterien:**
  1. Alle OpenAPI-Dateien sind als OpenAPI 3.1 parsbar, `$ref` auflösbar, `operationId` eindeutig, Beispiele schemagültig (Kap. 05 §5.8).
  2. Jede der 23 Anforderungen ist in `arbeitspakete.csv` mit Detailvertrag und mindestens einem positiven Testfall verknüpft.
  3. Jeder Zustandsautomat nennt unzulässige Übergänge und das Verhalten bei `UNKNOWN`; kein Automat verkürzt „unbestimmt“ zu Erfolg oder Fehler.
  4. Geldfelder verwenden `{amount_minor,currency,exponent}` bzw. `SignedMoney` für Korrekturen; Sätze und Schlüssel sind Dezimalstrings (Kap. 05 §5.1).
  5. Jeder als „Vorschlag“ gekennzeichnete Tabellen- oder Pfadname ist mit dem Auftraggeber bzw. der Architekturrunde bestätigt oder ersetzt.
- **Tests:** positiv: Vertragsprüfung in CI (`validate_artifacts.py`) grün für alle neuen Dateien. negativ: ein absichtlich ungültiges Beispiel bricht die Vertragsprüfung ab.
- **Abhängigkeiten:**
  - intern: keine
  - extern: WP-M0-31, WP-M0-13 (Detailvertragsprozess und CI-Stufe Vertragsprüfung)
  - extern: WP-M1-03, WP-M1-02, WP-M1-07 (OpenAPI je Modul, Fehlermodell, Rechtematrix)
  - extern: WP-M2-30, WP-M2-29 (Zahlungsautomat und PSP-Adapterschnittstelle der Einbetreiberzahlung als Ausgangsstand)
  - extern: WP-M6a-01 (Detailverträge Föderation (Confirm, Stornoangebot, Cancel))
  - extern: WP-M6b-01 (Vertrags-/Grant-Lebenszyklus und Rechtsträgerrollen)
- **Entscheidungen:** D-01, D-03, D-04, D-06 – blockieren nicht die Vertragsarbeit, aber die Festschreibung produktiver Regeln (nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Verträge für Saga und Adapter (M6a), Grants und Projektionen (M6b), Eingangsrechnungen und TCO (M8).

### WP-M6c-02 – Schema-Migration Zahlung P2 und Clearing

- **Ziel:** Alle Tabellen der Einheit existieren als Flyway-Migration mit Mandantenschlüssel, zusammengesetzten FKs, RLS, Revision und Audit; die Geld-Invarianten sind in der Datenbank verankert.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Datenbank (Kap. 04 §4.4, Modul PAY): `fare_component`, `voucher_redemption`, `refund_case`; Erweiterung von `payment_event` (aus M2) um Transfer-, Payout- und Chargeback-Ereignisse.
  - Datenbank (Kap. 04 §4.4, Modul Clearing I.9/COM): `settlement_statement`, `settlement_reconciliation`, `payout_instruction`, `transfer_reversal`.
  - Datenbank (Kap. 03 §2, ergänzende Relationsobjekte): `dispute_case`, `reconciliation_run`, `payment_order`, `tax_rule_revision` sowie Rechnungs-/Gutschriftenposition.
  - Datenbank (Erweiterung Kerntabellen): `settlement_line` um Leistenden, Verkäufer, Erlös, Steuer, Provision, Anspruchsgrund, Abrechnungsstatus und Statementbezug (Kap. 03 §2); `partner_contract_version` bleibt unveränderlich, Vertragsstatus wird in einer getrennten Statustabelle geführt (Trigger `contract_immutable`); `payment_intent` um Zahlungsart und Zweck (Reise/Trinkgeld).
  - Datenbank (Vorschlag, im Pflichtenheft ohne Tabellennamen): Gutscheindefinition, Guthabenkonto mit unveränderlichen Guthabenbewegungen, Pass, Tarifberechtigung, Empfehlung, Quittung, Zahlungsartfreigabe je Dienst.
  - Invarianten (Kap. 04 §4.5): „Gegenereignis statt Änderung“ für `settlement_line`, `settlement_statement`-Revisionen und Guthabenbewegungen (UPDATE/DELETE-Verbot per Trigger analog `reject_mutation()`); „Keine lokale Cross-Tenant-Referenz“; „Kein Mandantenkontext → keine Zeile“; CHECK erstattet ≤ erfasst ≤ autorisiert bleibt an `payment_intent`; Summe der `refund_case`-Beträge je Zahlung ≤ `captured_minor`; Guthabensaldo als Projektion der Bewegungen, nie negativ.
  - Datenbank (Konventionen Kap. 04 §4.1 und §4.7): jede neue Aggregattabelle erhält `revision`, `created_at`, Urheber, Änderungshistorie und Löschklasse; Indizes für die Statement-, Abgleich- und Guthabenabfragen; Nachweisdokumente (Tarifberechtigung, Streitfall) und Quittungen nur als Objektspeicherreferenz mit Hash.
  - RLS: Policy `tenant_isolation` auf allen neuen Tabellen; Finanztabellen zusätzlich mit eigenem Modulzugang (Kap. 04 §4.6).
- **Detailverträge vorab:** DDL aus WP-M6c-01; Entscheidung über die Bindung von `settlement_line.leg_id` für Leistungen ohne Segment (Offener Punkt 12).
- **Akzeptanzkriterien:**
  1. Migration läuft frisch und aus dem freigegebenen Vorstand; Testdaten beider Mandanten bleiben erhalten (Kap. 04 §4.7).
  2. Unter der Runtime-Rolle liefert jede neue Tabelle ohne Tenantkontext keine Zeile; ein INSERT mit fremder `tenant_id` oder fremdem FK scheitert.
  3. UPDATE/DELETE auf einer gebuchten `settlement_line`, einer Statementrevision und einer Guthabenbewegung wird abgewiesen.
  4. Alle Geldspalten sind `bigint` in kleinster Einheit mit `currency`; keine `float`-Spalte.
  5. Jede neue Tabelle trägt eine Löschklasse; für Finanz- und Guthabentabellen ist dokumentiert, dass eine Datenschutzlöschung (`/v1/privacy/requests`) aufbewahrungspflichtige Geldbewegungen nicht entfernt, sondern den Personenbezug nach der freigegebenen Regel trennt (Fristen: D-11, Offener Punkt 21).
  6. Zwei Anspruchskomponenten desselben Gläubigers am selben Segment (Beförderungsentgelt und Provisionskorrektur) sind nebeneinander speicherbar; dieselbe Komponente ein zweites Mal wird durch die Unique-Regel abgewiesen (Kap. 03 §2: „jede ist separat eindeutig“).
- **Tests:** positiv: Migration + Seed. negativ: Fremdmandantenzugriff, Mutation unveränderlicher Zeilen. konkurrierend: je Invariante ein Test mit zwei parallelen Transaktionen (Kap. 04 §4.5 Schlussabsatz), u. a. zwei parallele Erstattungsfälle gegen dieselbe Zahlung.
- **Abhängigkeiten:**
  - intern: WP-M6c-01
  - extern: WP-M0-16, WP-M0-15, WP-M0-18 (Kernschema als Flyway-Migration, Datenbankrollen, RLS-Testharness)
  - extern: WP-M2-07, WP-M2-29 (Tabellen `tariff_revision`, `tariff_rule`, `payment_event`)
  - extern: WP-M4-33, WP-M4-36 (`economic_event`, `journal`, `journal_line`, `fiscal_period` im produktiven Zuschnitt)
  - extern: WP-M6a-02 (Schema-Migration Föderation (`journey_leg`, Vertragsbezug))
- **Entscheidungen:** D-11 (Aufbewahrungs- und Löschfristen der neuen Tabellen) – blockiert nur Produktivschaltung; sonst keine.
- **Größe:** M
- **Nicht im Umfang:** Fachlogik der Tabellen (Folge-WPs); Tabellen der Module DOC/FCO (M8).

### WP-M6c-03 – PSP-Simulator für Mehrparteienzahlung und Referenzfixtures

- **Ziel:** Der PSP-Simulator beherrscht Mehrparteienfälle mit steuerbaren Fehlern, und die Rechenbeispiele des Pflichtenhefts liegen als versionierte Fixtures vor, sodass alle Folge-WPs ihre Geldfälle deterministisch prüfen können.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: Erweiterung des PSP-Simulators unter `tests/acceptance` um Split/Transfer je Betreiber, Transferreversal (erfolgreich und „unzureichendes Partnerguthaben“, Kap. 03 §5), Teilrefund, Payout mit Bankreferenz, Settlement-Datei, Chargeback, SCA-Verzögerung länger als Hold, Timeout, verlorene Antwort, doppelte/verspätete/ungeordnete Webhooks.
  - Fixtures: Rechenbeispiel A (Kap. 03 §5: 10,70 + 21,40 = 32,10; Provision 3,57 brutto; Auskehrung 28,53; B-Storno); PH-AT-005 (A 10,00 / B 15,00); PH-AT-014 (Erstattung 12,00, Stornoentgelt 3,00); COST-10 (Verbundrabatt auf zwei Segmente); Vertrag A↔B mit Test-Clearingregel und Test-Steuerregel (Testdaten, keine Steuerfeststellung).
- **Detailverträge vorab:** Szenariokatalog mit Seed, erwarteter Zustandsfolge und Fehlerinjektionspunkten; Fixture-Schema mit Vertrags-, Tarif-, Steuer- und Konfigurationsrevision (Kap. 08 §8.4).
- **Akzeptanzkriterien:**
  1. Jedes Szenario ist per Seed reproduzierbar.
  2. Fehler lassen sich je Zahlungsschritt (Authorize, Capture, Transfer, Refund, Reversal, Payout) gezielt injizieren.
  3. Die Fixtures enthalten Sollwerte je Journal und je `settlement_line`, mit denen COST-03 maschinell verglichen wird.
- **Tests:** positiv: Selbsttest des Simulators gegen den Szenariokatalog. negativ: ein Szenario mit absichtlich falschem Sollwert schlägt fehl.
- **Abhängigkeiten:**
  - intern: WP-M6c-01
  - extern: WP-M0-23, WP-M0-22 (PSP-Simulator mit steuerbaren Fehlern, Akzeptanztestgerüst, Zwei-Mandanten-Fixtures)
  - extern: WP-M6a-03 (Sandbox-Partner und Fixtures Mandant A, Mandant B, Vertrag A↔B)
- **Entscheidungen:** D-03 – PSP-Auswahl offen; der Simulator bildet das favorisierte Mehrparteienmodell ab (blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Anbindung eines realen PSP (WP-M6c-07 nach D-03-Beschluss).

### WP-M6c-04 – Tokenisierte Onlinezahlung ohne rohe Zahlungsdaten

- **Ziel:** Onlinezahlung läuft ausschließlich über PSP-Tokens; es ist nachgewiesen, dass weder Datenbank noch Logs, Events oder Backups vollständige Zahlungsinstrumentdaten enthalten.
- **Anforderungen:** `LH-6.10-PAY-005`
- **Liefergegenstände:**
  - Backend: Modul `fare-payment`; Zahlungsmittelreferenz als reiner PSP-Referenzwert am `PaymentIntent`; gespeicherte Zahlungsmittel eines Fahrgasts nur als PSP-Token mit maskierter Anzeigeinformation des PSP.
  - API: `/v1/federation/journeys/{journey_id}/confirm` und `/v1/bookings` akzeptieren nur PSP-Referenzen („rohe Zahlungsdaten erreichen diese API nie“, Kap. 05 §5.3); Schema-Validierung weist kartennummernähnliche Felder ab.
  - Oberfläche: UI-03 bindet die PSP-Zahlungsauswahl als PSP-gehostete Komponente ein („Zahlung [PSP-Zahlungsauswahl]“, Kap. 06 §6.3).
  - Datenbank: `payment_intent` (`provider`, `provider_ref`), `payment_event`; keine Spalte für Kartennummer, Prüfziffer oder vollständige Kontodaten.
  - Adapter/Worker: PSP-Adapter (`backend/adapters/psp`) tokenisiert clientseitig; Log-/Trace-Maskierung für Zahlungsfelder.
- **Detailverträge vorab:** OpenAPI-Schema der Zahlungsreferenz; Liste verbotener Feldmuster; Prüfskript für Datenbank- und Logscan.
- **Akzeptanzkriterien:**
  1. Nach einem vollständigen Zahlungslauf (Authorize, SCA, Capture, Refund) findet ein automatisierter Scan über alle Tabellen, `outbox`, `inbox`, `audit_event`, Anwendungslogs und einen Backup-Dump keine vollständigen Zahlungsinstrumentdaten (PAY-005).
  2. Ein Request mit rohen Kartendaten wird mit `422` abgewiesen und nicht persistiert, auch nicht im Idempotenzregister (`idempotency_record.response_json`) (PAY-005).
  3. Eine Zahlung ist mit gespeichertem PSP-Token ohne erneute Dateneingabe möglich; das Token ist mandanten- und fahrgastgebunden.
- **Tests:** positiv: Zahlung mit Token im PSP-Simulator. negativ: Kartennummer im Request, im Freitextfeld und im Webhook-Payload; Zugriff eines anderen Mandanten auf ein Token (404). konkurrierend: zwei parallele Confirms mit demselben Token und demselben Idempotenzschlüssel erzeugen eine Autorisierung (PH-AT-011).
- **Abhängigkeiten:**
  - intern: WP-M6c-01
  - extern: WP-M1-37, WP-M5-06, WP-M1-13 (Zahlungsdatentrennung, Maskierung von Diagnosedaten, Idempotenzregister)
  - extern: WP-M2-29, WP-M2-30, WP-M2-42 (PSP-Adapter und Provider-Ereignisse, Zahlungsautomat, UI-03 Checkout)
- **Entscheidungen:** D-03 (PSP-Auswahl) – blockiert nur Produktivschaltung; Bau gegen PSP-Simulator/Sandbox.
- **Größe:** S
- **Nicht im Umfang:** Mehrparteien-Aufteilung (WP-M6c-07); alternative Zahlungsarten (WP-M6c-05).

### WP-M6c-05 – Dienstabhängige Zahlungsarten einschließlich Barzahlung beim Fahrer

- **Ziel:** Je Dienst ist konfigurierbar, welche Zahlungsarten zulässig sind; der Checkout zeigt nur freigegebene Arten, und der Server lehnt nicht freigegebene ab.
- **Anforderungen:** `LH-6.10-PAY-016`
- **Liefergegenstände:**
  - Backend: Modul `fare-payment`; Zahlungsartfreigabe je Dienst als versionierte Konfiguration (`config_revision`, Scope Dienst); Zahlungsart am `PaymentIntent`; Zustandsführung einer Bar-/Fahrerzahlung getrennt vom PSP-Automaten (offen → beim Fahrer erhalten → abgerechnet).
  - API: Angebots-/Checkoutantwort listet zulässige Zahlungsarten je Angebot; `/v1/bookings` und `/v1/federation/journeys/{journey_id}/confirm` prüfen die gewählte Art serverseitig; Fahrerquittierung „Zahlung erhalten“ über `/v1/driver/events` (neuer Ereignistyp, Vorschlag).
  - Oberfläche: UI-03 zeigt nur freigegebene Zahlungsarten; die Callcenter-Buchung (UI-06, Maske in M7) erhält über dieselbe API ebenfalls nur freigegebene Arten; UI-10 (Driver Fahrt) zeigt offenen Barbetrag und Quittierung; UI-17 (Gebiete/Stops/Tarife) bzw. Konfigurationseditor pflegt die Freigabe.
  - Datenbank: `payment_intent` (Zahlungsart), Zahlungsartfreigabe je Dienst (Vorschlag), `economic_event` für den Barzufluss.
  - Konfigurationsparameter: im Katalog `kataloge.csv` existiert **kein** Parameter für Zahlungsarten (Offener Punkt 7); neuer Parameter ist als Katalogergänzung zu beantragen.
- **Detailverträge vorab:** Enum der Zahlungsarten; Regel bei gemischter Reise mit mehreren Diensten (Schnittmenge der Freigaben); Ereignisschema der Fahrerquittierung; Behandlung nicht quittierter Barzahlung.
- **Akzeptanzkriterien:**
  1. Ist „Barzahlung/Bezahlung beim Fahrer“ für einen Dienst nicht freigegeben, erscheint sie weder in der Angebotsantwort noch in UI-03 (PAY-016).
  2. Ein direkter API-Aufruf mit nicht freigegebener Zahlungsart wird mit `422` abgewiesen; es entsteht keine Buchung und keine Zahlung (PAY-016).
  3. Eine Konfigurationsänderung wirkt nur auf neue Angebote; ein bereits gehaltenes Angebot behält seine zugesagte Zahlungsart.
  4. Bei Reisen über mehrere Dienste wird nur die Schnittmenge der freigegebenen Zahlungsarten angeboten.
  5. Die Fahrerquittierung ist idempotent (stabile Event-ID) und nur für die aktuell zugeordnete Buchung zulässig; eine offline erfasste Quittierung wird nach Synchronisation mit Ereignis-, Geräte- und Serverempfangszeit verarbeitet und liefert `APPLIED`, `DUPLICATE` oder `REVIEW_REQUIRED` (Kap. 05 §5.7).
  6. Änderungen der Zahlungsartfreigabe sind als Konfigurationsrevision mit Akteur im Audit nachweisbar; ohne Konfigurationsrecht wird die Änderung mit 403 abgewiesen.
- **Tests:** positiv: Dienst mit Barzahlung, Fahrer quittiert, Zufluss gebucht. negativ: nicht freigegebene Art per API, auch mit Akteur Callcenter-Agent; Quittierung für fremde Buchung; Konfigurationsänderung ohne Recht. konkurrierend: doppelte Offline-Quittierung desselben Events erzeugt genau einen Zufluss.
- **Abhängigkeiten:**
  - intern: WP-M6c-01, WP-M6c-02
  - extern: WP-M1-20, WP-M1-21, WP-M1-22 (Konfigurationsrevisionen, Präzedenzkette, Publikation)
  - extern: WP-M2-21, WP-M2-23, WP-M2-42 (Angebotsobjekt, Confirm, UI-03 Checkout)
  - extern: WP-M3-29, WP-M3-24 (Fahrerereignisse und Driver-App (UI-10))
- **Entscheidungen:** D-03 (Zahlungsarten sind Teil des benötigten Beschlusses) – blockiert nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Rechnungszahlung für Unternehmen (WP-M6c-25); Kassenabrechnung des Fahrers gegenüber dem Betrieb (im Pflichtenheft nicht beschrieben, Offener Punkt 7).

### WP-M6c-06 – Preiskomponenten mit Rollen und centgenauer Rabattverteilung

- **Ziel:** Jeder Reisepreis liegt als unveränderliche Menge von Preiskomponenten mit allen Rollen vor; Rabatte sind vor der Bestätigung centgenau auf Segmente verteilt und dienen später als eingefrorene Grundlage für Erstattung und Clearing.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Modul `fare-payment`; `price_components` (Segmentfahrpreis, Steuer, Zuschlag, Rabatt, Zuschuss, Trinkgeld) mit Leistendem, Rechnungsaussteller, zahlendem Schuldner, Anspruchsinhaber, Tarif-/Steuerrevision; Rabatte zusätzlich mit Finanzierer und Verteilungsschlüssel (Kap. 03 §4.1); Berechnung `customer_payable_total` = Summe kundenwirksamer Bruttokomponenten aller kaufbaren Segmente + eigene Checkoutgebühren − kundenwirksame Rabatte (Kap. 03 §4.1), getrennt vom geschätzten Gesamtaufwand der Reise einschließlich externer Tickets; Zuschuss eines Aufgabenträgers als Komponente, die einen Betreiberanspruch finanziert, ohne als Kundenzahlung aufzutreten; Verteilung per Largest Remainder mit stabiler Empfänger-ID (Kap. 03 §2.1).
  - Datenbank: `fare_component` (Komponente je Segment, Kap. 04 §4.4), Bezug auf `fare_quote`, `journey_leg`, `tariff_revision`; Rollen `performing_carrier`, `seller`, `payment_recipient`, `invoice_issuer` als getrennte Organisationsreferenzen (Kap. 03 §4.1).
  - API: `fare_components` in `JourneyOffer`/`Journey` als disjunkte additive Bestandteile (Kap. 05 §5.2); `price.pay_at_platform`, `external_known`, `external_unknown_leg_ids` unverändert aus M6a.
- **Detailverträge vorab:** JSON-Schema `price_components`; Rundungs- und Verteilungsregel mit Beispiel 100,00 EUR → 33,34 / 33,33 / 33,33; Abgrenzung zur segmentbezogenen Preisaufstellung in M6a.
- **Akzeptanzkriterien:**
  1. Summe aller kundenwirksamen Komponenten = `fare_quote.pay_at_platform_minor`; Informationssegmente sind nicht Teil des einzuziehenden Betrags.
  2. Ein Verbundrabatt auf zwei Segmente ist beim Checkout centgenau verteilt; die Summe der Anteile entspricht exakt dem Rabatt (COST-10).
  3. Steuer wird nicht zusätzlich auf brutto ausgewiesene Komponenten addiert; Kundenzahlung und B2B-Provision sind getrennte Kontexte.
  4. Nach Bestätigung sind Komponenten unveränderlich; Korrektur nur durch Gegenkomponente mit Bezug auf das Original.
  5. Ein nur auskunftsfähiges Segment (Linienbus ohne Buchungs-API) erzeugt keine kundenwirksame Komponente; sein bekannter Preis steht ausschließlich unter `external_known` und geht nicht in `customer_payable_total` ein (PH-AT-006, E2E-087 – Preisteil).
  6. Eine Zuschusskomponente erhöht den Betreiberanspruch, nicht den vom Fahrgast zu zahlenden Betrag.
- **Tests:** positiv: Rechenbeispiel A reproduziert 32,10 EUR mit vier Komponenten. negativ: Summe ≠ Quote blockiert das Angebot; Rollenreferenz auf fremden Mandanten wird abgewiesen; Informationssegment mit Preis darf den Zahlbetrag nicht verändern. konkurrierend: zwei parallele Preisbildungen desselben Angebots liefern identische Komponenten (deterministische Verteilung). Referenztests: Beitrag zu PH-AT-005 (eingefrorene Preispositionen 25,00 EUR), PH-AT-006, E2E-087; COST-10.
- **Abhängigkeiten:**
  - intern: WP-M6c-02
  - extern: WP-M2-08, WP-M2-09 (Tarifengine, unveränderlicher `fare_quote`)
  - extern: WP-M4-35 (centgenaue Umlage (Largest Remainder) als wiederverwendbarer Baustein)
  - extern: WP-M6a-23 (Segmentbezogene Preisaufstellung mit Tarifgeber)
  - extern: WP-M6b-12 (Rechtsträgerrollen je Segment)
- **Entscheidungen:** D-01, D-03 (Rollen und Verkäufermodell) – blockieren nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Gutschein-, Guthaben- und Berechtigungslogik selbst (WP-M6c-09 bis WP-M6c-11); Provision (WP-M6c-19).

### WP-M6c-07 – Mehrparteien-Checkout: Autorisierung, Capture-Regel, Void

- **Ziel:** Eine Reise mit Segmenten mehrerer Betreiber wird mit einer Kundenzahlung autorisiert, nach bestätigten Segmenten gemäß freigegebener Zahlungsregel erfasst und den Betreiberansprüchen zugeordnet; unbestimmte PSP-Ergebnisse bleiben unbestimmt.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Modul `fare-payment`; Erweiterung des Zahlungsautomaten aus M2 um Mehrparteienzuordnung (Zahlungsempfänger je Segment), Capture nach Segmentbestätigung (Kap. 03 §4.2), `CAPTURE_UNKNOWN → RECONCILING`, `VOID_PENDING → VOIDED`; Zahlungsschritte als Saga-Schritte mit stabiler PSP-Kommandoreferenz (`saga_step.partner_command_ref`).
  - API: `/v1/federation/journeys/{journey_id}/confirm` mit exakt akzeptiertem Maximalbetrag, Abweichung → `409 REQUOTE_REQUIRED`; `ACTION_REQUIRED` bei SCA/3DS; `/v1/operations/{operation_id}` zeigt Zahlungsfortschritt; `/v1/payments/provider-events/{provider}` für Transfer-/Payout-Ereignisse.
  - Datenbank: `payment_intent`, `payment_event`, `payment_order`, `saga_step`, `operation`; Invariante „Bestätigung/PSP-Retry exakt gleiche Operation“ (Kap. 04 §4.5).
  - Ereignisse/Outbox: Zahlung autorisiert/erfasst/aufgehoben/in Klärung; daraus Betriebsnachricht an den Fahrgast über `/v1/notifications` (aufgehobene Autorisierung, Zahlung in Klärung), Wortlaut nach Reise-Policy aus M6a.
  - Oberfläche: Zahlungsklärfälle (`RECONCILING`, fehlende Statusfähigkeit des PSP) erscheinen in UI-08 (Reise-/Saga-Klärung) mit Betrag, PSP-Referenz und Alter; die Maske selbst liefert M6a, dieses WP liefert die Zahlungsdaten und den Klärfalltyp.
  - Adapter/Worker: PSP-Adapter um Split/Transfer und Statusabfrage erweitert; PSP-Aufrufe nie innerhalb offener SQL-Transaktionen.
  - Konfigurationsparameter: `partner_settlement_model` (bestimmt Anspruchstatbestand und Capture-Zeitpunkt je Vertrag).
- **Detailverträge vorab:** Reihenfolge Zahlung ↔ Segmentbestätigung je Zahlungsmodell (Favorit D-03: ein Checkout mit PSP-Mehrparteienlösung; Fallback getrennte Charges); Kredit-/Zahlungspolicy für „Reise bestätigt, Zahlung in Klärung“; Verhalten bei abgelaufenem Hold während SCA (Planungsdefault Hold 180 s aus dem Pflichtenheft als solcher gekennzeichnet, soweit von M6a übernommen).
- **Akzeptanzkriterien:**
  1. Zwei bestätigte Segmente, eine Autorisierung über den exakt freigegebenen Gesamtbetrag, Capture erst nach beiden Segmentbestätigungen; eine Autorisierung gilt nicht als Einnahme (PH-AT-005).
  2. Lehnt Segment B definitiv ab, wird die Autorisierung aufgehoben; ist der Capture bereits erfolgt, entsteht genau eine passende Erstattung (PH-AT-008).
  3. Läuft ein Hold während der SCA ab, wird nicht gegen abgelaufene Kapazität bestätigt; eine erfolgreiche Zahlung rekonstruiert keine Kapazität (PH-AT-010).
  4. PSP-Timeout ergibt `UNKNOWN`/`RECONCILING`; erst die Statusabfrage nach PSP-Referenz entscheidet; kein zweiter Charge auf Verdacht.
  5. Eine doppelte SCA-Rückkehr startet keine neue Autorisierung; ein gescheiterter Capture storniert nicht still eine bereits erbrachte Fahrt.
  6. Workerabsturz zwischen PSP-Erfolg und lokalem Commit führt nach Lease-Ende zu genau einer Zahlungswirkung (PH-AT-009).
  7. Für ein nur auskunftsfähiges Segment entsteht keine PSP-Belastung und kein Zahlungsempfänger (PH-AT-006).
  8. Bleibt das Partnerergebnis eines Segments unbekannt (Antwort verloren), wird weder eine zweite Autorisierung gestartet noch auf Verdacht erfasst oder aufgehoben; die Zahlung folgt erst dem geklärten Segmentstatus (PH-AT-007 – Zahlungsteil).
  9. Fehlt dem PSP die Statusabfrage, entsteht ein manueller Klärfall statt einer Wiederholung (Kap. 03 §4.2).
  10. Jeder Zahlungsübergang ist mit Akteur bzw. Systemursprung, PSP-Referenz und Korrelations-ID nachvollziehbar; Logs enthalten keine Zahlungsinstrumentdaten.
- **Tests:** positiv: PH-AT-005 im PSP-Simulator. negativ: Betragsabweichung, abgelaufener Hold, SCA länger als Hold (Kap. 05 §5.8). konkurrierend: zwei verschiedene Idempotenzschlüssel auf derselben Journey-Revision erzeugen eine Zahlung; 100 identische Webhooks erzeugen einen Zustandsübergang (Kap. 05 §5.8); Fencing bei zwei Workern. Referenztests: ermöglicht PH-AT-005, PH-AT-008, PH-AT-009, PH-AT-010, E2E-089, E2E-090; Beitrag (Zahlungsteil) zu PH-AT-006, PH-AT-007, E2E-086; Regression AT-15.
- **Abhängigkeiten:**
  - intern: WP-M6c-03, WP-M6c-04, WP-M6c-06
  - extern: WP-M1-15, WP-M1-14, WP-M1-13 (`operation` mit Lease/Fencing, Outbox/Inbox, Idempotenzregister)
  - extern: WP-M2-29, WP-M2-30, WP-M2-31 (PSP-Adapter, Zahlungsautomat, Void und Erstattung)
  - extern: WP-M2-32 (Betriebsnachrichten mit Zustellversuchen)
  - extern: WP-M6a-13, WP-M6a-14, WP-M6a-15, WP-M6a-16 (Saga Hold/Confirm/Kompensation, Reconciliation-Worker, UI-08 Reise-/Saga-Klärung, Kundennachricht nach Reise-Policy)
- **Entscheidungen:** D-03 (Zahlungsmodell, Capturezeitpunkt, PSP) und D-01 (Verkäuferrolle) – Bau mit Favorit in Sandbox; blockieren Produktivschaltung. D-04 (Teilkauf) – Standard aus, blockiert nur Produktivschaltung eines Teilkaufs.
- **Größe:** L
- **Nicht im Umfang:** Auskehrung an Betreiber (WP-M6c-22); Erstattungsregeln (WP-M6c-14); Oberfläche (WP-M6c-08).

### WP-M6c-08 – UI-03 Mehrparteien-Checkout: Zahlungsauswahl und Rechnungsausweis

- **Ziel:** Der Fahrgast sieht im Checkout je Segment Betreiber, Preis und Stornoregel, den heute zahlbaren Betrag, Anbieter und Rechnungsaussteller, die zulässigen Zahlungsarten sowie eingesetzte Gutscheine/Guthaben und stimmt dem ausdrücklich zu.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Oberfläche: UI-03 gemäß Kap. 06 §6.3 – Segmentzeilen mit Preis und Stornoregel, „Heute zahlbar“, „Anbieter und Rechnungsaussteller“, „Vermittlung … gemeinsame Zahlung … ggf. zwei Rechnungen“, PSP-Zahlungsauswahl, Pflichtzustimmung getrennt von Marketingeinwilligung, Countdown der gemeinsamen Restfrist; Zustände Holdverlust, SCA, Preisänderung, Doppelklick (Kap. 06 §6.9). UI-02 zeigt den Betreiberausweis je Segment. UI-06 (Callcenter) nutzt dieselbe API und dokumentiert die Zustimmung.
  - Oberfläche: UI-04 zeigt den Zahlungsstand getrennt vom Reisestand („autorisiert, noch nicht eingezogen“, „Zahlung in Klärung“, Kap. 06 §6.4) samt Hinweis, keine zweite Buchung auszulösen.
  - Backend: Die abgegebene Zustimmung wird mit Zeitpunkt, Akteur (Fahrgast oder Callcenter-Agent), Angebots-/Quote-Revision und Inhalt (Leistungsanbieter, Preisaufteilung, Umstiegsrisiko, Storno je Segment, ggf. mehrere Rechnungen; Kap. 03 §4.1) unveränderlich an der Reise gespeichert.
  - Oberfläche: Eingabe von Gutscheincode, Guthabeneinsatz und Berechtigung als Platzhalter-Slots, die von WP-M6c-09 bis WP-M6c-11 gefüllt werden.
  - API: nutzt `/v1/federation/offers/{offer_id}/hold`, `/v1/federation/journeys/{journey_id}/confirm`, `/v1/operations/{operation_id}`, `/v1/federation/journeys/{journey_id}`.
- **Detailverträge vorab:** Texte je Zustand (u. a. „Bestätigung wird geprüft“); Zuordnung API-Feld → UI-Element; Verhalten nach Verbindungsabbruch (dieselbe Operation, kein neuer Checkout).
- **Akzeptanzkriterien:**
  1. Der Kaufbutton nennt exakt `pay_at_platform`; bei unbepreisten externen Segmenten wird kein Gesamtpreis behauptet.
  2. Während laufender Operation ist der Button gesperrt; nach Verbindungsabbruch öffnet dieselbe Operation.
  3. Ein Timeout zeigt weder Erfolg noch Ablehnung, sondern den Klärzustand mit Betrag und Kontaktmöglichkeit.
  4. Der Ablauf ist vollständig per Tastatur und Screenreader bedienbar (UI-Abnahme Kap. 06 §6.9: Fahrt mit zwei Betreibern).
  5. Ohne gesetzte Pflichtzustimmung ist kein Confirm möglich (auch nicht per direktem API-Aufruf); die Zustimmung ist nie mit einer Marketingeinwilligung gekoppelt und nachträglich mit ihrem Inhalt nachweisbar.
  6. Externe Ticketabschnitte stehen vor dem Kaufbutton und verlangen die ausdrückliche Bestätigung, dass sie separat zu beschaffen sind (PH-AT-006, E2E-087 – Darstellungsteil).
  7. Teilkauf ist standardmäßig aus; ist er vertraglich freigegeben, erscheint er als eigene, bezifferte Wahl vor dem Commit (D-04).
  8. Bucht ein Callcenter-Agent über dieselbe API, wird die im Gespräch abgegebene Zustimmung mit Agent und Zeitpunkt dokumentiert; ohne Vertretungsumfang, Zustimmung oder Kontaktkanal ist der Kauf serverseitig gesperrt (Zustände UI-06, Kap. 06 §6.9; Maske in M7).
  9. Der Countdown zeigt die gemeinsame Restfrist, nicht die längste Partnerfrist.
- **Tests:** positiv: Zwei-Betreiber-Checkout Ende zu Ende. negativ: Preisänderung zwischen Hold und Confirm zeigt neue Quote; Holdverlust während SCA; Confirm ohne Zustimmung; Callcenter ohne Vertretungsumfang. konkurrierend: Doppelklick und zwei Tabs erzeugen eine Operation.
- **Abhängigkeiten:**
  - intern: WP-M6c-05, WP-M6c-07
  - extern: WP-M2-42, WP-M2-43, WP-M2-35 (UI-03 Checkout (Einbetreiber), UI-04 und Passenger-PWA)
  - extern: WP-M7b-35, WP-M7b-17, WP-M7b-19 (UI-06 Callcenter mit Vertretungsprüfung – die Callcenter-Kriterien werden API-seitig in M6c geprüft (Akteur Agent, dokumentierte Zustimmung), die Maske folgt in M7 (Offener Punkt 33))
  - extern: WP-M6a-11 (Angebotsdarstellung mit Betreiberausweis und Buchungsfähigkeit)
- **Entscheidungen:** D-01, D-03 – Wortlaut zu Verkäufer/Vermittler folgt dem Beschluss; blockiert nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** UI-04 Storno und Erstattungsanzeige (WP-M6c-14); Quittungsabruf (WP-M6c-16).

### WP-M6c-09 – Gutschein- und Promo-Code-Regeln

- **Ziel:** Gutscheine und Promo-Codes mit Frist, Budget, Zielgruppe und Mehrfacheinlösungsgrenze können angelegt und im Checkout eingelöst werden; ungültige oder verbrauchte Codes werden korrekt zurückgewiesen, auch unter Konkurrenz.
- **Anforderungen:** `LH-6.10-PAY-008`
- **Liefergegenstände:**
  - Backend: Modul `fare-payment`; Gutscheindefinition als versionierte Regel (Gültigkeitsintervall, Budget, Zielgruppe, Einlösungen gesamt und je Fahrgast); Einlösung als Rabattkomponente mit Finanzierer und Verteilungsschlüssel (Kap. 03 §4.1); Rückgabe der Einlösung bei gescheitertem Confirm/Void.
  - Datenbank: `voucher_redemption` („limitiertes Gutscheinbudget atomar“, Kap. 04 §4.4); Gutscheindefinition (Vorschlag); eindeutige Bedingung je Code/Fahrgast/Einlösungszähler; Budgetzeile wird beim Einlösen gesperrt.
  - API: Codeprüfung und Einlösung im Rahmen von Hold/Confirm (Pfad Vorschlag unter `/v1/vouchers`, in Kap. 05 §5.7 nicht enthalten); Verwaltung durch berechtigte Rolle; stabile Fehlercodes für abgelaufen, verbraucht, Budget erschöpft, Zielgruppe nicht erfüllt, unbekannt.
  - Oberfläche: UI-03 Codeeingabe mit Ergebnis je Fehlergrund; Verwaltungsmaske im Operator-Web (UI-17 Tarife).
  - Ereignisse/Outbox: Gutschein eingelöst, Einlösung zurückgegeben.
- **Detailverträge vorab:** Regelschema (Frist, Budget, Zielgruppe, Mehrfacheinlösung); Fehlercodeliste; Rückgaberegel bei Storno (COST-10: eingefrorene Rabattanteile, vertragliche Rabatt-Rückforderung vor Bestätigung nennen); Finanzierer je Gutschein.
- **Akzeptanzkriterien:**
  1. Ein abgelaufener, ein unbekannter, ein bereits verbrauchter Code sowie ein Code mit erschöpftem Budget oder nicht erfüllter Zielgruppe werden mit jeweils eigenem Fehlercode abgewiesen; der Preis bleibt unverändert (PAY-008).
  2. Ein gültiger Code erzeugt genau eine Rabattkomponente, centgenau auf die Segmente verteilt (COST-10).
  3. Ein doppelt parallel eingelöster Gutschein mit Einlösungsgrenze 1 wird genau einmal wirksam (AT-16).
  4. Anlage und Änderung einer Gutscheinregel erfordern die berechtigte Rolle (sonst 403), erzeugen eine neue Regelrevision und einen `audit_event`; bereits eingelöste Gutscheine behalten ihre Regelrevision.
  5. Scheitert das Confirm oder wird die Autorisierung aufgehoben, steht die Einlösung wieder zur Verfügung; ein fremder Mandant erfährt nichts über die Existenz eines Codes (404-gleiche Antwort).
  6. Teilstorno verwendet die eingefrorenen Rabattanteile ohne automatische Neubepreisung (COST-10).
- **Tests:** positiv: Einlösung mit Mehrfachgrenze 3. negativ: alle Fehlergründe aus Kriterium 1; Code eines anderen Mandanten; Regelpflege ohne Recht; massenhaftes Durchprobieren von Codes wird durch das Rate-Limit der Plattform begrenzt. konkurrierend: 100 parallele Einlösungen gegen Budget für zehn Einlösungen ergeben genau zehn. Referenztests: besteht AT-16 (Gutscheinteil).
- **Abhängigkeiten:**
  - intern: WP-M6c-02, WP-M6c-06, WP-M6c-08 (Eingabe-Slot in UI-03)
  - extern: WP-M2-07, WP-M2-21, WP-M2-23 (Tarifrevision, Angebot und Confirm)
  - extern: WP-M1-07, WP-M1-46 (Rollenmodell, Audit)
- **Entscheidungen:** D-06 (steuerliche Behandlung von Gutscheinen, Kap. 03 §8 Steuer-/Belegmodell) – blockiert nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Guthabenkonten (WP-M6c-10); Empfehlungsprämien (WP-M6c-12); Tarif-Sandbox (`LH-6.10-PAY-017`, P3).

### WP-M6c-10 – Guthaben und Pässe mit Zustandsautomat

- **Ziel:** Guthaben und Pässe führen die Zustände aktiv, gesperrt, abgelaufen und verbraucht; Einlösungen sind transaktionssicher, sodass dasselbe Guthaben nie doppelt verbraucht wird.
- **Anforderungen:** `LH-6.10-PAY-009`
- **Liefergegenstände:**
  - Backend: Modul `fare-payment`; Aggregat Guthabenkonto mit unveränderlichen Bewegungen (Aufladung, Reservierung, Verbrauch, Freigabe, Verfall, Sperre, Korrektur); Saldo als Projektion (Kap. 03 §2.2: „Kein Kontosaldo wird als beliebig editierbares Feld geführt“); Pass mit Gültigkeit und Kontingent; Reservierung beim Hold/Confirm, Verbrauch beim Capture-Äquivalent, Freigabe bei Void.
  - Datenbank: Guthabenkonto, Guthabenbewegung, Pass (Vorschläge; im Pflichtenheft ohne Tabellennamen); Zeilensperre des Kontos in stabiler Reihenfolge; CHECK Saldo ≥ 0 über Bewegungssumme in der Transaktion.
  - API: Saldo und Bewegungen des eigenen Kontos unter `/v1/me` (Unterressource, Vorschlag); Einsatz im Confirm; Sperre/Entsperrung durch berechtigte Rolle.
  - Oberfläche: UI-05 (Konto/Berechtigung) zeigt Guthaben, Pässe und Zustand; UI-03 erlaubt den Einsatz.
  - Adapter/Worker: Verfallsjob setzt Guthaben und Pässe zum Gültigkeitsende auf `abgelaufen` (Serverzeit, wiederholsicher) und gibt keine laufende Reservierung preis.
  - Ereignisse/Outbox: Guthaben reserviert/verbraucht/freigegeben/gesperrt/verfallen.
- **Detailverträge vorab:** Zustandsautomat und Transitionstabelle; Verfallsregel und Zeitzone; Reihenfolge Guthaben vor PSP-Betrag; Buchhalterische Einordnung (Verbindlichkeit) nach D-06.
- **Akzeptanzkriterien:**
  1. Zwei konkurrierende Einlösungen gegen dasselbe Guthaben verbrauchen es nicht doppelt; die Summe der Verbräuche überschreitet nie den Saldo (PAY-009).
  2. Ein gesperrtes, abgelaufenes oder verbrauchtes Guthaben bzw. ein solcher Pass ist nicht einlösbar; der Grund wird als stabiler Code geliefert (PAY-009).
  3. Bei gescheitertem Confirm wird die Reservierung vollständig freigegeben; bei Teilstorno wird der anteilige Guthabenbetrag nach eingefrorener Aufteilung zurückgebucht.
  4. Der Saldo ist jederzeit aus den Bewegungen reproduzierbar; keine Bewegung wird geändert oder gelöscht.
  5. Ein Fahrgast sieht nur das eigene Konto; ein anderer Mandant erhält 404.
  6. Alle vier Zustände sind erreichbar und in UI-05 unterscheidbar; Sperre und Entsperrung erfordern die berechtigte Rolle mit Begründung und erzeugen einen `audit_event` (PAY-009, Beschreibung).
  7. Läuft ein Guthaben während eines offenen Holds ab, gilt die im Detailvertrag festgelegte Regel einheitlich (Reservierung bleibt bis Confirm/Void gültig oder Confirm verlangt neue Quote) – nie ein teilweiser Verbrauch.
- **Tests:** positiv: Aufladung, Teilverbrauch, Restzahlung über PSP. negativ: Einlösung in jedem Nicht-aktiv-Zustand; Überziehung. konkurrierend: Verfallsjob bzw. Sperre parallel zur Einlösung – genau eine Reihenfolge gewinnt; zwei bzw. 100 parallele Einlösungen (zwei Transaktionen je Invariante, Kap. 04 §4.5). Referenztests: besteht AT-16 (Saldoteil).
- **Abhängigkeiten:**
  - intern: WP-M6c-02, WP-M6c-06, WP-M6c-08 (Einsatz in UI-03)
  - extern: WP-M1-13, WP-M1-14, WP-M1-06 (Idempotenzregister, Outbox, `/v1/me`)
  - extern: WP-M2-36, WP-M2-23 (UI-05 Konto, Confirm)
  - extern: WP-M4-33 (Kostenereignis/Journal für die Guthabenverbindlichkeit)
- **Entscheidungen:** D-03, D-06 (Führung von Kundenguthaben, steuerliche Behandlung) – blockieren nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Kauf von Guthaben/Pässen als eigenes Produkt mit Preisregeln (im Pflichtenheft nicht beschrieben, Offener Punkt 1); Kulanzgutschriften (WP-M6c-15).

### WP-M6c-11 – Tarifberechtigungen per Nachweis- oder API-Prüfung

- **Ziel:** Tarifberechtigungen für Schüler, ÖPNV-Tickets oder Unternehmen können per Nachweis oder API-Prüfung hinterlegt werden und wirken ausschließlich auf den freigegebenen Tarifbestandteil.
- **Anforderungen:** `LH-6.10-PAY-010`
- **Liefergegenstände:**
  - Backend: Modul `fare-payment`; Aggregat Tarifberechtigung (Art, Gültigkeit, Prüfweg Nachweis/API, Prüfstatus, Berechtigungsrevision); Tarifregel benennt, welcher Bestandteil durch welche Berechtigungsart beeinflusst wird; Quote und Journey speichern die aufgelöste Berechtigungsrevision (Kap. 05 §5.9).
  - API: `PlanRequest.entitlement_refs` referenziert ausschließlich serverseitig geprüfte Berechtigungen (Kap. 05 §5.9); Hinterlegung und Prüfstatus unter `/v1/me` bzw. `/v1/passengers` (Unterressource, Vorschlag); Prüfentscheidung durch berechtigte Rolle.
  - Datenbank: Tarifberechtigung (Vorschlag), Bezug auf `tariff_rule` und `fare_component`; Nachweisdokument im Objektspeicher mit Löschklasse.
  - Oberfläche: UI-05 Hinterlegung und Status; Prüfmaske im Operator-Web; UI-03 zeigt die angewandte Berechtigung am betroffenen Bestandteil.
  - Adapter/Worker: Prüfadapter-Schnittstelle für API-Prüfung (konkrete Gegenstelle offen, Offener Punkt 3).
- **Detailverträge vorab:** Zuordnung Berechtigungsart → beeinflussbarer Tarifbestandteil; Prüfstatusautomat; Aufbewahrung der Nachweise (D-11); Abgrenzung zur Prüfung externer Ticketberechtigungen in M6a.
- **Akzeptanzkriterien:**
  1. Eine geprüfte Berechtigung verändert nur den in der Tarifregel freigegebenen Bestandteil; alle übrigen Komponenten (andere Segmente, Zuschläge, Gebühren) bleiben betragsgleich (PAY-010).
  2. Eine ungeprüfte, abgelaufene oder abgelehnte Berechtigung hat keine Preiswirkung; ein frei im Request vorgegebener Rabatt wird ignoriert bzw. abgewiesen.
  3. Die angewandte Berechtigungsrevision ist am Angebot und an der Reise gespeichert; eine spätere Entziehung ändert keine bestätigte Zusage.
  4. Hinterlegung ist auf beiden Wegen möglich: Nachweis-Upload mit Prüfentscheidung einer berechtigten Rolle und API-Prüfung über den Prüfadapter; fällt die Prüf-API aus oder antwortet nicht, bleibt die Berechtigung „ungeprüft“ ohne Preiswirkung (kein stiller Rabatt) (PAY-010, Beschreibung).
  5. Das Nachweisdokument ist nur für den Fahrgast und die Prüfrolle abrufbar, durchläuft Uploadprüfung/Quarantäne und trägt eine Löschklasse; die Prüfentscheidung ist im Audit nachweisbar.
  6. Eine gültige Zeitkarte kann einen expliziten Nullpreis für das berechtigte Segment ergeben (Kap. 05 §5.2), ohne andere Segmente zu beeinflussen.
- **Tests:** positiv: Schülerberechtigung senkt nur den Segmentfahrpreis von Dienst A. negativ: Berechtigung eines anderen Fahrgasts, abgelaufene Berechtigung, manipulierte `entitlement_refs`, Timeout der Prüf-API, Nachweisabruf durch fremden Mandanten (404). konkurrierend: Entziehung parallel zum Confirm – entweder alte Quote mit gespeicherter Revision oder `409 REQUOTE_REQUIRED`, nie ein Mischzustand.
- **Abhängigkeiten:**
  - intern: WP-M6c-02, WP-M6c-06, WP-M6c-08 (Anzeige in UI-03)
  - extern: WP-M1-31, WP-M1-33, WP-M1-07 (Feldschutz, Aufbewahrungs- und Löschregeln, Rollenmodell)
  - extern: WP-M2-07, WP-M2-36 (Tarifrevision und Preisregeln, UI-05 Konto)
  - extern: WP-M4-21 (Uploadticket, Quarantäne und Malware-Prüfung für Nachweisdokumente)
  - extern: WP-M6a-24 (Prüfung externer Ticketberechtigungen (Abgrenzung und gemeinsame Schnittstelle))
- **Entscheidungen:** D-11 (Aufbewahrung der Nachweise), D-08 (verfügbare Prüf-APIs der Partner) – blockieren nur Produktivschaltung der jeweiligen Prüfart.
- **Größe:** M
- **Nicht im Umfang:** Unternehmensrechnung (WP-M6c-25); konkrete Anbindung eines Verbund- oder Schülerregisters (erst nach D-08).

### WP-M6c-12 – Freunde-werben-Freunde mit Betrugsgrenzen

- **Ziel:** Ein Empfehlungsprogramm kann je Mandant mit Betrugsgrenzen und Auszahlungssperren konfiguriert werden; Selbstempfehlung über dasselbe verifizierte Konto löst keine Prämie aus.
- **Anforderungen:** `LH-6.10-PAY-011`
- **Liefergegenstände:**
  - Backend: Modul `fare-payment`; Aggregat Empfehlung (Werber, Geworbener, Code, auslösendes Ereignis, Prämienstatus); Regelprüfung auf Selbstempfehlung, Höchstzahl je Werber/Zeitraum, Sperrfrist bis zur Gutschrift; Prämie als Guthabenbewegung.
  - Datenbank: Empfehlung (Vorschlag); eindeutige Bedingung je geworbenem Konto; Bezug auf Guthabenbewegung.
  - API: Empfehlungscode abrufen/einlösen (Pfad Vorschlag unter `/v1/me`); Programmkonfiguration als `config_revision`.
  - Oberfläche: UI-05 Empfehlungscode und Prämienstatus; Konfiguration im Konfigurationseditor.
  - Konfigurationsparameter: im Katalog `kataloge.csv` existiert kein Parameter für das Empfehlungsprogramm (Offener Punkt 4).
- **Detailverträge vorab:** Definition „dasselbe verifizierte Konto“ (verifizierter Kontakt bzw. Plattformsubjekt aus `passenger_private.subject_token`); auslösendes Ereignis der Prämie; Grenz- und Sperrwerte als Parameter mit Min/Max; Rückbuchung der Prämie bei Storno der auslösenden Fahrt.
- **Akzeptanzkriterien:**
  1. Löst ein Konto den eigenen Empfehlungscode ein oder teilen Werber und Geworbener denselben verifizierten Kontakt, entsteht keine Prämie; der Vorgang wird protokolliert (PAY-011).
  2. Oberhalb der konfigurierten Betrugsgrenze entsteht keine weitere Prämie; während der Auszahlungssperre ist die Prämie nicht einlösbar.
  3. Ein geworbenes Konto kann genau einmal prämienwirksam sein.
  4. Ist das Programm für den Mandanten deaktiviert, ist kein Code einlösbar.
  5. Betrugsgrenzen und Auszahlungssperre sind je Mandant als Konfigurationsrevision einstellbar; eine Änderung wirkt nur auf neue Empfehlungen und ist im Audit nachweisbar (PAY-011, Beschreibung).
  6. Wird die auslösende Fahrt storniert bzw. erstattet, wird die Prämie nach der festgelegten Regel als Gegenbewegung zurückgebucht, nie durch Löschen.
- **Tests:** positiv: gültige Empfehlung, Prämie nach Sperrfrist als Guthaben. negativ: Selbstempfehlung, Grenzüberschreitung, deaktiviertes Programm. konkurrierend: zwei parallele Einlösungen desselben geworbenen Kontos ergeben genau eine Empfehlung.
- **Abhängigkeiten:**
  - intern: WP-M6c-10
  - extern: WP-M1-28, WP-M1-20 (Fahrgastregistrierung mit Kontaktverifikation, Konfigurationsrevisionen)
- **Entscheidungen:** keine für den Bau; Programmbedingungen sind Geschäftsregel des Betreibers (Offener Punkt 4).
- **Größe:** M
- **Nicht im Umfang:** Auszahlung von Prämien in Geld (im Pflichtenheft nicht beschrieben); Marketingkommunikation (M7).

### WP-M6c-13 – Trinkgeld als getrennte Zahlung

- **Ziel:** Trinkgeld wird separat bestätigt, als eigene Zahlung verarbeitet und getrennt abgerechnet; der vereinbarte Fahrpreis bleibt unverändert.
- **Anforderungen:** `LH-6.10-PAY-013`
- **Liefergegenstände:**
  - Backend: Modul `fare-payment`; Trinkgeld als eigene Preiskomponente „freiwilliges Trinkgeld“ (Kap. 03 §4.1) mit eigenem Begünstigten; eigener `PaymentIntent` mit Zweck Trinkgeld; keine Änderung an `fare_quote` und an bestehenden `fare_component`-Zeilen.
  - Datenbank: `payment_intent` (Zweck), `fare_component` (Komponentenart Trinkgeld), `economic_event` für die getrennte Abrechnung.
  - API: Trinkgeld anbieten/bestätigen zur Reise (Pfad Vorschlag unter `/v1/federation/journeys/{journey_id}`; in Kap. 05 §5.7 nicht enthalten); Idempotenz über `Idempotency-Key`.
  - Oberfläche: UI-04 (Aktive/alte Reisen) bietet nach Fahrtende die getrennte Trinkgeldbestätigung; Quittung weist Trinkgeld separat aus.
  - Ereignisse/Outbox: Trinkgeld bestätigt/erfasst/erstattet.
- **Detailverträge vorab:** Zeitpunkt (vor oder nach Fahrt), Begünstigter (Fahrer oder Betreiber), steuerliche und provisionsseitige Behandlung, Erstattbarkeit – alles offen (Offener Punkt 6) und vor Bau zu entscheiden.
- **Akzeptanzkriterien:**
  1. Nach einem Trinkgeld sind `fare_quote`, Fahrpreiskomponenten, Provisionsbasis und Kundenbeleg des Fahrpreises betragsgleich wie vorher (PAY-013).
  2. Trinkgeld erfordert eine eigene ausdrückliche Bestätigung und erzeugt eine eigene Zahlung mit eigener PSP-Referenz.
  3. Trinkgeld erscheint in Abrechnung und Quittung als getrennte Position und geht nicht in den Tarifumsatz ein.
  4. Ein Storno der Reise nach Trinkgeld verändert das Trinkgeld nur über einen eigenen Erstattungsfall.
  5. Trinkgeld geht weder in die Provisionsbasis noch in eine `settlement_line` für Beförderungsentgelt ein; seine Abrechnung an den Begünstigten ist eine eigene, getrennt ausgewiesene Position (PAY-013, Beschreibung „abgerechnet“).
  6. Scheitert die Trinkgeldzahlung oder bleibt sie unbestimmt, bleiben Reise, Fahrpreiszahlung und Beleg unberührt.
- **Tests:** positiv: Fahrt 10,70 EUR, Trinkgeld 2,00 EUR, zwei getrennte Zahlungen. negativ: Trinkgeld zu fremder Reise (404), negativer Betrag, Trinkgeld vor zulässigem Zeitpunkt, PSP-Timeout der Trinkgeldzahlung. konkurrierend: doppelte Bestätigung mit gleichem Schlüssel ergibt eine Zahlung; zwei verschiedene Schlüssel folgen der festgelegten Mehrfachregel.
- **Abhängigkeiten:**
  - intern: WP-M6c-06, WP-M6c-07
  - extern: WP-M2-43, WP-M2-29 (UI-04 Buchungsdetail, PSP-Adapter)
  - extern: WP-M3-29 (Fahrtende-Ereignis des Fahrers)
- **Entscheidungen:** D-03, D-06 (Empfänger, Steuer, Abrechnung) – Bau der getrennten Zahlung möglich; Abrechnung an Fahrer blockiert bis Beschluss (nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Lohn-/Personalabrechnung des Trinkgelds (außerhalb der Plattform laut D-06-Favorit).

### WP-M6c-14 – Stornierungs- und Erstattungsregeln, Erstattungsfall und Refund-Obergrenze

- **Ziel:** Stornierungs- und Erstattungsregeln berücksichtigen Zeitpunkt, Grund und Zahlungslage; Betreiber- und Fahrgaststorno erzeugen jeweils den vorgesehenen Erstattungsfall, und die Summe aller Erstattungen überschreitet nie den erstattungsfähigen Betrag.
- **Anforderungen:** `LH-6.10-PAY-006`
- **Liefergegenstände:**
  - Backend: Modul `fare-payment`; versionierte Regelmatrix (Zeitpunkt × Grund × Zahlungslage → Gebühr/Erstattung je Segment); Aggregat `RefundCase` mit eigenem Zustandsautomaten; Zahlungslage-Zweig: nur autorisiert → Void, erfasst → Refund, Guthaben/Gutschein → Rückbuchung nach eingefrorener Aufteilung, Bar → Erstattungsweg laut Regel; Refund-Obergrenze je Zahlung unter Zeilensperre.
  - Datenbank: `refund_case` (Kap. 04 §4.4), `payment_intent` (CHECK `refunded_minor<=captured_minor`), `payment_order` mit eindeutiger PSP-Kommandoreferenz, `fare_component` (Gegenkomponenten).
  - API: `/v1/federation/journeys/{journey_id}/cancellation-quotes` liefert Gebühr/Erstattung je Segment mit unverändertem Regelstand; `/v1/federation/journeys/{journey_id}/cancel` referenziert genau diese Quote, bei geänderter Regel `409 REQUOTE_REQUIRED`; `/v1/bookings` (Einbetreiberstorno) nutzt dieselbe Regelmatrix; Erstattungsfälle lesbar (Pfad Vorschlag).
  - Oberfläche: UI-04 zweistufiges Storno mit Gebühr, erwarteter Erstattung und Reiseauswirkung; „Erstattung beauftragt“ bis zur PSP-Bestätigung, erst danach „erstattet“ (Kap. 06 §6.4).
  - Oberfläche: UI-04 zeigt bei fehlgeschlagenem Partnerstorno die verbleibende Verpflichtung und den Supportfall; Storno durch einen Callcenter-Agenten nutzt dieselbe API mit dokumentierter Zustimmung (Maske UI-06 in M7); Betreiberstorno wird in der Leitstelle (UI-07 aus M3 bzw. UI-08 aus M6a) mit Pflichtgrund ausgelöst.
  - Ereignisse/Outbox: Betriebsnachricht an den Fahrgast über `/v1/notifications` bei Storno durch den Betreiber, „Erstattung beauftragt“ und „erstattet“.
  - Ereignisse/Outbox: Erstattungsfall eröffnet/beauftragt/bestätigt/fehlgeschlagen; Korrekturbeleg referenziert die Originalposition.
  - Konfigurationsparameter: `partner_liability_policy` (Haftungs-/Stornoregel je Vertrag, „verbindlich für existierende Buchungen“); für betreibereigene Stornofristen existiert kein Katalogparameter (Offener Punkt 8).
- **Detailverträge vorab:** Regelschema und Präzedenz zwischen betreibereigener Regel und `partner_liability_policy`; Grundcodeliste (Fahrgaststorno, Betreiberstorno, Fahrzeugausfall, Sicherheitsausfall, No-Show); Zustandsautomat `refund_case`; Verhalten bei gescheiterter Partner-Rückholung (Kap. 03 §4.2 letzter Punkt).
- **Akzeptanzkriterien:**
  1. Ein Fahrgaststorno innerhalb und außerhalb der Frist sowie ein Betreiberstorno erzeugen jeweils den in der Regelmatrix vorgesehenen Erstattungsfall mit korrektem Betrag und Grund (PAY-006).
  2. Die Zahlungslage bestimmt den Weg: nur autorisiert → Aufhebung ohne Erstattungsbuchung; erfasst → Erstattung; teilweise mit Guthaben bezahlt → anteilige Rückbuchung (PAY-006).
  3. Parallele Erstattungen überschreiten nicht den erstattungsfähigen Betrag; ein zweiter identischer PSP-Webhook ändert weder Anspruch noch Geldsumme (PH-AT-014).
  4. Teilstorno von B erstattet exakt den Quote-Betrag, A bleibt gültig; die ursprüngliche Aufteilung bleibt erhalten, Korrektur über Gegenpositionen (Rechenbeispiel A: 21,40 EUR; PH-AT-014: 12,00 EUR bei 3,00 EUR Stornoentgelt).
  5. Ein Erstattungsauftrag gilt erst mit bestätigtem PSP-Ergebnis als bezahlt; eine fehlgeschlagene Partner-Rückholung löscht den Kundenerstattungsanspruch nicht.
  6. Ein bereits geschlossener Finanzmonat wird nicht neu geschrieben; die Korrektur bucht in die nächste offene Periode.
  7. Das Stornoangebot nennt vor der Bestätigung Gebühr, erwartete Erstattung je Zahlungsmittel (PSP, Guthaben, Gutschein) und eine eventuelle vertragliche Rabatt-Rückforderung; es findet keine automatische Neubepreisung der verbleibenden Segmente statt (COST-10).
  8. Ist ein bereits bestätigtes Segment vertraglich nicht stornierbar, entsteht ein expliziter Klärfall mit ausgewiesener finanzieller Exposition gemäß `partner_liability_policy`; es wird keine Erfolgsmeldung und keine Erstattung auf Verdacht erzeugt (PH-AT-008, Variante nicht stornierbar).
  9. Jeder Erstattungsfall trägt Grundcode, auslösenden Akteur (Fahrgast, Betreiber, System), Regelrevision und Zeitpunkt und ist im Audit nachweisbar; der Fahrgast wird über Betreiberstorno und Erstattungsstand benachrichtigt.
  10. Der Erstattungsbetrag je Zahlung ist nie größer als der erfasste Betrag abzüglich bereits erstatteter Beträge, auch nicht über Kulanz- oder Trinkgeldfälle hinweg.
- **Tests:** positiv: Regelmatrix-Tabelle als parametrisierter Test; AT-07 (Storno nach Disposition, Erstattung konsistent). negativ: Cancel mit abgelaufener Quote, mit geänderter Regel, mit fremder Journey; Storno eines nicht stornierbaren Segments; Erstattung über dem erfassten Betrag. konkurrierend: zwei parallele Erstattungsfälle gegen dieselbe Zahlung; Workerabsturz nach externem Refund vor lokalem Commit (PH-AT-009). Referenztests: besteht mit M6a PH-AT-014, PH-AT-008 (Variante Capture erfolgt); COST-10.
- **Abhängigkeiten:**
  - intern: WP-M6c-02, WP-M6c-06, WP-M6c-07, WP-M6c-09 (Rückgabe der Gutscheineinlösung), WP-M6c-10 (Rückbuchung von Guthaben)
  - extern: WP-M2-26, WP-M2-31, WP-M2-46 (Storno mit Fristen und Gründen, Void und Erstattung nach Storno, UI-04 Selbststorno)
  - extern: WP-M3-39, WP-M3-53, WP-M3-54, WP-M3-29 (Betreiberstorno aus der Leitstelle, Fahrzeugausfall und No-Show als Auslöser (AT-08, AT-09))
  - extern: WP-M4-36 (Fiskalperiode, Periodenabschluss und Gegenereignis)
  - extern: WP-M6a-22 (Storno, Teilstorno und teilweise gefahrene Reisen (Stornoangebot und Cancel-Saga))
- **Entscheidungen:** D-03 (Refund-/Chargeback-Träger, Rückerstattungsreserve), D-04 (Kostenträger nicht stornierbarer Segmente) – Bau mit Testregeln; blockieren Produktivschaltung der Regelmatrix.
- **Größe:** L
- **Nicht im Umfang:** Provisionskorrektur und Transferreversal gegenüber dem Betreiber (WP-M6c-20, WP-M6c-22); Kulanz (WP-M6c-15); Chargeback-Prozess (Offener Punkt 25).

### WP-M6c-15 – Kulanzgutschriften mit Rollenprüfung und Begründung

- **Ziel:** Kulanzgutschriften können nur von einer berechtigten Rolle mit dokumentierter Begründung vergeben werden und sind im Audit-Trail nachweisbar.
- **Anforderungen:** `LH-6.10-PAY-015`
- **Liefergegenstände:**
  - Backend: Modul `fare-payment`; Befehl „Kulanzgutschrift“ mit Pflichtfeldern Begründung, Bezug (Reise/Fall), Betrag, Form (Guthabenbewegung oder Erstattungsfall); Rechteprüfung auf Funktionsscope.
  - Datenbank: Guthabenbewegung bzw. `refund_case` mit Art Kulanz; `audit_event` (Akteur, Aktion, Objekt, Zeitpunkt, maskierte Änderung) in derselben Transaktion.
  - API: Kulanzbefehl (Pfad Vorschlag); fehlender Scope → 403, fehlende Begründung → 422.
  - Oberfläche: UI-20 (Datenschutz/Audit/Support, Basis aus M1) – Dialog mit Pflichtbegründung; derselbe Befehl steht der Callcenter-Maske UI-06 (M7) per API bereit; Anzeige der Gutschrift in UI-04/UI-05 des Fahrgasts.
- **Detailverträge vorab:** berechtigte Rolle(n) und Betragsgrenzen (im Pflichtenheft nicht benannt, Offener Punkt 10); Vier-Augen-Schwelle falls gewünscht; Form der Gutschrift.
- **Akzeptanzkriterien:**
  1. Jede manuelle Gutschrift erzeugt genau einen unveränderlichen `audit_event` mit Akteur, Begründung, Betrag und Bezug (PAY-015).
  2. Ohne berechtigte Rolle wird der Befehl mit 403 abgewiesen; ohne Begründung mit 422; es entsteht keine Gutschrift (PAY-015).
  3. Wiederholung mit gleichem Idempotenzschlüssel erzeugt keine zweite Gutschrift.
  4. Der Audit-Eintrag entsteht in derselben Transaktion wie die Gutschrift; scheitert einer von beiden, entsteht keiner (kein Geld ohne Nachweis). UPDATE/DELETE auf dem Eintrag wird abgewiesen.
  5. Die Gutschrift verändert weder den ursprünglichen Fahrpreis noch Partneransprüche; sie ist eine eigene Position mit eigenem Finanzierer.
- **Tests:** positiv: Gutschrift durch berechtigte Rolle. negativ: Rolle ohne Scope, leere Begründung, Reise eines anderen Mandanten. konkurrierend: doppelter Klick/zwei parallele Requests ergeben eine Gutschrift.
- **Abhängigkeiten:**
  - intern: WP-M6c-10, WP-M6c-14
  - extern: WP-M1-07, WP-M1-46, WP-M1-12, WP-M1-13 (Rollenmodell und Rechtematrix, Audit append-only, Idempotenzregister, UI-20 Basis)
- **Entscheidungen:** keine für den Bau; Betragsgrenzen und Rollen legt der Betreiber fest (Offener Punkt 10).
- **Größe:** S
- **Nicht im Umfang:** Supportfallverwaltung (M7); Kulanz gegenüber Partnern im Clearing (WP-M6c-28).

### WP-M6c-16 – Quittungen und Belege je Zahlungsmodell

- **Ziel:** Nach erfolgreicher Zahlung ist über die Benutzerkanäle ein passender Beleg abrufbar, der je Zahlungsmodell die erforderlichen Angaben enthält und keine fremden oder überflüssigen Daten offenlegt.
- **Anforderungen:** `LH-6.10-PAY-007`
- **Liefergegenstände:**
  - Backend: Modul `fare-payment`; Quittungserzeugung nach `CAPTURED` (bzw. bei Barzahlung nach Fahrerquittierung) aus den eingefrorenen `fare_component`-Zeilen; gemeinsame Übersicht plus Einzelbelege je tatsächlichem Aussteller (Favorit Kap. 03 §8 Steuer-/Belegmodell); Korrekturbeleg bei Erstattung mit Bezug auf das Original.
  - Datenbank: Quittung (Vorschlag) mit Inhaltshash und Objektspeicherreferenz; Rechnungs-/Gutschriftenposition (Kap. 03 §2: Aussteller und Empfänger als juristische Einheiten, Leistungsart, Leistungszeitpunkt, Steuerregelrevision, Anspruchsbezug).
  - API: Quittungen einer Reise abrufen (Pfad Vorschlag unter `/v1/federation/journeys/{journey_id}` bzw. `/v1/bookings`); Download nur für Reiseinhaber oder gültige Vertretung.
  - Oberfläche: UI-04 Belegabruf je Reise; Versand über `/v1/notifications` (E-Mail) als Betriebsnachricht.
  - Ereignisse/Outbox: Quittung erstellt; Korrekturbeleg erstellt.
- **Detailverträge vorab:** Pflichtangaben je Zahlungsmodell (im Pflichtenheft nicht aufgezählt, Offener Punkt 9); Vorlage je Aussteller; Maskierung von Zahlungsmittelangaben; Nummernkreis je Rechtsträger.
- **Akzeptanzkriterien:**
  1. Eine erfolgreiche Zahlung erzeugt genau einen passenden Beleg je Aussteller plus die gemeinsame Übersicht; Summen entsprechen dem erfassten Betrag (PAY-007).
  2. Der Beleg enthält keine vollständigen Zahlungsinstrumentdaten, keine Daten anderer Fahrgäste, keine Provisions- oder Partnerkonditionen und keine Unterstützungsmerkmale (PAY-007).
  3. Der Abruf einer fremden Quittung liefert 404; der Download-Link ist nicht erratbar und an die Sitzung gebunden.
  4. Zwei Betreiber, eine Zahlung: Kundenbeleg stimmt mit Segmentumsätzen überein (E2E-093, Kundenbelegteil).
  5. Eine Erstattung erzeugt einen Korrekturbeleg; der Originalbeleg bleibt unverändert.
  6. Der Beleg ist über die Benutzerkanäle des Fahrgasts abrufbar: in UI-04 der Passenger-PWA und über die Betriebsnachricht nach Zahlung; der erneute Versand an den verifizierten Kontakt ist per API auslösbar (Callcenter-Maske UI-06 in M7) (PAY-007, Beschreibung). Betriebsnachricht und Push enthalten selbst keine Beleginhalte.
  7. Je Zahlungsmodell (Online, Guthaben/Gutschein, Bar beim Fahrer, Rechnung, Trinkgeld) wird die im Detailvertrag festgelegte Pflichtangabenliste vollständig ausgewiesen; fehlt eine Pflichtangabe (z. B. Aussteller oder Steuerregel), wird kein Beleg erzeugt, sondern ein Klärfall (PAY-007, Beschreibung).
  8. Der Beleg ist barrierefrei lesbar (strukturierte HTML-Ansicht bzw. zugängliches PDF nach Festlegung im Detailvertrag, Kap. 06 §6.1).
- **Tests:** positiv: Beleg für Online-, Guthaben-, Bar- und Mischzahlung. negativ: Abruf durch anderen Fahrgast, durch anderen Mandanten, nach Entzug der Vertretung. konkurrierend: doppelter Capture-Webhook erzeugt keinen zweiten Beleg.
- **Abhängigkeiten:**
  - intern: WP-M6c-05 (Beleg nach Fahrerquittierung), WP-M6c-06, WP-M6c-07, WP-M6c-13 (Trinkgeld als getrennte Position), WP-M6c-14
  - extern: WP-M1-31, WP-M1-37, WP-M1-08, WP-M1-11 (Maskierung, Objektberechtigung, mandantengetrennter Objektspeicher)
  - extern: WP-M2-43, WP-M2-32 (UI-04 Buchungsdetail, Nachrichtenversand)
- **Entscheidungen:** D-06 (Steuer-/Belegmodell, Pflichtangaben) und D-01 (Rechnungsaussteller) – Bau mit Testvorlagen; blockieren produktive Faktura.
- **Größe:** M
- **Nicht im Umfang:** Unternehmens-Monatsrechnung (WP-M6c-25); Partnerrechnungen (WP-M6c-21); E-Rechnungsprofile (M8).

### WP-M6c-17 – Kooperationsvertrag: Parteien, Gebiet, Servicezeiten, Leistungsarten

- **Ziel:** Ein Partnervertrag verknüpft zwei rechtlich eigenständige Firmen und verwaltet Parteien, Genehmigungsgebiet, Servicezeiten, Leistungsarten, Ansprechpartner und die daraus folgenden Rechte als unveränderliche Vertragsversion.
- **Anforderungen:** `LH-I.9-COM-001`
- **Liefergegenstände:**
  - Backend: Modul `journey-federation` (führendes Aggregat `CooperationGrant`/Vertrag, Kap. 01 §1.3) mit Fachoperationen für Vertragsversionen; Lebenszyklus `DRAFT → REVIEWED → ACTIVE → PAUSED/EXPIRED/REVOKED`; `ACTIVE` nur mit beidseitig genehmigter Fassung, technischer Capability-Prüfung und Rechtsfreigabe; Daten-, Auskunfts-, Buchungs-, Zahlungs- und Clearingrechte getrennt aktivierbar (Kap. 02 §5).
  - Datenbank: `partner_contract_version` (`partner_ref`, `version`, `terms_hash`, `terms`, `valid_from`, `valid_to`; unveränderlich durch Trigger `contract_immutable`), Vertragsstatus (getrennte Tabelle aus WP-M6c-02), `cooperation_grant`, `legal_entity`, `organization`; Invarianten „Gegenereignis statt Änderung“ und „Grantwiderruf sofort wirksam“ (Kap. 04 §4.5).
  - API: Vertragsverwaltung (Pfad Vorschlag; in Kap. 05 §5.7 nicht enthalten) mit ETag/`If-Match`; neue Bedingungen nur als neue Version.
  - Oberfläche: UI-18 (Kooperation/Rollen): Partner, Rechtsträgerrollen, Vertragsversion, Frist, Nachweise, Genehmigungsgebiet, Servicezeiten, Leistungsarten, Ansprechpartner; Capability-Teststatus getrennt vom Vertragsstatus (Kap. 06 §6.8); Zustände Vertragsablauf, offene Reisen, Scopekonflikt.
  - Konfigurationsparameter: `sharing_visibility_level`, `sharing_service_ids`, `interoperator_bookings`, `partner_transport_modes`, `partner_settlement_model`, `partner_liability_policy` als Vertragsinhalt bzw. Kooperationsebene.
  - Ereignisse/Outbox: Vertrag aktiviert/pausiert/abgelaufen/widerrufen.
- **Detailverträge vorab:** JSON-Schema `terms`; Zustandsautomat mit Freigaberollen beider Parteien; Zuständigkeitsschnitt mit M6b (Grants) – wer führt Tabelle und UI-18-Teilbereiche (Offener Punkt 15).
- **Akzeptanzkriterien:**
  1. Ein gültiger Kooperationsvertrag verknüpft zwei verschiedene `legal_entity` aus zwei Mandanten samt den daraus abgeleiteten Rechten; jede Seite sieht ihre eigene Vertragsprojektion (COM-001).
  2. Parteien, Genehmigungsgebiet, Servicezeiten, Leistungsarten und Ansprechpartner sind Pflichtinhalt; ein Vertrag ohne diese Angaben erreicht nicht `ACTIVE` (COM-001).
  3. Eine Änderung wirtschaftlicher Bedingungen erzeugt eine neue Version mit neuem `terms_hash`; die alte bleibt unverändert und bestehenden Segmenten zugeordnet.
  4. Aktivierung erfordert die Genehmigung beider Parteien durch verschiedene Personen; eine einseitige Aktivierung wird abgewiesen.
  5. Ein Dritter (Mandant C) kann weder Vertrag noch dessen Existenz erkennen (404).
  6. Daten-, Auskunfts-, Buchungs-, Zahlungs- und Clearingrechte sind je Vertrag getrennt aktivierbar; ein Vertrag mit Auskunfts-, aber ohne Buchungsrecht erlaubt keine Fremdbuchung, einer ohne Clearingrecht keine Statementbildung (Kap. 02 §5).
  7. Jeder Statuswechsel des Vertrags ist mit Akteur, Zeitpunkt und Begründung im Audit nachweisbar; Ansprechpartner sind als personenbezogene Daten nur der Vertragsgegenseite und der Vertragsverwaltungsrolle sichtbar.
- **Tests:** positiv: Vertrag A↔B anlegen, beidseitig genehmigen, aktivieren. negativ: Aktivierung ohne Capability-Nachweis oder Rechtsfreigabe; Zugriff durch Mandant C; UPDATE auf `partner_contract_version`. konkurrierend: zwei parallele Aktivierungen bzw. zwei neue Versionen ergeben genau eine wirksame Version je Zeitpunkt (Invariante „Nicht überlappende aktive … Version“). Referenztests: Beitrag zu PH-AT-002, E2E-085.
- **Abhängigkeiten:**
  - intern: WP-M6c-01, WP-M6c-02
  - extern: WP-M1-05, WP-M1-22, WP-M1-46 (Mandant, Rechtsträger, Organisation; Vier-Augen-Freigabe; Audit)
  - extern: WP-M6a-04, WP-M6a-05 (Vertragsgate und Kooperationsfilter, Capability Registry)
  - extern: WP-M6b-05, WP-M6b-06, WP-M6b-07, WP-M6b-08 (Grants, Pause und Widerruf, UI-18 Grantverwaltung)
- **Entscheidungen:** D-01 (Rechtsträgerliste mit Rolle je Dienst), D-08 (Partner, Buchungsrechte) – Bau in Sandbox; blockieren produktive Verträge.
- **Größe:** M
- **Nicht im Umfang:** Grant-Durchsetzung und Projektionen (M6b); Clearingregeln im Detail (WP-M6c-19); Ablauf (WP-M6c-18).

### WP-M6c-18 – Vertragsablauf und dokumentierte Auslaufregel

- **Ziel:** Läuft ein Service- oder Datenvertrag aus, wird die Neuanlage externer Reisen unterbunden, während bereits laufende Reiseketten nach der im Vertrag dokumentierten Auslaufregel mit einem eng begrenzten Restabwicklungsrecht abgewickelt werden.
- **Anforderungen:** `LH-I.9-COM-011`
- **Liefergegenstände:**
  - Backend: Modul `journey-federation`; Ablaufjob setzt `EXPIRED` zur Serverzeit bei halboffenem Intervall `[valid_from, valid_until)`; Ausstellung eines scopebegrenzten Restabwicklungsgrants (nur bekannte Journey-/Segment-IDs, Status, Storno, notwendige Kommunikation; Kap. 02 §5); Finanzfolge: zulässige Abrechnung, Erstattung und Nachweisaufbewahrung bleiben möglich.
  - Datenbank: `partner_contract_version.valid_to`, Vertragsstatus, `cooperation_grant` (`purpose`, `actions`, `valid_to`, `revoked_at`), `journey_leg.contract_version_id` als eingefrorener Vertragsstand.
  - API: neue Fremdbuchung nach Ablauf → `403 COOPERATION_NOT_ALLOWED` (Kap. 05 §5.4) an `/v1/federation/journeys/plan` (kein Angebot) und `/v1/federation/offers/{offer_id}/hold`; Statusabfrage, Cancel und Clearinglesen für Bestandsreisen bleiben über den Restgrant möglich.
  - Oberfläche: UI-18 zeigt Ablaufdatum, Folgen für neue Reisen und bestehende Segmente sowie die Liste offener Reisen; Vorwarnung vor Ablauf.
  - Ereignisse/Outbox: Vertrag läuft ab (Vorwarnung), Vertrag abgelaufen, Restabwicklung abgeschlossen.
- **Detailverträge vorab:** Schema der Auslaufregel in `terms` (Fristen, zulässige Aktionen, Ende des Restgrants); Verhalten für `HELD`, aber nicht bestätigte Reisen zum Ablaufzeitpunkt; Vorwarnfrist.
- **Akzeptanzkriterien:**
  1. Nach Ablauf erhält eine neue Anfrage keine Fremdbuchung; Plan liefert kein buchbares Partnersegment, Hold wird mit `COOPERATION_NOT_ALLOWED` abgewiesen (COM-011, Beschreibung).
  2. Eine vor Ablauf bestätigte Reisekette wird nach der dokumentierten Auslaufregel abgewickelt: Status, Durchführung, Storno, Erstattung und Abrechnung funktionieren mit der ursprünglichen Haftungs-/Stornoregel (COM-011; PH-AT-003).
  3. Der Restabwicklungszugriff ist explizit auf die betroffenen Journey-/Segment-IDs und erlaubten Felder begrenzt; ein allgemeiner Zugriff wird auch mit altem Token/Cache nicht wiederhergestellt (PH-AT-003).
  4. Ausgelaufene Kooperation verhindert Neugeschäft; laufende Erstattungs- und Gegenberichtsrechte bleiben eng begrenzt (COST-09).
  5. Der eingefrorene Vertragsstand einer Buchung begründet allein keinen Zugriff.
  6. Der Ablauf wirkt zur Serverzeit am halboffenen Intervallende: eine Anfrage exakt zum Zeitpunkt `valid_to` gilt als nach Ablauf; Clientzeit hat keinen Einfluss.
  7. Beide Parteien und die verantwortliche Leitstelle erhalten Vorwarnung und Ablaufmeldung mit der Liste der betroffenen offenen Reisen; der Restabwicklungsgrant endet automatisch, wenn die letzte betroffene Reise abgewickelt und abgerechnet ist.
- **Tests:** positiv: J1 vor Ablauf bestätigt, nach Ablauf durchgeführt und abgerechnet. negativ: J2 nach Ablauf; Zugriff auf andere Reisen über den Restgrant; alter Cacheeintrag. konkurrierend: Confirm exakt am Ablaufzeitpunkt – Grantrevision wird unmittelbar vor dem lokalen Commit geprüft; bereits abgesandte Partneroperation wird als In-flight-Fall abgeglichen. Referenztests: besteht mit M6b PH-AT-003; COST-09.
- **Abhängigkeiten:**
  - intern: WP-M6c-17
  - extern: WP-M6a-04, WP-M6a-15 (Vertragsgate und Kooperationsfilter, Reconciliation von In-flight-Operationen)
  - extern: WP-M6b-05, WP-M6b-06, WP-M6b-07 (Grantprüfung bei jedem Zugriff, Pause und Sicherheitswiderruf)
- **Entscheidungen:** D-11 (Restabwicklungsfreigaben nach Kooperationspause, Aufbewahrung) – blockiert nur Produktivschaltung; D-04 (Kostenträger bei Abbruch) – nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Sicherheitswiderruf und geplante Pause einschließlich des gesonderten PH-AT-003-Tests „Datenschutz-/Sicherheitsnotfall entzieht auch Abwicklungsrechte“ (M6b); automatische Vertragsverlängerung (im Pflichtenheft nicht beschrieben).

### WP-M6c-19 – Clearingvereinbarung und Verteilungsabrechnung je Segment

- **Ziel:** Verbundtarife und Clearingvereinbarungen definieren je Vertragsversion Erlösanteile, Provisionen und Kostenverteilung pro Segment; für ein Beispiel mit zwei Betreibern entsteht eine prüfbare Verteilungsabrechnung.
- **Anforderungen:** `LH-I.9-COM-002`
- **Liefergegenstände:**
  - Backend: Modul `documents-finance` (Aggregat `Settlement`); Regelauswertung der Clearingvereinbarung: Erlösanteil je Segment/Leistendem, Provisionssatz und -basis, Kostenverteilung, Rabatt-/Zuschussfinanzierer; Dezimalarithmetik, Rundung nach Kap. 03 §2.1; Ergebnis als Verteilungsabrechnung mit Rechenweg.
  - Datenbank: `partner_contract_version.terms` (Clearingregel, eingefroren), Regeltabellen als versionierte Konfiguration; Ergebnis in `settlement_line`-Entwürfen (`PROVISIONAL`).
  - Konfigurationsparameter: `partner_settlement_model` (Regel-ID, Ebene Vertrag), `cost_split_rule` (Formel/Regel, Ebene Vertrag, „Summe muss 100% ergeben“).
  - API: Vorschau der Verteilungsabrechnung für eine Reise oder ein Testszenario (Pfad Vorschlag unter `/v1/clearing/statements`).
  - Oberfläche: UI-18 zeigt Clearingmodell und Sätze der Vertragsversion; UI-16 zeigt den Rechenweg je Zeile.
- **Detailverträge vorab:** Regelschema `partner_settlement_model` (Provision in Prozent des Nettofahrpreises, fix, gestaffelt; Fälligkeitstatbestand); Validator „Summe 100 %“ für `cost_split_rule`; Behandlung von Verbundrabatt und Zuschuss in der Verteilung.
- **Akzeptanzkriterien:**
  1. Für Rechenbeispiel A (zwei Betreiber) liefert die Verteilungsabrechnung je Segment Leistungswert netto, Steuer, Provision netto, Provisionssteuer und Betreiberanspruch: A 9,51 EUR, B 19,02 EUR, Provision 3,57 EUR brutto; Geldkontrolle 32,10 = 9,51 + 19,02 + 3,57 (COM-002; COST-03).
  2. Jede Zeile nennt Regel-ID, Vertragsversion, Satz und Rechenbasis, sodass ein Prüfer das Ergebnis nachrechnen kann (COM-002).
  3. Eine `cost_split_rule`, deren Anteile nicht 100 % ergeben, wird bei Publikation abgewiesen.
  4. Eine Änderung der Clearingregel wirkt nur über eine neue Vertragsversion und nie auf bereits bestätigte Segmente.
  5. Die Vereinbarung deckt alle drei Regelgegenstände der Anforderung ab – Erlösanteil, Provision und Kostenverteilung je Segment – und ein Verbundtarif bzw. Verbundrabatt über beide Segmente wird nach dem vereinbarten Schlüssel centgenau auf die Betreiber verteilt (COM-002, Beschreibung; COST-10).
  6. Die Vorschau ist nur für die Vertragsparteien abrufbar; ein Dritter erhält 404 und erfährt keine Sätze.
- **Tests:** positiv: Rechenbeispiel A; PH-AT-005 (A 10,00 / B 15,00) mit Testprovision. negativ: Regel ohne Fälligkeitstatbestand, Summe ≠ 100 %, Vorschau durch Mandant C, Rundungsrest (Kap. 05 §5.8 „EUR-Rundungsrest“). konkurrierend: zwei parallele Berechnungen derselben Reise liefern identische Beträge und erzeugen keine doppelten Entwürfe.
- **Abhängigkeiten:**
  - intern: WP-M6c-17
  - extern: WP-M1-19, WP-M1-20, WP-M1-22 (Parameterkatalog, Konfigurationsrevisionen, Validierung)
  - extern: WP-M4-35 (centgenaue Umlage (Largest Remainder))
- **Entscheidungen:** D-01, D-03 (Provisions- und Verkäufermodell), D-07 (Kostenverteilung) – Bau mit Testregeln; blockieren Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Buchung der Ansprüche (WP-M6c-20); Steuerregeln (WP-M6c-21); Subunternehmersätze (WP-M6c-24).

### WP-M6c-20 – Anspruchsledger: getrennte Transaktionshistorie und Stornokorrektur

- **Ziel:** Die Transaktionshistorie trennt Kundenbuchung, Entgeltanspruch des Beförderers und Vermittlungsprovision als eigene, unveränderliche Positionen; ein Storno der Kundenbuchung aktualisiert die Ansprüche nach vertraglicher Regel durch Gegenpositionen.
- **Anforderungen:** `LH-I.9-COM-005`
- **Liefergegenstände:**
  - Backend: Modul `documents-finance`; Erzeugung von `settlement_line` aus eindeutigem Segment-/Leistungsereignis und eingefrorener Vertragsversion (Kap. 03 §4.3); je Segment mehrere separat eindeutige Anspruchskomponenten (Beförderungsentgelt, Provision, Provisionssteuer); Zustände `PROVISIONAL → ELIGIBLE`; Gegenposition über `reverses_id`, die korrigiert, aber nie überschreibt; Journalbildung je Rechtsträger (PSP-Verrechnung, Verbindlichkeit A/B, Provisionsertrag, Provisionssteuer, PSP-Aufwand für die PSP-Gebühr als eigenes Journal ohne Wirkung auf Betreiberansprüche) in einer Transaktion mit Ereignis und Outbox.
  - Datenbank: `settlement_line` (`event_id`, `leg_id`, `contract_version_id`, `creditor_ref`, `debtor_ref`, `payable_minor`, `revision`, `reverses_id` plus Erweiterungen aus WP-M6c-02), `economic_event` (Unique-Ursprung), `journal`, `journal_line`, `fiscal_period`; Invarianten „Genau eine Kostenwirkung“, „Journal ausgeglichen, ≥2 Zeilen, offene Periode“, „Gegenereignis statt Änderung“ (Kap. 04 §4.5).
  - Ereignisse/Outbox: Anspruch entstanden, Anspruch korrigiert; Verbraucher: Zahlungsereignis `CAPTURED`, Segmentbestätigung, Ausführungsnachweis, Storno.
  - API: Lesen der Anspruchszeilen je Reise/Segment unter `/v1/clearing/statements` (Unterressource Zeilen).
  - Konfigurationsparameter: `partner_settlement_model`, `partner_liability_policy` (Rückrechnung der Provision bei Storno).
- **Detailverträge vorab:** DDL-Erweiterung `settlement_line` und neue Unique-Regel je Anspruchskomponente; Abbildung Ereignis → Zeilen → Journal (Kontenschema als Testkontenrahmen bis D-06); Storno-Rückrechnungsregel je Vertrag; Übergangsbedingung `PROVISIONAL → ELIGIBLE` (bestätigte Leistung bzw. vertraglicher Anspruchstatbestand).
- **Akzeptanzkriterien:**
  1. Für eine bezahlte Zweibetreiberreise existieren getrennte Positionen für Kundenbuchung (32,10), Entgeltanspruch A (9,51) und B (19,02) sowie Vermittlungsprovision (3,57 brutto); keine Position vermischt Beförderungsumsatz und Provision (COM-005; PH-AT-005).
  2. Storno von B erzeugt Gegenpositionen: Fahrgastforderung −21,40, Beförderungswert −20,00, Steuer −1,40, Provision −2,00, Provisionssteuer −0,38; die Originalpositionen bleiben unverändert; Kundennettobelastung 10,70 (COM-005; COST-03).
  3. Mit abweichender Vertragsregel (PH-AT-014: 12,00 Erstattung, 3,00 Stornoentgelt, festgelegte Provisionsrückrechnung) folgen Partner- und Provisionskorrektur der eingefrorenen Vertragsrevision und sind separat ausgewiesen (COM-005; PH-AT-014).
  4. Jedes Einzeljournal ist je Rechtsträger und Währung ausgeglichen (COST-03); ein nur mandantenübergreifend ausgeglichener Buchungssatz wird abgewiesen.
  5. Wiederholte Zustellung desselben Auslöseereignisses erzeugt keine zweite Anspruchsposition (COST-04).
  6. Ein Storno in geschlossener Periode bucht die Korrektur in die nächste offene Periode mit Ursprungsleistungszeitraum.
  7. Eine Zahlungsautorisierung allein erzeugt weder Einnahme noch Anspruchsposition; der Anspruch entsteht erst mit dem in `partner_settlement_model` vereinbarten Tatbestand (Kap. 03 §4.2).
  8. Die Anspruchszeilen sind nur für Gläubiger und Schuldner der jeweiligen Zeile lesbar; ein Dritter erhält 404.
- **Tests:** positiv: Rechenbeispiel A einschließlich aller sieben Journale aus Kap. 03 §5. negativ: Anspruch ohne Vertragsversion; UPDATE einer gebuchten Zeile; Buchung in geschlossene Periode (Kap. 05 §5.8). konkurrierend: 100 identische Webhooks; Storno parallel zur Anspruchsentstehung; Periodenschluss parallel zur Korrektur – genau eine Reihenfolge gewinnt. Referenztests: ermöglicht E2E-093 (Segmentumsätze), PH-AT-005, PH-AT-014, E2E-090, E2E-104.
- **Abhängigkeiten:**
  - intern: WP-M6c-02, WP-M6c-07, WP-M6c-14, WP-M6c-19
  - extern: WP-M1-14 (Outbox-Relay, Inbox-Deduplikation)
  - extern: WP-M4-33, WP-M4-36 (Kostenereignis, Journal und einmaliges Posting; Fiskalperiode und Gegenereignis)
  - extern: WP-M6a-25 (Ausführungsnachweis und Abrechnungsbezug je Segment)
- **Entscheidungen:** D-06 (Kontenrahmen, Erlöszeitpunkt), D-01/D-03 (Anspruchstatbestand) – Bau mit Testkontenrahmen; blockieren Produktivschaltung.
- **Größe:** L
- **Nicht im Umfang:** Statementbildung (WP-M6c-23); Geldbewegung an Betreiber (WP-M6c-22); Steuerfindung (WP-M6c-21).

### WP-M6c-21 – Steuer- und Rechnungsstellungsmodell je Leistungsart und Vertragsbeziehung

- **Ziel:** Mehrwertsteuer- und Rechnungsstellungspflichten sind je Leistungsart (Beförderung, Vermittlung, Fahrzeuggestellung, Subunternehmerleistung) und je Vertragsbeziehung getrennt modelliert; eine Musterrechnung zeigt das korrekt zugeordnete Szenario ohne pauschalen Steuersatz.
- **Anforderungen:** `LH-I.9-COM-008`
- **Liefergegenstände:**
  - Backend: Modul `documents-finance`; versionierte Steuerregel (`tax_rule_revision`) mit Schlüssel aus Leistungsart, Rechtsbeziehung (Aussteller → Empfänger), Land und Gültigkeit; Steuerfindung je Preiskomponente und je Anspruchskomponente; Steuerrundung je Gruppe aus Aussteller, Steuerkennzeichen, Steuersatz und Leistungsart, Standard `ROUND_HALF_UP` (Kap. 03 §2.1); Rechnungs-/Gutschriftenposition mit Aussteller und Empfänger als juristische Einheiten.
  - Datenbank: `tax_rule_revision` (Kap. 03 §2), Rechnungs-/Gutschriftenposition, `economic_event.tax_rule_revision`, `legal_entity.tax_reference_token`, Rollen `invoice_issuer` und `seller` je Segment.
  - API: Rechnungsvorschau und Abruf je Statement/Anspruch (Pfad Vorschlag unter `/v1/clearing/statements`).
  - Oberfläche: UI-16 zeigt Steuerregelrevision und Rechnungsaussteller je Zeile; UI-17/Konfigurationseditor pflegt Steuerregeln durch berechtigte Finanzrolle.
  - Konfigurationsparameter: `invoice_accounting_schema` (Kontierungsplan, nur berechtigte Finanzrolle).
- **Detailverträge vorab:** Schema `tax_rule_revision`; Liste der Leistungsarten; Musterrechnungsvorlagen je Szenario; Test-Steuersätze sind Testdaten, keine Feststellung (Kap. 03 Kopf).
- **Akzeptanzkriterien:**
  1. Die Musterrechnungen für Rechenbeispiel A zeigen zwei Leistungsrechnungen (A: 10,00 + 0,70; B: 20,00 + 1,40) und die Provisionsrechnung(en) (1,00 + 0,19; 2,00 + 0,38) mit jeweils eigenem Aussteller, Empfänger, Leistungsart und Steuerregelrevision; es wird kein pauschaler Steuersatz für alle Fahrten verwendet (COM-008; COST-03).
  2. Fehlt für eine Kombination aus Leistungsart und Vertragsbeziehung eine freigegebene Steuerregel, wird die Rechnung nicht erzeugt und ein Klärfall angelegt – kein stiller Standardsatz (COM-008).
  3. Eine gruppierte Kundendarstellung ersetzt keine je Aussteller erforderliche Rechnung (Kap. 03 §2).
  4. Eine neue Steuerregelrevision wirkt nur ab ihrem Gültigkeitsbeginn; bestehende Belege bleiben unverändert.
  5. Pflege und Freigabe von Steuerregeln erfordern die berechtigte Finanzrolle und Vier-Augen-Freigabe; jede Publikation ist im Audit nachweisbar.
  6. Eine vertragliche X→Y-Leistung erhält einen eigenen Forderungs-/Verbindlichkeitsbezug je Rechtsträger (PH-AT-013, Teil getrennte Rechtsträger).
- **Tests:** positiv: Musterrechnungen Rechenbeispiel A und Beispiel B (Gestellung 800,00 + 152,00). negativ: fehlende Regel, überlappende Gültigkeit zweier Regeln, Aussteller = Empfänger. konkurrierend: zwei parallele Publikationen überlappender Steuerregeln – genau eine wird wirksam.
- **Abhängigkeiten:**
  - intern: WP-M6c-19, WP-M6c-20
  - extern: WP-M1-20, WP-M1-22, WP-M1-05 (Konfigurationsrevisionen mit Vier-Augen-Freigabe, Rechtsträgerstamm)
  - extern: WP-M4-33, WP-M4-37 (Kostenereignis mit Steuerrevision, ERP-Export)
  - extern: WP-M6b-12 (Rechtsträgerrollen (Verkäufer, Beförderer, Rechnungsaussteller) je Dienst)
- **Entscheidungen:** D-06 (Steuerregeln, Vorsteuerabzug, Rundung, Belegmodell) und D-01 (Rechnungsaussteller) – Bau mit Testregeln; blockieren produktive Faktura.
- **Größe:** M
- **Nicht im Umfang:** E-Rechnungsformate XRechnung/ZUGFeRD (M8); Steuerberatung bzw. rechtliche Feststellung des Steuersatzes (Auftraggeber).

### WP-M6c-22 – Auszahlungsanweisung, Transferreversal und Rückforderung

- **Ziel:** Akzeptierte Ansprüche werden als Auszahlungsanweisung an den PSP gegeben; Korrekturen nach erfolgter Auskehrung führen zu Transferreversal oder dokumentierter Rückforderung/Verrechnung, niemals zu einer negativen Auszahlung.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Modul `documents-finance` mit Zahlungsoperationen über das Modul `fare-payment`; Aggregat Auszahlungsanweisung mit eigenem Zustandsautomaten; Zustände `settlement_line` `ACCEPTED → PAYOUT_PENDING → PAID`; Transferreversal mit Erfolgs- und Fehlpfad; bei Fehlschlag Forderung gegen den Partner statt PSP-Zufluss (Kap. 03 §5); Verrechnung offener Rückforderungen mit späteren Ansprüchen nur nach Vertragsregel und nie für strittige Beträge.
  - Datenbank: `payout_instruction`, `transfer_reversal` (Kap. 04 §4.4; externe PSP-Referenz), `payment_order` mit eindeutiger PSP-Kommandoreferenz, `journal`/`journal_line` für Auskehrung, Reversal und Forderung.
  - Adapter/Worker: PSP-Adapter Transfer/Payout/Reversal/Status; Worker mit Lease/Fencing.
  - Ereignisse/Outbox: Auszahlung angewiesen/bestätigt/fehlgeschlagen; Rückforderung entstanden/ausgeglichen.
- **Detailverträge vorab:** Zustandsautomaten Transfer, Payout, Reversal; Auslöser der Auskehrung je `partner_settlement_model` (PSP-Transfer nach Capture oder Auszahlung erst nach angenommenem Statement, Offener Punkt 32); Ausfall-/Liquiditätsregel (wer finanziert die Kundenerstattung vor, Kap. 03 §5 und §8) als Konfiguration; Verrechnungsregel.
- **Akzeptanzkriterien:**
  1. Auskehrung an A und B bestätigt: 9,51 und 19,02 EUR; Journal „Verbindlichkeit A/B an PSP-Verrechnung 28,53“ ausgeglichen (COST-03).
  2. Bereits ausgezahlte Mittel erzeugen bei Korrektur eine dokumentierte Rückforderung/Verrechnung statt einer negativen erneuten Auszahlung (PH-AT-014).
  3. Scheitert das Reversal, wird eine Forderung an B über 19,02 EUR gebucht; der Kundenerstattungsanspruch bleibt 21,40 EUR und wird nicht still auf 2,38 EUR gekürzt.
  4. Eine Auszahlung gilt erst mit bestätigtem PSP-Ergebnis als `PAID`; Timeout bleibt `UNKNOWN` bis zur Statusabfrage; keine zweite Anweisung auf Verdacht.
  5. Strittige Teilbeträge sind von der Auszahlung ausgenommen (COST-09: „Streitbetrag wird nicht automatisch ausgezahlt“).
  6. Die Summe aller Auszahlungen und Transfers je Anspruch überschreitet nie den akzeptierten, nicht gesperrten Betrag; es gibt keine Anweisung mit negativem Betrag.
  7. Auslösen einer Auszahlung erfordert die Freigaberolle Clearing; Anweisung, Bestätigung, Reversal und Verrechnung sind je mit Akteur bzw. Systemursprung im Audit nachweisbar.
  8. „Kundenerstattung ausstehend“ und „Rückholung vom Betreiber ausstehend“ werden als getrennte Status geführt (Kap. 06 §6.6).
- **Tests:** positiv: Auskehrung und erfolgreiches Reversal aus Rechenbeispiel A. negativ: Reversal bei unzureichendem Partnerguthaben; Auszahlung gesperrter Zeile. konkurrierend: zwei Worker auf derselben Anweisung (Fencing); doppelter Payout-Webhook; Storno parallel zur laufenden Auszahlung. Referenztests: besteht mit WP-M6c-14 und WP-M6c-20 PH-AT-014.
- **Abhängigkeiten:**
  - intern: WP-M6c-03, WP-M6c-20, WP-M6c-23 (Zustand `ACCEPTED` aus dem angenommenen Statement)
  - extern: WP-M1-15, WP-M1-14 (`operation` mit Lease/Fencing, Outbox)
  - extern: WP-M2-29 (PSP-Adapter und Provider-Ereignisse)
- **Entscheidungen:** D-03 (PSP-Mehrparteienmodell, Reserve, negative Salden, Risikoträger) – Bau gegen Simulator; blockiert Freigabe von Einzug/Auskehrung.
- **Größe:** M
- **Nicht im Umfang:** Bankkontoabgleich (WP-M6c-26); Onboarding der Betreiber beim PSP (Vertrags-/Betriebsthema nach D-03).

### WP-M6c-23 – Clearingdatensätze und monatliche Gegenberichte

- **Ziel:** Nachvollziehbare Clearingdatensätze führen Reise, Segmente, Ausführung, Tarif, Steuer und beteiligte Partner; beide Parteien erhalten monatliche Gegenberichte mit denselben Leistungs- und Anspruchs-IDs, die auf Segmentebene abstimmen.
- **Anforderungen:** `LH-I.9-COM-003`
- **Liefergegenstände:**
  - Backend: Modul `documents-finance`; Statementlauf je Monat/Vertrag/Währung; Übergang `ELIGIBLE → STATEMENTED → ACCEPTED`; Statementrevisionen unveränderlich, Korrektur als neue Revision bzw. Folgestatement; Gegenbericht als empfängergebundene Projektion ohne interne Aufwände der Gegenseite.
  - Datenbank: `settlement_statement` („Monat/Vertrag/Währung; … Settlementrevisionen unveränderlich“, Kap. 04 §4.4), `settlement_line` mit Statementbezug, `journey`, `journey_leg` (`execution_state`, `contract_version_id`), `fare_component`, `tax_rule_revision`.
  - API: `/v1/clearing/statements` (Kap. 05 §5.7): Liste `{items,next_cursor,as_of}`, Detail mit Zeilen, Annahme durch die Gegenseite mit `If-Match`; vollständiger Export asynchron über `/v1/reports/jobs`.
  - Oberfläche: UI-16 Tab „Partnerabrechnung“: Zeile mit Leg, Leistendem, Verkäufer, Tarifumsatz, Provision, Korrekturen, Sollauszahlung, Zahlungsstand (Kap. 06 §6.6).
  - Konfigurationsparameter: `finance_close_day` (Periodenabschlussdatum; „nach Abschluss nur Korrekturvorgänge“), `reporting_timezone` für die Monatsgrenze.
  - Ereignisse/Outbox: Statement erstellt/angenommen/korrigiert.
- **Detailverträge vorab:** Felder des Clearingdatensatzes (Reise, Segment, Ausführung, Tarif, Steuer, Partner, Anspruchs-ID); Stichtags- und Zeitzonenregel; Exportformat des Gegenberichts; Regel für nach Stichtag eintreffende Korrekturen.
- **Akzeptanzkriterien:**
  1. Jeder Clearingdatensatz enthält Reise, Segment, Ausführungsstatus, Tarif-/Tarifrevisionsbezug, Steuer und beteiligte Partner und ist bis zum Ursprungsereignis zurückverfolgbar (COM-003, Beschreibung).
  2. Die beiden monatlichen Gegenberichte von A und B (bzw. Betreiber und Plattform) enthalten dieselben Segment- und Anspruchs-IDs mit betragsgleichen, spiegelbildlichen Positionen; ein automatischer Vergleich auf Segmentebene ergibt null Differenzen (COM-003).
  3. Die monatliche Partnerforderung stimmt mit Kundenbeleg und Segmentumsätzen überein (E2E-093).
  4. Ein Gegenbericht enthält keine internen Aufwände, Lieferantenpreise oder Margen der Gegenseite (Kap. 03 §2).
  5. Ein angenommenes Statement ist unveränderlich; eine spätere Korrektur erscheint als eigene Position im Folgestatement mit Bezug auf das Original.
  6. Wiederholter Statementlauf für denselben Monat/Vertrag liefert dasselbe Ergebnis und kein zweites Statement.
- **Tests:** positiv: Monat mit Rechenbeispiel A und PH-AT-005; Vergleichsskript der beiden Gegenberichte. negativ: Zugriff eines Dritten; Änderung eines angenommenen Statements; Annahme mit veraltetem ETag (412). konkurrierend: zwei parallele Statementläufe; Storno parallel zum Statementlauf – Zeile landet genau in einem Statement. Referenztests: besteht mit WP-M6c-16 und WP-M6c-20 E2E-093; Beitrag PH-AT-005.
- **Abhängigkeiten:**
  - intern: WP-M6c-20, WP-M6c-21
  - extern: WP-M1-02, WP-M1-18 (Listen-/Cursor-Konvention, ETag, Zeit- und Zeitzonenbaustein)
  - extern: WP-M4-36, WP-M4-37, WP-M8c-27 (Fiskalperiode und Periodenabschluss; Berichtsjobs/Export)
  - extern: WP-M6a-25 (Ausführungsnachweis je Segment)
- **Entscheidungen:** D-06 (Periodenabschluss, Exportformat) – blockiert nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Differenzklärung (WP-M6c-26, WP-M6c-28); Partnerportal-Zugang (WP-M6c-31).

### WP-M6c-24 – Abrechnung von Subunternehmerleistungen

- **Ziel:** Subunternehmerleistungen können nach Schichten, Fahrzeugkilometern oder Pauschalen abgerechnet werden; die Leistungsabrechnung weist den vertraglichen Satz und die tatsächlich bestätigte Leistung aus.
- **Anforderungen:** `LH-I.9-COM-006`
- **Liefergegenstände:**
  - Backend: Modul `documents-finance`; Abrechnungsarten Schicht, Fahrzeugkilometer, Pauschale als Regeltypen der Vertragsversion; Leistungsnachweis aus bestätigten Schichten/Dienststunden und klassifizierten Fahrzeugkilometern über Leseports; Anspruchskomponente mit Rolle `subcontractor`; bilaterale Fremdleistung ohne Umbuchung fremder Originalaufwände (Kap. 03 §6).
  - Datenbank: `settlement_line` (Anspruchsgrund Subunternehmerleistung; Bindung ohne Segment siehe Offener Punkt 12), `partner_contract_version.terms` (Sätze), bestätigter Leistungsnachweis (Vorschlag) mit Bezug auf `staff_shift`, `vehicle_run`, `vehicle`; `cost_object` und `economic_event` je Rechtsträger.
  - API: Leistungsabrechnung als Statementart unter `/v1/clearing/statements`; Bestätigung der Leistung durch berechtigte Rolle.
  - Oberfläche: UI-16 Leistungsabrechnung mit Spalten vertraglicher Satz, bestätigte Menge, Betrag, Nachweisbezug.
  - Konfigurationsparameter: `partner_settlement_model`, `cost_split_rule`, `sharing_vehicle_quota` (Bezug zum vereinbarten Fahrzeugkontingent).
- **Detailverträge vorab:** Regelschema der drei Abrechnungsarten; Definition „tatsächlich bestätigte Leistung“ und bestätigende Rolle; Quelle der Kilometer (Kilometerklassen aus M4); Mengen als `numeric(20,8)` mit expliziter Einheit.
- **Akzeptanzkriterien:**
  1. Die Leistungsabrechnung nennt je Position den vertraglichen Satz der eingefrorenen Vertragsversion und die tatsächlich bestätigte Leistung (Menge, Einheit, Nachweis); Betrag = Satz × bestätigte Menge, bei Pauschale der vereinbarte Betrag (COM-006).
  2. Referenzfall Kap. 03 §6: 100 bestätigte Dienststunden, 800,00 EUR netto X→Y; Y sieht keine Einkaufsbelege oder Lieferantenpreise von X (COM-006; COST-06).
  3. Geplante, aber nicht bestätigte Leistung wird nicht abgerechnet; Abweichung Plan/Ist ist sichtbar.
  4. Kosten- und Leistungsträger bleiben getrennt; es entsteht keine gegenseitige Stammdatenfreigabe (E2E-094).
  5. Dieselbe bestätigte Leistung kann nicht zweimal abgerechnet werden.
- **Tests:** positiv: je ein Fall Schicht, Kilometer, Pauschale. negativ: Abrechnung ohne Bestätigung; Menge mit falscher Einheit; Zugriff von Y auf X-Belege. konkurrierend: zwei parallele Abrechnungsläufe über denselben Leistungsnachweis ergeben eine Position. Referenztests: Beitrag zu E2E-094, E2E-095, PH-AT-013.
- **Abhängigkeiten:**
  - intern: WP-M6c-19, WP-M6c-20, WP-M6c-23
  - extern: WP-M4-08, WP-M4-03, WP-M4-42, WP-M4-02 (Ist-Arbeitszeit/Schichten, Klassifikation der Fahrzeugkilometer, Leseports zu Fahrt und Flotte)
  - extern: WP-M6b-33, WP-M6b-12 (Fahrzeugüberlassung und Einsatzfreigabe für Partnerfahrzeuge, Rollen Eigentümer/Betreiber/Kostenträger)
- **Entscheidungen:** D-07 (interne Verrechnung, Treiber), D-06 – blockieren nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Konsolidierte Ressourcensicht und TCO (M8); Dienst- und Einsatzplanung des Subunternehmers (M7).

### WP-M6c-25 – Rechnungs- und Sammelzahlmodelle für Unternehmen und Partner

- **Ziel:** Unternehmen und Partner können Fahrten auf Rechnung beziehen und gesammelt bezahlen; die Monatsabrechnung weist jede bezogene Fahrt nachvollziehbar aus.
- **Anforderungen:** `LH-6.10-PAY-012`
- **Liefergegenstände:**
  - Backend: Modul `fare-payment` (Zahlungsart Rechnung) und Modul `documents-finance` (Sammelabrechnung); Rechnungskunde als Organisation mit Rechtsträger; Zuordnung der Fahrt zum Rechnungskunden beim Checkout; monatlicher Abrechnungslauf mit Einzelpositionen; Zahlungseingang als Ausgleich der Forderung ohne erneute Erlösbuchung.
  - Datenbank: `organization`/`legal_entity` (Rechnungskunde), `payment_intent` (Zahlungsart Rechnung), Rechnungs-/Gutschriftenposition, `economic_event`, `journal`; Zuordnung berechtigter Fahrgäste zum Rechnungskunden (Vorschlag).
  - API: Checkout mit Zahlungsart Rechnung und Kostenreferenz; Abruf der Monatsabrechnung (Pfad Vorschlag); Export über `/v1/ledger/exports`.
  - Oberfläche: UI-03 Zahlungsart „Rechnung“ nur für berechtigte Fahrgäste; UI-16 Tab für Sammelabrechnungen; UI-05 zeigt die Zuordnung.
- **Detailverträge vorab:** Modell des Rechnungskunden und seiner Berechtigten; Kreditrahmen/Zahlungsziel; Pflichtinhalt der Einzelposition (Datum, Strecke/Segment, Reisender-Referenz, Betrag, Steuer); Datenschutz bei Ausweis der Fahrt gegenüber dem Arbeitgeber (Offener Punkt 5).
- **Akzeptanzkriterien:**
  1. Die Monatsabrechnung eines Unternehmens listet jede bezogene Fahrt als eigene Position mit Datum, Segment, Betrag, Steuer und Buchungsreferenz; die Summe der Positionen ergibt den Rechnungsbetrag (PAY-012).
  2. Stornierte bzw. erstattete Fahrten erscheinen als Korrekturposition mit Bezug auf die Originalposition.
  3. Ein nicht berechtigter Fahrgast kann die Zahlungsart Rechnung weder sehen noch per API nutzen.
  4. Der Zahlungseingang gleicht die Forderung aus und erzeugt keinen zweiten Erlös.
  5. Wiederholter Abrechnungslauf erzeugt dieselbe Abrechnung, keine zweite.
  6. Dasselbe Modell gilt für einen Partner als Rechnungskunden (Sammelzahlung statt Einzelzahlung je Fahrt); Partner- und Unternehmensabrechnung sind getrennte Belege und vermischen sich nicht mit dem Partnerclearing aus WP-M6c-23 (PAY-012, Beschreibung).
  7. Die Monatsabrechnung zeigt dem Rechnungskunden nur die im Detailvertrag freigegebenen Fahrtmerkmale; Unterstützungsmerkmale und nicht erforderliche Reisedetails der Mitarbeitenden erscheinen nicht (Datenminimierung, Offener Punkt 5).
- **Tests:** positiv: drei Fahrten zweier Mitarbeitender, eine Monatsabrechnung. negativ: unberechtigter Fahrgast; Abruf durch fremdes Unternehmen; Überschreitung des Kreditrahmens. konkurrierend: Storno parallel zum Abrechnungslauf – Fahrt erscheint genau einmal (als Position oder Position plus Korrektur).
- **Abhängigkeiten:**
  - intern: WP-M6c-05, WP-M6c-16, WP-M6c-21
  - extern: WP-M1-05, WP-M1-08 (Organisation/Rechtsträger, Objektberechtigung)
  - extern: WP-M4-33, WP-M4-36, WP-M4-37 (Journal, Fiskalperiode, ERP-Export als wiederholsicherer Job)
- **Entscheidungen:** D-06 (Faktura, Kontenrahmen, Export) – blockiert produktive Faktura.
- **Größe:** M
- **Nicht im Umfang:** Mahnwesen und Bankeinzug (laut D-06-Favorit im Hauptbuch/ERP); Partnerclearing (WP-M6c-23).

### WP-M6c-26 – Zahlungsreconciliation über vier Ebenen

- **Ziel:** Ein Abgleichlauf vergleicht PSP-Webhooks, Buchungen, Belege und Auszahlungen über vier Ebenen; fehlende oder doppelte Providerereignisse führen nie zu einer doppelten Belastung, jede Differenz ist ein bearbeitbarer Fall.
- **Anforderungen:** `LH-6.10-PAY-014`
- **Liefergegenstände:**
  - Backend: Modul `documents-finance` mit Leseports auf `fare-payment`; Abgleich der Ebenen (1) Reise/Segment samt Durchführung, (2) Anspruch/Provision/Steuer, (3) Kundenzahlung/Refund und PSP-Transfers, (4) PSP-Auszahlung/Bankreferenz (Kap. 03 §4.3); zusätzlich Belegabgleich: jede erfasste Zahlung und jede bestätigte Erstattung hat genau einen Beleg bzw. Korrekturbeleg aus WP-M6c-16 und umgekehrt; Chargeback-Ereignis des PSP setzt den separaten Streitstatus `DISPUTED` an der Zahlung und erzeugt eine Differenz mit Grundcode (Mindestumfang, weiterer Ablauf Offener Punkt 25); PSP-Gebühren aus der Settlement-Datei werden als eigene Position abgeglichen; Differenz mit Betrag, Alter, Grundcode und Bearbeiter; Auflösung von `CAPTURE_UNKNOWN`/`RECONCILING` per Statusabfrage; Erkennung fehlender Webhooks über Settlement-Datei.
  - Datenbank: `settlement_reconciliation` (Abweichungsfall, externe PSP-Referenz), `reconciliation_run`, `payment_event`, `inbox` (Dedupe `(consumer,event_id)` plus Payloadhash), `payment_intent`, `payout_instruction`.
  - API: `/v1/payments/provider-events/{provider}` (Dedupe, PSP-spezifische Signatur); Abgleichläufe und Differenzen lesen/bearbeiten (Pfad Vorschlag unter `/v1/clearing/statements`).
  - Adapter/Worker: Import der PSP-Settlement-Datei („Settlement-Dateiabgleich“, Kap. 05 §5.7); geplanter Abgleichworker mit Backoff.
  - Oberfläche: UI-16 Tab „Zahlungsabgleich“ mit Drilldown Kundenbetrag → Segmentanspruch → PSP-Transfer → Kontoabgleich; Zustände „Unbekannter Transfer“, „Chargeback“, „geschlossene Periode“ (Kap. 06 §6.9).
  - Ereignisse/Outbox: Differenz erkannt/geklärt; Alarm bei überalterten Differenzen.
- **Detailverträge vorab:** Grundcodeliste der Differenzen; Dateiformat der Settlement-Datei je PSP; Regel für geschlossenen Monat mit offenen Differenzen (dokumentierter Umgang, kein automatisches Wegbuchen); Zuständigkeitsregel für Bearbeiter.
- **Akzeptanzkriterien:**
  1. Ein doppelter, ein verspäteter und ein ungeordneter PSP-Webhook erzeugen keine zweite Zahlung, Erstattung oder Clearingposition (PAY-014; COST-04).
  2. Ein fehlender Webhook wird über Statusabfrage bzw. Settlement-Datei erkannt und führt zur korrekten einmaligen Zustandsfortschreibung, nicht zu einer erneuten Belastung (PAY-014).
  3. Eine unklare PSP-Antwort bleibt offen bis zur Reconciliation; die Journey kann bestätigt und die Zahlung in Klärung sein, beides sichtbar (COST-04).
  4. Jede Differenz trägt Betrag, Alter, Grundcode und Bearbeiter; ein Provider-Webhook allein gilt nicht als Beleg einer Bankauszahlung.
  5. Ein Monat mit offenen Differenzen kann nur mit dokumentierter Behandlung geschlossen werden; es gibt kein automatisches Wegbuchen.
  6. Der Abgleich deckt alle vier in der Anforderung genannten Gegenstände ab: zu jedem PSP-Ereignis existiert die Buchung, zu jeder erfassten Zahlung der Beleg, zu jeder Auskehrung die Auszahlungsbestätigung; ein fehlender oder doppelter Beleg wird als Differenz geführt (PAY-014, Beschreibung).
  7. Ein Chargeback wird als „Chargeback“ in UI-16 angezeigt, ändert `captured_minor`/`refunded_minor` nicht still und löst keine automatische Belastung des Betreibers aus.
  8. Provider-Ereignisse mit ungültiger PSP-Signatur werden abgewiesen und als Sicherheitsereignis protokolliert; Differenzbearbeitung erfordert die Finanzrolle und ist im Audit nachweisbar.
  9. Nach Restore/Wiederanlauf werden externe Zahlungen mit Providerreferenzen abgeglichen, bevor unsichere Schritte wiederholt werden (Kap. 08 §8.6).
- **Tests:** positiv: Abgleichlauf über Rechenbeispiel A ohne Differenz. negativ: fehlendes Capture-Ereignis, doppelter Refund-Webhook, Betragsabweichung in Settlement-Datei, gleiche Ereignis-ID mit verändertem Payload (409, Quarantäne), ungültige Signatur, Zahlung ohne Beleg, Chargeback-Ereignis. konkurrierend: 100 identische Webhooks (Kap. 05 §5.8); Abgleichlauf parallel zu eintreffendem Webhook; zwei parallele Abgleichläufe. Referenztests: besteht AT-15 (Mehrparteienfall), E2E-090, E2E-104 (Abrechnungsteil); COST-04.
- **Abhängigkeiten:**
  - intern: WP-M6c-03, WP-M6c-16 (Belege), WP-M6c-20, WP-M6c-22, WP-M6c-23
  - extern: WP-M1-14, WP-M1-15, WP-M3-52 (Inbox-Deduplikation, `operation`, Alarmgrundlage über Outbox)
  - extern: WP-M2-29 (PSP-Adapter und Provider-Ereignisse)
  - extern: WP-M4-36 (Fiskalperiode und Periodenabschluss)
  - extern: WP-M6a-15 (Reconciliation-Worker der Saga (gemeinsame Statusabfragekonvention))
- **Entscheidungen:** D-03 (PSP, Settlement-Datei, Statusabruf), D-06 (Periodenabschluss) – blockieren nur Produktivschaltung.
- **Größe:** L
- **Nicht im Umfang:** Bankkontoauszugsimport ins Hauptbuch (laut D-06-Favorit ERP); Partnerstreit (WP-M6c-28); Abgleich von Leistungsdaten Dritter (WP-M6c-27).

### WP-M6c-27 – Abgleich von Drittplattformdaten mit Fahrzeugereignissen

- **Ziel:** Von Drittplattformen gemeldete Fahrten werden mit internen Fahrzeugereignissen abgeglichen; eine extern bestätigte Fahrt ohne Ankunftsereignis erscheint als Klärfall.
- **Anforderungen:** `LH-I.9-COM-009`
- **Liefergegenstände:**
  - Backend: Modul `documents-finance`; Abgleich Ebene (1) Reise/Segment samt Durchführung: externe Segmentbestätigung bzw. Partnerabrechnungszeile gegen interne Ausführungsereignisse (Ankunft, Einstieg, Ausstieg) und `journey_leg.execution_state`; Klärfall mit Grundcode „fehlendes Ankunftsereignis“ bzw. „unbestätigtes Segment“; Sperre der betroffenen `settlement_line` vor `ELIGIBLE`, bis geklärt.
  - Datenbank: `settlement_reconciliation`, `reconciliation_run`, `journey_leg` (`federation_ref`, `execution_state`), Leseport auf Fahrerereignisse; `inbox` für importierte Drittdaten.
  - Adapter/Worker: Importschnittstelle für Drittplattformdaten (Partnerereignisse über `/v1/federation/partners/{partner_id}/events` bzw. Dateiimport; Format offen, Offener Punkt 19).
  - Oberfläche: UI-16 Klärfallliste mit Segment, Partnerreferenz, fehlendem Ereignis, Alter, Bearbeiter.
- **Detailverträge vorab:** Abgleichschlüssel (`federation_ref`); Toleranzfenster für verspätet synchronisierte Offline-Ereignisse; Grundcodes; Auflösungsaktionen (Ereignis nachgemeldet, Leistung bestritten → Streitfall).
- **Akzeptanzkriterien:**
  1. Eine extern bestätigte Fahrt ohne internes Ankunftsereignis wird nach Ablauf des Toleranzfensters als Klärfall in UI-16 angezeigt (COM-009).
  2. Die zugehörige Anspruchsposition wird nicht automatisch ausgezahlt, solange der Klärfall offen ist.
  3. Trifft das Ankunftsereignis verspätet ein (Offline-Sync), schließt der Klärfall mit Grund „nachgemeldet“, ohne doppelte Anspruchsposition (E2E-104).
  4. Ein internes Ereignis ohne externe Bestätigung wird ebenfalls als Differenz geführt (COM-009, Beschreibung: „fehlende oder unbestätigte Fahrtsegmente“).
  5. Importierte Drittdaten werden nur für Partner mit aktivem bzw. in Restabwicklung befindlichem Vertrag angenommen, signatur- bzw. herkunftsgeprüft und idempotent verarbeitet; der Klärfall ist nur für die beiden Vertragsparteien sichtbar.
- **Tests:** positiv: vollständige Übereinstimmung ohne Klärfall. negativ: fehlendes Ankunftsereignis, unbekannte `federation_ref`, Drittdaten eines nicht vertraglich gebundenen Partners. konkurrierend: Import parallel zur Offline-Synchronisation – genau ein Klärfall bzw. keiner.
- **Abhängigkeiten:**
  - intern: WP-M6c-23, WP-M6c-26
  - extern: WP-M3-29 (Fahrerereignisse Ankunft/Einstieg/Ausstieg)
  - extern: WP-M6a-17, WP-M6a-25 (Signierte, idempotente Partnerereignisse; Ausführungsnachweis je Segment)
- **Entscheidungen:** D-08 (Partner und deren Datenlieferung) – blockiert nur die Produktivschaltung konkreter Quellen.
- **Größe:** M
- **Nicht im Umfang:** Echtzeit-Anschlussüberwachung (M6a); Datenqualitätskennzahlen (M8).

### WP-M6c-28 – Streitfälle mit gesperrtem strittigem Betrag

- **Ziel:** Streitfälle bei Leistungs- und Abrechnungsabweichungen werden als getrennte Vorgänge mit Dokumenten geführt; der strittige Betrag bleibt gekennzeichnet und gesperrt und wird nicht automatisch gebucht, verrechnet oder ausgezahlt.
- **Anforderungen:** `LH-I.9-COM-004`
- **Liefergegenstände:**
  - Backend: Modul `documents-finance`; Aggregat `dispute_case` mit eigenem Zustandsautomaten (eröffnet, in Prüfung, Einigung, abgelehnt, geschlossen); Bezug auf eine oder mehrere `settlement_line` mit strittigem Teilbetrag; Sperre dieses Teilbetrags für Statementannahme, Auszahlung und Verrechnung; Abschluss erzeugt Gegen-/Korrekturposition oder Freigabe, nie Überschreiben.
  - Datenbank: `dispute_case` (Kap. 03 §2), Streitfalldokumente im Objektspeicher mit Hash, `settlement_line` (separater Streitstatus), `audit_event`.
  - API: Streitfall eröffnen, kommentieren, Dokument anhängen, entscheiden (Pfad Vorschlag unter `/v1/clearing/statements`); beide Parteien sehen nur den gemeinsamen Fall.
  - Oberfläche: UI-16 Aktion „Differenz klären“ mit Streitfallansicht, Dokumentliste und gesperrtem Betrag; Kennzeichnung in Gegenbericht und Partnerportal.
  - Ereignisse/Outbox: Streitfall eröffnet/entschieden; Benachrichtigung der Gegenseite.
- **Detailverträge vorab:** Zustandsautomat und Rollen (wer darf eröffnen, wer entscheiden); Fristen laut Vertrag; zulässige Dokumenttypen und Uploadprüfung; Abgrenzung zum Zahlungs-Streitstatus `DISPUTED` (Chargeback, Offener Punkt 25).
- **Akzeptanzkriterien:**
  1. Ein Streitfall ist ein eigener Vorgang mit eigener ID, Historie und Dokumenten, getrennt von der Abrechnungsposition (COM-004, Beschreibung).
  2. Der strittige Betrag ist in Statement, Gegenbericht und UI-16 gekennzeichnet und wird weder still gebucht noch verrechnet noch ausgezahlt; der unstrittige Rest läuft weiter (COM-004; COST-09).
  3. Erst die dokumentierte Entscheidung löst eine Korrekturposition bzw. die Freigabe aus; die Originalposition bleibt unverändert.
  4. Ein Dritter sieht den Streitfall nicht; Dokumente sind nur für die beiden Parteien abrufbar.
  5. Ein Monat mit offenem Streitfall ist nur mit dokumentierter Behandlung abschließbar.
- **Tests:** positiv: Streit über 5,00 EUR von 19,02 EUR, Rest wird ausgezahlt, nach Einigung Korrekturposition. negativ: Auszahlung des gesperrten Betrags, Entscheidung durch nicht berechtigte Rolle, Zugriff durch Mandant C. konkurrierend: Streitfalleröffnung parallel zum Auszahlungslauf – entweder Sperre greift vor Anweisung oder es entsteht eine Rückforderung, nie stille Auszahlung des strittigen Betrags.
- **Abhängigkeiten:**
  - intern: WP-M6c-20, WP-M6c-22, WP-M6c-23
  - extern: WP-M1-46, WP-M1-12, WP-M1-08 (Audit append-only, Objektberechtigung)
  - extern: WP-M4-21 (Uploadticket, Quarantäne und Malware-Prüfung für Dokumente)
- **Entscheidungen:** D-03 (Risikoträger), D-04 – blockieren nur Produktivschaltung der Entscheidungsregeln.
- **Größe:** M
- **Nicht im Umfang:** Chargeback-Abwicklung mit dem PSP (Offener Punkt 25); Supportfälle des Fahrgasts (M7).

### WP-M6c-29 – Unveränderliches Auditprotokoll und Nachweiskette der Partnerabrechnung

- **Ziel:** Jeder über Partnerabrechnungen verarbeitete Geschäftsvorfall ist unveränderlich protokolliert; ein Streitfall lässt sich lückenlos aus Beleg, Buchung und Ereigniskette bis zur Abrechnung nachweisen.
- **Anforderungen:** `LH-I.9-COM-012`
- **Liefergegenstände:**
  - Backend: Modul `documents-finance`; durchgängige Korrelations- und Ursprungsreferenzen Kundenbeleg → `journey`/`journey_leg` → Zahlungsereignis → `economic_event` → `journal` → `settlement_line` → `settlement_statement` → `payout_instruction`; Nachweisbericht je Anspruch bzw. Streitfall; Audit-Eintrag je zustandsändernder Clearingoperation in derselben Transaktion.
  - Datenbank: `audit_event` (Trigger `audit_immutable`), `economic_event` (Trigger `economic_immutable`), `outbox`, `inbox`, `saga_step`, Statushistorien; Invariante „Gegenereignis statt Änderung“ (Kap. 04 §4.5); Export in getrennte Integritäts-/Berechtigungsdomäne (Kap. 04 §4.3).
  - API: Nachweiskette zu einem Anspruch/Streitfall abrufen (Pfad Vorschlag unter `/v1/clearing/statements`); Export als asynchroner Job über `/v1/reports/jobs`.
  - Oberfläche: UI-16 Drilldown „Ursprungsereignisse“; UI-20 (Datenschutz/Audit/Support) für begründeten Auditzugriff.
- **Detailverträge vorab:** Liste der auditpflichtigen Geschäftsvorfälle; Schema des Nachweisberichts; Maskierung personenbezogener Felder; Aufbewahrungsklasse (D-11).
- **Akzeptanzkriterien:**
  1. Für einen Streitfall liefert der Nachweisbericht ohne Lücke: Kundenbeleg, Buchung/Segment mit Vertragsversion, Zahlungs- und Partnerereignisse in Reihenfolge, Anspruchspositionen samt Korrekturen, Statement und Auszahlung (COM-012).
  2. Jeder Geschäftsvorfall der Partnerabrechnung (Anspruch, Korrektur, Statement, Annahme, Auszahlung, Reversal, Streitentscheidung, manuelle Differenzbehandlung) hat genau einen `audit_event`; UPDATE/DELETE darauf wird von der Datenbank abgewiesen (COM-012, Beschreibung).
  3. Eine späte Gutschrift nach Periodenschluss lässt das Ursprungsjournal unverändert und ist mit Ursprungsbezug nachvollziehbar (PH-AT-015).
  4. Der Nachweisbericht enthält keine Daten Dritter und keine internen Aufwände der Gegenseite.
  5. Jeder lesende Zugriff auf Nachweiskette und Auditdaten über UI-20 erfordert einen dokumentierten Zweck und wird selbst protokolliert; fehlender Zweck oder abgelaufener Zugriff führt zur Ablehnung (Zustände Kap. 06 §6.9).
  6. Ein Audit-Eintrag ohne zugehörigen Geschäftsvorfall und ein Geschäftsvorfall ohne Audit-Eintrag werden von einem Konsistenzprüflauf gemeldet.
- **Tests:** positiv: Nachweisbericht zum Streitfall aus WP-M6c-28. negativ: Mutation von `audit_event`; Abruf durch nicht beteiligten Mandanten; Operation ohne Audit (Architekturtest). konkurrierend: Geschäftsvorfall und Audit in einer Transaktion – Abbruch nach dem ersten lässt keines von beiden zurück. Referenztests: Beitrag PH-AT-015.
- **Abhängigkeiten:**
  - intern: WP-M6c-20, WP-M6c-22 (`payout_instruction` als Kettenende), WP-M6c-23, WP-M6c-28
  - extern: WP-M1-12, WP-M1-46, WP-M1-14 (Audit append-only und Sicherheitsereignisse, Outbox/Inbox, UI-20 Basis)
  - extern: WP-M4-33, WP-M8c-27 (Kostenereignis/Journal, Berichtsjobs)
- **Entscheidungen:** D-11 (Aufbewahrung, Legal Hold), D-06 (Archiv/Export) – blockieren nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Revisionssicheres Langzeitarchiv und GoBD-Nachweis (D-06, M8); allgemeines Plattform-Audit (M1).

### WP-M6c-30 – UI-16 Arbeitsplatz Clearing und Abgleich

- **Ziel:** Die Finanzrolle bearbeitet Partnerabrechnung, Zahlungsabgleich und Abschluss in einem Arbeitsplatz mit durchgängigem Drilldown und klar getrennten Zuständen. Die fachlichen Teilansichten aus WP-M6c-19, WP-M6c-21, WP-M6c-23, WP-M6c-24 und WP-M6c-26 bis WP-M6c-29 werden dort jeweils als eigenständig abnehmbare Seite im Finanzbereich geliefert; dieses WP führt sie zum Arbeitsplatz UI-16 mit gemeinsamer Navigation, Kopfzeile, Drilldown und Zustandskatalog zusammen.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Oberfläche: UI-16 (Clearing/Abgleich; primäre Aktion „Gegenbericht / Differenz klären“) im Operator-Web gemäß Kap. 06 §6.6: Tabs „Partnerabrechnung“, „Zahlungsabgleich“, „Abschluss“; Zeile mit Leg, Leistendem, Verkäufer, Tarifumsatz, Provision, Korrekturen, Sollauszahlung, Zahlungsstand; Drilldown Kundenbetrag → Segmentanspruch → PSP-Transfer → Kontoabgleich; getrennte Status „Kundenerstattung ausstehend“ und „Rückholung vom Betreiber ausstehend“; Periodenabschluss mit offenen Differenzen und freigegebener Behandlung; geschlossene Monate schreibgeschützt. Leere-/Fehler-/Rechtezustände: Unbekannter Transfer, Chargeback, geschlossene Periode (Kap. 06 §6.9).
  - Oberfläche: Kopfzeile mit Rechtsträger, Periode, Währung, Datenstand/Regelversion; Tabellenalternative und Tastaturbedienung (Kap. 06 §6.1).
  - API: nutzt `/v1/clearing/statements`, `/v1/ledger/exports`, `/v1/reports/jobs`, `/v1/reports/jobs/{id}`.
- **Detailverträge vorab:** Bildschirmspezifikation UI-16 mit Feld-zu-API-Zuordnung; Rechtematrix je Aktion; Texte der Zustände.
- **Akzeptanzkriterien:**
  1. Von einer Statementzeile ist jeder Drilldown-Schritt bis zum PSP-Transfer und Kontoabgleich erreichbar und zeigt dieselben IDs wie die API.
  2. Partnerkosten oder Margen erscheinen nicht ohne gesonderte Freigabe.
  3. Ein veralteter Stand führt bei Annahme zu einem sichtbaren 412-Konflikt ohne stilles Überschreiben (UI-Abnahme Kap. 06 §6.9).
  4. In einem geschlossenen Monat sind alle schreibenden Aktionen deaktiviert und begründet.
  5. Der Arbeitsplatz ist per Tastatur und Screenreader bedienbar; Status sind nie nur farblich kodiert.
  6. Leere Zustände (kein Statement im Monat, keine Differenz), Ladefehler und fehlende Rechte haben je einen eigenen, verständlichen Zustand; die drei Katalogzustände „Unbekannter Transfer“, „Chargeback“ und „geschlossene Periode“ sind je mit einem Testfall belegt.
  7. Der Tab „Abschluss“ listet offene Differenzen und Streitfälle mit der freigegebenen Behandlung; ohne diese ist der Abschluss gesperrt.
- **Tests:** positiv: Monat mit Rechenbeispiel A von Statement bis Auszahlung durchklicken. negativ: Rolle ohne Finanzscope (403), fremder Rechtsträger (404), Schreibversuch in geschlossener Periode. konkurrierend: zwei Bearbeiter nehmen dasselbe Statement an – einer erhält 412.
- **Abhängigkeiten:**
  - intern: WP-M6c-23, WP-M6c-26, WP-M6c-28
  - extern: WP-M1-43, WP-M1-07 (Web-Designsystem und Anwendungsrahmen, Rollenmodell)
  - extern: WP-M4-38, WP-M4-44 (Finanzbereich im Operator-Web (UI-14, UI-15) als Rahmen)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** UI-15 TCO/Deckungsbeitrag (M4/M8); Partnerportal (WP-M6c-31).

### WP-M6c-31 – Partnerportal: eigene Forderungen, Gutschriften, Belastungen, Status

- **Ziel:** Ein Partner sieht im eigenen Portal seine Forderungen, Gutschriften, Belastungen und den Abrechnungsstatus – ausschließlich eigene Clearingdatensätze und keine Konditionen anderer Partner.
- **Anforderungen:** `LH-I.9-COM-007`
- **Liefergegenstände:**
  - Backend: Modul `documents-finance`; empfängergebundene Leseprojektion der Clearingdaten je Partner (eigene Gegenberichte und ausdrücklich freigegebene Leistungsreferenzen, Kap. 03 §2); serverseitiger Filter auf `creditor_ref`/`debtor_ref` des angemeldeten Partners; Feldfreigabe ohne Sätze, Provisionen und Margen anderer Vertragsbeziehungen.
  - Datenbank: `settlement_line`, `settlement_statement`, `payout_instruction`, `dispute_case` in der Sicht des eigenen Mandanten; keine dynamische „alle Kooperationspartner“-RLS-Policy, keine `SECURITY DEFINER`-Allzweckfunktion (Kap. 04 §4.6).
  - API: `/v1/clearing/statements` mit Partnerscope; Filter dürfen die Autorisierung nur weiter einschränken (Kap. 05 §5.1); fremde Objekte → 404; Export nur eigener Daten über `/v1/reports/jobs`.
  - Oberfläche: Partnersicht von UI-16 (Forderungen, Gutschriften, Belastungen, Status, Auszahlungen, offene Streitfälle); Zugang über die eigene Mandantenanmeldung.
  - Konfigurationsparameter: `sharing_visibility_level` (Partner-Freigabegrad, „Grundsatz Datenminimierung“).
- **Detailverträge vorab:** Feldliste der Partnerprojektion; Zugangsmodell für Partner ohne eigenen Mandanten (Offener Punkt 14); Verhalten nach Vertragsende (Leserechte laut Auslaufregel).
- **Akzeptanzkriterien:**
  1. Partner B sieht alle eigenen Forderungen, Gutschriften, Belastungen und deren Abrechnungsstatus vollständig und betragsgleich zum Gegenbericht (COM-007, Beschreibung).
  2. Partner B sieht keinen Clearingdatensatz von Partner C und keine Konditionen (Sätze, Provisionen, Verträge) aus der Beziehung Plattform↔C; Detail-, Listen-, Such-, Export- und Reportendpunkt verhalten sich gleich (COM-007).
  3. Kleine Aggregationen oder Summen lassen keinen Rückschluss auf Konkurrenzkonditionen zu (keine partnerübergreifenden Summen in der Partnersicht).
  4. Nach Vertragsablauf bleiben nur die in der Auslaufregel erlaubten Gegenberichtsrechte bestehen (COST-09).
  5. Die Partnersicht ist rein lesend bis auf „Statement annehmen“ und „Streitfall eröffnen“; jeder Export wird mit Empfänger protokolliert, und Cache sowie Event-Subscription liefern keine weitergehenden Daten als die API (PH-AT-002).
  6. Die Partnersicht hat eigene Leer-, Fehler- und Rechtezustände und ist per Tastatur und Screenreader bedienbar.
- **Tests:** positiv: Partner B mit zwei Statements. negativ: bekannte C-UUID über Detail, Liste, Export, Report; manipulierter Filter; Zugriff nach Widerruf. konkurrierend: Widerruf des Zugangs parallel zum laufenden Export – Export bricht ab oder enthält nur zulässige Daten. Referenztests: Beitrag zu PH-AT-002 (Report-Download), E2E-094 („keine gegenseitige Stammdatenfreigabe“).
- **Abhängigkeiten:**
  - intern: WP-M6c-18 (Leserechte nach Vertragsablauf), WP-M6c-22, WP-M6c-23, WP-M6c-28
  - extern: WP-M1-01, WP-M1-07, WP-M1-08, WP-M1-11 (Tenantkontext, Rollen- und Objektberechtigung, Isolation außerhalb der Datenbank)
  - extern: WP-M6b-09, WP-M6b-05 (minimierte Föderationsprojektionen und Grantprüfung)
- **Entscheidungen:** D-02 (Isolationsstufe) – folgt dem Favoriten; D-11 (Zugriff nach Vertragsende) – nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Schreibende Clearingaktionen der Finanzrolle (WP-M6c-30); Partner-API für maschinellen Abruf über `/v1/webhook-subscriptions` hinaus (M6b).

### WP-M6c-32 – Gate-Nachweis M6c und semantische Contract-Fälle

- **Ziel:** Alle Anforderungen der Einheit haben einen grünen Test, die geldbezogenen Referenztests des Meilensteins sind mit Build und Evidenz ausgeführt, und jede Zahlungs- und Clearingfähigkeit ist bis zu ihrem Sandbox-Nachweis deaktiviert.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Tests: Akzeptanzsuite unter `tests/acceptance` für PH-AT-005, PH-AT-008, PH-AT-010, PH-AT-014, AT-16, E2E-093 sowie die M6c-Anteile (Geld-, Vertrags- und Clearingteil) von PH-AT-002, PH-AT-003, PH-AT-006, PH-AT-007, PH-AT-009, PH-AT-013, PH-AT-015, E2E-085, E2E-086, E2E-087, E2E-089, E2E-090, E2E-094, E2E-095, E2E-104; Abnahmefälle COST-03, COST-04, COST-09, COST-10 (Kap. 03 §7).
  - Tests: semantische Contract-Fälle aus Kap. 05 §5.8 mit Geldbezug als CI-Pflicht: „SCA länger als Hold“, „100 identische Webhooks“, „EUR-Rundungsrest“, „geschlossene Periode“.
  - Nachweis: Einträge in `e2e.csv` ausschließlich aus Testläufen mit Build-ID; Evidenz mit `source_key[]`, Vertrags-/Tarif-/Steuer-/Konfigurationsrevision, Seed, Rollen und Mandanten (Kap. 08 §8.4); UI-Abnahme „Fahrt mit zwei Betreibern und Teilstorno vollständig per Tastatur/Screenreader“ (Kap. 06 §6.9).
  - Betrieb: Feature-Schalter je Zahlungsart, je Clearingfunktion und je Partner; Freigabeliste der offenen Entscheidungen D-01, D-03, D-04, D-06 mit Stand „Sandbox“ oder „Produktiv“.
- **Detailverträge vorab:** Testplan mit Zuordnung Test → WP → `source_key`; Evidenzformat aus M0.
- **Akzeptanzkriterien:**
  1. Jede der 15 MUSS-Anforderungen hat mindestens einen positiven, einen negativen und – wo Geld oder Datentrennung betroffen ist – einen konkurrierenden grünen Test; jede der 8 SOLL-Anforderungen ist grün oder als Abweichung mit Begründung und AG-Freigabe dokumentiert.
  2. PH-AT-005, PH-AT-014, AT-16 und E2E-093 sind mit Build-ID, Evidenz und Entscheidung in `e2e.csv` eingetragen.
  3. COST-03 reproduziert 32,10 Einzug, 28,53 Auskehrung, 3,57 Provision brutto und 10,70 Kundennettobelastung nach B-Storno; jeder Journalabschluss ist ausgeglichen.
  4. Ohne Sandbox-Nachweis ist keine Zahlungs- oder Clearingfähigkeit in einer Produktivkonfiguration aktivierbar.
  5. Keine offenen Mängel der Klasse A (u. a. Doppelbelastung, Datenoffenlegung).
  6. Eine Zuordnungstabelle Gate-Test → WP ist geführt; die Gate-Tests ohne Geld- oder Clearingbezug (PH-AT-016, AT-14, E2E-088, E2E-092) sind darin ausdrücklich M6a bzw. M6b zugewiesen, damit kein Test des Meilensteins unzugeordnet bleibt.
  7. Jede Kap.-04-§4.5-Invariante mit Geldbezug (Gegenereignis statt Änderung, Journal ausgeglichen, genau eine Kostenwirkung, PSP-Retry exakt gleiche Operation, keine Cross-Tenant-Referenz) ist durch einen Negativtest mit mindestens zwei parallelen Transaktionen belegt.
- **Tests:** die oben genannten Suiten; zusätzlich Regressionslauf der M2-Zahlungstests (AT-15) gegen den erweiterten Zahlungsautomaten.
- **Abhängigkeiten:**
  - intern: WP-M6c-04, WP-M6c-05, WP-M6c-06, WP-M6c-07, WP-M6c-08, WP-M6c-09, WP-M6c-10, WP-M6c-11, WP-M6c-12, WP-M6c-13, WP-M6c-14, WP-M6c-15, WP-M6c-16, WP-M6c-17, WP-M6c-18, WP-M6c-19, WP-M6c-20, WP-M6c-21, WP-M6c-22, WP-M6c-23, WP-M6c-24, WP-M6c-25, WP-M6c-26, WP-M6c-27, WP-M6c-28, WP-M6c-29, WP-M6c-30, WP-M6c-31
  - extern: WP-M0-22, WP-M0-32 (Akzeptanztestgerüst, Evidenzformat, Nachverfolgungs-CI für `arbeitspakete.csv` und `e2e.csv`)
  - extern: WP-M6a-13, WP-M6a-14, WP-M6a-22, WP-M6a-03 (Saga, Storno und Sandbox-Partner für die gemeinsamen Referenztests)
  - extern: WP-M6b-05, WP-M6b-07, WP-M6b-09 (Grants, Widerruf und Projektionen für PH-AT-003 und die Isolationstests)
- **Entscheidungen:** D-01, D-03, D-04, D-06 – blockieren nicht das Gate (Sandbox), aber jede Produktivschaltung von Einzug, Auskehrung und Faktura.
- **Größe:** M
- **Nicht im Umfang:** Last- und Wiederanlaufnachweise des Gesamtmeilensteins; Gate-Nachweise von M6a und M6b.

## Offene Punkte

1. **Guthaben und Pässe ohne Datenmodell (`LH-6.10-PAY-009`).** Kap. 04 §4.4 nennt für PAY nur `tariff_revision`, `tariff_rule`, `fare_component`, `voucher_redemption`, `refund_case`, `payment_event`. Für Guthabenkonto, Guthabenbewegung und Pass gibt es weder Tabelle noch Endpunkt noch Zustandsautomat; auch Erwerb, Aufladung, Verfall und Auszahlung von Restguthaben sind nicht beschrieben. Tabellen- und Pfadnamen in WP-M6c-10 sind Vorschläge.
2. **Gutscheindefinition fehlt (`LH-6.10-PAY-008`).** Nur `voucher_redemption` ist benannt; die Regeltabelle (Frist, Budget, Zielgruppe, Mehrfacheinlösung), die Kriterien einer „Zielgruppe“, der Finanzierer eines Gutscheins und ein Endpunkt fehlen in Kap. 04 §4.4 und Kap. 05 §5.7. `kataloge.csv` enthält keinen Gutscheinparameter.
3. **Tarifberechtigungen (`LH-6.10-PAY-010`).** Kap. 05 §5.9 definiert `entitlement_refs`, aber nicht, wo Berechtigungen gespeichert, wie Nachweise geprüft und welche externen APIs (Schülerregister, Verbund, Arbeitgeber) angebunden werden. Die Abgrenzung zur „Prüfung externer Ticketberechtigungen“ in M6a ist festzulegen.
4. **Freunde-werben-Freunde (`LH-6.10-PAY-011`).** Das Pflichtenheft enthält keinerlei Ausarbeitung: Prämienform, auslösendes Ereignis, Betrugsgrenzen, Auszahlungssperren und die Definition „dasselbe verifizierte Konto“ sind offen; kein Parameter im Katalog.
5. **Rechnungs- und Sammelzahlmodelle (`LH-6.10-PAY-012`).** Kap. 03 §2 nennt nur das Objekt „Rechnungs-/Gutschriftenposition“ ohne Tabellennamen. Rechnungskunde, berechtigte Reisende, Kreditrahmen, Zahlungsziel, Endpunkt und der zulässige Detailgrad der Einzelfahrt gegenüber einem Arbeitgeber (Datenschutz) sind nicht spezifiziert.
6. **Trinkgeld (`LH-6.10-PAY-013`).** Kap. 03 §4.1 führt „freiwilliges Trinkgeld“ nur als Preiskomponente. Offen sind Zeitpunkt, Begünstigter (Fahrer oder Betreiber), steuerliche und provisionsseitige Behandlung, Erstattbarkeit, Auskehrung und ob Trinkgeld im Mehrbetreiberfall je Segment vergeben wird.
7. **Zahlungsarten je Dienst (`LH-6.10-PAY-016`).** `kataloge.csv` enthält keinen Parameter für zulässige Zahlungsarten. Die Erfassung der Barzahlung im Fahrerclient (Ereignistyp für `/v1/driver/events`), die Kassenabrechnung des Fahrers und die Behandlung von Barzahlung im Clearing mehrerer Betreiber sind nicht beschrieben.
8. **Stornierungs- und Erstattungsregeln (`LH-6.10-PAY-006`).** Für betreibereigene Stornofristen, Gebühren und Grundcodes existiert kein Katalogparameter (nur `partner_liability_policy` auf Vertragsebene und `no_show_grace_min`). Die Regelmatrix Zeitpunkt × Grund × Zahlungslage und die Präzedenz gegenüber der Partnerregel benötigen einen Beschluss (D-03, D-04).
9. **Quittungsinhalt (`LH-6.10-PAY-007`).** „Gesetzlich erforderliche Angaben je Zahlungsmodell“ sind nicht aufgezählt; das Steuer-/Belegmodell ist laut Kap. 03 §8 und D-06 offen. Ein Endpunkt für Quittungen und eine Tabelle fehlen.
10. **Kulanzgutschriften (`LH-6.10-PAY-015`).** Die „berechtigte Rolle“, Betragsgrenzen, eine etwaige Vier-Augen-Schwelle, die Form (Guthaben oder Erstattung) und der Finanzierer sind nicht benannt. `approval_stage_thresholds` und `expense_claim_limit` beziehen sich laut Katalog auf Belege/Ausgaben und werden hier nicht stillschweigend übertragen.
11. **Phasenüberschneidung `LH-6.10-PAY-005`.** Der Umsetzungsplan legt den PSP-Adapter bereits in M2, die Anforderung ist P2. WP-M6c-04 führt die formale Abnahme durch; der Bau liegt überwiegend in M2. Die Originalphase bleibt unverändert.
12. **`settlement_line` im Kern-DDL unvollständig.** Kap. 03 §2 verlangt Leistenden, Verkäufer, Erlös, Steuer, Provision, Anspruchsgrund und Abrechnungsstatus sowie „mehrere Anspruchskomponenten, jede separat eindeutig“. `kernschema.sql` enthält nur `payable_minor`, `creditor_ref`, `debtor_ref` und `UNIQUE(tenant_id,event_id,leg_id,creditor_ref,revision)`; zwei Komponenten desselben Gläubigers am selben Segment sind damit nicht abbildbar. Außerdem ist `leg_id` Pflicht, obwohl Subunternehmerleistungen nach Schichten oder Pauschalen (`LH-I.9-COM-006`) kein Segment haben.
13. **Uneinheitliche Tabellennamen im Clearing.** Kap. 03 §2 nennt `dispute_case`, `reconciliation_run`, `payment_order`, `tax_rule_revision`; Kap. 04 §4.4 nennt für Clearing nur `settlement_statement`, `settlement_reconciliation`, `payout_instruction`, `transfer_reversal`. Das Verhältnis `reconciliation_run` ↔ `settlement_reconciliation` und die Modulzuordnung von `dispute_case` sind zu klären (`LH-6.10-PAY-014`, `LH-I.9-COM-004`).
14. **Endpunkte und Oberfläche des Clearings.** Kap. 05 §5.7 nennt einzig `/v1/clearing/statements`. Pfade für Verträge, Streitfälle, Auszahlungen, Abgleichläufe, Rechnungen und Partnerportal fehlen. Für das „eigene Portal“ des Partners (`LH-I.9-COM-007`) gibt es keine UI-ID; offen ist auch, wie Partner ohne eigenen Mandanten auf der Plattform Zugang erhalten.
15. **Kooperationsvertrag (`LH-I.9-COM-001`).** Kap. 02 §5 definiert den Lebenszyklus, aber `partner_contract_version` hat keine Statusspalte und ist per Trigger unveränderlich; Genehmigungsgebiet, Servicezeiten, Leistungsarten und Ansprechpartner haben kein JSON-Schema. Die Zuständigkeit zwischen M6b (MUL, Grants, UI-18) und M6c (Vertragsinhalt) ist im Meilensteinplan nicht geschnitten.
16. **Auslaufregel (`LH-I.9-COM-011`).** Die „dokumentierte Auslaufregel“ ist Vertragsinhalt; ein Standard für Fristen, erlaubte Aktionen und das Ende des Restabwicklungsgrants ist nicht festgelegt, ebenso wenig das Verhalten für gehaltene, aber noch nicht bestätigte Reisen zum Ablaufzeitpunkt.
17. **Subunternehmerleistung (`LH-I.9-COM-006`).** Es gibt keine Tabelle für den bestätigten Leistungsnachweis und keine Festlegung, wer „tatsächlich bestätigt“ und welche Kilometerklasse abrechnungsrelevant ist.
18. **Steuermodell (`LH-I.9-COM-008`).** Steuersätze, Vorsteuerabzug und Rechnungsstellungspflichten sind ausdrücklich offene AG-Entscheidungen (Kap. 03 Kopf, §8; D-06). `tax_rule_revision` ist im Kern-DDL nur ein Textfeld an `economic_event`. Eine Musterrechnung kann bis zum Beschluss nur mit Testregeln abgenommen werden.
19. **Drittplattformdaten (`LH-I.9-COM-009`).** Quelle, Format, Lieferrhythmus und Toleranzfenster der Drittplattformdaten sind nicht spezifiziert und hängen von den Pilotpartnern ab (D-08).
20. **Gegenberichte (`LH-I.9-COM-003`).** Stichtag, Zeitzone der Monatsgrenze, Behandlung nach Stichtag eintreffender Korrekturen und das Format des Gegenberichts sind nicht festgelegt; `finance_close_day` ist nur als „Datum/Regel“ beschrieben.
21. **Auditprotokoll (`LH-I.9-COM-012`).** Offen ist, ob das allgemeine `audit_event` genügt oder ein eigenes Finanzprotokoll verlangt ist, wie der „Export in getrennte Integritäts-/Berechtigungsdomäne“ (Kap. 04 §4.3) technisch umgesetzt wird und welche Aufbewahrungsfrist gilt (D-11).
22. **`LH-I.9-COM-010` fehlt in dieser Einheit.** Abschnitt I.9 hat zwölf Anforderungen, die Einheit enthält elf; laut Kap. 08 §8.3 ist eine davon P3. Sie ist hier nicht zugeordnet und muss in der P3-Einheit erscheinen.
23. **`LH-6.10-PAY-014` ist SOLL, trägt aber MUSS-Nachweise.** Der Abgleich ist Voraussetzung für COST-04, PH-AT-014 und die Abnahme von `LH-I.9-COM-003`/`LH-I.9-COM-012`. Eine SOLL-Abweichung wäre faktisch nicht ohne Folgen für MUSS-Anforderungen möglich.
24. **Keine Zahlungs- oder Clearingkennzahlen im Katalog.** `kataloge.csv` enthält unter den 48 Kennzahlen keine zu Zahlung, Erstattung, Differenzalter oder Clearing; die in Kap. 03 §4.3 verlangten Differenzmerkmale (Betrag, Alter, Grundcode) sind keine Katalogkennzahlen. In dieser Einheit werden daher keine Kennzahlen geliefert.
25. **Chargeback und Zahlungsstreit.** Kap. 03 §4.2 nennt `DISPUTED` als separaten Streitstatus der Zahlung und Kap. 06 §6.9 „Chargeback“ als UI-16-Zustand, beschreibt aber keinen Ablauf, keinen Risikoträger (D-03) und keine Verbindung zum Partnerstreitfall (`LH-I.9-COM-004`).
26. **Finanzierer von Rabatten und Guthaben im Mehrbetreiberfall.** Kap. 03 §4.1 verlangt je Rabatt Finanzierer und Verteilungsschlüssel, nennt aber keine Standardregel dafür, wer einen Plattformgutschein, eine Kulanzgutschrift oder Kundenguthaben gegenüber den Betreibern trägt (`LH-6.10-PAY-008`, `LH-6.10-PAY-009`, `LH-6.10-PAY-015`, `LH-I.9-COM-002`).
27. **Fremdwährung.** Kap. 03 §2.1 verlangt für P2 Wechselkursquelle, Kurszeit und Kursdifferenzbuchung; keine Anforderung dieser Einheit fordert das ausdrücklich. Die Einheit plant mit einer Währung je Checkout und je Statement; Mehrwährungsfähigkeit ist nicht enthalten.
28. **Ausfall-/Liquiditätsregel.** Kap. 03 §5 verweist auf eine „freigegebene Ausfall-/Liquiditätsregel“, die entscheidet, ob die Plattform eine Kundenerstattung vorfinanziert, wenn die Rückholung beim Partner scheitert. Diese Regel existiert nicht als Parameter und ist Teil von D-03.
29. **Verbundtarif nicht modelliert (`LH-I.9-COM-002`).** Die Anforderung nennt „Verbundtarife“; Kap. 03 §4.1 kennt nur den „gemeinsamen Verbundrabatt“ als Preiskomponente. Wem eine Verbundtarifrevision gehört (`tariff_revision` eines Mandanten oder Vertragsinhalt), wie sie beidseitig freigegeben wird und wie sie sich zu den Einzeltarifen der Betreiber verhält, ist nicht beschrieben. WP-M6c-19 bildet den Verbundtarif als Rabatt-/Verteilungsregel der Vertragsversion ab.
30. **Benutzerkanäle und Format der Quittung (`LH-6.10-PAY-007`).** Welche Kanäle „Benutzerkanäle“ umfassen (PWA, E-Mail, Callcenter, spätere native App), ob der Beleg als Anhang oder nur als geschützter Abruf versendet werden darf und welches barrierefreie Format verlangt ist, legt das Pflichtenheft nicht fest.
31. **PSP-Gebühr.** Rechenbeispiel A bucht 0,50 EUR PSP-Aufwand bei P; Quelle (Settlement-Datei oder Vertragssatz), Zeitpunkt und Träger der Gebühr bei Erstattung sind nur als Testannahme genannt und Teil von D-03.
32. **Reihenfolge Auskehrung ↔ Statementannahme.** Kap. 03 §4.3 führt `ACCEPTED → PAYOUT_PENDING`, Rechenbeispiel A und PH-AT-014 setzen dagegen eine Auskehrung kurz nach dem Einzug voraus (PSP-Transfer). Ob der PSP-Transfer beim Capture bereits als „Auszahlung“ im Sinne des Automaten gilt oder erst nach angenommenem Monatsstatement ausgezahlt wird, ist mit D-03 festzulegen; WP-M6c-22 baut beide Auslöser konfigurierbar über `partner_settlement_model`.
33. **Callcenter als Auslöser.** UI-06 (Callcenter mit Vertretungsprüfung) liegt laut Meilensteinplan in M7, Kap. 06 §6.3 verlangt aber, dass das Callcenter dieselbe Checkout-API nutzt und die Zustimmung dokumentiert. WP-M6c-05, WP-M6c-08, WP-M6c-14, WP-M6c-15 und WP-M6c-16 liefern deshalb nur die API-Seite; der Nachweis an der Maske UI-06 ist in M7 zu führen. UI-20 steht aus M1 nur als Basis bereit; die Dialoge für Kulanzgutschrift (WP-M6c-15) und Nachweiskette (WP-M6c-29) ergänzen sie in M6c.
34. **Benachrichtigungstexte bei Zahlung und Erstattung (`LH-6.10-PAY-006`).** Inhalt, Kanal und Pflichtcharakter der Kundennachricht bei Betreiberstorno, „Erstattung beauftragt“ und „Zahlung in Klärung“ sind nur in Kap. 06 §6.4 als UI-Wortlaut skizziert; eine Nachrichtenvorlage fehlt.
35. **Guthabenverfall während offener Reservierung (`LH-6.10-PAY-009`).** Das Pflichtenheft regelt nicht, ob ein während Hold oder SCA ablaufendes Guthaben noch verbraucht werden darf.
36. **Reihenfolgekonflikt: Zyklen innerhalb des parallelen Meilensteins M6 (M6c ↔ M6a).** Bei der Auflösung der externen Abhängigkeiten auf WP-IDs zeigen mehrere Abhängigkeiten in beide Richtungen (Gegenrichtung laut Übersichtstabelle in `M6a-foederation-reiseketten.md`, dort Offener Punkt 28). Betroffen: WP-M6c-01 ↔ WP-M6a-01 (wechselseitige Detailverträge); WP-M6c-06 ↔ WP-M6a-23 (Preiskomponenten ↔ segmentbezogene Preisaufstellung); WP-M6c-07 ↔ WP-M6a-14 (Capture-Regel ↔ Confirm-Saga; zusätzlich WP-M6c-07 → WP-M6a-15/-16 → WP-M6a-14 → WP-M6c-07); WP-M6c-08 ↔ WP-M6a-11 (UI-03 Mehrparteien-Checkout ↔ Angebotsdarstellung); WP-M6c-11 ↔ WP-M6a-24 (Tarifberechtigung ↔ Ticketprüf-Adapter); WP-M6c-14 ↔ WP-M6a-22 (Erstattungsregeln ↔ Cancel-Saga; über WP-M6a-14 → WP-M6c-14 → WP-M6a-22 → WP-M6a-14 auch die Confirm-Saga); WP-M6c-20 → WP-M6a-25 → WP-M6c-19/-23/-30 mit WP-M6c-23 → WP-M6c-20 sowie direkt WP-M6c-23 ↔ WP-M6a-25 (Anspruchsledger/Clearingdatensätze ↔ Ausführungsnachweis). Die Abhängigkeiten wurden nicht verbogen. Aufzulösen ist je Paar über vertragsbasierten Bau gegen Stub/Port aus den Detailverträgen (WP-M6a-01, WP-M6c-01) mit festgelegter Lieferrichtung (Port-/Ereignislieferant zuerst, Aufrufer danach) oder durch Teilung des WPs in Port- und Integrationsanteil. Zusätzlich (kein Verstoß gegen die Reihenfolgeregel, aber Abweichung von der angenommenen Quelle): Die in WP-M6c-23 und WP-M6c-29 als M4-Fähigkeit geführten „Berichtsjobs“ liefert M4 nicht (dort ausdrücklich ausgeschlossen), sondern erst WP-M8c-27 im parallelen Strom M8; die in WP-M6c-04 und WP-M6c-26 als M1-Fähigkeit geführten „Maskierung von Diagnosedaten“ und „Alarmgrundlage“ liefern WP-M5-06 bzw. WP-M3-52 (frühere Meilensteine, unkritisch); WP-M6c-08 hängt für die Callcenter-Maske UI-06 an WP-M7b-35/-17/-19 im parallelen Strom M7 (vgl. Offener Punkt 33).
