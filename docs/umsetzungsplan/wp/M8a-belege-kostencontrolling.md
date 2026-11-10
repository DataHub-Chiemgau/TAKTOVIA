# M8a – Belege, Eingangsrechnungen und Kostencontrolling (Finanzstrom)

- **Meilenstein:** M8 – Finanzen, Controlling, ETA, Governance; Strom „Finanzen“ (setzt M5 voraus; die Ströme „Daten und Steuerung“ sowie „Plattform“ desselben Meilensteins sind eigene Einheiten)
- **Phase:** P2 (25 MUSS, 8 SOLL; Phase und Priorität jeder Anforderung unverändert)
- **Anzahl Anforderungen:** 33 (DOC 16, FCO 17)
- **Anzahl WPs:** 39 (33 mit Anforderungen, 6 Enabler)

**Ziel:** Nach M8a verarbeitet die Plattform Eingangsrechnungen einschließlich strukturierter E-Rechnungen (XRechnung, freigegebene ZUGFeRD-Profile) mit führendem XML, versionierten Steuerkennzeichen, mehrstufiger Freigabe, Gegenbelegen, Aufbewahrungsregeln, revisionsfähigem Archivexport und wiederholsicherem Buchungssatzexport. Das Kostencontrolling wird vom P1-Grundausbau (M4) zum vollständigen Fahrzeug- und Ressourcenmodell erweitert: Abschreibung, Leasing, Versicherung, Wartung, Pflichtnachweise, Werkstatt, Lager, Reifen, Depots, Ladekosten, Kostensplit, Budgets, Kontrollberichte und Periodenabschluss mit Restatement-Ansicht. Alle Erweiterungen nutzen den Buchungskern aus M4 (`economic_event`, `journal`, `allocation_set`, `fiscal_period`); es entsteht kein zweiter Buchungsweg und keine zweite Kostenwirkung.

**Gate-Anteil M8a (Umsetzungsplan §4, Gate M8):** E2E-070 (Verbrauchsabweichung). Die übrigen Gate-Tests des Meilensteins (PH-AT-022, E2E-074 bis E2E-079, E2E-099, E2E-101 bis E2E-103, E2E-105, E2E-106) gehören zu den anderen M8-Einheiten. Zusätzlich schließt M8a die in M4 ausdrücklich offen gelassenen Testanteile: E2E-067 und der XML-Teil von PH-AT-019 (E-Rechnung), die Vollform von E2E-065 und PH-AT-012 (echter Tankkartenimport) sowie die Restatement- und Plan/Ist-Anteile von PH-AT-015 (siehe OP-01).

**Lesehinweise:**

- Kurz-IDs in Klammern hinter Akzeptanzkriterien (z. B. DOC-014) verweisen auf den `source_key` des jeweiligen WP.
- Tabellen aus `kernschema.sql` und Kap. 04 §4.4 sowie Pfade aus `openapi.yaml` und Kap. 05 §5.7 sind mit exaktem Namen genannt. Wo das Pflichtenheft weder Tabelle noch Pfad nennt, steht ausdrücklich **Arbeitsname** bzw. „neuer Pfad“; beides wird in WP-M8a-01 festgelegt (OP-02, OP-03).
- Ereignisnamen sind Arbeitsnamen und werden in WP-M8a-01 verbindlich festgelegt.
- Beträge in Beispielen sind Testannahmen aus Kap. 03 und Kap. 08 §8.5, keine Steuer- oder Preisvorgaben.
- Größen: S bis 1, M bis 3, L bis 6 Personenwochen.
- WP-IDs sind stabil: Nachbareinheiten (M8c, M8d) verweisen bereits auf WP-M8a-04, -06, -11, -14, -18, -24, -25, -34, -35 und -36. Das in der Prüfung ergänzte Enabler-Paket WP-M8a-39 (versionierte Kostensätze) ist deshalb angehängt und nicht einsortiert; fachlich liegt es vor WP-M8a-21, WP-M8a-26 und WP-M8a-30.
- EXTERN-Abhängigkeiten auf M7c und M6c widersprechen der Aussage des Umsetzungsplans, M6, M7 und M8 seien voneinander unabhängige P2-Ströme (OP-40). Abhängigkeiten auf M6c sind in dieser Datei ausdrücklich optional geschnitten (nur wirksam, wenn das Clearing im Mandanten aktiv ist).
- **Querschnittskriterien** – gelten für jedes WP zusätzlich zu seinen eigenen Akzeptanzkriterien und werden in WP-M8a-38 gesammelt nachgewiesen:
  1. Jeder schreibende Befehl verlangt `Idempotency-Key` und, wo eine Revision existiert, `If-Match`; ein Konflikt antwortet 412 und überschreibt nicht still (Kap. 06 §6.9). Der Mandant stammt aus der signierten Identität, nie aus dem Request (Kap. 05 §5.5, §5.8).
  2. Jede neue Tabelle erfüllt die Vorgaben aus WP-M8a-02 (RLS über `current_tenant()`, zusammengesetzte FKs, Revision, Audit); jede fachliche Zustandsänderung erzeugt `audit_event` und, soweit andere Module sie brauchen, einen Outbox-Eintrag in derselben Transaktion (Kap. 03 §2.2).
  3. Jede Oberfläche liefert Leer-, Fehler- und Rechtezustand gemäß Kap. 06 §6.9 (UI-14: Dublette, XML-Konflikt, fehlende Kostenstelle; UI-15: Datenlücke, Nenner 0, fehlende Partnerrechte; UI-11: Frist überschritten, Freigaberolle fehlt; UI-20: fehlender Zweck, Legal Hold, abgelaufener Zugriff), ist vollständig per Tastatur und Screenreader bedienbar und kommt ohne Drag-and-drop-Zwang aus (Kap. 08 §8.4 Gate „UX“).
  4. Geld nur als ganzzahlige Minor Units mit `currency`, Mengen und Sätze als Dezimalstrings mit Einheit; Beträge unterschiedlicher Währungen werden nicht summiert (Kap. 03 §2.1, OP-36).
  5. Evidenz und Testdaten enthalten keine unnötigen Personen-, Bank- oder Lieferantendaten (Kap. 08 §8.4).

## Übersicht

| WP-ID | Titel | Anforderungen (Anzahl) | Größe | hängt ab von |
|---|---|---:|---|---|
| WP-M8a-01 | Detailverträge und Zustandsautomaten M8a | 0 (Enabler) | M | WP-M4-01, WP-M3-01, WP-M7c-01, WP-M5-31, WP-M8c-01, WP-M8d-01 |
| WP-M8a-02 | Migration der M8a-Tabellen mit RLS und Feldschutz | 0 (Enabler) | M | WP-M8a-01, WP-M1-01, WP-M1-12, WP-M4-22, WP-M4-33, WP-M4-36, WP-M7c-02 |
| WP-M8a-03 | Referenzfixtures Finanzen P2 und E-Rechnungs-Testkorpus | 0 (Enabler) | S | WP-M8a-01, WP-M4-02, WP-M0-22 |
| WP-M8a-04 | Kostenarten für Reise-, Park-, Maut-, Reinigungs-, Ersatzteil- und Werkstattkosten | 1 | S | WP-M8a-02, WP-M4-33, WP-M4-40, WP-M4-29, WP-M1-09 |
| WP-M8a-05 | Versionierte Steuerkennzeichen und Umsatzsteuersätze | 1 | M | WP-M8a-02, WP-M4-33, WP-M4-34, WP-M1-07, WP-M1-22, WP-M1-12 |
| WP-M8a-06 | Vollständiger Belegstatusautomat bis Export und Korrekturfall | 1 | M | WP-M8a-02, WP-M4-22, WP-M4-34, WP-M1-17, WP-M1-12 |
| WP-M8a-07 | Eingangsrechnung mit Kreditor, Fälligkeit, Steuerkennzeichen und Buchungsvorschlag | 1 | M | WP-M8a-04, WP-M8a-05, WP-M8a-06, WP-M4-21, WP-M4-22, WP-M4-32, WP-M4-33, WP-M4-38 |
| WP-M8a-08 | Lieferantenverwaltung mit freigegebenen Stammdaten und Kontodatenprüfung | 1 | M | WP-M8a-07, WP-M1-22, WP-M1-31, WP-M7c-50 |
| WP-M8a-09 | E-Rechnungsimport XRechnung/ZUGFeRD mit Validierung nach EN 16931 | 1 | L | WP-M8a-03, WP-M8a-07, WP-M4-21, WP-M4-23, WP-M4-24, WP-M4-27, WP-M1-15, WP-M1-38 |
| WP-M8a-10 | Führendes XML und Meldung von PDF/XML-Abweichungen | 1 | M | WP-M8a-09, WP-M4-27, WP-M4-34, WP-M4-38 |
| WP-M8a-11 | Freigabestufen nach Mandant, Betrag, Kostenstelle, Kostenart und Vertretung | 1 | M | WP-M8a-06, WP-M8a-07, WP-M4-34, WP-M1-07, WP-M1-08, WP-M1-20 |
| WP-M8a-12 | Zugriffsschutz für Rechnungsdaten nach Mandant, Rolle und Kostenstelle | 1 | M | WP-M8a-07, WP-M1-07, WP-M1-08, WP-M1-11, WP-M1-31, WP-M8b-25 |
| WP-M8a-13 | Gegenbelege für Erstattung, Storno und Gutschrift | 1 | M | WP-M8a-03, WP-M8a-06, WP-M8a-07, WP-M4-33, WP-M4-35, WP-M4-36 |
| WP-M8a-14 | Tank- und Firmenkartenabrechnung: Import und transaktionsgenauer Abgleich | 1 | M | WP-M8a-03, WP-M8a-04, WP-M8a-06, WP-M4-30, WP-M4-32, WP-M4-33, WP-M1-14, WP-M1-15, WP-M1-20 |
| WP-M8a-15 | Zwei- und Drei-Wege-Abgleich mit Klärfall | 1 | M | WP-M8a-07, WP-M7c-51 |
| WP-M8a-16 | Aufbewahrungsfristen je Dokumentenart und Rechtsraum | 1 | M | WP-M8a-06, WP-M1-33, WP-M4-23, WP-M4-24, WP-M8e-14, WP-M8e-15, WP-M8b-23 |
| WP-M8a-17 | Revisionsfähiger Archivexport mit Original, Metadaten und Audit-Historie | 1 | M | WP-M8a-11, WP-M8a-12, WP-M8a-16, WP-M4-23, WP-M4-24, WP-M1-12, WP-M1-15, WP-M8c-27 |
| WP-M8a-18 | Periodenabschluss mit Abschluss-Snapshot, Korrekturvorgang und Restatement-Ansicht | 1 | M | WP-M8a-13, WP-M4-36, WP-M6c-26 |
| WP-M8a-19 | Dokumentierter, wiederholsicherer Buchungssatzexport | 1 | M | WP-M8a-07, WP-M8a-12, WP-M8a-18, WP-M4-37, WP-M1-15, WP-M1-07 |
| WP-M8a-20 | DATEV-kompatibler Exportadapter | 1 | M | WP-M8a-19, WP-M6a-02 |
| WP-M8a-21 | Abschreibung und kalkulatorische Kapitalbindung je Fahrzeug | 1 | M | WP-M8a-02, WP-M8a-04, WP-M8a-39, WP-M4-33, WP-M4-40, WP-M3-03 |
| WP-M8a-22 | Leasingverträge mit Vertragsfortschritt und Mehrkilometerprognose | 1 | M | WP-M8a-02, WP-M8a-04, WP-M4-33, WP-M4-41, WP-M3-03, WP-M7c-53 |
| WP-M8a-23 | Versicherungspolicen, Selbstbehalte und Schadenfallbezug | 1 | M | WP-M8a-02, WP-M3-43, WP-M3-03, WP-M7c-41, WP-M7c-49, WP-M4-33 |
| WP-M8a-24 | Wartungsregeln nach Kilometer, Betriebsstunden und Kalender | 1 | M | WP-M8a-02, WP-M3-08, WP-M3-51, WP-M4-41, WP-M7c-15 |
| WP-M8a-25 | Pflichtnachweise mit Fristen, Zuständigen, Eskalation und Freigabesperre | 1 | M | WP-M8a-02, WP-M3-08, WP-M3-09, WP-M3-52, WP-M7c-49, WP-M7c-53, WP-M7c-23 |
| WP-M8a-26 | Werkstattauftrag mit Arbeitszeit, Teilen, Fremdleistung, Ausfallkosten und Kostenwirkung | 1 | M | WP-M8a-04, WP-M8a-24, WP-M8a-39, WP-M3-51, WP-M7c-35, WP-M7c-44, WP-M7c-14, WP-M4-33, WP-M4-32 |
| WP-M8a-27 | Materiallager: Entnahme mit Werkstattauftrags- und Kostenbezug | 1 | M | WP-M8a-26, WP-M7c-48, WP-M7c-51, WP-M4-33 |
| WP-M8a-28 | Reifen und saisonale Wechsel als Fahrzeugkomponenten | 1 | M | WP-M8a-02, WP-M3-03, WP-M4-41, WP-M7c-46 |
| WP-M8a-29 | Betriebliche Ressourcen und Depotbericht | 1 | M | WP-M8a-02, WP-M1-09, WP-M1-08, WP-M3-03, WP-M3-06, WP-M7c-15 |
| WP-M8a-30 | Ladepunktkosten ohne Doppelerfassung der Betriebshofenergie | 1 | M | WP-M8a-04, WP-M8a-29, WP-M8a-39, WP-M4-33, WP-M4-43, WP-M7c-15 |
| WP-M8a-31 | Kostensplit auf Kostenstellen, Projekte, Auftraggeber und Linien/Bediengebiete | 1 | M | WP-M8a-02, WP-M8a-03, WP-M8a-04, WP-M4-35, WP-M4-33, WP-M1-09, WP-M1-22 |
| WP-M8a-32 | TCO-Zusammenführung je Fahrzeug und Ausbau UI-15 | 0 (Enabler) | M | WP-M8a-12, WP-M8a-21, WP-M8a-22, WP-M8a-23, WP-M8a-26, WP-M8a-30, WP-M8a-31, WP-M4-44, WP-M4-40, WP-M4-43, WP-M1-43 |
| WP-M8a-33 | Kosten je Fahrzeugkilometer, Fahrgastfahrt, Fahrgastkilometer und Betriebsstunde | 1 | M | WP-M8a-32, WP-M4-45, WP-M4-46, WP-M4-42, WP-M4-56, WP-M8c-04 |
| WP-M8a-34 | Vertragsbudgets mit Soll, Ist, Forecast und Restbudget | 1 | L | WP-M8a-18, WP-M8a-31, WP-M4-33, WP-M4-46, WP-M3-52, WP-M8c-27 |
| WP-M8a-35 | Kostenverantwortliche, Freigabezuständigkeiten und Weiterleitung von Kostenüberschreitungen | 1 | M | WP-M8a-11, WP-M8a-34, WP-M3-52, WP-M1-07, WP-M1-08 |
| WP-M8a-36 | Kontrollberichte zu Kraftstoffverbrauch und Kosten je Kilometer | 1 | M | WP-M8a-33, WP-M4-30, WP-M4-31, WP-M4-41, WP-M4-43, WP-M3-52, WP-M8c-27 |
| WP-M8a-37 | Entscheidungshilfe Reparatur oder Ersatz | 1 | M | WP-M8a-26, WP-M8a-32 |
| WP-M8a-38 | Gate-Nachweis M8a und Regressionssuite Finanzen | 0 (Enabler) | M | WP-M8a-10, WP-M8a-12, WP-M8a-14, WP-M8a-18, WP-M8a-19, WP-M8a-34, WP-M8a-36, WP-M4-63, WP-M5-22, WP-M5-02 |
| WP-M8a-39 | Versionierte Kostensätze (`rate_revision`) mit Wirksamkeitsintervall und Freigabe | 0 (Enabler) | M | WP-M8a-02, WP-M1-22, WP-M1-07, WP-M1-12 |

## Arbeitspakete

### WP-M8a-01 – Detailverträge und Zustandsautomaten M8a
- **Ziel:** Für alle 33 Anforderungen liegen vor Codebeginn geprüfte Verträge vor (Leitlinie „Vertrag vor Code“, Kap. 08 §8.4); die Lücken des Pflichtenhefts bei Tabellen, Pfaden und Fachregeln sind entschieden oder als bestätigte Annahme dokumentiert.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - API: OpenAPI-3.1-Ergänzungen unter `api/` für `/v1/expenses/receipts`, `/extract`, `/v1/expenses/{id}`, `/v1/expenses/{expense_id}/approve` (Erweiterung um Stufen), `/v1/cost-events`, `/v1/cost-events/{cost_event_id}/allocations`, `/v1/vehicles/{id}/total-cost`, `/v1/ledger/exports`, `/v1/repair-orders`, `/v1/reports/jobs`, `/v1/reports/jobs/{id}`; Vorschlag neuer Pfade für Lieferanten, Eingangsrechnungen, Steuerkennzeichen, Kostenarten, Kartenabrechnungen, Bestell-/Lieferscheinabgleich, Fiskalperioden und Abschluss, Budgets, Leasing, Versicherung, Anlagen, Wartungsregeln, Pflichtnachweise, Lager, Reifen, Depotressourcen, Ladevorgänge und Kostenverantwortliche (in Kap. 05 §5.7 nicht enthalten, OP-03).
  - Datenbank: DDL-Entwürfe für `supplier_invoice`, `fuel_transaction`, `evidence_link`, `approval_step`, `document_revision`, `cost_category`, `rate_revision`, `accounting_mapping`, `allocation_activation`, `forecast_scenario`, `asset_schedule`, `vehicle_lease`, `repair_order`, `charging_slot`, `retention_rule`, `export_job`, die in Kap. 03 §2 genannten `tax_rule_revision`, `allocation_rule_revision`, `payment_order` sowie für alle Tabellen mit Arbeitsnamen (OP-02); Erweiterung von `cost_object.kind` (OP-04) und von `expense_document` (Belegart, Rechtsraum, Gegenbelegbezug). Bestandsaufnahme je Tabelle aus Kap. 04 §4.4: bereits durch M1/M3/M4/M7c angelegt (dann Expand) oder in M8a neu anzulegen – WP-M4-40 und WP-M4-33 schließen `rate_revision`, `asset_schedule`, `forecast_scenario` und `supplier_invoice` ausdrücklich aus und verweisen auf M8.
  - Oberfläche: Wireframes und Zustandskatalog (leer, Fehler, Rechte, 412-Konflikt) für alle in dieser Einheit neuen Masken in UI-14, UI-15 und UI-11; Zuordnung der Stammdatenmasken zu einem Bildschirm (OP-03).
  - Backend: Zustandsautomaten Beleg (Entwurf bis Export/Korrekturfall), Eingangsrechnung, Lieferant, Freigabestufe, Kartenabrechnung, Abgleich-Klärfall, Fiskalperiode, Budgetrevision, Wartungsfälligkeit, Pflichtnachweis, Werkstattauftrag (Kostenteil), Reifensatz; Ereigniskatalog M8a; Regel für die Periodenzuordnung an Tages-/Monatsgrenzen in `reporting_timezone` einschließlich Sommerzeitwechsel (ein Ereignis liegt in genau einer `fiscal_period`).
  - Konfigurationsparameter: JSON-Schemas (`policy_schema`) mit P2-Wertebereich für `approval_stage_thresholds`, `invoice_accounting_schema`, `invoice_archive_years`, `invoice_email_inbound`, `cost_split_rule`, `fuel_card_mapping`, `expense_claim_limit`, `cost_overrun_alert_pct`, `finance_close_day`, `receipt_mandatory_fields`, `receipt_duplicate_threshold`, `fuel_use_alert_pct`, `fuel_price_alert_pct`, `fuel_max_liters`, `maintenance_reminder_km`, `inspection_warning_days`, `inventory_min_stock`, `charge_reservation_min`; Abgrenzung, welche dieser Schemas M8a und welche WP-M8d-12/WP-M8d-13 (Finanzschwellen, Belegfelder und Freigabegrenzen je Belegart) im Konfigurationsregister veröffentlichen (OP-42).
  - Kennzahlen: Zähler-, Nenner- und Ereignisbindung für `Kosten pro Fahrzeugkilometer`, `Kosten pro Fahrgastfahrt`, `Kosten pro Fahrgastkilometer`, `Deckungsbeitrag`, `Zuschuss pro Fahrgastfahrt`, `Kraftstoff L/100 km`, `Strom kWh/100 km`, `Tankbeleg Vollständigkeit`, `Belegfreigabezeit`, `Offene Rechnungswerte`, `Budgetabweichung`, `Werkstattquote`, `Fahrzeugverfügbarkeit`, soweit M8a die Quelldaten liefert; die ausführbare Definition der 48 Katalogkennzahlen führt WP-M8c-22, M8a liefert Fakten und Kostenbasis (OP-43).
  - Adapter/Worker: Capability-Matrix der unterstützten XRechnung-/ZUGFeRD-Profile; Formatbeschreibung des Buchungssatzexports; Importspezifikation Kartenabrechnung.
- **Detailverträge vorab:** dieses WP erzeugt sie; Eingang sind die Verträge aus WP-M4-01, WP-M3-01 und WP-M7c-01.
- **Akzeptanzkriterien:**
  1. Jede der 33 Anforderungen ist in `arbeitspakete.csv` mit Detailvertrag und mindestens einem positiven Testfall verknüpft.
  2. Die ergänzte OpenAPI besteht `validate_artifacts.py` und die Contract-Test-Pipeline; `operationId`s sind eindeutig, Beispiele schema-gültig, Geld nur als ganzzahlige Minor Units, Mengen und Sätze als Dezimalstrings (Kap. 03 §2.1).
  3. Jeder Zustandsautomat nennt je Übergang Rolle, Vorbedingung, Ereignis und Negativfall.
  4. Für jeden Punkt unter „Offene Punkte“ liegt ein Beschluss oder eine vom Auftraggeber bestätigte Annahme vor; ohne sie beginnt das betroffene WP nicht.
  5. Die Abgrenzung zu den Tabellen und Abläufen aus M7c (Lager, Fahrzeugdokumente, Werkstattunterlagen, Einkauf, Vertragskalender) ist je Tabelle mit schreibendem Modul festgelegt (OP-05).
  6. Die Schnittstellen zu den Nachbareinheiten sind je Richtung als Modul-API bzw. Ereignis beschrieben und gegengezeichnet: M8c (Kostenbasis, `rate_revision`, Budgets, Abschluss-Snapshot, Tank-/Ladeereignisse), M8d (Budgetalarm, Kostenüberschreitung, Liste ungeklärter Buchungen, Fristerinnerungen), M8b (Zugriff nach Kostenstelle, Aufbewahrungsklassen), M8e (Legal Hold, Löschjob).
  7. Für jede Tabelle aus Kap. 04 §4.4, die diese Einheit nutzt, ist dokumentiert, ob sie besteht (mit lieferndem WP) oder in WP-M8a-02 neu entsteht.
- **Tests:** positiv: Contract-Tests der Beispielpayloads. negativ: je Endpunkt mindestens ein Negativbeispiel (fehlende Rolle, fremder Mandant, fremder Rechtsträger, veraltete Revision → 412, geschlossene Periode → 409).
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M4-01 (Verträge Beleg, Kostenereignis, Journal, Periode, Export)
  - extern: WP-M3-01 (Verträge Reparaturauftrag, Sperr-/Freigabekern)
  - extern: WP-M7c-01 (Verträge Lager, Fahrzeugdokumente, Einkauf, Werkstattunterlagen)
  - extern: WP-M5-31 (P1-Pilotabnahme als Ausgangsstand)
  - extern: WP-M8c-01 (Verträge Kennzahlregister, Berichtsjobs, Soll/Ist/Prognose)
  - extern: WP-M8d-01 (Verträge Konfigurationsregister P2, Alarme und Automatisierung)
- **Entscheidungen:** D-06 (Ziel-ERP, Kontenrahmen, Steuerregeln, E-Rechnungsprofile, Aufbewahrung), D-07 (Kostenarten, Treiber, Abschreibung/Leasing, Deckungsbeitragsstufen), D-11 (Löschfristen, Legal Hold) fließen in die Verträge ein – blockieren den Bau mit Testwerten nicht, aber die Produktivschaltung der betroffenen WPs.
- **Größe:** M
- **Nicht im Umfang:** Verträge der anderen M8-Einheiten (KPI-Katalog, Automatisierung, ETA, Governance, Plattform); Clearing-Verträge (M6c); Implementierung.

### WP-M8a-02 – Migration der M8a-Tabellen mit RLS und Feldschutz
- **Ziel:** Alle Tabellenerweiterungen und neuen Tabellen dieser Einheit sind per Flyway migriert, mandanten- und rechtsträgergetrennt und unter der Laufzeitrolle getestet.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Datenbank: Expand-Migrationen für die aus Vorgängermeilensteinen bestehenden Tabellen `expense_document`, `cost_object` (zusätzliche `kind`-Werte gemäß OP-04), `fiscal_period`, `fuel_transaction`, `evidence_link`, `approval_step`, `document_revision`, `cost_category`, `accounting_mapping`, `allocation_activation`, `vehicle_lease`, `repair_order`, `charging_slot`, `retention_rule`, `export_job`; Neuanlage (oder Expand, falls laut Bestandsaufnahme aus WP-M8a-01 bereits vorhanden) von `supplier_invoice`, `rate_revision`, `forecast_scenario`, `asset_schedule` aus Kap. 04 §4.4; `adapter_capability` wird nur angelegt, falls M6a sie noch nicht geliefert hat (OP-40); neue Tabellen `tax_rule_revision`, `allocation_rule_revision`, `payment_order` (Namen aus Kap. 03 §2) sowie Lieferant, Kartenabrechnung, Bestell-/Lieferscheinposition, Abgleich-Klärfall, Abschluss-Snapshot, Budget, Versicherungspolice, Wartungsregel, Pflichtnachweis, Reifensatz, Depotressource, Ladevorgang, Kostenverantwortung (Arbeitsnamen gemäß WP-M8a-01). Jede Tabelle mit Primärschlüssel `(tenant_id,id)`, `legal_entity_id` wo buchungsrelevant, zusammengesetzten FKs, RLS-Policy über `current_tenant()`, Revision und Audit (Kap. 04 §4.4, §4.5 „Keine lokale Cross-Tenant-Referenz“, „Kein Mandantenkontext → keine Zeile“).
  - Datenbank: Append-only-Schutz (UPDATE/DELETE-Verbot) für freigegebene Steuerregelrevisionen, Abschluss-Snapshots, Lagerbewegungen und Exportbatches; Exclusion-/Überlappungsprüfung für Gültigkeitsintervalle (Steuerregel, `fuel_card_mapping`, Leasing, Police, Reifenmontage, `fiscal_period`) gemäß Invariante „Nicht überlappende aktive Tarif-/Policy-/Dienstversion“ und der in Kap. 04 §4.5 als noch zu ergänzen genannten Periodenüberlappungsregel.
  - Datenbank: eigener Modulzugang und explizite Spalten-/Viewrechte für Finanzdaten (Bankverbindung, Preise, Margen) gemäß Kap. 04 §4.6; Standardindizes `(tenant_id,state,updated_at)` und `(tenant_id,legal_entity_id,booked_at)` (Kap. 04 §4.7).
- **Detailverträge vorab:** DDL aus WP-M8a-01; Beschlüsse zu OP-02, OP-04, OP-05.
- **Akzeptanzkriterien:**
  1. Migration läuft auf leerer Datenbank und auf einem M5-Bestand mit Daten zweier Mandanten und zweier Rechtsträger ohne Datenverlust (expand/contract); bestehende `economic_event`- und `journal`-Zeilen bleiben unverändert.
  2. Ohne Mandantenkontext liefert jede neue Tabelle keine Zeile; ein FK auf eine Zeile eines anderen Mandanten wird abgelehnt.
  3. UPDATE und DELETE auf die Append-only-Tabellen scheitern unter der Laufzeitrolle.
  4. Zwei überlappende Gültigkeitsintervalle desselben Schlüssels werden abgewiesen.
  5. Die Laufzeitrolle eines Moduls ohne Finanzzugang kann die geschützten Finanzspalten nicht lesen.
  6. Die Laufzeitrolle besitzt auf keiner neuen Tabelle TRUNCATE-, DDL- oder Eigentümerrechte; RLS gilt auch nach Poolwiederverwendung und nach Transaktionsabbruch (Kap. 04 §4.6).
  7. Neue Pflichtspalten an Bestandstabellen werden erst nach abgeschlossenem Backfill validiert; die Migration ist aus dem freigegebenen M5-Vorstand und frisch lauffähig (Kap. 08 §8.4 Gate „CI“).
- **Tests:** positiv: Migration, fortsetzbarer Backfill mit Abbruch und Wiederanlauf. negativ: Cross-Tenant-FK, fehlender Tenantkontext, Tenantkontext nach Poolwiederverwendung, UPDATE auf Append-only, Überlappung, Lesen geschützter Finanzspalten ohne Modulzugang. konkurrierend (Datentrennung/Geld): je neuer Invariante ein Test mit zwei parallelen Transaktionen (Kap. 04 §4.5), insbesondere überlappende Intervalle und Periodenanlage.
- **Abhängigkeiten:** intern: WP-M8a-01.
  - extern: WP-M1-01, WP-M1-12 (Tenantkontext, RLS-Laufzeitbasis, Audit)
  - extern: WP-M4-22, WP-M4-33, WP-M4-36 (bestehende Beleg-, Kosten- und Journaltabellen)
  - extern: WP-M7c-02 (bestehende Lager-, Fahrzeugdokument- und Werkstatttabellen)
- **Entscheidungen:** D-02 (Isolationsstufe) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Fachlogik; Tabellen `settlement_statement`, `settlement_reconciliation`, `payout_instruction`, `transfer_reversal` (M6c); `metric_definition_revision`, `aggregate_snapshot` (KPI-Einheit von M8).

### WP-M8a-03 – Referenzfixtures Finanzen P2 und E-Rechnungs-Testkorpus
- **Ziel:** Ein deterministischer Testdatenbestand und ein versionierter Korpus an Beispieldokumenten stehen allen WPs dieser Einheit zur Verfügung.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: Testkorpus unter `tests/acceptance` mit gültigen und ungültigen UBL-/CII-Rechnungen und -Gutschriften, hybrider Rechnung mit bewusst abweichendem PDF- und XML-Betrag (E2E-067), nicht unterstütztem Profil, generischer PDF mit beliebigem eingebettetem XML; Beispieldateien für Tank- und Firmenkartenabrechnungen (Treffer, fehlender Beleg, Differenz).
  - Backend: Seed mit zwei Mandanten, zwei Rechtsträgern, Test-Kontenrahmen, Test-Steuerregeln, Kostenstellen, Projekten, Auftraggebern K1/K2, Fahrzeugen mit Anschaffungs- und Leasingdaten; Fixtures für PH-AT-012 (119,00/100,00/19,00 EUR), PH-AT-015 (September 100,00 EUR, Gutschrift 20,00 EUR, Planbudget 90,00 EUR), Rechenbeispiel B aus Kap. 03 §6 (1.680,00 EUR, 60 h/40 h, 2.000 km, 300 Fahrten, 2.500 Fahrgast-km, 100 Dienststunden), PH-AT-013 (100,01 EUR auf drei gleiche Anteile → 33,34/33,34/33,33) und COST-02 (10.000 Minor Units im Drittelsplit).
  - Backend: Fixtures für die Fuhrparkpakete: je ein Verbrenner- und ein Elektrofahrzeug mit Tank-/Ladeereignissen und Kilometerständen, darunter eine Tankung mit unplausiblem Verbrauch (E2E-070); Leasingvertrag mit Kilometerlimit, Versicherungspolice mit Selbstbehalt und Schadenfall, Wartungsregel, HU-Nachweis kurz vor und nach Ablauf (E2E-071), Werkstattauftrag mit allen vier Positionsarten, Lagerartikel mit Mindestbestand, Reifensätze über drei Saisons, zwei Depots mit Stellplätzen und Ladepunkten, Bestellung mit Lieferschein und abweichender Rechnung, Budget je Projekt, Rechnung über der Vier-Augen-Schwelle (E2E-068), infizierter Upload (PH-AT-019).
- **Detailverträge vorab:** Capability-Matrix der Profile (WP-M8a-01); Herkunft und Lizenz der Beispieldokumente; Format der Kartenabrechnung (OP-12).
- **Akzeptanzkriterien:**
  1. Der Seed ist wiederholbar und erzeugt bei zweimaligem Lauf identische IDs und Summen.
  2. Jedes Korpusdokument trägt erwartetes Validierungsergebnis und erwartete Fehlercodes als maschinenlesbare Erwartung.
  3. Die Fixtures enthalten keine echten Personen-, Bank- oder Lieferantendaten (Kap. 08 §8.4).
  4. Zu jedem in dieser Datei genannten Referenztest (E2E-065, E2E-067, E2E-068, E2E-070, E2E-071, E2E-072, E2E-073, PH-AT-012, PH-AT-013, PH-AT-015, PH-AT-019, COST-01, COST-02, COST-05 bis COST-08) existiert ein benanntes Fixture mit erwarteten Sollwerten.
- **Tests:** positiv: Seedlauf, Erwartungsdatei gegen Korpus. negativ: Seed in falschem Mandantenkontext scheitert.
- **Abhängigkeiten:** intern: WP-M8a-01.
  - extern: WP-M4-02 (Referenzfixtures M4)
  - extern: WP-M0-22 (Testinfrastruktur `tests/acceptance`)
- **Entscheidungen:** D-06 (zulässige E-Rechnungsprofile, Kontenrahmen) – Bau mit Testwerten; keine Blockade.
- **Größe:** S
- **Nicht im Umfang:** PSP- und Partner-Simulatoren (M0, M6c); Lastdaten (M5).

### WP-M8a-04 – Kostenarten für Reise-, Park-, Maut-, Reinigungs-, Ersatzteil- und Werkstattkosten
- **Ziel:** Die genannten Ausgaben haben eigene, stabile Kostenarten und lassen sich standardisiert je Fahrzeug, Monat und Kostenstelle filtern.
- **Anforderungen:** `LH-I.1-DOC-020`
- **Liefergegenstände:**
  - Backend: versionierter Kostenartenkatalog im Modul `documents-finance` mit den Kostenarten Reisekosten, Parkgebühren, Maut, Reinigung, Ersatzteile, Werkstattkosten; Zuordnung `behavior` und Standard-`attribution` als zwei Achsen (Kap. 03 §1); Filterabfrage über gebuchte Kostenereignisse.
  - Datenbank: `cost_category` (Schlüssel, Bezeichnung, Gültigkeit, Standardkontierung über `accounting_mapping`); `economic_event.category` verweist auf den Katalogschlüssel; `cost_object` (`VEHICLE`, `COST_CENTER`).
  - API: `/v1/cost-events` (Filter Kostenart, Fahrzeug, Monat, Kostenstelle; Cursor, `as_of`); neuer Pfad Kostenartenkatalog (WP-M8a-01).
  - Oberfläche: UI-15 – Filter Kostenart/Fahrzeug/Monat/Kostenstelle; UI-14 – Auswahl der Kostenart am Beleg.
- **Detailverträge vorab:** Katalogschlüssel und Abbildung der P1-Kostenarten aus WP-M4-40; Monatsbezug (Leistungszeitraum oder Buchungsperiode, OP-06); Zeitzone `reporting_timezone`.
- **Akzeptanzkriterien:**
  1. Für jede der sechs Ausgabenarten existiert eine eigene Kostenart; ein Beleg kann ihr zugeordnet werden (DOC-020).
  2. Die Abfrage nach Fahrzeug, Monat und Kostenstelle liefert für jede der sechs Kostenarten genau die gebuchten Ereignisse des Testbestands; Summen stimmen mit den Journalen überein (DOC-020).
  3. Ein unbekannter oder nicht mehr gültiger Kostenartschlüssel wird beim Buchen abgewiesen.
  4. Original und Umlage derselben Kostenposition werden im Filterergebnis nicht doppelt summiert (Kap. 03 §1).
- **Tests:** positiv: Buchung je Kostenart, Filterkombinationen. negativ: ungültige Kostenart, Kostenstelle eines anderen Rechtsträgers, fremder Mandant → leeres Ergebnis. konkurrierend (Geld): Katalogrevision wird parallel zu einer Buchung veröffentlicht – Buchung trägt eindeutig eine Revision.
- **Abhängigkeiten:** intern: WP-M8a-02.
  - extern: WP-M4-33, WP-M4-40, WP-M4-29 (Kostenereignis und Posting, Kostenartenstruktur P1, Kostenzuordnung)
  - extern: WP-M1-09 (Kostenstellenstamm)
- **Entscheidungen:** D-07 (verbindliche Kostenarten) – blockiert nur die Produktivschaltung.
- **Größe:** S
- **Nicht im Umfang:** Schadenkostenarten Material/Arbeitszeit/Abschleppen/Fahrzeugausfall (WP-M7c-44); Reisekostenpauschalen und `expense_allowance_policy` (Personaleinheit von M7).

### WP-M8a-05 – Versionierte Steuerkennzeichen und Umsatzsteuersätze
- **Ziel:** Steuerkennzeichen und Sätze sind versioniert, nur für berechtigte Rollen zugänglich, und jede Buchung bleibt mit der zum Freigabezeitpunkt gültigen Fassung nachvollziehbar.
- **Anforderungen:** `LH-I.1-DOC-021`
- **Liefergegenstände:**
  - Backend: Steuerregelverwaltung im Modul `documents-finance` mit Entwurf, Vier-Augen-Freigabe, Wirksamkeitsintervall, Nachfolgerevision statt Änderung; Auflösung der wirksamen Revision zum Freigabezeitpunkt; Steuerrundung je Gruppe aus Aussteller, Kennzeichen, Satz und Leistungsart, Standard `ROUND_HALF_UP` (Kap. 03 §2.1, als Planungsdefault gekennzeichnet).
  - Datenbank: `tax_rule_revision` (Kennzeichen, Satz als `numeric(20,8)`, Abzugsfähigkeit, Rundungsregel, gültig von/bis, Ersteller/Prüfer, append-only nach Freigabe); `economic_event.tax_rule_revision` wird als Referenz auf diese Tabelle geprüft (OP-07); `approval_step` hält die Freigaberevision.
  - API: neuer Pfad Steuerkennzeichen (lesen, Entwurf, Freigabe; Scope nur Finanzadministration); `/v1/cost-events` gibt die Steuerregelrevision je Ereignis aus.
  - Oberfläche: UI-14 – Steuergruppe am Beleg mit Revisionsanzeige; Verwaltungsmaske im Finanzbereich (UI-Zuordnung OP-03).
- **Detailverträge vorab:** Rechtematrix (wer liest, wer ändert, OP-08); Schema Steuerregel; Verhalten bei Freigabe ohne wirksame Revision.
- **Akzeptanzkriterien:**
  1. Eine Buchung, die unter Revision 1 freigegeben wurde, zeigt nach Veröffentlichung von Revision 2 weiterhin Kennzeichen, Satz und Revision 1 (DOC-021).
  2. Ein Benutzer ohne die berechtigte Rolle kann Steuerkennzeichen weder ändern noch die Verwaltungsansicht öffnen (403, protokolliert) (DOC-021).
  3. Eine freigegebene Revision ist unveränderlich; eine Korrektur entsteht als neue Revision mit Wirksamkeitsbeginn.
  4. Zwei gleichzeitig wirksame Revisionen desselben Kennzeichens sind ausgeschlossen.
  5. Eingehende Originalrechnungsbeträge werden nicht still neu berechnet; eine tolerierte Rundungsdifferenz erscheint als eigene Position (Kap. 03 §2.1).
- **Tests:** positiv: Revisionswechsel mit historischer Auswertung. negativ: fehlende Rolle, Änderung einer freigegebenen Revision, Freigabe durch den Ersteller, Lücke ohne wirksame Revision. konkurrierend (Geld): Belegfreigabe parallel zum Wirksamwerden einer neuen Revision – genau eine Revision am Ereignis; zwei parallele Veröffentlichungen überlappender Intervalle – genau eine gewinnt.
- **Abhängigkeiten:** intern: WP-M8a-02.
  - extern: WP-M4-33, WP-M4-34 (Posting und Freigabe-Workflow)
  - extern: WP-M1-07, WP-M1-22, WP-M1-12 (Rollenmodell, Vier-Augen-Baustein, Audit)
- **Entscheidungen:** D-06 (Steuerregeln, Vorsteuerabzug, Rundung) – Bau mit Teststeuersätzen; Produktivschaltung blockiert.
- **Größe:** M
- **Nicht im Umfang:** Steuer- und Rechnungsstellungsmodell der Partnerabrechnung (WP-M6c-21); steuerliche Würdigung selbst (AG-Freigabe).

### WP-M8a-06 – Vollständiger Belegstatusautomat bis Export und Korrekturfall
- **Ziel:** Jeder Beleg durchläuft einen einheitlichen, serverseitig erzwungenen Status von Entwurf über Prüfung und Freigabe bis Export oder Korrekturfall; ungültige Sprünge sind ausgeschlossen und protokolliert.
- **Anforderungen:** `LH-I.1-DOC-026`
- **Liefergegenstände:**
  - Backend: Erweiterung des Belegautomaten aus M4 im Modul `documents-finance` um die Zustände Export und Korrekturfall sowie die Klärzustände für E-Rechnung, Abgleich und Dublette; Übergangstabelle mit Rolle und Vorbedingung; abgelehnte Übergänge erzeugen ein Auditereignis.
  - Datenbank: `expense_document` (`state`, `revision`), `document_revision`, `approval_step`, `audit_event`; CHECK auf den Zustandskatalog; Übergang nur über den Automaten (Laufzeitrolle ohne freies UPDATE auf `state`).
  - API: `/v1/expenses/{id}` (Status, zulässige Folgeaktionen, Historie); Statusbefehle mit `If-Match` und `Idempotency-Key`; Fehlercode für unzulässigen Übergang (409).
  - Oberfläche: UI-14 – Arbeitsliste nach Status, nur zulässige Aktionen aktiv.
  - Ereignisse/Outbox: Belegstatus geändert.
- **Detailverträge vorab:** Zustandskatalog und Abbildung der P1-Zustände einschließlich `WAITING_SECOND_APPROVAL`, `APPROVED_PENDING_POSTING`, `POSTED` (OP-09); Übergangsmatrix je Rolle.
- **Akzeptanzkriterien:**
  1. Jeder Beleg trägt genau einen Status aus dem Katalog von Entwurf über Prüfung und Freigabe bis Export oder Korrekturfall (DOC-026).
  2. Ein ungültiger Statussprung (z. B. Entwurf → Export, Export → Entwurf) wird über API und direkt auf der Datenbank unter der Laufzeitrolle abgewiesen (DOC-026).
  3. Jeder abgewiesene Sprung erzeugt einen Auditeintrag mit Akteur, Ausgangs- und Zielstatus (DOC-026).
  4. Bestandsbelege aus P1 sind nach der Migration eindeutig einem Status des erweiterten Katalogs zugeordnet.
- **Tests:** positiv: vollständiger Durchlauf bis Export; Durchlauf in den Korrekturfall. negativ: jeder nicht in der Matrix enthaltene Übergang, fehlende Rolle, veraltete Revision (412). konkurrierend (Geld): zwei parallele Statusbefehle auf demselben Beleg – genau einer wirkt.
- **Abhängigkeiten:** intern: WP-M8a-02.
  - extern: WP-M4-22, WP-M4-34 (Belegaggregat und Freigabeautomat P1)
  - extern: WP-M1-17, WP-M1-12 (Zustandsautomaten-Rahmen, Audit)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Inhalt der Freigabestufen (WP-M8a-11); Gegenbeleg-Erzeugung (WP-M8a-13); Exportlauf (WP-M8a-19).

### WP-M8a-07 – Eingangsrechnung mit Kreditor, Fälligkeit, Steuerkennzeichen und Buchungsvorschlag
- **Ziel:** Eingangsrechnungen werden mit allen buchhaltungsrelevanten Feldern erfasst, validiert und als Buchungsvorschlag exportierbar gemacht.
- **Anforderungen:** `LH-I.1-DOC-016`
- **Liefergegenstände:**
  - Backend: Aggregat Eingangsrechnung im Modul `documents-finance` als Spezialisierung des Belegs: Kreditor, Lieferantenstammdatenbezug (Minimalstamm: Name, Kreditorennummer, Status), Rechnungsnummer, Rechnungsdatum, Fälligkeitsdatum, Positionen mit Steuerkennzeichen und Kostenkategorie, Kostenobjekt; Validierung der Pflichtfelder und der Summen (`gross = net + tax`); Buchungsvorschlag aus `accounting_mapping` und Steuerregel.
  - Datenbank: `supplier_invoice` (Bezug auf `expense_document`, Lieferant, Fälligkeit, Zahlungsziel, Rechnungsnummer; Unique je Rechtsträger/Lieferant/Rechnungsnummer als Dublettenhilfe), Rechnungsposition (Arbeitsname), `accounting_mapping`, `cost_category`, `tax_rule_revision`, `evidence_link`.
  - API: neuer Pfad Eingangsrechnungen (anlegen, lesen, validieren, Buchungsvorschlag); `/v1/expenses/receipts` und `/v1/expenses/{id}` für Dokumentbezug; `/v1/ledger/exports` für den Export des Vorschlags.
  - Oberfläche: UI-14 – Rechnungsmaske mit Kreditor, Fälligkeit, Steuergruppe, Kostenkategorie; Validierungsfehler je Feld; Zustand „fehlende Kostenstelle“.
  - Konfigurationsparameter: `receipt_mandatory_fields`, `invoice_accounting_schema`, `invoice_email_inbound`.
  - Kennzahlen: `Offene Rechnungswerte` (brutto und netto getrennt), `Belegfreigabezeit`.
- **Detailverträge vorab:** Schema Eingangsrechnung und Position; Pflichtfeldliste je Belegart; Schema und Format des Buchungsvorschlags (OP-10); Abgrenzung „Buchungsvorschlag“ zu gebuchtem Journal.
- **Akzeptanzkriterien:**
  1. Eine Eingangsrechnung wird mit Kreditor, Lieferantenstammdaten, Fälligkeitsdatum, Steuerkennzeichen und Kostenkategorie gespeichert (DOC-016).
  2. Die Validierung meldet fehlende oder widersprüchliche buchhaltungsrelevante Felder konkret je Feld; ohne erfolgreiche Validierung ist keine Freigabe möglich (DOC-016).
  3. Für eine valide Rechnung lässt sich ein Buchungsvorschlag mit Konto, Gegenkonto, Steuerkennzeichen, Kostenstelle und Beleg-ID exportieren (DOC-016).
  4. Der Buchungsvorschlag erzeugt keine Kostenwirkung; Aufwand entsteht erst nach Freigabe und einmaligem Posting (Kap. 03 §3 Nr. 6).
  5. `Offene Rechnungswerte` weist gültige, nicht abgeschlossene Eingangsrechnungen brutto und netto getrennt aus.
  6. Für `Belegfreigabezeit` liefert jede Eingangsrechnung die Zeitpunkte „Eingang vollständig“ und „Freigabe“ als Fakten an das Kennzahlregister; eine Rechnung ohne Freigabe geht nicht mit Dauer 0 ein.
  7. Die Rechnungsmaske in UI-14 zeigt die Zustände „fehlende Kostenstelle“ und „Dublette“ sowie einen 412-Konflikt bei paralleler Bearbeitung ohne stilles Überschreiben.
- **Tests:** positiv: Erfassung, Validierung, Vorschlagsexport. negativ: fehlendes Fälligkeitsdatum, unbekanntes Steuerkennzeichen, Summe ≠ Positionen, Lieferant eines anderen Mandanten, doppelte Rechnungsnummer. konkurrierend (Geld): zwei parallele Erfassungen derselben Rechnungsnummer – eine Rechnung, ein Dublettenhinweis.
- **Abhängigkeiten:** intern: WP-M8a-04, WP-M8a-05, WP-M8a-06.
  - extern: WP-M4-21, WP-M4-22, WP-M4-32, WP-M4-33, WP-M4-38 (Uploadticket und Quarantäne, Belegaggregat, Dublettenprüfung, Posting, UI-14)
- **Entscheidungen:** D-06 (Kontenrahmen, Kontierung) – Bau mit Test-Kontenrahmen; Produktivschaltung blockiert.
- **Größe:** M
- **Nicht im Umfang:** vollständige Lieferantenverwaltung (WP-M8a-08); strukturierter E-Rechnungsimport (WP-M8a-09); Zahlungsausführung an Lieferanten (D-06: Bankwesen bleibt im ERP).

### WP-M8a-08 – Lieferantenverwaltung mit freigegebenen Stammdaten und Kontodatenprüfung
- **Ziel:** Lieferanten werden mit Bankverbindung, Zahlungsziel, Standardkontierung, Vertragsbezug und Pflichtdokumenten gepflegt; Rechnungen übernehmen nur freigegebene Stammdaten und widersprüchliche Kontodaten fallen auf.
- **Anforderungen:** `LH-I.1-DOC-027`
- **Liefergegenstände:**
  - Backend: Lieferantenaggregat mit Revisionen (Entwurf, Vier-Augen-Freigabe, gesperrt); Übernahme von Zahlungsziel und Standardkontierung in neue Rechnungen ausschließlich aus der freigegebenen Revision; Vergleich der Bankverbindung der Rechnung mit der freigegebenen Bankverbindung; Pflichtdokumente mit Gültigkeit.
  - Datenbank: Lieferant und Lieferantenrevision (Arbeitsnamen; Bankverbindung als geschütztes Feld), Bezug `supplier_invoice` → freigegebene Lieferantenrevision; `document_revision` für Pflichtdokumente.
  - API: neuer Pfad Lieferanten (anlegen, Revision, Freigabe, sperren).
  - Oberfläche: UI-14 – Hinweis „Kontodaten weichen ab“ an der Rechnung; Lieferantenmaske im Finanzbereich.
  - Ereignisse/Outbox: Lieferant freigegeben; Kontodatenabweichung erkannt.
- **Detailverträge vorab:** Schema Lieferant; Liste der Pflichtdokumente (OP-11); Vergleichsregel für Kontodaten (normalisierte IBAN/BIC); Bedeutung von „Vertragsbezug“ (OP-11).
- **Akzeptanzkriterien:**
  1. Ein Lieferant führt Bankverbindung, Zahlungsziel, Standardkontierung, Vertragsbezug und Pflichtdokumente (DOC-027).
  2. Eine neue Lieferantenrechnung übernimmt Stammdaten nur aus der freigegebenen Revision; ein nicht freigegebener Entwurf wird nicht verwendet (DOC-027).
  3. Weicht die Bankverbindung einer Rechnung von der freigegebenen ab, entsteht ein Prüfhinweis und die Freigabe ist bis zur Klärung blockiert (DOC-027).
  4. Die Bankverbindung ist nur für Rollen mit Finanzstammdatenrecht sichtbar; Änderungen sind auditiert.
- **Tests:** positiv: Freigabe einer Revision, Übernahme in Rechnung. negativ: Rechnung mit abweichender IBAN, Selbstfreigabe der Stammdatenänderung, gesperrter Lieferant. konkurrierend (Sicherheit/Geld): Stammdatenänderung und Rechnungserfassung parallel – Rechnung trägt eindeutig eine freigegebene Revision.
- **Abhängigkeiten:** intern: WP-M8a-07.
  - extern: WP-M1-22, WP-M1-31 (Vier-Augen-Baustein, Feldschutz)
  - extern: WP-M7c-50 (Dienstleisterstamm und Wartungsverträge als Vertragsbezug)
- **Entscheidungen:** D-06 (Kontierung, Kreditorenführung im ERP) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Zahlungsläufe und Bankanbindung; Lieferantenbewertung; Einkaufsprozess (WP-M7c-51).

### WP-M8a-09 – E-Rechnungsimport XRechnung/ZUGFeRD mit Validierung nach EN 16931
- **Ziel:** Strukturierte E-Rechnungen werden importiert, gegen eine freigegebene Regelversion validiert und strukturiert als Eingangsrechnung übernommen; ungültige Dokumente zeigen konkrete Fehler.
- **Anforderungen:** `LH-I.1-DOC-014`
- **Liefergegenstände:**
  - Adapter/Worker: KoSIT-Validator im Worker `workers/documents` mit versionierter Validator-Konfiguration (XSD und Schematron) für UBL und UN/CEFACT CII; separater, profilbezogener ZUGFeRD-Pfad mit Extraktion des eingebetteten XML; Capability-Matrix der unterstützten Profile; fortsetzbarer Validierungsjob über `operation`.
  - Backend: Import im Modul `documents-finance`: Profilerkennung, Validierung, Abbildung der EN-16931-Felder auf Eingangsrechnung und Positionen; nicht unterstützte Profile und generische PDFs mit beliebigem XML gehen in den Klärworkflow (Kap. 03 §3 Nr. 5); Validierungsbericht mit Regel-ID, Fundstelle und Text.
  - Datenbank: `supplier_invoice`, `document_revision` (XML als eigenes unverändertes Original mit SHA-256), `extraction_run` (Validator- und Regelversion, Ergebnis), `extracted_field` (Herkunft „strukturiert“), `evidence_link`.
  - API: `/v1/expenses/receipts` (Uploadticket für XML/PDF), `/extract` (Anstoß Validierung/Übernahme), `/v1/expenses/{id}` (Validierungsbericht).
  - Oberfläche: UI-14 – Validierungsfehler je Regel; Zustand „XML-Konflikt“/„Profil nicht unterstützt“.
  - Konfigurationsparameter: `invoice_email_inbound` (Eingangskanal), `receipt_mandatory_fields`.
- **Detailverträge vorab:** Capability-Matrix und freigegebene Validator-/Regelversion; Feldabbildung EN 16931 → Eingangsrechnung; Fehlerformat; Umgang mit Regelversionswechsel (OP-13).
- **Akzeptanzkriterien:**
  1. Gültige Beispieldokumente in XRechnung (UBL und CII) und in jedem in der Capability-Matrix freigegebenen ZUGFeRD-Profil werden strukturiert als Eingangsrechnung mit Positionen übernommen (DOC-014).
  2. Ungültige Rechnungen werden nicht übernommen und zeigen konkrete Validierungsfehler mit Regel-ID und Fundstelle (DOC-014).
  3. Ein nicht unterstütztes Profil und eine PDF mit beliebigem eingebettetem XML landen im Klärworkflow und gelten nicht als validierte E-Rechnung.
  4. Der Validierungsbericht nennt Validator- und Regelversion; eine erneute Validierung desselben Dokuments mit derselben Version liefert dasselbe Ergebnis.
  5. Eine bestandene technische Validierung löst keine Freigabe aus; Sachprüfung und steuerliche Behandlung bleiben getrennt (PH-AT-019).
  6. Ein infiziertes oder nicht freigegebenes Uploadobjekt wird nicht an den Validator übergeben.
- **Tests:** positiv: gesamter Positivkorpus aus WP-M8a-03. negativ: Negativkorpus (Schemaverstoß, Schematronverstoß, falsches Profil, XML-Bombe/externe Entitäten, übergroße Datei), fremder Mandant. konkurrierend (Geld): zweimaliger paralleler Import derselben E-Rechnung – eine Eingangsrechnung, kein zweites Kostenereignis. Teil von E2E-067, PH-AT-019, COST-07.
- **Abhängigkeiten:** intern: WP-M8a-03, WP-M8a-07.
  - extern: WP-M4-21, WP-M4-23, WP-M4-24, WP-M4-27 (Uploadticket/Quarantäne, unveränderliches Original, Hash, Worker-Grundlage Dokumente)
  - extern: WP-M1-15, WP-M1-38 (`operation`-Ressource, Eingabehärtung)
- **Entscheidungen:** D-06 (zulässige E-Rechnungsprofile) – Bau gegen XRechnung und Testprofile; Produktivschaltung je Profil blockiert bis Beschluss.
- **Größe:** L
- **Nicht im Umfang:** PDF/XML-Vergleich (WP-M8a-10); Ausgangsrechnungen und Quittungen (WP-M6c-16); E-Mail-Eingangskanal selbst (WP-M4-22); GoBD-/Steuerfreigabe (AG).

### WP-M8a-10 – Führendes XML und Meldung von PDF/XML-Abweichungen
- **Ziel:** Bei hybriden Rechnungen ist das strukturierte XML maßgeblich und bleibt maschinenlesbar erhalten; eine abweichende PDF-Darstellung wird sichtbar gemeldet und ist prüfpflichtig.
- **Anforderungen:** `LH-I.1-DOC-015`
- **Liefergegenstände:**
  - Backend: Übernahme ausschließlich aus dem XML; Vergleich der Kernfelder (Betrag brutto/netto/Steuer, Rechnungsnummer, Datum, Lieferant, Bankverbindung) zwischen XML und der aus dem PDF gelesenen Darstellung; Abweichung erzeugt einen Prüfhinweis, der vor Freigabe quittiert werden muss.
  - Adapter/Worker: Extraktion der PDF-Sicht im Worker `workers/documents` (Textschicht bzw. OCR-Adapter); OCR-Ergebnis ist nur Vergleichswert, nie Buchungsgrundlage.
  - Datenbank: `document_revision` (XML und PDF als getrennte unveränderliche Originale), `extraction_run`, `extracted_field` (Quelle XML bzw. PDF), Abweichungseintrag (Arbeitsname) mit Feld, XML-Wert, PDF-Wert, Quittierer.
  - API: `/v1/expenses/{id}` (Abweichungsliste), `/v1/expenses/{expense_id}/approve` (409, solange Abweichung unquittiert).
  - Oberfläche: UI-14 – XML- und PDF-Wert nebeneinander, Kennzeichnung „XML führend“; Zustand „XML-Konflikt“ (Kap. 06 §6.6, §6.9).
- **Detailverträge vorab:** Liste der verglichenen Felder und Toleranzen (OP-14); Fehlercode der blockierten Freigabe; Rolle für die Quittierung.
- **Akzeptanzkriterien:**
  1. Das XML einer E-Rechnung ist als eigenes Original mit Hash gespeichert und ohne PDF abrufbar und verarbeitbar (DOC-015).
  2. Bei bewusst abweichendem PDF- und XML-Betrag wird der XML-Inhalt übernommen und die Abweichung mit beiden Werten gemeldet (DOC-015; E2E-067).
  3. Die Freigabe ist blockiert, bis die Abweichung von einer berechtigten Person mit Begründung quittiert wurde; die Quittierung ändert keinen Betrag (PH-AT-019).
  4. Eine reine XRechnung ohne PDF wird ohne Abweichungsprüfung verarbeitet.
- **Tests:** positiv: hybride Rechnung ohne Abweichung. negativ: Betragsabweichung, abweichende Bankverbindung, nicht lesbares PDF (Hinweis „Vergleich nicht möglich“ statt stiller Annahme), Freigabeversuch ohne Quittierung, Quittierung durch den Erfasser ohne Quittierungsrecht. konkurrierend (Geld): Freigabe parallel zum Entstehen bzw. zur Quittierung einer Abweichung – Freigabe nur auf der quittierten Dokumentrevision, sonst 409/412. Besteht E2E-067 und den XML-Teil von PH-AT-019; Teil von COST-07.
- **Abhängigkeiten:** intern: WP-M8a-09.
  - extern: WP-M4-27, WP-M4-34, WP-M4-38 (OCR-Adapter, Freigabe-Workflow, UI-14)
- **Entscheidungen:** D-06 (zulässige Profile) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Validierung der XML-Struktur (WP-M8a-09); Vier-Augen-Teil von PH-AT-019 (WP-M4-34, WP-M8a-11).

### WP-M8a-11 – Freigabestufen nach Mandant, Betrag, Kostenstelle, Kostenart und Vertretung
- **Ziel:** Die Zahl und Besetzung der Freigabestufen ergibt sich regelbasiert aus Mandant, Betrag, Kostenstelle, Kostenart und Vertreterregelung; eine Schwellenüberschreitung löst automatisch eine zweite Stufe aus.
- **Anforderungen:** `LH-I.1-DOC-018`
- **Liefergegenstände:**
  - Backend: Regelwerk Freigabestufen im Modul `documents-finance`: Ermittlung der Stufenfolge und der zuständigen Rollen/Personen je Beleg; Vertreterregelung mit Gültigkeitszeitraum; Verbot der Selbstfreigabe auch über Vertretung (Kap. 03 §3 Nr. 4); Neuberechnung bei Änderung von Betrag oder Kostenobjekt vor Freigabe.
  - Datenbank: `approval_step` (Stufe, Regelrevision, zuständige Rolle/Person, Vertreter-für, Entscheidung, Begründung, Zeit); Vertretungsregel (Arbeitsname, Intervall, Umfang); `config_revision` für die Schwellen.
  - API: `/v1/expenses/{expense_id}/approve` (Stufenbezug, `WAITING_SECOND_APPROVAL`, 403 bei Selbstfreigabe); `/v1/expenses/{id}` (ausstehende Stufen und Zuständige); neuer Pfad Vertretungsregeln.
  - Oberfläche: UI-14 – Anzeige der Stufenfolge und der zuständigen zweiten Person; Vertretungshinweis.
  - Konfigurationsparameter: `approval_stage_thresholds` (Ebene Mandant/Kostenstelle), `expense_claim_limit`.
  - Ereignisse/Outbox: Freigabestufe angefordert; Freigabestufe entschieden.
- **Detailverträge vorab:** Regelschema (Dimensionen, Präzedenz bei mehreren Treffern); Abbildung der Dimension Kostenart, die `approval_stage_thresholds` laut Katalog nicht trägt (OP-15); Vertretungsmodell; Abgrenzung zur P1-Schwelle aus WP-M4-34.
- **Akzeptanzkriterien:**
  1. Ein Betrag über der konfigurierten Schwelle löst automatisch eine zweite Freigabestufe aus; ohne sie wird nicht gebucht und nicht exportiert (DOC-018).
  2. Unterschiedliche Schwellen je Mandant, Kostenstelle und Kostenart führen im Test zu unterschiedlichen Stufenfolgen (DOC-018).
  3. Ein Vertreter kann nur im Gültigkeitszeitraum und Umfang der Vertretung freigeben; er kann keinen Beleg freigeben, den er selbst erfasst oder in erster Stufe freigegeben hat (DOC-018).
  4. Eine Schwellenänderung wirkt ab Wirksamkeitsbeginn; bereits entschiedene Stufen bleiben mit ihrer Regelrevision nachvollziehbar.
  5. Wird der Betrag nach der ersten Freigabe über die Schwelle angehoben, ist eine neue Freigabe der neuen Dokumentrevision erforderlich.
- **Tests:** positiv: einstufig unter Schwelle, zweistufig über Schwelle, Vertretung. negativ: Selbstfreigabe direkt und über Vertretung, abgelaufene Vertretung, nicht zuständige Kostenstelle, Freigabe einer veralteten Revision. konkurrierend (Geld/Sicherheit): zwei Freigeber derselben Stufe parallel – genau eine Entscheidung; zweite Stufe parallel zu Betragsänderung – 412. Regression E2E-068; Teil von PH-AT-019 und COST-07.
- **Abhängigkeiten:** intern: WP-M8a-06, WP-M8a-07.
  - extern: WP-M4-34 (Freigabe-Workflow mit Vier-Augen-Schwelle)
  - extern: WP-M1-07, WP-M1-08, WP-M1-20 (Rollenmodell, Objektberechtigung, Konfigurationsrevisionen)
- **Entscheidungen:** D-06 (Freigabeschwellen und Rollen) – Bau mit Testwerten; Produktivschaltung blockiert.
- **Größe:** M
- **Nicht im Umfang:** Budgetprüfung als Freigabevoraussetzung (WP-M8a-34, WP-M8a-35); Freigabe von Einkaufsanforderungen (WP-M7c-51).

### WP-M8a-12 – Zugriffsschutz für Rechnungsdaten nach Mandant, Rolle und Kostenstelle
- **Ziel:** Vertrauliche Rechnungsinformationen sind nur für Benutzer des eigenen Mandanten mit passender Rolle und Kostenstellenberechtigung sichtbar – in API, Listen, Suche, Berichten und Exporten.
- **Anforderungen:** `LH-I.1-DOC-028`
- **Liefergegenstände:**
  - Backend: Objektberechtigung im Modul `documents-finance` für Rechnungen, Belege, Kostenereignisse, Lieferanten und Exporte nach Rolle und Kostenstelle; serverseitige Filterung aller Listen- und Suchabfragen; fremde IDs antworten wie nicht vorhanden; Zugriffsversuche als Sicherheitsereignis.
  - Datenbank: RLS über `current_tenant()` als erste Schicht; Kostenstellenbezug an `supplier_invoice` und `economic_event` über `cost_object`; Spalten-/Viewrechte für Preise, Bankverbindung, Margen (Kap. 04 §4.6); `audit_event`.
  - API: `/v1/expenses/{id}`, `/v1/cost-events`, `/v1/ledger/exports`, `/v1/vehicles/{id}/total-cost`, `/v1/reports/jobs` – einheitliche Prüfung; signierte Dokument-Downloads mit kurzer Gültigkeit.
  - Oberfläche: UI-14 und UI-15 – Rechtezustände „keine Berechtigung“, „fehlende Partnerrechte“ ohne Preisgabe fremder Inhalte.
- **Detailverträge vorab:** Rechtematrix Finanzdaten (Rolle × Kostenstelle × Aktion); Definition „nicht freigegebene Finanzdaten“ und des Freigabewegs zwischen Unternehmen (OP-16); Verhalten bei Belegen mit Split über mehrere Kostenstellen.
- **Akzeptanzkriterien:**
  1. Ein Disponent sieht keine nicht freigegebenen Finanzdaten eines anderen Unternehmens – weder über Detail, Liste, Suche, Bericht, Export noch Dokument-Download (DOC-028).
  2. Ein Finanzbenutzer mit Recht nur für Kostenstelle A sieht keine Rechnungen, die ausschließlich Kostenstelle B betreffen (DOC-028).
  3. Ein Zugriffsversuch auf eine fremde Rechnungs-ID liefert dieselbe Antwort wie eine nicht existierende ID und wird ohne Fremdinhalt protokolliert.
  4. Lieferantenpreise, Rohbelege und Margen eines anderen Betreibers erscheinen in keiner Sicht ohne ausdrückliche Freigabe (Kap. 03 §2; COST-06).
- **Tests:** positiv: berechtigter Zugriff je Rolle. negativ: fremder Mandant, fremde Kostenstelle, manipulierte `tenant_id` im Request (Contract-Fall Kap. 05 §5.8), unzulässige Fremd-ID, Export durch nicht berechtigte Rolle, abgelaufener Download-Link. konkurrierend (Datentrennung): Entzug der Kostenstellenberechtigung während laufender Listenabfrage und laufendem Exportjob – kein Datensatz nach Entzug ausgeliefert; Tenantkontext nach Pool-Wiederverwendung. Regression PH-AT-001 für die Finanztabellen.
- **Abhängigkeiten:** intern: WP-M8a-07.
  - extern: WP-M1-07, WP-M1-08, WP-M1-11, WP-M1-31 (Rollenmodell, Objektberechtigung, Isolation außerhalb der Datenbank, Feldschutz)
  - extern: WP-M8b-25 (Zugriffsbeschränkung nach Kostenstelle und Vertragsbeziehung, GOV-007 – gemeinsame Rechtematrix, eine Implementierung der Kostenstellenprüfung, OP-16)
- **Entscheidungen:** D-07 (welche Organisation welche konsolidierten Werte sehen darf) – blockiert die Produktivschaltung konsolidierter Sichten.
- **Größe:** M
- **Nicht im Umfang:** Partnerportal und Gegenberichte (WP-M6c-31); konsolidierte Ressourcensicht über Rechtsträger (OP-21).

### WP-M8a-13 – Gegenbelege für Erstattung, Storno und Gutschrift
- **Ziel:** Erstattungen, Stornos und Gutschriften entstehen als verknüpfte negative Gegenbelege; die Originalrechnung bleibt unverändert und Berichte berücksichtigen beide korrekt.
- **Anforderungen:** `LH-I.1-DOC-030`
- **Liefergegenstände:**
  - Backend: Befehl „Gegenbeleg erstellen“ im Modul `documents-finance` mit Art (Erstattung, Storno, Gutschrift), Voll- oder Teilbetrag, Bezug auf Originalbeleg und Originalereignis; Gegenereignis mit umgekehrtem Vorzeichen; Rücknahme von Allokationen mit den gespeicherten ursprünglichen Teilbeträgen (Kap. 03 §2.1); optionaler Ersatzbeleg; nach Periodenschluss Buchung in der nächsten offenen Periode mit Ursprungsleistungszeitraum (Kap. 03 §3 Nr. 7).
  - Datenbank: `expense_document` (Gegenbelegbezug), `economic_event` (`reverses_id`, `service_from`, `service_to`), `journal`, `journal_line`, `allocation_set`, `allocation_line`, `evidence_link`; Trigger `economic_immutable` bleibt wirksam.
  - API: `/v1/cost-events` (Gegenereignis), neuer Unterpfad Gegenbeleg an `/v1/expenses/{id}`; eingehende Gutschrift über den Rechnungsimport (CII/UBL CreditNote).
  - Oberfläche: UI-14 – Aktion „Gegenbeleg erstellen“ am gebuchten Beleg statt freier Betragsbearbeitung; Verknüpfungsanzeige Original ↔ Gegenbeleg (Kap. 06 §6.6).
  - Ereignisse/Outbox: Gegenbeleg gebucht.
- **Detailverträge vorab:** Schema Gegenbeleg; Regel für Teilgutschriften und für mehrere Gegenbelege je Original (Obergrenze: Summe der Gegenbeträge ≤ Originalbetrag); Berichtsregel Leistungszeitraum gegen Buchungsperiode.
- **Akzeptanzkriterien:**
  1. Nach einer Gutschrift ist die Originalrechnung in Dokument, Ereignis und Journal unverändert; ein negativer, verknüpfter Gegenbeleg existiert (DOC-030).
  2. Berichte weisen Original und Gegenbeleg aus und berücksichtigen den Gegenbeleg im Saldo korrekt; Erstattung, Storno und Gutschrift sind als Art unterscheidbar (DOC-030).
  3. 10.000 Minor Units im Drittelsplit (3.334/3.333/3.333) werden durch den Gegenbeleg exakt mit −3.334/−3.333/−3.333 zurückgenommen (COST-02).
  4. Die Summe aller Gegenbelege überschreitet den Originalbetrag nicht; ein zweiter Vollstorno wird abgewiesen.
  5. September mit 100,00 EUR abgeschlossen, Gutschrift 20,00 EUR im Oktober: Septemberjournal unverändert, Oktoberkorrektur −20,00 EUR mit Ursprungsbezug (PH-AT-015).
- **Tests:** positiv: Vollstorno, Teilgutschrift, Erstattung. negativ: Gegenbeleg auf nicht gebuchten Beleg, Überstorno, Gegenbeleg im falschen Rechtsträger, direkte Änderung des Originals. konkurrierend (Geld): zwei parallele Vollstornos – genau einer wirkt; Gegenbeleg parallel zum Periodenabschluss. Regression E2E-073; Teil von PH-AT-015 und COST-05.
- **Abhängigkeiten:** intern: WP-M8a-03, WP-M8a-06, WP-M8a-07.
  - extern: WP-M4-33, WP-M4-35, WP-M4-36 (Posting, centgenaue Allokation, Fiskalperiode und Gegenereignis)
- **Entscheidungen:** D-06 (Periodenabschluss- und Korrekturregeln) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Fahrgasterstattungen und Refund-Fälle (`refund_case`, WP-M6c-14); Korrekturen der Partnerabrechnung (WP-M6c-20).

### WP-M8a-14 – Tank- und Firmenkartenabrechnung: Import und transaktionsgenauer Abgleich
- **Ziel:** Kartenabrechnungen werden importiert und Transaktion für Transaktion mit Belegen abgeglichen; Treffer, fehlende Belege und Differenzen sind sichtbar, ohne dass Kosten doppelt wirken.
- **Anforderungen:** `LH-I.1-DOC-019`
- **Liefergegenstände:**
  - Adapter/Worker: Importadapter für Kartenabrechnungen (Dateiformat gemäß WP-M8a-01) als fortsetzbarer Job über `operation`; Inbox-Deduplikation je Transaktionsreferenz.
  - Backend: Abgleich im Modul `documents-finance`: Zuordnung Karte → Fahrzeug/Person über Gültigkeitsfenster; Matching Transaktion ↔ Beleg (Karten-/Kassentransaktion, Datum, Betrag, Währung, Lieferant); Ergebnis je Transaktion: Treffer, fehlender Beleg, Differenz; Transaktion und Foto werden als Evidenzen desselben Tankereignisses verknüpft; die Sammelzahlung gleicht Verbindlichkeiten aus und erzeugt nicht erneut Aufwand (Kap. 03 §3 Nr. 3 und 6).
  - Datenbank: Kartenabrechnung (Arbeitsname), `fuel_transaction`, `evidence_link`, `payment_order`, `economic_event` (genau ein Kostenursprung), `inbox`.
  - API: neuer Pfad Kartenabrechnungen (Import, Status, Abgleichsergebnis, manuelle Zuordnung).
  - Oberfläche: UI-14 – Abgleichsliste je Abrechnung mit Treffern, fehlenden Belegen, Differenzen; manuelle Verknüpfung mit Begründung.
  - Konfigurationsparameter: `fuel_card_mapping` (eindeutig pro Gültigkeitsfenster), `receipt_duplicate_threshold`, `fuel_receipt_required`.
  - Kennzahlen: `Tankbeleg Vollständigkeit` (Tankkartenimport einbezogen).
- **Detailverträge vorab:** Importformat(e) und Pflichtfelder (OP-12); Matchingregel und Toleranzen; Behandlung von Firmenkarten ohne Tankbezug; Buchungsschema der Sammelzahlung.
- **Akzeptanzkriterien:**
  1. Eine importierte Abrechnung mit zugehörigen Einzelbelegen zeigt je Transaktion Treffer, fehlende Belege und Differenzen (DOC-019).
  2. Foto, Tankkartentransaktion und wiederholter Freigabejob ergeben genau ein gebuchtes Tankereignis; alle Evidenzen bleiben nachvollziehbar (COST-01; E2E-065).
  3. Tankung brutto 119,00 EUR: genau ein Kostenursprung mit 100,00 EUR Kostenbasis und 19,00 EUR Vorsteuer, Verbindlichkeit 119,00 EUR; die Sammelzahlung erzeugt keinen zweiten Aufwand (PH-AT-012).
  4. Ein erneuter Import derselben Abrechnung erzeugt keine zweite Transaktion.
  5. Eine Karte ohne eindeutige Zuordnung im Transaktionszeitpunkt führt zu einem Klärfall, nicht zu einer geratenen Fahrzeugzuordnung.
  6. Ein ähnlich aussehender zweiter Beleg wird als möglicher Treffer zur Prüfung markiert, nicht automatisch zusammengeführt oder gelöscht (PH-AT-012).
  7. Eine Firmenkartenabrechnung ohne Tankbezug (z. B. Park- oder Mautumsatz) wird ebenso Transaktion für Transaktion mit den Einzelbelegen abgeglichen und zeigt Treffer, fehlende Belege und Differenzen (DOC-019); die Zuordnung Karte → Person/Kostenstelle folgt dem Detailvertrag (OP-12).
  8. `Tankbeleg Vollständigkeit` bezieht importierte Kartentransaktionen ein: eine Transaktion ohne verknüpften Beleg senkt die Quote und erscheint in der Liste fehlender Belege.
- **Tests:** positiv: vollständiger Abgleich, manuelle Zuordnung. negativ: unbekannte Karte, überlappende Kartenzuordnung, Betragsdifferenz, defekte Datei, Abrechnung eines anderen Mandanten. konkurrierend (Geld): Import parallel zur Belegfreigabe derselben Tankung – ein Kostenereignis; zwei parallele Importe derselben Datei. Besteht die Vollform von E2E-065 und PH-AT-012 sowie COST-01.
- **Abhängigkeiten:** intern: WP-M8a-03, WP-M8a-04, WP-M8a-06.
  - extern: WP-M4-30, WP-M4-32, WP-M4-33 (Tankbeleg-Fachdaten, Dublettenerkennung und Evidenzverknüpfung, Posting)
  - extern: WP-M1-14, WP-M1-15, WP-M1-20 (Inbox, `operation`-Ressource, Konfigurationsrevisionen)
- **Entscheidungen:** D-06 (Kartenanbieter, Kontierung der Sammelzahlung) – Bau mit Testformat; Produktivschaltung blockiert.
- **Größe:** M
- **Nicht im Umfang:** Online-Schnittstelle zu Kartenanbietern; Verbrauchsplausibilität (WP-M4-31, WP-M8a-36).

### WP-M8a-15 – Zwei- und Drei-Wege-Abgleich mit Klärfall
- **Ziel:** Bestell-, Lieferschein- und Rechnungspositionen werden abgeglichen; Mengen- und Preisabweichungen erzeugen einen protokollierten Klärfall.
- **Anforderungen:** `LH-I.1-DOC-017`
- **Liefergegenstände:**
  - Backend: Abgleichsdienst im Modul `documents-finance`: Zwei-Wege (Bestellung ↔ Rechnung) und Drei-Wege (Bestellung ↔ Wareneingang/Lieferschein ↔ Rechnung) je Position; Abweichungsarten Menge und Preis; Klärfall mit Zustand, Bearbeiter, Entscheidung und Begründung; Freigabe der Rechnung blockiert, solange ein Klärfall offen ist.
  - Datenbank: Bestell- und Lieferscheinposition als Lesebezug auf die Einkaufsdaten aus M7c bzw. eigene Referenztabelle (Arbeitsnamen, OP-05); Abgleichslauf und Klärfall (Arbeitsnamen); `supplier_invoice`, `evidence_link`, `audit_event`.
  - API: neuer Pfad Abgleich (starten, Ergebnis, Klärfall entscheiden).
  - Oberfläche: UI-14 – Positionsvergleich mit markierten Abweichungen; Klärfall-Arbeitsliste.
  - Ereignisse/Outbox: Abgleichsabweichung erkannt; Klärfall entschieden.
- **Detailverträge vorab:** Positionszuordnung (Artikel-/Bestellposition), Toleranzen für Menge und Preis (OP-17), Zustandsautomat Klärfall, Regel für Teil- und Überlieferung.
- **Akzeptanzkriterien:**
  1. Bestell-, Lieferschein- und Rechnungspositionen lassen sich wahlweise im Zwei- oder Drei-Wege-Verfahren abgleichen (DOC-017).
  2. Eine Mengenabweichung und eine Preisabweichung erzeugen jeweils einen Klärfall mit Soll-/Ist-Werten; Anlage, Bearbeitung und Entscheidung sind protokolliert (DOC-017).
  3. Eine Rechnung mit offenem Klärfall kann nicht freigegeben werden.
  4. Ohne vorhandene Bestellung wird der Abgleich als „nicht anwendbar“ ausgewiesen, nicht als bestanden.
- **Tests:** positiv: übereinstimmender Drei-Wege-Abgleich. negativ: Mengenabweichung, Preisabweichung, fehlender Lieferschein, Bestellung eines anderen Mandanten, Entscheidung ohne Recht. konkurrierend (Geld): Rechnungsfreigabe parallel zum Entstehen eines Klärfalls – Freigabe scheitert.
- **Abhängigkeiten:** intern: WP-M8a-07.
  - extern: WP-M7c-51 (Einkaufsanforderung, Bestellung und Wareneingang)
- **Entscheidungen:** D-06 (Umfang Einkauf im ERP oder in der Plattform) – blockiert die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Einkaufsprozess selbst (WP-M7c-51); automatische Preislisten und Rahmenverträge. SOLL-Anforderung: Abweichung nur mit Begründung und AG-Freigabe (Kap. 08 §8.4).

### WP-M8a-16 – Aufbewahrungsfristen je Dokumentenart und Rechtsraum
- **Ziel:** Beleg- und Rechnungsdokumente tragen eine aus Dokumentenart und Rechtsraum abgeleitete Aufbewahrungsfrist; Löschung erfolgt weder vorzeitig noch unkontrolliert.
- **Anforderungen:** `LH-I.1-DOC-022`
- **Liefergegenstände:**
  - Backend: Fristermittlung im Modul `documents-finance`: Dokumentenart und Rechtsraum (Land des Rechtsträgers) → Regelrevision → frühestes Löschdatum; Schutz gegen Löschung vor Fristende und bei Legal Hold; Löschung nach Fristende ausschließlich über eine bestätigte Löschregel als kontrollierter, protokollierter Job über Datenbank und Objektspeicher (Kap. 01 §1.7 Lebenszyklus); Trennung aufbewahrungspflichtiger Finanzbezüge von entbehrlicher PII.
  - Datenbank: `retention_rule` (Dokumentenart, Rechtsraum, Frist, Fristbeginn-Regel, Revision, Bestätiger), Fristfelder an `expense_document`/`document_revision`; `erasure_job` für den kontrollierten Löschlauf; `audit_event` mit Löschprotokoll (Hash, Regelrevision, ohne Inhalt).
  - Adapter/Worker: Objektspeicher-Retention/Object Lock gemäß Regel; Löschworker mit Lease/Fencing.
  - API: neuer Pfad Aufbewahrungsregeln im Finanzbereich bzw. Konfigurationsregister `/v1/config/revisions`; Löschvorschau (welche Dokumente wären betroffen).
  - Oberfläche: UI-14 – Anzeige von Frist und Sperrgrund am Dokument; UI-20 – Zustand „Legal Hold“.
  - Konfigurationsparameter: `invoice_archive_years` (Ebene Land/Belegart; Rechtsanforderungen vorrangig).
- **Detailverträge vorab:** Katalog der Dokumentenarten; Fristbeginn (Belegdatum, Ende des Geschäftsjahres, OP-18); Schnittstelle zum Legal-Hold-Dienst (WP-M8e-14) und zum orchestrierten Löschjob (WP-M8e-15); Abgrenzung zu den Aufbewahrungsklassen je Datentyp mit Schutz steuerlicher Nachweise (WP-M8b-23) – eine Regelquelle `retention_rule`, kein zweites Fristenregister (OP-41); Bestätigungsverfahren einer Löschregel.
- **Akzeptanzkriterien:**
  1. Unterschiedliche Dokumentenarten und Rechtsräume ergeben im Test unterschiedliche, konfigurierte Fristen am Dokument (DOC-022).
  2. Ein Testdokument vor Fristende lässt sich weder über API noch über den Löschjob noch im Objektspeicher löschen (DOC-022).
  3. Nach Fristende wird ein Testdokument nur gelöscht, wenn eine bestätigte Löschregel vorliegt; die Löschung ist protokolliert, ohne Regel bleibt es erhalten (DOC-022).
  4. Ein Dokument unter Legal Hold wird auch nach Fristende nicht gelöscht.
  5. Eine Verkürzung der Frist wirkt nicht rückwirkend ohne erneute Bestätigung; gebuchte Journale und Ereignisse bleiben von der Dokumentlöschung unberührt nachvollziehbar (Hash und Metadaten).
  6. Steht der Legal-Hold-Dienst nicht zur Verfügung oder antwortet er nicht eindeutig, wird nicht gelöscht (fail-closed); der Löschlauf bleibt wiederaufnehmbar.
  7. Ein Auskunfts- oder Löschersuchen einer betroffenen Person entfernt keine aufbewahrungspflichtigen Finanzbezüge vor Fristende; entbehrliche personenbezogene Angaben werden getrennt behandelt (Abstimmung mit WP-M8b-24).
- **Tests:** positiv: Fristberechnung je Art/Rechtsraum, kontrollierte Löschung nach Frist. negativ: Löschversuch vor Frist, Löschung ohne bestätigte Regel, Legal Hold, negative Frist. konkurrierend (Datentrennung/Sicherheit): Löschjob parallel zum Setzen eines Legal Hold und parallel zum Archivexport – kein Dokument wird trotz Hold gelöscht; zwei Löschworker – genau eine Löschung, ein Protokolleintrag. Regression AT-25, AT-53, AT-61 für Finanzdokumente.
- **Abhängigkeiten:** intern: WP-M8a-06.
  - extern: WP-M1-33 (Aufbewahrungs-, Archivierungs- und Löschregeln P1)
  - extern: WP-M4-23, WP-M4-24 (unveränderliches Original, Hash)
  - extern: WP-M8e-14, WP-M8e-15 (Legal Hold als Port für alle Löschpfade, orchestrierter Löschjob über Datenbank und Objektspeicher)
  - extern: WP-M8b-23 (Aufbewahrungsklassen je Datentyp)
- **Entscheidungen:** D-11 (Löschfristen, Legal Holds) und D-06 (Aufbewahrung, Archivmodell) – Bau mit Testfristen; Produktivschaltung der Löschung blockiert.
- **Größe:** M
- **Nicht im Umfang:** Fristen für Fahrgast-, Fahrer- und Protokolldaten (GOV-005, Governance-Einheit); Betroffenenauskunft (Plattform-Einheit).

### WP-M8a-17 – Revisionsfähiger Archivexport mit Original, Metadaten und Audit-Historie
- **Ziel:** Berechtigte Prüfer erhalten einen Export, der Herkunft und Freigabekette jedes enthaltenen Belegs vollständig rekonstruierbar macht.
- **Anforderungen:** `LH-I.1-DOC-023`
- **Liefergegenstände:**
  - Backend: Archivexport im Modul `documents-finance` als asynchroner Job: Auswahl nach Zeitraum, Rechtsträger, Stichprobe; Paket je Beleg aus unverändertem Original (inklusive XML), Hash, Metadaten (Eingangskanal, Zeiten, OCR-/Validatorversion, Steuerregelrevision), Revisionen, Freigabeschritten, Statushistorie, verknüpften Ereignissen/Journalen und Gegenbelegen; Manifest mit Inhaltshash; Integritätsprüfung der Originale vor Export.
  - Datenbank: `export_job` (Zweck, Empfänger, Umfang, Manifest-Hash), `document_revision`, `approval_step`, `audit_event`, `evidence_link`, `extraction_run`.
  - API: `/v1/reports/jobs`, `/v1/reports/jobs/{id}` (Jobtyp Archivexport; Empfängerrechte, signierter Download).
  - Oberfläche: UI-14 – Exportdialog für die Prüferrolle; UI-20 – begründeter Zugriff.
  - Ereignisse/Outbox: Archivexport bereitgestellt; Abruf protokolliert.
- **Detailverträge vorab:** Paketformat und Manifestschema; Prüferrolle und zeitlich begrenzter Zugriff; Maskierung personenbezogener Audit-Inhalte; Verfahren der Manipulationssicherung (Hash, Signatur – OP-19).
- **Akzeptanzkriterien:**
  1. Ein Stichprobenexport enthält je Beleg Original, Metadaten und Audit-Historie (DOC-023).
  2. Aus dem Export allein lassen sich Herkunft (Kanal, Erfasser, Eingangszeit, Hash) und vollständige Freigabekette (Stufen, Personen, Zeiten, Dokumentrevision) eines Belegs rekonstruieren (DOC-023).
  3. Nur die berechtigte Prüferrolle kann den Export anfordern und abrufen; Anforderung und Abruf sind auditiert.
  4. Der Hash jedes exportierten Originals stimmt mit dem gespeicherten `original_sha256` überein; eine Abweichung bricht den Export mit Integritätsfehler ab.
  5. Der Export enthält keine Belege fremder Mandanten oder nicht berechtigter Rechtsträger.
- **Tests:** positiv: Stichprobenexport mit Rekonstruktionsprüfung per Skript. negativ: fehlende Rolle, manipuliertes Objekt im Speicher, abgelaufener Download-Link, Export über fremden Rechtsträger. konkurrierend (Sicherheit): Rollenentzug während laufendem Job – kein Abruf; Export parallel zur kontrollierten Löschung. Teil von PH-AT-019 (Archivintegrität).
- **Abhängigkeiten:** intern: WP-M8a-11, WP-M8a-12, WP-M8a-16.
  - extern: WP-M4-23, WP-M4-24 (Original, Versionen, Prüfspur, Hash)
  - extern: WP-M1-12, WP-M1-15 (Audit append-only, `operation`-Ressource)
  - extern: WP-M8c-27 (Berichtsjob mit Berechtigungsprüfung über `/v1/reports/jobs`)
- **Entscheidungen:** D-06 (Archivmodell: Favorit unveränderte Originale mit dokumentiertem ERP-/Archivexport) und D-11 – blockieren nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Buchungssatzexport (WP-M8a-19); voll integriertes DMS (Variante in Kap. 03 §8, nicht Favorit).

### WP-M8a-18 – Periodenabschluss mit Abschluss-Snapshot, Korrekturvorgang und Restatement-Ansicht
- **Ziel:** Ein Periodenabschluss sperrt freigegebene Buchungen dauerhaft, hält einen unveränderlichen Abschlussstand fest und lässt Korrekturen nur als eigenständigen, nachvollziehbaren Vorgang zu.
- **Anforderungen:** `LH-I.2-FCO-023`
- **Liefergegenstände:**
  - Backend: Abschlussbefehl im Modul `documents-finance` mit Vorprüfung (offene Belege, `APPROVED_PENDING_POSTING`, offene Klärfälle und Differenzen samt dokumentierter Behandlung), Abschluss-Snapshot der Journalsummen je Rechtsträger und Währung; Korrekturvorgang nach Abschluss als Gegenereignis in der nächsten offenen Periode mit Ursprungsbezug; versionierte Restatement-Ansicht (Abschlussstand gegen heutigen Leistungszeitraumbericht); Wiedereröffnung ist nicht vorgesehen.
  - Datenbank: `fiscal_period` (`closed_at`, Abschließender), Abschluss-Snapshot (Arbeitsname, append-only, mit Inhaltshash), `journal`, `journal_line`, `economic_event` (`reverses_id`, `service_from`/`service_to`); Trigger `journal_guard` prüft die Periodensperre; Invariante „Journal ausgeglichen, ≥2 Zeilen, offene Periode“.
  - API: neuer Pfad Fiskalperioden (Liste, Vorprüfung, Abschluss); `/v1/cost-events` und `/v1/cost-events/{cost_event_id}/allocations` antworten bei geschlossener Periode mit 409 `PERIOD_CLOSED`; Berichtsparameter `as_of` und Sicht „Abschluss“/„Restatement“.
  - Oberfläche: UI-15 – Reiter „Abschluss“ mit Vorprüfliste und Abschlussaktion; Restatement-Ansicht ausdrücklich gekennzeichnet und versioniert; geschlossene Monate schreibgeschützt (Kap. 06 §6.6).
  - Konfigurationsparameter: `finance_close_day` (nach Abschluss nur Korrekturvorgänge).
  - Ereignisse/Outbox: Periode abgeschlossen; Korrektur nach Abschluss gebucht.
- **Detailverträge vorab:** Zustandsautomat Periode; Vorprüfregeln und zulässiger Umgang mit offenen Differenzen; Snapshot-Schema; Definition der Restatement-Ansicht und ihrer Versionierung; Abgrenzung zum technischen Ausschnitt aus WP-M4-36; Modul-API „offene Posten der Periode“ für die Liste ungeklärter Buchungen aus WP-M8d-30 und Festlegung, ob eine nicht leere Liste den Abschluss sperrt oder nur dokumentiert wird; Periodenzuordnung an der Monatsgrenze in `reporting_timezone`.
- **Akzeptanzkriterien:**
  1. Nach dem Abschluss lässt sich keine freigegebene Buchung der Periode ändern, löschen oder ergänzen – weder über API noch direkt unter der Laufzeitrolle (FCO-023).
  2. Eine Korrektur nach Abschluss entsteht ausschließlich als eigenständiger Korrekturvorgang in der nächsten offenen Periode mit Verweis auf Ursprung und Ursprungsleistungszeitraum (FCO-023).
  3. September 100,00 EUR abgeschlossen, Gutschrift 20,00 EUR im Oktober: Abschluss-Snapshot September bleibt 100,00 EUR; die Restatement-Ansicht zeigt 80,00 EUR und ist als solche gekennzeichnet und versioniert (PH-AT-015).
  4. Abschluss und gleichzeitige Freigabe: genau eine Reihenfolge gewinnt; nach Abschluss ist die Periode nicht beschreibbar (COST-05).
  5. Der Abschluss wird abgewiesen, solange offene Differenzen ohne dokumentierte Behandlung bestehen; es gibt kein automatisches Wegbuchen (Kap. 03 §4.3).
  6. Kostenereignis plus seine Allokationen ergeben in Abschluss- und Restatement-Ansicht 100,00 EUR, nicht 200,00 EUR (PH-AT-015).
  7. Ein Ereignis an der Monatsgrenze, auch in der Nacht eines Sommerzeitwechsels, liegt in genau einer Periode; die Zuordnung folgt `reporting_timezone` und ist im Snapshot nachvollziehbar.
  8. Ohne aktives Clearing (M6c nicht ausgerollt) läuft die Vorprüfung ohne Clearingquelle und weist das im Vorprüfprotokoll aus; mit aktivem Clearing sind dessen offene Differenzen Teil der Vorprüfung.
  9. Der Abschluss-Snapshot ist als Lesequelle für Berichte verfügbar; ein Bericht, der während des Abschlusses läuft, liest konsistent den Stand vor oder nach dem Abschluss, nie eine Mischung.
- **Tests:** positiv: Abschluss, Korrektur in Folgeperiode, Restatement-Ansicht. negativ: Buchung in geschlossene Periode (Contract-Fall Kap. 05 §5.8), Abschluss ohne Recht, Abschluss mit offenen Posten, UPDATE am Snapshot. konkurrierend (Geld): Abschluss parallel zu Posting, Allokation und Gegenbeleg; zwei parallele Abschlüsse derselben Periode – genau einer; Bericht parallel zum Abschluss. negativ zusätzlich: Buchung in der Umstellungsnacht an der Monatsgrenze (Beitrag zu E2E-102, führend M8c). Besteht E2E-073 (Regression), den Restatement-Teil von PH-AT-015 und COST-05.
- **Abhängigkeiten:** intern: WP-M8a-13.
  - extern: WP-M4-36 (Fiskalperiode, Periodenabschluss und Gegenereignis als technischer Ausschnitt)
  - extern: WP-M6c-26 (offene Clearing-Differenzen und Reconciliation als Vorprüfquelle – optional, nur bei aktivem Clearing; blockiert den Bau nicht)
- **Entscheidungen:** D-06 (Periodenabschlussregel; Hauptbuch bleibt im ERP) – Bau möglich; Produktivschaltung blockiert.
- **Größe:** M
- **Nicht im Umfang:** Jahresabschluss, Steuerabschluss (ERP, D-06); Abschluss der Partnerabrechnung (WP-M6c-23); Plan/Ist-Darstellung aus PH-AT-015 (WP-M8a-34); Finanzalarme und Liste ungeklärter Buchungen zum Monatsabschluss (Abnehmer WP-M8d-30, liest die Vorprüfung über Modul-API).

### WP-M8a-19 – Dokumentierter, wiederholsicherer Buchungssatzexport
- **Ziel:** Buchungssätze eines abgeschlossenen Zeitraums werden in einem dokumentierten Format an die Finanzbuchhaltung exportiert; eine Wiederholung erzeugt keine doppelten Buchungssätze.
- **Anforderungen:** `LH-I.1-DOC-024`
- **Liefergegenstände:**
  - Adapter/Worker: Adapter `erp-export` mit dokumentiertem neutralem Format (Feldbeschreibung, Zeichensatz, Vorzeichen- und Rundungsregel, stabile Journal-ID je Zeile); fortsetzbarer Exportjob über `operation` mit Lease/Fencing; Verarbeitung von Importbestätigungen.
  - Backend: Exportbatch im Modul `documents-finance` (Zielsystem, Batch-ID, eindeutige Journal-ID je Zeile, Inhaltshash, Empfangsstatus); Regel „ein Journal in genau einem Batch je Zielsystem“; Wiederholung liefert denselben Batch und Datenstand; Folgekorrekturen erscheinen als eigene Journale in späteren Batches; Belegstatus „Export“.
  - Datenbank: Exportbatch und Bestätigung (Arbeitsnamen bzw. `export_job` gemäß WP-M8a-01), Unique `(tenant_id, zielsystem, journal_id)`, `journal`, `journal_line`, `accounting_mapping`, `fiscal_period`.
  - API: `/v1/ledger/exports` (Batch anlegen je Periode, Status, Download, Bestätigung melden; asynchron, `as_of`).
  - Oberfläche: UI-15 – Exportübersicht je Periode mit Batchstatus und Bestätigung.
  - Ereignisse/Outbox: Export bereitgestellt; Import bestätigt.
  - Konfigurationsparameter: `invoice_accounting_schema`.
- **Detailverträge vorab:** Formatdokument (veröffentlichte Spezifikation); Batch- und Bestätigungsschema; Regel für Dedupe im Zielsystem oder dokumentierten manuellen Abgleich (Kap. 03 §2 „Exportbatch / Bestätigung“).
- **Akzeptanzkriterien:**
  1. Derselbe abgeschlossene Monat kann wiederholt exportiert werden; jeder Lauf liefert denselben Batch mit identischen IDs und identischem Inhaltshash, und es entstehen keine doppelten Buchungssätze (DOC-024).
  2. Das Exportformat ist dokumentiert; ein Formatvalidator prüft jeden Batch gegen die Dokumentation (DOC-024).
  3. Nur `POSTED`-Journale vollständig freigegebener Belege werden exportiert; ein Beleg ohne zweite Freigabe erscheint nicht (E2E-068).
  4. Bestätigte Import-IDs werden gespeichert; ein bestätigtes Journal wird in keinem neuen Batch erneut ausgegeben.
  5. Eine Gutschrift nach Abschluss erscheint als eigener Buchungssatz im Batch der Folgeperiode; der Batch der Ursprungsperiode bleibt bitgleich.
  6. Ein Workerabsturz während des Exports führt nach Wiederanlauf zu keinem zweiten Batch.
  7. Anlegen, Herunterladen und Bestätigen eines Batches erfordern die Exportberechtigung; sie ist von Erfassung und Freigabe getrennt (Kap. 03 §3 Nr. 4), und jeder Abruf ist auditiert.
  8. Kalkulatorische Größen (`PLAN`, `FORECAST`, `SIMULATION`, kalkulatorische Kapitalbindung und Ausfallkosten gemäß Detailvertrag) erscheinen nicht im Buchungssatzexport.
- **Tests:** positiv: Export, dreifache Wiederholung mit Hashvergleich, Bestätigung. negativ: Export einer offenen Periode (je nach Vertrag abgewiesen oder als vorläufig gekennzeichnet), Export ohne Recht, fehlende Kontierung, fremder Rechtsträger. konkurrierend (Geld): zwei parallele Exportläufe desselben Monats – ein Batch, kein Journal doppelt; Export parallel zum Periodenabschluss. Regression E2E-068 (Exportteil).
- **Abhängigkeiten:** intern: WP-M8a-07, WP-M8a-12, WP-M8a-18.
  - extern: WP-M4-37 (ERP-Export als wiederholsicherer Job, Grundausbau)
  - extern: WP-M1-15, WP-M1-07 (`operation`-Ressource, Exportberechtigung)
- **Entscheidungen:** D-06 (Ziel-ERP, Kontenrahmen, Export-/Importbestätigungen) – Bau mit neutralem Format; Abnahme gegen Zielsystem und Produktivschaltung blockiert.
- **Größe:** M
- **Nicht im Umfang:** DATEV-Format (WP-M8a-20); Clearing-Statements `/v1/clearing/statements` (M6c); Rückimport von Salden aus dem ERP.

### WP-M8a-20 – DATEV-kompatibler Exportadapter
- **Ziel:** Ein konfigurierbarer Adapter gibt Buchungssätze in einem DATEV-kompatiblen Format aus, das gegen eine definierte Importspezifikation geprüft ist.
- **Anforderungen:** `LH-I.1-DOC-025`
- **Liefergegenstände:**
  - Adapter/Worker: zusätzlicher Formatadapter unter `backend/adapters/erp-export` auf Basis des Exportbatches aus WP-M8a-19; Abbildung von Buchungssatz, Kontierung (Konto/Gegenkonto, Steuerschlüssel), Beleg-ID und Kostenstelle; Aktivierung je Mandant nur bei erfüllten Lizenz- und Formatvorgaben.
  - Backend: Konfiguration des Zielsystems je Rechtsträger; Formatvalidator gegen die Importspezifikation.
  - Datenbank: `accounting_mapping` (Zielkonten, Steuerschlüssel je Zielsystem), `adapter_capability` (Adapter aktiviert/gesperrt).
  - API: `/v1/ledger/exports` (Parameter Zielformat).
  - Konfigurationsparameter: `invoice_accounting_schema`.
- **Detailverträge vorab:** die „definierte Importspezifikation“ (Formatvariante und Version, OP-20); Nachweis der Lizenz-/Nutzungsrechte; Abbildungstabelle Steuerkennzeichen → Steuerschlüssel.
- **Akzeptanzkriterien:**
  1. Ein Testbatch im DATEV-kompatiblen Format besteht die Prüfung gegen die definierte Importspezifikation für Buchungssatz, Kontierung, Beleg-ID und Kostenstelle (DOC-025).
  2. Der Adapter ist je Mandant konfigurierbar und bleibt deaktiviert, solange Lizenz- und Formatvorgaben nicht als erfüllt hinterlegt sind (DOC-025).
  3. Wiederholung des Exports liefert denselben Batch; die Wiederholsicherheit aus WP-M8a-19 gilt unverändert.
  4. Ein Buchungssatz ohne abbildbares Konto oder Steuerschlüssel bricht den Batch mit konkretem Fehler ab, statt einen Platzhalter auszugeben.
- **Tests:** positiv: Referenzbatch gegen Spezifikationsvalidator. negativ: fehlende Abbildung, Feldlängen-/Zeichensatzverletzung, Aktivierung ohne hinterlegte Freigabe. konkurrierend (Geld): paralleler Export in neutralem und DATEV-Format – je Zielsystem ein Batch, kein Journal doppelt je Ziel.
- **Abhängigkeiten:** intern: WP-M8a-19.
  - extern: WP-M6a-02 (Tabelle `adapter_capability` – optional; ohne M6a legt WP-M8a-02 sie an, OP-40)
- **Entscheidungen:** D-06 (Ziel-ERP/DATEV-Variante) – blockiert den Bau der endgültigen Formatvariante und die Produktivschaltung; bis dahin nur Spezifikationsarbeit und Adaptergerüst.
- **Größe:** M
- **Nicht im Umfang:** Belegbild-Übertragung an DATEV; Lohn- und Zahlungsverkehr. SOLL-Anforderung mit Vorbehalt „sofern Lizenz- und Formatvorgaben erfüllt sind“.

### WP-M8a-21 – Abschreibung und kalkulatorische Kapitalbindung je Fahrzeug
- **Ziel:** Aus Anschaffungskosten, Nutzungsdauer, Restwert und Stichtag entsteht je Fahrzeug eine nachvollziehbare Periodenkostenreihe in einer ausdrücklich gewählten Bewertungssicht.
- **Anforderungen:** `LH-I.2-FCO-006`
- **Liefergegenstände:**
  - Backend: Anlagenmodell im Modul `documents-finance`: Fahrzeugparameter (Anschaffungskosten, Aktivierungsstichtag, Nutzungsdauer, Restwert, Methode, kalkulatorischer Zinssatz); Berechnung der Periodenreihe mit Dezimalarithmetik und Restcent-Regel; Erzeugung periodischer Kostenereignisse; Anschaffung als Investition, nicht als Periodenaufwand (Kap. 03 §1.1); Neuberechnung bei Parameteränderung als neue Revision ab Wirksamkeit, geschlossene Perioden unverändert.
  - Datenbank: `asset_schedule` (Fahrzeug, Parameter, Revision, Reihe je Periode in Minor Units), `rate_revision` (kalkulatorischer Zins), `economic_event` (`event_type` Abschreibung bzw. Kapitalbindung, `scenario`, `cost_behavior` `FIXED`, `attribution` `DIRECT`), `cost_object` (`VEHICLE`).
  - API: neuer Pfad Anlagen/Abschreibungsplan je Fahrzeug; `/v1/vehicles/{id}/total-cost` (Komponente Abschreibung/Kapitalbindung).
  - Oberfläche: UI-15 – Abschreibungsplan mit Parametern und Periodenreihe; Kennzeichnung der Bewertungssicht.
- **Detailverträge vorab:** zulässige Methoden und Rundung (OP-22); Bewertungssichten (bilanziell/kalkulatorisch) und ihr Verhältnis zu `scenario`; Regel gegen Doppelzählung mit Leasing.
- **Akzeptanzkriterien:**
  1. Anschaffungskosten, Nutzungsdauer, Restwert und Stichtag ergeben eine Periodenkostenreihe, deren Summe exakt Anschaffungskosten minus Restwert beträgt; jede Periodenzahl ist aus den Parametern nachrechenbar (FCO-006).
  2. Die Reihe nennt Methode, Parameterrevision und Bewertungssicht (FCO-006).
  3. Die Anschaffungssumme erscheint in keinem Kostenbericht zusätzlich zur Abschreibung (Kap. 03 §7 „Vollkosten“).
  4. Leasingrate und Abschreibung desselben wirtschaftlichen Anteils werden im selben TCO-Modell nicht doppelt gezählt; der Versuch, beide für denselben Zeitraum und Anteil zu aktivieren, wird abgewiesen oder als Konflikt ausgewiesen.
  5. Eine Parameteränderung ändert keine Periode, die bereits abgeschlossen ist.
  6. Die kalkulatorische Kapitalbindung wird als eigene Periodenreihe mit Zinssatzrevision aus `rate_revision` und Berechnungsbasis ausgewiesen; sie steht in einer ausdrücklich gewählten Bewertungssicht und wird nicht still zur bilanziellen Abschreibung addiert (FCO-006; Formel gemäß Detailvertrag, OP-22).
- **Tests:** positiv: lineare Reihe mit Restcent, unterjähriger Stichtag, Kapitalbindungsreihe mit Zinssatzwechsel. negativ: Nutzungsdauer 0, Restwert > Anschaffungskosten, Fahrzeug eines anderen Rechtsträgers, Doppelaktivierung mit Leasing. konkurrierend (Geld): Periodenlauf zweimal parallel – genau ein Kostenereignis je Fahrzeug/Periode/Sicht; Lauf parallel zum Periodenabschluss.
- **Abhängigkeiten:** intern: WP-M8a-02, WP-M8a-04, WP-M8a-39.
  - extern: WP-M4-33, WP-M4-40 (Posting, strukturierte Fahrzeugkosten)
  - extern: WP-M3-03 (Fahrzeugstamm)
- **Entscheidungen:** D-07 (Abschreibung/Leasing, Bewertungssichten) – Bau mit Testmethode; Produktivschaltung blockiert.
- **Größe:** M
- **Nicht im Umfang:** Anlagenbuchhaltung im Sinne des Hauptbuchs (ERP, D-06); Lebenszyklusszenarien (`LH-I.2-FCO-022`, P3).

### WP-M8a-22 – Leasingverträge mit Vertragsfortschritt und Mehrkilometerprognose
- **Ziel:** Leasingverträge führen Rate, Laufzeit, Kilometerlimit, Mehrkilometerpreis und Vertragsende; Auswertungen zeigen Vertragsfortschritt und prognostizierte Mehrkilometerkosten.
- **Anforderungen:** `LH-I.2-FCO-007`
- **Liefergegenstände:**
  - Backend: Leasingvertrag im Modul `documents-finance` mit Revisionen; periodische Leasingrate als Kostenereignis; Fortschritt nach Zeit und Kilometern; Prognose der Kilometer zum Vertragsende und der Mehrkilometerkosten mit ausgewiesener Berechnungsbasis.
  - Datenbank: `vehicle_lease` (Fahrzeug, Leasinggeber, Rate, Beginn, Ende, Kilometerlimit, Mehr-/Minderkilometersatz, Revision), `economic_event` (Leasingrate, `FIXED`/`DIRECT`), Kilometerstände aus der Quelle von M4.
  - API: neuer Pfad Leasingverträge; `/v1/vehicles/{id}/total-cost` (Komponente Leasing).
  - Oberfläche: UI-15 – Leasingübersicht mit Fortschritt, Restlaufzeit, Kilometerstand gegen Limit, Prognose.
  - Kennzahlen: keine Katalogkennzahl; Auswertung mit Datenstand und Vollständigkeit.
- **Detailverträge vorab:** Prognosemethode (OP-23); Kilometerquelle und Rangfolge; Behandlung von Vertragsverlängerung und vorzeitigem Ende.
- **Akzeptanzkriterien:**
  1. Ein Vertrag speichert Rate, Laufzeit, Kilometerlimit, Mehrkilometerpreis und Vertragsende (FCO-007).
  2. Die Auswertung zeigt Vertragsfortschritt nach Zeit und Kilometern sowie die prognostizierten Mehrkilometerkosten mit Berechnungsbasis (FCO-007).
  3. Fehlen Kilometerstände, wird die Prognose als nicht berechenbar bzw. `PROVISIONAL` mit Abdeckungsgrad ausgewiesen, nicht als 0.
  4. Zwei gleichzeitig aktive Leasingverträge für dasselbe Fahrzeug sind ausgeschlossen.
  5. Die Leasingrate wird je Periode genau einmal als Kostenereignis gebucht.
- **Tests:** positiv: Vertrag mit Überschreitungsprognose, Vertrag im Limit. negativ: Ende vor Beginn, überlappende Verträge, fehlende Kilometerdaten. konkurrierend (Geld): paralleler Ratenlauf – eine Rate je Periode.
- **Abhängigkeiten:** intern: WP-M8a-02, WP-M8a-04.
  - extern: WP-M4-33, WP-M4-41 (Posting, Kilometerstände mit Herkunft)
  - extern: WP-M3-03 (Fahrzeugstamm)
  - extern: WP-M7c-53 (Vertragskalender für Vertragsende-Warnung)
- **Entscheidungen:** D-07 (Abschreibung/Leasing) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Bilanzierung von Leasing; Fahrzeugüberlassung zwischen Betreibern (Kooperation, M6).

### WP-M8a-23 – Versicherungspolicen, Selbstbehalte und Schadenfallbezug
- **Ziel:** Versicherungspolicen und Selbstbehalte sind mit dem Fahrzeug verknüpft; ein Schadenfall zeigt die zum Schadenzeitpunkt aktive Versicherung und die vereinbarte Selbstbeteiligung.
- **Anforderungen:** `LH-I.2-FCO-008`
- **Liefergegenstände:**
  - Backend: Policenverwaltung (Versicherer, Policennummer, Sparte, Prämie, Selbstbehalt je Sparte, gültig von/bis, zugeordnete Fahrzeuge); Auflösung der aktiven Police zum Schadenzeitpunkt; Prämie als periodisches Kostenereignis; Lesesicht für die Schadenakte über Modul-API (kein Fremdtabellenzugriff).
  - Datenbank: Versicherungspolice und Policen-Fahrzeug-Zuordnung (Arbeitsnamen, Gültigkeitsintervalle ohne Überlappung je Sparte), Bezug zu `defect_case`, `economic_event` (Prämie, `FIXED`/`DIRECT`).
  - API: neuer Pfad Versicherungspolicen; `/v1/defects` (Anzeige aktive Police und Selbstbeteiligung am Fall).
  - Oberfläche: UI-11 – Schadenfall mit Police und Selbstbeteiligung; UI-15 – Policenübersicht je Fahrzeug.
- **Detailverträge vorab:** Schema Police; Sichtbarkeit von Prämien (Finanzrolle) gegenüber Policendaten (Flottenrolle); Abgrenzung zu Versicherungs- und Haftungsangaben der Schadenakte (WP-M7c-41) und zu Versicherungsnachweisen als Fahrzeugdokument (WP-M7c-49).
- **Akzeptanzkriterien:**
  1. Policen, Selbstbehalte und Schadenfälle sind mit dem Fahrzeug verknüpft und im Fahrzeugdetail abrufbar (FCO-008).
  2. Ein Schadenfall führt die zum Schadenzeitpunkt aktive Versicherung und die vereinbarte Selbstbeteiligung auf (FCO-008).
  3. Liegt zum Schadenzeitpunkt keine aktive Police vor, wird das ausdrücklich angezeigt, nicht die zuletzt bekannte Police.
  4. Die Anzeige erzeugt keine automatische Schuld- oder Regresszuweisung.
- **Tests:** positiv: Schadenfall innerhalb der Policenlaufzeit, Policenwechsel zwischen zwei Schäden. negativ: Schaden außerhalb jeder Laufzeit, überlappende Policen derselben Sparte, Prämiensicht ohne Finanzrolle. konkurrierend: Policenwechsel parallel zur Fallanlage – Fall trägt die zum Schadenzeitpunkt gültige Police.
- **Abhängigkeiten:** intern: WP-M8a-02.
  - extern: WP-M3-43, WP-M3-03 (Schadenakte, Fahrzeugstamm)
  - extern: WP-M7c-41, WP-M7c-49 (Versicherungs- und Haftungsangaben der Schadenakte, Fahrzeugdokumente)
  - extern: WP-M4-33 (Posting)
- **Entscheidungen:** D-07 (Zuordnung fixer Fahrzeugkosten) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Schadenregulierung und Kommunikation mit Versicherern; Kooperationshaftung (`partner_liability_policy`, M6).

### WP-M8a-24 – Wartungsregeln nach Kilometer, Betriebsstunden und Kalender
- **Ziel:** Wartungsregeln mit Kilometer-, Betriebsstunden- und Kalenderintervall sowie frühestmöglichem Termin erkennen fällige Wartungen und erzeugen nach Freigabe einen Werkstattauftrag oder eine geplante Sperre.
- **Anforderungen:** `LH-I.2-FCO-009`
- **Liefergegenstände:**
  - Backend: Wartungsregel im Modul `fleet-safety` je Fahrzeug/Fahrzeugtyp (Intervallarten, frühestmöglicher Termin, Vorwarnung); Fälligkeitsberechnung aus Kilometerstand, Betriebsstunden und Datum (es gilt das zuerst erreichte Kriterium); Fälligkeitsvorschlag mit Freigabeschritt; nach Freigabe wahlweise Werkstattauftrag oder geplantes Werkstattfenster/Sperre über den Sperr- und Freigabekern.
  - Adapter/Worker: täglicher Fälligkeitsworker mit Lease/Fencing.
  - Datenbank: Wartungsregel und Wartungsfälligkeit (Arbeitsnamen; Unique je Fahrzeug/Regel/Zyklus), `repair_order`, `resource_release`, `business_alert`.
  - API: neuer Pfad Wartungsregeln und Fälligkeiten; `/v1/repair-orders` (Anlage aus Fälligkeit).
  - Oberfläche: UI-11 – Liste fälliger und bald fälliger Wartungen, Freigabeaktion, Zustand „Frist überschritten“.
  - Konfigurationsparameter: `maintenance_reminder_km` (Ebene Fahrzeugtyp; nicht unter gesetzliche Fristsetzung).
  - Ereignisse/Outbox: Wartung fällig; Wartung freigegeben.
- **Detailverträge vorab:** Quelle der Betriebsstunden (OP-24); Bedeutung „frühestmöglicher Termin“ (OP-24); freigebende Rolle; Verknüpfung mit Werkstattfenstern aus WP-M7c-15.
- **Akzeptanzkriterien:**
  1. Eine Regel kann Kilometer-, Betriebsstunden- und Kalenderintervall sowie einen frühestmöglichen Termin tragen (FCO-009).
  2. Erreicht ein Fahrzeug eines der Intervalle, entsteht genau eine Fälligkeit; nach deren Freigabe entsteht ein Werkstattauftrag oder eine geplante Sperre (FCO-009).
  3. Ohne Freigabe entsteht weder Auftrag noch Sperre; die Fälligkeit bleibt sichtbar offen.
  4. Vor dem frühestmöglichen Termin wird kein Auftrag erzeugt.
  5. Fehlt die Betriebsstundenquelle, wird das Betriebsstundenkriterium als nicht auswertbar gekennzeichnet; Kilometer- und Kalenderkriterium wirken weiter.
  6. Die geplante Sperre läuft über `resource_release`; es entsteht kein zweiter Freigabeweg.
- **Tests:** positiv: Fälligkeit je Intervallart, Freigabe zu Auftrag, Freigabe zu Sperre. negativ: Freigabe ohne Rolle, Intervall 0, fremdes Fahrzeug. konkurrierend (Kapazität/Sicherheit): Worker zweimal parallel – eine Fälligkeit; geplante Sperre parallel zu einer Buchungszusage auf dem Fahrzeug – konsistentes Ergebnis ohne Überbuchung.
- **Abhängigkeiten:** intern: WP-M8a-02.
  - extern: WP-M3-08, WP-M3-51 (Sperr- und Freigabekern, Reparaturauftrag)
  - extern: WP-M4-41 (Kilometerstände mit Herkunft)
  - extern: WP-M7c-15 (Werkstattfenster in der Fahrzeugverfügbarkeit)
- **Entscheidungen:** D-09 (Telematik als Quelle der Betriebsstunden) – blockiert nur das Betriebsstundenkriterium in der Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Kostenführung des Werkstattauftrags (WP-M8a-26); Wartungsverträge mit Werkstätten (WP-M7c-50).

### WP-M8a-25 – Pflichtnachweise mit Fristen, Zuständigen, Eskalation und Freigabesperre
- **Ziel:** TÜV/HU, Sicherheitsprüfung und weitere relevante Nachweise besitzen Fristen, Zuständige und Eskalationen; ein abgelaufener Pflichtnachweis verhindert je Regelwerk eine neue Betriebsfreigabe.
- **Anforderungen:** `LH-I.2-FCO-010`
- **Liefergegenstände:**
  - Backend: Pflichtnachweis im Modul `fleet-safety` je Fahrzeug (Art, gültig bis, Zuständiger, Nachweisdokument); Regelwerk je Nachweisart (warnen oder sperren); Prüfung im Readiness-Gate vor jeder neuen Betriebsfreigabe; Vorwarnung und Eskalationsstufen bei Untätigkeit.
  - Datenbank: Pflichtnachweis und Nachweisregel (Arbeitsnamen bzw. Erweiterung der Fahrzeugdokumente aus M7c gemäß OP-05), `resource_release`, `vehicle_release_decision`, `business_alert`, `alert_policy_revision`.
  - API: neuer Pfad Pflichtnachweise; `/v1/vehicles/{id}/release` (Ablehnung mit Grund „Pflichtnachweis abgelaufen“).
  - Oberfläche: UI-11 – Fristenliste mit Zuständigen, Zustand „Frist überschritten“ und „Freigaberolle fehlt“.
  - Konfigurationsparameter: `inspection_warning_days` (Ebene Mandant, positive Werte), `alert_escalation_delay_min`, `vehicle_readiness_require_checks`.
  - Ereignisse/Outbox: Nachweis läuft ab; Nachweis abgelaufen; Eskalation ausgelöst.
- **Detailverträge vorab:** Katalog der Nachweisarten und ihre Sperrwirkung („gesetztes Regelwerk“, OP-25); Eskalationsstufen und Empfänger; Abgrenzung zu WP-M7c-49 und WP-M7c-53.
- **Akzeptanzkriterien:**
  1. Jeder Pflichtnachweis führt Frist, Zuständigen und Eskalationsstufen (FCO-010).
  2. Ein abgelaufener Pflichtnachweis mit Regel „sperren“ verhindert eine neue Betriebsfreigabe des Fahrzeugs; die Ablehnung nennt den Nachweis (FCO-010).
  3. Mit Regel „warnen“ bleibt die Freigabe möglich und der Hinweis sichtbar; die Regel ist je Nachweisart konfigurierbar (FCO-010).
  4. Vor Ablauf erhält der Zuständige die Vorwarnung gemäß `inspection_warning_days`; bleibt sie unbearbeitet, eskaliert sie an die nächste Stufe.
  5. Fällige HU vor einer Fahrzeugschicht: das System warnt bzw. sperrt gemäß Regel (E2E-071).
  6. Eine bereits erteilte laufende Freigabe wird durch den Ablauf nicht still aufgehoben; die Wirkung auf laufende Einsätze folgt der Regel und wird der Leitstelle angezeigt.
- **Tests:** positiv: Verlängerung vor Ablauf, Sperre nach Ablauf, Wiederfreigabe nach neuem Nachweis. negativ: Freigabeversuch mit abgelaufenem Nachweis, Nachweis ohne Zuständigen, Verlängerung ohne Dokument, fehlende Rolle. konkurrierend (Sicherheit): Ablauf wird parallel zur Freigabeanforderung wirksam – Freigabe nur mit gültigem Nachweis; Offline-Ereignis hebt die Sperre nicht auf. Regression E2E-071 und PH-AT-020.
- **Abhängigkeiten:** intern: WP-M8a-02.
  - extern: WP-M3-08, WP-M3-09, WP-M3-52 (Sperr- und Freigabekern, Readiness-Gate, Alarm-Grundlage)
  - extern: WP-M7c-49, WP-M7c-53, WP-M7c-23 (versionierte Fahrzeugdokumente, Vertragskalender mit Ablaufwarnung, Erinnerungen und Eskalationen)
- **Entscheidungen:** D-11 (Betreiber liefert Freigaberegeln und berechtigte Rollen) – Bau mit Testregelwerk; Produktivschaltung der Sperrwirkung blockiert.
- **Größe:** M
- **Nicht im Umfang:** Dokumentenablage der Nachweise (WP-M7c-49); Fahrerqualifikationen (WP-M3-05).

### WP-M8a-26 – Werkstattauftrag mit Arbeitszeit, Teilen, Fremdleistung, Ausfallkosten und Kostenwirkung
- **Ziel:** Werkstattaufträge führen Arbeitszeit, Teile, Fremdleistung, Ausfallkosten und Status; ein abgeschlossener Reparaturfall aktualisiert Gesamtkosten und Verfügbarkeitsstatistik des Fahrzeugs.
- **Anforderungen:** `LH-I.2-FCO-011`
- **Liefergegenstände:**
  - Backend: Kostenpositionen am Werkstattauftrag (Modul `fleet-safety`) mit Art Arbeitszeit, Teile, Fremdleistung, Ausfallkosten; Abschluss erzeugt über die Modul-API von `documents-finance` Kostenereignisse je Position mit eindeutigem Ursprung; Fremdleistung über die Eingangsrechnung als Evidenz desselben Ursprungs (keine zweite Kostenwirkung); Stillstandszeit als Verfügbarkeitsfakt.
  - Datenbank: `repair_order` (Status, Start/Ende, Fahrzeug), Auftragsposition (Arbeitsname; Menge `numeric(20,8)` mit Einheit, Betrag in Minor Units), `economic_event` (Quelle Werkstattauftrag/Position), `evidence_link` zu `supplier_invoice`, `cost_object` (`VEHICLE`).
  - API: `/v1/repair-orders` (Positionen, Abschluss), `/v1/vehicles/{id}/total-cost`.
  - Oberfläche: UI-11 – Auftragsmaske mit Positionen und Summen je Art; UI-15 – Werkstattkosten im Fahrzeug-TCO.
  - Kennzahlen: `Werkstattquote`, `Fahrzeugverfügbarkeit`, `Ausfallminuten kritisch` (Quelldaten Stillstand).
  - Ereignisse/Outbox: Werkstattauftrag abgeschlossen; Werkstattkosten gebucht.
- **Detailverträge vorab:** Statusautomat des Auftrags (Kostenteil) in Abstimmung mit M3/M7c; Bewertung der Arbeitszeit (Stundensatz über `rate_revision`) und der Ausfallkosten (OP-26); Ursprungsschlüssel zur Vermeidung der Doppelwirkung Auftrag/Rechnung.
- **Akzeptanzkriterien:**
  1. Ein Werkstattauftrag führt Arbeitszeit, Teile, Fremdleistung, Ausfallkosten und Status getrennt (FCO-011).
  2. Nach Abschluss eines Reparaturfalls erhöhen sich die Gesamtkosten des Fahrzeugs genau um die Auftragskosten, und die Verfügbarkeitsstatistik enthält die Stillstandszeit (FCO-011).
  3. Geht zur Fremdleistung später die Werkstattrechnung ein, entsteht kein zweiter Aufwand; die Rechnung wird als Evidenz verknüpft und eine Betragsdifferenz als Korrekturposition gebucht.
  4. Ein erneuter Abschlussbefehl ändert keine Summe.
  5. Korrekturen nach Abschluss erfolgen über Gegenereignis, nicht durch Änderung der Positionen.
  6. Ausfallkosten sind als kalkulatorische Größe gekennzeichnet und erscheinen nicht im Buchungssatzexport, sofern der Detailvertrag nichts anderes bestimmt.
- **Tests:** positiv: Auftrag mit allen vier Arten, spätere Rechnung. negativ: Abschluss ohne Positionen, negative Menge, Position auf fremdem Fahrzeug, Abschluss in geschlossener Periode. konkurrierend (Geld): zwei parallele Abschlüsse – ein Satz Kostenereignisse; Abschluss parallel zur Rechnungsfreigabe derselben Fremdleistung – eine Kostenwirkung. Regression AT-57.
- **Abhängigkeiten:** intern: WP-M8a-04, WP-M8a-24, WP-M8a-39.
  - extern: WP-M3-51 (Reparaturauftrag und Wiederfreigabe)
  - extern: WP-M7c-35, WP-M7c-44, WP-M7c-14 (Werkstattunterlagen am Fall, Kostenarten und Rechnungsfreigabe am Schadenfall, zusammengeführte Verfügbarkeit)
  - extern: WP-M4-33, WP-M4-32 (Posting, Dublettenerkennung/Evidenzverknüpfung)
- **Entscheidungen:** D-07 (Bewertung von Ausfallkosten und internen Stundensätzen) – Bau mit Testsätzen; Produktivschaltung blockiert.
- **Größe:** M
- **Nicht im Umfang:** Werkstattterminplanung und technische Wiederfreigabe (WP-M7c-36); Teileentnahme aus dem Lager (WP-M8a-27).

### WP-M8a-27 – Materiallager: Entnahme mit Werkstattauftrags- und Kostenbezug
- **Ziel:** Ersatzteile werden je Standort mit Mindestbeständen geführt; jede Entnahme ist eindeutig einem Werkstattauftrag und dem Lagerbestand zugeordnet und löst die Kostenzuordnung aus.
- **Anforderungen:** `LH-I.2-FCO-012`
- **Liefergegenstände:**
  - Backend: Erweiterung des Standortlagers (Modul `fleet-safety`) um Pflichtbezug Entnahme → Werkstattauftragsposition; bewerteter Lagerzugang (Einkauf wird als Bestand geführt, nicht als Aufwand); Entnahme erzeugt genau ein Kostenereignis „Ersatzteile“ auf das Fahrzeug des Auftrags (Kap. 03 §1.1); Storno der Entnahme als Gegenbewegung.
  - Datenbank: Lagerartikel und Lagerbewegung aus M7c (Arbeitsnamen, append-only) erweitert um Auftragspositionsbezug und Bewertungsbetrag; `repair_order`; `economic_event` (Quelle Lagerbewegung); `organization` (Depot).
  - API: neuer Pfad Lager aus M7c (Entnahme mit Auftragsbezug); `/v1/repair-orders` (Teileposition aus Entnahme).
  - Oberfläche: UI-11 – Entnahme aus dem Auftrag heraus; Bestand und Mindestbestand je Depot.
  - Konfigurationsparameter: `inventory_min_stock` (Ebene Depot/Artikel, nicht negativ).
- **Detailverträge vorab:** Bewertungsverfahren des Lagerbestands (OP-27); Regel für Entnahmen ohne Werkstattauftrag (Verbrauchsmaterial); Eigentum der Tabellen (OP-05).
- **Akzeptanzkriterien:**
  1. Ein Materiallager mit Ersatzteilen, Mindestbeständen und Entnahmen wird je Standort geführt (FCO-012).
  2. Eine Ersatzteilentnahme ist genau einem Werkstattauftrag zugeordnet und reduziert den Bestand genau eines Lagers um die entnommene Menge (FCO-012).
  3. Die Entnahme erzeugt genau ein Kostenereignis; der vorausgehende Einkauf des Teils hat keinen Periodenaufwand erzeugt (keine Doppelzählung).
  4. Eine Entnahme auf einen abgeschlossenen oder fremden Auftrag wird abgewiesen.
  5. Der Bestand wird nie negativ.
  6. Fällt der Bestand durch eine Entnahme unter `inventory_min_stock`, bleibt die Mindestbestandsmeldung aus WP-M7c-48 wirksam; die Erweiterung um den Auftragsbezug verändert sie nicht (FCO-012).
  7. Die Rücknahme einer Entnahme erzeugt eine Gegenbewegung und ein Gegenereignis mit dem ursprünglichen Bewertungsbetrag; die ursprüngliche Bewegung bleibt unverändert.
- **Tests:** positiv: Entnahme zu Auftrag, Rücknahme. negativ: Entnahme über Bestand, ohne Auftrag (sofern nicht als Verbrauchsmaterial zugelassen), fremdes Depot, abgeschlossener Auftrag. konkurrierend (Kapazität/Geld): zwei parallele Entnahmen der letzten Einheit – genau eine erfolgreich, ein Kostenereignis.
- **Abhängigkeiten:** intern: WP-M8a-26.
  - extern: WP-M7c-48, WP-M7c-51 (Standortlager mit Bestand, Mindestbestand, Entnahme und Inventur; Wareneingang aus dem Einkauf)
  - extern: WP-M4-33 (Posting)
- **Entscheidungen:** D-07 (Lagerbewertung, Energie-/Materialbevorratung) – Bau mit Testverfahren; Produktivschaltung blockiert.
- **Größe:** M
- **Nicht im Umfang:** Inventur und Bestellmeldung (WP-M7c-48); Einkauf (WP-M7c-51).

### WP-M8a-28 – Reifen und saisonale Wechsel als Fahrzeugkomponenten
- **Ziel:** Reifensätze werden als eigene Komponenten mit Laufleistung und Lagerort geführt und sind über mehrere Saisons ohne Doppelbelegung nachvollziehbar.
- **Anforderungen:** `LH-I.2-FCO-013`
- **Liefergegenstände:**
  - Backend: Reifensatz im Modul `fleet-safety` (Kennung, Saisonart, Zustand, Lagerort) mit Montagehistorie (Fahrzeug, von/bis, Kilometerstand bei Montage/Demontage); Laufleistung als Summe der Montageintervalle; Saisonwechsel als ein Vorgang (alter Satz ab, neuer Satz auf, Lagerort setzen).
  - Datenbank: Reifensatz und Reifenmontage (Arbeitsnamen; Exclusion Constraint: ein Satz höchstens an einem Fahrzeug bzw. Lagerort gleichzeitig, ein Fahrzeug höchstens ein montierter Satz).
  - API: neuer Pfad Reifensätze und Wechsel.
  - Oberfläche: UI-11 – Reifenhistorie am Fahrzeug, Lagerübersicht je Depot.
- **Detailverträge vorab:** Granularität (Satz oder Einzelreifen, OP-28); Kilometerquelle; Kostenbezug des Wechsels (Werkstattauftrag).
- **Akzeptanzkriterien:**
  1. Ein Reifensatz führt Laufleistung und Lagerort als eigene Komponente (FCO-013).
  2. Über mindestens drei Saisonwechsel ist lückenlos nachvollziehbar, wann welcher Satz an welchem Fahrzeug oder an welchem Lagerort war (FCO-013).
  3. Derselbe Satz kann nicht gleichzeitig an zwei Fahrzeugen oder zugleich montiert und eingelagert sein; ein Fahrzeug trägt nicht zwei Sätze (FCO-013).
  4. Die Laufleistung ergibt sich nachrechenbar aus den Kilometerständen der Montageintervalle.
- **Tests:** positiv: drei Saisonwechsel mit Laufleistungsprüfung. negativ: Montage eines bereits montierten Satzes, Kilometerstand rückläufig, fremdes Depot. konkurrierend (Kapazität): zwei parallele Montagen desselben Satzes – genau eine.
- **Abhängigkeiten:** intern: WP-M8a-02.
  - extern: WP-M3-03 (Fahrzeugstamm)
  - extern: WP-M4-41 (Kilometerstände mit Herkunft)
  - extern: WP-M7c-46 (Asset-Stamm für kostenrelevante Betriebsmittel)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Profiltiefe als Prüfpunkt der Checkliste (M3/M7c); Reifeneinkauf. SOLL-Anforderung.

### WP-M8a-29 – Betriebliche Ressourcen und Depotbericht
- **Ziel:** Betriebshöfe, Stellplätze, Ladepunkte, Tankstellen und Depots sind als betrieblich nutzbare Ressourcen modelliert; ein Depotbericht zeigt Kapazitäten, Belegung und zugeordnete Fahrzeuge.
- **Anforderungen:** `LH-I.2-FCO-014`
- **Liefergegenstände:**
  - Backend: Ressourcenmodell im Modul `fleet-safety`: Depot/Betriebshof mit untergeordneten Ressourcen der Arten Stellplatz, Ladepunkt, Tankstelle; Kapazität je Art; Zuordnung Fahrzeug → Depot/Stellplatz mit Gültigkeitsintervall; Belegung zu einem Stichtag bzw. Zeitraum; Depot als Kostenobjekt.
  - Datenbank: `organization` (Betriebshof/Depot aus M1), Depotressource (Arbeitsname; Art, Kapazität, Status), `vehicle_assignment` (Fahrzeug ↔ Depot/Stellplatz), `charging_slot` (Ladefenster je Ladepunkt), `cost_object` (`COST_CENTER` bzw. Depotbezug).
  - API: neuer Pfad Depotressourcen; Depotbericht über `/v1/reports/jobs` bzw. synchron als Leseendpunkt.
  - Oberfläche: UI-11 – Depotansicht mit Kapazität, Belegung und Fahrzeugliste; Export.
  - Konfigurationsparameter: `charge_reservation_min` (Ebene Depot).
- **Detailverträge vorab:** Ressourcenarten und Attribute; Definition „Belegung“ (zugeordnet oder tatsächlich anwesend, OP-29); Abgrenzung zum Betriebshofstamm aus WP-M1-09 und zu M7c.
- **Akzeptanzkriterien:**
  1. Betriebshöfe, Stellplätze, Ladepunkte, Tankstellen und Depots lassen sich als Ressourcen mit Kapazität anlegen (FCO-014).
  2. Der Depotbericht zeigt je Depot Kapazitäten je Ressourcenart, Belegung und die zugeordneten Fahrzeuge zum gewählten Stichtag (FCO-014).
  3. Eine Zuordnung über die Kapazität hinaus wird gemäß Detailvertrag abgewiesen oder als Überbelegung ausgewiesen, nie still akzeptiert.
  4. Ein Benutzer ohne Depotberechtigung sieht das Depot nicht im Bericht.
- **Tests:** positiv: Bericht mit zwei Depots, Stichtagswechsel. negativ: Kapazität negativ, Fahrzeug gleichzeitig zwei Depots zugeordnet, fremdes Depot. konkurrierend (Kapazität): zwei parallele Zuordnungen auf den letzten Stellplatz – genau eine.
- **Abhängigkeiten:** intern: WP-M8a-02.
  - extern: WP-M1-09, WP-M1-08 (Organisationsstruktur Betriebshöfe, Objektberechtigung)
  - extern: WP-M3-03, WP-M3-06 (Fahrzeugstamm, Fahrzeugpool-Zuordnung)
  - extern: WP-M7c-15 (Werkstatt-, Reinigungs- und Ladefenster in der Fahrzeugverfügbarkeit)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Ladeplanung und Reichweitenkonflikt (P3, M9); Disposition von Stellplätzen in Echtzeit.

### WP-M8a-30 – Ladepunktkosten ohne Doppelerfassung der Betriebshofenergie
- **Ziel:** Ladekosten werden je Ladevorgang nach Strommenge, Strompreis, Zeit und Fahrzeug bestimmt; dieselbe Energie erscheint nicht zusätzlich als allgemeine Betriebshofenergie.
- **Anforderungen:** `LH-I.2-FCO-020`
- **Liefergegenstände:**
  - Backend: Ladevorgang im Modul `fleet-safety` (Ladepunkt, Fahrzeug, Start/Ende, kWh als `numeric(20,8)`, Messquelle); Bewertung mit zeitabhängigem Strompreis; Kostenereignis „Ladeenergie“ auf das Fahrzeug; Abgrenzungsregel: Betriebshofenergie der Periode = Gesamtbezug minus Summe der Ladevorgänge am selben Zählpunkt; Ladeverluste getrennt ausweisbar.
  - Datenbank: Ladevorgang (Arbeitsname), `rate_revision` (Strompreis je Depot/Zeitfenster), `charging_slot`, Depotressource Ladepunkt, `economic_event` (Quelle Ladevorgang; `VARIABLE`/`DIRECT`), `cost_object`.
  - API: neuer Pfad Ladevorgänge (Erfassung/Import); `/v1/vehicles/{id}/total-cost` (Komponente Energie).
  - Oberfläche: UI-15 – Ladekosten je Fahrzeug mit Menge, Preis, Zeit; Hinweis auf abgegrenzte Betriebshofenergie.
  - Kennzahlen: `Strom kWh/100 km` (validierte kWh, Ladeverluste getrennt auswählbar).
- **Detailverträge vorab:** Quelle der Ladedaten (manuell, Import, Ladeinfrastruktur – OP-30); Zählpunktmodell und Abgrenzungsformel; Behandlung fehlender Messwerte.
- **Akzeptanzkriterien:**
  1. Ein Ladevorgang weist Strommenge, Strompreis, Zeit und Fahrzeug aus; die Kosten sind daraus nachrechenbar (FCO-020).
  2. Die Energiemenge eines Ladevorgangs erscheint genau einmal: als Fahrzeugenergie, nicht zusätzlich in der allgemeinen Betriebshofenergie desselben Zählpunkts und derselben Periode (FCO-020).
  3. Fehlt die Zuordnung eines Ladevorgangs zu einem Fahrzeug, bleibt die Menge als „unzugeordnet“ sichtbar und wird nicht stillschweigend der Betriebshofenergie oder einem Fahrzeug zugeschlagen.
  4. kWh und Liter werden in keiner Kennzahl gemischt (Kap. 03 §2.1).
  5. Übersteigt die Summe der Ladevorgänge den Gesamtbezug des Zählpunkts, wird ein Prüfhinweis erzeugt statt negativer Betriebshofenergie.
- **Tests:** positiv: Ladevorgang mit Tarifwechsel innerhalb des Vorgangs, Periodenabgrenzung. negativ: negativer Messwert, Ladepunkt eines fremden Depots, fehlender Preis (Status `PROVISIONAL`, kein 0-Betrag). konkurrierend (Geld): doppelter Import desselben Ladevorgangs – ein Kostenereignis.
- **Abhängigkeiten:** intern: WP-M8a-04, WP-M8a-29, WP-M8a-39.
  - extern: WP-M4-33, WP-M4-43 (Posting, Energiekosten je 100 km)
  - extern: WP-M7c-15 (Ladeereignisse in der Fahrzeugverfügbarkeit als mögliche Quelle der Ladevorgänge)
- **Entscheidungen:** D-07 (Energiebevorratung, Umlage der Betriebshofenergie), D-09 (Telematik/Ladedatenquelle) – blockieren nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Ladefensterplanung und SoC-Regeln (`min_battery_soc_pct`, `min_return_soc_pct`; P3/M9 bzw. M7c). SOLL-Anforderung.

### WP-M8a-31 – Kostensplit auf Kostenstellen, Projekte, Auftraggeber und Linien/Bediengebiete
- **Ziel:** Kosten und Fahrzeugleistung lassen sich über einen genehmigten Verteilungsschlüssel auf interne Kostenstellen, Projekte, Auftraggeber und Linien/Bediengebiete verteilen – centgenau und mit genau einer wirksamen Verteilung.
- **Anforderungen:** `LH-I.2-FCO-016`
- **Liefergegenstände:**
  - Backend: Verteilungsregeln im Modul `documents-finance` mit Revision, Genehmigung (Vier-Augen) und Wirksamkeit; Treiber aus Leistungsnachweis (z. B. Dienststunden, km) als eingefrorener Snapshot; Anwendung über den Largest-Remainder-Dienst aus M4; genau eine freigegebene Verteilung je Ursprung und Bewertung; Neuverteilung nur als neue Revision mit Rücknahme der alten Teilbeträge.
  - Datenbank: `allocation_rule_revision` (Schlüssel, Treiberart, Empfänger, Genehmiger, gültig von/bis), `allocation_set` (`driver_snapshot`, `algorithm_version`), `allocation_line`, `allocation_activation`, `cost_object` (Kostenstelle, Projekt, Auftraggeber/Vertrag, Linie/Bediengebiet gemäß OP-04); Invariante „Umlage centgenau und final unveränderlich“.
  - API: `/v1/cost-events/{cost_event_id}/allocations` (Regelrevision und Begründung Pflicht; 409 `PERIOD_CLOSED`/`ALREADY_ALLOCATED`); neuer Pfad Verteilungsregeln.
  - Oberfläche: UI-15 – „Umlageschlüssel“ je Kostenobjekt, Verteilungsergebnis mit Leistungsnachweis.
  - Konfigurationsparameter: `cost_split_rule` (Ebene Vertrag; Summe muss 100 % ergeben).
- **Detailverträge vorab:** Regelschema; Genehmigungsrolle; Abbildung von Auftraggeber und Linie/Bediengebiet als Kostenobjekt (OP-04); Abgrenzung Verteilung innerhalb eines Rechtsträgers gegenüber Intercompany (OP-21).
- **Akzeptanzkriterien:**
  1. Kostenstellen, Projekte, Auftraggeber und Linien/Bediengebiete können Empfänger eines Kostensplits sein (FCO-016).
  2. Dieselbe Fahrzeugleistung wird nach genehmigter Verteilung zwei Auftraggebern zugeordnet: 1.680,00 EUR bei 60 h/40 h ergeben 1.008,00 EUR für K1 und 672,00 EUR für K2; beide Zeilen verweisen auf denselben Leistungsnachweis und die Regelrevision (FCO-016; Kap. 03 §6).
  3. Eine nicht genehmigte Verteilungsregel kann nicht angewendet werden; eine Regel mit Summe ≠ 100 % wird abgewiesen.
  4. Die Summe der Teilbeträge entspricht exakt der Kostenbasis; Original und Umlage werden in keiner Standardsicht doppelt summiert (COST-08).
  5. Ein Empfänger eines anderen Rechtsträgers oder Mandanten wird abgewiesen; es entsteht kein Schreibzugriff auf fremde Kostenstellen (Kap. 05 §5.5).
  6. 100,01 EUR auf drei gleiche Anteile ergeben 33,34/33,34/33,33 EUR; ein erneuter Lauf liefert dasselbe Ergebnis, und das Journal ist je Rechtsträger und Währung ausgeglichen (PH-AT-013).
- **Tests:** positiv: Split nach Stunden, nach km, Drittelsplit mit Restcent. negativ: Summe ≠ Basis (Contract-Fall Kap. 05 §5.8), Regel nicht genehmigt, geschlossene Periode, fremder Rechtsträger. konkurrierend (Geld): zwei parallele Verteilungsrevisionen – genau eine aktive Fassung; Regelveröffentlichung parallel zur Anwendung. Regression E2E-072, PH-AT-013; Teil von COST-06.
- **Abhängigkeiten:** intern: WP-M8a-02, WP-M8a-03, WP-M8a-04.
  - extern: WP-M4-35, WP-M4-33 (Belegaufteilung centgenau, Posting)
  - extern: WP-M1-09, WP-M1-22 (Kostenstellen- und Dienststamm, Vier-Augen-Baustein)
- **Entscheidungen:** D-07 (Umlageschlüssel und Treiber; Favorit kostenartbezogene Schlüssel) – Bau mit Testschlüsseln; Produktivschaltung blockiert.
- **Größe:** M
- **Nicht im Umfang:** Intercompany-Belege und konsolidierte Ressourcensicht über Rechtsträger (E2E-094, OP-21); Clearing-Verteilung zwischen Partnern (WP-M6c-19).

### WP-M8a-32 – TCO-Zusammenführung je Fahrzeug und Ausbau UI-15
- **Ziel:** Alle Kostenkomponenten eines Fahrzeugs laufen in einer konsistenten Gesamtkostensicht zusammen, die als Datenbasis für Kosten je Einheit, Budgets und den Deckungsbeitrag dient.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: TCO-Abfrage im Modul `documents-finance`: Komponenten Abschreibung/Kapitalbindung, Leasing, Versicherung, Energie (Kraftstoff, Ladeenergie), Wartung/Werkstatt, Ersatzteile, sonstige Kostenarten, nicht abzugsfähige Steuer; Parameter Bewertungsart, Rechtsträger, Währung, Periode, Datenstand (`as_of`) Pflicht (Kap. 03 §1.1); Status `PROVISIONAL` mit Abdeckungsgrad bei unvollständigen Quellen; Kostenbasis für Erlös-/Deckungsbeitragsrechnung als Modul-API an Analytics.
  - API: `/v1/vehicles/{id}/total-cost` (vollständiger Vertrag mit Komponenten, Kreuztabelle fix/variabel × direkt/umgelegt, Vollständigkeit, Regelversion); `/v1/cost-events` (Drilldown).
  - Oberfläche: UI-15 TCO/Deckungsbeitrag – Ausbau: Umschalter Ist gebucht / Plan / Abweichung, Stand und Regelversion, Komponentenansicht, Drilldown auf Ursprungsereignisse und Umlageschlüssel, Export; Zustände Datenlücke, Nenner 0, fehlende Partnerrechte (Kap. 06 §6.6, §6.9); Deckungsbeitrag und Zuschussbedarf werden in UI-15 aus dem Kennzahlregister (WP-M8c-14) eingebunden und mit Definitionsversion angezeigt, nicht in diesem WP berechnet – liegt die Kennzahl nicht vor, zeigt UI-15 „nicht verfügbar“ (OP-39).
  - Kennzahlen: Kostenbasis für `Deckungsbeitrag`, `Zuschuss pro Fahrgastfahrt`, `Kosten pro Fahrzeugkilometer`, `Kosten pro Fahrgastfahrt`, `Kosten pro Fahrgastkilometer`.
- **Detailverträge vorab:** Antwortschema `total-cost`; Definition „Vollkosten“ (Kap. 03 §7) als versionierte Regel; Wireframe UI-15 Ausbau.
- **Akzeptanzkriterien:**
  1. Für ein Testfahrzeug ergibt die Summe der Komponenten exakt die Summe der wirksamen Ist-Kostenereignisse der Periode; keine Investitionssumme zusätzlich zur Abschreibung, keine Zahlungs- oder Transferbeträge.
  2. Rechenbeispiel B: Y weist 1.680,00 EUR operative Vollkosten aus; Xs eigene 400,00 EUR erscheinen nicht bei Y (COST-06).
  3. Plan und Ist werden als zwei Größen dargestellt und nie addiert (PH-AT-015: 90,00 EUR Plan und 100,00 EUR Ist, nicht 190,00 EUR).
  4. Bei fehlenden Energiedaten wird die Komponente als fehlend markiert, nicht als 0 (COST-08).
  5. Eine Abfrage ohne Bewertungsart, Periode oder Währung wird abgewiesen.
  6. Die Kreuztabelle zeigt fix/variabel × direkt/umgelegt als vier Zellen; Originalposition und Umlage derselben Kostenposition werden nicht zusammengezählt (Kap. 06 §6.6; COST-08).
  7. Die Modul-API „Kostenbasis“ liefert je Kostenobjekt, Periode und Bewertungsart dieselben Summen wie `/v1/vehicles/{id}/total-cost` samt Datenstand und Regelversion; darauf bauen WP-M8c-13 und WP-M8c-14 auf.
  8. Ein Benutzer ohne Finanz- bzw. Kostenstellenrecht erhält weder TCO noch Komponenten; fremde Lieferantenpreise und Margen erscheinen in keiner Komponente (Rechteprüfung aus WP-M8a-12).
- **Tests:** positiv: TCO eines Fahrzeugs mit allen Komponenten, UI-E2E. negativ: fehlende Pflichtparameter, fremdes Fahrzeug, fehlendes Finanzrecht, gemischte Währungen. konkurrierend (Geld): Abfrage mit `as_of` während laufender Buchungen liefert einen konsistenten Stand. Barrierefreiheitsprüfung der UI-15-Erweiterung in CI.
- **Abhängigkeiten:** intern: WP-M8a-12, WP-M8a-21, WP-M8a-22, WP-M8a-23, WP-M8a-26, WP-M8a-30, WP-M8a-31.
  - extern: WP-M4-44, WP-M4-40, WP-M4-43 (UI-15 Grundausbau, strukturierte Fahrzeugkosten, Energiekosten)
  - extern: WP-M1-43 (Web-Designsystem)
- **Entscheidungen:** D-07 (Deckungsbeitragsstufen, Kostenbewertung) – blockiert nur die Produktivschaltung verbindlicher Kostenberichte.
- **Größe:** M
- **Nicht im Umfang:** Kennzahldefinitionen `Deckungsbeitrag`, Zuschussbedarf und Wirtschaftlichkeit je Vertrag (KPI-027, KPI-Einheit von M8); Erlösdaten (M2/M6c); CO2e (`LH-I.2-FCO-021`, P3).

### WP-M8a-33 – Kosten je Fahrzeugkilometer, Fahrgastfahrt, Fahrgastkilometer und Betriebsstunde
- **Ziel:** Die vier Einheitskosten werden nach festen Bezugsgrößen berechnet; fehlende Kostendaten und Null-Divisoren erscheinen nie als scheinbar gültige Zahl.
- **Anforderungen:** `LH-I.2-FCO-015`
- **Liefergegenstände:**
  - Backend: Einheitskostenrechnung im Modul `documents-finance` mit Lieferung an das Kennzahlregister: Zähler Vollkosten aus WP-M8a-32, Nenner aus der Analytics-Faktenbasis für dieselbe Periode und denselben Umfang; Ergebnisobjekt mit Wert oder `null`, Statuscode (`ZERO_DENOMINATOR`, `PROVISIONAL`), Abdeckungsgrad, Zähler, Nenner, Regelversion, Datenstand; Dezimalarithmetik, Anzeige gerundet, Export mit Berechnungsbasis.
  - Datenbank: `metric_definition_revision` (Definition der vier Kennzahlen als Revision), `aggregate_snapshot`, `dataset_watermark` (lesend/über Modul-API der Analytics).
  - API: `/v1/analytics/metrics` (vier Kennzahlen mit Nenner, Datenstand, Vollständigkeit); `/v1/vehicles/{id}/total-cost` (Einheitskosten je Fahrzeug).
  - Oberfläche: UI-15 – Kosten pro km mit Bezugsstrecke, Kilometerklasse, Nenner und Datenstand; „nicht berechenbar“ bei fehlendem Nenner (Kap. 06 §6.6).
  - Kennzahlen: `Kosten pro Fahrzeugkilometer`, `Kosten pro Fahrgastfahrt`, `Kosten pro Fahrgastkilometer`; Kosten je Betriebsstunde (nicht im Katalog der 48 Kennzahlen, OP-31).
- **Detailverträge vorab:** feste Bezugsgrößen je Kennzahl (relevante Fahrzeugkilometer, abgeschlossene Fahrgastfahrten, Fahrgastkilometer, Betriebsstunde – OP-31); Schwelle für `PROVISIONAL`; Zählung Segmentfahrt gegen Gesamtreise.
- **Akzeptanzkriterien:**
  1. Mit 1.680,00 EUR, 2.000 Fahrzeug-km, 300 Fahrgastfahrten, 2.500 Fahrgast-km und 100 Dienststunden ergeben sich 0,84 EUR/Fahrzeug-km, 5,60 EUR/Fahrgastfahrt, 0,672 EUR/Fahrgast-km und 16,80 EUR/Stunde; der Export enthält 0,672 und die Berechnungsbasis (FCO-015; Kap. 03 §6).
  2. Bei Nenner 0 ist das Ergebnis `null` mit `ZERO_DENOMINATOR`; es wird keine 0 und kein Unendlich ausgegeben (FCO-015).
  3. Bei fehlenden Kostendaten trägt das Ergebnis den Status `PROVISIONAL` mit Abdeckungsgrad und wird in UI und Export als unvollständig gekennzeichnet (FCO-015).
  4. Zähler und Nenner beziehen sich nachweislich auf dieselbe Periode und denselben Umfang; eine Abfrage mit abweichendem Umfang wird abgewiesen.
  5. Eine Fahrgastfahrt über zwei Segmente wird in der Kennzahl je Fahrgastfahrt einmal gezählt; die Segmentzählung ist eine ausdrücklich so benannte eigene Größe.
- **Tests:** positiv: Rechenbeispiel B. negativ: Nenner 0 je Kennzahl, fehlende Energiedaten, gemischte Perioden, gemischte Währungen, fehlendes Finanzrecht, fremdes Fahrzeug. konkurrierend (Geld): Abfrage mit `as_of` während laufender Buchungen und während des Periodenabschlusses – Zähler und Nenner stammen aus demselben Datenstand. Regression PH-AT-021 (Nenner, Datenlücke); besteht COST-08.
- **Abhängigkeiten:** intern: WP-M8a-32.
  - extern: WP-M4-45, WP-M4-46, WP-M4-42, WP-M4-56 (Analytics-Faktenbasis, Kennzahlregister, Kilometerklassifikation, Kilometer-/Betriebsstunden-/Fahrgastkilometer-Kennzahlen)
  - extern: WP-M8c-04 (Kennzahlregister P2 mit Definitionsrevision und Snapshot – die dortigen Abnehmer „Kosten pro Fahrt und Fahrgastkilometer“ und „Katalogvervollständigung“ sind keine Vorgänger – eine Berechnung, keine zweite Formel, OP-43)
- **Entscheidungen:** D-07 (Kostenbasis, Leer-/Besetztkilometer) – blockiert nur die Produktivschaltung verbindlicher Berichte.
- **Größe:** M
- **Nicht im Umfang:** Wirtschaftlichkeit je Vertrag mit Zuschussbedarf und Deckungsbeitrag (KPI-027) und Energiekennzahlen mit Plausibilitätsgrenzen (KPI-028) – KPI-Einheit von M8.

### WP-M8a-34 – Vertragsbudgets mit Soll, Ist, Forecast und Restbudget
- **Ziel:** Budgets werden je Vertrag bzw. Projekt und Periode mit Soll, Ist, Forecast und Restbudget getrennt geführt; der Monatsbericht stimmt nach Buchungsabschluss mit den genehmigten Einzelkosten überein.
- **Anforderungen:** `LH-I.2-FCO-017`
- **Liefergegenstände:**
  - Backend: Budgetmodell im Modul `documents-finance`: Budget je Vertrag/Projekt, Periode, Rechtsträger, Währung; Soll als genehmigte Revision (`PLAN`); Ist als Projektion der `POSTED`-Kostenereignisse und Allokationen; Forecast = Ist bis Stichtag plus Restprognose, alternative Gesamtmonatsprognose als eigene Version (Kap. 03 §1.1); Restbudget = Soll minus Ist; Abweichung absolut und prozentual, bei Soll 0 nur absolut; Budgetalarm bei Überschreitung.
  - Datenbank: Budget und Budgetposition (Arbeitsnamen; Revision, Genehmiger), `forecast_scenario` (Version, Stichtag, Methode), `economic_event` (`scenario` `PLAN`/`FORECAST` getrennt von `ACTUAL`), `cost_object` (`PROJECT` bzw. Vertrag gemäß OP-04), `fiscal_period`, `business_alert`.
  - API: neuer Pfad Budgets (Entwurf, Genehmigung, Bericht je Periode mit `as_of`, Snapshot und Regelversion); Monatsbericht über `/v1/reports/jobs`.
  - Oberfläche: UI-15 – Budgetansicht Soll/Ist/Forecast/Rest je Periode und Projekt, Abweichung, Drilldown auf Einzelkosten; Export mit Snapshot und Regelversion.
  - Konfigurationsparameter: `cost_overrun_alert_pct` (Ebene Kostenstelle; konsistente Soll/Ist-Basis).
  - Kennzahlen: `Budgetabweichung` (Istkosten minus periodisches Sollbudget; bei null Soll absolut).
- **Detailverträge vorab:** Budgetschema und Genehmigungsweg; Forecastmethode (OP-32); Bezug „Vertrag“ (Auftraggebervertrag, nicht `partner_contract_version` – OP-04); Definition „Buchungsabschluss“ im Sinne der Abnahme (Periodenabschluss aus WP-M8a-18).
- **Akzeptanzkriterien:**
  1. Soll-, Ist-, Forecast- und Restbudget sind je Periode und Projekt getrennte Größen und werden getrennt ausgewiesen (FCO-017).
  2. Nach Periodenabschluss entspricht der Ist-Wert des Monatsberichts centgenau der Summe der genehmigten, gebuchten Einzelkosten des Projekts; jeder Betrag ist per Drilldown auf die Einzelereignisse rückführbar (FCO-017).
  3. Planbudget 90,00 EUR und Istkosten 100,00 EUR werden als zwei Größen mit Abweichung 10,00 EUR dargestellt, nicht zu 190,00 EUR addiert (PH-AT-015).
  4. Forecast addiert keine Gesamtmonatsprognose auf die Istkosten; die alternative Prognose erscheint als eigene Version.
  5. Bei Soll 0 wird die Abweichung nur absolut ausgewiesen; keine Division durch null.
  6. Wird die Schwelle `cost_overrun_alert_pct` überschritten, entsteht genau ein Budgetalarm je Budget und Periode.
  7. Eine spätere Gutschrift verändert den Bericht des abgeschlossenen Monats nicht; sie erscheint in der Folgeperiode bzw. in der gekennzeichneten Restatement-Ansicht.
  8. Eine genehmigte Budgetrevision ist unveränderlich; eine Budgetänderung entsteht als neue Revision mit Genehmiger und Wirksamkeit, und ältere Berichte bleiben mit ihrer Revision reproduzierbar.
  9. Export und UI nennen Snapshot und Regelversion (Kap. 03 §7 „Budget“); ein Benutzer ohne Recht auf das Projekt bzw. die Kostenstelle sieht das Budget nicht.
- **Tests:** positiv: Monatsbericht nach Abschluss mit Abstimmung gegen Journale, Forecastversionen. negativ: Budget ohne Genehmigung, Soll 0, gemischte Währungen, Projekt eines anderen Rechtsträgers, Ist enthält keine `PLAN`-/`FORECAST`-Ereignisse. konkurrierend (Geld): Bericht parallel zum Periodenabschluss – konsistenter Snapshot; zwei parallele Buchungen über die Alarmschwelle – ein Alarm. Besteht den Plan/Ist-Teil von PH-AT-015.
- **Abhängigkeiten:** intern: WP-M8a-18, WP-M8a-31.
  - extern: WP-M4-33, WP-M4-46 (Posting, Kennzahlregister)
  - extern: WP-M3-52 (Alarm-Grundlage)
  - extern: WP-M8c-27 (Berichtsjob mit Berechtigungsprüfung über `/v1/reports/jobs`)
- **Entscheidungen:** D-07 (Kostenbewertung, Plan-/Forecast-Sichten) und D-06 (Kostenstellen, Projekte) – Bau mit Testbudgets; Produktivschaltung blockiert.
- **Größe:** L
- **Nicht im Umfang:** Nachfrage- und Leistungsprognose (KPI-017, P3-Prognose M9); Fördermittel- und Zuschussabrechnung; Gutscheinbudgets (WP-M6c-09); Soll/Ist/Prognose-Vergleich des Statistikbereichs (Abnehmer WP-M8c-19), Prüfworkflow für Finanzschwellen und Finanzalarme zum Monatsabschluss (Abnehmer WP-M8d-12, WP-M8d-30).

### WP-M8a-35 – Kostenverantwortliche, Freigabezuständigkeiten und Weiterleitung von Kostenüberschreitungen
- **Ziel:** Auf Fahrzeug-, Standort- und Auftragsebene ist sichtbar, wer Kosten verantwortet und wer freigibt; eine Anfrage zu einer Kostenüberschreitung erreicht automatisch die zuständige Rolle.
- **Anforderungen:** `LH-I.2-FCO-024`
- **Liefergegenstände:**
  - Backend: Zuständigkeitsmodell im Modul `documents-finance`: Kostenverantwortung und Freigabezuständigkeit je Ebene (Fahrzeug, Standort, Auftrag) mit Gültigkeit und Vertretung; Auflösung der zuständigen Rolle mit Präzedenz (Auftrag vor Fahrzeug vor Standort, als Vorschlag dieses Plans – OP-33); Anfrage „Kostenüberschreitung“ als Vorgang mit Zustand, Empfänger, Frist und Antwort; Weiterleitung über die Alarm-/Benachrichtigungsinfrastruktur; Eskalation bei Untätigkeit.
  - Datenbank: Kostenverantwortung (Arbeitsname; Ebene, Objekt, Rolle/Person, gültig von/bis), Überschreitungsanfrage (Arbeitsname), `business_alert`, `alert_acknowledgement`, `audit_event`.
  - API: neuer Pfad Kostenverantwortliche und Überschreitungsanfragen; `/v1/vehicles/{id}/total-cost` (Anzeige Verantwortlicher).
  - Oberfläche: UI-15 – Verantwortlicher und Freigabezuständiger am Fahrzeug, Standort und Auftrag; Aktion „Anfrage stellen“; Arbeitsliste der eingegangenen Anfragen.
  - Konfigurationsparameter: `cost_overrun_alert_pct`, `alert_escalation_delay_min`.
  - Ereignisse/Outbox: Kostenüberschreitung angefragt; Anfrage beantwortet.
- **Detailverträge vorab:** Bedeutung „Auftragsebene“ (Werkstattauftrag, Verkehrsauftrag/Vertrag – OP-33); Auslöser der Anfrage (manuell, Budgetalarm oder beides); Präzedenz und Verhalten ohne hinterlegte Zuständigkeit.
- **Akzeptanzkriterien:**
  1. Für ein Fahrzeug, einen Standort und einen Auftrag zeigt die Software jeweils Kostenverantwortlichen und Freigabezuständigen (FCO-024).
  2. Eine Anfrage zu einer Kostenüberschreitung wird an die für das betroffene Objekt zuständige Rolle weitergeleitet und erscheint in deren Arbeitsliste; Weiterleitung und Antwort sind protokolliert (FCO-024).
  3. Ist keine Zuständigkeit hinterlegt, geht die Anfrage an die definierte Rückfallrolle und der Mangel wird ausgewiesen; sie geht nicht verloren.
  4. Die Anfrage enthält nur Daten, für die der Empfänger berechtigt ist.
  5. Eine unbeantwortete Anfrage eskaliert nach `alert_escalation_delay_min`.
- **Tests:** positiv: Anfrage je Ebene, Vertretung. negativ: Objekt ohne Zuständigkeit, Empfänger ohne Kostenstellenrecht, fremder Mandant, doppelte Anfrage zum selben Sachverhalt (Dedupe). konkurrierend (Sicherheit): Zuständigkeitswechsel parallel zur Anfrage – genau ein eindeutiger Empfänger.
- **Abhängigkeiten:** intern: WP-M8a-11, WP-M8a-34.
  - extern: WP-M3-52 (Alarm-Grundlage mit Dedupe und Quittierung)
  - extern: WP-M1-07, WP-M1-08 (Rollenmodell, Objektberechtigung)
- **Entscheidungen:** D-06 (Kostenstellen, Rollen) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Freigabestufen des Belegs (WP-M8a-11); allgemeine Alarmregeln, Alert-Center mit Bündelung, Unterdrückungsfenstern und Eskalationsregel (Abnehmer WP-M8d-22, WP-M8d-23; bis zu deren Lieferung nutzt dieses WP nur die Alarm-Grundlage aus WP-M3-52).

### WP-M8a-36 – Kontrollberichte zu Kraftstoffverbrauch und Kosten je Kilometer
- **Ziel:** Kontrollberichte weisen ungewöhnliche Kraftstoffverbräuche und Kosten je Kilometer als Prüfhinweis mit vollständiger Datengrundlage aus; es geht kein Datensatz unbemerkt verloren.
- **Anforderungen:** `LH-I.2-FCO-019`
- **Liefergegenstände:**
  - Backend: Kontrollbericht im Modul `documents-finance`: Verbrauch je Fahrzeug aus validierten Litern bzw. kWh und zugehörigen km; Kosten je km aus WP-M8a-33; Vergleich gegen eine einstellbare statistische Grenze je Fahrzeugtyp/Antriebsart mit Mindeststichprobe; Prüfhinweis mit Wert, Vergleichsbasis, Grenze, Stichprobengröße, Zeitraum, verwendeten Tank-/Ladeereignissen und Kilometerständen; Bearbeitung des Hinweises (geprüft, Ursache, Korrektur über Gegenereignis).
  - Adapter/Worker: periodischer Berichtslauf über `operation`.
  - Datenbank: Prüfhinweis (Arbeitsname bzw. `business_alert` mit Berechnungssnapshot), `fuel_transaction`, `aggregate_snapshot`, `dataset_watermark`.
  - API: `/v1/reports/jobs`, `/v1/reports/jobs/{id}` (Jobtyp Kontrollbericht); `/v1/analytics/metrics`.
  - Oberfläche: UI-15 – Liste der Prüfhinweise mit Datengrundlage und Drilldown; Zustand „Datenlücke“.
  - Konfigurationsparameter: `fuel_use_alert_pct` (Ebene Fahrzeugtyp; Stichprobengröße beachten), `fuel_price_alert_pct`, `fuel_max_liters`, `kpi_aggregation_min_n`.
  - Kennzahlen: `Kraftstoff L/100 km`, `Strom kWh/100 km`, `Kosten pro Fahrzeugkilometer`.
- **Detailverträge vorab:** Verfahren der „statistischen Grenze“ und ihre Parameter (OP-34); Mindeststichprobe; Vergleichsgruppe; Parameter für die Grenze der Kosten je km (im Katalog nicht vorhanden, OP-34); Abgrenzung zur Einzelbeleg-Plausibilität aus WP-M4-31.
- **Akzeptanzkriterien:**
  1. Ein Verbrauchswert außerhalb der eingestellten Grenze erscheint als Prüfhinweis mit Datengrundlage (Menge, km, Zeitraum, Vergleichswert, Grenze, Stichprobe) (FCO-019).
  2. Ein Wert der Kosten je Kilometer außerhalb der eingestellten Grenze erscheint ebenso als Prüfhinweis mit Datengrundlage (FCO-019).
  3. Die Grenze ist einstellbar; eine Änderung wirkt ab Wirksamkeit und ältere Hinweise bleiben mit ihrer Regelrevision nachvollziehbar (FCO-019).
  4. Eine Kraftstoffmenge, die einen unplausiblen Verbrauch ergibt, führt zu einer Warnung mit Berechnungsbasis; der zugrunde liegende Beleg und seine Werte bleiben unverändert erhalten, nichts wird still verworfen oder korrigiert (E2E-070).
  5. Unterhalb der Mindeststichprobe wird kein Hinweis und kein Scheinwert erzeugt, sondern „Datenbasis unzureichend“ ausgewiesen.
  6. Liter- und kWh-Fahrzeuge werden nicht in derselben Vergleichsgruppe gemittelt.
  7. Die Auswertung ist fahrzeugbezogen; sie enthält keine fahrerbezogene Rangfolge oder Leistungsbewertung, und ein Hinweis nennt keinen Fahrer als Verursacher (D-10).
  8. Ein Prüfhinweis und der Einzelbeleg-Hinweis aus WP-M4-31 bzw. ein später von M8d erzeugter Prüfauftrag zum selben Tankereignis sind über die Ereignisreferenz verknüpfbar und erzeugen nicht mehrere unabhängige Vorgänge.
- **Tests:** positiv: Ausreißer nach oben und unten, Normalwert ohne Hinweis. negativ: fehlende Kilometerstände, zu kleine Stichprobe, Nenner 0, gemischte Antriebsarten. konkurrierend: doppelter Berichtslauf – ein Hinweis je Sachverhalt. Besteht E2E-070; Teil von PH-AT-012 (Bezug E2E-070) und Regression PH-AT-021.
- **Abhängigkeiten:** intern: WP-M8a-33.
  - extern: WP-M4-30, WP-M4-31, WP-M4-41, WP-M4-43 (Tankbeleg-Fachdaten, Tankplausibilität, Kilometerstände, Energiekosten je 100 km)
  - extern: WP-M3-52 (Alarm-Grundlage)
  - extern: WP-M8c-27 (Berichtsjob mit Berechtigungsprüfung über `/v1/reports/jobs`)
- **Entscheidungen:** D-07 (Leer-/Besetztkilometer, Kostenbasis) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Fahrerbezogene Verbrauchsbewertung (unzulässig ohne D-10-Freigabe); Tankkartenabgleich (WP-M8a-14); Energiekennzahlen je Antriebsart mit Plausibilitätsgrenzen (Abnehmer WP-M8c-15) und automatischer Prüfauftrag bei Tankung über Limit (Abnehmer WP-M8d-25) – beide tragen zu E2E-070 bei, führend bleibt dieses WP.

### WP-M8a-37 – Entscheidungshilfe Reparatur oder Ersatz
- **Ziel:** Für ein Fahrzeug wird Reparatur gegen Ersatz anhand einer definierten Kosten- und Ausfallhistorie als transparente Entscheidungshilfe dargestellt.
- **Anforderungen:** `LH-I.2-FCO-018`
- **Liefergegenstände:**
  - Backend: Vergleichsrechnung im Modul `documents-finance`: historische Werkstatt- und Ausfallkosten, Stillstandszeiten, Restbuchwert bzw. Leasingrestlaufzeit, anstehende Reparaturkosten gegen eingegebene Ersatzannahmen; Ausgabe mit ausdrücklich getrennten Blöcken Annahmen, historische Kosten, Unsicherheiten (Datenlücken, Stichprobe, Spannbreite); Ergebnis als gespeicherter, versionierter Vergleich mit Bewertung `SIMULATION`, ohne Buchungswirkung.
  - Datenbank: Vergleich (Arbeitsname; Annahmen als JSON mit Schema, Datenstand, Ersteller) bzw. `forecast_scenario`; lesend `repair_order`, `asset_schedule`, `vehicle_lease`, `economic_event`.
  - API: neuer Pfad Reparatur-/Ersatzvergleich je Fahrzeug.
  - Oberfläche: UI-15 – Vergleichsansicht mit Annahmen, Historie und Unsicherheiten; Export.
- **Detailverträge vorab:** „definierte Kosten- und Ausfallhistorie“ (Zeitraum, Kostenarten – OP-35); Annahmenschema; Darstellung der Unsicherheit.
- **Akzeptanzkriterien:**
  1. Der Vergleich zeigt transparent und getrennt die Annahmen, die historischen Kosten und die Unsicherheiten (FCO-018).
  2. Jede historische Zahl ist per Drilldown auf Kostenereignisse und Werkstattaufträge rückführbar (FCO-018).
  3. Fehlende Historie wird als Unsicherheit ausgewiesen, nicht durch Schätzwerte ohne Kennzeichnung ersetzt.
  4. Der Vergleich erzeugt kein Kostenereignis, kein Journal und keine automatische Entscheidung; er ist als Entscheidungshilfe gekennzeichnet.
- **Tests:** positiv: Fahrzeug mit vollständiger Historie; geleastes Fahrzeug (Restlaufzeit statt Restbuchwert). negativ: Fahrzeug ohne Historie, ungültige Annahmen, fehlendes Finanzrecht, fremdes Fahrzeug, Versuch, aus dem Vergleich ein Kostenereignis zu buchen.
- **Abhängigkeiten:** intern: WP-M8a-26, WP-M8a-32.
- **Entscheidungen:** D-07 (Bewertung von Ausfallkosten) – blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Lebenszyklus- und Beschaffungsszenarien (`LH-I.2-FCO-022`, P3; Abnehmer WP-M9b-40); Simulation (M9). SOLL-Anforderung.

### WP-M8a-38 – Gate-Nachweis M8a und Regressionssuite Finanzen
- **Ziel:** Der Finanzanteil des Gates M8 ist mit Build, Evidenz und Entscheidung nachgewiesen; die Finanztests aus P1 laufen unverändert grün.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: Akzeptanzszenarien unter `tests/acceptance` für E2E-070 sowie die in M8a geschlossenen Anteile von E2E-065, E2E-067, PH-AT-012, PH-AT-015 und PH-AT-019; Abnahmefälle COST-01, COST-02, COST-05, COST-06 (einzelbetrieblicher Teil), COST-07, COST-08 aus Kap. 03 §7; Regressionslauf E2E-064, E2E-066, E2E-068, E2E-071, E2E-072, E2E-073, PH-AT-013, AT-57; Beitragsszenario Periodengrenze/Sommerzeit zu E2E-102 (führend M8c).
  - Backend: Evidenzerfassung je Test gemäß Kap. 08 §8.4 (`source_key[]`, Build, Migrationsstand, Konfigurations-/Steuerregelrevision, Seed, Rollen, Mandanten, Soll/Ist, Trace-IDs); Eintrag in `e2e.csv` ausschließlich aus Testläufen mit Build-ID.
  - Oberfläche: UI-Abnahme „Financebeleg mit Dublette und Rundungsrest“ und Kernreisen UI-14/UI-15 per Tastatur und Screenreader (Kap. 06 §6.9).
- **Detailverträge vorab:** Abnahmeplan mit Zuordnung Test → WP → Anforderung; Beschluss zu OP-01 (Gate-Zuordnung der nachgezogenen Tests).
- **Akzeptanzkriterien:**
  1. E2E-070 ist mit Build, Evidenz und Abnahmeentscheidung in `e2e.csv` eingetragen.
  2. Jede der 25 MUSS-Anforderungen der Einheit hat mindestens einen grünen positiven Test; Geld-, Sicherheits- und Datentrennungsregeln zusätzlich negative und konkurrierende Tests; die 8 SOLL-Anforderungen sind umgesetzt oder mit Begründung und AG-Freigabe als Abweichung dokumentiert (Kap. 08 §8.4).
  3. Die semantischen Contract-Fälle „EUR-Rundungsrest“, „geschlossene Periode“ und „Allokationssumme ≠ Basis“ (Kap. 05 §5.8) sind in CI grün.
  4. Jede neue Invariante der Einheit ist durch einen Negativtest mit mindestens zwei parallelen Transaktionen nachgewiesen (Kap. 04 §4.5).
  5. Es bestehen keine offenen Mängel der Klasse A.
  6. Die Querschnittskriterien 1 bis 5 aus den Lesehinweisen sind je WP nachgewiesen; die Kernreisen „Rechnung prüfen und freigeben“ (UI-14), „Kostenursache analysieren“ (UI-15) und „Wartung/Nachweis freigeben“ (UI-11) laufen vollständig per Tastatur und Screenreader (Kap. 08 §8.4 Gate „UX“).
  7. Die semantischen Contract-Fälle „Manipulation tenant_id“ und „unzulässige Fremd-ID“ (Kap. 05 §5.8) sind für jeden in M8a neuen Pfad grün; Upload-, Download- und Exportberechtigungen sind je Rolle negativ getestet (Kap. 08 §8.4 Gate „Sicherheit“).
  8. Für jede EXTERN-Abhängigkeit auf M7c, M6c, M8b, M8c, M8d und M8e ist im Abnahmeprotokoll vermerkt, gegen welchen Lieferstand getestet wurde; optional geschnittene Abhängigkeiten (Clearing) sind mit und ohne die Gegenseite geprüft.
- **Tests:** vollständiger Lauf der genannten Szenarien unter der Runtime-Rolle mit zwei Mandanten und zwei Rechtsträgern; Isolationsregression PH-AT-001 über alle neuen Finanztabellen und Exporte.
- **Abhängigkeiten:** intern: WP-M8a-10, WP-M8a-12, WP-M8a-14, WP-M8a-18, WP-M8a-19, WP-M8a-34, WP-M8a-36.
  - extern: WP-M4-63 (M4-Gate-Abnahmesuite als Regressionsbasis)
  - extern: WP-M5-22, WP-M5-02 (Release-Gate in CI, Staging-Umgebung)
- **Entscheidungen:** D-06, D-07, D-11 – offene Beschlüsse verhindern nicht den Gate-Nachweis in der Testumgebung, wohl aber die Produktivschaltung der betroffenen Funktionen.
- **Größe:** M
- **Nicht im Umfang:** Gate-Tests der anderen M8-Einheiten (PH-AT-022, E2E-074 bis E2E-079, E2E-099, E2E-101 bis E2E-103, E2E-105, E2E-106); Last- und Restore-Nachweise (M5).

### WP-M8a-39 – Versionierte Kostensätze (`rate_revision`) mit Wirksamkeitsintervall und Freigabe
- **Ziel:** Kostensätze (kalkulatorischer Zinssatz, interner Werkstattstundensatz, Strompreis je Depot/Zeitfenster und weitere im Detailvertrag benannte Sätze) werden versioniert mit Wirksamkeitsintervall gepflegt; alle Rechenpakete dieser Einheit und die Kennzahlpakete aus M8c lesen dieselbe Quelle.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Satzverwaltung im Modul `documents-finance`: Satzart, Geltungsbereich (Mandant, Rechtsträger, Depot, Fahrzeugtyp gemäß Detailvertrag), Wert als Dezimalstring mit Einheit und Währung, gültig von/bis, Entwurf, Vier-Augen-Freigabe, Nachfolgerevision statt Änderung; Auflösung des zum Leistungszeitraum wirksamen Satzes; Lese-Modul-API für M8c und M9.
  - Datenbank: `rate_revision` (Kap. 04 §4.4 FCO/FIN; Neuanlage, da WP-M4-40 sie ausdrücklich nach M8 verweist), Überlappungsverbot je Satzart und Geltungsbereich, append-only nach Freigabe, `audit_event`.
  - API: neuer Pfad Kostensätze (lesen, Entwurf, Freigabe; Scope Finanzadministration), festzulegen in WP-M8a-01 (OP-03).
  - Oberfläche: UI-15 – Satzübersicht mit Revision, Wirksamkeit, Ersteller und Grund; Rechtezustand für Nicht-Finanzrollen.
  - Ereignisse/Outbox: Kostensatz freigegeben.
- **Detailverträge vorab:** Katalog der Satzarten und Geltungsbereiche (OP-38); Schema `rate_revision`; Verhältnis „konfigurierte Kostensätze“ aus `LH-6.14-ANA-006` (WP-M8c-13) zu Istkosten aus `economic_event`; Verhalten bei fehlendem wirksamem Satz.
- **Akzeptanzkriterien:**
  1. Eine Satzrevision ist mit Wirksamkeitsbeginn, Ersteller, Prüfer und Grund gespeichert; nach Freigabe ist sie unveränderlich.
  2. Zwei gleichzeitig wirksame Revisionen derselben Satzart im selben Geltungsbereich sind ausgeschlossen (Invariante „Nicht überlappende aktive Tarif-/Policy-/Dienstversion“, Kap. 04 §4.5).
  3. Eine Berechnung, die vor einer Satzänderung gebucht oder berichtet wurde, behält die damalige Satzrevision; ein rückwirkend eingestellter Satz verändert keine abgeschlossene Periode.
  4. Fehlt für einen Zeitraum ein wirksamer Satz, liefert die Auflösung „kein Satz“ und das abhängige Ergebnis den Status `PROVISIONAL` bzw. einen Klärfall, nie den Wert 0.
  5. Sätze sind nur für Rollen mit Finanzrecht sichtbar und änderbar; die Selbstfreigabe ist ausgeschlossen.
- **Tests:** positiv: Satzwechsel zur Periodengrenze, Auflösung je Geltungsbereich. negativ: Änderung einer freigegebenen Revision, Freigabe durch den Ersteller, überlappendes Intervall, fehlende Rolle, Satz eines fremden Mandanten, negativer Satz. konkurrierend (Geld): zwei parallele Freigaben überlappender Intervalle – genau eine gewinnt; Berechnungslauf parallel zum Wirksamwerden einer Revision – das Ergebnis trägt eindeutig eine Revision.
- **Abhängigkeiten:** intern: WP-M8a-02.
  - extern: WP-M1-22, WP-M1-07, WP-M1-12 (Vier-Augen-Baustein, Rollenmodell, Audit)
- **Entscheidungen:** D-07 (Kostenarten, Treiber, Bewertung) – Bau mit Testsätzen; Produktivschaltung verbindlicher Sätze blockiert.
- **Größe:** M
- **Nicht im Umfang:** Tarif- und Fahrpreisversionen (`tariff_revision`, M2/M6c); Steuerregeln (WP-M8a-05); Berechnung der Kennzahlen aus den Sätzen (Abnehmer WP-M8c-13; Schema gemeinsam abzustimmen, OP-38).

## Offene Punkte

- **OP-01 Gate M8 deckt den Finanzstrom kaum ab** (alle 33 Anforderungen): Von den Gate-Tests des Meilensteins betrifft nur E2E-070 diese Einheit. E2E-067 und der XML-Teil von PH-AT-019 stehen im Gate M4, obwohl `LH-I.1-DOC-014` und `LH-I.1-DOC-015` erst hier geliefert werden (M4 Offener Punkt 4); gleiches gilt für die Vollform von E2E-065/PH-AT-012 (`LH-I.1-DOC-019`, M4 Offener Punkt 5), die Restatement- und Plan/Ist-Anteile von PH-AT-015 (`LH-I.2-FCO-023`, `LH-I.2-FCO-017`, M4 Offener Punkt 6) und E2E-071 im Gate M7 (`LH-I.2-FCO-010`). Zu entscheiden: Aufnahme dieser Tests und der Abnahmefälle COST-01 bis COST-08 in das Gate M8.
- **OP-02 Fehlende Tabellen** (`LH-I.1-DOC-017`, `LH-I.1-DOC-019`, `LH-I.1-DOC-027`, `LH-I.2-FCO-008`, `LH-I.2-FCO-009`, `LH-I.2-FCO-010`, `LH-I.2-FCO-012`, `LH-I.2-FCO-013`, `LH-I.2-FCO-014`, `LH-I.2-FCO-017`, `LH-I.2-FCO-020`, `LH-I.2-FCO-024`): Kap. 04 §4.4 nennt für Lieferant, Kartenabrechnung, Bestellung/Lieferschein, Versicherungspolice, Wartungsregel, Pflichtnachweis, Lager, Reifen, Depotressource, Ladevorgang, Budget und Kostenverantwortung keine Tabellen. `tax_rule_revision`, `allocation_rule_revision`, `payment_order` und `reconciliation_run` stehen nur in Kap. 03 §2, nicht in Kap. 04 §4.4. Für Exportbatch und Abschluss-Snapshot fehlt ein Tabellenname. Diese Datei plant mit Arbeitsnamen.
- **OP-03 Fehlende Endpunkte und Bildschirme** (wie OP-02, zusätzlich `LH-I.1-DOC-016`, `LH-I.1-DOC-021`, `LH-I.2-FCO-006`, `LH-I.2-FCO-007`, `LH-I.2-FCO-023`): Kap. 05 §5.7 nennt für Finanzen nur sieben Pfade; für Eingangsrechnung, Lieferant, Steuerkennzeichen, Periodenabschluss, Budget, Anlagen, Leasing, Versicherung, Wartung, Nachweise, Lager, Reifen, Depots und Ladevorgänge fehlen Pfade. Der Katalog Kap. 06 §6.9 kennt nur UI-14, UI-15 und UI-11; Stammdatenmasken (Lieferanten, Steuerkennzeichen, Vertretungen, Budgets) sind keinem Bildschirm zugeordnet. Diese Datei ordnet sie UI-14 bzw. UI-15 zu.
- **OP-04 `cost_object.kind` reicht für den Kostensplit nicht** (`LH-I.2-FCO-016`, `LH-I.2-FCO-017`): Das Kern-DDL kennt `VEHICLE, SERVICE, RUN, LEG, PROJECT, COST_CENTER, UNALLOCATED`, Kap. 03 §2 zusätzlich `CONTRACT` und `SHIFT`. Auftraggeber sowie Linie/Bediengebiet fehlen in beiden. Ob „Vertrag“ in FCO-017 einen Auftraggebervertrag meint und wie er modelliert wird (`partner_contract_version` ist Kooperationsvertrag), ist offen (siehe auch M4 Offener Punkt 14).
- **OP-05 Überschneidung mit M7c** (`LH-I.2-FCO-008`, `LH-I.2-FCO-009`, `LH-I.2-FCO-010`, `LH-I.2-FCO-011`, `LH-I.2-FCO-012`, `LH-I.2-FCO-014`, `LH-I.1-DOC-017`): Standortlager (`LH-I.10-AST-003`), Fahrzeugdokumente (`LH-I.10-AST-004`), Vertragskalender (`LH-I.10-AST-011`), Einkauf (`LH-I.10-AST-006`), Werkstattunterlagen (`LH-6.22-DMG-016`), Schadenkostenarten (`LH-6.22-DMG-025`) und Versicherungsangaben der Schadenakte werden in M7c gebaut. Das Pflichtenheft grenzt die FCO-Anforderungen davon nicht ab. Diese Datei schneidet M8a als Kosten- und Regelwerksaufsatz; schreibendes Modul je Tabelle und die Reihenfolge M7c vor M8a sind zu bestätigen. Kap. 01 §1.3 bzw. die Zielstruktur nennen kein eigenes Modul für Lager, Depot und Werkstatt.
- **OP-06 Monatsbezug der Kostenfilter** (`LH-I.1-DOC-020`): Offen ist, ob „je Monat“ den Leistungszeitraum oder die Buchungsperiode meint; beide können nach einem Periodenabschluss auseinanderfallen (Kap. 03 §3 Nr. 7).
- **OP-07 Steuerregel im Kern-DDL nur als Text** (`LH-I.1-DOC-021`): `economic_event.tax_rule_revision` ist `text NOT NULL` ohne Fremdschlüssel. Die Abnahme stellt auf den „Freigabezeitpunkt“ ab; Kap. 03 §2 nennt daneben Leistungszeitpunkt und Steuerregelrevision an der Rechnungsposition. Welcher Zeitpunkt die wirksame Revision bestimmt, ist festzulegen.
- **OP-08 „Ausschließlich berechtigten Rollen zugänglich“** (`LH-I.1-DOC-021`): Unklar ist, ob bereits das Lesen der Steuerkennzeichen oder nur deren Pflege beschränkt ist; Erfasser müssen ein Kennzeichen am Beleg auswählen können. Die berechtigten Rollen sind nicht benannt.
- **OP-09 Statuskatalog des Belegs** (`LH-I.1-DOC-026`): Die Anforderung nennt Entwurf, Prüfung, Freigabe, Export, Korrekturfall; `openapi.yaml` und Kap. 03 §2.2 kennen `WAITING_SECOND_APPROVAL`, `APPROVED_PENDING_POSTING`, `POSTED`. Ein vollständiger Zustandsautomat mit zulässigen Übergängen (z. B. Ablehnung, Rückgabe an den Erfasser, Rückweg aus dem Korrekturfall) fehlt.
- **OP-10 Format des Buchungsvorschlags** (`LH-I.1-DOC-016`): Die Abnahme verlangt den Export „zu einem Buchungsvorschlag“; Format, Empfänger und Verhältnis zum gebuchten Journal und zum Batch aus `LH-I.1-DOC-024` sind nicht beschrieben. `LH-I.1-DOC-016` (MUSS) verlangt Lieferantenstammdaten, die Lieferantenverwaltung `LH-I.1-DOC-027` ist SOLL; diese Datei baut in WP-M8a-07 einen Minimalstamm.
- **OP-11 Lieferantenstamm** (`LH-I.1-DOC-027`): „Pflichtdokumente“ und „Vertragsbezug“ sind nicht konkretisiert; die Regel, wann Kontodaten als „widersprüchlich“ gelten, und der Freigabeweg für Stammdaten fehlen.
- **OP-12 Format der Kartenabrechnungen** (`LH-I.1-DOC-019`): Anbieter, Dateiformat, Pflichtfelder und Matchingtoleranzen sind nicht angegeben; `fuel_card_mapping` regelt nur Tankkarten, für Firmenkarten (Person statt Fahrzeug) fehlt ein Parameter. Die Kontierung der Sammelzahlung hängt an D-06.
- **OP-13 „Geeignete ZUGFeRD-Profile“** (`LH-I.1-DOC-014`): Die Profile sind nicht benannt; Kap. 03 §3 verlangt eine Capability-Matrix, D-06 die Angabe „zulässige E-Rechnungsprofile“. Offen ist auch, wie mit einem Wechsel der Validator-/Regelversion für bereits validierte Rechnungen umzugehen ist und ob eingehende strukturierte Gutschriften mit abgedeckt sind.
- **OP-14 Vergleichsumfang PDF/XML** (`LH-I.1-DOC-015`): Welche Felder verglichen werden, mit welcher Toleranz, und wie eine nicht maschinenlesbare PDF-Darstellung behandelt wird, ist nicht festgelegt. E2E-067 nennt nur den Betrag.
- **OP-15 Dimensionen der Freigabestufen** (`LH-I.1-DOC-018`): `approval_stage_thresholds` ist im Katalog auf Ebene Mandant/Kostenstelle definiert; die Dimension Kostenart und die Vertreterregelung haben weder Parameter noch Datenmodell. Präzedenz bei mehreren zutreffenden Regeln und die maximale Stufenzahl (Abnahme nennt nur eine zweite Stufe) sind offen. Abgrenzung zu `LH-I.1-DOC-010` aus M4 ist zu bestätigen.
- **OP-16 „Nicht freigegebene Finanzdaten eines anderen Unternehmens“** (`LH-I.1-DOC-028`): Die Abnahme setzt voraus, dass Finanzdaten zwischen Unternehmen freigegeben werden können; ein Freigabeobjekt dafür (über `cooperation_grant` hinaus) ist nicht beschrieben. Die Abgrenzung zu GOV-007 (Kostenstelle und Vertragsbeziehung) in der Governance-Einheit ist zu klären.
- **OP-17 Abgleichstoleranzen** (`LH-I.1-DOC-017`): Mengen- und Preistoleranzen, Positionszuordnung sowie Teil- und Überlieferung sind nicht definiert; der Umfang des Einkaufs hängt an D-06 (siehe M7c OP-29).
- **OP-18 Fristen und Rechtsraum** (`LH-I.1-DOC-022`): Fristwerte, Fristbeginn, Katalog der Dokumentenarten und das Verfahren einer „bestätigten Löschregel“ fehlen und hängen an D-11 und D-06. `invoice_archive_years` liegt auf Ebene Land/Belegart; ob der Rechtsraum aus `legal_entity` oder aus dem Dokument abgeleitet wird, ist offen.
- **OP-19 Archivexport** (`LH-I.1-DOC-023`): Paketformat, Signatur-/Hashverfahren, die Rolle „berechtigter Prüfer“ (intern, extern, befristet) und der Umfang der Audit-Historie (Maskierung personenbezogener Angaben) sind nicht festgelegt.
- **OP-20 DATEV-Importspezifikation und Lizenz** (`LH-I.1-DOC-025`): Die „definierte Importspezifikation“ und die Lizenz- und Formatvorgaben liegen nicht vor (D-06 „Ziel-ERP/DATEV-Variante“). Ohne sie ist die SOLL-Anforderung nicht abnehmbar.
- **OP-21 Kostenverteilung über Rechtsträgergrenzen** (`LH-I.2-FCO-016`): Die Abnahme nennt „zwei Auftraggeber“; Kap. 05 §5.5 schließt Allokation über Rechtsträgergrenzen per Schreibzugriff aus und verlangt Intercompany-Belege, Kap. 03 §6 beschreibt zusätzlich eine konsolidierte Ressourcensicht mit Eliminierung (E2E-094, MUL-020). Keine Anforderung dieser Einheit deckt Intercompany-Beleg und Konsolidierung ab; die Zuständigkeit (M6 oder M8) ist zu klären (siehe M4 Offener Punkt 37).
- **OP-22 Abschreibungsmethode** (`LH-I.2-FCO-006`): Methode (linear, leistungsabhängig), Rundung, unterjährige Behandlung, kalkulatorischer Zinssatz und das Verhältnis der Bewertungssicht zu `scenario` sind nicht definiert; abhängig von D-07.
- **OP-23 Mehrkilometerprognose** (`LH-I.2-FCO-007`): Die Prognosemethode (lineare Fortschreibung, saisonal) und die Behandlung von Minderkilometern sind nicht beschrieben.
- **OP-24 Betriebsstunden und „frühestmöglicher Termin“** (`LH-I.2-FCO-009`): Eine Quelle für Betriebsstunden existiert im Datenmodell nicht (Telematik, D-09; siehe M4 Offener Punkt 22). Die Bedeutung des frühestmöglichen Termins, die freigebende Rolle und die Wahl zwischen Werkstattauftrag und geplanter Sperre sind nicht geregelt. `maintenance_reminder_km` deckt nur die Kilometer-Vorwarnung ab.
- **OP-25 Regelwerk der Pflichtnachweise** (`LH-I.2-FCO-010`): „Je gesetztem Regelwerk“ ist nicht konkretisiert: Liste der Nachweisarten, Sperrwirkung je Art, Wirkung auf bereits laufende Freigaben, Eskalationsstufen und Empfänger fehlen (siehe M7c OP-28). Die Überschneidung mit `LH-I.10-AST-004` und `LH-I.10-AST-011` ist aufzulösen.
- **OP-26 Bewertung von Arbeitszeit und Ausfallkosten** (`LH-I.2-FCO-011`): Interne Stundensätze und die Bewertung von Ausfallkosten sind nicht definiert (D-07; siehe M7c OP-25). Offen ist, ob Ausfallkosten gebuchter Aufwand oder kalkulatorische Größe sind und welche „Verfügbarkeitsstatistik“ gemeint ist (`Fahrzeugverfügbarkeit`, `Werkstattquote`).
- **OP-27 Lagerbewertung** (`LH-I.2-FCO-012`): Das Bewertungsverfahren für Entnahmen (Durchschnitt, FIFO, Festpreis) und die Zulässigkeit von Entnahmen ohne Werkstattauftrag sind nicht geregelt; Kap. 03 §1.1 verlangt nur Bestandsführung mit Kostenwirkung bei Entnahme.
- **OP-28 Granularität der Reifen** (`LH-I.2-FCO-013`): Offen ist, ob Einzelreifen oder Sätze geführt werden und ob Laufleistung je Reifen oder je Satz gilt.
- **OP-29 Belegung im Depotbericht** (`LH-I.2-FCO-014`): „Belegung“ ist nicht definiert (zugeordnete oder tatsächlich anwesende Fahrzeuge, Stichtag oder Zeitraum). Für Tankstellen und Stellplätze fehlen Attribute; `charging_slot` beschreibt nur Ladefenster.
- **OP-30 Quelle der Ladedaten und der Betriebshofenergie** (`LH-I.2-FCO-020`): Weder die Messquelle der Ladevorgänge noch die Erfassung der „allgemeinen Betriebshofenergie“ (Zählpunkt, Rechnung) noch die Preislogik sind beschrieben; Kap. 03 §1.1 kündigt für P2 außerdem eine Korrektur des Periodenverbrauchs bei bekannten Bestandsänderungen an, ohne Verfahren.
- **OP-31 Bezugsgrößen der Einheitskosten** (`LH-I.2-FCO-015`): „Betriebsstunde“ ist nicht definiert (Kap. 03 §6 rechnet mit Dienststunden) und Kosten je Betriebsstunde fehlen unter den 48 Kennzahlen in `kataloge.csv`. Welche Kilometerklassen „relevante Fahrzeugkilometer“ bilden und ab welchem Abdeckungsgrad ein Wert `PROVISIONAL` ist, ist offen.
- **OP-32 Budgetmodell** (`LH-I.2-FCO-017`): Forecastmethode, Definition des Restbudgets (gegen Ist oder Forecast), Budgetgenehmigung und die Ebene (Vertrag, Projekt, Kostenstelle) sind nicht festgelegt; `cost_overrun_alert_pct` liegt auf Ebene Kostenstelle. „Buchungsabschluss“ wird hier als Periodenabschluss gelesen.
- **OP-33 Zuständigkeiten und „Anfrage“** (`LH-I.2-FCO-024`): Es gibt kein Datenmodell für Kostenverantwortliche; „Auftragsebene“ (Werkstatt- oder Verkehrsauftrag), der Auslöser der „Anfrage zu einer Kostenüberschreitung“ (Benutzer oder automatischer Budgetalarm) und die Präzedenz zwischen den Ebenen sind nicht beschrieben.
- **OP-34 „Statistische Grenze“** (`LH-I.2-FCO-019`): Das Verfahren (Standardabweichung, Quantil, Prozentabweichung) ist nicht definiert; `fuel_use_alert_pct` ist eine Prozentgrenze auf Ebene Fahrzeugtyp mit dem Hinweis „Stichprobengröße beachten“ ohne Wert. Für die Grenze der Kosten je Kilometer fehlt ein Parameter. Die Datenquelle für `fuel_price_alert_pct` ist offen (M4 Offener Punkt 16).
- **OP-35 Kosten- und Ausfallhistorie** (`LH-I.2-FCO-018`): Zeitraum, einbezogene Kostenarten, Ersatzannahmen und die Darstellung der „Unsicherheiten“ sind nicht definiert.
- **OP-36 Fremdwährung in P2** (einheitsübergreifend): Kap. 03 §2.1 verlangt für P2 Wechselkursquelle, Kurszeit, Kursversion und eigene Kursdifferenzbuchung; keine der 33 Anforderungen fordert das. In M8a bleiben fremdwährungsbehaftete Belege daher nicht summierbar und gehen in den Klärfall; die Zuordnung der Fremdwährungsbewertung ist zu entscheiden.
- **OP-37 Eindeutigkeit des Kostenursprungs bei mehreren Quellen** (`LH-I.2-FCO-011`, `LH-I.2-FCO-012`, `LH-I.1-DOC-019`): Werkstattauftrag, Lagerentnahme, Kartenabrechnung und Eingangsrechnung können denselben wirtschaftlichen Ursprung betreffen. Das Kern-DDL sichert nur `(tenant_id,legal_entity_id,source_system,source_ref,event_type,scenario)`; die kanalübergreifende Regel, welche Quelle den Ursprung bildet und welche nur Evidenz ist, fehlt (Kap. 04 §4.5 „kanalübergreifende Dublettenklärung im Dienst“; M4 Offener Punkt 15).
- **OP-38 Zuständigkeit und Inhalt der Kostensätze** (`LH-I.2-FCO-006`, `LH-I.2-FCO-011`, `LH-I.2-FCO-020`): Kap. 04 §4.4 nennt `rate_revision` ohne Feldliste; WP-M4-40 verweist die Tabelle nach M8, WP-M8c-13 (`LH-6.14-ANA-006`) erwartet die Pflege von M8a. Keine Anforderung dieser Einheit fordert die Satzpflege ausdrücklich; sie ist hier als Enabler WP-M8a-39 geschnitten. Satzarten, Geltungsbereiche und das Verhältnis von Kostensätzen zu gebuchten Istkosten sind festzulegen.
- **OP-39 Deckungsbeitrag, Erlöse und Zuschussbuchungen im Finanzstrom** (einheitsübergreifend): Der Meilensteinplan nennt „Deckungsbeitrag“ unter dem Strom Finanzen und UI-15 heißt „TCO/Deckungsbeitrag“, aber keine der 33 Anforderungen fordert Erlös- oder Zuschussbuchungen; `LH-I.6-KPI-027` liegt in WP-M8c-14, das seinerseits „Deckungsbeitrag, Zuschussbuchungen“ von M8a erwartet. M8a liefert die Kostenbasis (WP-M8a-32) und bindet die Kennzahl in UI-15 ein; wer Nettoerlöse und Zuschüsse als Quelle liefert (M2-Tarif, M6c-Clearing, eigene Zuschussbuchung), ist zu entscheiden.
- **OP-40 Reihenfolge der P2-Ströme** (`LH-I.1-DOC-017`, `LH-I.1-DOC-025`, `LH-I.2-FCO-008` bis `LH-I.2-FCO-014`, `LH-I.2-FCO-020`, `LH-I.2-FCO-023`): Der Umsetzungsplan bezeichnet M6, M7 und M8 als voneinander unabhängig. Die Fuhrparkpakete dieser Einheit setzen jedoch M7c voraus (Lager, Fahrzeugdokumente, Einkauf, Werkstattunterlagen, Verfügbarkeit), `adapter_capability` entsteht in M6a, und die Abschlussvorprüfung liest bei aktivem Clearing M6c. Zu entscheiden: verbindliche Reihenfolge M7c vor den WPs 15 und 23 bis 30 oder Verlagerung der fehlenden Grundlagen nach M8a.
- **OP-41 Drei Stellen für Aufbewahrung und Löschung** (`LH-I.1-DOC-022`): Belegfristen (WP-M8a-16), Aufbewahrungsklassen je Datentyp mit Schutz steuerlicher Nachweise (WP-M8b-23) sowie Legal Hold und orchestrierter Löschjob (WP-M8e-14, WP-M8e-15) greifen auf `retention_rule` und `erasure_job` zu. Führendes Modul je Tabelle, die Reihenfolge der Lieferung und das Verhalten von WP-M8a-16 vor Lieferung von M8e sind festzulegen.
- **OP-42 Zuständigkeit für Parameterschemas** (`LH-I.1-DOC-018`, `LH-I.2-FCO-017`, `LH-I.2-FCO-019`): Welche Einheit die Schemas der Finanzparameter im Konfigurationsregister veröffentlicht, ist offen (siehe M8d OP-01); WP-M8d-12 und WP-M8d-13 behandeln Finanzschwellen und Belegfelder ebenfalls. Diese Datei spezifiziert die Schemas in WP-M8a-01 und nutzt die Parameter in den Fach-WPs.
- **OP-43 Doppelte Berechnung der Einheitskosten** (`LH-I.2-FCO-015`): WP-M8a-33 und WP-M8c-13 (`LH-6.14-ANA-006`) weisen beide Rechenbeispiel B mit 5,60 EUR/Fahrgastfahrt und 0,672 EUR/Fahrgast-km nach. Es darf nur eine Formel und eine Definitionsrevision geben; vorgeschlagen ist: M8a liefert Zähler (Vollkosten) und die Null-/Lückenregeln, M8c führt Definition und Snapshot. Zu bestätigen.
- **OP-44 Offline- und Mobilfälle** (`LH-I.2-FCO-012`, `LH-I.2-FCO-013`, `LH-I.2-FCO-020`): Das Pflichtenheft nennt für Lagerentnahme, Reifenwechsel und Ladevorgänge keinen Erfassungskanal (Operator-Web, Fahrer-/Werkstatt-App, offline). Diese Datei plant ausschließlich Operator-Web (UI-11, UI-15); eine Offline-Erfassung wäre zusätzlicher Umfang.
