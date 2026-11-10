# M8b – Lernfähige ETA, Modellfreigabe und Governance

- **Meilenstein:** M8 – Finanzen, Controlling, ETA, Governance, Strom „Daten und Steuerung“ (setzt M5 voraus; Nachbareinheiten M8a Belege/Finanzen, M8c Analyse/KPI, M8d Konfiguration/Automatisierung, M8e Plattform/Sicherheit)
- **Phase:** P2 (33 MUSS, 1 SOLL; Phase und Priorität jeder Anforderung unverändert)
- **Anzahl Anforderungen:** 34 (ETA 23, GOV 11)
- **Anzahl WPs:** 30 (26 mit Anforderungen, 4 Enabler)

**Ziel:** Nach M8b lernt die Plattform fahrerbezogene Zeitabweichungen ausschließlich aus qualitätsgesicherten, rechtmäßigen Fahrbeobachtungen und wendet sie nur innerhalb sicherer Grenzen, oberhalb der geprüften Mindestfahrzeit und nach dokumentierter Fachfreigabe je Mandant an; ohne Freigabe läuft ausschließlich das fahrerneutrale Grundmodell aus M4. Jede operative ETA ist auf Modellversion, Datengrundlage, Datenqualität und Puffer rückführbar; Güte- und Driftüberwachung führen zum dokumentierten Fallback. Fahrerprofile sind zweckgebunden, getrennt berechtigt, für Fahrer einsehbar und beanstandbar und erzeugen keine automatische Personalentscheidung. Die Governance-Pakete liefern Verarbeitungsregister, Aufbewahrungsklassen, Auskunfts-/Löschprüfung, Kostenstellen-/Vertragsbeschränkung, CI-Testkatalog, Kaskadenschutz, Regelverantwortung mit Rücknahme und Kleingruppenschutz.

**Gate-Anteil M8b (Umsetzungsplan §4, Gate M8):** PH-AT-022, E2E-074, E2E-075, E2E-076, E2E-077, E2E-078, E2E-079, E2E-105, E2E-106. Beiträge zu E2E-101 (Kleingruppenschutz; führend M8c). PH-AT-022 bezieht zusätzlich `LH-I.3-ETA-001` und `LH-I.3-ETA-002` (M4, fahrerneutrales Grundmodell) ein; diese werden in WP-M8b-30 als Regression mitgeprüft, nicht neu gebaut. Die übrigen Gate-Tests gehören zu M8a (E2E-070), M8c (E2E-101 bis 103) und M8d (E2E-099).

**Lesehinweise:**

- Kurz-IDs in Klammern hinter Akzeptanzkriterien verweisen auf die Anforderung dieser Einheit (ETA-0xx = `LH-I.3-ETA-0xx`, GOV-0xx = `LH-I.11-GOV-0xx`).
- In `openapi.yaml` ausformuliert ist für diesen Bereich nur `/v1/config/revisions/{revision_id}/publish`. Kap. 05 §5.7 nennt `/v1/eta/models/{id}/quality`, `/v1/eta/profiles/{id}/challenge`, `/v1/privacy/requests`, `/v1/config/effective`, `/v1/config/revisions`, `/v1/config/revisions/{id}`, `/validate`, `/impact`, `/v1/analytics/metrics`, `/v1/reports/jobs` nur als Funktionszuschnitt. Alle weiteren Pfade in dieser Datei sind ausdrücklich als **Arbeitsname** gekennzeichnet und werden in WP-M8b-01 festgelegt.
- Im Kern-DDL (`kernschema.sql`) vorhanden: `config_revision`, `audit_event`, `outbox`, `inbox`, `membership`, `cost_object`, `partner_contract_version`, `cooperation_grant`, `vehicle_run`, `staff_shift`, `booking`, `passenger_private`, `expense_document`, `journal`. Nur als Namen in Kap. 04 §4.4: `driving_observation`, `eta_model_revision`, `eta_profile`, `model_approval`, `position_sample`, `retention_rule`, `erasure_job`, `export_job`, `policy_schema`, `adapter_capability`, `metric_definition_revision`, `aggregate_snapshot`, `alert_policy_revision`, `business_alert`, `route_stop`, `route_matrix_cache`. Weitere Tabellen sind **Arbeitsnamen**.
- „Planungsdefault“ kennzeichnet Werte, die das Pflichtenheft selbst als vorläufig bezeichnet. Für alle ETA-Parameter aus `kataloge.csv` nennt das Pflichtenheft **keine** Zahlenwerte; Tests verwenden gekennzeichnete Fixturewerte (WP-M8b-03).
- Die Zielstruktur des Repositorys kennt kein eigenes ETA-Modul. Arbeitsannahme bis WP-M8b-01: Lernen, Profile, Güte in einem Teilmodul `analytics` (Unterpaket eta), operative Anwendung über einen Leseport in `booking-dispatch`; Governance-Funktionen in `identity`, `config` und `platform` (siehe Offene Punkte 1).
- Größen: S bis 1, M bis 3, L bis 6 Personenwochen.

## Übersicht

| WP-ID | Titel | Anforderungen (Anzahl) | Größe | hängt ab von |
|---|---|---:|---|---|
| WP-M8b-01 | Detailverträge und Zustandsautomaten M8b | – (Enabler) | M | WP-M0-31, WP-M0-13, WP-M4-01, WP-M4-61 |
| WP-M8b-02 | Migration der ETA- und Governance-Tabellen mit RLS und Zugriffsklassen | – (Enabler) | M | WP-M8b-01, WP-M1-01, WP-M1-46, WP-M1-33, WP-M0-16, WP-M0-17, WP-M4-61 |
| WP-M8b-03 | Referenzfixtures, Validierungsdatensatz und Beobachtungsgenerator | – (Enabler) | M | WP-M8b-02, WP-M3-34, WP-M3-29, WP-M4-61, WP-M4-62, WP-M5-02, WP-M5-23 |
| WP-M8b-04 | Aktivierungsgate der Personalisierung je Mandant mit Risikobewertung | 2 | M | WP-M8b-02, WP-M8b-03, WP-M1-04, WP-M1-07, WP-M1-46, WP-M1-22, WP-M8d-05 |
| WP-M8b-05 | Menschliche Aufsicht und Widerruf der Modellaktivierung | 1 | S | WP-M8b-04, WP-M4-61, WP-M4-62 |
| WP-M8b-06 | Fahrbeobachtungen mit Trennung von Fahrzeit und Nicht-Fahrzeit | 1 | M | WP-M8b-02, WP-M8b-03, WP-M8b-04, WP-M3-34, WP-M3-29, WP-M3-07, WP-M2-16, WP-M2-10 |
| WP-M8b-07 | Qualitäts- und Rechtmäßigkeitsfilter der Lerndaten | 1 | M | WP-M8b-06, WP-M4-61, WP-M4-62 |
| WP-M8b-08 | Lernlauf: Mindeststichprobe, begrenzter Anpassungsfaktor, Kontextsegmente | 2 | L | WP-M8b-04, WP-M8b-07, WP-M4-61 |
| WP-M8b-09 | Sichere Korrekturgrenzen und geprüfte Mindestfahrzeit | 2 | M | WP-M8b-08, WP-M4-61, WP-M4-62 |
| WP-M8b-10 | Ruhige Fahrweise und wiederholte Geschwindigkeitsverstöße | 2 | M | WP-M8b-07, WP-M8b-09, WP-M2-10, WP-M2-15, WP-M2-17 |
| WP-M8b-11 | Schätzwert-Metadaten, Rückführbarkeit und Datenqualitätsstatus je ETA | 2 | M | WP-M8b-02, WP-M8b-08, WP-M4-61, WP-M3-34, WP-M3-36, WP-M2-23, WP-M2-43 |
| WP-M8b-12 | Unsicherheitsquantile p50/p90 für kritische Prognosen | 1 | M | WP-M8b-03, WP-M8b-11, WP-M4-62 |
| WP-M8b-13 | Vorrang harter Zeitfenster, fixierter Stopps und Arbeitszeitregeln | 2 | M | WP-M8b-09, WP-M2-16, WP-M2-19, WP-M2-23, WP-M3-40, WP-M4-13, WP-M4-17 |
| WP-M8b-14 | Getrennte Speicherung und Zugriffsklassen für Flotten- und Profilwerte | 2 | M | WP-M8b-02, WP-M8b-08, WP-M1-07, WP-M1-31, WP-M1-46 |
| WP-M8b-15 | Keine automatische Personalentscheidung, kein Personenranking | 2 | S | WP-M8b-14, WP-M4-46, WP-M4-53, WP-M4-54, WP-M8c-22, WP-M8c-24, WP-M8c-32 |
| WP-M8b-16 | Fahrertransparenz, Zweckbindung und Löschfristen der Profile | 1 | M | WP-M8b-06, WP-M8b-08, WP-M8b-14, WP-M1-33, WP-M3-24, WP-M3-05 |
| WP-M8b-17 | Beanstandung falscher Messungen durch Fahrer | 1 | M | WP-M8b-08, WP-M8b-16, WP-M3-24, WP-M3-44 |
| WP-M8b-18 | Ablehnen und Überschreiben von ETA-Vorschlägen durch die Leitstelle | 1 | M | WP-M8b-02, WP-M8b-09, WP-M8b-11, WP-M8b-13, WP-M2-16, WP-M2-28, WP-M3-40, WP-M3-36 |
| WP-M8b-19 | Alternativoptionen bei nicht sicher einhaltbaren Aufträgen | 1 | M | WP-M8b-13, WP-M8b-18, WP-M2-15, WP-M2-17, WP-M3-36 |
| WP-M8b-20 | Güteüberwachung je Segment mit dokumentiertem Fallback und Rollout-Stopp | 1 | L | WP-M8b-03, WP-M8b-05, WP-M8b-11, WP-M8b-12, WP-M4-45, WP-M4-46, WP-M4-58, WP-M4-61, WP-M8c-22, WP-M8c-32, WP-M8d-22, WP-M8d-23 |
| WP-M8b-21 | Drift-Überwachung und Aktualisierung der Routinggrundlagen | 1 | M | WP-M8b-03, WP-M8b-20, WP-M2-10, WP-M8d-22, WP-M8d-23 |
| WP-M8b-22 | Verarbeitungsregister für neue Datenverarbeitungen | 1 | M | WP-M8b-02, WP-M1-46, WP-M1-07, WP-M1-32, WP-M4-22 |
| WP-M8b-23 | Aufbewahrungsklassen je Datentyp mit Schutz steuerlicher Nachweise | 1 | M | WP-M8b-02, WP-M8b-22, WP-M1-33, WP-M4-22, WP-M4-23, WP-M4-33 |
| WP-M8b-24 | Auskunfts- und Löschprozess mit Prüfung der Aufbewahrungspflichten | 1 | M | WP-M8b-22, WP-M8b-23, WP-M1-04, WP-M1-46, WP-M1-33, WP-M8e-16, WP-M8e-14 |
| WP-M8b-25 | Zugriffsbeschränkung nach Kostenstelle und Vertragsbeziehung | 1 | M | WP-M8b-02, WP-M1-07, WP-M1-08, WP-M4-29, WP-M6b-04, WP-M6b-05 |
| WP-M8b-26 | Ende-zu-Ende-Testkatalog mit automatischer CI-Protokollierung | 1 | M | WP-M8b-03, WP-M8b-09, WP-M0-12, WP-M5-23, WP-M5-24, WP-M6a-03, WP-M6a-09 |
| WP-M8b-27 | Last- und Fehlergrenzen, Schutz gegen kaskadierende Partnerausfälle | 1 | M | WP-M8b-03, WP-M6a-05, WP-M6a-09, WP-M6a-03, WP-M6b-26, WP-M5-02, WP-M5-10, WP-M1-02 |
| WP-M8b-28 | Verantwortliche, Konfigurationsreview und Rücknahme produktionskritischer Regeln | 1 | M | WP-M8b-02, WP-M1-20, WP-M1-22, WP-M8d-05 |
| WP-M8b-29 | Aggregation und Mindestgruppengröße für personenbezogene Auswertungen | 1 | M | WP-M8b-14, WP-M4-58, WP-M8c-22, WP-M8c-24, WP-M8c-27, WP-M8c-18 |
| WP-M8b-30 | Gate-Abnahme M8b: PH-AT-022 und ETA-Szenarien E2E-074 bis 079, 105, 106 | – (Enabler) | S | WP-M8b-03, WP-M8b-04, WP-M8b-05, WP-M8b-07, WP-M8b-08, WP-M8b-09, WP-M8b-10, WP-M8b-13, WP-M8b-14, WP-M8b-16, WP-M8b-20, WP-M8b-26, WP-M4-61, WP-M4-62, WP-M5-23, WP-M5-31 |

## Arbeitspakete

### WP-M8b-01 – Detailverträge und Zustandsautomaten M8b
- **Ziel:** Für alle 34 Anforderungen liegen vor Codebeginn OpenAPI-Ergänzungen, DDL-Entwürfe, Zustandsautomaten und JSON-Schemata vor und sind in `arbeitspakete.csv` verknüpft (Leitlinie „Vertrag vor Code“, Kap. 08 §8.4).
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - API: OpenAPI-3.1-Dateien unter `api/` für `/v1/eta/models/{id}/quality` und `/v1/eta/profiles/{id}/challenge` (Kap. 05 §5.7) sowie für die im Pflichtenheft nicht benannten Operationen (Arbeitsnamen): Modellaktivierung und Widerruf, Freigabenachweise, eigene Profiltransparenz, ETA-Übersteuerung, Verarbeitungsregister, Regelverantwortung. Ergänzung von `/v1/privacy/requests`, `/v1/config/revisions`, `/v1/config/revisions/{id}`, `/validate`, `/impact` um die Felder dieser Einheit, abgestimmt mit M8d/M8e.
  - Datenbank: DDL-Entwürfe für `driving_observation`, `eta_profile`, `model_approval`, Erweiterung von `eta_model_revision` (aus M4), `retention_rule` (Datentypklassen); Arbeitsnamen `eta_activation`, `eta_override`, `observation_challenge`, `eta_quality_snapshot`, `eta_prediction_record` (Herkunftsnachweis je ausgegebener ETA), `eta_subject_pseudonym` (Zuordnung Fahrer ↔ Pseudonym), `processing_activity`, `rule_ownership`, `legal_hold`, `access_scope` (Kostenstellen-/Vertragsscope).
  - Oberfläche: Zustandskatalog (leer, Fehler, fehlende Rechte, offline) und Barrierefreiheitsanforderungen für die Ansichten ohne Eintrag in Kap. 06 §6.9 (Konfigurationseditor Kap. 06 §6.8, Fahreransicht „Mein Prognoseprofil“) sowie für die Ergänzungen in UI-07, UI-19 und UI-20.
  - Backend: Zustandsautomaten für Personalisierungsaktivierung (Arbeitsstände: beantragt → geprüft → aktiv → widerrufen/gestoppt), Modellrollout (Kandidat → Rollout anteilig → aktiv → zurückgezogen), Beanstandung (gemeldet → in Prüfung → anerkannt/abgelehnt), Privacy-Request (Abstimmung mit M8e).
  - Ereignisse/Outbox: Ereigniskatalog mit JSON-Schema (Arbeitsnamen), u. a. Aktivierung erteilt/widerrufen, Fallback ausgelöst, Modellwarnung, Beanstandung entschieden, ETA übersteuert.
  - Konfigurationsparameter: Schema, Einheit, Ebene und Validator für `eta_personalization_enabled`, `eta_driver_min_samples`, `eta_driver_decay_days`, `eta_driver_upper_adjust_pct`, `eta_driver_lower_adjust_pct`, `eta_model_quantile`, `eta_max_data_age_sec`, `eta_safe_speed_tolerance`, `eta_model_rollout_percent`, `eta_fallback_policy`, `kpi_aggregation_min_n`, `partner_api_timeout_ms`.
  - Kennzahlen: Bindung von `ETA-MAE`, `ETA-Bias`, `ETA-p90-Abdeckung` an Ereignisse und Segmente (mit M8c); `Technische Partnerablehnungen` für WP-M8b-27.
- **Detailverträge vorab:** dieses WP ist die Vertragsarbeit; Vorlage und Reviewrollen laut M0.
- **Akzeptanzkriterien:**
  1. Jede der 34 Anforderungen hat in `arbeitspakete.csv` einen Eintrag in `detailvertrag` und mindestens einen positiven Testfall.
  2. Alle OpenAPI-Dateien sind als 3.1 parsbar, `$ref` auflösbar, `operationId` eindeutig (Kap. 05 §5.8).
  3. Jeder Arbeitsname ist entweder bestätigt oder durch den freigegebenen Namen ersetzt; jeder Offene Punkt dieser Datei ist entschieden, einem Entscheider zugewiesen oder als Fixtureannahme gekennzeichnet.
  4. Die Rollen-/Zugriffsklassenmatrix (Rolle × Datenklasse × Aktion lesen/exportieren) für Profil- und Flottenwerte ist freigegeben.
  5. Für jede neue oder ergänzte Ansicht liegen Leer-, Fehler-, Rechte- und Offlinezustand sowie die Tastatur-/Screenreader-Bedienung als prüfbare Vorgabe vor.
  6. Jede zustandsändernde Operation dieser Einheit hat im Vertrag Idempotenzschlüssel, Problem-Codes und Versionsprüfung (`If-Match`) festgelegt.
- **Tests:** positiv: CI-Vertragsprüfung (Lint, Schema-Beispiele gültig). negativ: Beispiel mit Fahrer-ID in einer Aggregatantwort wird vom Schema abgelehnt; semantische Contract-Tests nach Kap. 05 §5.8 für die neuen Operationen sind spezifiziert (Manipulation tenant_id, unzulässige Fremd-ID, wiederholte identische Anfrage).
- **Abhängigkeiten:**
  - intern: keine.
  - extern: WP-M0-31, WP-M0-13 (Detailvertragsprozess, Vorlage, CI-Vertragsprüfung)
  - extern: WP-M4-01, WP-M4-61 (Vertrag des ETA-Grundmodells und `eta_model_revision`)
- **Entscheidungen:** D-10, D-11 fließen ein; blockieren die Vertragsarbeit nicht (Annahmen als Fixture gekennzeichnet).
- **Größe:** M
- **Nicht im Umfang:** Verträge für Shadow-Testing und Modell-Rollback-Governance (`LH-I.3-ETA-024`, `LH-I.3-ETA-025`, P3/M9), Simulation (`simulation_run`, `/v1/scenarios`, M9).

### WP-M8b-02 – Migration der ETA- und Governance-Tabellen mit RLS und Zugriffsklassen
- **Ziel:** Alle Tabellen dieser Einheit existieren als Flyway-Migration mit Mandantenschlüssel, zusammengesetzten FKs, RLS und getrennten Modulzugängen für personenbezogene Profilwerte.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Datenbank: `driving_observation`, `eta_profile`, `model_approval`, Erweiterung `eta_model_revision`; Arbeitsnamen `eta_activation`, `eta_override`, `observation_challenge`, `eta_quality_snapshot`, `eta_prediction_record`, `eta_subject_pseudonym`, `processing_activity`, `rule_ownership`, `legal_hold`, `access_scope`; Erweiterung `retention_rule`. Primärschlüssel `(tenant_id,id)`, RLS-Policy `tenant_isolation`, Revision und Audit je Tabelle (Kap. 04 §4.4). Eigener Modulzugang mit expliziten Spalten-/Viewrechten für `eta_profile`, `driving_observation` und `eta_subject_pseudonym` (Kap. 04 §4.6); anonymisierte Flottenwerte in getrennter Tabelle/Sicht (`eta_quality_snapshot`). UPDATE/DELETE-Verbot auf `model_approval` und `eta_override` (Invariante „Gegenereignis statt Änderung“, §4.5); Löschung von Profilen nur über kontrollierten Lifecycleprozess.
  - Datenbank: Index `(tenant_id,state,updated_at)` für Arbeitslisten; Pseudonymspalte statt Klarnamen in `driving_observation` (Kap. 01 §1.7).
- **Detailverträge vorab:** DDL aus WP-M8b-01; Pseudonymisierungsverfahren (Offener Punkt 9).
- **Akzeptanzkriterien:**
  1. Migration läuft expand/contract auf leerer und auf M5-Stand-Datenbank mit Testdaten zweier Mandanten.
  2. Ohne Mandantenkontext liefert jede neue Tabelle null Zeilen; Cross-Tenant-FK ist unmöglich.
  3. Die Runtime-Rolle des Abrechnungs-/Analytics-Lesezugangs hat kein SELECT auf `eta_profile`, `driving_observation` und `eta_subject_pseudonym`.
  4. UPDATE/DELETE auf `model_approval` und `eta_override` schlägt fehl.
  5. Die Runtime-Rolle besitzt auf den neuen Tabellen weder TRUNCATE noch DDL-Rechte und ist nicht Eigentümer (Kap. 04 §4.6).
  6. Die Migration ist rückwärtskompatibel: der vorherige Anwendungsstand läuft nach dem Expand-Schritt unverändert weiter.
- **Tests:** positiv: Migration, Einfügen je Tabelle unter Runtime-Rolle. negativ: fehlender Tenantkontext, fremder Mandant, Mutation unveränderlicher Zeilen. konkurrierend (Datentrennung): zwei parallele Transaktionen verschiedener Mandanten auf gepoolter Verbindung sehen nur eigene Zeilen; Abbruch/Fehler einer Transaktion und anschließende Poolwiederverwendung hinterlassen keinen Mandantenkontext (Kap. 04 §4.6).
- **Abhängigkeiten:**
  - intern: WP-M8b-01.
  - extern: WP-M1-01, WP-M1-46, WP-M1-33, WP-M0-16, WP-M0-17 (RLS-Grundgerüst, Tenantkontext, Audit, Migrationspipeline, `retention_rule`/`erasure_job`-Basis)
  - extern: WP-M4-61 (Tabelle `eta_model_revision`)
- **Entscheidungen:** D-02 (Isolationsstufe) – blockiert nicht; D-10 – blockiert nicht.
- **Größe:** M
- **Nicht im Umfang:** Partitionierung von `driving_observation` (erst nach gemessenem Volumen, Kap. 04 §4.7); `simulation_run` (M9).

### WP-M8b-03 – Referenzfixtures, Validierungsdatensatz und Beobachtungsgenerator
- **Ziel:** Reproduzierbare Testdaten für alle ETA-Szenarien: Fahrer mit ruhiger, regelkonformer, zu schneller Fahrweise, GPS-Sprünge, zu wenige Abschnitte, Standzeiten, definierter Validierungsdatensatz mit bekannten p50/p90.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend/Tests: Generator für `position_sample`- und Fahrerereignisfolgen unter `tests/acceptance/`; Fixturekatalog mit Sollwerten je Szenario (E2E-074 bis E2E-079, PH-AT-022); Validierungsdatensatz mit analytisch bekannten Quantilen; Zweitmandant für Datentrennung; Sandbox-Partner mit einstellbarer Latenz (für WP-M8b-27).
  - Konfigurationsparameter: gekennzeichnete Fixturewerte für alle `eta_*`-Parameter und `kpi_aggregation_min_n` (keine Produktionswerte).
- **Detailverträge vorab:** Fixtureformat; Sollwerttabelle; Kennzeichnung „Fixturewert, nicht fachlich freigegeben“.
- **Akzeptanzkriterien:**
  1. Jeder Referenztest des Gate-Anteils hat ein benanntes Fixture mit deterministischem Sollwert.
  2. Der Generator erzeugt bei gleichem Seed identische Daten.
  3. Fixtures werden nur über fachliche Importwege unter der Runtime-Rolle geladen.
- **Tests:** positiv: Fixtureladen in CI. negativ: Fixture ohne Mandantenbezug wird abgelehnt.
- **Abhängigkeiten:**
  - intern: WP-M8b-02.
  - extern: WP-M3-34, WP-M3-29 (`position_sample`, Fahrerereignisse (`/v1/driver/events`))
  - extern: WP-M4-61, WP-M4-62 (ETA-Grundmodell als Referenz)
  - extern: WP-M5-02, WP-M5-23 (Referenzdatengenerator und Abnahmesuite)
- **Entscheidungen:** D-09 (Routing-/Tempolimitdaten) – blockiert nicht, Fixture nutzt Arbeitsstand.
- **Größe:** M
- **Nicht im Umfang:** Produktionsdaten (werden nie in Entwicklung kopiert, Kap. 01 §1.7).

### WP-M8b-04 – Aktivierungsgate der Personalisierung je Mandant mit Risikobewertung
- **Ziel:** Die personenbezogene Anpassung lässt sich je Mandant nur aktivieren, wenn alle Fachfreigaben dokumentiert und bestätigt sind; sonst läuft ausschließlich das nicht personenbezogene Modell.
- **Anforderungen:** `LH-I.3-ETA-013`, `LH-I.11-GOV-003`
- **Liefergegenstände:**
  - Backend: Aggregat Personalisierungsaktivierung mit Zuständen laut WP-M8b-01; Pflichtnachweise in `model_approval`: rechtliche Risikobewertung mit benannten Verantwortlichen, Datenschutzprüfung/gegebenenfalls DSFA, Beteiligung Betriebsrat/Personalvertretung, Stichprobengröße, Qualitäts-/Fairnessprüfung, Löschfrist (Kap. 01 §1.6, D-10). Serverseitiger Guard: operative Profilanwendung nur bei aktivem Gate und `eta_personalization_enabled` = true; Standard aus.
  - Datenbank: `model_approval`, `eta_activation` (Arbeitsname), `config_revision`, `audit_event`.
  - API: Aktivierung/Nachweise (Arbeitsname `/v1/eta/activations`); Veröffentlichung des Parameters über `/v1/config/revisions/{revision_id}/publish` schlägt mit 422 fehl, solange Nachweise fehlen.
  - Oberfläche: Konfigurationseditor (Kap. 06 §6.8): ETA-Personalisierung zeigt fehlende Fachfreigaben und bleibt deaktiviert; Nachweisbearbeitung in UI-20. Zustände: keine Nachweise vorhanden, Nachweis unvollständig, fehlende Rechte, Fehler beim Laden; per Tastatur und Screenreader bedienbar.
  - Ereignisse/Outbox: Aktivierung erteilt/abgelehnt (Arbeitsnamen).
  - Konfigurationsparameter: `eta_personalization_enabled`.
- **Detailverträge vorab:** Zustandsautomat; Liste der Pflichtnachweise und bestätigungsberechtigten Rollen; Problem-Codes; Vieraugenregel Antragsteller ≠ Bestätiger.
- **Akzeptanzkriterien:**
  1. Ohne genehmigte Aktivierung verwendet jede operative ETA ausschließlich das fahrerneutrale Modell; nachweisbar an Modellversion und Fallbackgrund jeder Prognose (ETA-013).
  2. Der Versuch, `eta_personalization_enabled` ohne bestätigte Risikobewertung, gegebenenfalls DSFA und Beteiligung produktiv zu schalten, wird serverseitig abgelehnt und protokolliert (GOV-003).
  3. Jede Aktivierung ist je Mandant mit Antragsteller, Prüfern, Nachweisen, Zeitpunkt und Revision revisionssicher gespeichert; Aktivierung in Mandant A wirkt nicht in Mandant B (ETA-013).
  4. Die Risikobewertung benennt Verantwortliche; ohne benannte Verantwortliche ist keine Bestätigung möglich (GOV-003).
  5. Im Auslieferungszustand und für jeden neu angelegten Mandanten ist `eta_personalization_enabled` aus (Kap. 01 §1.6); das Wiedereinspielen oder Zurücknehmen auf eine ältere Konfigurationsrevision umgeht das Gate nicht (ETA-013).
  6. Wird ein Pflichtnachweis nachträglich zurückgezogen oder läuft er ab, fällt die Aktivierung in den Zustand gestoppt; die Prognose nutzt ab dann das fahrerneutrale Modell (GOV-003).
- **Tests:** positiv: vollständige Nachweise → aktiv. negativ: fehlender Betriebsratsnachweis (E2E-106), fehlende Datenschutzprüfung (E2E-106), Selbstbestätigung, direktes Setzen des Parameters per API, Rücknahme auf alte Revision mit Wert true, Fremdmandant, Rolle ohne Bestätigungsrecht, wiederholte identische Aktivierungsanfrage (idempotent). konkurrierend (Sicherheit): gleichzeitiger Widerruf eines Nachweises und Aktivierung → Aktivierung scheitert. Referenztests: E2E-106, Teil von PH-AT-022.
- **Abhängigkeiten:**
  - intern: WP-M8b-02, WP-M8b-03.
  - extern: WP-M1-04, WP-M1-07, WP-M1-46, WP-M1-22 (Rollen/MFA für privilegierte Aktionen, Audit, `config_revision`-Veröffentlichung)
  - extern: WP-M8d-05 (Konfigurationslebenszyklus mit Validatoren und Freigabe)
- **Entscheidungen:** D-10 – blockiert nicht den Bau, blockiert die Produktivschaltung der Personalisierung.
- **Größe:** M
- **Nicht im Umfang:** Inhaltliche rechtliche Prüfung (Auftraggeber); Widerruf im Betrieb (WP-M8b-05); Modellqualitätsfreigaben und Shadow-Testing (`LH-I.3-ETA-024`, `LH-I.3-ETA-025`, M9).

### WP-M8b-05 – Menschliche Aufsicht und Widerruf der Modellaktivierung
- **Ziel:** Ein berechtigter Verantwortlicher stoppt die Personalisierung sofort; der Fahrbetrieb läuft mit dem fahrerneutralen Modell unterbrechungsfrei weiter.
- **Anforderungen:** `LH-I.11-GOV-004`
- **Liefergegenstände:**
  - Backend: Übergang aktiv → widerrufen/gestoppt mit Pflichtgrund; sofortige Wirkung über serverseitigen Gatecheck (kein Cache-Restzustand); laufende Buchungen, Zusagen und Fahrtenpläne bleiben unverändert gebunden.
  - API: Widerruf (Arbeitsname `/v1/eta/activations/{id}/revoke`), Idempotenzschlüssel.
  - Oberfläche: UI-20 Stopp-Aktion mit Folgenhinweis; Statusanzeige im Konfigurationseditor.
  - Ereignisse/Outbox: Aktivierung widerrufen. Datenbank: `eta_activation`, `audit_event`.
  - Konfigurationsparameter: `eta_fallback_policy`.
- **Detailverträge vorab:** Rollenmatrix Aufsicht; Verhalten laufender Prognosen beim Stopp; Wiederaktivierung nur über erneutes Gate.
- **Akzeptanzkriterien:**
  1. Nach dem Stopp enthält keine neu berechnete ETA mehr eine fahrerbezogene Korrektur; Buchung, Disposition und Fahrer-App arbeiten ohne Unterbrechung oder Fehlerantwort weiter (GOV-004).
  2. Stopp ist rollenbasiert, begründet und im Audit mit Akteur und Zeitpunkt nachvollziehbar; unberechtigte Rolle erhält 403 (GOV-004).
  3. Bereits bestätigte Zusagen werden durch den Stopp nicht verengt oder verändert.
- **Tests:** positiv: Stopp unter laufender Buchungslast. negativ: Stopp durch Disponentenrolle, Fremdmandant. negativ zusätzlich: wiederholter Stopp mit gleichem Idempotenzschlüssel erzeugt genau einen Übergang; Stopp ohne Grund wird abgelehnt. konkurrierend (Sicherheit): Stopp während laufender Reoptimierung – kein Plan mit Profilkorrektur nach Commit des Stopps. Referenztest: PH-AT-022 (Widerruf unterbricht Fahrbetrieb nicht).
- **Abhängigkeiten:**
  - intern: WP-M8b-04.
  - extern: WP-M4-61, WP-M4-62 (fahrerneutrales Grundmodell als Fallback)
- **Entscheidungen:** D-10 – nur Produktivschaltung.
- **Größe:** S
- **Nicht im Umfang:** Automatischer Fallback wegen Modellgüte (WP-M8b-20).

### WP-M8b-06 – Fahrbeobachtungen mit Trennung von Fahrzeit und Nicht-Fahrzeit
- **Ziel:** Aus Positions- und Fahrerereignissen entstehen abgeschlossene Streckenabschnitte, in denen reine Fahrzeit von Wartezeit, Einstiegsdauer, Pause, Umweg und Verspätung wegen später Fahrzeugbereitstellung getrennt ist.
- **Anforderungen:** `LH-I.3-ETA-016`
- **Liefergegenstände:**
  - Adapter/Worker: Segmentierungsjob, der `position_sample` und Fahrerereignisse (Check-in/out, Stoppankunft/-abfahrt, Pause) über Leseports konsumiert und `driving_observation` je Abschnitt mit Phasenanteilen schreibt; idempotent über Inbox (`inbox`).
  - Backend: Pseudonymvergabe je Fahrer über `eta_subject_pseudonym` (Arbeitsname) mit getrenntem Zugriff; Rückauflösung nur für Fahrertransparenz (WP-M8b-16) und Beanstandung (WP-M8b-17); Verfahren laut Offenem Punkt 9.
  - Datenbank: `driving_observation` (Pseudonym, Abschnitt, Zeitanteile je Phase, Mess-/Empfangszeit, Qualität), `eta_subject_pseudonym` (Arbeitsname).
- **Detailverträge vorab:** Definition „abgeschlossener Streckenabschnitt“; Phasenklassifikation und Zuordnungsregeln; Behandlung nicht zuordenbarer Zeit (Offener Punkt 10).
- **Akzeptanzkriterien:**
  1. Eine absichtlich lange Standzeit im Fixture erhöht den gelernten Fahrzeitfaktor des Fahrers nicht; sie erscheint als Warte-/Standanteil (ETA-016).
  2. Einstiegsdauer, Pause, Umweg und verspätete Fahrzeugbereitstellung werden als eigene Anteile ausgewiesen und gehen nicht in die Fahrzeit ein (ETA-016).
  3. Wiederholte Zustellung desselben Ereignisses erzeugt keine doppelte Beobachtung.
  4. `driving_observation` enthält keinen Klarnamen und keine direkte Personal-ID, nur das Pseudonym (Kap. 01 §1.7).
  5. Arbeitsannahme bis zur Klärung von Offenem Punkt 25: ohne aktive Personalisierungsaktivierung des Mandanten werden keine fahrerbezogenen Beobachtungen gespeichert.
  6. Offline erfasste, verspätet eintreffende Ereignisse werden nach Ereigniszeit (nicht Empfangszeit) dem Abschnitt zugeordnet; nicht eindeutig zuordenbare Zeit zählt nicht als Fahrzeit (ETA-016).
- **Tests:** positiv: Abschnitt mit Halt, Pause, Umweg. negativ: fehlendes Abfahrtsereignis → Abschnitt nicht abgeschlossen, keine Beobachtung. Regression: Offline-nachgelieferte Ereignisse in falscher Reihenfolge. konkurrierend (Idempotenz/Datentrennung): zwei Worker verarbeiten dasselbe Ereignis parallel – genau eine Beobachtung; Fremdmandant liest keine Beobachtung.
- **Abhängigkeiten:**
  - intern: WP-M8b-02, WP-M8b-03, WP-M8b-04.
  - extern: WP-M3-34, WP-M3-29, WP-M3-07 (`position_sample`, Fahrerereignisse, Schichtbezug)
  - extern: WP-M2-16, WP-M2-10 (`vehicle_run`, `route_stop`, geplante Route je Abschnitt)
- **Entscheidungen:** D-09 – blockiert nicht; D-10 – Erhebung personenbezogener Beobachtungen erst nach Produktivfreigabe.
- **Größe:** M
- **Nicht im Umfang:** Qualitätsfilter (WP-M8b-07); Lernen (WP-M8b-08).

### WP-M8b-07 – Qualitäts- und Rechtmäßigkeitsfilter der Lerndaten
- **Ziel:** Beobachtungen mit erkannter Geschwindigkeitsüberschreitung, ungeklärter GPS-Qualität oder untypischem Ausnahmeereignis werden nicht als Zeitersparnis gelernt; der Ausschluss ist protokolliert.
- **Anforderungen:** `LH-I.3-ETA-004`
- **Liefergegenstände:**
  - Backend: Filterstufe mit Ausschlussgrund je `driving_observation` (Arbeitscodes: Tempoüberschreitung, GPS-Qualität, Ausnahmeereignis); Tempovergleich gegen Routendaten mit `eta_safe_speed_tolerance`; Erkennung sprunghafter Positionen; Kennzeichnung von Störungs-/Ausnahmeabschnitten.
  - Datenbank: `driving_observation` (Qualitäts-/Rechtmäßigkeitsstatus, Ausschlussgrund).
  - Kennzahlen: Ausschlussquote als Datenqualitätsquote für `ETA-MAE`, `ETA-Bias`.
  - Konfigurationsparameter: `eta_safe_speed_tolerance`, `eta_max_data_age_sec`.
- **Detailverträge vorab:** Quelle der zulässigen Geschwindigkeit je Abschnitt (D-09); Regel GPS-Sprung; Katalog „untypisches Ausnahmeereignis“ (Offener Punkt 11).
- **Akzeptanzkriterien:**
  1. Ein künstlich schneller Datensatz verändert weder Profil noch Prognose zu Lasten der sicheren Fahrzeit; vor und nach dem Lernlauf ist die ETA nicht kürzer (ETA-004).
  2. Sprunghafte Standortmessungen führen zum Ausschluss des Datensatzes und zu einem protokollierten Qualitätsmangel (ETA-004).
  3. `eta_safe_speed_tolerance` wirkt nur als Messgenauigkeit; eine Überschreitung oberhalb der Toleranz ist nie Trainingsdatum (ETA-004).
  4. Ein als untypisches Ausnahmeereignis gekennzeichneter Abschnitt geht nicht als Zeitersparnis in das Profil ein; der Ausschlussgrund ist je Beobachtung gespeichert und auswertbar (ETA-004).
  5. Fehlt die zulässige Geschwindigkeit für einen Abschnitt, gilt die GPS-/Datenqualität als ungeklärt und die Beobachtung wird nicht als Zeitersparnis gelernt (ETA-004).
- **Tests:** positiv: regelkonforme Beobachtung bleibt nutzbar. negativ: zu schnell (E2E-075), GPS-Sprung (E2E-076), Ausnahmeereignis, Abschnitt ohne Tempolimitdaten, Position älter als `eta_max_data_age_sec`. Wiederholung: erneuter Filterlauf über dieselben Daten liefert dasselbe Ergebnis. Referenztests: E2E-075, E2E-076, Teil von PH-AT-022.
- **Abhängigkeiten:**
  - intern: WP-M8b-06.
  - extern: WP-M4-61, WP-M4-62 (regelkonforme Referenzfahrzeit und Tempodaten des Grundmodells)
- **Entscheidungen:** D-09 – Tempolimitquelle; blockiert Produktivschaltung, nicht den Bau.
- **Größe:** M
- **Nicht im Umfang:** Reaktion auf wiederholte Verstöße (WP-M8b-10).

### WP-M8b-08 – Lernlauf: Mindeststichprobe, begrenzter Anpassungsfaktor, Kontextsegmente
- **Ziel:** Ein Lernlauf bildet je Fahrer und Kontextsegment einen Anpassungsfaktor nur bei ausreichend vielen validen, aktuellen Abschnitten; sonst bleibt die globale Referenz aktiv.
- **Anforderungen:** `LH-I.3-ETA-003`, `LH-I.3-ETA-005`
- **Liefergegenstände:**
  - Adapter/Worker: periodischer Lernjob (fortsetzbar, eigene Queue) über gefilterte `driving_observation`; Segmentierung nach Streckenklasse, Tageszeit, Fahrzeugtyp, Stopphäufigkeit und Verkehrskontext; Datenalter über `eta_driver_decay_days`; Ergebnis als neuer Stand in `eta_profile` mit Bezug auf `eta_model_revision`.
  - Backend: Leseport „Profilkorrektur für (Fahrer, Segment)“ mit Ergebnis Faktor oder Fallbackgrund (zu wenige Daten, Gate aus, Segment unbekannt).
  - Oberfläche: Anzeige des begrenzten Anpassungsfaktors bzw. „globale Referenz aktiv“ für berechtigte Rollen (UI-19, Profilklasse laut WP-M8b-14).
  - Konfigurationsparameter: `eta_driver_min_samples`, `eta_driver_decay_days`.
- **Detailverträge vorab:** Schätzverfahren und Reproduzierbarkeit (gleiche Daten → gleicher Stand); Segmenttaxonomie (Offener Punkt 12); Verhalten bei Segment ohne Daten (kein Übertrag aus anderem Segment).
- **Akzeptanzkriterien:**
  1. Für einen Fahrer ohne geeignete Stichprobe bleibt die globale Referenz aktiv und die Prognose ist mit Fallbackgrund gekennzeichnet (ETA-003).
  2. Bei ausreichenden Daten wird ein begrenzter Anpassungsfaktor mit Stichprobenumfang angezeigt (ETA-003).
  3. Beobachtungen aus Stadtverkehr verändern den Faktor für Autobahn- oder Bergstrecken nicht; dort gilt ohne eigene Stichprobe die Referenz (ETA-005).
  4. Faktoren unterscheiden nachweislich Tageszeit, Fahrzeugtyp, Stopphäufigkeit und Verkehrskontext (ETA-005).
  5. Beobachtungen älter als `eta_driver_decay_days` gehen nicht ein.
  6. Ein Lernlauf ist reproduzierbar (gleiche Eingangsdaten und Konfigurationsrevision → gleicher Profilstand) und nach Abbruch ohne doppelte oder halbe Profilstände fortsetzbar.
  7. Jeder Profilstand ist mit Lernlauf, Datenstand, Stichprobenumfang und Konfigurationsrevision im Audit nachvollziehbar.
- **Tests:** positiv: Fahrer mit ausreichender Stichprobe in einem Segment. negativ: zu wenige Abschnitte (E2E-077), nur Stadtdaten bei Autobahnprognose, Gate aus. konkurrierend (Sicherheit): Lernlauf parallel zu Widerruf – kein neuer Profilstand wird operativ wirksam. Referenztests: E2E-077, Teil von PH-AT-022.
- **Abhängigkeiten:**
  - intern: WP-M8b-04, WP-M8b-07.
  - extern: WP-M4-61 (Grundmodell und `eta_model_revision`)
- **Entscheidungen:** D-10 (Mindeststichprobe, Beobachtungsfenster) – Bau mit Fixturewerten möglich, Produktivschaltung blockiert.
- **Größe:** L
- **Nicht im Umfang:** Begrenzung der Faktoren (WP-M8b-09); Quantile (WP-M8b-12); Nachfrageprognose und Model Registry (M9).

### WP-M8b-09 – Sichere Korrekturgrenzen und geprüfte Mindestfahrzeit
- **Ziel:** Jede personalisierte Korrektur wird auf konfigurierte Ober-/Untergrenzen begrenzt; eine verkürzende Korrektur ist nur zulässig, solange die Gesamtzeit oberhalb der geprüften sicheren Mindestfahrzeit bleibt.
- **Anforderungen:** `LH-I.3-ETA-007`, `LH-I.3-ETA-008`
- **Liefergegenstände:**
  - Backend: Begrenzungsstufe hinter dem Profilleseport: Clamp auf `eta_driver_upper_adjust_pct`/`eta_driver_lower_adjust_pct` mit Begründungscode; Vergleich der Gesamtzeit gegen die regelkonforme Mindestfahrzeit des Grundmodells; Verletzung → Referenzzeit. Globale Sicherheitsgrenze hat Vorrang vor Mandantenwerten (Kap. 01 §1.6).
  - API: `/validate` lehnt Parameterwerte ab, die die Untergrenze unter die rechtmäßige Mindestzeit legen würden.
  - Konfigurationsparameter: `eta_driver_upper_adjust_pct`, `eta_driver_lower_adjust_pct`.
- **Detailverträge vorab:** formale Definition „geprüfte sichere Mindestfahrzeit“ (Offener Punkt 4); Vorzeichenkonvention der Grenzen; Begründungscodes.
- **Akzeptanzkriterien:**
  1. Testwerte außerhalb des erlaubten Intervalls werden auf die zulässige Grenze begrenzt; die Begrenzung ist mit Grund in der Prognose ausgewiesen (ETA-007).
  2. Mit einem künstlich extrem schnellen Fahrerprofil unterschreitet keine ausgegebene ETA oder Planzeit die Mindestfahrzeit (ETA-008).
  3. Eine negative Korrektur wird nur angewendet, wenn die geplante Gesamtzeit danach weiterhin oberhalb der Mindestfahrzeit liegt (ETA-008).
  4. Unzulässige Sicherheitswerte blockieren die Veröffentlichung der Konfiguration.
  5. Ober- und Untergrenze sind je Mandant/Modell konfigurierbar (Ebene laut `kataloge.csv`); eine Änderung wirkt nur auf künftige Prognosen und Angebote (ETA-007).
- **Tests:** positiv: Korrektur innerhalb der Grenzen. negativ: Faktor über/unter Grenze, extrem schnelles Profil, direkt in die Datenbank geschriebener Profilwert außerhalb der Grenze. konkurrierend (Sicherheit): Parameteränderung während Prognoseberechnung – Prognose nutzt genau eine Konfigurationsrevision. Referenztest: Teil von PH-AT-022.
- **Abhängigkeiten:**
  - intern: WP-M8b-08.
  - extern: WP-M4-61, WP-M4-62 (regelkonforme Referenz-/Mindestfahrzeit)
- **Entscheidungen:** D-10 (Qualitätsgrenzen) – Produktivwerte; Bau nicht blockiert.
- **Größe:** M
- **Nicht im Umfang:** Zusagefenster und Reoptimierung (WP-M8b-13).

### WP-M8b-10 – Ruhige Fahrweise und wiederholte Geschwindigkeitsverstöße
- **Ziel:** Ruhige, regelkonforme Fahrer erhalten realistisch längere ETAs oder früheren Einsatzbeginn; wiederholt zu schnelle Fahrer erhalten regelkonforme Referenzen und nötigenfalls größere Puffer, nie höhere Kapazitätszusagen.
- **Anforderungen:** `LH-I.3-ETA-009`, `LH-I.3-ETA-010`
- **Liefergegenstände:**
  - Backend: positive Korrektur wird an Disposition als längere Fahrzeit bzw. frühere Abfahrt übergeben; Regel „wiederholte Verstöße“ ersetzt gemessene Schnellfahrzeiten durch Referenz und setzt einen Pufferzuschlag; Kapazitäts- und Zeitfensterberechnung konsumiert nur den begrenzten Wert.
  - Datenbank: `eta_profile` (Kennzeichen Referenzersatz, Pufferzuschlag), `driving_observation`.
- **Detailverträge vorab:** Schwelle und Zeitraum für „wiederholt“ sowie Höhe des Pufferzuschlags (Offener Punkt 5); Schnittstelle zur Einsatzplanung (früherer Einsatzbeginn).
- **Akzeptanzkriterien:**
  1. Ein Testfahrer mit längeren beobachteten Fahrzeiten erhält keinen niedrigeren Zeitansatz als zuvor; sein Zeitansatz ist gleich oder höher, alternativ wird früher geplant (ETA-009).
  2. Ein Fahrer mit wiederholten unzulässig schnellen Fahrten erhält daraus weder höhere Kapazitätszusagen noch engere Zeitfenster; seine Prognose entspricht mindestens der Referenz (ETA-010).
  3. Der Referenzersatz und ein Pufferzuschlag sind in der Prognose als genutzte Restriktion ausgewiesen (ETA-010).
- **Tests:** positiv: ruhiger Fahrer (E2E-074). negativ: Schnellfahrer mit vielen Verstößen (E2E-075) – kein kürzerer Ansatz, keine zusätzliche Fahrt eingeplant; ruhiger Fahrer erhält keine kürzere Abholfrist gegenüber dem Fahrgast. konkurrierend (Kapazität): zwei parallele Angebotsanfragen für denselben Schnellfahrer-Umlauf erhalten keine Zusage, die nur mit verkürzter Fahrzeit erfüllbar wäre. Referenztests: E2E-074, E2E-075.
- **Abhängigkeiten:**
  - intern: WP-M8b-07, WP-M8b-09.
  - extern: WP-M2-10, WP-M2-15, WP-M2-17 (Disposition/Kapazitätsprüfung konsumiert Fahrzeit über Port)
- **Entscheidungen:** D-10 – Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Jede arbeitsrechtliche Folge von Verstößen (ausgeschlossen, WP-M8b-15).

### WP-M8b-11 – Schätzwert-Metadaten, Rückführbarkeit und Datenqualitätsstatus je ETA
- **Ziel:** Jede operative ETA trägt Modellversion, Datengrundlage, Stichprobenumfang, Aktualität, Konfidenzintervall, Datenqualitätsstatus, Berechnungszeit, genutzte Restriktionen und Puffer; veraltete Positionen werden als unsicher markiert.
- **Anforderungen:** `LH-I.3-ETA-006`, `LH-I.3-ETA-022`
- **Liefergegenstände:**
  - Backend: ETA-Ergebnisobjekt (Werttyp) mit Pflichtfeldern; Persistenz der Herkunft je ausgegebener ETA (Bezug `eta_model_revision`, Profilstand, Konfigurationsrevision); Altersprüfung der Position gegen Serverzeit mit `eta_max_data_age_sec` → Status „unsicher“ und Fallback.
  - API: Ergänzung der ETA-Felder in Leitstellen-/Fahrgastprojektionen; `/v1/eta/models/{id}/quality` liefert Modell-/Datenversion.
  - Oberfläche: UI-07 zeigt Datenalter, Qualitätsstatus und „veralteter Standort“; UI-04 trennt Zusage und aktuelle ETA und zeigt Unsicherheit.
  - Datenbank: `eta_profile` (Stichprobenumfang, Aktualitätsdatum, Intervall), `eta_model_revision`, `eta_prediction_record` (Arbeitsname; Herkunftsnachweis je ausgegebener operativer ETA, zugleich Vergleichsbasis für WP-M8b-20), `config_revision`.
  - Konfigurationsparameter: `eta_max_data_age_sec`, `eta_fallback_policy`.
- **Detailverträge vorab:** JSON-Schema des ETA-Objekts; Enum Datenqualitätsstatus und Fallbackgrund; Aufbewahrung der Herkunftsnachweise.
- **Akzeptanzkriterien:**
  1. Für jede operative ETA lassen sich Modellversion und Datengrundlage (Profilstand mit Stichprobenumfang, Aktualitätsdatum, Konfidenzintervall) abrufen (ETA-006).
  2. Eine Vorhersage mit GPS-Position älter als `eta_max_data_age_sec` ist als unsicher markiert und wird nicht als Live-Wert angezeigt oder verwendet (ETA-022).
  3. Jede ETA enthält Berechnungszeit, genutzte Restriktionen und Zeitpuffer (ETA-022).
  4. Die Rückführung funktioniert auch nachträglich für eine bereits ausgegebene ETA (gespeicherter Herkunftsnachweis), nicht nur für die aktuelle Berechnung (ETA-006).
  5. Fahrgastprojektionen (UI-04) enthalten keine fahrerbezogenen Profilangaben wie Stichprobenumfang oder Anpassungsfaktor; diese sind nur für Rollen mit Profilklasse sichtbar (Abgrenzung zu WP-M8b-14).
  6. Der Unsicherheits-/Veraltet-Status ist in UI-07 und UI-04 als Text und nicht nur über Farbe erkennbar.
- **Tests:** positiv: ETA mit vollständigen Metadaten. negativ: veraltete Position; fehlende Modellversion → Antwort wird vom Schema abgelehnt; Fremdmandant ruft Herkunftsnachweis ab → kein Treffer; Gerätezeit in der Zukunft macht eine alte Position nicht „frisch“ (Prüfung gegen Serverzeit). Regression: AT-01 (Status und ETAs widerspruchsfrei), AT-21 (Prognoseausfall).
- **Abhängigkeiten:**
  - intern: WP-M8b-02, WP-M8b-08.
  - extern: WP-M4-61 (Grundmodell liefert Modellversion)
  - extern: WP-M3-34, WP-M3-36 (Positionsfrische und Veraltet-Flag in UI-07)
  - extern: WP-M2-23, WP-M2-43 (ETA-Felder in Buchungs-/Reiseprojektionen)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Quantilberechnung (WP-M8b-12).

### WP-M8b-12 – Unsicherheitsquantile p50/p90 für kritische Prognosen
- **Ziel:** Kritische Prognosen liefern neben dem Mittelwert mindestens ein Unsicherheitsquantil; die Disposition nutzt das konfigurierte Quantil.
- **Anforderungen:** `LH-I.3-ETA-018`
- **Liefergegenstände:**
  - Backend: Quantilschätzung im Modell; eindeutig benannte Felder je Quantil; Auswahl des Dispositionsquantils über `eta_model_quantile` (p50/p75/p85/p90) je Dienst.
  - Oberfläche: UI-19 Validierungsansicht mit p50 und p90, eindeutig beschriftet; UI-07 zeigt das Dispositionsquantil mit Bezeichnung.
  - Kennzahlen: `ETA-p90-Abdeckung`.
  - Konfigurationsparameter: `eta_model_quantile`.
- **Detailverträge vorab:** Definition „kritische Prognose“ (Offener Punkt 6); Verhältnis Konfidenzintervall (ETA-006) zu Quantilen; Validierungsdatensatz.
- **Akzeptanzkriterien:**
  1. Auf dem definierten Validierungsdatensatz zeigt das System p50 und p90 getrennt und richtig beschriftet; die Werte stimmen mit den Sollwerten des Fixtures überein und p90 ≥ p50 (ETA-018).
  2. Eine Änderung von `eta_model_quantile` wirkt nur auf zukünftige Angebote; bestätigte Zusagen bleiben an ihre Version gebunden.
- **Tests:** positiv: Validierungslauf. negativ: ungültiger Quantilwert wird von `/validate` abgelehnt; vertauschte Feldnamen fallen im Contract-Test auf.
- **Abhängigkeiten:**
  - intern: WP-M8b-03, WP-M8b-11.
  - extern: WP-M4-62 (kalibrierter Unsicherheitspuffer des Grundmodells)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Laufende Überwachung der Abdeckung (WP-M8b-20).

### WP-M8b-13 – Vorrang harter Zeitfenster, fixierter Stopps und Arbeitszeitregeln
- **Ziel:** Weder Prognose noch Fahrerprofil können zugesagte Zeitfenster, fixierte Stopps oder Arbeitszeit-/Schichtgrenzen aufweichen.
- **Anforderungen:** `LH-I.3-ETA-017`, `LH-I.3-ETA-027`
- **Liefergegenstände:**
  - Backend: Reoptimierung prüft jede Variante mit der begrenzten ETA gegen gebundene Zusageversionen und fixierte Stopps und verwirft Verletzungen; Einsatzfreigabe und Schicht-/Ruhezeitprüfung verwenden ausschließlich die fahrerneutrale Referenz und sind architektonisch vom Profilleseport getrennt (Architekturtest).
  - API: `/v1/runs/{id}/revisions` – Konfliktantwort mit stabilem Code bei Verletzung harter Zeitgrenzen.
  - Datenbank: gelesen `booking`, `vehicle_run`, `route_stop`, `staff_shift`.
- **Detailverträge vorab:** Liste „harte Zeitgrenzen“; Port zwischen Disposition und ETA; Architekturregel.
- **Akzeptanzkriterien:**
  1. Die Reoptimierung verwirft jede Variante, die eine zugesagte harte Zeitgrenze oder einen fixierten Stopp verletzt; der verworfene Grund ist protokolliert (ETA-017).
  2. Eine Schichtüberschreitung lässt sich durch eine statistisch schnellere Fahrerprognose nicht freischalten; die Einsatzfreigabe liefert mit und ohne Profil dasselbe Ergebnis (ETA-027).
  3. Tätigkeitszeit- und Arbeitszeitregeln werden unabhängig vom ETA-Modell geprüft; ein Architekturtest verhindert die Abhängigkeit (ETA-027).
- **Tests:** positiv: Reoptimierung innerhalb der Fenster. negativ: Variante mit Fensterverletzung; schneller Fahrer am Schichtende (kein Umgehen der Ruhezeit, PH-AT-022). konkurrierend (Kapazität): parallele Reoptimierung und neue Buchung auf demselben Umlauf – keine Zusageverletzung nach Commit. Regression: AT-40.
- **Abhängigkeiten:**
  - intern: WP-M8b-09.
  - extern: WP-M2-16, WP-M2-19, WP-M2-23, WP-M3-40 (Reoptimierung, Plan-CAS, Zusageversionen)
  - extern: WP-M4-13, WP-M4-17 (Schicht-/Ruhezeitprüfung)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Erweiterte Arbeitszeitregeln (M7d).

### WP-M8b-14 – Getrennte Speicherung und Zugriffsklassen für Flotten- und Profilwerte
- **Ziel:** Anonymisierte Flottenlernwerte und personenbezogene Profilwerte sind getrennt gespeichert und unterschiedlich berechtigt; persönliche Geschwindigkeitsprofile sind für normale Rollen weder lesbar noch exportierbar.
- **Anforderungen:** `LH-I.3-ETA-021`, `LH-I.11-GOV-002`
- **Liefergegenstände:**
  - Backend: eigene Datenzugriffsklasse „Fahrerprofil ETA“ ohne Standardzuweisung an HR-, Disponenten-, Abrechnungs- und Tenantadmin-Rollen; getrennte DTOs; Exportsperre in Berichts-/Exportjobs; Protokollierung jedes Zugriffs und jedes verweigerten Versuchs.
  - Datenbank: `eta_profile`, `driving_observation` (Profilklasse) getrennt von `eta_quality_snapshot` (Arbeitsname, Flottenwerte); `membership`, `audit_event`.
  - API: `/v1/eta/models/{id}/quality` liefert nur Aggregatgüte; Profilendpunkte verlangen die Profilklasse; `/v1/reports/jobs` lehnt Profilfelder ohne Klasse ab.
  - Oberfläche: UI-19 zeigt betriebliche Prognosequalität; UI-20 zeigt verweigerte Zugriffe.
- **Detailverträge vorab:** Rollen-/Klassenmatrix; Scope-Namen; Liste der als Profil klassifizierten Felder.
- **Akzeptanzkriterien:**
  1. Die Abrechnungsrolle sieht die betriebliche Prognosequalität, erhält aber auf persönliche Fahrkurven 403; der Versuch ist protokolliert (ETA-021).
  2. Normale HR- und Disponentenrollen können persönliche Geschwindigkeitsprofile weder über API noch über Berichts-/Exportjobs exportieren (GOV-002).
  3. Die Profilklasse ist in keiner Standardrolle enthalten und wird nur ausdrücklich und begründet vergeben; Vergabe und Entzug sind auditiert (GOV-002). Ob die Vergabe zu befristen ist, legt der Detailvertrag fest.
  4. Flottenwerte enthalten keine Fahrer-ID oder rückführbare Pseudonyme (ETA-021).
  5. Die Trennung gilt auch für Nebenwege: Logs, Fehlermeldungen, Suchindex, Caches und Supportzugriffe enthalten keine persönlichen Fahrkurven (ETA-021).
- **Tests:** positiv: berechtigte Rolle liest Profil. negativ: Abrechnung (E2E-079), HR, Disponent, Tenantadmin, Fremdmandant; Export über Berichtsjob; direkter Objektzugriff per ID. konkurrierend (Datentrennung): Entzug der Profilklasse während laufender Sitzung wirkt beim nächsten Request. Referenztests: E2E-079, Teil von PH-AT-022.
- **Abhängigkeiten:**
  - intern: WP-M8b-02, WP-M8b-08.
  - extern: WP-M1-07, WP-M1-31, WP-M1-46 (Rollen/Scopes, feldbezogene Rechte, Audit)
- **Entscheidungen:** D-10 (Zugriffs-/Löschklasse) – Bau nicht blockiert.
- **Größe:** M
- **Nicht im Umfang:** Mindestgruppengrößen in Dashboards (WP-M8b-29).

### WP-M8b-15 – Keine automatische Personalentscheidung, kein Personenranking
- **Ziel:** Fahrerindividuelle Schätzungen erzeugen keine automatische Lohn-, Disziplinar-, Kündigungs- oder Beförderungsentscheidung; Pünktlichkeits-KPIs erscheinen aggregiert, personalisierte Leaderboards sind standardmäßig deaktiviert.
- **Anforderungen:** `LH-I.3-ETA-011`, `LH-I.3-ETA-026`
- **Liefergegenstände:**
  - Backend: Architekturtest – Module `staff-time` (Lohn/Personal) und Exporte haben keinen Zugriff auf `eta_profile`; kein Ereignis und kein Endpunkt liefert eine Bewertungsentscheidung je Person; KPI-Abfragen bieten die Dimension „Fahrer“ für Pünktlichkeit standardmäßig nicht an.
  - API: `/v1/analytics/metrics` – Dimension Person für Pünktlichkeitskennzahlen gesperrt (stabiler Problem-Code); Vertragstest über alle OpenAPI-Dateien: kein Feld mit automatischer Leistungsbewertung.
  - Oberfläche: UI-19 zeigt aggregierte Verkehrsqualität; keine Ranglistenansicht.
- **Detailverträge vorab:** Liste der gesperrten Dimensionen/Felder; Beschreibung des getrennten menschlichen Prüfprozesses (organisatorisch, außerhalb der Software); Offener Punkt 7.
- **Akzeptanzkriterien:**
  1. Keine API – insbesondere keine Schnittstelle für Leistungs-/Personalentscheidungen – erhält eine automatische Bewertungsentscheidung; nachgewiesen durch Contract- und Architekturtest; der menschliche Prüfprozess ist separat dokumentiert (ETA-011).
  2. Das Dashboard zeigt aggregierte Verkehrsqualität; personalisierte Leaderboards sind im Auslieferungszustand deaktiviert und per Konfiguration ohne rechtlich genehmigte Zweckprüfung nicht einschaltbar (ETA-026).
- **Tests:** positiv: aggregierte Pünktlichkeit je Dienst. negativ: Abfrage mit Dimension Fahrer; Lohnexport fordert Profilfeld an; Versuch, Ranking per Konfiguration zu aktivieren.
- **Abhängigkeiten:**
  - intern: WP-M8b-14.
  - extern: WP-M4-46, WP-M4-53, WP-M4-54 (KPI-Kern und Pünktlichkeitskennzahlen)
  - extern: WP-M8c-22, WP-M8c-24, WP-M8c-32 (Dashboards und Metrik-API des vollständigen KPI-Katalogs)
- **Entscheidungen:** D-10 (Leistungsranglisten außerhalb des spezifizierten Zwecks) – keine Blockade.
- **Größe:** S
- **Nicht im Umfang:** Eine Ranglistenfunktion selbst (nicht spezifiziert).

### WP-M8b-16 – Fahrertransparenz, Zweckbindung und Löschfristen der Profile
- **Ziel:** Fahrer sehen Zweck, gespeicherte Datentypen, Fristen und die zugelassene menschliche Korrektur ihres Profils; die Aufbewahrungskonfiguration für Fahrerprognosen wird technisch angewendet.
- **Anforderungen:** `LH-I.3-ETA-012`
- **Liefergegenstände:**
  - Backend: Transparenzauskunft je Fahrer (Zweck, Datentypen, Aufbewahrungsfrist, Modellstand, Beanstandungsweg); Löschlauf für `eta_profile` und `driving_observation` gemäß `retention_rule` über `erasure_job`; Zweckbindung als Zugriffsvoraussetzung (operativer Prognosezweck).
  - API: eigene Profilauskunft (Arbeitsname `/v1/me/eta-profile`).
  - Oberfläche: Fahrer-App (`mobile/driver`) Ansicht „Mein Prognoseprofil“; kein UI-Katalogeintrag vorhanden (Offener Punkt 3). Zustände: kein Profil vorhanden/Personalisierung nicht aktiv, Fehler beim Laden, offline (letzter Stand mit Zeitangabe, keine Erfassungsdialoge während der Fahrt, Kap. 06); barrierefrei bedienbar.
  - Konfigurationsparameter: `eta_driver_decay_days`.
- **Detailverträge vorab:** Inhalt der Auskunft; Datentypklasse „Fahrerprognosen“ in `retention_rule`; Verhältnis `eta_driver_decay_days` (Lernfenster) zu Löschfrist (Offener Punkt 13).
- **Akzeptanzkriterien:**
  1. Ein Fahrer kann Zweck und relevante gespeicherte Datentypen seines Profils einsehen, auch wenn die Personalisierung nicht aktiv ist (ETA-012).
  2. Nach Ablauf der konfigurierten Aufbewahrung sind Profil- und Beobachtungsdaten des Fixtures gelöscht; der Löschlauf ist protokolliert (ETA-012).
  3. Die Auskunft nennt Fristen und die zugelassene menschliche Korrektur (E2E-105).
  4. Ein Fahrer sieht ausschließlich das eigene Profil.
  5. Der Zugriff auf Profilwerte ist nur für den operativen Prognosezweck möglich; jeder Zugriff trägt Zweck und Akteur im Audit (ETA-012).
  6. Objekte unter Legal Hold werden vom Löschlauf ausgenommen und als gesperrt ausgewiesen (Abgleich mit WP-M8b-23).
- **Tests:** positiv: Auskunft (E2E-105), Löschlauf nach Fristablauf. negativ: fremdes Profil, Fremdmandant, Zugriff ohne Zweck. konkurrierend (Datentrennung): Löschlauf parallel zum Lernlauf – gelöschte Beobachtungen erscheinen in keinem neuen Profilstand. Referenztest: E2E-105.
- **Abhängigkeiten:**
  - intern: WP-M8b-06, WP-M8b-08, WP-M8b-14.
  - extern: WP-M1-33 (`retention_rule`, `erasure_job`, Löschjob-Grundgerüst)
  - extern: WP-M3-24, WP-M3-05 (Fahrer-App-Grundgerüst und Fahreridentität)
- **Entscheidungen:** D-10, D-11 (Löschfrist) – Produktivschaltung blockiert, Bau nicht.
- **Größe:** M
- **Nicht im Umfang:** Allgemeine Betroffenenauskunft (WP-M8b-24, M8e).

### WP-M8b-17 – Beanstandung falscher Messungen durch Fahrer
- **Ziel:** Fahrer melden sachlich falsche Messungen; nach menschlicher Prüfung wird der Messpunkt aus dem nächsten Lernlauf entfernt.
- **Anforderungen:** `LH-I.3-ETA-015`
- **Liefergegenstände:**
  - Backend: Aggregat Beanstandung (gemeldet → in Prüfung → anerkannt/abgelehnt) mit Nachweisanhang; anerkannte Beanstandung markiert `driving_observation` als ausgeschlossen (Gegenkennzeichen, keine Löschung der Historie); nächster Lernlauf berücksichtigt den Ausschluss.
  - API: `/v1/eta/profiles/{id}/challenge` (anlegen, Status lesen, Entscheidung durch Prüfrolle).
  - Oberfläche: Fahrer-App Meldung aus der Profilansicht (Zustände: keine beanstandbaren Messungen, offline als Entwurf mit lokalem Status gespeichert – „synchronisiert“ und „entschieden“ getrennt, Fehler); UI-20 Prüfarbeitsliste (Human Review) mit Zuständen leer, fehlender Zweck, fehlende Rechte.
  - Datenbank: `observation_challenge` (Arbeitsname), `driving_observation`, `audit_event`. Ereignisse/Outbox: Beanstandung entschieden.
- **Detailverträge vorab:** Zustandsautomat; Prüfrolle; Nachweisarten; Rückmeldung an den Fahrer.
- **Akzeptanzkriterien:**
  1. Ein anerkannter Nachweis fehlerhaften GPS-Empfangs entfernt den Messpunkt aus dem nächsten Lernlauf; der neue Profilstand enthält ihn nachweislich nicht (ETA-015).
  2. Die Entscheidung trifft ein Mensch; Prüfer, Grund und Zeitpunkt sind gespeichert; abgelehnte Beanstandungen bleiben nachvollziehbar (ETA-015).
  3. Fahrer können nur eigene Messungen beanstanden.
  4. Der Fahrer erhält den Status und das Ergebnis seiner Beanstandung; eine offline erfasste Meldung geht nach Wiederverbindung genau einmal ein.
- **Tests:** positiv: Beanstandung anerkannt → Lernlauf. negativ: fremde Messung, doppelte Beanstandung (idempotent), Entscheidung durch nicht berechtigte Rolle. konkurrierend (Datentrennung): Entscheidung während laufendem Lernlauf – Messpunkt spätestens im Folgelauf ausgeschlossen, kein inkonsistenter Stand.
- **Abhängigkeiten:**
  - intern: WP-M8b-08, WP-M8b-16.
  - extern: WP-M3-24, WP-M3-44 (Fahrer-App, Evidenz-Upload)
- **Entscheidungen:** D-10 (menschliche Beanstandung) – Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Korrektur von Arbeitszeitdaten (M7d).

### WP-M8b-18 – Ablehnen und Überschreiben von ETA-Vorschlägen durch die Leitstelle
- **Ziel:** Disponenten lehnen ETA-Vorschläge ab oder überschreiben sie mit Pflichtgrund; Eingriff, Auswirkung und Revisionsvermerk bleiben gespeichert.
- **Anforderungen:** `LH-I.3-ETA-014`
- **Liefergegenstände:**
  - Backend: Übersteuerung als eigenes, unveränderliches Ereignis mit ursprünglichem Wert, neuem Wert, Grund, Akteur, betroffenen Stopps/Buchungen; Sicherheitsprüfung im Commit (Mindestfahrzeit und harte Fenster aus WP-M8b-09/13 bleiben vorrangig).
  - API: Übersteuerung im Rahmen `/v1/runs/{id}/revisions` (Begründung manueller Änderung, Plan-CAS mit `If-Match`).
  - Oberfläche: UI-07 Aktion „ETA ablehnen/überschreiben“ mit Auswirkungsvorschau.
  - Datenbank: `eta_override` (Arbeitsname), `audit_event`. Ereignisse/Outbox: ETA übersteuert.
- **Detailverträge vorab:** Grundkatalog; Auswirkungsberechnung; Gültigkeitsdauer einer Übersteuerung (Offener Punkt 14).
- **Akzeptanzkriterien:**
  1. Ein Dispatcher-Eingriff wird mit Grund, Auswirkung (betroffene Stopps/Zeiten) und Revisionsvermerk gespeichert und ist später unverändert abrufbar (ETA-014).
  2. Übersteuerung ohne Grund wird abgelehnt; eine Übersteuerung unter die sichere Mindestfahrzeit ist nicht möglich (ETA-014).
  3. Übersteuerte Werte fließen nicht als Fahrerleistung in das Profil ein.
  4. Auch das reine Ablehnen eines ETA-Vorschlags ohne eigenen Ersatzwert wird mit Grund und Akteur gespeichert; welcher Wert danach gilt, legt der Detailvertrag fest (Offener Punkt 14) (ETA-014).
  5. Eine Übersteuerung kann eine gebundene Zusage oder einen fixierten Stopp nicht verletzen (Konfliktantwort wie in WP-M8b-13).
- **Tests:** positiv: Überschreiben mit Grund. negativ: ohne Grund, unter Mindestfahrzeit, Fremdmandant, Rolle ohne Dispositionsrecht. konkurrierend (Kapazität): zwei Disponenten übersteuern denselben Stopp – zweiter erhält 412, kein stilles Überschreiben.
- **Abhängigkeiten:**
  - intern: WP-M8b-02, WP-M8b-09, WP-M8b-11, WP-M8b-13.
  - extern: WP-M2-16, WP-M2-28, WP-M3-40 (Planrevisionen, Plan-CAS)
  - extern: WP-M3-36 (Leitstellenoberfläche UI-07)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Alternativvorschläge (WP-M8b-19).

### WP-M8b-19 – Alternativoptionen bei nicht sicher einhaltbaren Aufträgen
- **Ziel:** Bei Engpässen sieht der Disponent konkrete Alternativen (frühere Abfahrt, anderes Fahrzeug, zusätzliche Reservefahrt) statt eines Aufrufs zum schnelleren Fahren.
- **Anforderungen:** `LH-I.3-ETA-023`
- **Liefergegenstände:**
  - Backend: Erkennung „Auftrag mit konfiguriertem Quantil nicht sicher einhaltbar“; Erzeugung von Optionen über die bestehende Kandidatensuche; Optionen sind Vorschläge, Annahme über Planrevision.
  - API: Optionsliste im Leitstellenlesemodell bzw. `/v1/operator/incidents`.
  - Oberfläche: UI-07 Optionskarte mit Wirkung je Alternative; Textprüfung: keine Formulierung, die zu höherer Geschwindigkeit auffordert (auch nicht in Fahrer-App-Mitteilungen).
  - Konfigurationsparameter: `eta_model_quantile`.
- **Detailverträge vorab:** Auslöseschwelle; Optionsarten; Bedeutung „Reservefahrt“ (Offener Punkt 15).
- **Akzeptanzkriterien:**
  1. Ein nicht sicher einhaltbarer Auftrag erzeugt mindestens eine konkrete Alternativoption aus frühere Abfahrt, anderes Fahrzeug oder Reservefahrt (ETA-023).
  2. Weder Leitstellen- noch Fahreroberfläche enthält eine Aufforderung zur Geschwindigkeitssteigerung; geprüft über Textkatalog und UI-Test (ETA-023).
- **Tests:** positiv: Engpassfixture → Optionen. negativ: keine zulässige Alternative → ausdrücklicher Hinweis „keine sichere Option“, keine verkürzte ETA; Option, die Schicht-/Ruhezeit oder ein hartes Zeitfenster verletzt, wird nicht angeboten. konkurrierend (Kapazität): zwei Disponenten nehmen dieselbe Alternative an – zweiter erhält 412, keine doppelte Ressourcenbelegung.
- **Abhängigkeiten:**
  - intern: WP-M8b-13, WP-M8b-18.
  - extern: WP-M2-15, WP-M2-17 (Kandidatensuche, Reoptimierung)
  - extern: WP-M3-36 (Leitstellenoberfläche UI-07)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Automatische Umsetzung der Alternativen (Automatisierungsregeln, M8d).

### WP-M8b-20 – Güteüberwachung je Segment mit dokumentiertem Fallback und Rollout-Stopp
- **Ziel:** MAE, Bias und Intervalltrefferquote werden je Region, Fahrergruppe, Fahrzeugtyp und Tageszeit überwacht; ein signifikant schlechteres Segment oder Modell fällt dokumentiert auf das vorherige bzw. fahrerneutrale Modell zurück.
- **Anforderungen:** `LH-I.3-ETA-019`
- **Liefergegenstände:**
  - Adapter/Worker: Gütejob vergleicht Prognose (aus `eta_prediction_record`, Arbeitsname) und Ist je qualifiziertem Stopp, segmentiert und schreibt `eta_quality_snapshot` (Arbeitsname) bzw. `aggregate_snapshot`.
  - Backend: Schwellenprüfung je Segment; Fallback gemäß `eta_fallback_policy`; anteiliger Rollout über `eta_model_rollout_percent` mit automatischem Stopp und Reaktivierung der vorherigen `eta_model_revision`; Fallbackprotokoll (Anlass, Segment, Zeitpunkt, Modellstände).
  - API: `/v1/eta/models/{id}/quality` (Aggregatgüte je Segment, Datenstand, Vollständigkeit).
  - Oberfläche: UI-19 Güteansicht mit Zuständen „keine Daten“, „geringe Qualität“.
  - Ereignisse/Outbox: Fallback ausgelöst, Rollout gestoppt; Alarm über `business_alert`/`alert_policy_revision`.
  - Kennzahlen: `ETA-MAE`, `ETA-Bias`, `ETA-p90-Abdeckung`.
  - Konfigurationsparameter: `eta_model_rollout_percent`, `eta_fallback_policy`.
- **Detailverträge vorab:** Signifikanzkriterium und Schwellen (Offener Punkt 8); Segmentdefinition „Fahrergruppe“ mit Mindestgruppengröße; Enumwerte `eta_fallback_policy`; Abgrenzung zu ETA-024/025 (Offener Punkt 2).
- **Akzeptanzkriterien:**
  1. Ein im Fixture signifikant schlechteres Modellsegment wird erkannt und führt zum dokumentierten Fallback auf die sichere Referenz (ETA-019).
  2. MAE, Bias und Intervalltrefferquote sind je Region, Fahrergruppe, Fahrzeugtyp und Tageszeit abrufbar; Fahrergruppen unter `kpi_aggregation_min_n` werden unterdrückt (ETA-019).
  3. Prognostiziert ein neues Modell wesentlich schlechter, stoppt der Rollout und das vorherige Modell wird wieder aktiv, ohne Buchung oder Disposition zu unterbrechen (E2E-078).
  4. Jeder Fallback und jeder Rollout-Stopp ist mit Anlass, Segment, Zeitpunkt, betroffenen Modellständen und auslösender Kennzahl im Audit dokumentiert und in UI-19 einsehbar (ETA-019).
  5. `eta_model_rollout_percent` ist nur im Bereich 0..100 und nur nach Freigabe veröffentlichbar (Katalogvalidierung); `eta_fallback_policy` lässt keinen Wert zu, der die sichere fahrerneutrale Referenz ausschließt.
  6. Segmente ohne ausreichende Ist-Daten werden als „nicht berechenbar“ ausgewiesen und lösen weder Scheingüte noch einen Fallback aus.
- **Tests:** positiv: gutes Modell bleibt aktiv. negativ: schlechtes Segment, schlechtes Gesamtmodell (E2E-078), fehlende Ist-Daten → „nicht berechenbar“ statt Scheingüte. konkurrierend (Sicherheit): Fallback parallel zu Rollout-Erhöhung – Endzustand ist Fallback. Referenztests: E2E-078, Teil von PH-AT-022; Regression AT-21.
- **Abhängigkeiten:**
  - intern: WP-M8b-03, WP-M8b-05, WP-M8b-11, WP-M8b-12.
  - extern: WP-M4-45, WP-M4-46, WP-M4-58, WP-M4-61 (KPI-Kern (`metric_definition_revision`, `aggregate_snapshot`, `dataset_watermark`) einschließlich Kleingruppenschutz mit `kpi_aggregation_min_n`, Grundmodell)
  - extern: WP-M8c-22, WP-M8c-32 (ausführbare Definitionen `ETA-MAE`, `ETA-Bias`, `ETA-p90-Abdeckung`, Dashboard UI-19) – Reihenfolgekonflikt (Zyklus über WP-M8c-22), siehe Offener Punkt 27
  - extern: WP-M8d-22, WP-M8d-23 (Alarmierung (`alert_policy_revision`, `business_alert`))
- **Entscheidungen:** D-10 (Qualitätsgrenzen) – Produktivwerte.
- **Größe:** L
- **Nicht im Umfang:** Shadow-Testing und formale Modellfreigabe mit Verantwortlichen (`LH-I.3-ETA-024`, `LH-I.3-ETA-025`, P3/M9).

### WP-M8b-21 – Drift-Überwachung und Aktualisierung der Routinggrundlagen
- **Ziel:** Änderungen durch Baustellen, Streckenumstellungen oder Datenalterung werden erkannt, lösen eine Modellwarnung aus und aktivieren aktuelle Routinggrundlagen.
- **Anforderungen:** `LH-I.3-ETA-020`
- **Liefergegenstände:**
  - Adapter/Worker: Driftjob über Abweichungstrend je Streckenabschnitt/Region und Datenalter der Profile; bei Warnung Invalidierung betroffener `route_matrix_cache`-Einträge und Neuberechnung mit aktuellem Routingdatenstand; betroffene Profilsegmente werden bis zur Neulernung auf Referenz gesetzt.
  - Ereignisse/Outbox: Modellwarnung; `business_alert`.
  - Oberfläche: UI-19 Warnliste mit Ursache und Datenstand.
- **Detailverträge vorab:** Driftmaß und Schwelle (Offener Punkt 8); Mechanismus „aktuelle Routinggrundlagen aktivieren“ (Offener Punkt 16).
- **Akzeptanzkriterien:**
  1. Eine simulierte Streckensperrung löst eine Modellwarnung aus (ETA-020).
  2. Nach der Warnung verwenden neue Prognosen den aktuellen Routingdatenstand; die verwendete Routingversion ist in der ETA ausgewiesen (ETA-020).
  3. Veraltete Profilsegmente (Datenalterung) werden erkannt und nicht weiter angewendet (ETA-020).
- **Tests:** positiv: Sperrungsfixture. negativ: kurzzeitiger Ausreißer löst keine Warnung aus; Routingdienst nicht erreichbar → Fallback mit Kennzeichnung, keine stille Altberechnung; Fremdmandant sieht Warnung nicht. konkurrierend (Sicherheit): Cache-Invalidierung während laufender Prognoseberechnung – jede Prognose nutzt genau einen ausgewiesenen Routingdatenstand.
- **Abhängigkeiten:**
  - intern: WP-M8b-03, WP-M8b-20.
  - extern: WP-M2-10 (Routingadapter, `route_matrix_cache` mit Versionsschlüssel)
  - extern: WP-M8d-22, WP-M8d-23 (Alarmierung)
- **Entscheidungen:** D-09 (Routing/Karten) – Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Pflege der Kartendaten selbst.

### WP-M8b-22 – Verarbeitungsregister für neue Datenverarbeitungen
- **Ziel:** Für jeden neuen Datenverarbeitungsvorgang sind Datenschutzrolle, Zweck, Rechtsgrundlage und Empfängerkreis erfassbar; das Register ist für Beleg- und ETA-Verarbeitung vollständig befüllbar.
- **Anforderungen:** `LH-I.11-GOV-001`
- **Liefergegenstände:**
  - Backend: Registereintrag (versioniert) je Verarbeitung mit Pflichtfeldern Datenschutzrolle, Zweck, Grundlage, Empfängerkreis, Datenkategorien, Verantwortliche, Verweis auf `retention_rule`; Vollständigkeitsprüfung; Export.
  - Datenbank: `processing_activity` (Arbeitsname), `audit_event`.
  - API: Arbeitsname `/v1/privacy/processing-activities`.
  - Oberfläche: UI-20 Registerpflege mit Zuständen „fehlender Zweck“, leeres Register, fehlende Rechte.
- **Detailverträge vorab:** Feldliste und Pflichtfelder; Verknüpfung mit dem Aktivierungsgate (WP-M8b-04); Abgrenzung zum Verarbeitungsverzeichnis aus M1.
- **Akzeptanzkriterien:**
  1. Für die Belegverarbeitung und die ETA-Datenverarbeitung lässt sich je ein Registereintrag mit allen Pflichtfeldern vollständig anlegen; fehlende Pflichtfelder werden benannt (GOV-001).
  2. Jeder Eintrag enthält Datenschutzrolle, Zweck, Grundlage und Empfängerkreis und ist versioniert änderbar (GOV-001).
  3. Einträge sind mandantengetrennt.
  4. Anlegen, Ändern und Freigeben eines Eintrags sind auf die Datenschutzrolle beschränkt und im Audit mit Akteur und Version nachvollziehbar (GOV-001).
- **Tests:** positiv: zwei vollständige Einträge. negativ: Eintrag ohne Zweck nicht freigebbar; Fremdmandant; Rolle ohne Datenschutzrecht. konkurrierend (Datentrennung): zwei Bearbeiter ändern denselben Eintrag – zweiter erhält 412.
- **Abhängigkeiten:**
  - intern: WP-M8b-02.
  - extern: WP-M1-46, WP-M1-07, WP-M1-32 (Audit, Rollen Datenschutz, vorhandenes Verarbeitungsverzeichnis)
  - extern: WP-M4-22 (Belegverarbeitung als zu registrierender Vorgang)
- **Entscheidungen:** D-11 (Verantwortliche je Datenfluss) – Inhalte; Bau nicht blockiert.
- **Größe:** M
- **Nicht im Umfang:** Rechtliche Bewertung der Einträge.

### WP-M8b-23 – Aufbewahrungsklassen je Datentyp mit Schutz steuerlicher Nachweise
- **Ziel:** Aufbewahrungsfristen unterscheiden Fahrgastdaten, Fahrerprognosen, Buchungs-/Zahlungsbelege und technische Protokolle; der Ablauf eines Datentyps löscht nie ungeprüft steuerlich notwendige Rechnungsdaten.
- **Anforderungen:** `LH-I.11-GOV-005`
- **Liefergegenstände:**
  - Backend: Datentypklassen in `retention_rule` (mindestens die vier genannten) mit eigener Frist und Aktion; Löschplanung prüft je Objekt Abhängigkeiten zu aufbewahrungspflichtigen Finanzbezügen und Legal Hold; PII wird von Finanzbezug getrennt (Anonymisierung statt Löschung des Belegs, Kap. 01 §1.7).
  - Datenbank: `retention_rule`, `erasure_job`, `legal_hold` (Arbeitsname); gelesen `passenger_private`, `eta_profile`, `expense_document`, `journal`, `audit_event`.
  - Oberfläche: UI-20 Fristenmatrix und Löschvorschau mit Zustand „Legal Hold“.
- **Detailverträge vorab:** Löschmatrix Datentyp × Frist × Aktion (Fristen offen, D-11); Prüfregel „steuerlich notwendig“; Zuständigkeit Legal Hold mit M8e.
- **Akzeptanzkriterien:**
  1. Für die vier Datentypklassen sind getrennte Fristen konfigurierbar und wirksam (GOV-005).
  2. Läuft die Frist für Fahrgastdaten ab, bleiben zugehörige Rechnungs-/Buchungsbelege mit steuerlicher Aufbewahrungspflicht erhalten; nur entbehrliche PII wird entfernt, der Vorgang ist protokolliert (GOV-005).
  3. Ein Objekt unter Legal Hold wird nicht gelöscht.
  4. Technische Protokolle und Fahrerprognosen laufen unabhängig von den Fristen der Buchungs-/Zahlungsbelege ab; `audit_event` und `journal` werden vom Löschlauf nicht verändert (Invariante „Gegenereignis statt Änderung“, Kap. 04 §4.5) (GOV-005).
  5. Ein abgebrochener Löschlauf ist fortsetzbar und löscht bei Wiederholung nichts doppelt oder zusätzlich.
- **Tests:** positiv: Fristablauf Fahrerprognosen löscht Profile. negativ: Fristablauf Fahrgast mit offenem Rechnungsbezug; Legal Hold. konkurrierend (Geld/Datentrennung): Löschlauf parallel zu Periodenabschluss/Belegbuchung – kein Beleg verliert seine Referenz, Journal bleibt ausgeglichen. Regression: AT-53.
- **Abhängigkeiten:**
  - intern: WP-M8b-02, WP-M8b-22.
  - extern: WP-M1-33 (Löschjob-Grundgerüst (`retention_rule`, `erasure_job`))
  - extern: WP-M4-22, WP-M4-23, WP-M4-33 (Beleg-/Journalmodell und Finanzarchiv)
- **Entscheidungen:** D-11 – Fristen blockieren Produktivschaltung, nicht den Bau; D-06 (Aufbewahrung Finanzdokumente).
- **Größe:** M
- **Nicht im Umfang:** Löschung in Objektspeicher, Suchindex und Partnerprojektion als Orchestrierung (M8e); Restore mit Lösch-/Sperrjournal (M8e).

### WP-M8b-24 – Auskunfts- und Löschprozess mit Prüfung der Aufbewahrungspflichten
- **Ziel:** Auskunfts- und Löschanfragen werden datenschutzgerecht bearbeitet; die Auskunft listet Kategorien und Empfänger ohne Daten anderer Mandanten, die Löschung prüft gesetzliche Aufbewahrungspflichten.
- **Anforderungen:** `LH-I.11-GOV-006`
- **Liefergegenstände:**
  - Backend: Auskunftsaufbereitung aus dem Verarbeitungsregister (Kategorien, Empfänger, Zwecke, Fristen) je Mandantenkontext; Löschentscheid mit Prüfschritt gegen `retention_rule` und Legal Hold (WP-M8b-23); Ergebnis je Datenkategorie „gelöscht / gesperrt wegen Aufbewahrungspflicht“.
  - API: `/v1/privacy/requests` (Felder Kategorien, Empfänger, Aufbewahrungsprüfung).
  - Oberfläche: UI-20 Fallbearbeitung; UI-05 Löschstatus.
  - Datenbank: `erasure_job`, `export_job`, `processing_activity`, `audit_event`.
- **Detailverträge vorab:** Antwortschema der Auskunft; Abgrenzung zu `LH-6.18-SEC-014` und `LH-6.2-IAM-012` (M8e), siehe Offener Punkt 17; Verhalten bei Personen mit Konten in mehreren Mandanten.
- **Akzeptanzkriterien:**
  1. Eine Auskunft listet Datenkategorien und Empfänger des anfragenden Mandantenkontexts; sie enthält nachweislich keine Daten, Kategorien oder Existenzhinweise aus anderen Mandanten (GOV-006).
  2. Eine Löschanfrage wird vor Ausführung gegen gesetzliche Aufbewahrungspflichten geprüft; aufbewahrungspflichtige Daten werden gesperrt statt gelöscht und der Grund wird ausgewiesen (GOV-006).
  3. Jede Anfrage, Entscheidung und Auslieferung ist mit Akteur, Zeitpunkt und Ergebnis im Audit protokolliert; die Auskunft selbst wird nur dem berechtigten Anfragenden bereitgestellt (GOV-006).
- **Tests:** positiv: Auskunft für Fahrgast und Fahrer. negativ: Person existiert in zwei Mandanten – Auskunft in A zeigt nichts aus B; Löschung mit offenem Rechnungsbezug; Legal Hold. konkurrierend (Datentrennung): zwei parallele Anfragen derselben Person in zwei Mandanten bleiben getrennt.
- **Abhängigkeiten:**
  - intern: WP-M8b-22, WP-M8b-23.
  - extern: WP-M1-04, WP-M1-46, WP-M1-33 (Identität, Audit, Löschjob-Grundgerüst)
  - extern: WP-M8e-16, WP-M8e-14 (Betroffenenrechte-Workflow und Endpunkt `/v1/privacy/requests`, Legal Hold) – Reihenfolgekonflikt (Zyklus über WP-M8e-16), siehe Offener Punkt 27
- **Entscheidungen:** D-11 (Auskunfts-/Exportprozess) – Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Identitätsprüfung des Anfragenden, Export- und Korrekturworkflow (M8e).

### WP-M8b-25 – Zugriffsbeschränkung nach Kostenstelle und Vertragsbeziehung
- **Ziel:** Sensible Kosten- und Partnerdaten sind zusätzlich zur Rolle nach Kostenstelle und Vertragsbeziehung beschränkbar.
- **Anforderungen:** `LH-I.11-GOV-007`
- **Liefergegenstände:**
  - Backend: zusätzliche Berechtigungsdimension Kostenstellen-Scope und Vertrags-Scope im Autorisierungscheck von Kosten-, Einkaufs- und Partnersichten; getrennte DTOs für Fahrstatus und Einkaufsbedingungen; serverseitige Filterung in Listen, Exporten und Berichten.
  - Datenbank: `membership` (Erweiterung um Kostenstellen-/Vertragsscope oder Arbeitsname `access_scope`), `cost_object`, `partner_contract_version`, `cooperation_grant`.
  - API: betroffen `/v1/cost-events`, `/v1/vehicles/{id}/total-cost`, `/v1/clearing/statements`, `/v1/reports/jobs`.
  - Oberfläche: UI-15 Zustand „fehlende Partnerrechte“; UI-18 Vergabe der Scopes.
- **Detailverträge vorab:** Scopemodell; Liste „sensible Kosten-/Partnerdaten“; Verhalten bei Summen über gesperrte Kostenstellen.
- **Akzeptanzkriterien:**
  1. Ein Operator-Mitarbeiter sieht den Fahrstatus, erhält aber auf fremde Einkaufsbedingungen 403 bzw. das Feld ist in keiner Antwort enthalten (GOV-007).
  2. Ein Nutzer mit Kostenstellen-Scope sieht Kosten nur seiner Kostenstellen, auch in Export und Bericht (GOV-007).
  3. Entzug eines Scopes wirkt beim nächsten Request.
  4. Verweigerte Zugriffe auf sensible Kosten-/Partnerdaten sind protokolliert; Summen über gesperrte Kostenstellen lassen keinen Rückschluss auf gesperrte Einzelwerte zu (Verfahren laut Detailvertrag).
- **Tests:** positiv: berechtigter Finanznutzer. negativ: Operator auf Einkaufsbedingungen, fremde Kostenstelle, Partner auf Margen des anderen Betreibers, Export/Cache/Berichtsdownload. konkurrierend (Datentrennung): Scopeentzug während laufendem Berichtsjob – kein Download gesperrter Daten. Regression: PH-AT-016 (Bezug GOV-007 laut Kap. 08 §8.5), E2E-092, E2E-094.
- **Abhängigkeiten:**
  - intern: WP-M8b-02.
  - extern: WP-M1-07, WP-M1-08 (Rollen-/Scopemodell, API-Autorisierung)
  - extern: WP-M4-29 (Kostenobjekte/Kostenstellen)
  - extern: WP-M6b-04, WP-M6b-05 (Vertragsbeziehungen und Grants)
- **Entscheidungen:** D-06, D-07 (Kostenstellenstruktur) – Inhalte; Bau nicht blockiert.
- **Größe:** M
- **Nicht im Umfang:** Finanzfunktionen selbst (M8a); Partnerprojektionen (M6).

### WP-M8b-26 – Ende-zu-Ende-Testkatalog mit automatischer CI-Protokollierung
- **Ziel:** Ein ausführbarer Ende-zu-Ende-Katalog deckt Partner-Timeout, Doppelbuchung, Dublettenbeleg, ETA-Sicherheitsgrenzen und Datenisolation ab; jede Ausführung wird in CI automatisch protokolliert.
- **Anforderungen:** `LH-I.11-GOV-008`
- **Liefergegenstände:**
  - Tests: Katalog unter `tests/acceptance/` mit mindestens je einem Szenario der fünf Themen, verknüpft mit Anforderung und Referenztest; ETA-Sicherheitsgrenzen neu aus dieser Einheit, übrige Themen als Regression bestehender Szenarien. Kandidaten bis zur Bestätigung in Offenem Punkt 18: Partner-Timeout E2E-088 und E2E-104, Doppelbuchung AT-12, Dublettenbeleg E2E-065, Datenisolation AT-49, ETA-Sicherheitsgrenzen E2E-075 und PH-AT-022.
  - CI: Pflichtlauf je Merge/Release; maschinenlesbares Protokoll (Testfall, Anforderung, Ergebnis, Commit, Zeitpunkt), unveränderlich archiviert; fehlender oder übersprungener Katalogtest lässt die Pipeline scheitern.
- **Detailverträge vorab:** Zuordnung Thema → Testfälle (Offener Punkt 18); Protokollschema und Aufbewahrung.
- **Akzeptanzkriterien:**
  1. Für Partner-Timeout, Doppelbuchung, Dublettenbeleg, ETA-Sicherheitsgrenzen und Datenisolation existiert je mindestens ein ausführbarer Ende-zu-Ende-Test (GOV-008).
  2. Alle Katalogtestfälle werden in Continuous Integration automatisch ausgeführt und protokolliert; das Protokoll eines Laufs ist nachträglich abrufbar (GOV-008).
  3. Ein deaktivierter Katalogtest führt zu einem roten Lauf.
- **Tests:** positiv: vollständiger CI-Lauf. negativ: Katalogtest entfernt/übersprungen → Pipeline rot. Referenztests: PH-AT-009, E2E-104, E2E-088, E2E-065, AT-12, AT-49 (Regression), E2E-075 und PH-AT-022 (ETA-Teil).
- **Abhängigkeiten:**
  - intern: WP-M8b-03, WP-M8b-09.
  - extern: WP-M0-12 (CI-Pipeline)
  - extern: WP-M5-23, WP-M5-24 (Abnahmesuite und bestehende Szenarien Doppelbuchung/Datenisolation/Dublettenbeleg)
  - extern: WP-M6a-03, WP-M6a-09 (Sandbox-Partner und Partner-Timeout-Szenario)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Revisionsfähige Releasefreigabe mit Testbericht (`LH-I.11-GOV-011`, P3/M9); erweiterte QAT-Anforderungen (M8e).

### WP-M8b-27 – Last- und Fehlergrenzen, Schutz gegen kaskadierende Partnerausfälle
- **Ziel:** Mehrmandanten- und Föderationsschnittstellen haben je Partner und Mandant begrenzte Ressourcen, Zeitlimits und Unterbrecher; ein langsamer Partner blockiert weder lokale Buchung noch andere Partner.
- **Anforderungen:** `LH-I.11-GOV-009`
- **Liefergegenstände:**
  - Backend/Adapter: je Partner getrennte Verbindungs-/Parallelitätsbudgets, Timeout aus `partner_api_timeout_ms`, Circuit Breaker mit Halböffnung, Ratenbegrenzung je Mandant; keine Partneraufrufe in offenen SQL-Transaktionen; Ergebnis `PARTNER_UNAVAILABLE` mit Aktualitätsstatus (Kap. 02).
  - API: betroffen `/v1/federation/journeys/plan`, `/v1/partner/offers`, `/v1/federation/partners/{partner_id}/events`.
  - Kennzahlen: `Technische Partnerablehnungen`.
  - Konfigurationsparameter: `partner_api_timeout_ms`.
- **Detailverträge vorab:** Grenzwerttabelle je Schnittstelle; Planungsdefaults aus Kap. 02 (1.500 ms erste Partnerauskunft, 3.000 ms gemeinsames Partnerfenster, 5.000 ms Gesamtbudget – ausdrücklich Planungsdefaults, im Referenzlasttest zu kalibrieren); Breaker-Schwellen (Offener Punkt 19).
- **Akzeptanzkriterien:**
  1. Bei einem künstlich langsamen Partner bleibt die lokale Fahrgastbuchung innerhalb ihres Zeitbudgets und Angebote anderer Partner werden weiterhin geliefert (GOV-009).
  2. Je Föderations- und Mehrmandantenschnittstelle sind Last- und Fehlergrenzen dokumentiert, konfiguriert und im Lasttest nachgewiesen (GOV-009).
  3. Überlast eines Mandanten verdrängt keine Anfragen anderer Mandanten.
  4. Ein nicht antwortender Partner wird als unverfügbar mit Aktualitätsstatus ausgewiesen; nach Erholung nimmt der Unterbrecher den Partner ohne manuellen Eingriff wieder auf, und `Technische Partnerablehnungen` zählt die Ausfälle (GOV-009).
- **Tests:** positiv: alle Partner schnell. negativ: ein Partner mit hoher Latenz, ein Partner mit Fehlerrate 100 %, Wiederanlauf (Halböffnung). konkurrierend (Kapazität): Lasttest mit parallelen lokalen Buchungen während Partnerstörung – keine Erschöpfung von Threads/DB-Verbindungen. Referenztests: PH-AT-009, E2E-104 und E2E-088 als Regression.
- **Abhängigkeiten:**
  - intern: WP-M8b-03.
  - extern: WP-M6a-05, WP-M6a-09, WP-M6a-03, WP-M6b-26 (Föderationsadapter, Partnerbudgets, Sandbox-Partner)
  - extern: WP-M5-02, WP-M5-10 (Lasttestumgebung und Referenzlastprofil)
  - extern: WP-M1-02 (Rate-Limit-Baustein)
- **Entscheidungen:** D-05 (Peaklast), D-08 (Partnerstandards) – Kalibrierung; Bau nicht blockiert.
- **Größe:** M
- **Nicht im Umfang:** Saga-Wiederaufnahme nach Workerabsturz (M6).

### WP-M8b-28 – Verantwortliche, Konfigurationsreview und Rücknahme produktionskritischer Regeln
- **Ziel:** Jede produktionskritische Regel hat benannte Verantwortliche, ein Review vor Veröffentlichung und eine Rollback-Anweisung; eine fehlerhafte Freigabe lässt sich nachvollziehbar zurücknehmen.
- **Anforderungen:** `LH-I.11-GOV-010`
- **Liefergegenstände:**
  - Backend: Register produktionskritischer Regeln mit Verantwortlichem, Stellvertretung, Reviewpflicht und Rollback-Anweisung; Veröffentlichung kritischer Parameter nur mit Review durch eine zweite Person; Rücknahme erzeugt eine neue Revision mit Verweis auf die zurückgenommene (Kap. 01 §1.6).
  - Datenbank: `rule_ownership` (Arbeitsname), `config_revision` (`created_by`, `approved_by`, `published_at`), `policy_schema`, `audit_event`.
  - API: `/v1/config/revisions`, `/v1/config/revisions/{id}`, `/validate`, `/impact`, `/v1/config/revisions/{revision_id}/publish`, `/v1/config/effective`.
  - Oberfläche: Konfigurationseditor (Kap. 06 §6.8): Verantwortliche, Prüfer, Rücknahmeaktion mit Verlauf.
- **Detailverträge vorab:** Liste der produktionskritischen Regeln (Offener Punkt 20); Abgrenzung zum Konfigurationslebenszyklus in M8d.
- **Akzeptanzkriterien:**
  1. Eine fehlerhafte Konfigurationsfreigabe lässt sich zurücknehmen; die Rücknahme ist als neue Revision mit Akteur, Grund und Bezug nachvollziehbar, historische Buchungen behalten ihre Zusageversion (GOV-010).
  2. Für jede als produktionskritisch markierte Regel sind Verantwortliche und Rollback-Anweisung hinterlegt; ohne diese Angaben und ohne Review ist keine Veröffentlichung möglich (GOV-010).
  3. Die Rollback-Anweisung ist bei der Rücknahme in der Oberfläche einsehbar; die Rücknahme verlangt einen Grund und ist auf berechtigte Rollen beschränkt (GOV-010).
- **Tests:** positiv: Veröffentlichung mit Review, anschließende Rücknahme. negativ: Selbstfreigabe, fehlender Verantwortlicher, Rücknahme durch unberechtigte Rolle. konkurrierend (Sicherheit): zwei Prüfer veröffentlichen gleichzeitig – zweiter erhält 412.
- **Abhängigkeiten:**
  - intern: WP-M8b-02.
  - extern: WP-M1-20, WP-M1-22 (Konfigurationsrevisionen und Veröffentlichung)
  - extern: WP-M8d-05 (Entwurf, Vorschau, Freigabe, Rücknahme und Wirkungsbericht der Konfiguration) – Reihenfolgekonflikt (Zyklus über WP-M8d-05), siehe Offener Punkt 27
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Release-Rollback der Software (`LH-6.19-NFR-013`, M8e).

### WP-M8b-29 – Aggregation und Mindestgruppengröße für personenbezogene Auswertungen
- **Ziel:** Betriebsrelevante personenbezogene Auswertungen sind durch konfigurierbare Aggregation und Mindestgruppengrößen begrenzt; Kleingruppen-Dashboards geben keine rückrechenbaren Einzelwerte aus.
- **Anforderungen:** `LH-I.11-GOV-012`
- **Liefergegenstände:**
  - Backend: zentrale Unterdrückungsstufe für alle personenbezogenen Auswertungen (KPI, ETA-Güte nach Fahrergruppe, Berichte, Exporte) mit Schutz gegen Differenzbildung benachbarter Filter; konfigurierbare Aggregationsebene.
  - API: `/v1/analytics/metrics`, `/v1/reports/jobs`, `/v1/eta/models/{id}/quality` liefern für unterdrückte Zellen einen ausdrücklichen Status statt Wert.
  - Oberfläche: UI-19 Zustand „Gruppe zu klein“.
  - Konfigurationsparameter: `kpi_aggregation_min_n`.
- **Detailverträge vorab:** Liste personenbezogener Auswertungen; Unterdrückungsverfahren (komplementäre Unterdrückung); Mindestwert des Parameters (Datenschutzanforderungen vorrangig, Wert offen).
- **Akzeptanzkriterien:**
  1. Ein Dashboard für eine Gruppe unter `kpi_aggregation_min_n` gibt keine Werte aus, aus denen Einzelpersonenwerte rückrechenbar sind – auch nicht über Differenzbildung, Zeitfilter oder Export (GOV-012).
  2. Aggregationsebene und Mindestgruppengröße sind je Mandant konfigurierbar; ein Wert unterhalb der global zulässigen Grenze wird abgelehnt (GOV-012).
- **Tests:** positiv: Gruppe über Schwelle. negativ: Gruppe unter Schwelle, zwei benachbarte Filter mit Differenz 1, Export derselben Abfrage, Aufgabenträgersicht über drei Firmen (E2E-101), Fremdmandant, Versuch den Parameter unter die globale Grenze zu setzen. konkurrierend (Datentrennung): Parameteränderung während laufendem Berichtsjob – der Job nutzt genau eine Konfigurationsrevision und gibt keine unterdrückte Zelle aus. Referenztests: Beitrag zu E2E-101; Regression PH-AT-021.
- **Abhängigkeiten:**
  - intern: WP-M8b-14.
  - extern: WP-M4-58 (Kleingruppenschutz des KPI-Kerns)
  - extern: WP-M8c-22, WP-M8c-24, WP-M8c-27, WP-M8c-18 (vollständiger KPI-Katalog, Dashboards, Berichtsjobs, Verbundauswertung)
- **Entscheidungen:** D-11 – Schwellenwert; Bau nicht blockiert.
- **Größe:** M
- **Nicht im Umfang:** KPI-Definitionen selbst (M8c).

### WP-M8b-30 – Gate-Abnahme M8b: PH-AT-022 und ETA-Szenarien E2E-074 bis 079, 105, 106
- **Ziel:** Der Gate-Anteil der Einheit läuft als zusammenhängende, automatisierte Abnahme mit protokolliertem Ergebnis.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Tests: PH-AT-022 als durchgängiges Szenario (neues Modell mit zu schnellen Beobachtungen, fehlende Aktivierungsfreigabe, zu wenige Samples; danach Freigabe, Qualitätsgrenzen, Fallback, Rollenprüfung Finanz/HR, Widerruf); E2E-074, E2E-075, E2E-076, E2E-077, E2E-078, E2E-079, E2E-105, E2E-106 unter `tests/acceptance/`; Abnahmeprotokoll je Test.
- **Detailverträge vorab:** Abnahmedrehbuch mit Fixture- und Sollwertbezug.
- **Akzeptanzkriterien:**
  1. PH-AT-022 besteht vollständig unter der Runtime-Rolle mit zwei Mandanten.
  2. E2E-074 bis E2E-079, E2E-105 und E2E-106 bestehen und sind im CI-Protokoll aus WP-M8b-26 enthalten.
  3. Alle offenen Fixtureannahmen (Parameterwerte) sind im Protokoll als nicht fachlich freigegeben gekennzeichnet.
- **Tests:** PH-AT-022, E2E-074, E2E-075, E2E-076, E2E-077, E2E-078, E2E-079, E2E-105, E2E-106; konkurrierend: Widerruf unter Buchungslast.
- **Abhängigkeiten:**
  - intern: WP-M8b-03, WP-M8b-04, WP-M8b-05, WP-M8b-07, WP-M8b-08, WP-M8b-09, WP-M8b-10, WP-M8b-13, WP-M8b-14, WP-M8b-16, WP-M8b-20, WP-M8b-26.
  - extern: WP-M4-61, WP-M4-62 (fahrerneutrales, regelkonformes Grundmodell (`LH-I.3-ETA-001`, `LH-I.3-ETA-002`) für PH-AT-022)
  - extern: WP-M5-23, WP-M5-31 (Abnahmesuite und Protokollierung)
- **Entscheidungen:** D-10 – Abnahme in Testumgebung möglich; Produktivschaltung der Personalisierung blockiert.
- **Größe:** S
- **Nicht im Umfang:** Gate-Tests der Nachbareinheiten (E2E-070, E2E-099, E2E-101 bis 103).

## Offene Punkte

1. **Modulzuordnung ETA.** Die Zielstruktur (`umsetzungsplan.md` §3) nennt kein ETA-Modul; Kap. 04 §4.4 führt „AI/ETA/SIM“ als Tabellengruppe. Die Zuordnung zu `analytics`/`booking-dispatch` ist eine Annahme dieses Plans.
2. **Rollout-Stopp vor P3-Anforderungen.** E2E-078 (Gate M8) verlangt Rollout-Stopp und Reaktivierung des vorherigen Modells; die zugehörigen Anforderungen `LH-I.3-ETA-024` (Shadow-Testing) und `LH-I.3-ETA-025` (Qualitätsfreigaben, Rollback, Verantwortliche) sind SOLL/P3 (M9). WP-M8b-20 liefert nur den automatischen Stopp als Teil von `LH-I.3-ETA-019`; die Abgrenzung ist zu bestätigen.
3. **Fehlende Endpunkte und UI-IDs.** Kap. 05 §5.7 nennt für ETA nur `/v1/eta/models/{id}/quality` und `/v1/eta/profiles/{id}/challenge`. Für Aktivierung/Widerruf (`LH-I.3-ETA-013`, `LH-I.11-GOV-003`, `LH-I.11-GOV-004`), Profiltransparenz (`LH-I.3-ETA-012`), Übersteuerung (`LH-I.3-ETA-014`) und Verarbeitungsregister (`LH-I.11-GOV-001`) fehlen Pfade. Der Konfigurationseditor (Kap. 06 §6.8) und die Fahreransicht des Prognoseprofils haben keinen Eintrag in UI-01 bis UI-20.
4. **„Geprüfte sichere Mindestfahrzeit“.** `LH-I.3-ETA-008` setzt sie voraus; das Pflichtenheft definiert weder Berechnung noch Prüfinstanz. Annahme: regelkonforme Referenzfahrzeit des Grundmodells (`LH-I.3-ETA-001`/`-002`, M4). Die Katalogvalidierung zu `eta_driver_lower_adjust_pct` („nie unter rechtmäßiger Mindestzeit“) und zu `eta_driver_upper_adjust_pct` („nur sichere positive Verlängerung“) lässt Vorzeichen und Bezugsgröße offen.
5. **„Wiederholte“ Geschwindigkeitsverstöße.** `LH-I.3-ETA-010`: Anzahl, Zeitraum und Höhe der Puffervergrößerung fehlen; kein Katalogparameter vorhanden.
6. **„Kritische Prognosen“ und Konfidenzintervall.** `LH-I.3-ETA-018` definiert „kritisch“ nicht; das Verhältnis des Konfidenzintervalls aus `LH-I.3-ETA-006` zu den Quantilen aus `eta_model_quantile` ist offen.
7. **„API für Leistungsentscheidungen“.** `LH-I.3-ETA-011` nennt in der Abnahme eine solche API; im Endpunktkatalog existiert keine. Annahme: Nachweis als Negativ-/Architekturtest über alle Schnittstellen. Zu `LH-I.3-ETA-026`: „rechtlich genehmigte Zweckprüfung“ für Rankings ist nicht beschrieben; D-10 stellt Ranglisten außerhalb des spezifizierten Zwecks – gebaut wird nur der deaktivierte Standard.
8. **Schwellen für Güte und Drift.** `LH-I.3-ETA-019` („signifikant schlechter“) und `LH-I.3-ETA-020` (Driftmaß) haben keine Grenzwerte; die Enumwerte von `eta_fallback_policy` sind nicht aufgezählt. „Fahrergruppe“ ist nicht definiert. Für alle zehn `eta_*`-Parameter fehlen Zahlenwerte (Katalogstatus: „freigegebene Werte vor Implementierung ergänzen“).
9. **Pseudonymisierung.** Kap. 01 §1.7 verlangt Pseudonymisierung der Standort-/Driver-ETA-Daten; Verfahren, Schlüsselhaltung und Rückführbarkeit für Auskunft/Beanstandung (`LH-I.3-ETA-012`, `LH-I.3-ETA-015`) sind nicht festgelegt. Die Tabelle `eta_subject_pseudonym` ist ein Arbeitsname dieses Plans und steht nicht in Kap. 04 §4.4.
10. **Phasentrennung.** `LH-I.3-ETA-016`: Erkennungsregeln für Umweg und „späte Fahrzeugbereitstellung“ sowie die Behandlung nicht zuordenbarer Zeit fehlen.
11. **Ausschlusskriterien.** `LH-I.3-ETA-004`: „ungeklärte GPS-Qualität“ und „untypische Ausnahmeereignisse“ sind nicht operationalisiert; die Quelle zulässiger Geschwindigkeiten hängt an D-09.
12. **Segmenttaxonomie.** `LH-I.3-ETA-005`: Klassen für Streckenklasse, Tageszeit, Stopphäufigkeit und die Datenquelle „Verkehrskontext“ sind nicht definiert.
13. **Löschfrist Fahrerprofile.** `LH-I.3-ETA-012`, `LH-I.11-GOV-005`: Fristen stehen unter D-10/D-11; das Verhältnis von `eta_driver_decay_days` (Lernfenster) zur Löschfrist ist ungeklärt. E2E-105 verlangt „Fristen“ in der Auskunft.
14. **Übersteuerung.** `LH-I.3-ETA-014`: Gültigkeitsdauer einer Übersteuerung, berechtigte Rollen und der Inhalt von „Auswirkung“ sind nicht beschrieben.
15. **Alternativen.** `LH-I.3-ETA-023`: „zusätzliche Reservefahrten“ setzt ein Reservekonzept voraus, das im Pflichtenheft nicht beschrieben ist.
16. **„Aktiviert aktuelle Routinggrundlagen“.** `LH-I.3-ETA-020`: Der Mechanismus (Cache-Invalidierung, Datenstandwechsel, Quelle der Sperrungsinformation) ist nicht spezifiziert.
17. **Überschneidung Betroffenenrechte.** `LH-I.11-GOV-006` überschneidet sich mit `LH-6.18-SEC-014` und `LH-6.2-IAM-012` (M8e) sowie mit dem Löschjob-Grundgerüst aus M1 (dort OP-25). Der Meilensteinplan ordnet „Lösch- und Auskunftsjobs, Legal Hold“ dem Strom Plattform zu; eine Tabelle für Legal Hold nennt Kap. 04 §4.4 nicht. Für das Verarbeitungsregister (`LH-I.11-GOV-001`) existiert ebenfalls keine Tabelle.
18. **Umfang des Testkatalogs.** `LH-I.11-GOV-008` nennt fünf Themen ohne Testfallzuordnung; Überschneidung mit den QAT-Anforderungen (M5, M8e) und mit `LH-I.11-GOV-011` (P3).
19. **Last- und Fehlergrenzen.** `LH-I.11-GOV-009`: außer den Planungsdefaults in Kap. 02 (1.500/3.000/5.000 ms) fehlen Grenzwerte für Parallelität, Breaker-Schwellen und Quoten je Mandant; `partner_api_timeout_ms` hat keinen Wert.
20. **„Produktionskritische Regeln“.** `LH-I.11-GOV-010`: Das Pflichtenheft zählt sie nicht auf; Überschneidung mit dem Konfigurationslebenszyklus der CFG-Anforderungen (M8d).
21. **Kostenstellen-/Vertragsscope.** `LH-I.11-GOV-007`: `membership` kennt nur `role_code` und `service_scope`; ein Scopemodell für Kostenstellen und Vertragsbeziehungen fehlt im Datenmodell.
22. **Kleingruppenschutz.** `LH-I.11-GOV-012` (P2) wird laut PH-AT-021 bereits in M4 für den KPI-Kern gebraucht; `kpi_aggregation_min_n` hat keinen freigegebenen Wert. Die Aufteilung M4/M8b/M8c ist zu bestätigen.
23. **Fairnessprüfung.** Kap. 01 §1.6 nennt eine „Qualitäts-/Fairnessprüfung“ als Aktivierungsvoraussetzung; Kriterien fehlen (betrifft `LH-I.3-ETA-013`, `LH-I.11-GOV-003`).
24. **Format der externen Abhängigkeiten.** `check_wp.py` meldet Zeilen mit „EXTERN[“ als unaufgelöst; die Auflösung auf konkrete WP-IDs der Nachbareinheiten steht nach deren Fertigstellung aus.
25. **Erhebung vor Aktivierung.** Das Pflichtenheft legt nicht fest, ob fahrerbezogene `driving_observation`-Daten bereits vor der genehmigten Aktivierung (`LH-I.3-ETA-013`, `LH-I.11-GOV-003`) erhoben werden dürfen, etwa um die geforderte Stichprobengröße nachzuweisen, oder ob bis dahin nur nicht personenbezogene Flottenwerte entstehen. WP-M8b-06 arbeitet mit der Annahme „keine fahrerbezogene Speicherung ohne aktive Aktivierung“; das ist rechtlich zu entscheiden (D-10).
26. **Herkunftsnachweis je ETA.** `LH-I.3-ETA-006` und `LH-I.3-ETA-019` setzen voraus, dass ausgegebene Prognosen mit Modell-, Profil- und Konfigurationsstand gespeichert werden; Kap. 04 §4.4 nennt dafür keine Tabelle. `eta_prediction_record` ist ein Arbeitsname; Speichertiefe (jede ETA oder nur operative Zusagen/qualifizierte Stopps), Aufbewahrungsklasse und Mengengerüst sind offen.
27. **Zurückgezogene oder abgelaufene Nachweise.** Für `LH-I.3-ETA-013`/`LH-I.11-GOV-003` ist nicht beschrieben, ob Fachfreigaben eine Gültigkeitsdauer haben und was bei Ablauf geschieht. WP-M8b-04 nimmt an, dass die Aktivierung dann gestoppt wird.
28. **Kostenstellen-/Vertragsscope als Tabelle.** `access_scope` (`LH-I.11-GOV-007`) ist ein Arbeitsname; alternativ Erweiterung von `membership`. Entscheidung in WP-M8b-01 gemeinsam mit M1/M6.
29. **Rollout-Steuerung.** `eta_model_rollout_percent` ist laut Katalog „0..100 nach Freigabe“; wer einen Rollout freigibt und nach welcher Zuordnungsregel (Fahrt, Fahrer, Dienst) der Anteil gebildet wird, ist nicht beschrieben (`LH-I.3-ETA-019`, E2E-078).
27. **Reihenfolgekonflikt: Zyklen mit parallelen M8-Einheiten.** Die Auflösung der externen Abhängigkeiten ergibt drei wechselseitige Abhängigkeiten innerhalb des Meilensteins M8, die nicht aufgelöst wurden: (a) Reihenfolgekonflikt: WP-M8b-20 → WP-M8c-22 (ausführbare Definitionen `ETA-MAE`, `ETA-Bias`, `ETA-p90-Abdeckung`), während WP-M8c-22 die ETA-Gütewerte aus M8b (WP-M8b-20) erwartet; (b) Reihenfolgekonflikt: WP-M8b-24 → WP-M8e-16 (Betroffenenrechte-Workflow, `/v1/privacy/requests`), während WP-M8e-16 Auskunftsinhalt und Aufbewahrungsprüfung aus M8b (WP-M8b-22, WP-M8b-24) erwartet; (c) Reihenfolgekonflikt: WP-M8b-28 → WP-M8d-05 (Konfigurationslebenszyklus), während WP-M8d-05 Verantwortliche und Rollback-Anweisung aus WP-M8b-28 erwartet. Zu entscheiden ist je Paar die Lieferrichtung (z. B. Vertrag/Port zuerst gegen Stub, Zusammenschaltung im Gate) oder ein Schnitt der WPs.
