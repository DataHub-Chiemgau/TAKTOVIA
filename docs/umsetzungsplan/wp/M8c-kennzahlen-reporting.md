# M8c – Kennzahlen, Reporting und Mobilitätsdaten

- **Meilenstein:** M8 – Finanzen, Controlling, ETA, Governance, Strom „Daten und Steuerung“ (setzt M5 voraus; Nachbareinheiten M8a Belege/Finanzen, M8b ETA/Governance, M8d Konfiguration/Automatisierung, M8e Plattform/Sicherheit)
- **Phase:** P2 (22 MUSS, 6 SOLL; Phase und Priorität jeder Anforderung unverändert)
- **Anzahl Anforderungen:** 28 (ANA 9, KPI 19)
- **Anzahl WPs:** 33 (28 mit Anforderungen, 5 Enabler)

**Ziel:** Nach M8c sind alle 48 Kennzahldefinitionen aus `kataloge.csv` als versionierte, ausführbare Definitionen mit Zähler, Nenner, Ausschlüssen, Datenstand und Vollständigkeit verfügbar; ältere Berichte bleiben mit ihrer damaligen Definition reproduzierbar. Der Statistikbereich (UI-19) liefert Ursachenklassifikation, Drilldown, Soll/Ist/Prognose- und Zeitvergleiche, Wirtschaftlichkeits-, Energie-, Ausfall-, Schicht- und Verbundkennzahlen sowie Zielwerte und Qualitätswarnungen. Berichtsjobs erzeugen zeitgesteuerte, berechtigungsgeprüfte CSV-/JSON-Exporte einschließlich Mobilitätsdaten- und pseudonymisiertem Evaluationsexport; kleine Gruppen werden überall unterdrückt.

**Gate-Anteil M8c (Umsetzungsplan §4, Gate M8):** führend E2E-101, E2E-103; Beitrag zu E2E-102 (Berichtszeitraum, Betriebstag, Zeitzone; Kalender und Schichten liegen bei M7d), zu E2E-070 (Verbrauchskennzahl mit Berechnungsbasis; führend M8a) und zu E2E-099 (Kennzahlquelle der Kapazitätsablehnungen; führend M8d). Regression der P1-Nachweise PH-AT-021 und E2E-080 bis E2E-084 mit den erweiterten Definitionen.

**Lesehinweise:**

- Kurz-IDs in Klammern hinter Akzeptanzkriterien verweisen auf die Anforderung dieser Einheit (ANA-0xx = `LH-6.14-ANA-0xx`, KPI-0xx = `LH-I.6-KPI-0xx`).
- `openapi.yaml` enthält für diesen Bereich **keinen** ausformulierten Pfad. Kap. 05 §5.7 nennt als Funktionszuschnitt nur `/v1/analytics/metrics`, `/v1/reports/jobs` und `/v1/reports/jobs/{id}`. Alle weiteren Pfade in dieser Datei sind als **Arbeitsname** gekennzeichnet und werden in WP-M8c-01 festgelegt.
- Im Kern-DDL (`kernschema.sql`) vorhanden und hier gelesen: `travel_attempt`, `fare_quote`, `journey`, `journey_leg`, `booking`, `vehicle`, `vehicle_run`, `staff_shift`, `resource_release`, `economic_event`, `cost_object`, `allocation_set`, `allocation_line`, `fiscal_period`, `settlement_line`, `partner_contract_version`, `cooperation_grant`, `config_revision`, `audit_event`, `outbox`, `inbox`. Nur als Namen in Kap. 04 §4.4: `metric_definition_revision`, `aggregate_snapshot`, `dataset_watermark`, `rejection_reason`, `export_job`, `alert_policy_revision`, `business_alert`, `alert_acknowledgement`, `rate_revision`, `cost_category`, `forecast_scenario`, `fuel_transaction`, `realtime_observation`, `transfer_hub`, `transfer_rule`, `position_sample`, `roster_revision`, `time_event`, `repair_order`, `defect_case`, `support_case`, `retention_rule`. Weitere Tabellen in dieser Datei sind **Arbeitsnamen**.
- Das Modul `analytics` liest ausschließlich eigene Projektionen (Kap. 01 §1.1 „Lesereplikat + inkrementelle Projektionen“, §1.3 „keine Schreibrechte auf Live-Buchungen“). Faktenbasis, Kennzahlregister, Filterdimensionen und die Kennzahlenansicht der P1-Kennzahlen stammen aus M4 und werden hier erweitert, nicht neu gebaut.
- „Planungsdefault“ kennzeichnet Werte, die das Pflichtenheft selbst als vorläufig bezeichnet. Für `kpi_aggregation_min_n`, `daily_report_time`, die Toleranz- und Alarmparameter nennt das Pflichtenheft **keine** Zahlenwerte; Tests verwenden gekennzeichnete Fixturewerte (WP-M8c-03).
- **Unabhängigkeit der P2-Ströme:** Der Meilensteinplan (Umsetzungsplan §1) führt M6, M7 und M8 als voneinander unabhängige Ströme. Mehrere WPs dieser Einheit lesen jedoch Fakten aus M6a/M6b/M6c (Föderation, Anschlüsse, Clearing) und M7b/M7c/M7d (Support, Werkstatt, Dienstplan). Diese WPs werden **vertragsbasiert** gebaut: gegen die in WP-M8c-01 festgelegten Consumer-Verträge (Ereignis-/Leseport-Schemas) und die synthetischen Ereignisfixtures aus WP-M8c-03. Der Bau ist damit nicht blockiert; die **Integrationsabnahme** gegen echte Quelldaten setzt die liefernde Einheit voraus (Offener Punkt 30). Liegt die Quelle nicht vor, liefert die Kennzahl `NOT_AVAILABLE`, nie 0.
- **Querschnittsregeln für jedes WP mit Lese- oder Schreibzugriff** (werden in den WPs nicht jedes Mal wiederholt, sind aber Teil jeder Abnahme):
  - Autorisierung serverseitig je Anfrage zusätzlich zu RLS (Kap. 01 §1.7); nicht sichtbare Ressourcen werden ohne Existenzhinweis beantwortet, Fehler als `application/problem+json` mit stabilem `code` und `correlation_id` (Kap. 05 §5.1). Mindestens ein Negativtest „fremder Mandant“ und „Rolle ohne Recht“ je Endpunkt bzw. Dimension.
  - Mutierende Aufrufe: Idempotenzschlüssel bzw. `If-Match` (fehlend 428, veraltet 412; Kap. 05 §5.1) und Eintrag in `audit_event` mit Akteur, Zeitpunkt, Grund.
  - Projektionen des Moduls `analytics` verarbeiten Ereignisse at-least-once über `inbox` mit genau einer fachlichen Wirkung (PH-AT-017-Muster) und sind aus den Quellereignissen vollständig neu aufbaubar; ein Neuaufbau verändert keinen bereits ausgelieferten `aggregate_snapshot`.
  - Jede UI-19-Ansicht behandelt die Katalogzustände „Keine Daten“, „geringe Qualität“ und „Live-Schreibversuch“ (Kap. 06 §6.9) sowie Lade-, Fehler- und Rechtezustand; Diagramme haben eine Tabellenalternative und sind per Tastatur bedienbar.
  - Jede Antwort und jeder Export trägt Definition/Version, Filter, Datenstand und Vollständigkeit; Zähler und Nenner verwenden dieselbe Periode und denselben Umfang (Kap. 05 §5.7).
- Größen: S bis 1, M bis 3, L bis 6 Personenwochen.

## Übersicht

| WP-ID | Titel | Anforderungen (Anzahl) | Größe | hängt ab von |
|---|---|---:|---|---|
| WP-M8c-01 | Detailverträge M8c: Kennzahldefinitionen, Berichtsjob, Exportformate | – (Enabler) | M | WP-M0-31, WP-M0-13, WP-M4-46, WP-M4-45, WP-M4-58, WP-M1-07, WP-M1-02 |
| WP-M8c-02 | Migration der Analytics- und Berichtstabellen mit RLS | – (Enabler) | M | WP-M8c-01, WP-M1-01, WP-M0-15, WP-M1-46, WP-M4-45, WP-M4-46, WP-M4-48, WP-M4-64, WP-M2-13 |
| WP-M8c-03 | Standardisierter Referenzdatensatz und Kennzahl-Orakel | – (Enabler) | M | WP-M8c-02, WP-M4-02, WP-M4-45, WP-M5-02 |
| WP-M8c-04 | Versionierte Definitionen und Zeitreihen, reproduzierbare Altberichte | 1 | M | WP-M8c-02, WP-M8c-03, WP-M4-46, WP-M8d-03, WP-M8d-05 |
| WP-M8c-05 | Datenvollständigkeit und ausgewiesene Unsicherheit | 1 | M | WP-M8c-02, WP-M8c-03, WP-M4-45, WP-M4-46 |
| WP-M8c-06 | Pünktlichkeit, Wartezeit, Verspätung, Ablehnungsgründe mit dokumentierter Definition | 1 | M | WP-M8c-04, WP-M8c-05, WP-M4-53, WP-M4-54, WP-M4-48, WP-M4-49, WP-M4-50, WP-M4-52 |
| WP-M8c-07 | Verspätungsursachen mit revisionssicherem Ursachenwechsel | 1 | M | WP-M8c-06, WP-M1-07, WP-M1-08, WP-M1-46, WP-M1-13, WP-M4-52, WP-M4-55, WP-M6a-13, WP-M6a-25 |
| WP-M8c-08 | ÖPNV-Anschlüsse: erreicht, gefährdet, verpasst, nicht bewertbar | 1 | M | WP-M8c-04, WP-M8c-05, WP-M6a-13, WP-M6a-08, WP-M6a-18, WP-M6a-19, WP-M6b-27, WP-M6a-27 |
| WP-M8c-09 | Pooling-Kennzahlen mit Bezugsgröße und Formel | 1 | S | WP-M8c-03, WP-M8c-04, WP-M4-56, WP-M4-57 |
| WP-M8c-10 | Angebotsabbruch- und Konversionsquote aus Funnelereignissen | 1 | S | WP-M8c-03, WP-M8c-04, WP-M2-21, WP-M2-22, WP-M2-23, WP-M2-26, WP-M4-47, WP-M4-51 |
| WP-M8c-11 | Nicht bediente Nachfrage mit Engpass-Heatmap und Kleingruppenschutz | 1 | M | WP-M8c-04, WP-M8c-10, WP-M4-49, WP-M4-50, WP-M4-58 |
| WP-M8c-12 | Nachfrage-Heatmap und Zeit-Histogramm | 1 | S | WP-M8c-11 |
| WP-M8c-13 | Kosten pro Fahrt und Fahrgastkilometer aus versionierten Kostensätzen | 1 | M | WP-M8c-04, WP-M8c-05, WP-M4-33, WP-M4-39, WP-M4-42, WP-M4-56, WP-M8a-39, WP-M8a-32 |
| WP-M8c-14 | Wirtschaftlichkeit je Vertrag: Kosten, Zuschussbedarf, Deckungsbeitrag | 1 | M | WP-M8c-13, WP-M6c-19, WP-M6c-20, WP-M6c-23, WP-M8a-32, WP-M8a-34, WP-M8a-18, UNGEDECKT (Zuschussbuchungen) |
| WP-M8c-15 | Treibstoff- und Energiekennzahlen je Antriebsart | 1 | M | WP-M8c-04, WP-M8c-05, WP-M4-43, WP-M4-41, WP-M8a-14, WP-M8a-30, WP-M8a-36 |
| WP-M8c-16 | Fahrzeugausfallzeiten nach Ursache und geplanter Betriebszeit | 1 | M | WP-M8c-04, WP-M3-08, WP-M3-41, WP-M3-51, WP-M7c-35, WP-M7c-15, WP-M7c-36 |
| WP-M8c-17 | Schichtplan-Kennzahlen und offener Personalbedarf | 1 | M | WP-M8c-04, WP-M4-14, WP-M4-05, WP-M4-08, WP-M4-09, WP-M7d-23, WP-M7d-26, WP-M7d-14, WP-M7d-35, WP-M7d-11 |
| WP-M8c-18 | Verbundkennzahlen mit abgestimmter Partnerzählung | 1 | L | WP-M8c-04, WP-M8c-08, WP-M8c-11, WP-M6a-13, WP-M6a-18, WP-M6a-09, WP-M6a-14, WP-M6b-05, WP-M6b-04, WP-M6b-09, WP-M6b-13, WP-M6a-17 |
| WP-M8c-19 | Soll/Ist/Prognose-Vergleich im Statistikbereich | 1 | M | WP-M8c-04, WP-M8c-13, WP-M8a-34, WP-M8a-18 |
| WP-M8c-20 | Kohorten-, Saison- und Vorjahresvergleich mit Feiertagskennzeichnung | 1 | M | WP-M8c-04, WP-M1-18, WP-M1-21, WP-M7d-11 |
| WP-M8c-21 | Kundenzufriedenheit nur mit Datenquelle und Stichprobenkennzeichnung | 1 | S | WP-M8c-04, WP-M8c-05, WP-M7b-20, WP-M7a-17 |
| WP-M8c-22 | Vervollständigung des Kennzahlkatalogs auf 48 ausführbare Definitionen | – (Enabler) | M | WP-M8c-06, WP-M8c-08, WP-M8c-09, WP-M8c-10, WP-M8c-13, WP-M8c-14, WP-M8c-15, WP-M8c-16, WP-M8c-17, WP-M8c-18, WP-M7b-20, WP-M8a-07, WP-M8a-11, WP-M8a-34, WP-M8b-20 |
| WP-M8c-23 | Drilldown von Ursache über anonymisierten Fall zum Buchungsdetail | 1 | M | WP-M8c-07, WP-M8c-11, WP-M1-07, WP-M1-08, WP-M1-46, WP-M1-02, WP-M2-23 |
| WP-M8c-24 | Standarddashboard Auslastung, Leerkilometer, Abholqualität, No-Show | 1 | S | WP-M8c-06, WP-M8c-23, WP-M4-59, WP-M4-57, WP-M4-56 |
| WP-M8c-25 | Zielwerte und Schwellenmarkierung für Service Level | 1 | S | WP-M8c-04, WP-M1-20, WP-M1-22, WP-M1-07 |
| WP-M8c-26 | Qualitätswarnungen gegen Zielwerte mit Zuständigkeit | 1 | M | WP-M8c-16, WP-M8c-25, WP-M8d-22, WP-M8d-23, WP-M8d-26 |
| WP-M8c-27 | Berichtsjob: zeitgesteuertes CSV und Reporting-API mit Berechtigungsprüfung | 1 | M | WP-M8c-02, WP-M8c-04, WP-M8c-11, WP-M1-15, WP-M1-13, WP-M1-11, WP-M1-07, WP-M1-04, WP-M8e-15, WP-M1-33 |
| WP-M8c-28 | Maschinenlesbare Exportformate CSV/JSON mit Felddefinitionen | 1 | S | WP-M8c-27 |
| WP-M8c-29 | Wiederkehrende Berichte mit Verteilung an berechtigte Empfänger | 1 | M | WP-M8c-27, WP-M8c-28, WP-M2-32 |
| WP-M8c-30 | Mobilitätsdatenexport für PBefG-/Mobilitätsdatenpflichten | 1 | M | WP-M8c-27, WP-M8c-28, WP-M2-03, WP-M2-04, WP-M2-05, WP-M2-07, WP-M3-03, WP-M4-56 |
| WP-M8c-31 | Pseudonymisierter Buchungsexport für Evaluation | 1 | M | WP-M8c-27, WP-M8c-28, WP-M1-36, WP-M1-33, WP-M8e-15, WP-M8e-16, WP-M8e-14 |
| WP-M8c-32 | Dashboard-Builder mit Kennzahlen, Filtern, Diagrammen und Warnschwellen | 1 | M | WP-M8c-22, WP-M8c-24, WP-M8c-25, WP-M8c-27 |
| WP-M8c-33 | M8c-Gate-Abnahmesuite | – (Enabler) | M | WP-M8c-18, WP-M8c-20, WP-M8c-22, WP-M8c-26, WP-M8c-29, WP-M8c-30, WP-M8c-31, WP-M8c-32, WP-M6a-34, WP-M6b-34, WP-M6c-32, WP-M7b-36, WP-M7c-54, WP-M7d-43, WP-M8a-36, WP-M8d-22, WP-M8d-26 |

## Arbeitspakete

### WP-M8c-01 – Detailverträge M8c: Kennzahldefinitionen, Berichtsjob, Exportformate

- **Ziel:** Vor Codebeginn liegen alle Verträge der Einheit vor: Definitionsschema der 48 Kennzahlen, OpenAPI der Analytics- und Berichtsendpunkte, DDL der Ergänzungstabellen, Zustandsautomat des Berichtsjobs und Feldkataloge der Exporte.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - API: OpenAPI-3.1-Ergänzung für `/v1/analytics/metrics`, `/v1/reports/jobs`, `/v1/reports/jobs/{id}` (Kap. 05 §5.7) nach den Konventionen aus Kap. 05 §5.1 (Fehlermodell, `limit`/`cursor`, Antwort `{items,next_cursor,as_of}`, asynchrone Exportläufe); Festlegung der Arbeitsnamen-Pfade dieser Datei (Drilldown, Ursachenwechsel, Zielwerte, Berichtspläne, Dashboards) oder deren Abbildung auf die drei Katalogpfade.
  - Datenbank: DDL-Entwurf für `metric_definition_revision`, `aggregate_snapshot`, `dataset_watermark`, `rejection_reason` (Erweiterungen gegenüber M4) sowie für die Arbeitsnamen-Tabellen aus WP-M8c-02; Klärung, ob Berichtsjobs in `export_job` (Kap. 04 §4.4, Zeile CFG/SEC/INT) oder in einer eigenen Tabelle liegen.
  - Backend: JSON-Schema der Kennzahldefinition (stabile technische ID, Definitionsversion, Zeitzonen-/Periodenbasis, Zähler, Nenner, Ausschlüsse, Datenqualitätsquote, Datenstand – Kap. 01 §1.6); Ergebnisstatus `ZERO_DENOMINATOR`, `PROVISIONAL`, `NOT_AVAILABLE` (Kap. 03 §6/§7); Zustandsautomat Berichtsjob; Zustandsautomat Ursachenwechsel.
  - Backend (Rechte): Rollen-/Rechtematrix der Einheit je Funktion (Kennzahl lesen, Finanzkennzahl lesen, Personalkennzahl lesen, Drilldown Stufe 2/3, Ursachenwechsel, Zielwert pflegen, Bericht/Export anlegen, Berichtsplan pflegen, Mobilitätsdaten-/Evaluationsexport, Dashboard erstellen/teilen, Verbundsicht) auf Basis des Rollenmodells aus M1; im Pflichtenheft nicht als Matrix vorhanden (Offener Punkt 32).
  - Ereignisse/Outbox: JSON-Schemas der eigenen Ereignisse (Arbeitsnamen dieser Datei) und Consumer-Verträge für die gelesenen Ereignisse/Leseports der Nachbareinheiten M2, M3, M4, M6a, M6b, M6c, M7b, M7c, M7d, M8a, M8b, jeweils mit Stub für den vertragsbasierten Bau; Fehlercodes der neuen Endpunkte (428, 412, 409, 404/403 gemäß Kap. 05 §5.1).
  - Kennzahlen: Zuordnungstabelle aller 48 Namen aus `kataloge.csv` zu technischer ID, Quellereignissen und liefernder Einheit (M4 bereits geliefert / M8c-WP).
- **Detailverträge vorab:** dieses WP ist der Detailvertrag; Reviewrollen gemäß Detailvertragsprozess aus M0.
- **Akzeptanzkriterien:**
  1. `validate_artifacts.py` läuft mit den ergänzten OpenAPI-Dateien fehlerfrei.
  2. Für jede der 28 Anforderungen der Einheit ist ein Detailvertrag und mindestens ein positiver Testfall benannt (Kap. 08 §8.4).
  3. Jede der 48 Kennzahlen hat genau eine technische ID und ein liefernd verantwortliches WP.
  4. Jeder Arbeitsname dieser Datei ist entweder bestätigt oder durch den endgültigen Namen ersetzt.
  5. Für jede Funktion der Einheit ist die berechtigte Rolle in der Rechtematrix benannt; für jede gelesene Fremdquelle existiert ein Consumer-Vertrag mit Stub und benannter liefernder Einheit.
- **Tests:** positiv: Vertragslinting und Schemavalidierung der Beispielpayloads. negativ: Beispielpayload ohne Definitionsversion bzw. ohne Datenstand wird vom Schema abgelehnt.
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M0-31, WP-M0-13 (Detailvertragsprozess und CI-Vertragsprüfung)
  - extern: WP-M4-46, WP-M4-45, WP-M4-58 (bestehendes Kennzahlregister, Faktenschema und Filterschema)
  - extern: WP-M1-07, WP-M1-02 (Rollenmodell und Fehlermodell als Grundlage der Rechtematrix)
- **Entscheidungen:** D-05 (Zielwerte, Berichtsumfang Pilot), D-07 (Kostenbewertung, Deckungsbeitragsstufen), D-11 (Export-/Auskunftsprozess, Fristen) – blockieren nur Produktivschaltung; die Verträge werden mit gekennzeichneten Annahmen erstellt.
- **Größe:** M
- **Nicht im Umfang:** Verträge der Alarm-/Regeltabellen (M8d), der Finanztabellen (M8a), der ETA-Gütewerte (M8b).

### WP-M8c-02 – Migration der Analytics- und Berichtstabellen mit RLS

- **Ziel:** Alle Ergänzungstabellen der Einheit sind als Flyway-Migration mit Mandantenschlüssel, RLS und Unveränderlichkeitsregeln ausführbar.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Datenbank: Erweiterung von `metric_definition_revision` (Gültigkeitsintervall, Vorgängerbezug, Transformationshinweis), `aggregate_snapshot` (Definitionsversion, Filterhash, Zähler, Nenner, Status, Vollständigkeit, Datenstand, Unsicherheitsangabe), `dataset_watermark`, `rejection_reason`; neue Tabellen mit Arbeitsnamen: `delay_cause_assignment` (Ursachenzuordnung, append-only), `connection_assessment`, `metric_target_revision`, `report_definition`, `report_schedule`, `report_run`, `report_recipient`, `dashboard_definition_revision`, `export_field_definition`, `holiday_calendar_entry`, `metric_plan_value` (Planwerte für WP-M8c-19) sowie die Faktentabellen der P2-Projektionen (Funnel, Anschluss, Ausfallzeit, Schicht, Energie, Partnerübergabe; Namen in WP-M8c-01 festzulegen); Berichtsläufe gemäß Entscheidung aus WP-M8c-01 in `export_job` oder `report_run`.
  - Datenbank (Regeln): Primärschlüssel `(tenant_id,id)`, zusammengesetzte FKs, RLS USING/WITH CHECK über `current_tenant()` (Kap. 04 §4.1/§4.6); UPDATE/DELETE-Verbot für freigegebene Definitionsrevisionen, Snapshots und Ursachenzuordnungen (Invariante „Gegenereignis statt Änderung“, Kap. 04 §4.5); nicht überlappende aktive Definitions- und Zielwertrevisionen je Scope (Invariante „Nicht überlappende aktive Tarif-/Policy-/Dienstversion“, Kap. 04 §4.5); Indizes beginnend mit `tenant_id`.
  - Backend: Modulzugang `analytics` mit DML nur auf eigenen Tabellen (Architekturtest aus M0/M1); kein INSERT/UPDATE/DELETE-Recht auf Live-Tabellen wie `booking`, `journey`, `journey_leg`, `vehicle_run`, `staff_shift`, `economic_event` (Kap. 01 §1.3 „keine Schreibrechte auf Live-Buchungen“; technische Grundlage des UI-19-Zustands „Live-Schreibversuch“).
  - Ereignisse/Outbox: Nutzung von `inbox` (Dedupe je Ereignis und Consumer) und `outbox` für die Projektionen und Ereignisse der Einheit.
- **Detailverträge vorab:** DDL aus WP-M8c-01; Expand/Contract-Plan gegenüber den M4-Tabellen; Backfillregel für bestehende Snapshots ohne neue Pflichtspalten (Kap. 04 §4.7).
- **Akzeptanzkriterien:**
  1. Migration läuft auf leerer Datenbank und auf einem M5-Stand mit Daten zweier Mandanten; Backfill ist fortsetzbar.
  2. Unter der Runtime-Rolle liefert jede neue Tabelle ohne Tenantkontext keine Zeile.
  3. UPDATE/DELETE auf einer freigegebenen Definitionsrevision, einem Snapshot oder einer Ursachenzuordnung wird von der Datenbank abgelehnt.
  4. Zwei überlappende aktive Revisionen derselben Kennzahl bzw. desselben Zielwert-Scopes können nicht gleichzeitig bestehen.
  5. Ein Schreibversuch der Datenbankrolle des Moduls `analytics` auf eine Live-Tabelle (`booking`, `journey`, `vehicle_run`) wird von der Datenbank abgelehnt.
  6. Die Rückwärtskompatibilität ist gewahrt: die P1-Kennzahlenansicht aus M4 liefert nach der Expand-Migration unveränderte Werte (Regression PH-AT-021).
- **Tests:** positiv: Migration, Backfill, Lesen im Tenantkontext. negativ: Zugriff ohne Kontext, Cross-Tenant-FK, UPDATE auf unveränderliche Zeile. konkurrierend (Datentrennung): zwei parallele Transaktionen aktivieren überlappende Revisionen – genau eine gewinnt; Poolwiederverwendung mit wechselndem Mandanten.
- **Abhängigkeiten:** intern: WP-M8c-01.
  - extern: WP-M1-01, WP-M0-15, WP-M1-46 (RLS-Grundlage, Rollen Migrator/Runtime, Audit)
  - extern: WP-M4-45, WP-M4-46, WP-M4-48, WP-M4-64, WP-M2-13 (bestehende Tabellen `metric_definition_revision`, `aggregate_snapshot`, `dataset_watermark`, `rejection_reason` und Faktentabellen)
- **Entscheidungen:** D-02 (Isolationsstufe; Favorit RLS, blockiert nur Produktivschaltung bei abweichendem Vertrag).
- **Größe:** M
- **Nicht im Umfang:** `alert_policy_revision`, `business_alert`, `alert_acknowledgement` (M8d); `rate_revision`, `cost_category`, `forecast_scenario`, `fuel_transaction` (M4/M8a); `retention_rule`, `erasure_job` (M8e).

### WP-M8c-03 – Standardisierter Referenzdatensatz und Kennzahl-Orakel

- **Ziel:** Ein deterministischer Testdatensatz mit unabhängig berechneten Sollwerten erlaubt, jede Kennzahl der Einheit reproduzierbar zu prüfen.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: Fixturegenerator unter `tests/acceptance/` für zwei Betreibermandanten, einen Aufgabenträgermandanten (drei Firmen, zwei Gebiete für E2E-101), Fahrten über Mitternacht und über den Sommerzeitwechsel (25.10.2026, `Europe/Berlin`), gepoolte Fahrten, Funnelereignisse, Ablehnungen mit Primär- und Sekundärgründen, 20 % GPS-Lücke, Anschlüsse mit ausgefallener Datenquelle, Werkstattaufenthalte innerhalb und außerhalb der Betriebszeit, Kostenereignisse aus Rechenbeispiel B (Kap. 03 §6), Tankereignisse in Litern und kWh, Plan- und Istwerte zweier Monate, Regelkalendereinträge mit Feiertag, Partnerübergaben zwischen zwei Betreibern mit doppelt zugestelltem Partnerereignis, Dienstplan mit zwei offenen Diensten in den kommenden sieben Tagen, Zufriedenheitsrückmeldungen mit ausreichender und mit Kleinststichprobe, Fahrgäste mit Kontaktdaten (Name, Telefon, E-Mail) als Suchmuster für den Pseudonymisierungstest.
  - Adapter/Worker: synthetische Ereignisfixtures für alle Quellen aus M6a, M6b, M6c, M7b, M7c, M7d, M8a, M8b gemäß den Consumer-Verträgen aus WP-M8c-01, damit die abhängigen WPs ohne die liefernde Einheit baubar und prüfbar sind.
  - Backend: Orakel (unabhängige Referenzberechnung, nicht der Produktivcode) mit erwarteten Zählern, Nennern und Werten je Kennzahl und Filter.
  - Konfigurationsparameter: gekennzeichnete Fixturewerte für `kpi_aggregation_min_n`, `reporting_timezone`, `daily_report_time`, `on_time_pickup_tolerance_min`, `on_time_arrival_tolerance_min`, `late_arrival_alert_pct`.
- **Detailverträge vorab:** Fixturebeschreibung mit Herleitung jedes Sollwerts; Kennzeichnung aller Fixturewerte als Testannahme, nicht als fachlicher Default.
- **Akzeptanzkriterien:**
  1. Der Datensatz lässt sich wiederholt laden und liefert identische Fakten und Datenstände.
  2. Für jede Kennzahl mit WP in dieser Datei existiert ein Sollwert mit dokumentierter Herleitung.
  3. Das Fixture PH-AT-021 (zehn Versuche, drei Ablehnungen, zwei mit Sekundärgründen) ist enthalten und unverändert bestehbar.
  4. Die Werte 0,84 EUR/Fahrzeug-km, 5,60 EUR/Fahrgastfahrt und 0,672 EUR/Fahrgast-km aus Kap. 03 §6 sind als Sollwerte hinterlegt.
  5. Ein vollständiger Neuaufbau der Projektionen aus den Fixture-Ereignissen ergibt dieselben Fakten und Sollwerte; doppelt und in falscher Reihenfolge zugestellte Ereignisse verändern das Ergebnis nicht.
  6. Jedes synthetische Fremdereignis validiert gegen den Consumer-Vertrag aus WP-M8c-01; eine Vertragsänderung ohne Fixtureanpassung bricht den Build.
- **Tests:** positiv: Laden und Orakelvergleich. negativ: verändertes Fixture ohne angepasstes Orakel bricht die Suite. Ermöglicht PH-AT-021, E2E-101, E2E-102, E2E-103.
- **Abhängigkeiten:** intern: WP-M8c-02.
  - extern: WP-M4-02, WP-M4-45 (Referenzfixtures und Leseports M4, Projektionsworker)
  - extern: WP-M5-02 (Zwei-Mandanten-Testumgebung der Pilotabnahme)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Lastprofile (`tests/performance/`), ETA-Validierungsdatensatz (M8b).

### WP-M8c-04 – Versionierte Definitionen und Zeitreihen, reproduzierbare Altberichte

- **Ziel:** Eine geänderte Kennzahldefinition oder Schwelle wirkt nur ab ihrem Gültigkeitsbeginn; ein Bericht des Vormonats lässt sich mit der alten oder einer dokumentiert transformierten Definition erneut erzeugen.
- **Anforderungen:** `LH-I.6-KPI-020`
- **Liefergegenstände:**
  - Backend: Modul `analytics`: Lebenszyklus der Definitionsrevision (Entwurf → freigegeben → abgelöst), Gültigkeitsintervall, Auswahl der Revision nach Berichtsperiode; Zeitreihenpunkte tragen Definitionsversion, Konfigurationsrevision der verwendeten Schwellen und Datenstand; Versionswechsel innerhalb einer Zeitreihe wird als Bruch ausgewiesen; optionale dokumentierte Transformation alter Werte auf die neue Definition als eigene, gekennzeichnete Reihe.
  - Datenbank: `metric_definition_revision`, `aggregate_snapshot` (unveränderlich), Verweis auf `config_revision`.
  - API: `/v1/analytics/metrics` (Parameter für Definitionsversion bzw. „gültig zum Berichtszeitraum“; Antwort mit Definition/Version, Datenstand).
  - Oberfläche: UI-19 zeigt verwendete Version, Versionsbruch in Zeitreihen und Definitionshistorie.
  - Ereignisse/Outbox: Ereignis „Kennzahldefinition freigegeben“ (Arbeitsname) für Berichtsjobs und Audit.
  - Konfigurationsparameter: `on_time_pickup_tolerance_min`, `on_time_arrival_tolerance_min`, `reporting_timezone`.
- **Detailverträge vorab:** Zustandsautomat der Definitionsrevision; Regel, welche Revision für einen Zeitraum gilt, der einen Versionswechsel überspannt; Schema der Transformationsdokumentation.
- **Akzeptanzkriterien:**
  1. Der Bericht des Vormonats liefert nach einer Definitionsänderung dieselben Werte, Zähler und Nenner wie vor der Änderung und nennt die alte Version (KPI-020).
  2. Alternativ abrufbare transformierte Werte sind als transformiert gekennzeichnet und verweisen auf die Transformationsbeschreibung (KPI-020).
  3. Eine für die nächste Saison geänderte Pünktlichkeitsschwelle verändert keine Kennzahl vor ihrem Wirksamkeitsbeginn (E2E-103; PH-AT-018).
  4. Eine Zeitreihe über den Versionswechsel zeigt den Bruch sichtbar; es findet keine stille Mischung statt (KPI-020).
  5. Eine freigegebene Definitionsrevision kann nicht geändert, nur abgelöst werden.
- **Tests:** positiv: E2E-103 mit Referenzdatensatz. negativ: Änderung einer freigegebenen Revision; rückwirkender Gültigkeitsbeginn in bereits berichtete Periode ohne Transformationskennzeichnung. konkurrierend (Datentrennung): Berichtsabruf während Freigabe einer neuen Revision liefert vollständig alte oder vollständig neue Version. Besteht E2E-103; Beitrag zu PH-AT-018.
- **Abhängigkeiten:** intern: WP-M8c-02, WP-M8c-03.
  - extern: WP-M4-46 (Kennzahlregister mit Definitionsversion und Snapshot)
  - extern: WP-M8d-03, WP-M8d-05 (vollständige Konfigurationsschemas und Wirksamkeitsintervalle der Toleranzparameter)
- **Entscheidungen:** keine für den Bau; D-05 (verbindliche Berichtsdefinitionen des Auftraggebers) blockiert nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Versionierung der Kostensätze (WP-M8c-13); Restatement-Ansicht des Periodenabschlusses (M8a).

### WP-M8c-05 – Datenvollständigkeit und ausgewiesene Unsicherheit

- **Ziel:** Jede Kennzahl zeigt, wie vollständig ihre Quelldaten sind, und ersetzt bei Lücken die Scheingenauigkeit durch einen Warnhinweis mit Unsicherheitsangabe.
- **Anforderungen:** `LH-I.6-KPI-018`
- **Liefergegenstände:**
  - Backend: Vollständigkeitsquote je Kennzahl und Filter aus `dataset_watermark` und Faktenabdeckung; Status `PROVISIONAL` + Abdeckungsgrad bei unvollständigen Quellen (Kap. 03 §7); fehlende Daten als fehlend, nicht als null (COST-08); Darstellungsregel für reduzierte Anzeigegenauigkeit bzw. Bandbreite.
  - Datenbank: `dataset_watermark`, `aggregate_snapshot` (Vollständigkeit, Unsicherheitsangabe).
  - API: `/v1/analytics/metrics` (Felder Vollständigkeit, Status, Warnhinweis).
  - Oberfläche: UI-19 Zustand „geringe Qualität“ mit Warnhinweis und Quelle der Lücke.
- **Detailverträge vorab:** Definition der Vollständigkeit je Quellenart (GPS, Fahrerereignis, Kostenquelle, Partnerdaten); Schwelle, ab der gewarnt wird (im Pflichtenheft nicht beziffert, Offener Punkt 9); Form der Unsicherheitsangabe.
- **Akzeptanzkriterien:**
  1. Bei 20 Prozent GPS-Ausfall im Referenzdatensatz erscheint für die betroffenen Kennzahlen ein Warnhinweis mit Vollständigkeitsquote; der Wert wird nicht mit unbegründeter Präzision angezeigt (KPI-018).
  2. Kennzahlen ohne betroffene Quelle bleiben ohne Warnhinweis (KPI-018).
  3. Fehlende Energie- oder Kostendaten werden als fehlend ausgewiesen und nicht als 0 gerechnet (KPI-018; COST-08).
  4. Der Warnhinweis ist in Webansicht, API-Antwort und Export identisch vorhanden.
- **Tests:** positiv: Fixture mit 20 % GPS-Lücke (PH-AT-021, E2E-084). negativ: vollständige Daten erzeugen keinen Hinweis; Lücke in einer nicht verwendeten Quelle erzeugt keinen Hinweis. Besteht den Datenlückenteil von PH-AT-021 für alle P2-Kennzahlen. negativ (Datentrennung): Vollständigkeitsquote eines fremden Mandanten ist nicht abrufbar. konkurrierend (Datentrennung): Abruf während laufender Nachlieferung von GPS-Daten liefert einen konsistenten Datenstand mit dazu passender Vollständigkeitsquote, nie neuen Wert mit alter Quote.
- **Abhängigkeiten:** intern: WP-M8c-02, WP-M8c-03.
  - extern: WP-M4-45, WP-M4-46 (`dataset_watermark` und Vollständigkeitsangabe der Faktenbasis)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Qualitätsausschluss von Fahrbeobachtungen für das ETA-Lernen (M8b).

### WP-M8c-06 – Pünktlichkeit, Wartezeit, Verspätung, Ablehnungsgründe mit dokumentierter Definition

- **Ziel:** Berichte zu Pünktlichkeit, Wartezeiten, Verspätungen und Ablehnungsgründen tragen ihre Definition einschließlich des Umgangs mit fehlenden Daten in sich.
- **Anforderungen:** `LH-6.14-ANA-004`
- **Liefergegenstände:**
  - Backend: ausführbare Definitionen für Wartezeit (Definition im Detailvertrag, Offener Punkt 25) und 95. Perzentil; Definitionsblock (Zähler, Nenner, Ausschlüsse, Behandlung fehlender Ist-Zeitpunkte, Version) als Bestandteil jedes Berichts; frühe Abholung nicht als negative Verspätung.
  - Datenbank: `metric_definition_revision`, `aggregate_snapshot`, `rejection_reason`.
  - API: `/v1/analytics/metrics`.
  - Oberfläche: UI-19 Berichtsansicht mit aufklappbarer Definition je Kennzahl.
  - Konfigurationsparameter: `on_time_pickup_tolerance_min`, `on_time_arrival_tolerance_min`, `pickup_window_min`, `arrival_grace_min`, `max_wait_min`, `no_show_grace_min`.
  - Kennzahlen: `Abholung pünktlich`, `Ankunft pünktlich`, `Mittlere Abholverspätung`, `Mittlere Ankunftsverspätung`, `95%-Abholverspätung`, `Kapazitätsablehnungen`, `Personalmangelablehnungen`, `Ausstattungsablehnungen`, `Gebietsablehnungen`, `Zeitfensterablehnungen`, `Technische Partnerablehnungen`.
- **Detailverträge vorab:** Definition „Wartezeit“; Quantildefinition (`95%-Abholverspätung`: „Quantildefinition versionieren“); Regel für nicht bewertbare Abholungen/Ankünfte.
- **Akzeptanzkriterien:**
  1. Jeder Bericht dieser Kennzahlen enthält die verwendete Definition und Version sowie die Regel für fehlende Daten (ANA-004).
  2. Fahrten ohne Ist-Zeitpunkt werden aus dem Nenner ausgeschlossen und als „nicht bewertbar“ mit Anzahl ausgewiesen (ANA-004).
  3. Die Werte stimmen mit dem Orakel des Referenzdatensatzes überein; E2E-082 (08:22 gegen Zusage 08:10–08:15) und E2E-083 (17 Minuten) bleiben korrekt (ANA-004).
  4. Die Summe der Primärgründe entspricht der Zahl der Ablehnungen; Sekundärgründe erhöhen sie nicht (ANA-004; PH-AT-021).
- **Tests:** positiv: Orakelvergleich, E2E-082, E2E-083. negativ: Bericht ohne Definitionsblock wird nicht ausgeliefert; Nullnenner ergibt „nicht berechenbar“. Regression E2E-080, E2E-081. negativ (Datentrennung/Rechte): Abruf mit Dienst- oder Gebietsfilter eines fremden Mandanten liefert keine Zeile und keinen Existenzhinweis; Rolle ohne Kennzahlrecht wird abgelehnt.
- **Abhängigkeiten:** intern: WP-M8c-04, WP-M8c-05.
  - extern: WP-M4-53, WP-M4-54, WP-M4-48, WP-M4-49, WP-M4-50, WP-M4-52 (Pünktlichkeitskennzahlen gegen Zusageversion, Ablehnungscodes, getrennte Zeitpunkte)
- **Entscheidungen:** keine für den Bau; D-05 (Toleranzwerte) blockiert nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Ursachenklassifikation der Verspätung (WP-M8c-07); Anschlusspünktlichkeit (WP-M8c-08).

### WP-M8c-07 – Verspätungsursachen mit revisionssicherem Ursachenwechsel

- **Ziel:** Jede Verspätung trägt eine Ursache aus dem festen Katalog; ein manueller Wechsel bleibt nachvollziehbar und wirkt nur auf die dafür zugelassenen Kennzahlen.
- **Anforderungen:** `LH-I.6-KPI-016`
- **Liefergegenstände:**
  - Backend: Ursachenkatalog Verkehrszeit, Anfahrt, Aufenthalt, Vorfahrt, Fremdpartner, Dispositionsfehler; automatische Erstzuordnung aus Fakten; manueller Ursachenwechsel als neue Zuordnungsrevision mit Akteur, Begründung, Zeitpunkt; Liste der von der Ursache abhängigen Kennzahlen (Ursachenverteilung) getrennt von ursachenunabhängigen (Verspätungsminuten, Pünktlichkeitsquote).
  - Datenbank: `delay_cause_assignment` (Arbeitsname, append-only, Vorgängerbezug), `audit_event`.
  - API: Arbeitsname `POST /v1/analytics/delay-causes/{id}/reassign` mit Idempotenzschlüssel und Revision; Lesen über `/v1/analytics/metrics` (Dimension Ursache).
  - Oberfläche: UI-19 Ursachenverteilung; Dialog Ursachenwechsel mit Pflichtbegründung und Historie.
  - Ereignisse/Outbox: Ereignis „Verspätungsursache geändert“ (Arbeitsname) zur Neuberechnung betroffener Snapshots.
- **Detailverträge vorab:** Zustandsautomat der Ursachenzuordnung; Regeln der automatischen Erstzuordnung; abschließende Liste der Kennzahlen, die sich durch einen Ursachenwechsel ändern dürfen (Offener Punkt 7); berechtigte Rolle.
- **Akzeptanzkriterien:**
  1. Jede Verspätung im Referenzdatensatz ist genau einer der sechs Ursachen zugeordnet oder als „nicht klassifiziert“ ausgewiesen (KPI-016; der Status „nicht klassifiziert“ ist eine Planungsannahme für Fälle ohne ausreichende Fakten, Offener Punkt 7).
  2. Nach einem manuellen Ursachenwechsel sind alte Ursache, neue Ursache, Akteur, Zeitpunkt und Begründung abrufbar; die alte Zuordnung ist nicht überschrieben (KPI-016).
  3. Der Wechsel ändert die Ursachenverteilung, nicht aber Verspätungsminuten, Pünktlichkeitsquote oder Anzahl der Fahrten (KPI-016).
  4. Bereits ausgelieferte Berichte bleiben unter ihrem Snapshot unverändert; der neue Stand ist als spätere Fassung erkennbar (KPI-016).
  5. Ein Benutzer ohne Berechtigung oder aus fremdem Mandanten kann keine Ursache ändern.
  6. Ein Ursachenwechsel auf einer Verspätung, deren Bericht bereits in einer abgeschlossenen Periode liegt, verändert den ausgelieferten Bericht nicht; ob und bis wann solche Wechsel zulässig sind, regelt der Detailvertrag (Offener Punkt 7).
- **Tests:** positiv: Erstzuordnung und Wechsel. negativ: Wechsel ohne Begründung, mit veralteter Revision (412), durch unberechtigte Rolle, auf fremden Mandanten. konkurrierend (Datentrennung/Integrität): zwei gleichzeitige Wechsel derselben Verspätung – genau einer gewinnt, der andere erhält einen Konflikt. Regression E2E-083. Zusätzlich negativ: Wechsel ohne `If-Match` (428), Ursache außerhalb des Katalogs, wiederholter Aufruf mit gleichem Idempotenzschlüssel und anderem Inhalt (409, PH-AT-011).
- **Abhängigkeiten:** intern: WP-M8c-06.
  - extern: WP-M1-07, WP-M1-08, WP-M1-46, WP-M1-13 (Rollen-/Objektberechtigung, Audit, Idempotenzregister)
  - extern: WP-M4-52, WP-M4-55 (Verspätungsfakten gegen Zusageversion, Verursachungskategorie nicht durchgeführter Fahrten)
  - extern: WP-M6a-13, WP-M6a-25 (Segment- und Partnerbezug für die Ursache „Fremdpartner“)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Schuld- oder Leistungsbewertung einzelner Fahrer (ausgeschlossen, Kap. 01 §1.6; M8b).

### WP-M8c-08 – ÖPNV-Anschlüsse: erreicht, gefährdet, verpasst, nicht bewertbar

- **Ziel:** Anschlüsse an den ÖPNV werden in vier getrennten Klassen gezählt; fehlende Anschlussdaten führen zu „nicht bewertbar“, nie zu „erreicht“.
- **Anforderungen:** `LH-I.6-KPI-023`
- **Liefergegenstände:**
  - Backend: Bewertung je geplantem Anschluss aus Ist-Ankunft, Abfahrtsdaten und Mindestumsteigezeit; Klassen erreicht, gefährdet, verpasst, nicht bewertbar; Aktualitätsprüfung der Anschlussquelle (Staleness).
  - Datenbank: `connection_assessment` (Arbeitsname) mit Quelle, Quellenzeit, Klasse, Begründung; gelesen über Projektion: `realtime_observation`, `transfer_hub`, `transfer_rule`, `journey_leg`.
  - API: `/v1/analytics/metrics` (Dimension Anschlussklasse).
  - Oberfläche: UI-19 Anschlussauswertung mit vier Klassen und Hinweis auf Datenquellenausfall.
  - Konfigurationsparameter: `transfer_min_time_min`, `interchange_min_time_min`, `advance_arrival_buffer_min`.
  - Kennzahlen: `Nicht erreichte Anschlüsse` (Nenner: bewertbare geplante Anschlüsse; unbekannte Daten separat).
- **Detailverträge vorab:** Schwelle für „gefährdet“ (Offener Punkt 12); Regel, ab welchem Quellenalter ein Anschluss nicht bewertbar ist; Zeitpunkt der Bewertung (laufend vs. abschließend).
- **Akzeptanzkriterien:**
  1. Bei ausgefallener Anschlussdatenschnittstelle wird der Anschluss als „nicht bewertbar“ gezählt und nicht als erreicht (KPI-023).
  2. Die vier Klassen sind disjunkt und summieren sich zur Zahl der geplanten Anschlüsse (KPI-023).
  3. `Nicht erreichte Anschlüsse` verwendet nur bewertbare Anschlüsse im Nenner; die Zahl der nicht bewertbaren wird daneben ausgewiesen (KPI-023).
- **Tests:** positiv: je ein Anschluss pro Klasse im Referenzdatensatz. negativ: veraltete Quelle, fehlende Abfahrtszeit, Anschluss eines fremden Mandanten. Beitrag zu E2E-101 (Anschlussqualität). konkurrierend (Integrität): verspätet und doppelt eintreffende Echtzeitbeobachtung nach bereits abgeschlossener Bewertung führt zu genau einer nachvollziehbaren Neubewertung, nicht zu zwei Zählungen.
- **Abhängigkeiten:** intern: WP-M8c-04, WP-M8c-05.
  - extern: WP-M6a-13, WP-M6a-08, WP-M6a-18, WP-M6a-19 (mehrgliedrige Reiseketten, Übergabepunkte, Legereignisse)
  - extern: WP-M6b-27, WP-M6a-27 (Fahrplan- und Echtzeitdaten des ÖPNV mit Quellenzeit und Staleness)
- **Entscheidungen:** D-04 (Anschlussrisiko; die Kennzahl ist unabhängig baubar, die Bewertungsschwellen blockieren nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** operative Anschlusssicherung und Umbuchung (M6); Partnerübergaben zwischen Betreibern (WP-M8c-18).

### WP-M8c-09 – Pooling-Kennzahlen mit Bezugsgröße und Formel

- **Ziel:** Pooling-Kennzahlen nennen ihre Bezugsgröße und Formel und lassen sich aus dem standardisierten Testdatensatz nachrechnen.
- **Anforderungen:** `LH-6.14-ANA-005`
- **Liefergegenstände:**
  - Backend: Berechnung gemeinsamer Wegstrecke je Fahrgastfahrt aus Stoppfolge und Besetzung; ausführbare Definition mit expliziter Bezugsgröße.
  - Datenbank: `metric_definition_revision`, `aggregate_snapshot`; Fakten aus `vehicle_run` und Stoppereignissen.
  - API: `/v1/analytics/metrics`.
  - Oberfläche: UI-19 Pooling-Kachel mit Formel und Nenner.
  - Konfigurationsparameter: `pooling_allowed` (Dienste ohne Pooling getrennt ausweisen).
  - Kennzahlen: `Pooling-Nutzungsquote` (Nenner: abgeschlossene Fahrgastfahrten; „Mindestüberlappung konfiguriert“).
- **Detailverträge vorab:** Definition „relevante gemeinsame Wegstrecke“ und Parameter der Mindestüberlappung (im Parameterkatalog nicht vorhanden, Offener Punkt 23).
- **Akzeptanzkriterien:**
  1. Die Pooling-Kennzahlen des Referenzdatensatzes stimmen exakt mit dem Orakel überein (ANA-005).
  2. Jede Pooling-Kennzahl weist Bezugsgröße, Formel und Definitionsversion aus (ANA-005).
  3. Ohne abgeschlossene Fahrgastfahrten ist der Wert „nicht berechenbar“.
- **Tests:** positiv: Orakelvergleich mit gepoolten und ungepoolten Fahrten. negativ: Nullnenner; Dienst mit `pooling_allowed` = aus wird nicht als 0 % Pooling fehlgedeutet. negativ (Datentrennung): Fahrten eines fremden Mandanten gehen nicht in Zähler oder Nenner ein.
- **Abhängigkeiten:** intern: WP-M8c-03, WP-M8c-04.
  - extern: WP-M4-56, WP-M4-57 (Kilometer- und Auslastungsfakten, Fahrgastkilometer)
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Optimierung der Poolingquote (M7/M9).

### WP-M8c-10 – Angebotsabbruch- und Konversionsquote aus Funnelereignissen

- **Ziel:** Angebotsabbruch und Konversion werden aus ausdrücklichen Funnelereignissen berechnet; eine abgewiesene Anfrage zählt nie als freiwillige Nichtbuchung.
- **Anforderungen:** `LH-I.6-KPI-026`
- **Liefergegenstände:**
  - Backend: Funnelstufen Anfrage → gültiges Angebot → Hold/Checkout → bestätigte Buchung mit expliziten Ereignissen „Angebot abgelaufen“, „Angebot verworfen/Checkout abgebrochen“, „Ablehnung“; Abbruch nur für Versuche mit mindestens einem gültigen Angebot.
  - Datenbank: Fakten aus `travel_attempt` (`primary_rejection`, `secondary_rejections`), `fare_quote`, `capacity_hold`, `booking`.
  - API: `/v1/analytics/metrics`.
  - Oberfläche: UI-19 Funnelansicht mit getrennten Säulen „abgewiesen“ und „nicht gebucht trotz Angebot“.
  - Kennzahlen: `Anfragen`, `Technische Anfragefehler`, `Angebotsquote`, `Buchungskonversion`, `Buchungsstornoquote Fahrgast`, `Buchungsstornoquote Betreiber`; Angebotsabbruchquote als zusätzliche Definition (nicht unter den 48 Katalognamen, Offener Punkt 15).
- **Detailverträge vorab:** Ereignisliste des Funnels und Sichtfenster für „nicht gebucht“ (Katalog: „Sichtfenster und Stornofälle definiert“); Definition der Angebotsabbruchquote mit Nenner.
- **Akzeptanzkriterien:**
  1. Abgewiesene Anfragen erscheinen ausschließlich unter Ablehnungen und in keiner Abbruch- oder Nichtbuchungszahl (KPI-026).
  2. Angebotsabbruchquote und Konversionsquote entsprechen dem Orakel und verwenden gültige Angebote als Nenner (KPI-026).
  3. Technische Anfragefehler sind von operativen Ablehnungen und von Abbrüchen getrennt (KPI-026).
  4. Ein wiederholter neuer Versuch desselben Fahrgastes zählt als eigener Versuch.
- **Tests:** positiv: Funnel des Referenzdatensatzes. negativ: Versuch ohne Angebot im Abbruchnenner; doppelt zugestelltes Funnelereignis zählt einmal. Regression PH-AT-021. negativ (Datentrennung): Funnelereignis mit fremdem Mandantenbezug wird verworfen. konkurrierend (Idempotenz): dasselbe Funnelereignis wird von zwei Projektionsworkern parallel verarbeitet – eine fachliche Wirkung; Ereignisse in vertauschter Reihenfolge (Bestätigung vor Angebot) ergeben nach Nachlesen denselben Funnelstand (PH-AT-017-Muster).
- **Abhängigkeiten:** intern: WP-M8c-03, WP-M8c-04.
  - extern: WP-M2-21, WP-M2-22, WP-M2-23, WP-M2-26 (Ereignisse zu Angebot, Hold, Ablauf, Bestätigung, Storno)
  - extern: WP-M4-47, WP-M4-51 (Versuchseintrag, Nachfragetrichter der Buchungsstufen)
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Funnel föderierter Partnersegmente (WP-M8c-18).

### WP-M8c-11 – Nicht bediente Nachfrage mit Engpass-Heatmap und Kleingruppenschutz

- **Ziel:** Nicht bediente Nachfrage ist nach Grund, Ursprung, Zielrelation und Zeit aggregiert sichtbar; die Heatmap zeigt Engpässe, ohne einzelne Fahrgastadressen oder kleine Gruppen offenzulegen.
- **Anforderungen:** `LH-I.6-KPI-015`
- **Liefergegenstände:**
  - Backend: Aggregation abgelehnter Versuche nach Primärgrund, grober Ursprungszelle, Zielrelation (Zellenpaar) und Zeitfenster; zentrale Unterdrückungskomponente: Zellen unter `kpi_aggregation_min_n` werden unterdrückt, einschließlich Schutz gegen Differenzbildung benachbarter Filter (PH-AT-021); keine Adressen oder exakten Koordinaten in Projektion, API und Export.
  - Datenbank: Fakten aus `travel_attempt` (`coarse_origin`, `coarse_destination`), `rejection_reason`, `aggregate_snapshot`.
  - API: `/v1/analytics/metrics` (Dimensionen Grund, Ursprungszelle, Zielrelation, Zeitfenster; Markierung „unterdrückt“).
  - Oberfläche: UI-19 Heatmap nicht bedienter Nachfrage mit Grundfilter und Zeitfilter; Zustand „Keine Daten“ und „unterdrückt“.
  - Konfigurationsparameter: `kpi_aggregation_min_n`, `reporting_timezone`, `capacity_reject_alert_count` (nur als angezeigter Schwellbezug; Alarm selbst in M8d).
  - Kennzahlen: `Kapazitätsablehnungen`, `Personalmangelablehnungen`, `Ausstattungsablehnungen`, `Gebietsablehnungen`, `Zeitfensterablehnungen`.
- **Detailverträge vorab:** Raster/Kodierung der groben Zellen (Offener Punkt 6); Unterdrückungsregel einschließlich Sekundärunterdrückung; Filterschema-Erweiterung.
- **Akzeptanzkriterien:**
  1. Die Heatmap zeigt die Kapazitätsengpässe des Referenzdatensatzes je Zelle und Zeitfenster; kein Antwortfeld enthält Adresse, Haltepunkt einer Einzelperson oder exakte Koordinate (KPI-015).
  2. Nicht bediente Nachfrage ist nach Grund, Ursprung, Zielrelation und Zeit filter- und aggregierbar (KPI-015).
  3. Zellen unter der Schwelle werden unterdrückt; auch durch Differenz zweier benachbarter Filter lässt sich ihr Wert nicht ermitteln (KPI-015; PH-AT-021).
  4. Ein Versuch zählt in genau einem Primärgrund.
- **Tests:** positiv: Heatmap gegen Orakel. negativ: Filterkombination, die eine Einzelperson isolieren würde; Abruf fremder Mandantenzellen. konkurrierend (Datentrennung): parallele Abfragen mit komplementären Filtern während laufender Projektion liefern keinen rekonstruierbaren Kleinwert. Besteht den Aggregationsschutzteil von PH-AT-021; Datenquelle für E2E-099.
- **Abhängigkeiten:** intern: WP-M8c-04, WP-M8c-10.
  - extern: WP-M4-49, WP-M4-50, WP-M4-58 (Ablehnungscodes, Filterdimensionen, bestehende Kleingruppenunterdrückung)
- **Entscheidungen:** D-11 (Freigabe der Mindestzellgröße, Kap. 01 §1.7 „Aggregierte Statistik“; blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** zentraler Alert und Kapazitätsvorschlag (E2E-099, M8d); Mindestgruppengröße für personenbezogene Fahrerauswertungen (M8b).

### WP-M8c-12 – Nachfrage-Heatmap und Zeit-Histogramm

- **Ziel:** Die gesamte Nachfrage wird datensparsam geografisch als Heatmap und zeitlich als Histogramm gezeigt; zu kleine Gruppen bleiben verborgen.
- **Anforderungen:** `LH-6.14-ANA-007`
- **Liefergegenstände:**
  - Backend: Aggregation aller gültigen Anfrageversuche je grober Zelle und je Zeitklasse (Stunde, Wochentag) unter Nutzung der Unterdrückungskomponente aus WP-M8c-11.
  - API: `/v1/analytics/metrics` (Histogramm- und Zellaggregat).
  - Oberfläche: UI-19 Nachfrage-Heatmap und Histogramm mit Textalternative (Tabelle) für Screenreader.
  - Konfigurationsparameter: `kpi_aggregation_min_n`, `reporting_timezone`.
  - Kennzahlen: `Anfragen`.
- **Detailverträge vorab:** Klassenbreiten des Histogramms; Darstellung unterdrückter Zellen.
- **Akzeptanzkriterien:**
  1. Zellen und Histogrammklassen unterhalb der Datenschutzschwelle werden unterdrückt und als unterdrückt gekennzeichnet (ANA-007).
  2. Heatmap und Histogramm summieren sich – abzüglich ausgewiesener Unterdrückung – zur Zahl der Anfragen des Zeitraums (ANA-007).
  3. Stundenklassen folgen `reporting_timezone`; die doppelte Stunde des Sommerzeitwechsels wird nicht zusammengelegt.
- **Tests:** positiv: Orakelvergleich. negativ: Zelle mit n unter Schwelle; Versuch, per Zeit- und Raumfilter eine Einzelanfrage zu isolieren. Beitrag zu E2E-102. konkurrierend (Datentrennung): parallele Abfragen mit komplementären Zeit- und Raumfiltern liefern keinen durch Differenz rekonstruierbaren Kleinwert (PH-AT-021).
- **Abhängigkeiten:** intern: WP-M8c-11.
- **Entscheidungen:** D-11 (Mindestzellgröße; blockiert nur Produktivschaltung).
- **Größe:** S
- **Nicht im Umfang:** Nachfrageprognose (M9).

### WP-M8c-13 – Kosten pro Fahrt und Fahrgastkilometer aus versionierten Kostensätzen

- **Ziel:** Kosten pro Fahrt und pro Fahrgastkilometer werden aus konfigurierten, versionierten Kostensätzen berechnet; eine Satzänderung lässt alte Berichte unverändert.
- **Anforderungen:** `LH-6.14-ANA-006`
- **Liefergegenstände:**
  - Backend: Kalkulation je Periode mit dem zum Leistungszeitraum gültigen Kostensatz; Snapshot hält Satzrevision, Bewertungsart (`valuation`), Periode, Umfang und Datenstand fest (Kap. 03 §1.1); gleiche Periode und Umfang für Zähler und Nenner; Nullnenner → `null` + `ZERO_DENOMINATOR`; unvollständige Kosten → `PROVISIONAL` + Abdeckungsgrad (Kap. 03 §7).
  - Datenbank: lesend über Projektion `rate_revision`, `cost_category`, `economic_event`, `cost_object`, `allocation_line`; schreibend `aggregate_snapshot`.
  - API: `/v1/analytics/metrics`; Verweis auf `/v1/vehicles/{id}/total-cost` als Detailquelle.
  - Oberfläche: UI-19 Kostenkennzahlen mit Nenner, Satzrevision und Datenstand; Übergang nach UI-15.
  - Kennzahlen: `Kosten pro Fahrgastfahrt`, `Kosten pro Fahrgastkilometer`, `Kosten pro Fahrzeugkilometer`, `Fahrgastkilometer`.
- **Detailverträge vorab:** Verhältnis „konfigurierte Kostensätze“ zu Istkosten aus `economic_event` (Offener Punkt 24); Schema der Satzrevision und ihres Wirksamkeitsintervalls; Rundung der Anzeige bei Export der Berechnungsbasis (Kap. 03 §6).
- **Akzeptanzkriterien:**
  1. Eine neue Kostensatzrevision ist versioniert mit Wirksamkeitsbeginn, Ersteller und Grund gespeichert (ANA-006).
  2. Ein vor der Satzänderung erzeugter Bericht liefert bei erneutem Abruf dieselben Werte mit der alten Satzrevision (ANA-006).
  3. Rechenbeispiel B ergibt 5,60 EUR/Fahrgastfahrt und 0,672 EUR/Fahrgast-km; die Anzeige darf runden, der Export enthält die Berechnungsbasis (ANA-006; Kap. 03 §6).
  4. Bei null Fahrgastfahrten bzw. null Fahrgastkilometern ist der Wert „nicht berechenbar“.
- **Tests:** positiv: Orakelvergleich Rechenbeispiel B; Satzwechsel zur Periodengrenze. negativ: rückwirkender Satz für bereits berichtete Periode verändert den Altbericht nicht; Nullnenner; fehlende Kostendaten sind fehlend statt 0 (COST-08). konkurrierend (Geld): Berichtsabruf parallel zur Freigabe einer Satzrevision liefert konsistent eine Revision. negativ (Sicherheit/Datentrennung): Rolle ohne Finanzrecht erhält keine Kostenkennzahl; Kostenobjekt eines fremden Mandanten ist nicht adressierbar; identisch benannte Kostenstelle zweier Mandanten wird nicht vermischt (PH-AT-001).
- **Abhängigkeiten:** intern: WP-M8c-04, WP-M8c-05.
  - extern: WP-M4-33, WP-M4-39, WP-M4-42, WP-M4-56 (Kostenereignisse, Kostenklassifikation, Kilometerklassifikation, Fahrgastkilometer)
  - extern: WP-M8a-39, WP-M8a-32 (`rate_revision` mit Wirksamkeitsintervall, TCO je Fahrzeug)
- **Entscheidungen:** D-07 (Kostenarten, Treiber, Bewertung; Bau mit Testannahmen aus Kap. 03 möglich, blockiert Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Pflege der Kostensätze und TCO-Ansicht (M8a); Wirtschaftlichkeit je Vertrag (WP-M8c-14).

### WP-M8c-14 – Wirtschaftlichkeit je Vertrag: Kosten, Zuschussbedarf, Deckungsbeitrag

- **Ziel:** Je Vertrag sind Kosten pro Fahrt und pro Fahrgast, Zuschussbedarf und Deckungsbeitrag ausgewiesen; variable und fixe Kosten stehen getrennt und werden nicht doppelt summiert.
- **Anforderungen:** `LH-I.6-KPI-027`
- **Liefergegenstände:**
  - Backend: Wirtschaftlichkeitsbericht je Vertrag: Kreuztabelle `behavior` (FIXED/VARIABLE/MIXED) × `attribution` (DIRECT/ALLOCATED) als zwei Achsen (Kap. 03 §1); Originalposition und Umlage nie zusammengezählt; Deckungsbeitrag = Nettoerlöse minus direkt zurechenbare variable Kosten, Fixkosten separat; Zuschussbedarf = freigegebene Kostenbasis minus tarifliche Nettoerlöse minus bereits berücksichtigte andere Deckung (Kap. 03 §7); konsolidierte Sicht nur mit eigener Freigabe, sonst `NOT_AVAILABLE`.
  - Datenbank: lesend über Projektion `economic_event`, `allocation_set`, `allocation_line`, `cost_object`, `settlement_line`, `partner_contract_version`; schreibend `aggregate_snapshot`.
  - API: `/v1/analytics/metrics` (Dimension Vertrag).
  - Oberfläche: UI-19 Wirtschaftlichkeit je Vertrag; UI-15 Zustände „Datenlücke“, „Nenner 0“, „fehlende Partnerrechte“.
  - Kennzahlen: `Kosten pro Fahrgastfahrt`, `Zuschuss pro Fahrgastfahrt`, `Deckungsbeitrag`.
- **Detailverträge vorab:** welches Vertragsobjekt gemeint ist (Offener Punkt 16); Definition „Kosten/Fahrgast“ gegenüber `Kosten pro Fahrgastfahrt`; Aufschlüsselung der Fördermittel nach Herkunft; Deckungsbeitragsstufen.
- **Akzeptanzkriterien:**
  1. Der Bericht weist variable und fixe Kosten getrennt aus; die Gesamtsumme entspricht der Summe der Ursprungsereignisse, keine Position ist doppelt enthalten (KPI-027).
  2. Kosten/Fahrt, Kosten/Fahrgast, Zuschussbedarf und Deckungsbeitrag sind je Vertrag vorhanden und stimmen mit dem Orakel überein (KPI-027).
  3. Original plus Umlage wird in keiner Standardsicht doppelt summiert (KPI-027; COST-08).
  4. Bereits verbuchte Zuschüsse werden im Zuschussbedarf nicht doppelt abgezogen.
  5. Ohne Freigabe für Partnerdaten zeigt die konsolidierte Sicht `NOT_AVAILABLE`, keinen Schätzwert (COST-06).
- **Tests:** positiv: Rechenbeispiel B (Y 1.680, konsolidiert 1.280 nur mit Berechtigung). negativ: Rolle ohne Finanzrecht; Vertrag eines fremden Mandanten; null Fahrgäste → `null`. konkurrierend (Geld): Bericht während Periodenabschluss liest konsistenten Abschlusssnapshot. Beitrag zu PH-AT-015 (keine Doppelzählung nach später Gutschrift), E2E-072.
- **Abhängigkeiten:** intern: WP-M8c-13.
  - extern: WP-M6c-19, WP-M6c-20, WP-M6c-23 (Clearingpositionen und Erlösaufteilung je Vertrag)
  - extern: WP-M8a-32, WP-M8a-34, WP-M8a-18 (Kostenbasis für Deckungsbeitrag, Budgets, Periodenabschluss-Snapshot)
  - extern: UNGEDECKT – Zuschussbuchungen (Erlös-/Zuschussquelle; siehe Offener Punkt 35 und OP-39 in M8a)
- **Entscheidungen:** D-07 (Deckungsbeitragsstufen, Zuschussbehandlung, Sichtrechte auf konsolidierte Werte; blockiert Produktivschaltung), D-06 (Finanzumfang; blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Budgetpflege und `Budgetabweichung` als Finanzfunktion (M8a); Clearing-Gegenberichte UI-16 (M6c).

### WP-M8c-15 – Treibstoff- und Energiekennzahlen je Antriebsart

- **Ziel:** Verbrauchskennzahlen tragen die zur Antriebsart gehörige Einheit und Plausibilitätsgrenzen; L/100 km und kWh/100 km werden nie unkommentiert zusammengeführt.
- **Anforderungen:** `LH-I.6-KPI-028`
- **Liefergegenstände:**
  - Backend: Verbrauch je Antriebsart aus validierten Mengen und zugehörigen Kilometern; getrennte Reihen je Einheit; Flottensummen nur je Einheit oder mit ausdrücklich gekennzeichneter Umrechnung; Plausibilitätsprüfung mit Kennzeichnung statt Verwerfen; Tankfüllstands-/Periodenabweichungen und Ladeverluste gesondert (Kap. 03 §1.1, §7 „Energie“).
  - Datenbank: lesend über Projektion `fuel_transaction`, Kilometerfakten, `vehicle`; schreibend `aggregate_snapshot`.
  - API: `/v1/analytics/metrics` (Pflichtfeld Einheit je Wert).
  - Oberfläche: UI-19 Energiekennzahlen getrennt nach Antriebsart; Warnmarkierung mit Berechnungsbasis.
  - Konfigurationsparameter: `fuel_use_alert_pct`, `fuel_max_liters`, `fuel_price_alert_pct`.
  - Kennzahlen: `Kraftstoff L/100 km`, `Strom kWh/100 km`, `Tankbeleg Vollständigkeit`.
- **Detailverträge vorab:** Plausibilitätsgrenzen je Antriebsart (im Pflichtenheft ohne Werte, Offener Punkt 17); Behandlung von Hybrid- und sonstigen Antrieben; Regel „Ladeverluste getrennt auswählbar“.
- **Akzeptanzkriterien:**
  1. Kein Bericht, keine API-Antwort und kein Export enthält einen aus L/100 km und kWh/100 km gebildeten Durchschnitt oder eine Summe ohne ausdrücklichen Kommentar und Umrechnungsangabe (KPI-028).
  2. Jeder Verbrauchswert trägt Einheit und Antriebsart; Plausibilitätsgrenzen sind je Antriebsart hinterlegt und versioniert (KPI-028).
  3. Ein unplausibler Verbrauch wird mit Warnung und Berechnungsbasis (Menge, Kilometer, Zeitraum) ausgewiesen; der Datensatz bleibt erhalten (KPI-028; E2E-070).
  4. Fehlende Energiedaten sind als fehlend markiert, nicht als 0 (COST-08).
- **Tests:** positiv: gemischte Flotte im Referenzdatensatz. negativ: Aggregationsanfrage über beide Antriebsarten ohne Einheitenfilter liefert getrennte Reihen; Menge ohne Kilometerbezug ist „nicht berechenbar“. Beitrag zu E2E-070. negativ (Datentrennung): Fahrzeug eines fremden Mandanten nicht adressierbar. konkurrierend (Idempotenz): doppelt importiertes Tankereignis erhöht die Menge nicht.
- **Abhängigkeiten:** intern: WP-M8c-04, WP-M8c-05.
  - extern: WP-M4-43, WP-M4-41 (Energiekosten je 100 km, Kilometerstände mit Herkunft)
  - extern: WP-M8a-14, WP-M8a-30, WP-M8a-36 (Tankkartenimport, validierte Tank-/Ladeereignisse, Verbrauchswarnung)
- **Entscheidungen:** keine für den Bau; Grenzwerte sind fachlich freizugeben (blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Erfassung und Dublettenprüfung der Tankbelege (M4/M8a).

### WP-M8c-16 – Fahrzeugausfallzeiten nach Ursache und geplanter Betriebszeit

- **Ziel:** Ausfallzeiten werden nach Ursache gegen die geplante Betriebszeit gemessen; Stillstand außerhalb der Betriebszeit erhöht die Dienst-Ausfallzeit nicht.
- **Anforderungen:** `LH-I.6-KPI-029`
- **Liefergegenstände:**
  - Backend: Schnittmengenbildung zwischen Sperr-/Werkstattintervallen und geplanter Betriebszeit des Fahrzeugs; getrennte Ausweisung „tatsächlich nicht verfügbare Zeit“ (gesamt) und „Ausfallzeit im geplanten Dienst“; Ursachenbezug aus Sperrgrund (der Kennzahlkatalog nennt nur „Schaden-/Technikgrund“ und „Werkstattstillstand ursachenbezogen“; vollständiger Ursachenkatalog laut Detailvertrag, Offener Punkt 18); geplant vs. ungeplant getrennt.
  - Datenbank: lesend über Projektion `resource_release`, `vehicle_run`, `staff_shift`, `repair_order`, `defect_case`; schreibend `aggregate_snapshot`.
  - API: `/v1/analytics/metrics` (Dimension Ausfallursache).
  - Oberfläche: UI-19 Ausfallzeiten je Ursache und Fahrzeug.
  - Konfigurationsparameter: `vehicle_unavailability_alert_min` (nur angezeigter Schwellbezug).
  - Kennzahlen: `Fahrzeugverfügbarkeit`, `Ausfallminuten kritisch`, `Werkstattquote`.
- **Detailverträge vorab:** Quelle der „geplanten Betriebszeit“ je Fahrzeug (Offener Punkt 18); Ursachenkatalog; Rechnung über Mitternacht und Sommerzeitwechsel mit absoluten Zeitpunkten.
- **Akzeptanzkriterien:**
  1. Ein Werkstattaufenthalt vollständig außerhalb der Betriebszeit erhöht die geplante Dienst-Ausfallzeit um 0 Minuten, erscheint aber in der tatsächlich nicht verfügbaren Zeit (KPI-029).
  2. Ein Aufenthalt, der die Betriebszeit teilweise überlappt, zählt nur mit dem überlappenden Anteil (KPI-029).
  3. Jede Ausfallminute ist einer Ursache zugeordnet; geplante und ungeplante Stillstände sind getrennt (KPI-029).
  4. Ausfall über den Sommerzeitwechsel wird aus absoluten Zeitpunkten berechnet.
- **Tests:** positiv: drei Werkstattfälle (innerhalb, außerhalb, überlappend) im Referenzdatensatz. negativ: Fahrzeug ohne geplante Betriebszeit → Nenner „nicht berechenbar“; überlappende Sperren werden nicht doppelt gezählt. Beitrag zu E2E-102. negativ (Datentrennung): Fahrzeug bzw. Werkstattauftrag eines fremden Mandanten geht nicht in die Kennzahl ein. konkurrierend (Integrität): Sperre und Wiederfreigabe treffen in vertauschter Reihenfolge ein – die Ausfallzeit wird aus den fachlichen Ereigniszeiten, nicht aus der Empfangsreihenfolge berechnet.
- **Abhängigkeiten:** intern: WP-M8c-04.
  - extern: WP-M3-08, WP-M3-41, WP-M3-51 (Fahrzeugsperre und Wiederfreigabe mit Grund und Zeitpunkten)
  - extern: WP-M7c-35, WP-M7c-15, WP-M7c-36 (Reparaturaufträge, Werkstattaufenthalte, Wartungsplanung)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Alarm bei Fahrzeugunverfügbarkeit (M8d); Werkstattprozess (M7c).

### WP-M8c-17 – Schichtplan-Kennzahlen und offener Personalbedarf

- **Ziel:** Kennzahlen zu unbesetzten Diensten, kurzfristigen Änderungen, tatsächlicher Arbeitszeit und Schichtabdeckung sind verfügbar; der Personalplaner sieht den offenen Bedarf der kommenden sieben Tage.
- **Anforderungen:** `LH-I.6-KPI-030`
- **Liefergegenstände:**
  - Backend: Projektion aus veröffentlichten Dienstplanrevisionen und Zeitereignissen; Zählung unbesetzter Dienste, Änderungen nach Veröffentlichung innerhalb der Kurzfristgrenze, Ist-Arbeitszeit gegen Plan, Abdeckung; Vorschau offener Bedarf für die nächsten sieben Betriebstage in `reporting_timezone`.
  - Datenbank: lesend über Projektion `staff_shift`, `roster_revision`, `shift_offer`, `time_event`, `absence_request`; schreibend `aggregate_snapshot`. Keine personenbezogenen Einzelwerte in der Kennzahlprojektion.
  - API: `/v1/analytics/metrics`.
  - Oberfläche: UI-19 Schichtkennzahlen; UI-12 Kachel „offener Personalbedarf 7 Tage“ mit Zustand „unbesetzter Dienst“.
  - Konfigurationsparameter: `shift_publish_notice_hours`, `reporting_timezone`, `kpi_aggregation_min_n`.
  - Kennzahlen: `Schicht-Abdeckungsquote`.
- **Detailverträge vorab:** Definition „kurzfristige Änderung“ (Offener Punkt 19); Sichtrechte (Personalplaner vs. sonstige Rollen); Aggregationsebene, damit keine Einzelperson erkennbar wird.
- **Akzeptanzkriterien:**
  1. Der Personalplaner sieht für die kommenden sieben Tage je Tag und Standort die Zahl offener Dienste bzw. Stunden; der Wert stimmt mit dem Dienstplan überein (KPI-030).
  2. Unbesetzte Dienste, kurzfristige Änderungen, tatsächliche Arbeitszeit und Abdeckung sind als getrennte Kennzahlen mit Nenner ausgewiesen (KPI-030).
  3. Rollen ohne Personalberechtigung erhalten keine Arbeitszeiten einzelner Personen; Kleingruppen werden unterdrückt.
  4. Schichten über Mitternacht und über den Sommerzeitwechsel werden mit ihrer absoluten Dauer gezählt (E2E-102).
- **Tests:** positiv: Dienstplan mit zwei offenen Diensten in der Sieben-Tage-Vorschau. negativ: unveröffentlichter Entwurf zählt nicht; fremdes Depot/fremder Mandant nicht sichtbar; Finanzrolle erhält keine Einzelzeiten. Beitrag zu E2E-102. konkurrierend (Sicherheit/Datentrennung): Veröffentlichung einer neuen Dienstplanrevision während des Abrufs liefert vollständig alten oder vollständig neuen Stand; Rechteentzug des Personalplaners wirkt beim nächsten Abruf.
- **Abhängigkeiten:** intern: WP-M8c-04.
  - extern: WP-M4-14, WP-M4-05, WP-M4-08, WP-M4-09 (Dienstplan mit Veröffentlichung, offener Bedarf, Zeitereignisse Plan/Ist)
  - extern: WP-M7d-23, WP-M7d-26, WP-M7d-14, WP-M7d-35, WP-M7d-11 (Schichtangebote, Tausch, Abwesenheiten, Zeitkonten, Schichtkalender)
- **Entscheidungen:** D-10 (keine Leistungsrangliste; Auswertung nur aggregiert – Bau unblockiert, Sichtrechte vor Produktivschaltung freigeben).
- **Größe:** M
- **Nicht im Umfang:** Dienstplanung und Besetzungsvorschläge (M7d).

### WP-M8c-18 – Verbundkennzahlen mit abgestimmter Partnerzählung

- **Ziel:** Weitergereichte Anfragen, Partnerannahmen, Anschlussqualität und grenzübergreifende Reisen werden mandantenübergreifend so gezählt, dass beide beteiligten Betreiber dieselbe aggregierte Zahl erhalten und ein Aufgabenträger nur freigegebene Gesamtwerte sieht.
- **Anforderungen:** `LH-I.6-KPI-031`
- **Liefergegenstände:**
  - Backend: freigegebene Reportingprojektion „Verbundauswertung“ (Kap. 02 §3) im Kontext des jeweiligen Verkaufs-/Verbundmandanten, keine dynamische Cross-Tenant-RLS (Kap. 04 §4.6); Zählung über deduplizierte Journey-/Leg-Referenzen (Kap. 01 §1.6); beide Seiten zählen dieselbe Übergabereferenz; Aufgabenträgersicht je genehmigtem Gebiet/Zeitraum mit Mindestgruppengröße; keine personenbezogenen Partnerdaten, keine Partnerkosten oder Margen.
  - Datenbank: lesend über Projektion `journey`, `journey_leg`, `saga_step`, `cooperation_grant`, `partner_contract_version`, `connection_assessment` (Arbeitsname); schreibend `aggregate_snapshot` je Empfängermandant.
  - API: `/v1/analytics/metrics` (Verbundsicht; „keine identifizierbaren Föderationskleingruppen“, Kap. 05 §5.7).
  - Oberfläche: UI-19 Verbundansicht; UI-18 zeigt, welche Kennzahlfreigabe ein Grant umfasst.
  - Ereignisse/Outbox: Verarbeitung der Partnerereignisse über Inbox `(partner_id, event_id)`.
  - Konfigurationsparameter: `sharing_visibility_level`, `kpi_aggregation_min_n`, `interchange_min_time_min`.
  - Kennzahlen: `Technische Partnerablehnungen`, `Partnerannahmequote`, `Partnerübergabe pünktlich`, `Ende-zu-Ende-Reiseabschluss`.
- **Detailverträge vorab:** Definition „erfolgreiche Partnerübergabe“ und „Anschlussqualität“ (Offener Punkt 20); Projektionsschema der Verbundauswertung; Grant-Scope für Kennzahlfreigaben; Verhalten nach Grantwiderruf (Invariante „Grantwiderruf sofort wirksam“, Kap. 04 §4.5).
- **Akzeptanzkriterien:**
  1. Zwei Betreiber erhalten für denselben Zeitraum dieselbe aggregierte Anzahl erfolgreicher Partnerübergaben (KPI-031).
  2. Weitergereichte Anfragen, Partnerannahmen, Anschlussqualität und grenzübergreifende Reisen sind als getrennte Kennzahlen mit Nenner verfügbar (KPI-031).
  3. Ein Aufgabenträger mit drei Firmen und zwei Gebieten erhält korrekte freigegebene Gesamt-KPIs; personenbezogene Partnerdaten sind in Ansicht, API und Export nicht enthalten (KPI-031; E2E-101).
  4. Eine Reise über zwei Segmente zählt als eine Gesamtreise; Segmentzählungen sind ausdrücklich als solche benannt (Kap. 03 §6).
  5. Nach Widerruf eines Grants liefert die Verbundsicht keine neuen Partnerwerte mehr; kleine Zellen bleiben auch über Differenzbildung verborgen.
- **Tests:** positiv: E2E-101 mit Referenzdatensatz; Abgleich der Übergabezahl beider Betreiber. negativ: Betreiber ohne Grant; Aufgabenträger außerhalb des genehmigten Gebiets/Zeitraums; Kleingruppe eines Partners; doppelt zugestelltes Partnerereignis. konkurrierend (Datentrennung): Abruf der Verbundsicht während Grantwiderruf; parallele Projektion in beiden Mandanten ergibt identische Zahl. Besteht E2E-101; Beitrag zu PH-AT-002, PH-AT-021.
- **Abhängigkeiten:** intern: WP-M8c-04, WP-M8c-08, WP-M8c-11.
  - extern: WP-M6a-13, WP-M6a-18, WP-M6a-09, WP-M6a-14, WP-M6b-05 (föderierte Reiseketten, Segmentstatus, Partnerangebote und -bestätigungen, Grants)
  - extern: WP-M6b-04, WP-M6b-09, WP-M6b-13, WP-M6a-17 (Kooperationsvertrag, Sichtbarkeitsstufen, Partnerereignis-Inbox)
- **Entscheidungen:** D-01 (Rollen in der Reisekette; bestimmt, wer Verbundmandant ist – blockiert Produktivschaltung), D-11 (Freigabe aggregierter Statistik für Aufgabenträger und Partner – blockiert Produktivschaltung).
- **Größe:** L
- **Nicht im Umfang:** konsolidierte Kostensicht über Rechtsträger (WP-M8c-14, M8a); Clearing-Gegenberichte (M6c).

### WP-M8c-19 – Soll/Ist/Prognose-Vergleich im Statistikbereich

- **Ziel:** Der Statistikbereich stellt Soll, Ist und Prognose für Nachfrage, Fahrzeugstunden, Betriebskosten und Fahrgastfahrten gegenüber und zeigt im Monatsvergleich Basiswert sowie absolute und prozentuale Abweichung.
- **Anforderungen:** `LH-I.6-KPI-017`
- **Liefergegenstände:**
  - Backend: Vergleichsdienst mit ausdrücklicher Bewertungsart je Reihe (`ACTUAL`, `PLAN`, `FORECAST`; Kap. 03 §1.1); PLAN wird durch ACTUAL nicht überschrieben; FORECAST = Ist bis Stichtag plus Restprognose, nie auf Ist addiert; Abweichung absolut und prozentual, bei Basis 0 nur absolut (Kap. 03 §7 „Budget“).
  - Datenbank: lesend über Projektion `economic_event` (Bewertungsart), `forecast_scenario`, Planwerttabelle (Arbeitsname `metric_plan_value`); schreibend `aggregate_snapshot`.
  - API: `/v1/analytics/metrics` (Vergleichsmodus mit Basis, Vergleichswert, Abweichung absolut/prozentual).
  - Oberfläche: UI-19 „Vergleich durchführen“ für die vier Größen; Zustände „Keine Daten“ und „geringe Qualität“.
  - Kennzahlen: `Anfragen`, `Fahrgastfahrten pro Fahrzeugstunde`, `Budgetabweichung`.
- **Detailverträge vorab:** Herkunft von Soll- und Prognosewerten für Nachfrage, Fahrzeugstunden und Fahrgastfahrten (Offener Punkt 8); Schema der Planwerte; Regel für Basis 0.
- **Akzeptanzkriterien:**
  1. Der Monatsvergleich zeigt je Größe Basiswert, Abweichung absolut und Abweichung prozentual (KPI-017).
  2. Soll, Ist und Prognose sind für Nachfrage, Fahrzeugstunden, Betriebskosten und Fahrgastfahrten getrennt ausgewiesen; Ist und Plan werden nie vermischt (KPI-017).
  3. Bei Basiswert 0 wird nur die absolute Abweichung gezeigt, die prozentuale ist „nicht berechenbar“ (KPI-017).
  4. Fehlt eine Prognosequelle, ist die Prognosereihe `NOT_AVAILABLE` und nicht 0.
- **Tests:** positiv: zwei Monate mit Plan- und Istwerten im Referenzdatensatz. negativ: Basis 0; fehlende Planwerte; Vermischung unterschiedlicher Perioden/Umfänge wird abgelehnt. konkurrierend (Geld): Vergleich während Periodenabschluss liest konsistenten Snapshot. negativ (Sicherheit/Datentrennung): Rolle ohne Finanzrecht sieht die Betriebskostenreihe nicht; Planwerte eines fremden Mandanten sind weder lesbar noch überschreibbar.
- **Abhängigkeiten:** intern: WP-M8c-04, WP-M8c-13.
  - extern: WP-M8a-34, WP-M8a-18 (Budgets/Planwerte der Betriebskosten, Forecast-Bewertung, Periodenabschluss-Snapshot)
- **Entscheidungen:** D-07 (Plan-/Forecast-Sichten; blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Nachfrageprognosemodell und Simulation (M9); die Prognosereihe zeigt bis dahin nur gelieferte Werte.

### WP-M8c-20 – Kohorten-, Saison- und Vorjahresvergleich mit Feiertagskennzeichnung

- **Ziel:** Kennzahlen lassen sich über Kohorten, Saisons und Vorjahre mit konsistenten Zeitfenstern vergleichen; Feiertagseffekte sind aus Regelkalendern kenntlich.
- **Anforderungen:** `LH-I.6-KPI-025`
- **Liefergegenstände:**
  - Backend: Zeitfensterbibliothek (Betriebstag, Woche, Monat, Saison) in `reporting_timezone` mit DST-fester Bildung; Vorjahresvergleich wahlweise datums- oder wochentagsgleich, die gewählte Regel wird ausgewiesen; Kohortenbildung nach Erstereignis im Zeitfenster; Kennzeichnung von Feiertagen und Sondertagen aus dem Regelkalender.
  - Datenbank: `holiday_calendar_entry` (Arbeitsname; Region, Datum, Art, Quelle, Revision); `aggregate_snapshot` mit Fensterdefinition.
  - API: `/v1/analytics/metrics` (Vergleichsparameter, Feiertagsmarkierung je Periode).
  - Oberfläche: UI-19 Vergleichsansicht mit Feiertagsmarkern und Angabe der Fensterregel.
  - Konfigurationsparameter: `reporting_timezone`, `kpi_aggregation_min_n`.
- **Detailverträge vorab:** Kohortendefinition (Offener Punkt 14); Quelle und Pflege des Regelkalenders; Saisongrenzen; Regel für unvollständige Vergleichsperioden.
- **Akzeptanzkriterien:**
  1. Perioden mit Feiertag aus dem Regelkalender sind im Vergleich als solche gekennzeichnet; der Effekt ist durch Ein-/Ausblenden der Feiertage sichtbar (KPI-025).
  2. Kohorten-, Saison- und Vorjahresvergleich verwenden gleich lange, gleich definierte Zeitfenster und weisen die Regel aus (KPI-025).
  3. Fahrten über Mitternacht und über den Sommerzeitwechsel werden genau einem Betriebstag zugeordnet; der 25-Stunden-Tag wird nicht gekürzt (KPI-025; E2E-102).
  4. Kohorten unter der Mindestgruppengröße werden unterdrückt.
  5. Vergleichsperioden mit unterschiedlicher Definitionsversion sind gekennzeichnet (Bezug WP-M8c-04).
- **Tests:** positiv: Vorjahresvergleich mit Feiertag; Fixture 25.10.2026 02:30 doppelt (PH-AT-018). negativ: unvollständige Vergleichsperiode wird gekennzeichnet statt hochgerechnet; unbekannte Kalenderregion. Beitrag zu E2E-102, PH-AT-018. negativ (Datentrennung): Regelkalender und Vergleichsreihen eines fremden Mandanten sind nicht abrufbar.
- **Abhängigkeiten:** intern: WP-M8c-04.
  - extern: WP-M1-18, WP-M1-21 (Zeittypen der Plattform, `reporting_timezone` in der wirksamen Konfiguration)
  - extern: WP-M7d-11 (Schicht- und Betriebskalender (führend für den Kalender-/Schichtteil von E2E-102))
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Saisonprognose (M9); Logkonsistenz über Zeitzonen (M8e).

### WP-M8c-21 – Kundenzufriedenheit nur mit Datenquelle und Stichprobenkennzeichnung

- **Ziel:** Kundenzufriedenheit erscheint nur dann als Kennzahl, wenn Datenquelle und Stichprobe dokumentiert sind; zu kleine oder nicht repräsentative Stichproben sind gekennzeichnet.
- **Anforderungen:** `LH-I.6-KPI-024`
- **Liefergegenstände:**
  - Backend: Kennzahldefinition mit Pflichtangaben Datenquelle, Erhebungszeitraum, Stichprobenumfang, Grundgesamtheit; ohne dokumentierte Quelle wird die Kennzahl nicht ausgewiesen (`NOT_AVAILABLE`); Kennzeichnung „nicht repräsentative Kleinstichprobe“.
  - Datenbank: `metric_definition_revision` (Quellenangabe), `aggregate_snapshot` (Stichprobe, Grundgesamtheit, Kennzeichen).
  - API: `/v1/analytics/metrics`.
  - Oberfläche: UI-19 Zufriedenheitskachel mit Quelle, n und Kennzeichnung; Zustand „Keine Daten“.
  - Konfigurationsparameter: `kpi_aggregation_min_n`.
  - Kennzahlen: `Kundenkontakt-Auflösung` (als dokumentierte Supportquelle daneben ausgewiesen, nicht als Zufriedenheit umgedeutet).
- **Detailverträge vorab:** zulässige Datenquellen (im Pflichtenheft nicht benannt, Offener Punkt 13); Kriterium für „geeignete Stichprobe“.
- **Akzeptanzkriterien:**
  1. Eine Kleinstichprobe wird als nicht repräsentativ gekennzeichnet; der Wert erscheint nicht ohne diese Kennzeichnung (KPI-024).
  2. Ohne dokumentierte Datenquelle wird keine Zufriedenheitskennzahl ausgewiesen (KPI-024).
  3. Stichprobenumfang und Grundgesamtheit sind in Ansicht und Export enthalten (KPI-024).
- **Tests:** positiv: ausreichende Stichprobe mit Quelle. negativ: Stichprobe unter Schwelle; Definition ohne Quelle; Stichprobe unter `kpi_aggregation_min_n` wird unterdrückt. negativ (Datenschutz/Datentrennung): Einzelrückmeldungen und Freitexte sind über die Kennzahl nicht abrufbar; Rückmeldungen eines fremden Mandanten gehen nicht ein.
- **Abhängigkeiten:** intern: WP-M8c-04, WP-M8c-05.
  - extern: WP-M7b-20, WP-M7a-17 (Supportfälle und Rückmeldungen als mögliche Datenquelle)
- **Entscheidungen:** keine für den Bau; Datenquelle und Stichprobenkriterium sind vor Produktivschaltung fachlich festzulegen.
- **Größe:** S
- **Nicht im Umfang:** Durchführung von Fahrgastbefragungen; Erhebungskanal.

### WP-M8c-22 – Vervollständigung des Kennzahlkatalogs auf 48 ausführbare Definitionen

- **Ziel:** Alle 48 Kennzahlen aus `kataloge.csv` sind als freigegebene, ausführbare Definitionsrevision registriert und über die Metrik-API abrufbar; eine CI-Prüfung verhindert Lücken.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Definitionen der noch nicht durch M4 oder die WPs 06–21 gelieferten Kennzahlen, jeweils mit Zähler, Nenner, Ausschlüssen laut Katalogspalte „validierung_oder_ausschluss_original“; Katalogprüfung in CI (jede Katalogzeile ↔ genau eine technische ID mit freigegebener Revision).
  - Datenbank: `metric_definition_revision` (Seed-Migration der 48 Definitionen), lesend über Projektion `support_case`, Belegfreigabe- und Rechnungsfakten, ETA-Gütewerte.
  - API: `/v1/analytics/metrics` (Katalogliste mit 48 Einträgen).
  - Oberfläche: UI-19 Kennzahlkatalog mit Definition, Version und Verfügbarkeit.
  - Kennzahlen (vollständige Liste): `Anfragen`, `Technische Anfragefehler`, `Kapazitätsablehnungen`, `Personalmangelablehnungen`, `Ausstattungsablehnungen`, `Gebietsablehnungen`, `Zeitfensterablehnungen`, `Technische Partnerablehnungen`, `Angebotsquote`, `Buchungskonversion`, `Buchungsstornoquote Fahrgast`, `Buchungsstornoquote Betreiber`, `No-Show-Rate`, `Abholung pünktlich`, `Ankunft pünktlich`, `Mittlere Abholverspätung`, `Mittlere Ankunftsverspätung`, `95%-Abholverspätung`, `Nicht erreichte Anschlüsse`, `Pooling-Nutzungsquote`, `Leerkilometerquote`, `Produktive Kilometer`, `Kapazitätsauslastung distanzgewichtet`, `Sitzplatz-Zeit-Auslastung`, `Fahrgastkilometer`, `Fahrgastfahrten pro Fahrzeugstunde`, `Fahrzeugverfügbarkeit`, `Ausfallminuten kritisch`, `Kosten pro Fahrzeugkilometer`, `Kosten pro Fahrgastfahrt`, `Kosten pro Fahrgastkilometer`, `Zuschuss pro Fahrgastfahrt`, `Deckungsbeitrag`, `Kraftstoff L/100 km`, `Strom kWh/100 km`, `Tankbeleg Vollständigkeit`, `Belegfreigabezeit`, `Offene Rechnungswerte`, `Budgetabweichung`, `Werkstattquote`, `Schicht-Abdeckungsquote`, `Partnerannahmequote`, `Partnerübergabe pünktlich`, `Ende-zu-Ende-Reiseabschluss`, `Kundenkontakt-Auflösung`, `ETA-MAE`, `ETA-Bias`, `ETA-p90-Abdeckung`.
  - Kennzahlen (in diesem WP neu ausführbar): `Fahrgastfahrten pro Fahrzeugstunde`, `Belegfreigabezeit`, `Offene Rechnungswerte` (brutto und netto getrennt), `Budgetabweichung` (bei null Soll absolut), `Kundenkontakt-Auflösung`, `ETA-MAE` (nach Prognosehorizont), `ETA-Bias`, `ETA-p90-Abdeckung` (nach Modell und Region).
- **Detailverträge vorab:** Zuordnungstabelle aus WP-M8c-01; je Kennzahl Eventbindung/SQL, Zeitbasis und Version (laut `kataloge.csv` „noch auszuarbeiten“); Leseports zu M7b, M8a, M8b.
- **Akzeptanzkriterien:**
  1. `/v1/analytics/metrics` listet genau 48 Katalogkennzahlen, jede mit stabiler technischer ID, Definitionsversion, Zähler, Nenner, Ausschlüssen, Zeitbasis und Datenstand (Kap. 01 §1.6).
  2. Die CI-Katalogprüfung schlägt fehl, wenn eine Zeile aus `kataloge.csv` keine freigegebene Definition hat.
  3. Jede Kennzahl liefert auf dem Referenzdatensatz den Orakelwert oder einen begründeten Status (`ZERO_DENOMINATOR`, `PROVISIONAL`, `NOT_AVAILABLE`).
  4. `Offene Rechnungswerte` weist brutto und netto getrennt aus; `Budgetabweichung` zeigt bei null Soll nur den Absolutwert.
  5. ETA-Kennzahlen enthalten keine fahrerbezogene Einzelauswertung.
- **Tests:** positiv: Orakelvergleich aller 48. negativ: fehlende Quelle eines Nachbarmoduls ergibt `NOT_AVAILABLE` statt 0; Nullnenner je Kennzahl. Regression PH-AT-021, E2E-080 bis E2E-084. negativ (Sicherheit/Datentrennung): Finanzkennzahlen (`Offene Rechnungswerte`, `Budgetabweichung`, `Deckungsbeitrag`) ohne Finanzrecht abgelehnt; Katalogabruf zeigt keine Werte fremder Mandanten. konkurrierend (Geld): Abruf von `Offene Rechnungswerte` und `Budgetabweichung` während Belegfreigabe bzw. Periodenabschluss liest einen konsistenten Stand; paralleler Neuaufbau der Projektion verändert keinen ausgelieferten Snapshot.
- **Abhängigkeiten:** intern: WP-M8c-06, WP-M8c-08, WP-M8c-09, WP-M8c-10, WP-M8c-13, WP-M8c-14, WP-M8c-15, WP-M8c-16, WP-M8c-17, WP-M8c-18.
  - extern: WP-M7b-20 (Supportfälle mit SLA-Frist und Ursachentyp)
  - extern: WP-M8a-07, WP-M8a-11, WP-M8a-34 (Eingangsrechnungen, Belegfreigabezeiten, Budgets)
  - extern: WP-M8b-20 (ETA-Gütewerte je Modell, Region und Prognosehorizont)
- **Entscheidungen:** D-05 (verbindlicher Berichtsumfang), D-10 (ETA-Auswertung nur aggregiert) – blockieren nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Güteüberwachung und Rollout-Stopp des ETA-Modells (M8b); Budgetalarm `cost_overrun_alert_pct` (M8a/M8d).

### WP-M8c-23 – Drilldown von Ursache über anonymisierten Fall zum Buchungsdetail

- **Ziel:** Von einer aggregierten Ursache gelangt der Benutzer zur anonymisierten Fallliste und – nur mit Berechtigung – zum Buchungsdetail; fremde und nicht berechtigte Einzelfälle bleiben unsichtbar.
- **Anforderungen:** `LH-I.6-KPI-019`
- **Liefergegenstände:**
  - Backend: dreistufiger Drilldown: Aggregat → anonymisierter Fall (ohne Name, Kontakt, Adresse; grobe Zelle, Zeitfenster, Ursache, Dienst) → Buchungsdetail über Leseport des führenden Moduls mit aktueller Objektberechtigung; Kleingruppenschutz auf Stufe 1; Protokollierung jedes Detailzugriffs.
  - Datenbank: Fallreferenz in den Faktentabellen (opake ID, kein PII); `audit_event` für Detailzugriffe.
  - API: Arbeitsname `GET /v1/analytics/metrics/{metric_id}/cases` (anonymisierte Fälle, `limit`/`cursor`); Buchungsdetail über `/v1/bookings` des führenden Moduls.
  - Oberfläche: UI-19 Drilldown mit Rechtezustand „Detail nicht berechtigt“; Übergang nach UI-07.
- **Detailverträge vorab:** Feldliste des anonymisierten Falls; Rollenmatrix je Stufe; Verhalten bei Föderationsfällen (Betreiber sieht nur sein Segment, Kap. 02 §3).
- **Akzeptanzkriterien:**
  1. Mandantenfremde Einzelfälle sind auf keiner Stufe abrufbar, auch nicht über erratene Fall-IDs (KPI-019).
  2. Ein Benutzer ohne Buchungsberechtigung sieht die anonymisierte Stufe, erhält für das Buchungsdetail aber eine Ablehnung; der Versuch wird protokolliert (KPI-019).
  3. Ein berechtigter Benutzer gelangt von der aggregierten Ursache zum zugehörigen Buchungsdetail; die Zahl der Fälle entspricht dem Aggregat (KPI-019).
  4. Die anonymisierte Stufe enthält keine direkten Identifikatoren; Aggregatzellen unter der Schwelle bieten keinen Drilldown.
  5. Bei föderierten Reisen zeigt der Drilldown nur das eigene Segment.
- **Tests:** positiv: Drilldown für eine Kapazitätsablehnung und eine Verspätung. negativ: fremde Fall-ID (404 ohne Existenzhinweis), fehlende Rolle (403), entzogene Berechtigung zwischen Stufe 2 und 3, Filter, der Autorisierung erweitern würde. konkurrierend (Datentrennung): zwei Sitzungen verschiedener Mandanten rufen parallel dieselbe Kennzahl-ID ab – keine Vermischung. Beitrag zu PH-AT-001, E2E-101.
- **Abhängigkeiten:** intern: WP-M8c-07, WP-M8c-11.
  - extern: WP-M1-07, WP-M1-08, WP-M1-46, WP-M1-02 (Rollen-/Objektberechtigung, Audit, Fehlermodell)
  - extern: WP-M2-23 (Leseport Buchungsdetail mit Berechtigungsprüfung)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Zugriff auf Fahrerprofile (E2E-079, M8b); Supportzugriff mit Zweckbindung (UI-20, M8e).

### WP-M8c-24 – Standarddashboard Auslastung, Leerkilometer, Abholqualität, No-Show

- **Ziel:** Ein vorkonfiguriertes Dashboard zeigt Auslastung, Leerkilometer, Abholqualität und No-Show-Quote; die Zahlen stimmen mit den Detailansichten überein.
- **Anforderungen:** `LH-6.14-ANA-009`
- **Liefergegenstände:**
  - Backend: Dashboarddefinition „Standard“ als Systemvorlage auf Basis der registrierten Kennzahlen; identische Snapshotquelle für Kachel und Detailansicht.
  - Datenbank: `dashboard_definition_revision` (Arbeitsname; Systemvorlage, je Mandant lesbar).
  - API: `/v1/analytics/metrics`; Arbeitsname `GET /v1/analytics/dashboards/standard`.
  - Oberfläche: UI-19 Standarddashboard mit vier Kacheln, Datenstand, Vollständigkeit und Sprung in die Detailansicht; Zustände „Keine Daten“, „geringe Qualität“.
  - Kennzahlen: `Kapazitätsauslastung distanzgewichtet`, `Sitzplatz-Zeit-Auslastung`, `Leerkilometerquote`, `Produktive Kilometer`, `Abholung pünktlich`, `Mittlere Abholverspätung`, `No-Show-Rate`.
- **Detailverträge vorab:** Festlegung, welche Kennzahlen „Abholqualität“ bilden (Offener Punkt 26); Kachelschema.
- **Akzeptanzkriterien:**
  1. Für einen Referenzzeitraum stimmt jeder Dashboardwert mit der Summe bzw. dem Wert der zugehörigen Detailansicht überein (ANA-009).
  2. Das Dashboard zeigt Auslastung, Leerkilometer, Abholqualität und No-Show-Quote mit Nenner und Datenstand (ANA-009).
  3. Das Dashboard ist per Tastatur bedienbar und bietet je Diagramm eine Tabellenalternative.
- **Tests:** positiv: Abgleich Dashboard ↔ Detail ↔ Drilldownzahl. negativ: Zeitraum ohne Daten; Rolle ohne Kennzahlrecht. negativ (Datentrennung): Standarddashboard zeigt nach Mandantenwechsel derselben Sitzung keine Werte oder Cacheeinträge des vorherigen Mandanten.
- **Abhängigkeiten:** intern: WP-M8c-06, WP-M8c-23.
  - extern: WP-M4-59, WP-M4-57, WP-M4-56 (Kennzahlenansicht im Operator-Web, Auslastungs- und Kilometerkennzahlen)
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Live-Dashboard des laufenden Betriebs (M4); frei konfigurierbare Dashboards (WP-M8c-32).

### WP-M8c-25 – Zielwerte und Schwellenmarkierung für Service Level

- **Ziel:** Mandanten pflegen Zielwerte und Schwellen je Kennzahl; eine Verletzung ist in Ansicht und Zeitreihe mit Zeitraum sichtbar markiert.
- **Anforderungen:** `LH-6.14-ANA-010`
- **Liefergegenstände:**
  - Backend: Zielwertrevisionen je Mandant, Kennzahl und Scope (Dienst, Gebiet) mit Richtung (Mindest-/Höchstwert), Bewertungsperiode und Wirksamkeitsintervall; Auswertung je Periode gegen den damals gültigen Zielwert; Markierung „verletzt von–bis“.
  - Datenbank: `metric_target_revision` (Arbeitsname; versioniert, nicht überlappend je Scope); `audit_event` für jede Zielwertänderung.
  - API: Arbeitsname `GET/POST /v1/analytics/targets`; Markierung in der Antwort von `/v1/analytics/metrics`.
  - Oberfläche: UI-19 Zielwertpflege und Markierung in Kachel und Zeitreihe (nicht nur farblich, auch textlich).
  - Konfigurationsparameter: `late_arrival_alert_pct`, `on_time_pickup_tolerance_min`, `on_time_arrival_tolerance_min`.
- **Detailverträge vorab:** Schema des Zielwerts; Abgrenzung zu Konfigurationsparametern des Registers (ob Zielwerte als `config_revision` oder als eigene Revision geführt werden, Offener Punkt 10); Freigabeweg.
- **Akzeptanzkriterien:**
  1. Eine Schwellwertverletzung erzeugt in der Kennzahlenansicht eine sichtbare Markierung mit dem betroffenen Zeitraum (ANA-010).
  2. Zielwerte sind je Mandant getrennt pflegbar; Mandant A sieht und verändert keine Zielwerte von Mandant B (ANA-010).
  3. Eine Zielwertänderung wirkt ab ihrem Wirksamkeitsbeginn; frühere Perioden behalten ihre Bewertung.
  4. Bei „nicht berechenbar“ oder unterdrücktem Wert wird keine Verletzung behauptet.
  5. Jede Zielwertänderung ist mit Akteur, Zeitpunkt, altem und neuem Wert und Grund im Audit nachvollziehbar; eine Rolle ohne Pflegerecht kann Zielwerte lesen, aber nicht ändern.
- **Tests:** positiv: Verletzung in einer von drei Wochen. negativ: Zielwert außerhalb des zulässigen Bereichs, überlappendes Intervall, fremder Mandant, Rolle ohne Pflegerecht. konkurrierend (Datentrennung): zwei gleichzeitige Zielwertänderungen desselben Scopes – eine gewinnt. Zusätzlich negativ: Änderung ohne `If-Match` (428) bzw. mit veralteter Revision (412).
- **Abhängigkeiten:** intern: WP-M8c-04.
  - extern: WP-M1-20, WP-M1-22, WP-M1-07 (Konfigurationsregister mit Wirksamkeitsintervall und Freigabe; Rollenmodell)
- **Entscheidungen:** D-05 (SLA-Zielwerte sind vorläufig; blockiert nur Produktivschaltung).
- **Größe:** S
- **Nicht im Umfang:** Benachrichtigung und Eskalation (WP-M8c-26, M8d).

### WP-M8c-26 – Qualitätswarnungen gegen Zielwerte mit Zuständigkeit

- **Ziel:** Ablehnungsrate, Verspätung, No-Shows und Ausfallursachen werden gegen Zielwerte überwacht; eine Überschreitung erzeugt genau eine Warnung mit Kennzahl, Zeitraum und Zuständigkeit.
- **Anforderungen:** `LH-I.6-KPI-021`
- **Liefergegenstände:**
  - Backend: periodische Auswertung der überwachten Kennzahlen gegen `metric_target_revision`; Übergabe an das Alarmwesen als fachliches Ereignis; Deduplikation je `(policy,event,recipient)` (Kap. 04 §4.4); Zuständigkeit aus Scope (Dienst/Gebiet) und Rollenbindung; keine Warnung bei `PROVISIONAL` unterhalb definierter Vollständigkeit ohne Kennzeichnung.
  - Datenbank: lesend/schreibend über Modul-API des Alarmwesens: `alert_policy_revision`, `business_alert`, `alert_acknowledgement`.
  - Ereignisse/Outbox: Ereignis „Kennzahlschwelle überschritten“ (Arbeitsname) mit Kennzahl-ID, Definitionsversion, Wert, Zielwert, Zeitraum, Scope.
  - Oberfläche: UI-19 Warnliste mit Kennzahl, Zeitraum, Zuständigkeit, Quittierstatus; Verweis in die Arbeitsliste der Leitstelle (UI-07).
  - Konfigurationsparameter: `late_arrival_alert_pct`, `capacity_reject_alert_count`, `vehicle_unavailability_alert_min`, `alert_escalation_delay_min`, `notification_quiet_hours`.
  - Kennzahlen: `Kapazitätsablehnungen`, `Mittlere Abholverspätung`, `Mittlere Ankunftsverspätung`, `No-Show-Rate`, `Ausfallminuten kritisch`.
- **Detailverträge vorab:** Ereignisschema; Regel für Wiederholung/Entwarnung; Herleitung der Zuständigkeit (Offener Punkt 10); Schnittstelle zum Alarmwesen M8d.
- **Akzeptanzkriterien:**
  1. Die Überschreitung eines Schwellwerts erstellt eine Warnung, die Kennzahl, Zeitraum und Zuständigkeit enthält (KPI-021).
  2. Ablehnungsrate, Verspätung, No-Shows und Ausfallursachen sind jeweils überwachbar (KPI-021).
  3. Dieselbe Überschreitung im selben Zeitraum erzeugt je Empfänger genau eine Warnung, auch bei wiederholter Auswertung oder Workerneustart (KPI-021).
  4. Warnungen eines Mandanten erreichen keine Empfänger eines anderen Mandanten.
  5. Häufen sich Kapazitätsablehnungen in einem Gebiet, steht das Kennzahlereignis für den zentralen Alert bereit (Beitrag E2E-099).
- **Tests:** positiv: Überschreitung je überwachter Kennzahl. negativ: Wert unter Schwelle, „nicht berechenbar“, unterdrückte Kleingruppe, fehlende Zuständigkeit (Warnung geht an definierte Ersatzrolle, nicht verloren). konkurrierend (Datentrennung/Einmaligkeit): zwei Worker werten dieselbe Periode parallel aus – eine Warnung. Beitrag zu E2E-099.
- **Abhängigkeiten:** intern: WP-M8c-16, WP-M8c-25.
  - extern: WP-M8d-22, WP-M8d-23, WP-M8d-26 (Alarmrichtlinien, `business_alert` mit Dedupe, Eskalation, Quittierung, Kapazitätsvorschlag)
- **Entscheidungen:** D-05 (Zielwerte und Eskalationswege; blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Automatisierungsregeln, Eskalationskette, Kapazitätsvorschlag (M8d, führend für E2E-099).

### WP-M8c-27 – Berichtsjob: zeitgesteuertes CSV und Reporting-API mit Berechtigungsprüfung

- **Ziel:** Berichte lassen sich als asynchroner Job per API anstoßen und zeitgesteuert als CSV erzeugen; jeder Abruf prüft die Berechtigung, und aus der CSV lässt sich die Webansicht rekonstruieren.
- **Anforderungen:** `LH-I.6-KPI-032`
- **Liefergegenstände:**
  - Backend: Berichtsjob im Modul `analytics` als persistierte Operation mit Lease/Fencing und Wiederaufnahme nach Neustart (Kap. 01 §1.5); Zustände angefordert → läuft → fertig | fehlgeschlagen | abgelaufen; fester Snapshot/As-of je Lauf; Ergebnis im Objektspeicher mit SHA-256, Schema-/Policyversion und Checksumme (Kap. 04 §4.7); Berechtigungsprüfung bei Anlage und bei jedem Abruf; Kleingruppenunterdrückung im Export wie in der Ansicht; Zeitsteuerung über Scheduler.
  - Datenbank: `report_definition`, `report_run` (Arbeitsnamen) bzw. `export_job`; `idempotency_record` für die Jobanlage; `operation`; `audit_event` für Jobanlage und jeden Download.
  - API: `POST /v1/reports/jobs` (Idempotenzschlüssel, Definition, Filter, Format), `GET /v1/reports/jobs/{id}` (Status, Datenstand, Vollständigkeit, kurzlebiger Downloadverweis); `/v1/operations/{operation_id}`; `/v1/analytics/metrics` für synchrones API-Reporting mit `limit`/`cursor`.
  - Oberfläche: UI-19 „Export“ mit Jobstatus und Download; Rechtezustand.
  - Ereignisse/Outbox: „Berichtslauf abgeschlossen/fehlgeschlagen“ (Arbeitsname).
  - Konfigurationsparameter: `daily_report_time`, `reporting_timezone`, `kpi_aggregation_min_n`.
  - Adapter/Worker: Exportworker mit Tenantkontext je Transaktion; mandantengetrennte Objektpfade.
- **Detailverträge vorab:** OpenAPI der beiden Pfade; Zustandsautomat; CSV-Konvention (Trennzeichen, Dezimalformat, Zeitformat mit Offset, Kopfzeilen mit Definition/Version/Datenstand); Maschinenzugang (Service-Account je Zweck, Kap. 01 §1.7; Offener Punkt 21); Aufbewahrung der Ergebnisdateien.
- **Akzeptanzkriterien:**
  1. Aus der CSV einer dokumentierten Beispielkennzahl lassen sich Wert, Zähler und Nenner der Webansicht exakt rekonstruieren (KPI-032).
  2. Ein zeitgesteuerter CSV-Lauf startet zur konfigurierten Zeit in `reporting_timezone` ohne Benutzeraktion (KPI-032).
  3. API-Reporting und Download werden ohne passende Berechtigung abgelehnt; ein Downloadverweis eines anderen Mandanten oder nach Rechteentzug ist wirkungslos (KPI-032).
  4. Wiederholte Jobanlage mit gleichem Idempotenzschlüssel liefert denselben Job; anderer Inhalt mit gleichem Schlüssel → 409 (PH-AT-011).
  5. Nach Workerabsturz wird der Lauf fortgesetzt und erzeugt genau eine Ergebnisdatei.
  6. Die Datei enthält Definitionsversion, Datenstand, Vollständigkeit und Checksumme.
  7. Jobanlage und jeder Download sind mit Akteur, Berichtstyp, Filter/Umfang und Zeitpunkt im Audit protokolliert; abgelaufene Ergebnisdateien werden gemäß Aufbewahrungsregel entfernt und sind danach nicht mehr abrufbar.
  8. Ein fehlgeschlagener Lauf ist mit stabilem Fehlercode sichtbar und hinterlässt keine teilweise abrufbare Datei.
- **Tests:** positiv: Job anlegen, abrufen, CSV gegen Webansicht prüfen; Zeitsteuerung mit simulierter Uhr einschließlich Sommerzeitwechsel. negativ: fehlende Rolle, fremder Mandant, abgelaufener Downloadverweis, unzulässiger Filter, Kleingruppe im Export. konkurrierend (Datentrennung/Einmaligkeit): doppelte Jobanlage parallel; zwei Worker übernehmen denselben Lauf (Fencing); gleichzeitige Läufe zweier Mandanten ohne Pfad-/Cachevermischung. Beitrag zu PH-AT-001 (Export), PH-AT-011.
- **Abhängigkeiten:** intern: WP-M8c-02, WP-M8c-04, WP-M8c-11.
  - extern: WP-M1-15, WP-M1-13, WP-M1-11, WP-M1-07, WP-M1-04 (`operation` mit Lease/Fencing, Idempotenzregister, Objektspeicherzugang, Rollenmodell, Service-Accounts)
  - extern: WP-M8e-15, WP-M1-33 (Aufbewahrungs-/Löschregeln (`retention_rule`) für Exportdateien; bis dahin gekennzeichnete Testfrist)
- **Entscheidungen:** D-11 (Aufbewahrung der Exportdateien, Exportprozess; blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** ERP-/Ledger-Export `/v1/ledger/exports` (M4/M8a); Verteilung an Empfänger (WP-M8c-29); JSON-Format und Feldkatalog (WP-M8c-28).

### WP-M8c-28 – Maschinenlesbare Exportformate CSV/JSON mit Felddefinitionen

- **Ziel:** Berichtsexporte stehen als CSV und JSON mit veröffentlichter Felddefinition bereit; beide Formate lassen sich mit gleichem Summenwert wieder einlesen.
- **Anforderungen:** `LH-6.14-ANA-008`
- **Liefergegenstände:**
  - Backend: JSON-Serialisierung neben CSV aus derselben Snapshotquelle; Feldkatalog je Berichtstyp (Name, Typ, Einheit, Bedeutung, Nullregel, Version); Geldwerte als ganze Minor Units mit Währung, keine Gleitkommadarstellung (Kap. 03 §2.1); Kontrollsumme je Datei.
  - Datenbank: `export_field_definition` (Arbeitsname, versioniert).
  - API: `/v1/reports/jobs` (Formatwahl), `/v1/reports/jobs/{id}`; Arbeitsname `GET /v1/reports/field-definitions`.
  - Oberfläche: UI-19 Formatauswahl und Link zur Felddefinition.
- **Detailverträge vorab:** JSON-Schema je Berichtstyp; CSV-Dialekt; Darstellung von „nicht berechenbar“, „unterdrückt“ und fehlenden Werten in beiden Formaten.
- **Akzeptanzkriterien:**
  1. CSV- und JSON-Datei desselben Laufs ergeben nach Import in ein Prüfwerkzeug denselben Summenwert wie die Webansicht (ANA-008).
  2. Jedes exportierte Feld ist im Feldkatalog mit Typ, Einheit und Bedeutung dokumentiert; die Datei nennt die Katalogversion (ANA-008).
  3. „Nicht berechenbar“ und „unterdrückt“ sind maschinenlesbar von 0 unterscheidbar.
  4. Die JSON-Datei validiert gegen das veröffentlichte Schema.
- **Tests:** positiv: Rundlauf Export → Import → Summenvergleich für Mengen- und Geldbericht. negativ: Feld ohne Katalogeintrag bricht den Build; Sonderzeichen/Trennzeichen im Text; Dezimaltrennzeichen. negativ (Geld/Datentrennung): Geldwert als Gleitkommazahl oder ohne Währung besteht die Schemaprüfung nicht; unterdrückte Kleingruppe ist auch im JSON nicht als Zahl enthalten; Felddefinitionen enthalten keine mandantenspezifischen Inhalte fremder Mandanten. konkurrierend (Geld): CSV- und JSON-Erzeugung desselben Laufs parallel zu neuen Buchungen verwenden denselben Snapshot und ergeben dieselbe Summe.
- **Abhängigkeiten:** intern: WP-M8c-27.
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Fremdformate der Mobilitätsdatenpflicht (WP-M8c-30); PDF-Berichte.

### WP-M8c-29 – Wiederkehrende Berichte mit Verteilung an berechtigte Empfänger

- **Ziel:** Berichte werden nach Plan automatisch erzeugt und nur an Empfänger verteilt, die zum Laufzeitpunkt berechtigt sind.
- **Anforderungen:** `LH-I.6-KPI-022`
- **Liefergegenstände:**
  - Backend: Berichtsplan (täglich, wöchentlich, monatlich) mit Uhrzeit in `reporting_timezone`; je Fälligkeit genau ein Lauf (eindeutiger Schlüssel aus Plan und Fälligkeit); Empfängerprüfung zum Laufzeitpunkt („Empfängerrechte bei Export“, Kap. 05 §5.7); Zustellung als Hinweis mit authentifiziertem Abruf, keine personenbezogenen Daten im Nachrichtentext; Fehlläufe sichtbar und wiederholbar.
  - Datenbank: `report_schedule`, `report_recipient`, `report_run` (Arbeitsnamen); `audit_event` für Anlage/Änderung von Plan und Empfängern.
  - API: Arbeitsname `GET/POST /v1/reports/schedules`; Läufe über `/v1/reports/jobs`, `/v1/reports/jobs/{id}`; Zustellung über `/v1/notifications`.
  - Oberfläche: UI-19 Berichtspläne mit Empfängern, nächstem Lauf, letztem Ergebnis.
  - Ereignisse/Outbox: „Bericht fällig“, „Bericht verteilt“ (Arbeitsnamen).
  - Konfigurationsparameter: `daily_report_time`, `reporting_timezone`, `notification_quiet_hours`.
  - Adapter/Worker: Scheduler mit Lease; Nachholregel nach Ausfall.
- **Detailverträge vorab:** Planschema (im Parameterkatalog existiert nur `daily_report_time`, Offener Punkt 11); Zustellform (Anhang vs. Abruflink); Nachholregel; Verhalten bei doppelter bzw. fehlender lokaler Uhrzeit am Sommerzeitwechsel.
- **Akzeptanzkriterien:**
  1. Ein Wochenbericht wird ohne manuelle Ausführung zur konfigurierten Zeit erzeugt (KPI-022).
  2. Der Bericht wird nur an Empfänger verteilt, die zum Laufzeitpunkt die Berechtigung für Inhalt und Scope besitzen; ein zwischenzeitlich entzogener Empfänger erhält nichts (KPI-022).
  3. Je Fälligkeit entsteht genau ein Lauf und je Empfänger genau eine Zustellung, auch bei Schedulerneustart (KPI-022).
  4. Am Tag des Sommerzeitwechsels läuft der Bericht genau einmal (E2E-102).
  5. Ein fehlgeschlagener Lauf ist mit Grund sichtbar und kann wiederholt werden.
  6. Anlage, Änderung und Deaktivierung eines Berichtsplans sowie jede Änderung der Empfängerliste sind auditiert; nur berechtigte Rollen des eigenen Mandanten können Pläne pflegen.
  7. Die Zustellnachricht enthält keine Kennzahlwerte und keine personenbezogenen Daten, sondern nur den Hinweis mit authentifiziertem Abruf; Ruhezeiten (`notification_quiet_hours`) verschieben die Zustellung, nicht die Erzeugung.
- **Tests:** positiv: Wochenplan mit simulierter Uhr. negativ: Empfänger ohne Recht, Empfänger fremder Mandant, deaktivierter Plan, Zustellfehler. konkurrierend (Datentrennung/Einmaligkeit): zwei Schedulerinstanzen zur selben Fälligkeit – ein Lauf; Rechteentzug während des Laufs. Beitrag zu E2E-102.
- **Abhängigkeiten:** intern: WP-M8c-27, WP-M8c-28.
  - extern: WP-M2-32 (Benachrichtigungsdienst mit Zustellversuchen)
- **Entscheidungen:** D-11 (zulässige Empfänger und Zustellweg; blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Automatisierungsregeln und Alarme (M8d); externe Empfänger außerhalb der Mandantenmitgliedschaft.

### WP-M8c-30 – Mobilitätsdatenexport für PBefG-/Mobilitätsdatenpflichten

- **Ziel:** Für die anwendbaren PBefG-/Mobilitätsdatenpflichten gibt es einen nachvollziehbaren Exportprozess, der die vereinbarten Datenkategorien abdeckt und Erfolg wie Fehler protokolliert.
- **Anforderungen:** `LH-6.14-ANA-013`
- **Liefergegenstände:**
  - Backend: konfigurierbares Exportprofil je Mandant mit Liste der vereinbarten Kategorien, Empfänger, Rhythmus und Format; Abdeckungsprüfung (jede vereinbarte Kategorie ist im Lauf enthalten oder als fehlend begründet); Protokoll je Lauf mit Zeitpunkt, Umfang, Kategorien, Checksumme, Ergebnis, Fehlergrund; Wiederholung ohne Doppelabgabe.
  - Datenbank: `export_job` bzw. `report_run` (Arbeitsname) mit Exportzweck; `audit_event`; lesend über Projektion `service`, `service_area`, `stop_revision`, `vehicle`, Leistungs- und Preisfakten.
  - API: `/v1/reports/jobs`, `/v1/reports/jobs/{id}` (Berichtstyp Mobilitätsdaten).
  - Oberfläche: UI-19 Exportprotokoll mit Erfolg/Fehler je Lauf und Kategorie.
  - Ereignisse/Outbox: „Mobilitätsdatenexport abgeschlossen/fehlgeschlagen“ (Arbeitsname).
  - Adapter/Worker: Übergabeadapter zum Empfänger (Datei-/Schnittstellenübergabe; Zielsystem laut Detailvertrag).
- **Detailverträge vorab:** Katalog der vereinbarten Kategorien, Zielformat und Empfänger (im Pflichtenheft nicht benannt, Offener Punkt 4); Exportprofilschema; Fehler- und Wiederholregeln.
- **Akzeptanzkriterien:**
  1. Der Export enthält alle im Exportprofil vereinbarten Kategorien; eine fehlende Kategorie führt zu einem als fehlerhaft protokollierten Lauf mit Grund (ANA-013).
  2. Jeder Lauf ist mit Erfolg oder Fehler, Zeitpunkt, Umfang und Checksumme protokolliert und für berechtigte Rollen einsehbar (ANA-013).
  3. Ein wiederholter Lauf derselben Periode ist als Wiederholung erkennbar und führt nicht zu einer unbemerkten Doppelabgabe.
  4. Der Export enthält keine personenbezogenen Fahrgastdaten außerhalb der vereinbarten Kategorien.
  5. Anlage und Änderung des Exportprofils sowie jeder Lauf sind auditiert; nur eine dafür berechtigte Rolle kann Profil und Empfänger ändern.
- **Tests:** positiv: vollständiger Lauf mit Fixtureprofil. negativ: Kategorie ohne Daten, Empfänger nicht erreichbar (Lauf bleibt als fehlgeschlagen sichtbar, kein stiller Erfolg), fremder Mandant, Rolle ohne Exportrecht. konkurrierend (Einmaligkeit): paralleler manueller und zeitgesteuerter Lauf derselben Periode.
- **Abhängigkeiten:** intern: WP-M8c-27, WP-M8c-28.
  - extern: WP-M2-03, WP-M2-04, WP-M2-05, WP-M2-07 (Dienste, Bediengebiete, Haltepunktrevisionen, Tarifdaten)
  - extern: WP-M3-03, WP-M4-56 (Fahrzeugstamm und Leistungsdaten)
- **Entscheidungen:** D-11 (Exportprozess und Verantwortliche je Datenfluss; blockiert Produktivschaltung). Die rechtliche Feststellung der anwendbaren Pflichten ist Sache des Auftraggebers und blockiert die Produktivschaltung, nicht den Bau des profilgesteuerten Prozesses.
- **Größe:** M
- **Nicht im Umfang:** rechtliche Bewertung der Pflichten; Fahrplan-/Echtzeitdatenbereitstellung für Partner (M6b).

### WP-M8c-31 – Pseudonymisierter Buchungsexport für Evaluation

- **Ziel:** Buchungen können für Evaluationen pseudonymisiert exportiert werden; die Pseudonyme lassen keine direkten Kontaktinformationen erkennen.
- **Anforderungen:** `LH-6.14-ANA-014`
- **Liefergegenstände:**
  - Backend: Exporttyp „Evaluation“ mit fester Positivliste zulässiger Felder; Pseudonym je Fahrgast als schlüsselabhängiger, exportprofilbezogener Wert (kein Hash direkter Kontaktdaten, kein Konto- oder Partneralias); Vergröberung von Ort und Zeit laut Profil; Ausschluss von Name, Telefon, E-Mail, Adresse, Freitext; Zweck und Empfänger als Pflichtangabe.
  - Datenbank: `export_job` bzw. `report_run` (Arbeitsname) mit Exportzweck; Schlüsselreferenz ohne Secretwert (Kap. 04 §4.4 „keine Secretwerte“); `audit_event`. Kein Lesezugriff des Exports auf `passenger_private`.
  - API: `/v1/reports/jobs`, `/v1/reports/jobs/{id}` (Berichtstyp Evaluation).
  - Oberfläche: UI-19 Exportdialog mit Zweck, Empfänger, Zeitraum; Rechtezustand.
- **Detailverträge vorab:** Pseudonymisierungsverfahren, Schlüsselverwahrung und Wiederverwendbarkeit des Pseudonyms über Exporte hinweg (Offener Punkt 5); Feldpositivliste; Vergröberungsstufen; Löschfrist der Exportdateien.
- **Akzeptanzkriterien:**
  1. Die Exportdatei enthält keine direkten Kontaktinformationen; aus dem Pseudonym lässt sich ohne den getrennt verwahrten Schlüssel kein Kontaktmerkmal ableiten (ANA-014).
  2. Derselbe Fahrgast erhält innerhalb eines Exports dasselbe Pseudonym; Pseudonyme zweier Mandanten sind nicht verknüpfbar (ANA-014).
  3. Felder außerhalb der Positivliste, insbesondere Freitext, erscheinen nicht im Export.
  4. Jeder Export ist mit Zweck, Empfänger, Akteur und Umfang protokolliert; ohne Zweckangabe startet kein Lauf.
  5. Der Evaluationsexport unterdrückt Datensätze bzw. vergröbert Merkmale so, dass die im Profil festgelegte Mindestgruppengröße eingehalten wird; die konkrete Regel ist Detailvertrag (Offener Punkt 5).
- **Tests:** positiv: Export des Referenzdatensatzes; automatische Suche nach Kontaktmustern (Telefon, E-Mail, Name aus Fixture) in der Datei ergibt keinen Treffer. negativ: Rolle ohne Exportrecht, fehlender Zweck, Anforderung eines nicht zugelassenen Feldes, fremder Mandant. konkurrierend (Datentrennung): parallele Exporte zweier Mandanten verwenden getrennte Schlüssel und Pfade.
- **Abhängigkeiten:** intern: WP-M8c-27, WP-M8c-28.
  - extern: WP-M1-36, WP-M1-33, WP-M8e-15, WP-M8e-16, WP-M8e-14 (Schlüsselverwaltung, Aufbewahrungs-/Löschregeln für Exporte, Auskunfts- und Löschjobs, Legal Hold)
- **Entscheidungen:** D-11 (Aufbewahrung, Exportprozess, Verantwortliche; blockiert Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Anonymisierung im statistischen Sinn; Auskunftsexport für Betroffene `/v1/privacy/requests` (M8e).

### WP-M8c-32 – Dashboard-Builder mit Kennzahlen, Filtern, Diagrammen und Warnschwellen

- **Ziel:** Berechtigte Anwender stellen eigene Dashboards aus Kennzahlen, Filtern, Diagrammen und Warnschwellen zusammen und speichern sie.
- **Anforderungen:** `LH-I.6-KPI-033`
- **Liefergegenstände:**
  - Backend: Dashboarddefinition als versioniertes Dokument (Kacheln mit Kennzahl-ID, Filter, Diagrammtyp, optionale Warnschwelle); Validierung gegen Kennzahlkatalog und Filterschema; Ausführung ausschließlich über die Metrik-API mit den Rechten des Betrachters; kein freies SQL (Kap. 04 §4.6).
  - Datenbank: `dashboard_definition_revision` (Arbeitsname; Eigentümer, Sichtbarkeit, Revision).
  - API: Arbeitsname `GET/POST/PUT /v1/analytics/dashboards` mit `If-Match`/Revision (412 bei Konflikt); Daten über `/v1/analytics/metrics`.
  - Oberfläche: UI-19 Builder: Kennzahl wählen, Filter setzen, Diagrammtyp wählen, Warnschwelle setzen, speichern, als Bericht exportieren; Tastaturbedienung und Tabellenalternative.
  - Kennzahlen: Auswahl aus allen 48 registrierten Kennzahlen; Abnahmebeispiel mit `Kosten pro Fahrzeugkilometer` und `Mittlere Abholverspätung`.
- **Detailverträge vorab:** JSON-Schema der Dashboarddefinition; Liste der Diagrammtypen; Rollen für Erstellen/Teilen; Verhältnis der Builder-Warnschwelle zu Zielwerten und Alarmen (Offener Punkt 22).
- **Akzeptanzkriterien:**
  1. Ein Betreiber erstellt einen Fahrzeugkosten- und Verspätungsbericht mit Filter, Diagramm und Warnschwelle, speichert ihn und findet ihn nach erneuter Anmeldung unverändert vor (KPI-033).
  2. Das gespeicherte Dashboard zeigt einem Betrachter nur Werte, für die er selbst berechtigt ist; fehlende Rechte führen zu einer leeren Kachel mit Hinweis, nicht zu fremden Daten (KPI-033).
  3. Eine überschrittene Warnschwelle ist in der Kachel sichtbar markiert (KPI-033).
  4. Gleichzeitiges Speichern zweier Bearbeiter führt zu einem 412-Konflikt ohne stilles Überschreiben.
  5. Unbekannte Kennzahl-ID oder unzulässiger Filter werden beim Speichern abgelehnt.
  6. Anlegen, Ändern, Teilen und Löschen eines Dashboards sind auditiert; ein Dashboard ist ohne ausdrückliche Freigabe nur für seinen Eigentümer sichtbar und nie mandantenübergreifend.
- **Tests:** positiv: Abnahmebeispiel Ende-zu-Ende im Browser. negativ: Benutzer ohne Builderrecht, Kachel mit Finanzkennzahl für Rolle ohne Finanzrecht, fremder Mandant, veraltete Revision. konkurrierend (Datentrennung): paralleles Bearbeiten; geteiltes Dashboard nach Rechteentzug des Betrachters. Zusätzlich negativ: Speichern ohne `If-Match` (428); Versuch, über eine Kachel Live-Daten zu verändern (UI-19-Zustand „Live-Schreibversuch“, serverseitig abgelehnt).
- **Abhängigkeiten:** intern: WP-M8c-22, WP-M8c-24, WP-M8c-25, WP-M8c-27.
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** frei formulierte Abfragen, eigene Kennzahlformeln durch Anwender, Simulation und Forecast (M9).

### WP-M8c-33 – M8c-Gate-Abnahmesuite

- **Ziel:** Der Gate-Anteil der Einheit ist als automatisierte, wiederholbare Suite mit Evidenz nachgewiesen.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: Szenarien unter `tests/acceptance/` für E2E-101 (Mehrgebietsauswertung), E2E-103 (KPI-Definition geändert), den Berichtsanteil von E2E-102 (Mehrregion Zeitzone) sowie Regression PH-AT-021, PH-AT-018 (Berichtsteil), E2E-080 bis E2E-084; Evidenzablage mit Build, Datenstand und Ergebnis für `e2e.csv`.
  - Backend: Prüfliste „jede MUSS-Anforderung der Einheit hat einen grünen Test“ (22 MUSS) und Status der 6 SOLL-Anforderungen.
  - Oberfläche: Barrierefreiheitsprüfung der UI-19-Kernpfade (Vergleich durchführen, Drilldown, Export) mit Tastatur und Screenreader; Zustandsprüfung UI-19 „Keine Daten“, „geringe Qualität“, „Live-Schreibversuch“ sowie Rechtezustand.
  - Backend (Nachverfolgung): Pflege der Zeilen dieser Einheit in `arbeitspakete.csv` mit Testfallreferenz (Umsetzungsplan §6: CI bricht ab, wenn eine umgesetzte MUSS-Zeile keinen Testfall referenziert).
  - Adapter/Worker: Isolationslauf nach PH-AT-001 über alle Analytics-, Berichts- und Exportendpunkte der Einheit (bekannte fremde UUID über Detail-, Listen-, Export- und Downloadpfad; manipulierter Mandantenheader) unter der nicht privilegierten Anwendungsrolle; Integrationslauf der vertragsbasiert gebauten WPs gegen die echten Quellen aus M6/M7, sobald diese vorliegen.
- **Detailverträge vorab:** Testdrehbuch je Referenztest mit Fixture, Schritten und Sollergebnis; Evidenzformat.
- **Akzeptanzkriterien:**
  1. E2E-101: freigegebene Gesamt-KPIs für drei Firmen und zwei Gebiete stimmen mit dem Orakel; personenbezogene Partnerdaten sind verborgen.
  2. E2E-103: nach geänderter Pünktlichkeitsschwelle bleiben alte Kennzahlen mit gültiger Definition reproduzierbar.
  3. E2E-102 (Anteil M8c): Berichte, Betriebstage und Berichtspläne bleiben über Gebietsgrenze, Mitternacht und Sommerzeitwechsel konsistent.
  4. PH-AT-021 besteht unverändert mit dem vollständigen Katalog.
  5. Für alle 22 MUSS-Anforderungen der Einheit ist ein grüner Test mit Evidenz verknüpft.
  6. PH-AT-001 (Anteil Analytics/Export): kein Endpunkt der Einheit liefert Daten oder Existenzhinweise eines fremden Mandanten; PH-AT-002 (Anteil Verbundkennzahlen): die Verbundsicht enthält nur freigegebene Aggregate.
  7. UI-19 zeigt die drei Katalogzustände „Keine Daten“, „geringe Qualität“ und „Live-Schreibversuch“ nachweislich korrekt; ein Schreibversuch auf Live-Daten aus dem Statistikbereich wird serverseitig abgelehnt.
  8. Für jedes vertragsbasiert gebaute WP (WP-M8c-07, -08, -14, -16, -17, -18, -21, -22) ist ausgewiesen, ob die Integrationsabnahme gegen die echte Quelle erfolgt ist oder noch aussteht.
- **Tests:** positiv: vollständiger Suitelauf auf frischer Umgebung. negativ: bewusst verfälschter Sollwert lässt die Suite fehlschlagen (Wirksamkeitsnachweis). Besteht E2E-101, E2E-103; Beitrag zu E2E-102, E2E-070, E2E-099, PH-AT-001, PH-AT-002, PH-AT-015, PH-AT-018, PH-AT-021.
- **Abhängigkeiten:** intern: WP-M8c-18, WP-M8c-20, WP-M8c-22, WP-M8c-26, WP-M8c-29, WP-M8c-30, WP-M8c-31, WP-M8c-32.
  - extern: WP-M6a-34, WP-M6b-34, WP-M6c-32 (echte Föderations-, Anschluss- und Clearingdaten für die Integrationsabnahme (E2E-101))
  - extern: WP-M7b-36, WP-M7c-54, WP-M7d-43 (echte Support-, Werkstatt- und Dienstplandaten für die Integrationsabnahme)
  - extern: WP-M8a-36 (Verbrauchswarnung (E2E-070, führend))
  - extern: WP-M8d-22, WP-M8d-26 (zentraler Alert und Kapazitätsvorschlag (E2E-099, führend))
- **Entscheidungen:** keine für den Bau; die Produktivschaltung der Verbund- und Exportfunktionen hängt an D-01, D-07 und D-11.
- **Größe:** M
- **Nicht im Umfang:** Gate-Tests der Nachbareinheiten (PH-AT-022, E2E-074 bis E2E-079, E2E-105, E2E-106 – M8b; E2E-070 führend M8a; E2E-099 führend M8d); Last- und Restore-Nachweise.

## Offene Punkte

1. **Kein API-Vertrag im Kernartefakt:** `openapi.yaml` enthält keinen Analytics- oder Berichtspfad; Kap. 05 §5.7 nennt nur `/v1/analytics/metrics`, `/v1/reports/jobs`, `/v1/reports/jobs/{id}`. Pfade für Ursachenwechsel, Drilldown, Zielwerte, Berichtspläne, Felddefinitionen und Dashboards sind Arbeitsnamen (betrifft `LH-I.6-KPI-016`, `LH-I.6-KPI-019`, `LH-6.14-ANA-010`, `LH-I.6-KPI-022`, `LH-6.14-ANA-008`, `LH-I.6-KPI-033`).
2. **Kein DDL:** `metric_definition_revision`, `aggregate_snapshot`, `dataset_watermark`, `rejection_reason` stehen nur namentlich in Kap. 04 §4.4. Tabellen für Ursachenzuordnung, Zielwerte, Berichtspläne, Dashboards, Feldkatalog und Regelkalender fehlen dort ganz.
3. **Zuständigkeit `export_job`:** Kap. 04 §4.4 führt `export_job` unter CFG/SEC/INT („Lösch-/Exportzweck“). Unklar, ob Berichtsläufe (`/v1/reports/jobs`) dieselbe Tabelle nutzen oder eine eigene; betrifft `LH-I.6-KPI-032`, `LH-6.14-ANA-013`, `LH-6.14-ANA-014` und die Abgrenzung zu M8e.
4. **`LH-6.14-ANA-013`:** Das Pflichtenheft benennt weder die „anwendbaren“ PBefG-/Mobilitätsdatenpflichten noch die „vereinbarten Kategorien“, das Zielformat, den Empfänger oder den Rhythmus. Geplant ist ein profilgesteuerter Prozess; Inhalte müssen vom Auftraggeber kommen.
5. **`LH-6.14-ANA-014`:** Pseudonymisierungsverfahren, Schlüsselverwahrung, Stabilität des Pseudonyms über mehrere Exporte, zulässige Felder, Vergröberung von Ort/Zeit und Empfängerkreis sind nicht festgelegt. Die Abnahme prüft nur „keine direkten Kontaktinformationen“; Re-Identifikation über Quasi-Identifikatoren (seltene Relation, Uhrzeit) ist nicht geregelt.
6. **`LH-I.6-KPI-015`, `LH-6.14-ANA-007`:** Räumliche Auflösung der Heatmap ist nicht definiert; `travel_attempt.coarse_origin`/`coarse_destination` sind `text` ohne festgelegte Kodierung. Für `kpi_aggregation_min_n` nennt das Pflichtenheft keinen Wert („Datenschutzanforderungen vorrangig“).
7. **`LH-I.6-KPI-016`:** Welche Kennzahlen durch einen manuellen Ursachenwechsel „zulässig“ verändert werden dürfen, ist nicht aufgezählt. Ein Status für nicht klassifizierbare Verspätungen ist nicht vorgesehen (im Plan als Annahme „nicht klassifiziert“ geführt). Die Ursache „Vorfahrt“ ist fachlich nicht erläutert. Berechtigte Rolle und Frist für nachträgliche Wechsel fehlen.
8. **`LH-I.6-KPI-017`:** Herkunft von Soll- und Prognosewerten für Nachfrage, Fahrzeugstunden und Fahrgastfahrten ist nicht spezifiziert; Kap. 03 regelt PLAN/FORECAST nur für Kosten, Prognosemodelle liegen in P3 (M9). Bis dahin kann die Prognosereihe nur gelieferte Werte zeigen.
9. **`LH-I.6-KPI-018`:** Die Abnahme nennt 20 % GPS-Ausfall als Beispiel; eine allgemeine Warnschwelle und die Form der „ausgewiesenen Unsicherheit“ (Bandbreite, reduzierte Stellenzahl, Status) fehlen.
10. **`LH-6.14-ANA-010`, `LH-I.6-KPI-021` und AUT (M8d):** Zielwerte, Schwellenalarme und Qualitätswarnungen überschneiden sich mit den Alarmparametern (`late_arrival_alert_pct`, `capacity_reject_alert_count`, `vehicle_unavailability_alert_min`) und den Tabellen `alert_policy_revision`/`business_alert`. Unklar ist, ob Zielwerte Konfigurationsparameter oder eigene Revisionen sind und woraus sich die „Zuständigkeit“ einer Warnung ergibt.
11. **`LH-I.6-KPI-022`:** Der Parameterkatalog kennt nur `daily_report_time`; Wochentag, Monatsstichtag und Empfängerliste sind keine Katalogparameter. Zustellform (Anhang oder Abruflink) und Nachholregel nach Ausfall sind offen.
12. **`LH-I.6-KPI-023`:** Schwelle für „gefährdet“, maximales Quellenalter für Bewertbarkeit und der Bewertungszeitpunkt sind nicht definiert.
13. **`LH-I.6-KPI-024`:** Das Pflichtenheft benennt keine Datenquelle für Kundenzufriedenheit und kein Kriterium für eine „geeignete Stichprobe“; im Katalog der 48 Kennzahlen gibt es keine Zufriedenheitskennzahl.
14. **`LH-I.6-KPI-025`:** „Cohort“ ist nicht definiert (Kohortenmerkmal, Fenster). Für „Regelkalender“ existieren weder Tabelle noch Parameter noch Datenquelle; Saisongrenzen fehlen.
15. **`LH-I.6-KPI-026`:** „Angebotsabbruchquote“ ist keine der 48 Katalogkennzahlen; Nenner und Sichtfenster für „nicht gebucht“ sind festzulegen. Zu klären ist, ob sie als 49. Definition oder als Variante von `Buchungskonversion` geführt wird.
16. **`LH-I.6-KPI-027`:** „je Vertrag“ ist nicht auf ein Datenobjekt abgebildet: `partner_contract_version` beschreibt Kooperationsverträge, ein Verkehrsvertrag mit einem Aufgabenträger ist nicht modelliert. „Kosten/Fahrgast“ weicht vom Katalognamen `Kosten pro Fahrgastfahrt` ab.
17. **`LH-I.6-KPI-028`:** Plausibilitätsgrenzen je Antriebsart sind nicht beziffert; Hybrid- und weitere Antriebe sowie eine zulässige kommentierte Umrechnung sind nicht beschrieben.
18. **`LH-I.6-KPI-029`:** Quelle der „geplanten Betriebszeit“ je Fahrzeug (Dienstbetriebszeit, `vehicle_run`, Schicht) und der Ursachenkatalog sind nicht festgelegt.
19. **`LH-I.6-KPI-030`:** „Kurzfristige Änderung“ ist nicht definiert; `shift_publish_notice_hours` ist eine naheliegende, aber nicht bestätigte Bezugsgröße. Aggregationsebene zum Schutz einzelner Beschäftigter ist offen.
20. **`LH-I.6-KPI-031`:** „Erfolgreiche Partnerübergabe“ und „Anschlussqualität“ sind nicht definiert. Offen ist, in wessen Mandantenkontext die gemeinsame Zahl entsteht und wie der Aufgabenträgermandant (E2E-101) Freigaben erhält; hängt an D-01.
21. **`LH-I.6-KPI-032`:** Authentisierung maschineller Reporting-Clients (Service-Account je Zweck) und die „dokumentierte Beispielkennzahl“ der Abnahme sind nicht benannt.
22. **`LH-I.6-KPI-033`:** Umfang der Diagrammtypen, Teilen von Dashboards und die Wirkung der Builder-Warnschwelle (nur Anzeige oder Alarm) sind nicht beschrieben.
23. **`LH-6.14-ANA-005`:** Der Katalog verlangt für `Pooling-Nutzungsquote` eine „konfigurierte Mindestüberlappung“; ein entsprechender Parameter fehlt unter den 80 Parametern.
24. **`LH-6.14-ANA-006`:** „Konfigurierte Kostensätze“ (kalkulatorisch) und die Istkostenlogik aus `economic_event` (Kap. 03) sind zwei Berechnungsgrundlagen; das Pflichtenheft sagt nicht, welche für diese Kennzahlen führt. `rate_revision` wird nur namentlich genannt.
25. **`LH-6.14-ANA-004`:** „Wartezeiten“ sind keine der 48 Katalogkennzahlen; Bezugspunkt (Buchung bis Abholung, zugesagte bis tatsächliche Abholung) ist nicht definiert.
26. **`LH-6.14-ANA-009`:** „Abholqualität“ ist kein Katalogname; die zugehörigen Kennzahlen sind festzulegen.
27. **E2E-102:** Der Test umfasst Kalender, Logs und Schichten; M8c liefert nur den Berichts-/Betriebstagsanteil. Die führende Einheit für den Gesamttest ist im Meilensteinplan nicht benannt.
28. **Abgrenzung zu M4:** Welche der 48 Kennzahlen M4 bereits ausführbar liefert, ist dort ein Vorschlag des Plans. WP-M8c-22 unterstellt diese Liste; weicht sie ab, verschiebt sich Umfang zwischen M4 und M8c.
29. **Reporting-Infrastruktur:** Kap. 01 §1.1 sieht „Lesereplikat + inkrementelle Projektionen“ vor; ob das Lesereplikat zu M8 bereitsteht, ist im Meilensteinplan nicht festgehalten. Die WPs sind gegen Projektionen geplant und funktionieren auch ohne Replikat.
30. **Abhängigkeit von M6/M7 trotz „unabhängiger“ Ströme:** Der Meilensteinplan bezeichnet M6, M7 und M8 als voneinander unabhängig. `LH-I.6-KPI-023` (Anschlussdaten, M6a/M6b), `LH-I.6-KPI-031` (Föderation, M6a/M6b), `LH-I.6-KPI-027` (Clearing, M6c), `LH-I.6-KPI-029` (Werkstatt, M7c), `LH-I.6-KPI-030` (Dienstplan, M7d), `LH-I.6-KPI-024` (Support/Rückmeldungen, M7b) und der Gate-Test E2E-101 brauchen jedoch fachlich Daten aus M6/M7. Geplant ist vertragsbasierter Bau mit synthetischen Ereignissen; die Integrationsabnahme und damit das Gate M8 für diese Anforderungen kann erst nach Lieferung der jeweiligen Quelle erfolgen. Die Reihenfolge bzw. ein gemeinsamer Integrationstermin ist im Meilensteinplan festzulegen.
31. **UI-19-Zustand „Live-Schreibversuch“:** Kap. 06 §6.9 nennt den Zustand für „Kennzahlen/Forecast/Simulation“, ohne zu beschreiben, welche Aktion ihn im Kennzahlenteil auslöst (vermutlich Simulation/Forecast, P3/M9). M8c sichert ihn technisch ab (keine Schreibrechte des Moduls `analytics` auf Live-Tabellen) und prüft die Anzeige; das fachliche Auslöseszenario ist zu klären.
32. **Rollen und Rechte der Auswertung:** Das Pflichtenheft legt keine Rechtematrix für Statistik, Finanz- und Personalkennzahlen, Drilldown, Ursachenwechsel, Zielwertpflege, Exporte und Dashboard-Builder fest („berechtigt“ in `LH-I.6-KPI-019`, `LH-I.6-KPI-022`, `LH-I.6-KPI-032`, `LH-I.6-KPI-033` bleibt unbestimmt). Die Matrix entsteht in WP-M8c-01 und ist fachlich freizugeben.
33. **Aufbewahrung von Berichts- und Exportdateien:** Fristen für Ergebnisdateien von `LH-I.6-KPI-032`, `LH-I.6-KPI-022`, `LH-6.14-ANA-013` und `LH-6.14-ANA-014` sind nicht festgelegt (D-11); die Löschmechanik liegt bei M8e, die Tests verwenden eine gekennzeichnete Testfrist.
34. **Leistungsziele für Auswertungen:** Für Antwortzeiten der Metrik-API, Laufzeiten von Berichtsjobs und die zulässige Verzögerung des Datenstands nennt das Pflichtenheft im Bereich dieser Einheit keine Zielwerte (Kap. 08 §8.6 bezeichnet die vorhandenen Zahlen als vorläufig). Lastnachweise sind nicht Teil von M8c.
35. **UNGEDECKT – Zuschussbuchungen (WP-M8c-14):** Für die von `LH-I.6-KPI-027` benötigten Zuschussbuchungen (und Nettoerlöse außerhalb des Clearings) gibt es kein lieferndes WP. WP-M8a-32 liefert nur die Kostenbasis für Deckungsbeitrag und Zuschuss; M8a schließt Fördermittel- und Zuschussabrechnung ausdrücklich aus (dort OP-39). Die Quelle (M2-Tarif, M6c-Clearing oder eigene Zuschussbuchung) ist zu entscheiden und einem WP zuzuordnen.
36. **Reihenfolgekonflikt (Zyklus) WP-M8c-22 ↔ WP-M8b-20:** WP-M8c-22 benötigt die ETA-Gütewerte aus WP-M8b-20; WP-M8b-20 erwartet umgekehrt von M8c die ausführbaren Definitionen `ETA-MAE`, `ETA-Bias`, `ETA-p90-Abdeckung` und das Dashboard UI-19, die in M8c erst mit WP-M8c-22 vollständig vorliegen. Aufzulösen durch Schnitt (z. B. Definitionsrevisionen der drei ETA-Kennzahlen vorab über WP-M8c-04, Gütewerte danach) im Detailvertrag WP-M8c-01/WP-M8b-01.
37. **Zyklusrisiko WP-M8c-04 ↔ M8d:** Die Wirksamkeitsintervalle der Toleranzparameter liefert fachlich am genauesten WP-M8d-15 (versionierte SLA-Ziele im Reporting) bzw. WP-M8d-08 (Zeitauflösung); beide erwarten ihrerseits Leistungen von M8c (KPI-Katalog, Berichtsjobs, Führung E2E-102/E2E-103), die auf WP-M8c-04 aufbauen. Um keinen Zyklus zu erzeugen, hängt WP-M8c-04 nur an WP-M8d-03 und WP-M8d-05; die Integration mit WP-M8d-15 erfolgt im Gate (WP-M8c-33, E2E-103).
