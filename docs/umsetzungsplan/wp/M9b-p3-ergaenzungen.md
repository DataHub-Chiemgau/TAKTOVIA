# M9b – P3-Ergänzungen (E-Mobilität und verstreute Einzelanforderungen)

- **Meilenstein:** M9 – Phase 3 (setzt M6, M7 und M8 voraus; Nachbareinheit M9a: Nachfrageprognose und Szenariosimulation, AI 9 + SIM 9)
- **Phase:** P3 (3 MUSS, 37 SOLL, 14 KANN; Phase und Priorität jeder Anforderung unverändert)
- **Anzahl Anforderungen:** 54 aus 31 Modulen (FLT 6, OPT 4, FED 4, PAX 2, GEO 2, PTI 2, SUP 3, ANA 2, DMG 2, SCH 2, ETA 2, MUL 2, FCO 2, KPI 2 und je eine aus SYS, BKG, DRV, OPS, PAY, COM 6.12, INT, NFR, QAT, CHK, TIM, DOC, CFG, AUT, COM I.9, AST, GOV)
- **Anzahl WPs:** 57 (52 mit Anforderungen, 5 Enabler)

**Ziel:** Nach M9b disponiert die Plattform E-Fahrzeuge unter Beachtung von Ladezustand, Reichweite, Reserve und Ladefenstern, verwaltet Ladepunkte und verarbeitet Telemetrie mit Quelle, Qualität und Alter. Zusätzlich sind die 54 über 31 Module verstreuten P3-Einzelanforderungen je Modul als eigene, einzeln abnehmbare Lieferungen umgesetzt: dynamische Haltepunkte aus dem geprüften Kandidatennetz, prognosegestützte Empfehlungen ohne Einfluss auf verbindliche Regeln, Erweiterungen der Föderation, Berichte, Governance und Personalplanung. Jede KANN-Funktion bleibt hinter einem Release-Aufnahmeschalter ausgeschaltet, bis sie ausdrücklich ins Release aufgenommen ist.

**Gate-Anteil M9b (Umsetzungsplan §4, Gate M9):** AT-20 und AT-58 vollständig; AT-21, AT-22 und AT-55 gemeinsam mit M9a (M9b liefert die Dispositions-, Entkopplungs- und Personalplanungsseite, M9a den Prognose- und Simulationsdienst).

**Lesehinweise:**

- Kurz-IDs in Klammern hinter Akzeptanzkriterien verweisen auf die Anforderung dieser Einheit (z. B. FLT-009 = `LH-6.8-FLT-009`). Wegen der doppelten COM-IDs steht COM-013 immer für `LH-6.12-COM-013` (Kommunikation) und COM-010-CLR für `LH-I.9-COM-010` (Clearing).
- In `openapi.yaml` ausformuliert sind nur 14 Kernpfade; für diese Einheit relevant sind davon `/v1/federation/journeys/plan`, `/v1/federation/journeys/{journey_id}`, `/v1/federation/partners/{partner_id}/events`, `/v1/driver/events`, `/v1/rosters/{roster_id}/publish`, `/v1/config/revisions/{revision_id}/publish` und `/v1/operations/{operation_id}`. Kap. 05 §5.7 nennt weitere Pfade nur als Funktionszuschnitt. Alle Pfade, die in dieser Datei mit **(Arbeitsname)** gekennzeichnet sind, stehen in keinem Artefakt und werden in WP-M9b-01 festgelegt.
- Im Kern-DDL (`kernschema.sql`) vorhanden: u. a. `vehicle`, `vehicle_run`, `capacity_hold`, `booking`, `stop_revision`, `service`, `service_area`, `config_revision`, `journey`, `journey_leg`, `operation`, `saga_step`, `partner_contract_version`, `cooperation_grant`, `resource_release`, `staff_shift`, `economic_event`, `cost_object`, `settlement_line`, `tenant`, `legal_entity`, `organization`, `outbox`, `inbox`, `audit_event`. Nur als Namen in Kap. 04 §4.4: u. a. `charging_slot`, `position_sample`, `realtime_observation`, `transfer_hub`, `transfer_rule`, `inspection_run`, `inspection_answer`, `defect_case`, `repair_order`, `roster_revision`, `tariff_revision`, `extraction_run`, `extracted_field`, `forecast_scenario`, `settlement_statement`, `channel_template_revision`, `metric_definition_revision`, `aggregate_snapshot`, `eta_model_revision`, `model_approval`, `simulation_run`, `adapter_capability`, `alert_policy_revision`, `business_alert`, `support_case`. Tabellen mit dem Zusatz **(Arbeitsname)** nennt das Pflichtenheft nicht; sie sind Vorschläge und werden in WP-M9b-01 bestätigt.
- „Planungsdefault“ kennzeichnet Werte, die das Pflichtenheft selbst als vorläufig bezeichnet. Für die E-Mobilitätsparameter `min_battery_soc_pct`, `min_return_soc_pct` und `charge_reservation_min` nennt `kataloge.csv` Namen, Einheit und Ebene, aber keine Zahlenwerte; Tests verwenden gekennzeichnete Fixturewerte (WP-M9b-03).
- Die Zielstruktur des Repositorys kennt keinen Telematik-/Ladesystemadapter. Arbeitsannahme bis WP-M9b-01: `backend/adapters/telematics` (Arbeitsname).
- Größen: S bis 1, M bis 3, L bis 6 Personenwochen.
- **WP-IDs sind stabil:** Die Nachbareinheit M9a verweist namentlich auf WP-M9b-02, -22, -34, -41, -48, -54, -55 und -57, M8a auf WP-M9b-40. WPs werden deshalb nicht umnummeriert; ein später nötiges WP wird hinten angefügt.
- **Querschnittsregeln für jedes WP dieser Datei** (Bestandteil der Abnahme jedes WPs; Sammelnachweis in WP-M9b-57, Kriterium 6):
  - Datenbank: Jede Tabelle mit dem Zusatz (Arbeitsname) wird in dem WP, das sie zuerst als Liefergegenstand nennt, als eigene Flyway-Migration (expand/contract, Kap. 04 §4.7) geliefert – mit `(tenant_id,id)`, zusammengesetzten FKs, RLS über `current_tenant()`, Revision, Audit und Lösch-/Aufbewahrungsklasse (Kap. 04 §4.1, §4.4). Ausnahme: `charging_point`, `vehicle_energy_state` und `telemetry_reading` liefert WP-M9b-02. Zu jeder neuen Tabelle gehört ein Negativtest unter der nicht privilegierten Runtime-Rolle (kein Tenantkontext → keine Zeile; fremder FK abgewiesen; Muster PH-AT-001).
  - API: Jeder schreibende Endpunkt folgt Kap. 05 §5.1 (einheitliches Fehlermodell, `Idempotency-Key`, bei revisionierten Objekten `If-Match`); ein fremdes und ein nicht vorhandenes Objekt liefern eine gleichartige Antwort. Je Endpunkt nennt die Rechtematrix aus WP-M9b-01 die berechtigte Rolle; ein Aufruf ohne Recht wird serverseitig abgewiesen und als Sicherheitsereignis protokolliert.
  - Oberfläche: Jede neue oder erweiterte Ansicht besitzt die Zustände leer, lädt, Fehler, fehlendes Recht und veralteter Datenstand; Bedienung per Tastatur und Screenreader, Status nie nur farblich, Karte immer mit gleichwertiger Liste (Kap. 06, WCAG 2.2 AA als Abnahmeziel).
  - Audit/Ereignisse: Jeder fachliche Zustandswechsel schreibt `audit_event`; Ereignisse verlassen das Modul nur über `outbox` und werden beim Empfänger über `inbox` dedupliziert.
- Referenztests anderer Meilensteine (z. B. PH-AT-001, PH-AT-004, PH-AT-017, PH-AT-018, PH-AT-020, PH-AT-021, PH-AT-022, AT-03, AT-09, AT-14, AT-47, E2E-078, E2E-102) werden in dieser Einheit nicht erstmals bestanden; wo ein WP sie nennt, ist die **Regression** bzw. das Prüfmuster gegen die neue Funktion gemeint.

## Übersicht

| WP-ID | Titel | Anforderungen (Anzahl) | Größe | hängt ab von |
|---|---|---:|---|---|
| WP-M9b-01 | Detailverträge und Zustandsautomaten M9b | – (Enabler) | M | WP-M0-31, WP-M0-13, WP-M0-04, WP-M8a-01, WP-M8b-01, WP-M8c-01, WP-M8d-01, WP-M8e-01, WP-M9a-01 |
| WP-M9b-02 | Migration der E-Mobilitäts- und Telemetrietabellen mit RLS | – (Enabler) | M | WP-M9b-01, WP-M1-01, WP-M0-15, WP-M3-03, WP-M3-08 |
| WP-M9b-03 | Referenzfixtures E-Flotte, Telematik- und Ladesystemsimulator | – (Enabler) | M | WP-M9b-02, WP-M0-24, WP-M0-22, WP-M5-10, WP-M5-11 |
| WP-M9b-04 | Release-Aufnahmeschalter für KANN-Funktionen | – (Enabler) | S | WP-M9b-01, WP-M1-20, WP-M1-21, WP-M1-22, WP-M8d-03, WP-M8d-05 |
| WP-M9b-05 | Energiestamm der E-Fahrzeuge und Umlaufsperre bei zu geringer Reichweite | 1 | M | WP-M9b-02, WP-M9b-03, WP-M3-03, WP-M3-08 |
| WP-M9b-06 | Telemetriewerte mit Quelle, Qualität, Zeitpunkt und Veraltungsregel | 1 | M | WP-M9b-02, WP-M9b-03, WP-M9b-05 |
| WP-M9b-07 | Telematik-/Ladesystemadapter mit Herkunft und Konfliktregeln | 1 | M | WP-M9b-06, WP-M6b-29, WP-M6a-05, WP-M6a-17 |
| WP-M9b-08 | Ladepunktverwaltung und Kompatibilitätsprüfung | 1 | M | WP-M9b-02, WP-M9b-05, WP-M1-09, WP-M7c-46, WP-M7c-48 |
| WP-M9b-09 | E-Fahrzeugdisposition mit Ladezustand, Energieprognose und Reserve | 1 | L | WP-M9b-05, WP-M9b-06, WP-M9b-08, WP-M2-17, WP-M2-22, WP-M2-23, WP-M2-16, WP-M7a-34, WP-M7a-36 |
| WP-M9b-10 | Ladeplanung mit Standzeiten, Diensten und Strombedarf | 1 | L | WP-M9b-08, WP-M9b-09, WP-M7d-04, WP-M7d-12 |
| WP-M9b-11 | Vorbelegung von Prüfpunkten aus Telematik | 1 | M | WP-M9b-06, WP-M3-13, WP-M3-14, WP-M3-08, WP-M3-24, WP-M7c-18, WP-M7c-17 |
| WP-M9b-12 | Diagnosecodes und Servicemeldungen als Wartungsempfehlung | 1 | M | WP-M9b-07, WP-M7c-35, WP-M7c-50, WP-M7c-53, WP-M7c-46 |
| WP-M9b-13 | Versionierte Adapter für Werkstatt-, Telematik- und Versicherungsdienste | 1 | M | WP-M9b-04, WP-M9b-07, WP-M7c-34, WP-M7c-35, WP-M7c-36 |
| WP-M9b-14 | Ersatzfahrzeugvorschlag bei Ausfall | 1 | M | WP-M9b-04, WP-M3-53, WP-M3-54, WP-M3-40, WP-M7b-04, WP-M7b-14 |
| WP-M9b-15 | Fuhrparkauswertungen: Fahrleistung, Energieverbrauch, Stillstände | 1 | M | WP-M9b-05, WP-M8a-14, WP-M8a-30, WP-M8a-32, WP-M8c-04, WP-M8c-27 |
| WP-M9b-16 | Schadenstatistik je Fahrzeugtyp ohne Verschuldensableitung | 1 | M | WP-M7c-34, WP-M7c-35, WP-M3-21, WP-M8c-04, WP-M8c-11 |
| WP-M9b-17 | Eco-Driving-Hinweise in der Driver-App | 1 | M | WP-M9b-04, WP-M3-24, WP-M3-26, WP-M8b-06, WP-M8b-07, WP-M8b-14 |
| WP-M9b-18 | Geprüftes Kandidatennetz für dynamische Abholpunkte | 1 | M | WP-M9b-01, WP-M2-05, WP-M7a-21, WP-M7a-22 |
| WP-M9b-19 | Bewertung von Haltestellenalternativen mit Kriterienprotokoll | 1 | M | WP-M9b-18, WP-M2-10, WP-M7a-24 |
| WP-M9b-20 | Dynamische Haltestellenwahl in der Disposition | 1 | L | WP-M9b-18, WP-M9b-19, WP-M2-17, WP-M2-22, WP-M2-23, WP-M1-29, WP-M7a-24, WP-M7a-32 |
| WP-M9b-21 | Fahrgastauswahl sicherer Abholpunkte mit Fußwegvergleich | 1 | M | WP-M9b-04, WP-M9b-20, WP-M2-35, WP-M7a-37, WP-M7a-38 |
| WP-M9b-22 | Prognosegestützte Fahrzeugpositionierung mit Fallback ohne Prognose | 1 | M | WP-M9a-06, WP-M2-17, WP-M2-16, WP-M2-20, WP-M7a-35, WP-M7a-32 |
| WP-M9b-23 | Repositionierungs- und Ladeempfehlungen für die Leitstelle | 2 | L | WP-M9b-04, WP-M9b-10, WP-M9b-22, WP-M7b-13, WP-M7b-10, WP-M8d-20, WP-M8d-21 |
| WP-M9b-24 | Warteliste und Alternativangebote bei fehlender Kapazität | 1 | L | WP-M9b-04, WP-M2-22, WP-M2-23, WP-M2-26, WP-M2-11, WP-M7a-03, WP-M7a-04, WP-M7a-12, WP-M7b-18 |
| WP-M9b-25 | Gespeicherter, verknüpfter Reiseplan mit Segmentverfolgung | 2 | L | WP-M9b-04, WP-M6a-13, WP-M6a-18, WP-M6a-19, WP-M7a-38, WP-M7a-15 |
| WP-M9b-26 | Alternative Reiseketten bei Anschlussbruch mit Supportfall | 1 | M | WP-M6a-09, WP-M6a-19, WP-M6a-13, WP-M6a-14, WP-M6b-27, WP-M7b-20 |
| WP-M9b-27 | Regelbasierte Wartezeitgrenzen Linie → On-Demand | 1 | M | WP-M9b-26, WP-M6a-19, WP-M6a-20, WP-M6a-18, WP-M6b-27 |
| WP-M9b-28 | Priorisierte Ersatzvorschläge im Störungsfall | 1 | M | WP-M2-21, WP-M2-22, WP-M3-53, WP-M3-54, WP-M7b-20, WP-M7b-26, WP-M7b-32 |
| WP-M9b-29 | Automatisierte Aktivierung von Sonderbetriebsplänen | 1 | M | WP-M1-20, WP-M1-22, WP-M2-03, WP-M2-04, WP-M8d-20, WP-M8d-21, WP-M8d-34 |
| WP-M9b-30 | Gemeinsame Ersatzkapazitäten und Partnerübernahmeanfrage | 1 | L | WP-M9b-31, WP-M6a-13, WP-M6a-14, WP-M6a-15, WP-M6b-04, WP-M6b-05, WP-M6a-05, WP-M6a-07, WP-M7b-12, WP-M7b-14 |
| WP-M9b-31 | Fahrzeugkontingente für die Verbundvermittlung | 1 | M | WP-M2-22, WP-M2-14, WP-M6a-06, WP-M6b-05, WP-M6b-16 |
| WP-M9b-32 | Partnerbezogene Leistungsgüte und täglicher SLA-Bericht | 1 | M | WP-M6a-13, WP-M6a-14, WP-M6a-07, WP-M6b-32, WP-M8c-04, WP-M8c-27 |
| WP-M9b-33 | Gemeinsamer Monatsbericht zu Qualitätszielen und SLA-Konditionen | 1 | M | WP-M9b-32, WP-M6c-23, WP-M6c-26, WP-M6c-20, WP-M8c-27, WP-M8c-04 |
| WP-M9b-34 | Tarif-Sandbox auf anonymisierten historischen Fahrten | 1 | M | WP-M9b-04, WP-M2-07, WP-M2-08, WP-M6c-06, WP-M9a-14, WP-M9a-15 |
| WP-M9b-35 | Redaktionsworkflow für Kommunikationsvorlagen | 1 | M | WP-M9b-04, WP-M2-32, WP-M2-33, WP-M7b-26, WP-M7b-33, WP-M7b-24 |
| WP-M9b-36 | Telefon-Sprachassistent mit Übergabe an das Callcenter | 1 | L | WP-M7b-17, WP-M7b-19, WP-M7b-20, WP-M7a-12 |
| WP-M9b-37 | Servicequalitätsbericht Kundenservice | 1 | S | WP-M9b-04, WP-M7b-20, WP-M8c-04, WP-M8c-11 |
| WP-M9b-38 | Versionierte Emissionsfaktoren und CO2e-Kennzahlen | 1 | M | WP-M8a-14, WP-M8a-30, WP-M8a-32, WP-M8c-04 |
| WP-M9b-39 | Nachhaltigkeitsbericht mit offengelegten Faktoren | 1 | M | WP-M9b-15, WP-M9b-38, WP-M8c-27, WP-M8c-28 |
| WP-M9b-40 | Flottenersatz- und Beschaffungsszenarien mit Lebenszykluskosten | 1 | M | WP-M9b-04, WP-M8a-32, WP-M8a-04, WP-M8a-21 |
| WP-M9b-41 | Getrennte Darstellung von Prognosequalität und Simulationspotenzial | 1 | M | WP-M8c-04, WP-M8c-32, WP-M9a-10, WP-M9a-17, WP-M9a-19 |
| WP-M9b-42 | Analyse Prognosegüte, Modellvertrauen und Verkehrsqualität | 1 | M | WP-M9b-46, WP-M8b-20, WP-M8c-22 |
| WP-M9b-43 | Anonymisierte regionale Benchmarks | 1 | M | WP-M8c-22, WP-M8c-11, WP-M1-07, WP-M8b-22, WP-M8b-29 |
| WP-M9b-44 | Lernbasierte Anomaliehinweise auf Auslastung und Kosten | 1 | M | WP-M8a-32, WP-M8a-34, WP-M8c-04, WP-M8c-13, WP-M8d-20, WP-M8d-21, WP-M8d-22, WP-M8d-23 |
| WP-M9b-45 | Zeitlich begrenztes Shadow-Testing neuer ETA-Modelle | 1 | M | WP-M8b-08, WP-M8b-11, WP-M8b-20 |
| WP-M9b-46 | Qualitätsfreigaben, Rollback und Verantwortliche für Modelländerungen | 1 | M | WP-M9b-45, WP-M8b-04, WP-M8b-05, WP-M8b-20 |
| WP-M9b-47 | Begründete automatische Besetzungsvorschläge | 1 | L | WP-M4-04, WP-M4-07, WP-M4-14, WP-M7d-04, WP-M7d-23, WP-M7d-14, WP-M7d-35 |
| WP-M9b-48 | KI-gestützte Personalbedarfsempfehlung | 1 | M | WP-M9b-47, WP-M9a-06, WP-M7d-32, WP-M7d-23 |
| WP-M9b-49 | Versionierte Arbeitszeitregelwerke je Mitarbeitergruppe | 1 | M | WP-M4-08, WP-M4-07, WP-M4-13, WP-M7d-35, WP-M7d-33, WP-M7d-09, WP-M8d-03, WP-M8d-14, WP-M1-19 |
| WP-M9b-50 | Konzernübersichten für Untermandanten und Unternehmensgruppen | 1 | M | WP-M1-05, WP-M1-06, WP-M1-01, WP-M6b-12, WP-M6b-10, WP-M6b-05, WP-M8a-32, WP-M8a-12, WP-M8c-04 |
| WP-M9b-51 | Mehrregionenbetrieb: Zeitzonen, Feiertage, Währungen, lokale Regeln | 1 | L | WP-M1-02, WP-M1-18, WP-M1-09, WP-M1-20, WP-M4-10, WP-M4-36, WP-M6b-10, WP-M6b-11, WP-M8d-03, WP-M8d-07, WP-M8d-08 |
| WP-M9b-52 | Zentral verteilte Unternehmensvorlagen mit geschützten Abweichungen | 1 | M | WP-M1-20, WP-M1-21, WP-M1-22, WP-M8d-05, WP-M8d-07 |
| WP-M9b-53 | Lernende OCR- und Kontierungsvorschläge für wiederkehrende Rechnungen | 1 | M | WP-M9b-04, WP-M8a-07, WP-M8a-09, WP-M4-27, WP-M4-28 |
| WP-M9b-54 | Ressourcenentkopplung von Prognose und Simulation | 1 | M | WP-M9b-03, WP-M5-10, WP-M5-11, WP-M5-03, WP-M5-04, WP-M8b-27, WP-M9a-14, WP-M9a-04, WP-M9a-09 |
| WP-M9b-55 | Gesonderte Abnahme von Simulation und KI gegen eine Baseline | 1 | M | WP-M9b-03, WP-M8b-04, WP-M8b-05, WP-M8b-20, WP-M9a-05, WP-M9a-06, WP-M9a-17, WP-M9a-10 |
| WP-M9b-56 | Revisionsfähige Releasefreigabe mit Sicherheits- und Datenschutzprüfung | 1 | M | WP-M0-12, WP-M0-14, WP-M0-31, WP-M0-32, WP-M8b-28, WP-M8b-26, WP-M8e-22, WP-M8e-30, WP-M8e-35 |
| WP-M9b-57 | Gate-Abnahme M9b: AT-20, AT-58 und Anteile an AT-21, AT-22, AT-55 | – (Enabler) | S | WP-M9b-05, WP-M9b-06, WP-M9b-08, WP-M9b-09, WP-M9b-11, WP-M9b-18, WP-M9b-22, WP-M9b-47, WP-M9b-48, WP-M9b-54, WP-M9a-06, WP-M9a-09, WP-M9a-12, WP-M9a-14, WP-M9a-17, WP-M5-01, WP-M5-31, WP-M7d-32, WP-M7d-20 |

## Arbeitspakete

### WP-M9b-01 – Detailverträge und Zustandsautomaten M9b
- **Ziel:** Für alle 54 Anforderungen liegen vor Codebeginn OpenAPI-Ergänzungen, DDL-Entwürfe, Zustandsautomaten und JSON-Schemata vor und sind in `arbeitspakete.csv` verknüpft (Leitlinie „Vertrag vor Code“, Kap. 08 §8.4).
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - API: OpenAPI-3.1-Ergänzungen je Modul für alle in dieser Datei als Arbeitsname gekennzeichneten Pfade sowie die Erweiterungen von `/v1/operator/vehicles`, `/v1/operator/incidents`, `/v1/stops`, `/v1/ride-offers`, `/v1/bookings`, `/v1/inspections`, `/v1/defects`, `/v1/repair-orders`, `/v1/rosters`, `/v1/analytics/metrics`, `/v1/reports/jobs`, `/v1/config/revisions`, `/v1/notifications`, `/v1/support/cases`, `/v1/eta/models/{id}/quality`, `/v1/scenarios`, `/v1/partner/capabilities`, `/v1/partner/offers`, `/v1/partner/reservations`, `/v1/clearing/statements`.
  - Datenbank: DDL-Entwürfe für `charging_slot`, `position_sample`-Erweiterung und alle Arbeitsnamen-Tabellen dieser Datei mit `(tenant_id,id)`, zusammengesetzten FKs, RLS, Revision und Audit (Kap. 04 §4.1, §4.4).
  - Zustandsautomaten: Ladeslot, Empfehlung (vorgeschlagen/angenommen/abgelehnt/abgelaufen), Wartelisteneintrag, Übernahmeanfrage, Vorlagenfreigabe, Modell-Shadowlauf, Releasefreigabe, Sonderbetriebsplan.
  - JSON-Schemata für die Parameter `min_battery_soc_pct`, `min_return_soc_pct`, `charge_reservation_min`, `sharing_vehicle_quota` sowie für alle neuen Parameter (siehe Offene Punkte 2).
  - Festlegung des Adaptermoduls `backend/adapters/telematics` (Arbeitsname) in der Zielstruktur.
  - Rechtematrix: je Endpunkt dieser Einheit die berechtigte Rolle und der Fall „fremdes oder nicht vorhandenes Objekt“ (gleichartige Antwort, Kap. 08 §8.5 PH-AT-001).
  - Katalognachtrag: Vorschlagsliste der in dieser Datei als neu gekennzeichneten Parameter und Kennzahldefinitionen (Offene Punkte 2 und 3) mit Typ, Einheit, Ebene, Zähler/Nenner zur Entscheidung durch den Auftraggeber; ohne Entscheidung wird kein neuer Name in `kataloge.csv` eingetragen.
  - Leseport-Verträge mit M9a: Prognose-Leseport und Simulations-Ergebnisport als Abnehmer (WP-M9b-22, -34, -41, -48) sowie die von M9b an WP-M9a-20 gelieferte Regelbibliothek Reichweite/Ladefenster.
- **Detailverträge vorab:** Dieses WP ist die Vertragsarbeit; Ergebnis je Anforderung: Verantwortlicher, Detailvertrag, mindestens ein positiver ausführbarer Testfall.
- **Akzeptanzkriterien:**
  1. `validate_artifacts.py` läuft mit den ergänzten Artefakten fehlerfrei.
  2. Jede der 54 Anforderungen hat in `arbeitspakete.csv` gefüllte Spalten `detailvertrag` und `testfall`.
  3. Jeder Arbeitsname dieser Datei ist entweder bestätigt oder durch den endgültigen Namen ersetzt; die Offenen Punkte dieser Datei sind entschieden oder an den Auftraggeber eskaliert.
  4. Die Leseport-Verträge zwischen M9a und M9b sind von beiden Einheiten gegengezeichnet (Gegenstück: WP-M9a-01); für `LH-6.10-PAY-017` ist festgelegt, dass WP-M9b-34 nur die Simulationsumgebung, nicht die KANN-Tarifsimulation aus M9a voraussetzt.
  5. Die Rechtematrix deckt jeden Endpunkt dieser Datei ab; für jeden Zustandsautomaten sind unzulässige Übergänge als Negativbeispiel im Vertrag enthalten.
- **Tests:** Vertragslint in CI; Review durch Fachanalyse und Architektur.
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M0-31, WP-M0-13, WP-M0-04 (Detailvertragsprozess, Vorlage und CI-Vertragsprüfung)
  - extern: WP-M8a-01, WP-M8b-01, WP-M8c-01, WP-M8d-01, WP-M8e-01 (stabile P2-Verträge der Module als Erweiterungsbasis)
  - extern: WP-M9a-01 (Prognose-Leseport und Simulations-Ergebnisport als Vertrag (WP-M9a-01), gemeinsame Abstimmung)
- **Entscheidungen:** D-09 (Telemetrieanbieter, E-Ladeinfrastruktur) und D-12 (Aufnahme der KANN-Funktionen) blockieren den Bau nicht, aber die endgültige Festlegung der Adapterverträge bzw. die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Verträge der Prognose- und Simulationsdienste (M9a); Implementierung.

### WP-M9b-02 – Migration der E-Mobilitäts- und Telemetrietabellen mit RLS
- **Ziel:** Alle Tabellen des E-Mobilitätsstroms sind als Flyway-Migration vorhanden, mandantengetrennt und unter der Runtime-Rolle getestet.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Datenbank: Migration für `charging_slot` (Ladefenster/SoC, Kap. 04 §4.4), `charging_point` (Arbeitsname), `vehicle_energy_state` (Arbeitsname; SoC, Batteriekapazität, Reichweitenschätzung, Reservegrenze), `telemetry_reading` (Arbeitsname; Quelle, Qualität, Mess- und Empfangszeit), Erweiterung von `vehicle` um Antriebs-/Steckermerkmale; Index `(tenant_id,vehicle_id,measured_at DESC)` (Kap. 04 §4.7); RLS-Policies über `current_tenant()`; Exclusion Constraint gegen überlappende Ladeslots je Ladepunkt.
  - Invarianten (Kap. 04 §4.5): „Keine lokale Cross-Tenant-Referenz“, „Kein Mandantenkontext → keine Zeile“.
- **Detailverträge vorab:** DDL aus WP-M9b-01; Lösch- und Aufbewahrungsklasse je Tabelle.
- **Akzeptanzkriterien:**
  1. Migration läuft auf leerer Datenbank und auf einem M8-Stand mit Daten zweier Mandanten (expand/contract).
  2. Ohne Tenantkontext liefert jede neue Tabelle keine Zeile; ein FK auf ein Fahrzeug eines fremden Mandanten wird abgewiesen.
  3. Zwei überlappende Ladeslots auf demselben Ladepunkt lassen sich nicht gleichzeitig speichern.
- **Tests:** positiv: Migration und Rollforward; negativ: Cross-Tenant-FK, fehlender Kontext; konkurrierend: zwei parallele Transaktionen legen überlappende Ladeslots an, genau eine gewinnt. Regression des Prüfmusters PH-AT-001 (Detail-, Listen-, Such- und Exportzugriff auf fremde Energie-, Ladepunkt- und Telemetriedaten) unter der Runtime-Rolle.
- **Abhängigkeiten:** intern: WP-M9b-01.
  - extern: WP-M1-01, WP-M0-15 (RLS-Grundlage, Runtime-Rolle, Tenantkontext)
  - extern: WP-M3-03, WP-M3-08 (Fahrzeugstamm `vehicle`, `resource_release`)
- **Entscheidungen:** D-11 (Aufbewahrung der Telemetrie) blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Tabellen der übrigen WPs (jeweils dort als eigene Migration); Partitionierung der Telemetrie (erst nach gemessenem Volumen, Kap. 04 §4.7).

### WP-M9b-03 – Referenzfixtures E-Flotte, Telematik- und Ladesystemsimulator
- **Ziel:** Deterministische Testdaten und steuerbare Simulatoren machen die E-Mobilitäts- und Entkopplungstests reproduzierbar.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: Telematiksimulator und Ladesystemsimulator in `tests/acceptance` mit steuerbaren Fällen (veralteter Wert, widersprüchliche Quellen, Ausfall, Duplikat, verspätete Zustellung).
  - Fixtures: zwei Mandanten mit E- und Verbrennerfahrzeugen, Ladepunkten verschiedener Steckertypen, Umläufen unterschiedlicher Länge; gekennzeichnete Fixturewerte für `min_battery_soc_pct`, `min_return_soc_pct`, `charge_reservation_min`.
  - Lastprofil „Simulation unter Volllast“ für WP-M9b-54 in `tests/performance`.
- **Detailverträge vorab:** Fixturekatalog mit Szenario-IDs; Simulatorschnittstelle entsprechend Adaptervertrag aus WP-M9b-01.
- **Akzeptanzkriterien:**
  1. Jeder Simulatorfall ist per Szenario-ID wiederholbar und liefert identische Ergebnisse.
  2. Fixturewerte sind als Testwerte gekennzeichnet und nicht als fachliche Defaults in Konfigurationsschemata übernommen.
- **Tests:** Selbsttest der Simulatoren; Wiederholungslauf mit identischem Ergebnis.
- **Abhängigkeiten:** intern: WP-M9b-02.
  - extern: WP-M0-24, WP-M0-22 (Sandbox-Partner und Testinfrastruktur mit steuerbaren Fehlern)
  - extern: WP-M5-10, WP-M5-11 (Referenzlastprofil und SLO-Messung)
- **Entscheidungen:** D-05 (Peaklast) blockiert nur die Belastbarkeit des Lastprofils; D-09 nur die Nähe zum realen Telemetrieanbieter.
- **Größe:** M
- **Nicht im Umfang:** Fixtures für Prognose und Simulation (M9a).

### WP-M9b-04 – Release-Aufnahmeschalter für KANN-Funktionen
- **Ziel:** Jede der 14 KANN-Funktionen dieser Einheit ist je Mandant standardmäßig aus und nur nach dokumentierter Aufnahme ins Release aktivierbar (Umsetzungsplan §4, M9).
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Backend: Modul `config`; Funktionsschalter je KANN-Funktion als versionierter Konfigurationswert mit Vier-Augen-Freigabe.
  - Datenbank: `config_revision` (bestehend); neue Parameter (Arbeitsnamen, siehe Offene Punkte 2).
  - API: `/v1/config/effective`, `/v1/config/revisions`, `/v1/config/revisions/{revision_id}/publish`.
  - Oberfläche: Konfigurationseditor (Kap. 06 §6.8) zeigt Schalter mit Releasebeschluss-Referenz.
- **Detailverträge vorab:** Liste der 14 Schalter mit zugehörigem source_key; JSON-Schema.
- **Akzeptanzkriterien:**
  1. Ohne veröffentlichte Aktivierung liefert jeder Endpunkt einer KANN-Funktion eine fachliche Ablehnung und die Oberfläche blendet die Funktion aus.
  2. Aktivierung verlangt Referenz auf den Releasebeschluss und wird in `audit_event` protokolliert.
  3. Deaktivierung wirkt als neue Revision; laufende Vorgänge der Funktion bleiben lesbar.
- **Tests:** positiv: Aktivierung mit Beschlussreferenz; negativ: Aufruf bei ausgeschaltetem Schalter, Aktivierung ohne zweite Freigabe; Datentrennung: Aktivierung in Mandant A wirkt nicht in Mandant B. konkurrierend: Aktivierung und Deaktivierung desselben Schalters parallel – genau eine wirksame Revision, der Verlierer erhält einen Konflikt. Regression E2E-097 (unzulässige Konfiguration wird abgewiesen).
- **Abhängigkeiten:** intern: WP-M9b-01.
  - extern: WP-M1-20, WP-M1-21, WP-M1-22 (Konfigurationsregister mit Präzedenzkette und Vier-Augen-Freigabe)
  - extern: WP-M8d-03, WP-M8d-05 (vollständiger Parameterkatalog und Wirkungsbericht)
- **Entscheidungen:** D-12 (Umfang, Aufnahme von KANN-Funktionen) blockiert die Produktivschaltung jeder KANN-Funktion, nicht den Bau.
- **Größe:** S
- **Nicht im Umfang:** Die KANN-Funktionen selbst (WP-M9b-13, -14, -17, -21, -23, -24, -25, -34, -35, -37, -40, -53).

### WP-M9b-05 – Energiestamm der E-Fahrzeuge und Umlaufsperre bei zu geringer Reichweite
- **Ziel:** E-Fahrzeuge führen Ladestand, Batteriekapazität, Reichweitenschätzung und Reservegrenze; ein Umlauf, den die Reichweite nicht deckt, wird für dieses Fahrzeug gesperrt.
- **Anforderungen:** `LH-6.8-FLT-009`
- **Liefergegenstände:**
  - Backend: Modul `fleet-safety`; Aggregat Fahrzeug-Energiezustand; Prüfung „Umlauf passt zur Reichweite abzüglich Reserve“ als Leseport für `booking-dispatch` und die Einsatzfreigabe; die Prüfregel ist als seiteneffektfreie Regelbibliothek gekapselt, damit WP-M9a-20 (E-Flotten-Simulation) sie ohne Live-Zugriff wiederverwenden kann.
  - Datenbank: `vehicle`, `vehicle_energy_state` (Arbeitsname), `resource_release`, `vehicle_run`.
  - API: Erweiterung `/v1/operator/vehicles` um Energiefelder; Stammdatenpflege (Arbeitsname `/v1/vehicles/{id}/energy`).
  - Oberfläche: UI-11 (Energiestamm je Fahrzeug), UI-07 (Fahrzeugliste zeigt SoC, Reichweite, Sperrgrund).
  - Ereignisse/Outbox: Energiezustand geändert; Umlauf wegen Reichweite gesperrt.
  - Konfigurationsparameter: `min_battery_soc_pct`, `min_return_soc_pct`.
- **Detailverträge vorab:** DDL und Felddefinitionen (Einheit kWh, km, Prozent); Formel der Reichweitenprüfung; Fehlercode der Sperre.
- **Akzeptanzkriterien:**
  1. Ladestand, Batteriekapazität, Reichweitenschätzung und Reservegrenze sind je E-Fahrzeug speicherbar, versioniert und über die API lesbar (FLT-009).
  2. Ein Testumlauf, dessen Strecke die Reichweite abzüglich Reserve übersteigt, wird für dieses Fahrzeug gesperrt; die Ablehnung nennt Grund und Rechenwerte (FLT-009).
  3. Ein Verbrennerfahrzeug ist von der Prüfung nicht betroffen.
  4. Nur eine berechtigte Fuhrparkrolle ändert Batteriekapazität und Reservegrenze; jede Änderung und jede Umlaufsperre ist mit Akteur, Zeitpunkt und Rechenwerten auditiert. Fehlt bei einem E-Fahrzeug ein Energiewert, gilt es für die Reichweitenprüfung als nicht nachgewiesen (sichere Seite), nicht als ausreichend geladen.
- **Tests:** positiv: ausreichende Reichweite → Umlauf zulässig; negativ: zu geringe Reichweite → Sperre, ungültige Werte (SoC > 100) abgewiesen; konkurrierend: SoC-Aktualisierung und Umlaufzuweisung parallel, kein Umlauf wird auf Basis eines überholten Zustands freigegeben (Revisionsvergleich, Kap. 04 §4.5 „Kapazität/Qualifikation/Fahrzeugfreigabe“); Datentrennung: Energiezustand fremder Mandanten nicht lesbar. Ermöglicht AT-20 und AT-58. Regression des Prüfmusters PH-AT-020 (synchroner Readiness-/Versionscheck vor neuer Zuweisung).
- **Abhängigkeiten:** intern: WP-M9b-02, WP-M9b-03.
  - extern: WP-M3-03, WP-M3-08 (Fahrzeugstamm und Einsatzfreigabe `resource_release`)
- **Entscheidungen:** D-09 blockiert nur die Produktivschaltung mit realen Telemetriequellen.
- **Größe:** M
- **Nicht im Umfang:** Herkunft und Alter der Werte (WP-M9b-06); Dispositionswirkung in der Online-Einfügung (WP-M9b-09).

### WP-M9b-06 – Telemetriewerte mit Quelle, Qualität, Zeitpunkt und Veraltungsregel
- **Ziel:** Jeder Telemetriewert trägt Quelle, Qualität und Messzeit; ein veralteter Batteriestand führt zu Warnung oder sicherer Fallback-Regel statt zu stiller Weiterverwendung.
- **Anforderungen:** `LH-6.8-FLT-012`
- **Liefergegenstände:**
  - Backend: Modul `fleet-safety`; Veraltungsprüfung und Fallback-Regel (konservative Reichweitenannahme oder Sperre, konfigurierbar).
  - Datenbank: `telemetry_reading` (Arbeitsname), `position_sample` (Mess-/Empfangszeit, Qualität, Kap. 04 §4.4), `vehicle_energy_state` (Arbeitsname).
  - API: Energiefelder in `/v1/operator/vehicles` mit Quelle, Messzeit, Qualitätsstatus.
  - Oberfläche: UI-07 zeigt Alter und Quelle des Batteriestands, Status „veraltet“ nicht nur als Farbe.
  - Ereignisse/Outbox: Telemetrie veraltet; Alarm über `business_alert`.
  - Konfigurationsparameter: Höchstalter für Batteriewerte (Arbeitsname, siehe Offene Punkte 2); `eta_max_data_age_sec` gilt nur für GPS und wird nicht umgedeutet.
- **Detailverträge vorab:** Wertebereich der Qualität; Definition „veraltet“; Fallback-Regel als Zustandsautomat.
- **Akzeptanzkriterien:**
  1. Jeder gespeicherte Telemetriewert besitzt Quelle, Qualität und Messzeitpunkt; Werte ohne diese Angaben werden abgewiesen (FLT-012).
  2. Ein Batteriestand älter als das konfigurierte Höchstalter erzeugt eine Warnung oder aktiviert die konfigurierte sichere Fallback-Regel; der veraltete Wert wird nicht als aktuell angezeigt (FLT-012).
  3. Verspätet eintreffende ältere Messungen überschreiben keinen neueren Zustand.
  4. Die Veraltungsregel wird serverseitig ausgewertet; ein Client kann einen Wert weder als frisch markieren noch die Messzeit überschreiben. Auslösung der Warnung bzw. des Fallbacks ist auditiert.
- **Tests:** positiv: frischer Wert wird wirksam; negativ: veralteter Wert → Warnung/Fallback, Messung ohne Quelle abgewiesen; konkurrierend: zwei Messungen in umgekehrter Reihenfolge, der neuere Messzeitpunkt bleibt wirksam. Ermöglicht AT-58. Datentrennung: Telemetrie fremder Mandanten nicht lesbar. Regression der Prüfmuster PH-AT-017 (Reihenfolge und Verspätung von Events) und AT-10 (Veraltet-Kennzeichnung statt fiktivem Livewert).
- **Abhängigkeiten:** intern: WP-M9b-02, WP-M9b-03, WP-M9b-05.
- **Entscheidungen:** D-09 (Telemetrieanbieter) blockiert nur die Produktivschaltung; D-11 die Aufbewahrungsdauer.
- **Größe:** M
- **Nicht im Umfang:** Konfliktauflösung zwischen parallelen Quellen (WP-M9b-07).

### WP-M9b-07 – Telematik-/Ladesystemadapter mit Herkunft und Konfliktregeln
- **Ziel:** Mehrere parallele Telemetrie- und Ladesystemquellen werden über versionierte Adapter angebunden; widersprüchliche Batterieinformationen werden nach dokumentierter Regel aufgelöst und nie blind kombiniert.
- **Anforderungen:** `LH-6.17-INT-013`
- **Liefergegenstände:**
  - Adapter/Worker: `backend/adapters/telematics` (Arbeitsname) mit Inbox-Deduplikation, je Quelle fixierter Vertragsversion (Kap. 02: keine Laufzeitauflösung über `latest`).
  - Backend: Konfliktregel (Quellenrang, Aktualität, Qualitätsstatus) und Zustand „Konflikt ungeklärt“ mit sicherem Fallback.
  - Datenbank: `telemetry_reading` (Arbeitsname), `adapter_capability`, `inbox`.
  - API: eingehender Adapterendpunkt (Arbeitsname `/v1/telematics/{source}/events`) mit quellenspezifischer Signatur.
  - Ereignisse/Outbox: Quellenkonflikt erkannt.
  - Konfigurationsparameter: Quellenrangfolge (Arbeitsname, siehe Offene Punkte 2).
- **Detailverträge vorab:** Adaptervertrag, Konfliktregel als Entscheidungstabelle, Toleranzschwelle für „widersprüchlich“.
- **Akzeptanzkriterien:**
  1. Zwei Quellen liefern für dasselbe Fahrzeug abweichende Batteriestände: der wirksame Wert stammt nachweislich aus genau einer Quelle gemäß Regel; es wird kein Mittelwert gebildet; beide Rohwerte bleiben mit Herkunft gespeichert (INT-013).
  2. Liegt die Abweichung über der Toleranz und ist keine Regel eindeutig, gilt der Zustand „Konflikt ungeklärt“ mit sicherem Fallback und Hinweis an die Leitstelle (INT-013).
  3. Eine doppelt zugestellte Nachricht erzeugt genau einen Messwert.
- **Tests:** positiv: Einzelquelle; negativ: widersprüchliche Quellen, ungültige Signatur, unbekannte Quelle; konkurrierend: zwei Quellen liefern gleichzeitig, deterministisches Ergebnis; Datentrennung: Quelle von Mandant A kann kein Fahrzeug von Mandant B aktualisieren. Regression AT-24 (doppelte und verspätete Events führen zu konsistentem Endstatus).
- **Abhängigkeiten:** intern: WP-M9b-06.
  - extern: WP-M6b-29, WP-M6a-05, WP-M6a-17 (Adapterrahmen, Capability Registry, Inbox/Signaturprüfung)
- **Entscheidungen:** D-09 (Telemetrieanbieter, E-Ladeinfrastruktur) blockiert die Produktivschaltung und die Auswahl realer Adapter; Bau gegen Simulator möglich.
- **Größe:** M
- **Nicht im Umfang:** Diagnosecodes (WP-M9b-12); Werkstatt-/Versicherungsadapter (WP-M9b-13).

### WP-M9b-08 – Ladepunktverwaltung und Kompatibilitätsprüfung
- **Ziel:** Ladepunkte sind mit Steckertyp, Leistung, Verfügbarkeit und Standort gepflegt; vorgeschlagen werden nur technisch kompatible Ladepunkte.
- **Anforderungen:** `LH-6.8-FLT-010`
- **Liefergegenstände:**
  - Backend: Modul `fleet-safety`; Aggregat Ladepunkt mit Revision; Kompatibilitätsprüfung Fahrzeug ↔ Ladepunkt; Vorschlagsabfrage nach Standort und Verfügbarkeit.
  - Datenbank: `charging_point` (Arbeitsname), `charging_slot`, `vehicle`.
  - API: Arbeitsnamen `/v1/charging-points`, `/v1/charging-points/{id}`, `/v1/vehicles/{id}/charging-options`.
  - Oberfläche: UI-11 (Ladepunktpflege), UI-07 (Ladealternative zum Fahrzeug).
  - Ereignisse/Outbox: Ladepunkt geändert; Verfügbarkeit geändert.
- **Detailverträge vorab:** Katalog der Steckertypen und Leistungsangaben; Kompatibilitätsregel; GeoJSON/SRID-Konvention für Standort.
- **Akzeptanzkriterien:**
  1. Ein Ladepunkt lässt sich mit Steckertyp, Leistung, Verfügbarkeit und Standort anlegen, ändern und stilllegen; Änderungen sind revisioniert (FLT-010).
  2. Die Vorschlagsabfrage für ein Fahrzeug enthält ausschließlich Ladepunkte mit kompatiblem Steckertyp; inkompatible und nicht verfügbare Ladepunkte fehlen (FLT-010).
  3. Ladepunkte anderer Mandanten erscheinen nie, auch nicht bei räumlicher Nähe.
  4. Ladepunkte pflegt nur eine berechtigte Fuhrpark-/Depotrolle; ist für ein Fahrzeug kein kompatibler verfügbarer Ladepunkt vorhanden, liefert die Abfrage eine begründete leere Liste statt eines inkompatiblen Vorschlags.
- **Tests:** positiv: kompatibler Vorschlag; negativ: inkompatibler Stecker, stillgelegter Ladepunkt, ungültige Geometrie; Datentrennung: fremder Ladepunkt nicht sichtbar. Ermöglicht AT-20 (Ladealternative). konkurrierend: Stilllegung eines Ladepunkts parallel zur Vorschlagsabfrage bzw. Slotreservierung – kein Vorschlag und keine Reservierung auf dem stillgelegten Ladepunkt.
- **Abhängigkeiten:** intern: WP-M9b-02, WP-M9b-05.
  - extern: WP-M1-09, WP-M7c-46, WP-M7c-48 (Betriebshöfe/Depots und Inventarstamm)
- **Entscheidungen:** D-09 (E-Ladeinfrastruktur) blockiert nur die Produktivschaltung mit realen Ladepunktdaten.
- **Größe:** M
- **Nicht im Umfang:** Slotplanung über die Zeit (WP-M9b-10); Echtzeitstatus aus Ladesystemen (WP-M9b-07).

### WP-M9b-09 – E-Fahrzeugdisposition mit Ladezustand, Energieprognose und Reserve
- **Ziel:** Die Disposition vergibt einem E-Fahrzeug keine Fahrt, die es mit Ladezustand, Energieprognose und Sicherheitsreserve nicht sicher leisten kann, und nennt eine Ladealternative.
- **Anforderungen:** `LH-6.5-OPT-021`
- **Liefergegenstände:**
  - Backend: Modul `booking-dispatch`; harter Constraint „Lade-/Reichweitenreserve“ im Constraint Validator (Kap. 01: Einfügeheuristik hält Lade-/Reichweitenreserven ein); Energieprognose je Einfügung; erneute Prüfung im Commit.
  - Adapter/Worker: `workers/routing` – Energiebedarf je Streckenabschnitt als Kostenmaß.
  - Datenbank: `vehicle_run`, `vehicle_energy_state` (Arbeitsname), `travel_attempt` (Ablehnungsgrund).
  - API: `/v1/ride-offers`, `/v1/bookings`, `/v1/runs/{id}/revisions`, `/v1/operator/vehicles`.
  - Oberfläche: UI-07 (Fahrzeug „nicht disponierbar – Reichweite“, Ladealternative).
  - Konfigurationsparameter: `min_battery_soc_pct`, `min_return_soc_pct`.
  - Kennzahlen: `Kapazitätsablehnungen` (Zuordnung des Ablehnungsgrundes, siehe Offene Punkte 5), `Fahrzeugverfügbarkeit`.
- **Detailverträge vorab:** Energieprognosemodell (Eingaben, Einheit, Unsicherheitszuschlag); Constraint-Definition; Ablehnungsgrund in `rejection_reason`.
- **Akzeptanzkriterien:**
  1. Ein Fahrzeug unter der Restreichweitenschwelle erhält keine neue Fahrt, deren prognostizierter Energiebedarf einschließlich Rückkehrreserve nicht gedeckt ist; eine passende kurze Fahrt bleibt möglich (OPT-021).
  2. Bei Ablehnung wegen Reichweite zeigt die Leitstelle das Fahrzeug als nicht disponierbar und nennt mindestens eine kompatible Ladealternative (OPT-021, AT-20).
  3. Manuelle Umplanung durch die Leitstelle kann den Constraint nicht überschreiben.
  4. Fällt die Energieprognose aus, gilt die sichere Fallback-Regel aus WP-M9b-06; Buchung mit anderen Fahrzeugen funktioniert weiter.
- **Tests:** positiv: ausreichender SoC; negativ: niedriger SoC vor langem Umlauf, manuelle Zuweisung trotz Sperre; konkurrierend: zwei parallele Buchungen auf dasselbe E-Fahrzeug, deren Summe die Reichweite übersteigt – genau eine wird bestätigt (Sperre der Run-Zeilen in stabiler Reihenfolge); Last: Solverlaufzeit mit Energie-Constraint innerhalb des Angebotsziels. Besteht AT-20; trägt zu AT-58 bei (korrekte Dispositionswirkung). Datentrennung: Energiezustand und Ladealternativen fremder Mandanten gehen nie in die Disposition ein. Regression PH-AT-004/AT-03 (letzter Platz) und PH-AT-020 mit aktivem Energie-Constraint.
- **Abhängigkeiten:** intern: WP-M9b-05, WP-M9b-06, WP-M9b-08.
  - extern: WP-M2-17, WP-M2-22, WP-M2-23, WP-M2-16 (Einfügeheuristik, Hold/Confirm, Constraint Validator)
  - extern: WP-M7a-34, WP-M7a-36 (erweiterte Disposition und Ablehnungsgründe)
- **Entscheidungen:** D-09 (Routing, Telematik) blockiert nur die Produktivschaltung.
- **Größe:** L
- **Nicht im Umfang:** Planung der Ladefenster (WP-M9b-10); Empfehlungen an die Leitstelle (WP-M9b-23).

### WP-M9b-10 – Ladeplanung mit Standzeiten, Diensten und Strombedarf
- **Ziel:** Ladefenster werden in Standzeiten unter Berücksichtigung von Diensten und Strombedarf geplant, ohne mit verbindlichen Fahrten zu kollidieren.
- **Anforderungen:** `LH-6.8-FLT-011`
- **Liefergegenstände:**
  - Backend: Modul `fleet-safety`; Ladeplaner (Vorschlag und Reservierung von `charging_slot`; Regel „Ladefenster/Reichweitenkonflikt“ als seiteneffektfreie Regelbibliothek, wiederverwendbar durch WP-M9a-20); Zustandsübergänge Ladeslot: geplant → reserviert → aktiv → abgeschlossen / storniert.
  - Datenbank: `charging_slot`, `charging_point` (Arbeitsname), `vehicle_run`, `staff_shift`.
  - API: Arbeitsnamen `/v1/charging-plans`, `/v1/charging-slots`.
  - Oberfläche: UI-11 (Ladeplan je Depot), UI-07 (Ladefenster in der Fahrzeugsicht).
  - Ereignisse/Outbox: Ladeslot reserviert/storniert; Konflikt Ladung ↔ Fahrt.
  - Konfigurationsparameter: `charge_reservation_min`, `min_return_soc_pct`.
- **Detailverträge vorab:** Zustandsautomat Ladeslot; Planungsregel (Strombedarf aus Folgeumlauf); Simulationsmodus des Planers.
- **Akzeptanzkriterien:**
  1. Der Planer erzeugt Ladefenster nur in Standzeiten; in einem Simulationslauf mit vorgegebenen Diensten überschneidet sich kein Ladefenster mit einer verbindlichen Fahrt (FLT-011).
  2. Der Puffer `charge_reservation_min` wird vor und nach dem Ladefenster eingehalten.
  3. Wird nachträglich eine verbindliche Fahrt eingefügt, die mit einem Ladeslot kollidiert, entsteht ein sichtbarer Konflikt; die Fahrtzusage bleibt unverändert.
  4. Der geplante Ladeumfang deckt den Energiebedarf der Folgedienste einschließlich `min_return_soc_pct`; reicht die Standzeit dafür nicht, weist der Plan die Unterdeckung je Fahrzeug aus statt sie zu verschweigen (FLT-011).
  5. Ladeplan und Reservierungen ändert nur eine berechtigte Rolle; jede Reservierung und Stornierung ist auditiert.
- **Tests:** positiv: Planung ohne Konflikt; negativ: kein freier kompatibler Ladepunkt → begründete Nichtplanbarkeit; konkurrierend: zwei Fahrzeuge reservieren denselben Slot, genau eine Reservierung gelingt; Ladeslot-Reservierung und Fahrteinfügung parallel, keine Überschneidung im Ergebnis. Datentrennung: Ladeslots und Ladepunkte fremder Mandanten weder sichtbar noch reservierbar.
- **Abhängigkeiten:** intern: WP-M9b-08, WP-M9b-09.
  - extern: WP-M7d-04, WP-M7d-12 (Dienst- und Schichtplan als Planungsgrundlage)
- **Entscheidungen:** D-09 blockiert nur die Produktivschaltung.
- **Größe:** L
- **Nicht im Umfang:** Lastmanagement des Netzanschlusses und Strompreisoptimierung (im Pflichtenheft nicht gefordert, siehe Offene Punkte 6); Empfehlungen an die Leitstelle (WP-M9b-23).

### WP-M9b-11 – Vorbelegung von Prüfpunkten aus Telematik
- **Ziel:** Prüfpunkte der Pflichtprüfung können mit Telematikwerten vorbelegt werden; die vorgeschriebene menschliche Sichtprüfung bleibt Pflicht.
- **Anforderungen:** `LH-6.21-CHK-029`
- **Liefergegenstände:**
  - Backend: Modul `fleet-safety`; Vorbelegung je Schritt der Prüfvorlage; Kennzeichnung „übernommen“; Sperrlogik bei SoC-/Reichweitenschwelle in der Prüfung.
  - Datenbank: `checklist_template_revision`, `checklist_step`, `inspection_run`, `inspection_answer` (Quelle, Messzeit, Vertrauensstatus), `telemetry_reading` (Arbeitsname).
  - API: `/v1/inspections`, `/v1/inspections/{id}/submit`.
  - Oberfläche: UI-09 (vorbelegter Wert mit Quelle, Messzeit, Vertrauensstatus; Bestätigung durch Fahrer).
  - Konfigurationsparameter: `vehicle_readiness_require_checks`, `min_battery_soc_pct`.
- **Detailverträge vorab:** Erweiterung der Vorlagenrevision (Schritt „telematikfähig“, „Sichtprüfung vorgeschrieben“); Wertebereich Vertrauensstatus.
- **Akzeptanzkriterien:**
  1. Ein übernommener Wert ist in Prüfung und Protokoll mit Quelle, Messzeit und Vertrauensstatus gekennzeichnet (CHK-029).
  2. Ein Schritt mit vorgeschriebener Sichtprüfung lässt sich nicht allein durch den Telematikwert abschließen (CHK-029).
  3. Ein E-Fahrzeug unter der Lade-/Reichweitenschwelle erzeugt in der Prüfung die konfigurationsgerechte Warnung oder Sperre; die Einsatzfreigabe bleibt serverbestätigt (AT-58).
  4. Veraltete oder ungeklärte Telemetrie wird nicht vorbelegt, sondern als „nicht verfügbar“ gezeigt.
- **Tests:** positiv: Vorbelegung und Bestätigung; negativ: Abschluss ohne Sichtprüfung, manipulierte Clientangabe zur Quelle; offline: Prüfung ohne Telemetrie bleibt durchführbar. Besteht AT-58 zusammen mit WP-M9b-05, -06, -09. konkurrierend: SoC fällt parallel zur Einreichung der Prüfung unter die Schwelle – die serverseitige Einsatzfreigabe verwendet den neueren Zustand; Datentrennung: Telematikwert eines fremden Fahrzeugs wird nie vorbelegt. Regression AT-26, AT-27 und AT-31 (keine unbestätigte serverseitige Einsatzfreigabe).
- **Abhängigkeiten:** intern: WP-M9b-06.
  - extern: WP-M3-13, WP-M3-14, WP-M3-08, WP-M3-24 (Pflichtcheck, Einsatzfreigabe, Driver-App)
  - extern: WP-M7c-18, WP-M7c-17 (Prüfversionen und Fahrzeugwechsel)
- **Entscheidungen:** D-09 blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Diagnosecodes (WP-M9b-12).

### WP-M9b-12 – Diagnosecodes und Servicemeldungen als Wartungsempfehlung
- **Ziel:** Diagnosecodes und Servicemeldungen von Herstellern oder Telematikprovidern erzeugen nachvollziehbare Wartungsempfehlungen.
- **Anforderungen:** `LH-I.10-AST-010`
- **Liefergegenstände:**
  - Backend: Modul `fleet-safety`; Qualifizierung eingehender Codes über eine gepflegte Zuordnungstabelle; Erzeugung einer Wartungsempfehlung.
  - Datenbank: `diagnostic_event` (Arbeitsname), `maintenance_recommendation` (Arbeitsname), Bezug zu `repair_order` und `defect_case`.
  - Adapter/Worker: Erweiterung `backend/adapters/telematics` (Arbeitsname).
  - API: Arbeitsname `/v1/vehicles/{id}/maintenance-recommendations`; Übernahme in `/v1/repair-orders`.
  - Oberfläche: UI-11 (Empfehlung mit Quelle und Zeitstempel, Übernahme in Werkstattauftrag).
  - Ereignisse/Outbox: Wartungsempfehlung erzeugt.
  - Konfigurationsparameter: `maintenance_reminder_km` (unverändert, zur Abgrenzung).
- **Detailverträge vorab:** Schema Diagnoseereignis; Definition „qualifizierter Diagnosecode“ (siehe Offene Punkte 7).
- **Akzeptanzkriterien:**
  1. Ein qualifizierter Diagnosecode bzw. eine qualifizierte Servicemeldung erzeugt genau eine Wartungsempfehlung mit Quelle und Zeitstempel; die Empfehlung lässt sich in einen Werkstattauftrag übernehmen oder begründet verwerfen (AST-010).
  2. Ein unbekannter oder nicht qualifizierter Code erzeugt keine Empfehlung, wird aber protokolliert.
  3. Die Empfehlung löst keine automatische Einsatzsperre aus; Sperren entstehen nur über den Schadenprozess.
- **Tests:** positiv: qualifizierter Code; negativ: unbekannter Code, Duplikat, Code für fremdes Fahrzeug; Datentrennung. konkurrierend: derselbe Code wird von zwei Quellen gleichzeitig zugestellt – genau eine Empfehlung.
- **Abhängigkeiten:** intern: WP-M9b-07.
  - extern: WP-M7c-35, WP-M7c-50, WP-M7c-53, WP-M7c-46 (Werkstattaufträge, Wartungsfristen, Inventar)
- **Entscheidungen:** D-09 (Telemetrieanbieter) blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Automatische Sperre aus Diagnosecodes; Kostenprognose der Wartung.

### WP-M9b-13 – Versionierte Adapter für Werkstatt-, Telematik- und Versicherungsdienste
- **Ziel:** Externe Werkstatt-, Telematik- oder Versicherungsdienste sind über versionierte Adapter mit minimalen Daten anbindbar; ein Schnittstellenfehler berührt weder Schadensmeldung noch Einsatzsperre.
- **Anforderungen:** `LH-6.22-DMG-028`
- **Liefergegenstände:**
  - Adapter/Worker: Adapterrahmen für Schadendienste mit Outbox-Zustellung, Retry und Circuit Breaker; je Adapter feste Vertragsversion und Feldfreigabeliste.
  - Datenbank: `adapter_capability`, `outbox`, `defect_case`, `repair_order` (externe Referenz).
  - API: Arbeitsname `/v1/defects/{id}/external-referrals`.
  - Oberfläche: UI-11 (Zustellstatus der externen Meldung).
  - Ereignisse/Outbox: Schaden an externen Dienst übergeben / Zustellung fehlgeschlagen.
- **Detailverträge vorab:** Datenminimierungsliste je Diensttyp; Adaptervertrag; Fehlerverhalten.
- **Akzeptanzkriterien:**
  1. Bei ausgefallenem oder fehlerhaftem externem Dienst lässt sich ein Schaden vollständig melden und eine Einsatzsperre setzen und aufheben; die externe Übergabe bleibt als ausstehend sichtbar (DMG-028).
  2. Der ausgehende Payload enthält ausschließlich die freigegebenen Felder des Adaptervertrags; keine Personalakte, keine Fahrgastdaten (DMG-028).
  3. Eine wiederholte Zustellung erzeugt beim Empfänger keine zweite Meldung (stabile Command-ID).
  4. Funktion ist ohne Release-Aufnahme (WP-M9b-04) aus.
- **Tests:** positiv: erfolgreiche Übergabe; negativ: Timeout, Fehlerantwort, unbekannte Adapterversion; Fault-Injection: Dienst dauerhaft aus – Schadenprozess unbeeinträchtigt; Datentrennung. konkurrierend: zwei Worker stellen dieselbe Outbox-Nachricht zu – genau eine externe Meldung; Sperre wird während eines hängenden externen Aufrufs gesetzt – keine Verzögerung der Sperre.
- **Abhängigkeiten:** intern: WP-M9b-04, WP-M9b-07.
  - extern: WP-M7c-34, WP-M7c-35, WP-M7c-36 (Schadenprozess, Werkstattaufträge, Wiederfreigabe)
- **Entscheidungen:** D-12 (Aufnahme KANN) und D-09 blockieren nur die Produktivschaltung; D-11 für die Weitergabe personenbezogener Daten.
- **Größe:** M
- **Nicht im Umfang:** Konkrete Anbindung eines benannten Anbieters (nicht im Pflichtenheft bestimmt).

### WP-M9b-14 – Ersatzfahrzeugvorschlag bei Ausfall
- **Ziel:** Bei Fahrzeugausfall schlägt das System Ersatzfahrzeuge nach Kapazität und Nähe vor und bewertet dabei vereinbarte Zeitfenster und Ausstattungsbedarf.
- **Anforderungen:** `LH-6.8-FLT-015`
- **Liefergegenstände:**
  - Backend: Modul `fleet-safety` mit Leseport in `booking-dispatch`; Vorschlagsliste mit Bewertung je Kandidat; Übernahme nur durch Disponent über Plan-CAS.
  - Datenbank: `vehicle`, `vehicle_run`, `resource_release`, `booking`, `vehicle_assignment`.
  - API: `/v1/operator/incidents` (Ersatzvorschläge zum Vorfall), `/v1/runs/{id}/revisions`.
  - Oberfläche: UI-07 („Ersatzoptionen prüfen“, Kap. 06 §6.5).
  - Konfigurationsparameter: `vehicle_unavailability_alert_min`.
- **Detailverträge vorab:** Bewertungsschema (Kapazitätsvektor, Entfernung, Zeitfenstererfüllung, Ausstattung).
- **Akzeptanzkriterien:**
  1. Für einen ausgefallenen Umlauf nennt der Vorschlag je Kandidat, welche vereinbarten Zeitfenster eingehalten würden und ob der Ausstattungsbedarf (z. B. Rollstuhlplatz) gedeckt ist (FLT-015).
  2. Gesperrte, nicht freigegebene oder fremde Fahrzeuge erscheinen nicht; die Rangfolge folgt nachvollziehbar Kapazität und Nähe, ein Kandidat mit veraltetem Standort ist als solcher gekennzeichnet (FLT-015).
  3. Der Vorschlag ändert keinen Plan; die Übernahme erfolgt als begründete Planrevision.
  4. Funktion ist ohne Release-Aufnahme (WP-M9b-04) aus.
- **Tests:** positiv: passender Ersatz; negativ: kein Kandidat → begründete Leere, Kandidat ohne Rollstuhlplatz bei Bedarf; konkurrierend: zwei Disponenten übernehmen denselben Ersatz, genau eine Planrevision gewinnt (412 beim Verlierer). Datentrennung: Fahrzeuge fremder Mandanten nie in der Vorschlagsliste. Regression AT-09 und AT-50 (Fahrzeugausfall mit Ersatzfluss).
- **Abhängigkeiten:** intern: WP-M9b-04.
  - extern: WP-M3-53, WP-M3-54, WP-M3-40 (Störungsablauf Fahrzeugausfall, manuelle Umplanung mit Plan-CAS)
  - extern: WP-M7b-04, WP-M7b-14 (erweiterte Leitstellenfunktionen)
- **Entscheidungen:** D-12 blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Partnerfahrzeuge als Ersatz (WP-M9b-30).

### WP-M9b-15 – Fuhrparkauswertungen: Fahrleistung, Energieverbrauch, Stillstände
- **Ziel:** Fuhrparkauswertungen liefern Fahrleistung, Energieverbrauch und Stillstände je Zeitraum vergleichbar und mit dokumentierter Berechnung.
- **Anforderungen:** `LH-6.8-FLT-013`
- **Liefergegenstände:**
  - Backend: Modul `analytics`; Kennzahldefinitionen mit Version, Nenner, Datenstand und Vollständigkeit.
  - Datenbank: `metric_definition_revision`, `aggregate_snapshot`, `dataset_watermark`.
  - API: `/v1/analytics/metrics`, `/v1/reports/jobs`, `/v1/reports/jobs/{id}`.
  - Oberfläche: UI-19 (Fuhrparkauswertung mit Zeitraumfilter), UI-15 (Verweis aus TCO).
  - Kennzahlen: `Strom kWh/100 km`, `Kraftstoff L/100 km`, `Produktive Kilometer`, `Leerkilometerquote`, `Fahrzeugverfügbarkeit`, `Ausfallminuten kritisch`, `Werkstattquote`.
- **Detailverträge vorab:** Definition „Stillstand“ (geplant/ungeplant); Berechnungsdokumentation je Kennzahl; Filterschema.
- **Akzeptanzkriterien:**
  1. Die Auswertung weist Fahrleistung, Energieverbrauch und Stillstände je Fahrzeug und Fahrzeugtyp aus; zwei verschiedene Zeiträume liefern für dasselbe Fahrzeug Werte nach identischer Definitionsversion; die Berechnungsgrundlage ist aus dem Bericht abrufbar (FLT-013).
  2. Energieverbrauch mischt keine unvereinbaren Einheiten (Liter und kWh getrennt, Kap. 03).
  3. Fehlender Nenner oder Datenlücke ergibt „nicht berechenbar“ bzw. Vollständigkeitsangabe statt Scheingenauigkeit.
  4. Auswertung und Export sind nur für berechtigte Rollen abrufbar; Empfängerrechte werden beim Export geprüft (Kap. 05 §5.7 Reports/KPIs).
- **Tests:** positiv: Zeitraumvergleich; negativ: Zeitraum ohne Daten, fehlende km-Basis; Datentrennung: Fahrzeuge fremder Mandanten nie enthalten. Regression der Prüfmuster PH-AT-021 (Nenner, Datenlücke) und E2E-084 (Statistik bei Datenlücke).
- **Abhängigkeiten:** intern: WP-M9b-05.
  - extern: WP-M8a-14, WP-M8a-30, WP-M8a-32 (validierte Tank-/Ladeereignisse und TCO je Fahrzeug)
  - extern: WP-M8c-04, WP-M8c-27 (Kennzahlregister, Berichtsjobs)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Emissionen (WP-M9b-38, -39); Schadenstatistik (WP-M9b-16).

### WP-M9b-16 – Schadenstatistik je Fahrzeugtyp ohne Verschuldensableitung
- **Ziel:** Die Schadenstatistik zeigt Häufigkeiten, Standzeiten, Kosten und wiederkehrende Schwachstellen je Fahrzeugtyp und leitet daraus kein Personalverschulden ab.
- **Anforderungen:** `LH-6.22-DMG-027`
- **Liefergegenstände:**
  - Backend: Modul `analytics` mit Leseprojektion aus `fleet-safety`; Aggregation ausschließlich auf Fahrzeugtyp/Bauteil.
  - Datenbank: `defect_case`, `repair_order`, `economic_event` (Kostenbezug), `metric_definition_revision`, `aggregate_snapshot`.
  - API: `/v1/analytics/metrics`, `/v1/reports/jobs`.
  - Oberfläche: UI-19 (Schadenstatistik mit Filtern), UI-11 (Einstieg).
  - Kennzahlen: `Werkstattquote`, `Ausfallminuten kritisch`.
  - Konfigurationsparameter: `kpi_aggregation_min_n`.
- **Detailverträge vorab:** Berichtsdefinition mit Filtern und Berechnungsgrundlage; Feldliste ohne Fahrer-/Melderbezug.
- **Akzeptanzkriterien:**
  1. Der Bericht zeigt Häufigkeiten, Standzeiten, Kosten und wiederkehrende Schwachstellen je Fahrzeugtyp, berücksichtigt gesetzte Filter (Zeitraum, Fahrzeugtyp, Schadenklasse) und weist die Berechnungsgrundlage aus (DMG-027).
  2. Der Bericht enthält weder Fahrer- noch Melderbezug und keine Rangfolge von Personen; eine Gruppierung nach Person ist in der API nicht verfügbar (DMG-027).
  3. Gruppen unter `kpi_aggregation_min_n` werden unterdrückt.
- **Tests:** positiv: Filterkombinationen; negativ: Versuch der Gruppierung nach Fahrer wird abgewiesen, Kleingruppe unterdrückt; Datentrennung. Differenzbildung benachbarter Filter legt keine Kleingruppe offen (Prüfmuster PH-AT-021); Kosten: Summe der Schadenkosten stimmt mit den referenzierten `economic_event` überein, keine Doppelzählung.
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M7c-34, WP-M7c-35, WP-M3-21 (Schäden, Werkstattaufträge, Bekanntschäden)
  - extern: WP-M8c-04, WP-M8c-11 (Kennzahlregister, Kleingruppenschutz)
- **Entscheidungen:** D-10 (Mitbestimmung) blockiert nur die Produktivschaltung, falls Auswertungen personenbeziehbar würden.
- **Größe:** M
- **Nicht im Umfang:** Fuhrparkauswertung (WP-M9b-15).

### WP-M9b-17 – Eco-Driving-Hinweise in der Driver-App
- **Ziel:** Fahrer erhalten effizienzorientierte, unverbindliche Hinweise, die Navigation und sicherheitskritische Bedienung nicht beeinträchtigen.
- **Anforderungen:** `LH-6.6-DRV-020`
- **Liefergegenstände:**
  - Backend: Modul `fleet-safety`; Erzeugung aggregierter Hinweise.
  - Datenbank: Datengrundlage ist im Pflichtenheft nicht festgelegt (siehe Offene Punkte 23); in Frage kommen `telemetry_reading` (Arbeitsname) und `driving_observation` nur im Rahmen ihrer Zweckbindung und Zugriffsklasse; keine neue personenbezogene Auswertungstabelle.
  - Oberfläche: UI-10 (Hinweis nur im Stillstand bzw. außerhalb der Fahrtansicht), `mobile/driver`.
  - API: Arbeitsname `/v1/driver/eco-hints`.
- **Detailverträge vorab:** Hinweiskatalog; Anzeigeregel (keine Anzeige während Fahrbewegung, Kap. 06 §6.7); Datengrundlage und Zweckbindung.
- **Akzeptanzkriterien:**
  1. Hinweise überdecken weder Navigation noch Check-in/out-Flächen und erscheinen nicht während der Fahrbewegung (DRV-020).
  2. Hinweise sind als nicht verpflichtend gekennzeichnet, abschaltbar und fließen in keine Leistungsbewertung oder Rangliste ein (DRV-020).
  3. Ohne Netz oder bei Ausfall des Hinweisdienstes bleibt die Fahrtansicht voll bedienbar.
  4. Funktion ist ohne Release-Aufnahme (WP-M9b-04) aus.
- **Tests:** positiv: Hinweis nach Fahrtende; negativ: Anzeigeversuch während Fahrt wird unterdrückt, Zugriff einer Personalrolle auf individuelle Hinweise verweigert; offline. Datentrennung: Hinweise eines Fahrers sind weder für andere Fahrer noch für fremde Mandanten abrufbar. Regression AT-11 (Offline Driver-App) und AT-54 (Bedienbarkeit per Screenreader).
- **Abhängigkeiten:** intern: WP-M9b-04.
  - extern: WP-M3-24, WP-M3-26 (Driver-App, Fahrtansicht)
  - extern: WP-M8b-06, WP-M8b-07, WP-M8b-14 (rechtmäßige Fahrbeobachtungen und Zugriffsklassen für Fahrerdaten)
- **Entscheidungen:** D-10 (Fahrerdaten, Mitbestimmung) blockiert die Produktivschaltung; D-12 die Aufnahme ins Release.
- **Größe:** M
- **Nicht im Umfang:** Fahrerbezogene Auswertung für Vorgesetzte (ausgeschlossen).

### WP-M9b-18 – Geprüftes Kandidatennetz für dynamische Abholpunkte
- **Ziel:** Kandidaten für dynamische Abholung stammen ausschließlich aus einem freigegebenen Haltepunktnetz; eine freie Kartenkoordinate wird nie zum verbindlichen Stopp.
- **Anforderungen:** `LH-6.9-GEO-009`
- **Liefergegenstände:**
  - Backend: Modul `service-geo`; Kennzeichnung und Freigabe von Kandidatenhaltepunkten als Haltepunktrevision; Auflösung „Koordinate → nächstgelegene freigegebene Kandidaten“.
  - Datenbank: `stop_revision` (Erweiterung Kandidatenmerkmal, Freigabestatus, Sicherheitsprüfung), `service_area`; Geo-GiST-Index.
  - API: `/v1/stops` (Filter Kandidatennetz), Freigabe über Revisionsworkflow.
  - Oberfläche: UI-17 (Kandidaten prüfen und freigeben).
  - Ereignisse/Outbox: Kandidatenrevision freigegeben/zurückgezogen.
  - Invarianten (Kap. 04 §4.5): „Nicht überlappende aktive Tarif-/Policy-/Dienstversion“ sinngemäß für wirksame Haltepunktrevisionen.
- **Detailverträge vorab:** DDL-Erweiterung `stop_revision`; Freigabeautomat; Regel für zurückgezogene Kandidaten mit bestehenden Buchungen.
- **Akzeptanzkriterien:**
  1. Eine Anfrage mit freier Kartenkoordinate ohne freigegebenen Kandidaten in zulässiger Entfernung führt zu keinem verbindlichen Stopp an dieser Koordinate; angeboten werden nur freigegebene Kandidaten oder eine begründete Ablehnung (GEO-009).
  2. Ein nicht freigegebener oder zurückgezogener Kandidat erscheint in keinem neuen Angebot (GEO-009).
  3. Jeder Stopp einer Buchung verweist auf eine wirksame freigegebene `stop_revision`.
  4. Kandidaten gibt nur eine berechtigte Rolle frei; Freigabe und Rückzug sind mit Akteur und Prüfvermerk auditiert. Bestehende Buchungen auf einem zurückgezogenen Kandidaten werden nach der im Detailvertrag festgelegten Regel sichtbar in die Klärung gegeben, nicht still belassen.
- **Tests:** positiv: Koordinate nahe freigegebenem Kandidat; negativ: Koordinate ohne Kandidat, Entwurfskandidat, ungültige Geometrie; konkurrierend: Rückzug eines Kandidaten parallel zur Angebotsbestätigung – keine neue Zusage auf zurückgezogenem Kandidaten; Datentrennung: Kandidaten fremder Mandanten nicht nutzbar.
- **Abhängigkeiten:** intern: WP-M9b-01.
  - extern: WP-M2-05 (Haltepunktrevisionen mit Geo-Validierung)
  - extern: WP-M7a-21, WP-M7a-22 (Gebiets- und Straßenrestriktionen)
- **Entscheidungen:** D-09 (reale Haltepunkte, barrierefreie Zugänge) blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Bewertung (WP-M9b-19) und Auswahl (WP-M9b-20).

### WP-M9b-19 – Bewertung von Haltestellenalternativen mit Kriterienprotokoll
- **Ziel:** Haltestellenalternativen werden gemeinsam nach Fußweg, Sicherheit und Fahrzeugumweg bewertet; die Kriterien je Bewertung sind protokolliert.
- **Anforderungen:** `LH-6.9-GEO-010`
- **Liefergegenstände:**
  - Backend: Modul `service-geo`; Bewertungsfunktion mit versionierter Gewichtung; Protokoll je Bewertung.
  - Datenbank: `stop_evaluation_log` (Arbeitsname), `route_matrix_cache`, `stop_revision`.
  - Adapter/Worker: `workers/routing` – Fußweg- und Umwegberechnung auf Basis des Routingadapters aus M2; ein darüber hinausgehendes Fußwegprofil (Qualität, Sicherheit des Wegs) ist in keiner Nachbareinheit enthalten und wird hier geliefert, soweit die Kriterien entschieden sind (siehe Offene Punkte 24).
  - Konfigurationsparameter: `stop_walk_max_m`, `max_detour_pct`; Gewichte (Arbeitsname, siehe Offene Punkte 2).
- **Detailverträge vorab:** Bewertungsformel und Kriterienliste; Schema des Protokolleintrags; Version der Gewichtung.
- **Akzeptanzkriterien:**
  1. Für zwei Alternativen liegt je ein Protokolleintrag mit Fußweg, Sicherheitsmerkmal, Fahrzeugumweg, Gewichtungsversion und Gesamtbewertung vor; die Rangfolge ist daraus reproduzierbar (GEO-010).
  2. Eine Alternative über `stop_walk_max_m` wird ausgeschlossen, nicht nur schlechter bewertet.
- **Tests:** positiv: zwei Alternativen mit unterschiedlicher Rangfolge je Gewichtung; negativ: Alternative über Fußweggrenze, fehlende Routingdaten → Bewertung „nicht möglich“ statt Annahme. Datentrennung: Protokoll und Kandidaten fremder Mandanten nicht lesbar; Reproduzierbarkeit: gleiche Eingaben und Gewichtungsversion ergeben identische Rangfolge.
- **Abhängigkeiten:** intern: WP-M9b-18.
  - extern: WP-M2-10 (Routingadapter mit Fußweg (Distanz/Gehzeit) und Matrix)
  - extern: WP-M7a-24 (Haltepunkteigenschaften und Ausschlussregeln (`stop_revision`, Barrierefreiheit); kein Fußwegrouting (M7a liefert es ausdrücklich nicht))
- **Entscheidungen:** D-09 blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Einbindung in die Tourenbildung (WP-M9b-20).

### WP-M9b-20 – Dynamische Haltestellenwahl in der Disposition
- **Ziel:** Die Disposition wählt dynamische Haltestellen aus dem freigegebenen Kandidatennetz nach Fußwegqualität und Bündelung und beachtet individuelle Mobilitätsrestriktionen.
- **Anforderungen:** `LH-6.5-OPT-019`
- **Liefergegenstände:**
  - Backend: Modul `booking-dispatch`; Erweiterung der Einfügeheuristik um Stoppauswahl; Constraint Validator prüft Freigabe und Restriktionen im Commit.
  - Adapter/Worker: `workers/routing`.
  - Datenbank: `route_stop`, `stop_revision`, `passenger_preference`, `booking`.
  - API: `/v1/ride-offers`, `/v1/bookings`.
  - Konfigurationsparameter: `stop_walk_max_m`, `pooling_allowed`, `max_detour_pct`.
- **Detailverträge vorab:** Erweiterung des Angebotsschemas (gewählter Stopp, Fußweg, Begründung); Constraint-Definition Mobilitätsrestriktion.
- **Akzeptanzkriterien:**
  1. Jeder gewählte Stopp ist ein freigegebener Kandidat (OPT-019).
  2. Für einen Fahrgast mit Mobilitätsrestriktion (z. B. Rollstuhl, maximaler Fußweg) wird kein Stopp gewählt, der die Restriktion verletzt, auch wenn er die Bündelung verbessern würde (OPT-019).
  3. Bereits zugesagte Zeitfenster anderer Fahrgäste bleiben harte Constraints.
  4. Die Auswahl ist je Angebot mit Kandidaten, Bewertung aus WP-M9b-19 (Fußwegqualität, Bündelungseffekt) und Gewichtungsversion protokolliert; ohne zulässigen Kandidaten erfolgt eine begründete Ablehnung in `travel_attempt` statt eines Ausweichens auf eine freie Koordinate.
- **Tests:** positiv: Bündelung zweier Anfragen an einem Kandidaten; negativ: bündelungsgünstiger Stopp verletzt Restriktion → nicht gewählt; konkurrierend: zwei parallele Buchungen auf denselben Umlauf mit dynamischer Stoppwahl, keine Überbuchung und keine Verletzung bestehender Zusagen; Last: Angebotsziel p95 eingehalten. Datentrennung: Kandidaten und Präferenzen fremder Mandanten gehen nie in die Auswahl ein. Regression AT-04 (Rollstuhltransport) und PH-AT-004/AT-03 (letzter Platz unter Konkurrenz).
- **Abhängigkeiten:** intern: WP-M9b-18, WP-M9b-19.
  - extern: WP-M2-17, WP-M2-22, WP-M2-23 (Einfügeheuristik, Hold/Confirm)
  - extern: WP-M1-29, WP-M7a-24, WP-M7a-32 (Fahrgastpräferenzen, erweiterte Disposition)
- **Entscheidungen:** D-09 blockiert nur die Produktivschaltung.
- **Größe:** L
- **Nicht im Umfang:** Auswahl durch den Fahrgast (WP-M9b-21).

### WP-M9b-21 – Fahrgastauswahl sicherer Abholpunkte mit Fußwegvergleich
- **Ziel:** Der Fahrgast kann zwischen mehreren geprüften sicheren Abholpunkten wählen und sieht den Fußwegvergleich.
- **Anforderungen:** `LH-6.3-PAX-017`
- **Liefergegenstände:**
  - Oberfläche: UI-01, UI-02 in `web` (passenger) und `mobile/passenger` (Liste und Karte der Abholpunkte mit Fußweg in Metern/Minuten, gleichwertige Listenalternative zur Karte).
  - API: `/v1/ride-offers` (mehrere Abholpunktoptionen je Angebot).
  - Backend: Modul `booking-dispatch` (Angebotsvarianten je Kandidat).
- **Detailverträge vorab:** Angebotsschema mit Abholpunktoptionen; Texte für Barrierefreiheit.
- **Akzeptanzkriterien:**
  1. Kein angebotener oder automatisch erzeugter Haltepunkt liegt außerhalb der freigegebenen Stopppunkte; ein manipuliertes Client-Request mit freier Koordinate als Abholpunkt wird serverseitig abgewiesen (PAX-017).
  2. Sofern mehrere freigegebene Kandidaten zulässig sind, werden mindestens zwei Abholpunkte angeboten; je Option werden Fußweg und Abholzeit vergleichbar angezeigt; Auswahl ist per Tastatur und Screenreader und ohne Karte möglich (PAX-017).
  3. Funktion ist ohne Release-Aufnahme (WP-M9b-04) aus.
- **Tests:** positiv: Auswahl zwischen zwei Abholpunkten; negativ: freie Koordinate im Request, abgelaufenes Angebot; Barrierefreiheit: Tastaturpfad. konkurrierend: zwei Fahrgäste wählen parallel Abholpunkte auf demselben Umlauf mit letztem freiem Platz – höchstens eine Zusage; Datentrennung: Abholpunkte fremder Mandanten erscheinen nie. Regression AT-19 (barrierefreier Buchungsweg ohne Kartenpflicht).
- **Abhängigkeiten:** intern: WP-M9b-04, WP-M9b-20.
  - extern: WP-M2-35, WP-M7a-37, WP-M7a-38 (Passenger-Web und native Passenger-App)
- **Entscheidungen:** D-12 blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Bewertungslogik (WP-M9b-19).

### WP-M9b-22 – Prognosegestützte Fahrzeugpositionierung mit Fallback ohne Prognose
- **Ziel:** Nachfrageprognosen dürfen die Positionierung unterstützen, überschreiben aber keine verbindliche Kapazitätsregel; ohne Prognose läuft die reguläre Online-Disposition unverändert weiter.
- **Anforderungen:** `LH-6.5-OPT-020`
- **Liefergegenstände:**
  - Backend: Modul `booking-dispatch`; optionaler Leseport „Nachfrageprognose“ mit Timeout und Circuit Breaker; Prognose wirkt nur als weiches Ziel nach erfüllten harten Constraints (Kap. 01: Unzulässigkeit muss null sein).
  - Ereignisse/Outbox: Prognosequelle nicht verfügbar (Alarm über `business_alert`).
  - Konfigurationsparameter: `min_capacity_reserve` (bleibt harte Regel); Schalter „Prognose in Positionierung nutzen“ (Arbeitsname, siehe Offene Punkte 2).
- **Detailverträge vorab:** Schnittstelle zum Prognosedienst (M9a); Definition, welche Regeln „verbindliche Kapazitätsregeln“ sind; Fallbackverhalten.
- **Akzeptanzkriterien:**
  1. Bei abgeschaltetem oder nicht antwortendem Prognosesystem funktionieren Angebotsbildung, Buchung und Online-Disposition ohne Fehler und innerhalb der Zielzeiten (OPT-020, AT-21).
  2. Eine Prognose, die eine Positionierung nahelegt, welche `min_capacity_reserve`, zugesagte Zeitfenster oder Sicherheitsregeln verletzen würde, bleibt wirkungslos (OPT-020).
  3. Der Einfluss der Prognose auf eine Positionierungsentscheidung ist protokolliert.
- **Tests:** positiv: Prognose verfügbar, Positionierungsziel berücksichtigt; negativ: Prognosedienst aus, Timeout, fehlerhafte Antwort; Fault-Injection: Prognosedienst fällt während laufender Buchungen aus – keine Buchung scheitert deswegen. Besteht AT-21 (Dispositionsseite). konkurrierend: Positionierungsziel und parallele Buchung beanspruchen dasselbe Fahrzeug – die Buchung wird nach den harten Regeln entschieden, `min_capacity_reserve` bleibt gewahrt; Datentrennung: Prognosewerte fremder Mandanten wirken nie.
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M9a-06 (Nachfrageprognose je Gebiet und Zeithorizont mit Modellversion)
  - extern: WP-M2-17, WP-M2-16, WP-M2-20 (Online-Disposition, Constraint Validator)
  - extern: WP-M7a-35, WP-M7a-32 (erweiterte Disposition)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Sichtbare Empfehlungen an die Leitstelle (WP-M9b-23); Prognosemodell selbst (M9a).

### WP-M9b-23 – Repositionierungs- und Ladeempfehlungen für die Leitstelle
- **Ziel:** Das System schlägt Repositionierung leerer Fahrzeuge und Ladevorgänge erklärbar und mit Kosten-/Qualitätseffekt vor; wirksam wird eine Empfehlung nur durch Entscheidung des Disponenten oder eine definierte Automation.
- **Anforderungen:** `LH-6.5-OPT-023`, `LH-6.7-OPS-015`
- **Liefergegenstände:**
  - Backend: Modul `booking-dispatch`; Aggregat Empfehlung mit Zuständen vorgeschlagen → angenommen / abgelehnt / abgelaufen; Erklärung (Prognosewert, Gebiet, Horizont, Modellversion) und geschätzter Kosten-/Qualitätseffekt.
  - Datenbank: `dispatch_recommendation` (Arbeitsname), `vehicle_run`, `charging_slot`, `alert_policy_revision` (für definierte Automation).
  - API: Arbeitsnamen `/v1/operator/recommendations`, `/v1/operator/recommendations/{id}/accept`, `/reject`.
  - Oberfläche: UI-07 (Empfehlungsliste mit Erklärung, Effekt, Annehmen/Ablehnen mit Begründung).
  - Ereignisse/Outbox: Empfehlung erzeugt/entschieden.
  - Kennzahlen: `Leerkilometerquote`, `Kosten pro Fahrzeugkilometer` (als Effektbezug).
- **Detailverträge vorab:** Zustandsautomat Empfehlung; Schema der Erklärung; Definition „definierte Automation“ als freigegebene Regelrevision (siehe Offene Punkte 8).
- **Akzeptanzkriterien:**
  1. Jede Repositionierungsempfehlung enthält eine nachvollziehbare Erklärung und kann vom Disponenten angenommen, abgelehnt oder ignoriert werden; ohne Entscheidung ändert sich kein Plan (OPT-023).
  2. Repositionierungs- und Ladeempfehlungen zeigen den geschätzten Kosten- und Qualitätseffekt; eine Empfehlung wird nur durch ausdrückliche Entscheidung oder eine freigegebene, versionierte Automationsregel wirksam, und beides ist protokolliert (OPS-015).
  3. Eine angenommene Empfehlung durchläuft dieselben harten Constraints wie eine manuelle Umplanung.
  4. Ohne Prognose entstehen keine Repositionierungsempfehlungen; die Leitstelle arbeitet normal weiter.
  5. Funktion ist ohne Release-Aufnahme (WP-M9b-04) aus.
- **Tests:** positiv: Annahme führt zu Planrevision; negativ: Annahme einer abgelaufenen Empfehlung, Empfehlung verletzt Constraint → Annahme abgewiesen; konkurrierend: zwei Disponenten entscheiden dieselbe Empfehlung, genau eine Entscheidung wirkt. Datentrennung: Empfehlungen fremder Mandanten weder sichtbar noch entscheidbar; Automation: eine nicht freigegebene oder zurückgezogene Automationsregel setzt keine Empfehlung um.
- **Abhängigkeiten:** intern: WP-M9b-04, WP-M9b-10, WP-M9b-22.
  - extern: WP-M7b-13, WP-M7b-10 (Leitstellenarbeitsliste und Livekarte)
  - extern: WP-M8d-20, WP-M8d-21 (Automatisierungsregeln mit Revision und Freigabe)
- **Entscheidungen:** D-12 blockiert nur die Produktivschaltung.
- **Größe:** L
- **Nicht im Umfang:** Vollautomatische Repositionierung ohne freigegebene Regel.

### WP-M9b-24 – Warteliste und Alternativangebote bei fehlender Kapazität
- **Ziel:** Bei fehlender Kapazität können Warteliste und Alternativangebote aktiviert werden; ein frei gewordener Platz wird nach definierten Regeln und ohne Überbuchung angeboten.
- **Anforderungen:** `LH-6.4-BKG-017`
- **Liefergegenstände:**
  - Backend: Modul `booking-dispatch`; Aggregat Wartelisteneintrag (wartend → angeboten → angenommen / abgelaufen / zurückgezogen); Nachrückregel; Angebot als befristeter Hold.
  - Datenbank: `waitlist_entry` (Arbeitsname), `capacity_hold`, `booking`, `travel_attempt`.
  - API: `/v1/ride-offers` (Alternativen), Arbeitsname `/v1/waitlist-entries`.
  - Oberfläche: UI-02 (Alternativen, Wartelistenangebot), UI-04 (Status des Wartelisteneintrags), UI-06 (Callcenter).
  - Ereignisse/Outbox: Platz frei geworden; Wartelistenangebot erstellt/abgelaufen; Benachrichtigung.
  - Konfigurationsparameter: Schalter und Nachrückregel (Arbeitsnamen, siehe Offene Punkte 2); `min_capacity_reserve`.
  - Kennzahlen: `Kapazitätsablehnungen` (Warteliste zählt weiterhin als ursprüngliche Ablehnung, siehe Offene Punkte 5).
  - Invarianten (Kap. 04 §4.5): „Kapazität/Qualifikation/Fahrzeugfreigabe“ – kein Überbuchen; Holdkonvertierung genau einmal.
- **Detailverträge vorab:** Zustandsautomat; Nachrückregel (Reihenfolge, Frist); Preis-/Angebotsrevision beim Nachrücken.
- **Akzeptanzkriterien:**
  1. Wird durch Storno ein Platz frei, erhält genau der nach Regel nächste passende Wartelisteneintrag ein befristetes Angebot; die Kapazität wird dabei per Hold in PostgreSQL reserviert und nie doppelt vergeben (BKG-017).
  2. Läuft das Angebot ab, geht der Platz an den nächsten Eintrag oder zurück in den freien Verkauf.
  3. Ein Wartelisteneintrag ist keine Zusage und wird dem Fahrgast nicht als Buchung dargestellt.
  4. Funktion ist ohne Release-Aufnahme (WP-M9b-04) aus.
  5. Warteliste und Alternativangebote sind je Dienst getrennt aktivierbar; ein Alternativangebot (andere Zeit oder anderer freigegebener Haltepunkt) ist ein reguläres Angebot mit eigenem Hold und eigener Zustimmung, kein Ersatz der abgelehnten Anfrage (BKG-017).
  6. Der Fahrgast kann seinen Wartelisteneintrag jederzeit zurückziehen; Benachrichtigungen zum Wartelistenangebot enthalten keine sensiblen Buchungsdetails und folgen den Kanalregeln aus M7a/M7b.
- **Tests:** positiv: Nachrücken nach Storno; negativ: Annahme nach Ablauf, Eintrag passt nicht (Rollstuhlplatz fehlt); konkurrierend: frei gewordener Platz wird gleichzeitig von regulärer Buchung und Wartelistenangebot beansprucht – genau eine Zusage; zwei Stornos und drei Wartende parallel. Datentrennung: Wartelisteneinträge fremder Mandanten und fremder Fahrgäste nicht lesbar. Regression PH-AT-004/AT-03 (letzter Platz), PH-AT-010 (abgelaufener Hold) und AT-12 (Callcenter kontra App) mit aktiver Warteliste.
- **Abhängigkeiten:** intern: WP-M9b-04.
  - extern: WP-M2-22, WP-M2-23, WP-M2-26, WP-M2-11 (Hold/Confirm, Storno, `travel_attempt`)
  - extern: WP-M7a-03, WP-M7a-04, WP-M7a-12, WP-M7b-18 (Buchungsänderungen, Callcenterbuchung, Benachrichtigungen)
- **Entscheidungen:** D-12 blockiert nur die Produktivschaltung.
- **Größe:** L
- **Nicht im Umfang:** Wartelisten für Partnersegmente der Föderation (im Pflichtenheft nicht geregelt, siehe Offene Punkte 9).

### WP-M9b-25 – Gespeicherter, verknüpfter Reiseplan mit Segmentverfolgung
- **Ziel:** Nutzer speichern einen mehrteiligen Reiseplan mit Anschluss und Zeitpuffer und verfolgen durchgehende Reiseketten in einer zeitlich sortierten Ansicht, die Statusänderungen der Segmente zeigt.
- **Anforderungen:** `LH-6.3-PAX-018`, `LH-6.11-PTI-012`
- **Liefergegenstände:**
  - Backend: Modul `journey-federation`; gespeicherter Reiseplan als Bezug auf `journey`/`journey_leg` einschließlich Informationssegmenten; Neubewertung betroffener Segmente bei Anschlussänderung.
  - Datenbank: `saved_travel_plan` (Arbeitsname), `journey`, `journey_leg`, `realtime_observation`, `transfer_rule`.
  - API: `/v1/federation/journeys/plan`, `/v1/federation/journeys/{journey_id}`; Arbeitsnamen `/v1/travel-plans`, `/v1/travel-plans/{id}/timeline`.
  - Oberfläche: UI-04 (gespeicherte Reisepläne, Zeitachse der Segmente mit Status und Datenstand).
  - Ereignisse/Outbox: Segmentstatus geändert; Anschluss geändert.
  - Konfigurationsparameter: `advance_arrival_buffer_min`, `transfer_min_time_min`.
- **Detailverträge vorab:** Schema Reiseplan (Segmente, Puffer); Ereignisschema der Zeitachse; Abgrenzung Plan (unverbindlich) ↔ gebuchte Reise; Lösch-/Aufbewahrungsklasse des gespeicherten Plans und Einbezug in Auskunft/Löschung (`/v1/privacy/requests`).
- **Akzeptanzkriterien:**
  1. Ein gespeicherter Plan mit Anschluss und Zeitpuffer wird bei einer Anschlussänderung so aktualisiert, dass die betroffenen Segmente neu bewertet und als geändert gekennzeichnet sind (PAX-018).
  2. Statusänderungen aller Segmente einer durchgehenden Reisekette erscheinen in einer zeitlich sortierten Ansicht mit Zeitpunkt und Quelle (PTI-012).
  3. Informationssegmente erhalten keinen fingierten Buchungsstatus; UNKNOWN bleibt als „wird geprüft“ sichtbar.
  4. Funktion ist ohne Release-Aufnahme (WP-M9b-04) aus.
  5. Der Nutzer kann einen gespeicherten Plan ändern und löschen; ein gelöschter Plan erscheint in keiner Ansicht und in keiner Benachrichtigung mehr.
- **Tests:** positiv: Plan speichern, Verspätung einspielen, Aktualisierung sehen; negativ: Zugriff auf fremden Reiseplan, veraltete Echtzeitquelle wird als veraltet gekennzeichnet; Reihenfolge: verspätet eintreffende Ereignisse werden korrekt einsortiert; Datentrennung. Regression der Prüfmuster PH-AT-017 (Reihenfolge und Verspätung von Events) und PH-AT-006 (Informationssegment ohne fingierte Buchung).
- **Abhängigkeiten:** intern: WP-M9b-04.
  - extern: WP-M6a-13, WP-M6a-18, WP-M6a-19 (Journey-Orchestrator, Reise und Segmente, Anschlussüberwachung)
  - extern: WP-M7a-38, WP-M7a-15 (Passenger-App und Reisebegleitung)
- **Entscheidungen:** D-12 blockiert nur die Produktivschaltung; D-04 (Anschlussrisiko) nur die Formulierung von Zusagen.
- **Größe:** L
- **Nicht im Umfang:** Alternativempfehlung bei Anschlussbruch (WP-M9b-26).

### WP-M9b-26 – Alternative Reiseketten bei Anschlussbruch mit Supportfall
- **Ziel:** Bei Anschlussbruch empfiehlt das System alternative Reiseketten oder begründet deren Nichtverfügbarkeit und verknüpft den Vorgang mit einem Supportfall.
- **Anforderungen:** `LH-6.11-PTI-010`
- **Liefergegenstände:**
  - Backend: Modul `journey-federation`; Erkennung Anschlussbruch → Alternativsuche über den Reiseplaner → Verknüpfung mit `support_case`.
  - Datenbank: `journey`, `journey_leg`, `realtime_observation`, `transfer_hub`, `support_case`.
  - API: `/v1/federation/journeys/plan`, `/v1/federation/journeys/{journey_id}`, `/v1/support/cases`.
  - Oberfläche: UI-04 (Alternative oder Begründung), UI-07 („Anschluss gefährdet“, „Fall eröffnen“), UI-06.
  - Ereignisse/Outbox: Anschluss gebrochen; Alternative vorgeschlagen; Benachrichtigung.
  - Kennzahlen: `Nicht erreichte Anschlüsse`.
- **Detailverträge vorab:** Definition „Anschlussbruch“; Schema der Empfehlung einschließlich „nicht verfügbar“ mit Grund; Regel zu Preis und Zustimmung.
- **Akzeptanzkriterien:**
  1. Eine Testverspätung, die den Anschluss bricht, zeigt mindestens eine alternative Reisekette oder eine begründete Nichtverfügbarkeit (PTI-010).
  2. Zu jedem Anschlussbruch besteht ein verknüpfter Supportfall mit Bezug zur Reise (PTI-010).
  3. Eine Alternative wird nicht automatisch gebucht; ein höherer Preis verlangt neue Zustimmung (Kap. 06 §6.4).
- **Tests:** positiv: Alternative vorhanden; negativ: keine Alternative, Partner nicht erreichbar, veraltete Echtzeitdaten; Idempotenz: wiederholtes Verspätungsereignis erzeugt keinen zweiten Supportfall. konkurrierend: zwei Verspätungsereignisse verschiedener Quellen treffen gleichzeitig ein – genau ein Supportfall und eine Empfehlung; Datentrennung: Supportfall und Alternativen nur für den berechtigten Mandanten bzw. Fahrgast sichtbar. Regression AT-14 (Anschlusswarnung).
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M6a-09, WP-M6a-19, WP-M6a-13, WP-M6a-14 (Reiseplaner, Anschlussüberwachung, Saga)
  - extern: WP-M6b-27 (GTFS-RT-/Partneradapter)
  - extern: WP-M7b-20 (Supportfälle)
- **Entscheidungen:** D-04 (Anschlussrisiko) blockiert die Produktivschaltung der Kostenfolgen; Bau mit Favorit möglich.
- **Größe:** M
- **Nicht im Umfang:** Warteentscheidung des On-Demand-Fahrzeugs (WP-M9b-27).

### WP-M9b-27 – Regelbasierte Wartezeitgrenzen Linie → On-Demand
- **Ziel:** Beim Übergang vom Linienverkehr zum On-Demand-Anschluss entscheidet eine konfigurierte Regel, ob gewartet wird; der Fahrgast wird informiert und bei Bedarf wird neu geplant.
- **Anforderungen:** `LH-I.5-FED-031`
- **Liefergegenstände:**
  - Backend: Modul `journey-federation` mit Auftrag an `booking-dispatch`; Warteentscheidung als protokollierter Regelentscheid; Neuplanung über bestehende Umplanung.
  - Datenbank: `transfer_rule` (Wartezeitgrenze), `journey_leg`, `realtime_observation`, `vehicle_run`.
  - API: `/v1/federation/journeys/{journey_id}`, `/v1/transfer-hubs`.
  - Oberfläche: UI-04 (Fahrgastinfo), UI-07 (Warteentscheidung sichtbar).
  - Ereignisse/Outbox: Warteentscheidung getroffen; Neuplanung ausgelöst; Fahrgastbenachrichtigung.
  - Konfigurationsparameter: `interchange_min_time_min`, `transfer_min_time_min`, `advance_arrival_buffer_min`, `max_wait_min`; maximale Anschlusswartezeit (Arbeitsname, siehe Offene Punkte 2).
- **Detailverträge vorab:** Entscheidungstabelle (Verspätung, Wartegrenze, Folgezusagen); Ereignisschema.
- **Akzeptanzkriterien:**
  1. Ein verspäteter Zug innerhalb der Wartegrenze führt zur Entscheidung „warten“ mit Fahrgastinfo; oberhalb der Grenze zur Entscheidung „nicht warten“ mit Fahrgastinfo und gegebenenfalls Neuplanung (FED-031).
  2. Warten verletzt keine zugesagten Zeitfenster anderer Fahrgäste; andernfalls wird nicht gewartet.
  3. Jede Entscheidung ist mit Regelversion und Eingangsdaten protokolliert.
- **Tests:** positiv: Verspätung unter/über Grenze; negativ: fehlende oder veraltete Echtzeitdaten → dokumentierte Standardentscheidung, kein Raten; konkurrierend: Warteentscheidung und neue Buchung auf demselben Umlauf parallel, bestehende Zusagen bleiben eingehalten. Datentrennung: Warteentscheidung nur auf eigenen Umläufen; Idempotenz: wiederholtes Verspätungsereignis erzeugt keine zweite Entscheidung. Regression AT-14.
- **Abhängigkeiten:** intern: WP-M9b-26.
  - extern: WP-M6a-19, WP-M6a-20, WP-M6a-18 (Anschlussüberwachung, Journey-Orchestrator)
  - extern: WP-M6b-27 (Fahrplan-/Echtzeitadapter)
- **Entscheidungen:** D-04 blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Anschlussgarantie und deren Kostenfolgen.

### WP-M9b-28 – Priorisierte Ersatzvorschläge im Störungsfall
- **Ziel:** Das Störungssystem erstellt für betroffene Fahrgäste priorisierte Ersatzvorschläge, deren Buchung keine harten Kapazitäts- und Zeitregeln verletzt.
- **Anforderungen:** `LH-6.13-SUP-012`
- **Liefergegenstände:**
  - Backend: Modul `communication-support` mit Leseport in `booking-dispatch`; Ermittlung betroffener Buchungen, Priorisierung, Ersatzangebot über den regulären Angebotskern.
  - Datenbank: `support_case`, `booking`, `booking_change`, `capacity_hold`.
  - API: `/v1/operator/incidents`, `/v1/support/cases`, `/v1/ride-offers`, `/v1/bookings`.
  - Oberfläche: UI-07 (Liste Betroffener mit Vorschlägen), UI-06, UI-04 (Vorschlag beim Fahrgast).
  - Ereignisse/Outbox: Ersatzvorschlag erstellt; Benachrichtigung.
- **Detailverträge vorab:** Priorisierungsregel (siehe Offene Punkte 10); Schema Ersatzvorschlag; Zustimmungsregel.
- **Akzeptanzkriterien:**
  1. Für jede von einer Störung betroffene Buchung entsteht ein priorisierter Ersatzvorschlag oder eine begründete Nichtverfügbarkeit (SUP-012).
  2. Jede Ersatzbuchung läuft über Hold/Confirm und verletzt keine harte Kapazitäts- oder Zeitregel; ein Vorschlag, der dies täte, wird nicht erzeugt bzw. bei Bestätigung abgewiesen (SUP-012).
  3. Die Reihenfolge der Vorschläge ist aus der protokollierten Regel reproduzierbar.
  4. Ein Ersatzvorschlag mit geänderter Zeit, geändertem Haltepunkt oder höherem Preis wird nur mit Zustimmung des Fahrgasts oder des berechtigten Vertreters gebucht; Unterstützungsbedarf der ursprünglichen Buchung bleibt harte Bedingung.
- **Tests:** positiv: Ersatz für drei Betroffene; negativ: keine Kapazität, Rollstuhlbedarf nicht deckbar; konkurrierend: mehr Betroffene als Plätze, parallele Annahme – keine Überbuchung. Datentrennung: betroffene Buchungen fremder Mandanten nie in der Liste. Regression AT-09 und AT-50.
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M2-21, WP-M2-22 (Angebots- und Kapazitätskern)
  - extern: WP-M3-53, WP-M3-54 (Störungsablauf Fahrzeugausfall)
  - extern: WP-M7b-20, WP-M7b-26, WP-M7b-32 (Supportfälle, Massenkommunikation)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Partnerübernahme (WP-M9b-30); Ersatzfahrzeugvorschlag (WP-M9b-14).

### WP-M9b-29 – Automatisierte Aktivierung von Sonderbetriebsplänen
- **Ziel:** Verkehrsangebote werden abhängig von Datum, Veranstaltung, Wetterlage oder Sonderbetriebsplan automatisch aktiviert und nach Ablauf zurückgenommen.
- **Anforderungen:** `LH-6.1-SYS-012`
- **Liefergegenstände:**
  - Backend: Module `config` und `service-geo`; Sonderbetriebsplan als freigegebene Dienst-/Konfigurationsrevision mit Wirksamkeitsintervall und Auslöser; Zustände Entwurf → freigegeben → wirksam → zurückgenommen.
  - Adapter/Worker: zeitgesteuerter Aktivierungs-/Rücknahmejob (idempotent, wiederanlaufsicher über `operation` mit Lease; holt nach Ausfall versäumte Aktivierungen und Rücknahmen in korrekter Reihenfolge nach).
  - Datenbank: `config_revision`, `service`, `special_operation_plan` (Arbeitsname).
  - API: `/v1/services`, `/v1/config/revisions`, `/v1/config/revisions/{revision_id}/publish`, `/v1/config/effective`.
  - Oberfläche: UI-17 (Sonderbetriebsplan anlegen, Vorschau betroffener Angebote).
  - Ereignisse/Outbox: Sonderbetriebsplan wirksam/zurückgenommen.
  - Invarianten (Kap. 04 §4.5): „Nicht überlappende aktive Tarif-/Policy-/Dienstversion“.
- **Detailverträge vorab:** Zustandsautomat; Auslösertypen und ihre Datenquelle (siehe Offene Punkte 11); Regel für bestätigte Buchungen bei Rücknahme.
- **Akzeptanzkriterien:**
  1. Ein Test-Sonderbetriebsplan wird im konfigurierten Zeitraum wirksam und danach ohne manuellen Eingriff zurückgenommen (SYS-012).
  2. Bestätigte Buchungen bleiben an ihre zugesagte Version gebunden; neue Angebote nutzen den jeweils wirksamen Plan.
  3. Aktivierung und Rücknahme sind auditiert; widersprüchliche gleichzeitig wirksame Pläne werden bei der Freigabe abgewiesen.
  4. Neben dem Zeitraum sind die Auslöser Veranstaltung und Wetterlage als gesetztes Auslösersignal (manuell durch berechtigte Rolle oder per Adapter) abbildbar: liegt das Signal vor, wird der freigegebene Plan wirksam; fehlt es oder ist es veraltet, bleibt der Regelbetrieb bestehen (SYS-012; Datenquellen siehe Offene Punkte 11).
  5. Fällt der Aktivierungsjob aus, wird nach Wiederanlauf genau eine Aktivierung bzw. Rücknahme nachgeholt; ein doppelter Joblauf erzeugt keine zweite Wirkung.
- **Tests:** positiv: zeitgesteuerte Aktivierung und Rücknahme; negativ: überlappender Plan, Plan ohne Freigabe; Zeit: Wirksamkeit über Zeitumstellung; konkurrierend: zwei Freigaben überlappender Pläne parallel, genau eine gelingt. Fault-Injection: Jobabsturz zwischen Aktivierung und Ereignisversand; Datentrennung: Plan von Mandant A wirkt nicht auf Dienste von Mandant B. Regression PH-AT-018 (Konfigurationswechsel, Zusage und DST) und E2E-096 (Konfigurationsänderung Zukunft).
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M1-20, WP-M1-22 (Konfigurationsregister mit Wirksamkeitsintervallen und Vier-Augen-Freigabe)
  - extern: WP-M2-03, WP-M2-04 (Dienste und Bediengebiete)
  - extern: WP-M8d-20, WP-M8d-21, WP-M8d-34 (Automatisierungsregeln)
- **Entscheidungen:** keine für den Bau; externe Wetter-/Veranstaltungsquelle ist nicht entschieden (siehe Offene Punkte 11).
- **Größe:** M
- **Nicht im Umfang:** Anbindung einer konkreten Wetter- oder Veranstaltungsquelle über die Adapterschnittstelle hinaus.

### WP-M9b-30 – Gemeinsame Ersatzkapazitäten und Partnerübernahmeanfrage
- **Ziel:** Partner können Ersatzkapazität für Störungen oder Lastspitzen anbieten; bei Fahrzeugausfall erhält ein freigegebener Partner eine rechtskonforme Übernahmeanfrage.
- **Anforderungen:** `LH-I.5-FED-029`
- **Liefergegenstände:**
  - Backend: Modul `journey-federation`; Übernahmeanfrage als `operation` mit `saga_step`; Zustände angefragt → angenommen / abgelehnt / abgelaufen / UNKNOWN → RECONCILING.
  - Datenbank: `operation`, `saga_step`, `cooperation_grant`, `partner_contract_version`, `journey_leg`, `adapter_capability`.
  - API: `/v1/partner/capabilities`, `/v1/partner/offers`, `/v1/partner/reservations`, `/v1/partner/reservations/{id}/confirm`, `/v1/federation/partners/{partner_id}/events`, `/v1/operations/{operation_id}`.
  - Oberfläche: UI-07 („Partnerübernahme anfragen“, Kap. 06 §6.5), UI-08 (Klärung), UI-18 (Grant für Ersatzkapazität).
  - Ereignisse/Outbox: Übernahme angefragt/beantwortet.
  - Konfigurationsparameter: `sharing_vehicle_quota`, `sharing_service_ids`, `interoperator_bookings`, `partner_api_timeout_ms`, `partner_retry_policy`, `partner_liability_policy`.
  - Invarianten (Kap. 04 §4.5): „Grantwiderruf sofort wirksam“, „Bestätigung/PSP-Retry exakt gleiche Operation“.
- **Detailverträge vorab:** Capability „Ersatzkapazität“; Payload der Übernahmeanfrage (minimierte Felder); Zustandsautomat; Vertragsbedingung in `partner_contract_version`.
- **Akzeptanzkriterien:**
  1. Bei Fahrzeugausfall erhält ausschließlich ein Partner mit gültigem Vertrag, aktivem Grant und nachgewiesener Capability eine Übernahmeanfrage; deren Payload enthält nur die freigegebene Feldmenge (FED-029).
  2. Ein Partner ohne Grant oder mit widerrufenem Grant erhält keine Anfrage.
  3. Ein Timeout führt zu UNKNOWN und Abgleich, nicht zu einer zweiten Anfrage oder stillschweigendem Erfolg.
  4. Die Übernahme verändert die Zusage an den Fahrgast nur mit dokumentierter Information bzw. Zustimmung.
  5. Ein Partner kann Ersatzkapazität für die Zwecke Störung und Lastspitze getrennt anbieten, befristen und zurückziehen; sichtbar ist sie nur Vertragspartnern mit aktivem Grant, und eine zurückgezogene Kapazität erscheint in keiner neuen Anfrage (FED-029).
- **Tests:** positiv: Annahme durch Sandbox-Partner; negativ: kein Grant, abgelaufener Vertrag, Ablehnung, Timeout; konkurrierend: Grantwiderruf parallel zur Anfrage; doppelte Partnerantwort → genau ein Zustandsübergang; Datentrennung: Partner sieht keine weiteren Buchungen. Regression der Prüfmuster PH-AT-002 (minimale Datenprojektion), PH-AT-003 (Vertragswiderruf), PH-AT-007 (Antwort geht verloren), E2E-090 (Partner-Doppelnachricht) und E2E-100 (Werkstattausfall, Partnerreserve als zulässige Option).
- **Abhängigkeiten:** intern: WP-M9b-31.
  - extern: WP-M6a-13, WP-M6a-14, WP-M6a-15 (Journey-Orchestrator, Saga, Reconciliation)
  - extern: WP-M6b-04, WP-M6b-05, WP-M6a-05, WP-M6a-07 (Kooperationsvertrag, Grants, Partneradapter, Capability Registry)
  - extern: WP-M7b-12, WP-M7b-14 (Störungsablauf der Leitstelle)
- **Entscheidungen:** D-01, D-04 und D-08 blockieren die Produktivschaltung („rechtskonform“ setzt Rollen-, Haftungs- und Partnerstandardbeschluss voraus); Bau in Sandbox möglich.
- **Größe:** L
- **Nicht im Umfang:** Abrechnung der übernommenen Leistung (Clearing aus M6c).

### WP-M9b-31 – Fahrzeugkontingente für die Verbundvermittlung
- **Ziel:** Ein Betreiber gibt nur einzelne Fahrzeugkontingente zur Verbundvermittlung frei; eine geschützte Stammkundenreserve bleibt unangetastet.
- **Anforderungen:** `LH-I.5-FED-032`
- **Liefergegenstände:**
  - Backend: Module `booking-dispatch` und `journey-federation`; Kontingentprüfung bei Partnerangebot und Partnerreservierung im lokalen Kapazitätskern.
  - Datenbank: `cooperation_grant`, `vehicle_run`, `capacity_hold`, `config_revision`.
  - API: `/v1/partner/offers`, `/v1/partner/reservations`.
  - Oberfläche: UI-18 (Kontingent je Dienst/Partner pflegen).
  - Konfigurationsparameter: `sharing_vehicle_quota`, `min_capacity_reserve`, `sharing_service_ids`.
  - Kennzahlen: `Partnerannahmequote`.
- **Detailverträge vorab:** Semantik von `sharing_vehicle_quota` (Fahrzeuge oder Plätze, siehe Offene Punkte 12); Reihenfolge der Prüfungen.
- **Akzeptanzkriterien:**
  1. Bei gesetzter Stammkundenreserve werden Partnern nur die überschüssigen Plätze angeboten; eine Partnerreservierung, die die Reserve anbrechen würde, wird abgelehnt (FED-032).
  2. Fahrzeuge außerhalb des freigegebenen Kontingents erscheinen in keinem Partnerangebot.
  3. Eine Änderung des Kontingents wirkt auf neue Angebote, nicht rückwirkend auf bestätigte Partnerbuchungen.
  4. Kontingent und Reserve pflegt nur die berechtigte Rolle des Betreibers; Änderungen sind revisioniert und auditiert, der Partner sieht nur das ihm freigegebene Angebot, nicht Reserve oder Gesamtkapazität.
- **Tests:** positiv: Angebot innerhalb Kontingent; negativ: Reserve würde verletzt, Dienst nicht freigegeben; konkurrierend: eigene Buchung und Partnerreservierung parallel auf die letzten freien Plätze – Reserve bleibt gewahrt, keine Überbuchung. Datentrennung: Partner sieht keine Belegung außerhalb des freigegebenen Kontingents. Regression PH-AT-004 (letzter Platz unter Konkurrenz) mit Partnerreservierung.
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M2-22, WP-M2-14 (lokaler Kapazitätskern)
  - extern: WP-M6a-06 (Partnerangebot und -reservierung)
  - extern: WP-M6b-05, WP-M6b-16 (Grants und Kooperationsparameter)
- **Entscheidungen:** D-08 blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Übernahmeanfragen (WP-M9b-30).

### WP-M9b-32 – Partnerbezogene Leistungsgüte und täglicher SLA-Bericht
- **Ziel:** Je Partner werden API-Verfügbarkeit, Antwortzeit und Abbruchrate erfasst; ein täglicher Bericht zeigt technische und operative Störungen.
- **Anforderungen:** `LH-I.5-FED-030`
- **Liefergegenstände:**
  - Backend: Modul `analytics` mit Messpunkten in `journey-federation`; Kennzahldefinitionen je Partner.
  - Datenbank: `metric_definition_revision`, `aggregate_snapshot`, `dataset_watermark`, `saga_step` (Quelle).
  - API: `/v1/analytics/metrics`, `/v1/reports/jobs`, `/v1/reports/jobs/{id}`.
  - Oberfläche: UI-19 (Partnerbericht), UI-18 (Verweis je Kooperation).
  - Konfigurationsparameter: `daily_report_time`, `reporting_timezone`, `partner_api_timeout_ms`.
  - Kennzahlen: `Technische Partnerablehnungen`, `Partnerannahmequote`, `Partnerübergabe pünktlich`; API-Verfügbarkeit, Antwortzeit und Abbruchrate als neue Definitionen (nicht im KPI-Katalog, siehe Offene Punkte 3).
- **Detailverträge vorab:** Definition der drei Messgrößen (Zähler, Nenner, Messfenster); Trennung technisch/operativ; Berichtsschema.
- **Akzeptanzkriterien:**
  1. Der tägliche Bericht weist je Partner API-Verfügbarkeit, Antwortzeit und Abbruchrate sowie getrennt technische und operative Störungen aus (FED-030).
  2. Der Bericht entsteht zum Zeitpunkt `daily_report_time` in `reporting_timezone`, trägt Datenstand und Definitionsversion und ist wiederholbar ohne Doppelzählung.
  3. Ein Partner sieht höchstens seinen eigenen Bericht.
- **Tests:** positiv: Tag mit Timeout und Abbruch; negativ: Tag ohne Aufrufe → „nicht berechenbar“, nicht 100 %; Datentrennung: Partner A sieht keine Werte von Partner B. Idempotenz: wiederholter Berichtslauf desselben Tages erzeugt keine Doppelzählung; Zeit: Tagesgrenze in `reporting_timezone` über die Zeitumstellung.
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M6a-13, WP-M6a-14 (Saga-Schritte und Partneraufrufe als Messquelle)
  - extern: WP-M6a-07, WP-M6b-32 (Partneradapter)
  - extern: WP-M8c-04, WP-M8c-27 (Kennzahlregister, Berichtsjobs)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Wirtschaftlicher Monatsbericht (WP-M9b-33).

### WP-M9b-33 – Gemeinsamer Monatsbericht zu Qualitätszielen und SLA-Konditionen
- **Ziel:** Verbundpartner berichten wirtschaftliche Qualitätsziele und SLA-Konditionen gemeinsam in einem Monatsbericht.
- **Anforderungen:** `LH-I.9-COM-010`
- **Liefergegenstände:**
  - Backend: Modul `documents-finance` (Clearing) mit `analytics`; Monatsbericht je Vertrag.
  - Datenbank: `settlement_statement`, `settlement_line`, `settlement_reconciliation`, `partner_contract_version`, `aggregate_snapshot`.
  - API: `/v1/clearing/statements`, `/v1/reports/jobs`.
  - Oberfläche: UI-16 (Monatsbericht je Vertrag), UI-19.
  - Kennzahlen: `Partnerübergabe pünktlich`, `Ende-zu-Ende-Reiseabschluss`, `Nicht erreichte Anschlüsse`.
- **Detailverträge vorab:** Berichtsschema; Definition „ausgefallenes Segment“ und „offene Forderung“; Sichtbarkeitsregel je Vertragspartei.
- **Akzeptanzkriterien:**
  1. Der Monatsbericht enthält pünktliche Partnerübergaben, ausgefallene Segmente und offene Forderungen je Vertrag (COM-010-CLR).
  2. Beide Vertragsparteien sehen denselben Bericht mit identischen Werten und Datenstand; Dritte sehen ihn nicht.
  3. Offene Forderungen stimmen mit den Settlement-Statements der Periode überein.
  4. Der Bericht stellt die im Vertrag (`partner_contract_version`) vereinbarten Qualitätsziele und SLA-Konditionen den Istwerten gegenüber; fehlt ein vereinbarter Zielwert, wird das ausgewiesen statt ein Ziel anzunehmen (COM-010-CLR).
- **Tests:** positiv: Monat mit Ausfall und offener Forderung; negativ: Zugriff eines Dritten, geschlossene Periode bleibt unverändert; Geld: Summen in kleinster Währungseinheit, Abgleich gegen `settlement_line`; Datentrennung. konkurrierend: Berichtslauf parallel zu einer neuen Statementrevision bzw. zum Periodenabschluss – der Bericht nennt einen konsistenten Datenstand und mischt keine zwei Revisionen. Regression des Prüfmusters PH-AT-015 (geschlossene Periode bleibt unverändert).
- **Abhängigkeiten:** intern: WP-M9b-32.
  - extern: WP-M6c-23, WP-M6c-26, WP-M6c-20 (Settlement-Statements, Abgleich, Forderungen)
  - extern: WP-M8c-27, WP-M8c-04 (Berichtsjobs, Kennzahlregister)
- **Entscheidungen:** D-03 (Geldfluss) und D-01 blockieren nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Änderung der Clearingregeln.

### WP-M9b-34 – Tarif-Sandbox auf anonymisierten historischen Fahrten
- **Ziel:** Preisänderungen lassen sich auf historischen anonymisierten Fahrten durchspielen, ohne einen produktiven Zahlvorgang zu beeinflussen.
- **Anforderungen:** `LH-6.10-PAY-017`
- **Liefergegenstände:**
  - Backend: Modul `fare-payment`; Tarifberechnung als reine Funktion auf einer Entwurfs-Tarifrevision; Lauf in der isolierten Simulationsumgebung.
  - Datenbank: `tariff_revision`, `tariff_rule` (Entwurf), `simulation_run`; anonymisierter Fahrtendatensatz getrennt von Live (Kap. 04 §4.4 AI/ETA/SIM).
  - API: `/v1/scenarios`, `/v1/scenarios/{id}/runs`.
  - Oberfläche: UI-19 (Vergleich Ist-Erlös ↔ simulierter Erlös, als modelliert gekennzeichnet), UI-17 (Einstieg aus Tarifentwurf).
- **Detailverträge vorab:** Szenarioschema „Tarif“; Anonymisierungsregel; Ergebnisschema mit `valuation = SIMULATION` (Kap. 03).
- **Akzeptanzkriterien:**
  1. Ein Sandboxlauf erzeugt weder `fare_quote` noch `payment_intent` noch Journalzeilen im Live-Bestand; ein Schreibversuch auf Live-Objekte wird technisch abgewiesen (PAY-017).
  2. Die verwendeten Fahrten enthalten keine Personen- oder Kontaktmerkmale.
  3. Ergebnisse sind als Simulation gekennzeichnet und nennen Tarifrevision und Datenzeitraum.
  4. Funktion ist ohne Release-Aufnahme (WP-M9b-04) aus.
- **Tests:** positiv: Preisänderung durchspielen; negativ: Live-Schreibversuch, Veröffentlichung der Entwurfsrevision aus der Sandbox; Geld: Rechenergebnis identisch zur produktiven Tarifberechnung bei gleicher Revision; Datentrennung. konkurrierend: Sandboxlauf parallel zu produktiven Preisberechnungen und Zahlungen – Live-Preise, `fare_quote` und Zahlungen bleiben unverändert und innerhalb der Zielzeiten. Trägt zu AT-22 bei (keine Änderung an Echt-Buchungen).
- **Abhängigkeiten:** intern: WP-M9b-04.
  - extern: WP-M2-07, WP-M2-08 (Tarifrevision und Tarifberechnung)
  - extern: WP-M6c-06 (Mehrparteien-Tarife)
  - extern: WP-M9a-14, WP-M9a-15 (isolierte Simulationsumgebung und Szenarioläufe (WP-M9a-14, WP-M9a-15); die KANN-Tarifsimulation der Einheit M9a zu `LH-6.16-SIM-008` ist ausdrücklich keine Voraussetzung, siehe Offene Punkte 26)
- **Entscheidungen:** D-12 blockiert nur die Produktivschaltung; D-11 die Anonymisierungs-/Aufbewahrungsregel.
- **Größe:** M
- **Nicht im Umfang:** Nachfrageelastizität (keine Fachregel im Pflichtenheft).

### WP-M9b-35 – Redaktionsworkflow für Kommunikationsvorlagen
- **Ziel:** Kommunikationsvorlagen durchlaufen einen kontrollierten Redaktionsworkflow; nur freigegebene Vorlagen sind produktiv aktivierbar.
- **Anforderungen:** `LH-6.12-COM-013`
- **Liefergegenstände:**
  - Backend: Modul `communication-support`; Zustände Entwurf → in Prüfung → freigegeben → aktiv → zurückgezogen; Freigabe durch zweite Person.
  - Datenbank: `channel_template_revision`, `notification`.
  - API: Arbeitsnamen `/v1/notification-templates`, `/v1/notification-templates/{id}/submit`, `/approve`, `/activate`.
  - Oberfläche: UI-20 bzw. Admin-Bereich (Vorlagenredaktion mit Vorschau je Kanal und Sprache; Zuordnung der UI-ID siehe Offene Punkte 4).
  - Ereignisse/Outbox: Vorlage freigegeben/aktiviert.
- **Detailverträge vorab:** Zustandsautomat; Rollen Redaktion/Freigabe; Platzhalterschema.
- **Akzeptanzkriterien:**
  1. Eine nicht freigegebene Vorlage lässt sich weder über Oberfläche noch API produktiv aktivieren; ein Versand referenziert stets eine freigegebene Revision (COM-013).
  2. Ersteller und Freigebender sind verschiedene Personen; Freigabe ist auditiert.
  3. Eine aktive Vorlage ist unveränderlich; Änderungen entstehen als neue Revision.
  4. Funktion ist ohne Release-Aufnahme (WP-M9b-04) aus; bis dahin gilt der bisherige Vorlagenbetrieb.
- **Tests:** positiv: Entwurf bis Aktivierung; negativ: Aktivierung ohne Freigabe, Selbstfreigabe, unbekannter Platzhalter; konkurrierend: zwei Aktivierungen verschiedener Revisionen parallel, genau eine aktive Revision; Datentrennung. Regression AT-17 (Massen-Push erreicht nur die Zielgruppe) mit freigegebener Vorlagenrevision.
- **Abhängigkeiten:** intern: WP-M9b-04.
  - extern: WP-M2-32, WP-M2-33 (Betriebsnachrichten und Zustellversuche)
  - extern: WP-M7b-26, WP-M7b-33, WP-M7b-24 (Massenkommunikation und Vorlagen)
- **Entscheidungen:** D-12 blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Übersetzungsmanagement.

### WP-M9b-36 – Telefon-Sprachassistent mit Übergabe an das Callcenter
- **Ziel:** Ein Telefon-Sprachassistent erfasst Wünsche strukturiert und übergibt sie dem Callcenter zur Bestätigung; unklare Sprache führt zur menschlichen Rückfrage, nie zu einer unkontrollierten Buchung.
- **Anforderungen:** `LH-6.13-SUP-011`
- **Liefergegenstände:**
  - Adapter/Worker: Sprachadapter (Arbeitsname `backend/adapters/voice`) hinter providerneutraler Schnittstelle; liefert strukturierten Wunsch mit Konfidenz je Feld.
  - Backend: Modul `communication-support`; Aggregat Sprachwunsch (erfasst → zur Bestätigung → bestätigt / verworfen / Rückfrage); der Assistent besitzt kein Recht, Hold oder Confirm auszulösen.
  - Datenbank: `voice_intake` (Arbeitsname), `support_case`, `delegated_access`.
  - API: Arbeitsnamen `/v1/support/voice-intakes`, `/v1/support/voice-intakes/{id}/confirm`; Buchung weiterhin über `/v1/ride-offers` und `/v1/bookings` durch den Callcenter-Agenten.
  - Oberfläche: UI-06 (Arbeitsliste erfasster Wünsche, Felder mit Konfidenz, Übernahme in die Buchungsmaske).
  - Ereignisse/Outbox: Sprachwunsch erfasst; Rückfrage erforderlich.
- **Detailverträge vorab:** Schema strukturierter Wunsch; Konfidenzschwelle für „unklar“ (siehe Offene Punkte 13); Zustandsautomat; Aufbewahrung von Audio/Transkript.
- **Akzeptanzkriterien:**
  1. Ein klar verstandener Wunsch liegt dem Callcenter strukturiert zur Bestätigung vor; eine Buchung entsteht erst nach Bestätigung durch einen Menschen (SUP-011).
  2. Unklare oder unvollständige Sprache erzeugt eine menschliche Rückfrage; es entsteht weder Hold noch Buchung (SUP-011).
  3. Ein direkter API-Aufruf des Assistenten-Dienstkontos auf Buchungsendpunkte wird mit fehlender Berechtigung abgewiesen.
  4. Bei Ausfall des Sprachadapters bleibt das Callcenter voll arbeitsfähig.
  5. Audio und Transkript werden nur nach der im Detailvertrag festgelegten Aufbewahrungsklasse gespeichert; der erfasste Wunsch ist der verifizierten Person bzw. Vertretung nach den Regeln des Callcenters zugeordnet, ohne Verifikation entsteht kein personenbezogener Buchungsentwurf.
- **Tests:** positiv: klarer Wunsch bis bestätigte Buchung; negativ: unklare Sprache, widersprüchliche Angaben, nicht verifizierter Anrufer, Buchungsversuch des Dienstkontos; Fault-Injection: Sprachdienst aus; Datentrennung: Sprachwünsche je Mandant getrennt. konkurrierend: zwei Agenten bestätigen denselben Sprachwunsch parallel – genau eine Buchung, der zweite erhält einen Konflikt. Regression AT-12 (Callcenter kontra App).
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M7b-17, WP-M7b-19, WP-M7b-20 (Callcenter mit Vertretungsprüfung, Supportfälle)
  - extern: WP-M7a-12 (Buchungs-API für Callcenterbuchungen)
- **Entscheidungen:** D-11 (Aufbewahrung von Sprachaufzeichnungen) blockiert die Produktivschaltung; Anbieterwahl ist in keiner Entscheidung D-01…D-12 erfasst (siehe Offene Punkte 13).
- **Größe:** L
- **Nicht im Umfang:** Vollautomatische Sprachbuchung; Sprachassistent für Fahrer.

### WP-M9b-37 – Servicequalitätsbericht Kundenservice
- **Ziel:** Servicequalität wird mit Erstlösungsquote, Reaktionszeit und Beschwerdeursachen nach konsistenter Definition und anonymisiert berichtet.
- **Anforderungen:** `LH-6.13-SUP-013`
- **Liefergegenstände:**
  - Backend: Modul `analytics`; Kennzahldefinitionen auf `support_case`.
  - Datenbank: `metric_definition_revision`, `aggregate_snapshot`, `support_case` (Ursachenkatalog).
  - API: `/v1/analytics/metrics`, `/v1/reports/jobs`.
  - Oberfläche: UI-19.
  - Konfigurationsparameter: `kpi_aggregation_min_n`.
  - Kennzahlen: `Kundenkontakt-Auflösung`; Erstlösungsquote und Reaktionszeit als neue Definitionen (siehe Offene Punkte 3).
- **Detailverträge vorab:** Definitionen (Zähler, Nenner, Ausschlüsse); Ursachenkatalog; Anonymisierungsregel.
- **Akzeptanzkriterien:**
  1. Der Bericht weist Erstlösungsquote, Reaktionszeit und Beschwerdeursachen mit Definitionsversion aus; derselbe Zeitraum liefert bei Wiederholung identische Werte (SUP-013).
  2. Der Bericht enthält keine Fahrgast- oder Bearbeiternamen; Gruppen unter `kpi_aggregation_min_n` werden unterdrückt (SUP-013).
  3. Funktion ist ohne Release-Aufnahme (WP-M9b-04) aus.
- **Tests:** positiv: Zeitraumbericht; negativ: Kleingruppe, Zeitraum ohne Fälle → „nicht berechenbar“; Datentrennung. Differenzbildung benachbarter Filter legt keine Kleingruppe offen (Prüfmuster PH-AT-021).
- **Abhängigkeiten:** intern: WP-M9b-04.
  - extern: WP-M7b-20 (Supportfälle mit Status- und Zeitstempelhistorie)
  - extern: WP-M8c-04, WP-M8c-11 (Kennzahlregister, Kleingruppenschutz)
- **Entscheidungen:** D-12 blockiert nur die Produktivschaltung; D-10 falls Bearbeiterbezug gewünscht würde (hier ausgeschlossen).
- **Größe:** S
- **Nicht im Umfang:** Leistungsbewertung einzelner Bearbeiter.

### WP-M9b-38 – Versionierte Emissionsfaktoren und CO2e-Kennzahlen
- **Ziel:** CO2e-Kennzahlen beruhen auf nachvollziehbaren, versionierten Emissionsfaktoren je Antrieb und Energieart; jeder Wert nennt Quelle, Faktorversion und Rechenmethode.
- **Anforderungen:** `LH-I.2-FCO-021`
- **Liefergegenstände:**
  - Backend: Modul `documents-finance` (Controlling); Faktorregister mit Wirksamkeitsintervall und Freigabe; CO2e-Berechnung aus validierten Energiemengen.
  - Datenbank: `emission_factor_revision` (Arbeitsname; analog `rate_revision`), `fuel_transaction`, `metric_definition_revision`.
  - API: Arbeitsname `/v1/emission-factors`; `/v1/analytics/metrics`.
  - Oberfläche: UI-15 (CO2e je Fahrzeug/Dienst mit Faktorversion), UI-19.
  - Kennzahlen: CO2e-Kennzahl als neue Definition (nicht im KPI-Katalog, siehe Offene Punkte 3); Grundlage `Kraftstoff L/100 km`, `Strom kWh/100 km`.
  - Invarianten (Kap. 04 §4.5): „Nicht überlappende aktive Tarif-/Policy-/Dienstversion“ sinngemäß für Faktorrevisionen.
- **Detailverträge vorab:** DDL Faktorrevision (Antrieb, Energieart, Einheit, Quelle, gültig von/bis); Rechenmethode; Regel für fehlenden Faktor.
- **Akzeptanzkriterien:**
  1. Jeder CO2e-Wert im Bericht nennt Quelle, Faktorversion und Rechenmethode; ein Wert ohne diese Angaben wird nicht ausgegeben (FCO-021).
  2. Ein veröffentlichter Faktor ist unveränderlich; eine Korrektur entsteht als neue Revision, ältere Berichte bleiben mit ihrer Version reproduzierbar.
  3. Fehlt ein Faktor für Antrieb/Energieart, lautet das Ergebnis „nicht berechenbar“, nicht 0.
  4. Faktoren pflegt und veröffentlicht nur eine berechtigte Rolle mit Vier-Augen-Freigabe; Veröffentlichung ist auditiert.
- **Tests:** positiv: Diesel- und Stromfaktor; negativ: fehlender Faktor, überlappende Gültigkeit, unvereinbare Einheit; konkurrierend: zwei Veröffentlichungen überlappender Faktorrevisionen parallel, genau eine gelingt; Datentrennung.
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M8a-14, WP-M8a-30, WP-M8a-32 (validierte Tank- und Energiemengen, TCO)
  - extern: WP-M8c-04 (Kennzahlregister)
- **Entscheidungen:** D-07 (einheitliche Bewertung) blockiert nur die Produktivschaltung; die Faktorquelle ist nicht entschieden (siehe Offene Punkte 14).
- **Größe:** M
- **Nicht im Umfang:** Berichtsdarstellung Nachhaltigkeit (WP-M9b-39).

### WP-M9b-39 – Nachhaltigkeitsbericht mit offengelegten Faktoren
- **Ziel:** Ein Nachhaltigkeitsbericht berechnet geschätzte Emissionen und Energiebedarf und weist Faktorversion und angenommenen Fahrzeugtyp aus.
- **Anforderungen:** `LH-6.14-ANA-011`
- **Liefergegenstände:**
  - Backend: Modul `analytics`; Berichtsdefinition auf Basis von WP-M9b-38 und WP-M9b-15.
  - Datenbank: `aggregate_snapshot`, `export_job`.
  - API: `/v1/reports/jobs`, `/v1/reports/jobs/{id}`, `/v1/analytics/metrics`.
  - Oberfläche: UI-19 (Bericht mit Kennzeichnung „geschätzt“).
  - Kennzahlen: `Fahrgastkilometer`, `Produktive Kilometer`, `Strom kWh/100 km`, `Kraftstoff L/100 km`.
- **Detailverträge vorab:** Berichtsschema; Regel für angenommenen Fahrzeugtyp bei fehlender Messung; Exportformat.
- **Akzeptanzkriterien:**
  1. Der Bericht berechnet geschätzte Emissionen und Energiebedarf, weist je Wert die Faktorversion und den angenommenen Fahrzeugtyp aus und kennzeichnet beide Größen als geschätzt (ANA-011).
  2. Der Export enthält dieselben Angaben sowie Datenstand und Vollständigkeit.
  3. Empfängerrechte werden beim Export geprüft.
- **Tests:** positiv: Bericht für gemischte Flotte; negativ: fehlender Faktor, fehlende Energiemessung → Annahme ausgewiesen; Datentrennung.
- **Abhängigkeiten:** intern: WP-M9b-15, WP-M9b-38.
  - extern: WP-M8c-27, WP-M8c-28 (Berichtsjobs und Exporte)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Externe Berichtsstandards (im Pflichtenheft nicht benannt).

### WP-M9b-40 – Flottenersatz- und Beschaffungsszenarien mit Lebenszykluskosten
- **Ziel:** Flottenersatz und Beschaffungsvorhaben lassen sich als Szenarien mit Lebenszykluskosten vergleichen, bei identischem Bewertungszeitraum und identischen Annahmen.
- **Anforderungen:** `LH-I.2-FCO-022`
- **Liefergegenstände:**
  - Backend: Modul `documents-finance`; Szenariovergleich auf `valuation = SIMULATION` (Kap. 03), getrennt von Ist und Plan.
  - Datenbank: `forecast_scenario`, `asset_schedule`, `rate_revision`, `cost_object`.
  - API: `/v1/scenarios`, `/v1/vehicles/{id}/total-cost` (Vergleichsbasis).
  - Oberfläche: UI-15 (Szenariovergleich), UI-19.
- **Detailverträge vorab:** Szenarioschema (Bewertungszeitraum, Annahmensatz, Alternativen); Rechenregel (keine Doppelzählung von Leasingrate und Abschreibung, Kap. 03).
- **Akzeptanzkriterien:**
  1. Alle Alternativen eines Vergleichs verwenden denselben Bewertungszeitraum und denselben Annahmensatz; der Versuch, abweichende Zeiträume zu vergleichen, wird abgewiesen (FCO-022).
  2. Szenarioergebnisse erscheinen in keinem Ist- oder Planbericht und erzeugen keine Journalzeilen.
  3. Beträge werden ohne Gleitkomma in kleinster Währungseinheit geführt.
  4. Funktion ist ohne Release-Aufnahme (WP-M9b-04) aus.
- **Tests:** positiv: zwei Fahrzeugalternativen; negativ: abweichender Zeitraum, fehlende Annahme, Leasing und Abschreibung desselben Anteils doppelt; Geld: Rundungssummen reproduzierbar; Datentrennung je Rechtsträger. konkurrierend: zwei parallele Läufe desselben Szenarios liefern identische Ergebnisse und keine Journalzeile; Änderung des Annahmensatzes während eines Laufs wirkt nicht auf den laufenden Vergleich.
- **Abhängigkeiten:** intern: WP-M9b-04.
  - extern: WP-M8a-32, WP-M8a-04, WP-M8a-21 (TCO je Fahrzeug, Kostenkategorien, Bewertungssichten)
- **Entscheidungen:** D-07 und D-12 blockieren nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Betriebssimulation der Flotte (M9a).

### WP-M9b-41 – Getrennte Darstellung von Prognosequalität und Simulationspotenzial
- **Ziel:** Prognosequalität und Einsparpotenzial von Simulationen werden getrennt von Ist-KPIs gezeigt; Prognosewerte sind klar als modelliert gekennzeichnet.
- **Anforderungen:** `LH-6.14-ANA-012`
- **Liefergegenstände:**
  - Backend: Modul `analytics`; eigene Kennzahlklasse „modelliert“ in der Definition; getrennte Abfrage und Darstellung.
  - Datenbank: `metric_definition_revision` (Merkmal Ist/modelliert), `aggregate_snapshot`, `simulation_run`.
  - API: `/v1/analytics/metrics` (Pflichtfilter Wertart), `/v1/scenarios/{id}/runs`.
  - Oberfläche: UI-19 (getrennte Bereiche Ist ↔ Prognose/Simulation, Kennzeichnung nicht nur farblich).
- **Detailverträge vorab:** Erweiterung der Kennzahldefinition; Exportkennzeichnung; Verbot gemischter Aggregation.
- **Akzeptanzkriterien:**
  1. Jeder Prognose- oder Simulationswert trägt in Oberfläche, API und Export die Kennzeichnung „modelliert“ samt Modell-/Szenarioversion (ANA-012).
  2. Eine Abfrage, die Ist- und modellierte Werte in einer Summe aggregiert, wird abgewiesen.
  3. Ist-KPIs bleiben durch Prognose- oder Simulationsläufe unverändert.
- **Tests:** positiv: getrennte Anzeige; negativ: gemischte Aggregation, Export ohne Kennzeichnung nicht möglich. Datentrennung: modellierte Werte fremder Mandanten nicht abrufbar; Zugriff: Rolle ohne Berichtsrecht abgewiesen.
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M8c-04, WP-M8c-32 (Kennzahlregister, Dashboards)
  - extern: WP-M9a-10, WP-M9a-17, WP-M9a-19 (Prognosegüte- und Simulationsergebnisse)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Berechnung der Prognosegüte selbst (M9a).

### WP-M9b-42 – Analyse Prognosegüte, Modellvertrauen und Verkehrsqualität
- **Ziel:** Prognosegüte, Modellvertrauen und Verkehrsqualität lassen sich gegeneinander analysieren, sodass ein Modellupdate seine Wirkung auf ETA-Fehler und sichere Planung nachweist.
- **Anforderungen:** `LH-I.6-KPI-034`
- **Liefergegenstände:**
  - Backend: Modul `analytics`; Vergleichsansicht je Modellversion.
  - Datenbank: `eta_model_revision`, `aggregate_snapshot`, `metric_definition_revision`.
  - API: `/v1/eta/models/{id}/quality`, `/v1/analytics/metrics`.
  - Oberfläche: UI-19 (Vorher/Nachher je Modellversion).
  - Kennzahlen: `ETA-MAE`, `ETA-Bias`, `ETA-p90-Abdeckung`, `Abholung pünktlich`, `Ankunft pünktlich`.
- **Detailverträge vorab:** Vergleichsschema; Definition „Verschlechterung sicherer Planung“ (siehe Offene Punkte 15).
- **Akzeptanzkriterien:**
  1. Für ein Modellupdate zeigt die Analyse Prognosegüte (`ETA-MAE`, `ETA-Bias`) und Modellvertrauen (`ETA-p90-Abdeckung`) vor und nach dem Update sowie die Pünktlichkeits- und Sicherheitskennzahlen desselben Zeitraums und Segments, sodass Verbesserung des ETA-Fehlers ohne Verschlechterung sicherer Planung prüfbar ist (KPI-034).
  2. Werte tragen Modellversion, Datenstand und Stichprobengröße; zu kleine Stichproben werden als nicht belastbar gekennzeichnet.
  3. Die Analyse enthält keine fahrerindividuellen Werte.
- **Tests:** positiv: Update mit Verbesserung; negativ: Update mit verschlechterter Pünktlichkeit wird sichtbar, zu kleine Stichprobe; Zugriff: Rolle ohne Modellrecht abgewiesen. Datentrennung: Modell- und Qualitätswerte fremder Mandanten nicht abrufbar.
- **Abhängigkeiten:** intern: WP-M9b-46.
  - extern: WP-M8b-20 (ETA-Güteüberwachung, Modellrevisionen)
  - extern: WP-M8c-22 (KPI-Katalog)
- **Entscheidungen:** D-10 blockiert nur die Produktivschaltung personalisierter Modelle.
- **Größe:** M
- **Nicht im Umfang:** Freigabeentscheidung (WP-M9b-46).

### WP-M9b-43 – Anonymisierte regionale Benchmarks
- **Ziel:** Anonymisierte regionale Benchmarks sind nur bei ausreichender Gruppengröße und vorliegender rechtlicher Freigabe verfügbar; kleine Mandantengruppen können nicht de-anonymisiert werden.
- **Anforderungen:** `LH-I.6-KPI-035`
- **Liefergegenstände:**
  - Backend: Modul `analytics`; Benchmark-Job in eigenem Plattformkontext über freigegebene Aggregate (kein Rohdatenzugriff über Mandanten); Teilnahmefreigabe je Mandant.
  - Datenbank: `aggregate_snapshot`, `benchmark_participation` (Arbeitsname), `metric_definition_revision`.
  - API: `/v1/analytics/metrics` (Benchmarksicht), Arbeitsname `/v1/analytics/benchmarks`.
  - Oberfläche: UI-19 (eigener Wert ↔ regionaler Vergleichswert), UI-18 bzw. Admin (Teilnahmefreigabe).
  - Konfigurationsparameter: `kpi_aggregation_min_n`; Mindestzahl teilnehmender Mandanten (Arbeitsname, siehe Offene Punkte 2).
- **Detailverträge vorab:** Anonymisierungsverfahren einschließlich Differenzschutz (Subtraktionsangriff bei Ein-/Austritt eines Mandanten); Freigabenachweis; Kennzahlenliste.
- **Akzeptanzkriterien:**
  1. Ein Benchmark wird nur ausgegeben, wenn Mindestgruppengröße und rechtliche Freigabe aller einbezogenen Mandanten vorliegen; sonst „nicht verfügbar“ (KPI-035).
  2. Aus zwei aufeinanderfolgenden Benchmarks mit und ohne einen Mandanten lässt sich dessen Wert nicht ableiten; eine Region mit zu wenigen Mandanten liefert keinen Wert (KPI-035).
  3. Kein Mandant erhält Einzelwerte anderer Mandanten; Widerruf der Teilnahme wirkt auf künftige Benchmarks.
- **Tests:** positiv: ausreichend große Gruppe; negativ: Gruppe unter Schwelle, fehlende Freigabe, Differenzangriff über zwei Abfragen, enge Filterkombination; Datentrennung: Job läuft ohne Rohdatenzugriff unter eigener Rolle. konkurrierend: Widerruf der Teilnahme parallel zum Benchmark-Lauf – der Lauf enthält den Mandanten entweder vollständig mit gültiger Freigabe oder gar nicht. Regression des Prüfmusters PH-AT-021 (Aggregationsschutz auch bei Differenzbildung) und E2E-101 (Mehrgebietsauswertung).
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M8c-22, WP-M8c-11 (KPI-Katalog, Kleingruppenschutz)
  - extern: WP-M1-07, WP-M8b-22, WP-M8b-29 (Plattformrollen, Datenschutzprüfung)
- **Entscheidungen:** D-11 und D-02 blockieren die Produktivschaltung (rechtliche Freigabe, mandantenübergreifende Verarbeitung).
- **Größe:** M
- **Nicht im Umfang:** Benchmarks mit Partnerkosten oder Margen.

### WP-M9b-44 – Lernbasierte Anomaliehinweise auf Auslastung und Kosten
- **Ziel:** Anomaliehinweise auf Auslastung und Kosten können lernbasierte Anteile enthalten, deren Trefferquote getrennt evaluiert wird; Hinweise ändern keine operative Sperre automatisch.
- **Anforderungen:** `LH-I.8-AUT-014`
- **Liefergegenstände:**
  - Backend: Modul `analytics` mit Alarmanbindung; Hinweise mit Kennzeichnung „regelbasiert“ oder „lernbasiert“; Rückmeldung falsch-positiv; getrennte Trefferstatistik.
  - Datenbank: `alert_policy_revision`, `business_alert`, `alert_acknowledgement` (Rückmeldung), `model_approval`.
  - API: Arbeitsname `/v1/alerts/{id}/feedback`; `/v1/analytics/metrics`.
  - Oberfläche: UI-07 bzw. UI-15 (Hinweis mit Herkunft und Rückmeldeaktion), UI-19 (Trefferquote).
  - Konfigurationsparameter: `cost_overrun_alert_pct`, `capacity_reject_alert_count`, `fuel_use_alert_pct`.
  - Kennzahlen: `Kapazitätsauslastung distanzgewichtet`, `Budgetabweichung`; Trefferquote als neue Definition (siehe Offene Punkte 3).
- **Detailverträge vorab:** Hinweisschema mit Herkunft und Modellversion; Rückmeldeschema; Definition Trefferquote.
- **Akzeptanzkriterien:**
  1. Ein Hinweis kann als falsch-positiv markiert werden; die Markierung geht in die getrennt ausgewiesene Trefferquote des lernbasierten Anteils ein (AUT-014).
  2. Weder ein Hinweis noch eine Rückmeldung setzt oder hebt eine operative Sperre (Fahrzeug, Fahrer, Dienst, Budget) automatisch auf (AUT-014).
  3. Bei Ausfall des lernbasierten Anteils laufen regelbasierte Alarme unverändert weiter.
- **Tests:** positiv: Hinweis, Rückmeldung, Quote; negativ: Versuch, per Hinweis eine Sperre auszulösen, findet keinen Pfad; Ausfall des Modells; Datentrennung. konkurrierend: zwei Nutzer melden denselben Hinweis gleichzeitig zurück – genau eine gezählte Rückmeldung je Hinweis und Rolle. Regression E2E-099 (Nachfragealarm) mit unverändert regelbasiertem Alarm.
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M8a-32, WP-M8a-34 (Kostenereignisse und Budgets)
  - extern: WP-M8c-04, WP-M8c-13 (Kennzahlen)
  - extern: WP-M8d-20, WP-M8d-21, WP-M8d-22, WP-M8d-23 (Automatisierungsregeln, Alarme, Regelrevisionen)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Automatische Gegenmaßnahmen.

### WP-M9b-45 – Zeitlich begrenztes Shadow-Testing neuer ETA-Modelle
- **Ziel:** Ein neues ETA-Modell läuft zeitlich begrenzt im Schatten des etablierten Modells; beide Prognosefehler und die Freigabeschwellen werden nachgewiesen, ohne Buchungen zu beeinflussen.
- **Anforderungen:** `LH-I.3-ETA-024`
- **Liefergegenstände:**
  - Backend: Modul `analytics` (Unterpaket eta); Shadowlauf mit Zuständen geplant → laufend → beendet → ausgewertet; Schattenprognosen in getrenntem Speicher.
  - Datenbank: `eta_model_revision`, `eta_shadow_run` (Arbeitsname), `eta_shadow_prediction` (Arbeitsname), `model_approval`.
  - API: `/v1/eta/models/{id}/quality`; Arbeitsname `/v1/eta/shadow-runs`.
  - Oberfläche: UI-19 (Vergleich beider Modelle mit Schwellen).
  - Konfigurationsparameter: `eta_model_rollout_percent` (bleibt während Shadow bei 0 für das neue Modell), `eta_fallback_policy`.
  - Kennzahlen: `ETA-MAE`, `ETA-Bias`, `ETA-p90-Abdeckung`.
- **Detailverträge vorab:** Zustandsautomat Shadowlauf; Berichtsschema; Freigabeschwellen als Konfiguration.
- **Akzeptanzkriterien:**
  1. Der Bericht einer Testperiode weist die Prognosefehler beider Modelle und die Freigabeschwellen aus (ETA-024).
  2. Während des Shadowlaufs stammt keine operative ETA, kein Angebot und keine Zusage aus dem neuen Modell; dies ist über die Modellversion an jeder ETA nachweisbar (ETA-024).
  3. Der Shadowlauf endet automatisch zum festgelegten Zeitpunkt.
- **Tests:** positiv: Shadowlauf mit Auswertung; negativ: Versuch, Schattenprognose operativ zu lesen, Lauf ohne Enddatum abgewiesen; Last: Shadowlauf verletzt die ETA-Antwortzeiten nicht. Datentrennung: Schattenprognosen fremder Mandanten nicht lesbar; konkurrierend: zwei Shadowläufe für dasselbe Modell und denselben Zeitraum – der zweite wird abgewiesen. Regression PH-AT-022 (nicht freigegebenes Modell bleibt ohne operative Wirkung).
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M8b-08, WP-M8b-11, WP-M8b-20 (lernfähige ETA, Modellrevisionen, Güteüberwachung, Rückführbarkeit je ETA)
- **Entscheidungen:** D-10 blockiert nur die Produktivschaltung personalisierter Modelle.
- **Größe:** M
- **Nicht im Umfang:** Freigabe und Rollback (WP-M9b-46).

### WP-M9b-46 – Qualitätsfreigaben, Rollback und Verantwortliche für Modelländerungen
- **Ziel:** Jede Modelländerung braucht definierte Qualitätsfreigaben, benannte Verantwortliche und ist ohne Datenverlust auf die vorherige Version zurückrollbar.
- **Anforderungen:** `LH-I.3-ETA-025`
- **Liefergegenstände:**
  - Backend: Modul `analytics` (Unterpaket eta); Freigabeworkflow je Modellrevision; Rollback als neue Aktivierungsrevision der Vorversion.
  - Datenbank: `eta_model_revision`, `model_approval` (Freigaben, Verantwortliche), `audit_event`.
  - API: Arbeitsnamen `/v1/eta/models/{id}/approve`, `/v1/eta/models/{id}/rollback`.
  - Oberfläche: UI-19 (Freigabestatus, Verantwortliche, Rollback-Aktion), Konfigurationseditor (Kap. 06 §6.8: fehlende Fachfreigaben sichtbar).
  - Ereignisse/Outbox: Modell aktiviert/zurückgerollt.
  - Konfigurationsparameter: `eta_model_rollout_percent`, `eta_fallback_policy`.
- **Detailverträge vorab:** Freigabeautomat mit Pflichtfreigaben; Rollbackvertrag (was bleibt erhalten: Beobachtungen, Profile, Prognoseprotokoll).
- **Akzeptanzkriterien:**
  1. Ein fehlerhaftes neues Modell lässt sich auf die vorherige Version zurückrollen; danach sind Beobachtungen, Prognoseprotokolle und beide Modellrevisionen vollständig vorhanden (ETA-025).
  2. Eine Aktivierung ohne alle definierten Qualitätsfreigaben und ohne benannte Verantwortliche wird abgewiesen (ETA-025).
  3. Bestätigte Zusagen bleiben vom Rollback unberührt.
- **Tests:** positiv: Freigabe, Aktivierung, Rollback; negativ: Aktivierung ohne Freigabe, Rollback ohne Vorversion; konkurrierend: Aktivierung und Rollback parallel, genau eine aktive Revision. Baut auf E2E-078 (M8b) auf. Regression PH-AT-022 und E2E-106 (ohne Fachfreigabe keine produktive Aktivierung).
- **Abhängigkeiten:** intern: WP-M9b-45.
  - extern: WP-M8b-04, WP-M8b-05, WP-M8b-20 (Modellfreigabe, Aktivierungsgate, Fallback)
- **Entscheidungen:** D-10 blockiert nur die Produktivschaltung personalisierter Modelle.
- **Größe:** M
- **Nicht im Umfang:** Freigabe der Nachfrageprognosemodelle (M9a).

### WP-M9b-47 – Begründete automatische Besetzungsvorschläge
- **Ziel:** Die automatische Besetzung berücksichtigt Qualifikation, Ruhezeiten, Präferenzen und faire Verteilung und begründet ihre Vorschläge; sie veröffentlicht nichts selbst.
- **Anforderungen:** `LH-6.23-SCH-033`
- **Liefergegenstände:**
  - Backend: Modul `staff-time`; Vorschlagslauf auf einem Dienstplanentwurf; Ergebnis als Vorschlagsliste mit Kriterien je Zuordnung und Konfliktprüfung.
  - Datenbank: `roster_revision` (Entwurf), `roster_proposal` (Arbeitsname), `staff_preference` (Arbeitsname; Präferenzen der Beschäftigten – weder Kap. 04 §4.4 noch M7d liefern ein Präferenzmodell, siehe Offene Punkte 16), `qualification_revision`, `staff_shift`, `absence_request`.
  - API: `/v1/rosters`; Arbeitsname `/v1/rosters/{roster_id}/proposals`; Publikation unverändert nur über `/v1/rosters/{roster_id}/publish`.
  - Oberfläche: UI-12 (Vorschläge mit Begründung, einzeln übernehmen/verwerfen, als unverbindlich markiert).
  - Konfigurationsparameter: `min_rest_hours`, `max_daily_work_minutes`, `shift_publish_notice_hours`.
  - Kennzahlen: `Schicht-Abdeckungsquote`.
- **Detailverträge vorab:** Vorschlagsschema (Kriterien, Konfliktstatus); Definition „faire Verteilung“ und Präferenzmodell (siehe Offene Punkte 16).
- **Akzeptanzkriterien:**
  1. Die Vorschlagsliste weist je Zuordnung Konfliktfreiheit (Qualifikation, Ruhezeit, Doppelbelegung) und die angewandten Kriterien aus (SCH-033).
  2. Der Vorschlagslauf verändert keinen veröffentlichten Plan und ruft keine Publikation auf; Publikation erfolgt erst nach Freigabe durch den Planer (SCH-033, AT-55).
  3. Ein Vorschlag mit Konflikt wird nicht als konfliktfrei ausgegeben; nicht besetzbare Dienste bleiben offen mit Grund.
  4. Präferenzen und Verteilungsmaß gehen nur als weiche Kriterien ein und können keine harte Regel (Qualifikation, Ruhezeit, Abwesenheit) aufwiegen; Begründungen enthalten keine Gesundheits- oder Abwesenheitsgründe und sind nur für Planerrollen sichtbar.
- **Tests:** positiv: konfliktfreier Vorschlag; negativ: fehlende Qualifikation, Ruhezeitverletzung, Abwesenheit; konkurrierend: Planer ändert den Entwurf während des Laufs – Übernahme veralteter Vorschläge wird per Revisionsvergleich abgewiesen; Zugriff: Abwesenheitsgründe erscheinen nicht in Begründungen. Datentrennung: Vorschläge und Präferenzen fremder Mandanten nicht lesbar; Beschäftigte sehen keine Vorschlagslisten. Regression AT-37 (Entwurf/Publikation), AT-39 (Qualifikation, Doppelschicht), AT-40 (Ruhezeit) und AT-54 (Bedienung ohne Drag-and-drop).
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M4-04, WP-M4-07, WP-M4-14 (Dienstplan mit Entwurf/Publikation und Konfliktprüfung)
  - extern: WP-M7d-04, WP-M7d-23, WP-M7d-14, WP-M7d-35 (Schichtvorlagen, Schichtangebote, Abwesenheiten, Zeitkonten (Präferenzen liefert M7d nicht; sie entstehen in diesem WP))
- **Entscheidungen:** D-10 (Mitbestimmung) blockiert die Produktivschaltung.
- **Größe:** L
- **Nicht im Umfang:** Bedarfsermittlung aus Prognose (WP-M9b-48).

### WP-M9b-48 – KI-gestützte Personalbedarfsempfehlung
- **Ziel:** Personalbedarfsempfehlungen nutzen anonymisierte Nachfrageprognosen und geplante Verkehrsdienste; eine Änderung des Nachfrageprofils ändert die Bedarfsprognose nachvollziehbar.
- **Anforderungen:** `LH-6.23-SCH-034`
- **Liefergegenstände:**
  - Backend: Modul `staff-time`; Bedarfsrechnung je Zeitfenster und Gebiet aus Prognose und geplanten Diensten; Erklärung der Einflussgrößen.
  - Datenbank: `staffing_demand_recommendation` (Arbeitsname), `roster_revision`, `service`.
  - API: Arbeitsname `/v1/rosters/{roster_id}/demand-recommendations`.
  - Oberfläche: UI-12 (Bedarf ↔ Besetzung, Unterdeckung, Kennzeichnung „unverbindlich“ mit Modellversion), UI-19.
  - Kennzahlen: `Schicht-Abdeckungsquote`, `Personalmangelablehnungen`.
- **Detailverträge vorab:** Schnittstelle zur Nachfrageprognose (nur aggregiert, kein Personenbezug); Erklärungsschema; Verhalten ohne Prognose.
- **Akzeptanzkriterien:**
  1. Eine Änderung des Nachfrageprofils führt zu einer geänderten Bedarfsprognose; die Änderung ist auf Prognosewerte, Dienste und Modellversion zurückführbar (SCH-034).
  2. Erhöht die Prognose den Bedarf, zeigt der Dienstplan Unterdeckung und Vorschläge als unverbindlich; nichts wird ohne Freigabe publiziert (AT-55).
  3. Die Bedarfsrechnung verwendet ausschließlich anonymisierte, aggregierte Prognosedaten.
  4. Ohne Prognose bleibt die Dienstplanung nutzbar; die Empfehlung meldet „nicht verfügbar“.
- **Tests:** positiv: zwei Nachfrageprofile, unterschiedlicher Bedarf; negativ: Prognosedienst aus, personenbezogene Eingabe wird abgewiesen. Besteht AT-55 zusammen mit WP-M9b-47 und M9a. Datentrennung: Bedarfsempfehlungen und Prognosewerte fremder Mandanten nicht abrufbar; Nachvollziehbarkeit: gleiche Prognose- und Dienstversion ergibt identischen Bedarf.
- **Abhängigkeiten:** intern: WP-M9b-47.
  - extern: WP-M9a-06 (anonymisierte Nachfrageprognose mit Modellversion)
  - extern: WP-M7d-32, WP-M7d-23 (Dienstplanung und Schichtangebote; unverbindlicher Personalbedarfsvorschlag aus historischen Daten (WP-M7d-32) als Ausgangsfunktion, die hier um die Nachfrageprognose erweitert wird)
- **Entscheidungen:** D-10 blockiert die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Automatische Publikation oder Schichtzuweisung.

### WP-M9b-49 – Versionierte Arbeitszeitregelwerke je Mitarbeitergruppe
- **Ziel:** Regelwerke für Tarifverträge, Betriebsvereinbarungen und weitere Arbeitszeitvorgaben sind versioniert konfigurierbar und auf Mitarbeitergruppen begrenzbar.
- **Anforderungen:** `LH-6.24-TIM-018`
- **Liefergegenstände:**
  - Backend: Modul `staff-time`; Regelwerksrevision mit Geltungsbereich Mitarbeitergruppe und Wirksamkeitsintervall; Auswertung in Zeiterfassung und Dienstplanprüfung.
  - Datenbank: `work_rule_set_revision` (Arbeitsname), `employee_group` (Arbeitsname), `config_revision`, `time_event`, `time_account_entry`.
  - API: `/v1/config/revisions`, `/v1/config/effective` (Scope Mitarbeitergruppe).
  - Oberfläche: Konfigurationseditor (Kap. 06 §6.8), UI-12, UI-13 (wirksames Regelwerk sichtbar).
  - Konfigurationsparameter: `max_daily_work_minutes`, `min_rest_hours` (heute Ebene Rechtsraum; Ebene Mitarbeitergruppe ist neu, siehe Offene Punkte 17).
  - Invarianten (Kap. 04 §4.5): „Nicht überlappende aktive Tarif-/Policy-/Dienstversion“.
- **Detailverträge vorab:** Präzedenz Rechtsraum → Mandant → Mitarbeitergruppe (gesetzliche Grenze nie unterschreitbar); Regelschema Pausen/Arbeitszeit; Wirkung auf bereits erfasste Zeiten.
- **Akzeptanzkriterien:**
  1. Zwei Beschäftigtengruppen desselben Mandanten nutzen getrennte Pausen-/Arbeitszeitregeln; dieselbe Stempelfolge führt je Gruppe zum jeweils regelgerechten Ergebnis (TIM-018).
  2. Eine Regelwerksänderung wirkt ab Wirksamkeitsdatum; frühere Zeiten bleiben nach ihrer damaligen Version bewertet.
  3. Ein Gruppenregelwerk kann die Grenze des Rechtsraums nicht lockern; der Validator weist das ab.
- **Tests:** positiv: zwei Gruppen; negativ: Lockerung der Rechtsraumgrenze, überlappende Revisionen, Mitarbeiter ohne Gruppe → Standardregel; konkurrierend: zwei Veröffentlichungen für dieselbe Gruppe parallel; Zeit: Wirksamkeitswechsel über Mitternacht und Zeitumstellung. Datentrennung: Regelwerke und Gruppenzuordnung fremder Mandanten nicht lesbar. Regression AT-40 (Ruhe-/Pausenkonflikt), AT-47 und PH-AT-018 (DST, Konfigurationswechsel ohne Rückwirkung).
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M4-08, WP-M4-07, WP-M4-13 (Zeiterfassung, Dienstplan-Konfliktprüfung)
  - extern: WP-M7d-35, WP-M7d-33, WP-M7d-09 (Zeitkonten, Zeitkorrekturen)
  - extern: WP-M8d-03, WP-M8d-14, WP-M1-19 (Parameterkatalog, Policy Validator)
- **Entscheidungen:** D-10 (Mitbestimmung) blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Lohnabrechnung.

### WP-M9b-50 – Konzernübersichten für Untermandanten und Unternehmensgruppen
- **Ziel:** Untermandanten und Unternehmensgruppen erhalten rechtlich zulässige Konzernübersichten; Einzelbelege bleiben entsprechend der Berechtigung isoliert.
- **Anforderungen:** `LH-I.4-MUL-024`
- **Liefergegenstände:**
  - Backend: Module `identity` und `analytics`; Gruppenbeziehung zwischen Mandanten; Konzernsicht ausschließlich über freigegebene Aggregatprojektionen im Kontext des Gruppenmandanten (keine dynamische Mehrmandanten-RLS, Kap. 04 §4.6).
  - Datenbank: `tenant_group` (Arbeitsname), `tenant_group_member` (Arbeitsname), `aggregate_snapshot`, `legal_entity`, `organization`.
  - API: `/v1/analytics/metrics` (Konzernsicht), `/v1/reports/jobs`.
  - Oberfläche: UI-15 und UI-19 (konsolidierte Sicht mit beteiligten Rechtsträgern, Eliminierungen und Berechtigungsbasis, Kap. 06 §6.6), UI-18 (Gruppenfreigabe).
  - Invarianten (Kap. 04 §4.5): „Keine lokale Cross-Tenant-Referenz“, „Kein Mandantenkontext → keine Zeile“.
- **Detailverträge vorab:** Projektionsschema; Freigabeakt je Tochter; Liste zulässiger Kennzahlen in der Konzernsicht.
- **Akzeptanzkriterien:**
  1. Drei Tochtergesellschaften sind in der Konzernübersicht zentral sichtbar; der Abruf eines Einzelbelegs einer Tochter durch einen Konzernnutzer ohne Berechtigung im Tochtermandanten wird serverseitig abgewiesen und protokolliert (MUL-024).
  2. Gleicher Rechtsträger oder Gruppenzugehörigkeit allein begründet keinen Zugriff auf Mandantendaten.
  3. Widerruf der Gruppenfreigabe einer Tochter entfernt sie aus künftigen Übersichten.
- **Tests:** positiv: Übersicht über drei Töchter; negativ: Drilldown auf Einzelbeleg, Tochter ohne Freigabe, Direktzugriff per ID; konkurrierend: Widerruf parallel zum Berichtslauf; Datentrennung: Test unter Runtime-Rolle ohne BYPASSRLS. Regression PH-AT-001, AT-18, E2E-091 (Cross-Tenant-Zugriff) und E2E-101 (Mehrgebietsauswertung: freigegebene Gesamt-KPIs stimmen).
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M1-05, WP-M1-06, WP-M1-01 (Mandant, Rechtsträger, Mitgliedschaft, RLS)
  - extern: WP-M6b-12, WP-M6b-10, WP-M6b-05 (Firmenrollen und Gebietsorganisation, Grants)
  - extern: WP-M8a-32, WP-M8a-12 (konsolidierbare Finanzsichten)
  - extern: WP-M8c-04 (Kennzahlregister)
- **Entscheidungen:** D-02 (Isolationsstufe) und D-01 blockieren die Produktivschaltung; D-06/D-07 für Finanzkonsolidierung.
- **Größe:** M
- **Nicht im Umfang:** Rechtliche Konsolidierungsbuchungen im Hauptbuch.

### WP-M9b-51 – Mehrregionenbetrieb: Zeitzonen, Feiertage, Währungen, lokale Regeln
- **Ziel:** Der Betrieb unterstützt mehrere Zeitzonen, Feiertagskalender, Währungen und lokal gültige Verkehrsregeln; grenzüberschreitende Planung bleibt beim Datumswechsel fehlerfrei.
- **Anforderungen:** `LH-I.4-MUL-025`
- **Liefergegenstände:**
  - Backend: `platform` (Zeit-/Geldtypen) sowie `config`, `service-geo`, `staff-time`, `fare-payment`; Region als Konfigurationsscope mit IANA-Zone, Feiertagskalender, Währung und Regelsatz.
  - Datenbank: `region` (Arbeitsname), `holiday_calendar` (Arbeitsname), `currency`, `config_revision`, `service_area`.
  - API: `/v1/config/effective` (Scope Region), `/v1/services`, `/v1/service-areas`, `/v1/rosters`.
  - Oberfläche: UI-17 (Region je Gebiet), UI-12 (Planung mit Zonenanzeige), UI-01 (Ortszeit mit Offset, Kap. 06 §6.2).
  - Konfigurationsparameter: `reporting_timezone`, `max_daily_work_minutes`, `min_rest_hours`, `transfer_min_time_min`, `finance_close_day`.
- **Detailverträge vorab:** Regionsmodell und Präzedenz; Regel für Buchungen/Dienste über Zonengrenzen; Währungsregel (keine implizite Umrechnung, Journal je Rechtsträger und Währung ausgeglichen, Kap. 03); Umfang „lokal gültige Verkehrsregeln“ (siehe Offene Punkte 18).
- **Akzeptanzkriterien:**
  1. Eine grenzüberschreitende Planung über zwei Zeitzonen mit Datumswechsel weist jedem Dienst und jeder Fahrt das korrekte lokale Datum zu; Ruhezeit- und Arbeitszeitprüfung rechnen auf Zeitpunkten, nicht auf Ortszeitdifferenzen (MUL-025).
  2. Feiertagsregeln gelten nach dem Kalender der jeweiligen Region.
  3. Beträge verschiedener Währungen werden nie ohne ausgewiesene Umrechnungsregel summiert.
  4. Berichte nennen die verwendete Zeitzone.
  5. Ein regionsbezogener Regelsatz („lokal gültige Verkehrsregeln“, soweit als Parameter abgebildet) liefert für zwei Regionen desselben Mandanten unterschiedliche wirksame Werte; die Herkunft Region ist in `/v1/config/effective` ausgewiesen (MUL-025; Umfang siehe Offene Punkte 18).
- **Tests:** positiv: Dienst 23:30–00:30 über Zonengrenze; negativ: DST-Lücke/-Doppelstunde in einer der Zonen, Summe über zwei Währungen abgewiesen, Region ohne Kalender; Geld: Journal je Währung ausgeglichen; konkurrierend: parallele Buchungen beiderseits der Datumsgrenze auf denselben Umlauf. Datentrennung: Regionen, Kalender und Regelsätze fremder Mandanten nicht lesbar. Regression E2E-102 (Mehrregion Zeitzone), AT-47 und PH-AT-018 (zwei eindeutige Zeitpunkte in der Doppelstunde).
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M1-02, WP-M1-18, WP-M1-09, WP-M1-20 (Zeit-/Geldtypen, Konfigurationsscopes)
  - extern: WP-M4-10, WP-M4-36 (DST-feste Zeitrechnung, Fiskalperioden)
  - extern: WP-M6b-10, WP-M6b-11 (Gebietsorganisation)
  - extern: WP-M8d-03, WP-M8d-07, WP-M8d-08 (Parameterkatalog je Ebene)
- **Entscheidungen:** D-05 (Regionen, Hostingregion) blockiert die Produktivschaltung; D-06/D-07 für Mehrwährungsbewertung.
- **Größe:** L
- **Nicht im Umfang:** Währungsumrechnung mit Kursquelle und steuerliche Regeln je Land (im Pflichtenheft nicht spezifiziert, siehe Offene Punkte 18).

### WP-M9b-52 – Zentral verteilte Unternehmensvorlagen mit geschützten Abweichungen
- **Ziel:** Vorlagen für Tarifgebiete, Betriebsmodelle und KPI-Dashboards werden zentral verteilt und lokal angepasst; eine Vorlagenaktualisierung überschreibt keine ausdrücklich geschützte Mandantenabweichung.
- **Anforderungen:** `LH-I.7-CFG-023`
- **Liefergegenstände:**
  - Backend: Modul `config`; Vorlagenrevision, Verteilung an Zielmandanten als vorgeschlagene Konfigurationsrevision, Schutzmerkmal je lokaler Abweichung, Differenzbericht.
  - Datenbank: `config_template_revision` (Arbeitsname), `config_revision` (Herkunft Vorlage, Schutzmerkmal), `policy_schema`.
  - API: `/v1/config/revisions`, `/v1/config/revisions/{id}`, `/validate`, `/impact`, `/v1/config/effective`; Arbeitsname `/v1/config/templates`.
  - Oberfläche: Konfigurationseditor (Kap. 06 §6.8) mit Herkunft „Vorlage“/„lokal geschützt“, UI-17, UI-19 (Dashboardvorlagen).
  - Ereignisse/Outbox: Vorlage verteilt; Abweichung beibehalten.
- **Detailverträge vorab:** Merge-Regel Vorlage ↔ lokale Werte; Schutzmerkmal; Einordnung in die Präzedenzkette.
- **Akzeptanzkriterien:**
  1. Nach Aktualisierung einer Vorlage behält ein Mandant jeden als geschützt markierten abweichenden Wert; nicht geschützte Werte folgen der Vorlage; der Differenzbericht nennt beides (CFG-023).
  2. Die Verteilung erzeugt je Mandant eine eigene Revision mit regulärer Validierung und Freigabe; eine ungültige Vorlage wird in keinem Mandanten wirksam.
  3. Die Vorlage gibt keinem Mandanten Einblick in Werte anderer Mandanten.
- **Tests:** positiv: Verteilung an zwei Mandanten mit einer geschützten Abweichung; negativ: Vorlage verletzt Sicherheitsinvariante, Zielmandant ohne Freigabe; konkurrierend: lokale Änderung parallel zur Vorlagenverteilung – kein stilles Überschreiben (412); Datentrennung. Regression E2E-097 (unzulässige Konfiguration abgewiesen) und E2E-098 (Konfigurations-Rollback) für aus Vorlagen entstandene Revisionen.
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M1-20, WP-M1-21, WP-M1-22 (Konfigurationsregister, Präzedenzkette, Rollback als neue Revision)
  - extern: WP-M8d-05, WP-M8d-07 (Policy Validator, Wirkungsbericht)
- **Entscheidungen:** D-02 blockiert nur die Produktivschaltung mandantenübergreifender Verteilung.
- **Größe:** M
- **Nicht im Umfang:** Konzernübersichten (WP-M9b-50).

### WP-M9b-53 – Lernende OCR- und Kontierungsvorschläge für wiederkehrende Rechnungen
- **Ziel:** Bei wiederkehrenden Rechnungen werden OCR- und Kontierungsvorschläge aus bestätigten Beispielen gelernt; sie sind erklärbar, ablehnbar, korrigierbar und je Mandant deaktivierbar.
- **Anforderungen:** `LH-I.1-DOC-029`
- **Liefergegenstände:**
  - Backend: Modul `documents-finance`; Lernspeicher je Mandant aus freigegebenen Belegen; Vorschlag mit Verweis auf die zugrunde liegenden Beispiele.
  - Adapter/Worker: `workers/documents`.
  - Datenbank: `extraction_run`, `extracted_field`, `accounting_mapping`, `supplier_invoice`, `learned_suggestion_rule` (Arbeitsname).
  - API: `/v1/expenses/receipts`, `/extract`, `/v1/expenses/{id}`.
  - Oberfläche: UI-14 (Vorschlag mit Erklärung, Annehmen/Ablehnen/Korrigieren).
  - Konfigurationsparameter: `receipt_ocr_enabled`, `invoice_accounting_schema`; Schalter „gelernte Vorschläge“ je Mandant (Arbeitsname, siehe Offene Punkte 2).
- **Detailverträge vorab:** Vorschlagsschema mit Erklärung; Lernregel (nur bestätigte Belege); Löschverhalten des Lernspeichers.
- **Akzeptanzkriterien:**
  1. Ein gelernter Vorschlag nennt seine Grundlage, lässt sich ablehnen und korrigieren; Ablehnung und Korrektur bleiben in der Historie nachvollziehbar (DOC-029).
  2. Die Funktion lässt sich je Mandant getrennt deaktivieren; danach erscheinen dort keine gelernten Vorschläge, andere Mandanten bleiben unberührt (DOC-029).
  3. Beispiele eines Mandanten beeinflussen nie Vorschläge eines anderen Mandanten.
  4. Ein Vorschlag bucht nichts; Freigabe und Vier-Augen-Regel gelten unverändert.
  5. Funktion ist ohne Release-Aufnahme (WP-M9b-04) aus.
- **Tests:** positiv: dritte Rechnung desselben Lieferanten erhält Vorschlag; negativ: abgelehnter Vorschlag wird nicht erneut unverändert gelernt, deaktivierter Mandant; Geld: Vorschlag ändert keinen Betrag ohne Bestätigung; Datentrennung: Lernspeicher mandantengetrennt geprüft. konkurrierend: zwei Prüfer nehmen denselben Vorschlag parallel an bzw. korrigieren ihn – genau ein Ergebnis, der zweite erhält einen Konflikt; Deaktivierung parallel zur Vorschlagserzeugung – danach kein neuer gelernter Vorschlag. Regression E2E-066 (falscher OCR-Betrag bleibt korrigierbar) und E2E-068 (Vier-Augen-Freigabe unverändert).
- **Abhängigkeiten:** intern: WP-M9b-04.
  - extern: WP-M8a-07, WP-M8a-09, WP-M4-27, WP-M4-28 (Eingangsrechnungen, OCR-Vorschläge, Kontierung)
- **Entscheidungen:** D-06 (Kontenrahmen) und D-12 blockieren nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Mandantenübergreifendes Lernen.

### WP-M9b-54 – Ressourcenentkopplung von Prognose und Simulation
- **Ziel:** Prognose- und Simulationsmodule sind ressourcentechnisch vom Live-Buchungsbetrieb getrennt; ein Stresstest der Simulation verletzt die Buchungs-SLOs nicht.
- **Anforderungen:** `LH-6.19-NFR-019`
- **Liefergegenstände:**
  - Adapter/Worker: `infra` – getrennte Worker-Pools, eigene Queue, CPU-/Speicherlimits und eigene Datenbankverbindungen bzw. Lesereplikat für Prognose und Simulation (Kap. 01: Reporting und Simulation belasten keine Live-Buchung).
  - Backend: Lastabwurf und Zulassungssteuerung für Simulationsläufe.
  - Tests: Stresstestprofil in `tests/performance`.
  - Kennzahlen: Buchungs-SLO-Messung (Planungsdefault des Pflichtenhefts: Standard-API p95 ≤ 1 s, Angebot p95 ≤ 5 s; vorläufig laut D-05).
- **Detailverträge vorab:** Ressourcenbudget je Workerklasse; Nachweisverfahren; Monitoring-Dashboard.
- **Akzeptanzkriterien:**
  1. Während eines Simulationsstresstests bei gleichzeitiger Referenzlast bleiben die Buchungs-SLOs eingehalten; Messprotokoll mit Build-ID liegt vor (NFR-019).
  2. Der Simulationsworker besitzt keine Schreibrechte auf Live-Buchungstabellen; es entstehen keine Änderungen an Echt-Buchungen (AT-22).
  3. Bei Überlast werden Simulationsläufe gedrosselt oder abgewiesen, nie Buchungsanfragen.
  4. Dieselbe Entkopplung gilt für die Prognose: Training und Prognoseläufe unter Volllast verletzen die Buchungs-SLOs nicht, und ein Ausfall der Prognoseworker lässt Buchung und Disposition unberührt (NFR-019, Beitrag zu AT-21).
- **Tests:** positiv: Stresstest mit Referenzlast; negativ: Simulationslauf über Limit wird begrenzt; Fault-Injection: Simulationsworker stürzt ab – Buchung unberührt; Sicherheit: Schreibversuch der Simulationsrolle auf `booking` abgewiesen. Besteht AT-22 gemeinsam mit M9a. Stresstest Prognosetraining bei gleichzeitiger Referenzlast; konkurrierend: mehrere Simulationsläufe zweier Mandanten parallel – Limits je Mandant greifen, kein Mandant verdrängt die Live-Buchung oder liest Ergebnisse des anderen.
- **Abhängigkeiten:** intern: WP-M9b-03.
  - extern: WP-M5-10, WP-M5-11 (Lasttestharness, Referenzlast, SLO-Messung)
  - extern: WP-M5-03, WP-M5-04, WP-M8b-27 (Betriebs- und Observability-Erweiterungen)
  - extern: WP-M9a-14, WP-M9a-04, WP-M9a-09 (Simulations- und Prognoseworker mit Queue)
- **Entscheidungen:** D-05 (Peaklast, Hosting) blockiert die Verbindlichkeit des Nachweises, nicht den Bau.
- **Größe:** M
- **Nicht im Umfang:** Fachlogik der Simulation (M9a).

### WP-M9b-55 – Gesonderte Abnahme von Simulation und KI gegen eine Baseline
- **Ziel:** Simulation und KI werden gesondert auf fachliche Güte, Bias und Betriebsnutzen abgenommen; der Vergleich zu einer einfachen Baseline zeigt messbaren Nutzen oder dokumentiert die Einschränkung.
- **Anforderungen:** `LH-6.20-QAT-011`
- **Liefergegenstände:**
  - Tests: Abnahmeverfahren und Auswertungsharness in `tests/acceptance` (Baseline-Vergleich, Bias-Prüfung nach Gebiet/Zeit/Nutzergruppe ohne Personenbezug).
  - Datenbank: `model_approval` (Abnahmenachweis als Freigabevoraussetzung).
  - Oberfläche: UI-19 (Abnahmebericht je Modell-/Szenarioversion).
- **Detailverträge vorab:** Definition der einfachen Baseline je Anwendungsfall; Gütemaße; Bias-Dimensionen; Berichtsvorlage (siehe Offene Punkte 19).
- **Akzeptanzkriterien:**
  1. Für jedes abzunehmende Modell bzw. Simulationsverfahren liegt ein Bericht mit Vergleich zur einfachen Baseline vor, der messbaren Nutzen oder eine dokumentierte Einschränkung ausweist (QAT-011).
  2. Der Bericht enthält Güte, Bias-Prüfung und Betriebsnutzen getrennt und ist einer Modell-/Datenversion zugeordnet.
  3. Ohne abgeschlossenen Abnahmebericht ist die Produktivfreigabe des Modells gesperrt.
- **Tests:** positiv: Modell besser als Baseline; negativ: Modell schlechter → Einschränkung dokumentiert, Freigabe ohne Bericht abgewiesen. Datentrennung: Abnahmeberichte und Auswertungsdaten je Mandant getrennt; Bias-Auswertung unterdrückt Gruppen unter `kpi_aggregation_min_n`.
- **Abhängigkeiten:** intern: WP-M9b-03.
  - extern: WP-M8b-04, WP-M8b-05, WP-M8b-20 (Modellfreigabe und Governance)
  - extern: WP-M9a-05, WP-M9a-06, WP-M9a-17, WP-M9a-10 (Prognosemodelle, Simulationsverfahren, Model Registry)
- **Entscheidungen:** keine für den Bau; Betriebsdaten aus dem Pilot nötig (Umsetzungsplan §8).
- **Größe:** M
- **Nicht im Umfang:** Abnahme der ETA-Modelle (M8b, WP-M9b-45/46).

### WP-M9b-56 – Revisionsfähige Releasefreigabe mit Sicherheits- und Datenschutzprüfung
- **Ziel:** Softwareänderungen werden mit Sicherheitsbewertung, Datenschutzprüfung und revisionsfähiger Releasefreigabe dokumentiert; jedes Release besitzt einen freigegebenen Testbericht mit den betroffenen Anforderungen.
- **Anforderungen:** `LH-I.11-GOV-011`
- **Liefergegenstände:**
  - Backend/CI: Release-Datensatz, aus der CI erzeugt (Build-ID, betroffene source_keys aus `arbeitspakete.csv`, Testergebnisse aus `e2e.csv`, SBOM-Verweis); Freigabeworkflow mit Rollen Sicherheit, Datenschutz, Releaseverantwortung.
  - Datenbank: `release_approval` (Arbeitsname; append-only), `audit_event`.
  - API: Arbeitsname `/v1/governance/releases`.
  - Oberfläche: UI-20 (Releaseakte mit Bewertungen und Testbericht).
  - Invarianten (Kap. 04 §4.5): „Gegenereignis statt Änderung“ für die Releaseakte.
- **Detailverträge vorab:** Schema Releaseakte; Freigabeautomat; Verknüpfungsregel Änderung → Anforderung → Test.
- **Akzeptanzkriterien:**
  1. Ein Release besitzt einen freigegebenen Testbericht, der die betroffenen Anforderungen namentlich aufführt (GOV-011).
  2. Sicherheitsbewertung und Datenschutzprüfung sind je Release mit Prüfer, Datum und Ergebnis dokumentiert; ohne beide ist die Releasefreigabe nicht möglich.
  3. Eine freigegebene Releaseakte ist unveränderlich; Korrekturen entstehen als Nachtrag.
  4. Die Produktivverteilung prüft das Vorliegen der Freigabe.
- **Tests:** positiv: vollständige Freigabe; negativ: Freigabe ohne Datenschutzprüfung, nachträgliche Änderung der Akte, Deployment ohne Freigabe wird blockiert; Selbstfreigabe durch den Ersteller abgewiesen. konkurrierend: zwei Freigaben derselben Releaseakte bzw. Freigabe und Nachtrag parallel – genau ein konsistenter Endzustand, kein Überschreiben.
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M0-12, WP-M0-14, WP-M0-31, WP-M0-32 (CI, SBOM, signierte Images, `arbeitspakete.csv`-Pflege)
  - extern: WP-M8b-28, WP-M8b-26 (Governance-Grundfunktionen, Verantwortliche)
  - extern: WP-M8e-22, WP-M8e-30, WP-M8e-35 (Sicherheits- und Betriebsprozesse)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Inhaltliche Durchführung von Penetrationstests.

### WP-M9b-57 – Gate-Abnahme M9b: AT-20, AT-58 und Anteile an AT-21, AT-22, AT-55
- **Ziel:** Die Referenztests des M9-Gates, die M9b trägt, sind mit Build, Evidenz und Entscheidung in `e2e.csv` eingetragen; alle drei MUSS-Anforderungen haben einen grünen Test.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Tests: Akzeptanzszenarien AT-20 (E-Reichweitenkonflikt), AT-58 (Fahrzeugkontrolle E-Flotte) vollständig; AT-21 (Prognoseausfall), AT-22 (Simulationsisolation), AT-55 (Unterdeckung und KI-Bedarf) als gemeinsamer Lauf mit M9a in `tests/acceptance`.
  - Nachweis: Verknüpfung aller 54 Anforderungen mit Test und Evidenz; Liste der ins Release aufgenommenen KANN-Funktionen mit Beschlussreferenz.
- **Detailverträge vorab:** Szenariodrehbücher mit Fixtures aus WP-M9b-03; Abgrenzung der Verantwortung M9a/M9b je Test.
- **Akzeptanzkriterien:**
  1. AT-20: Fahrzeug mit niedrigem SoC vor langem Umlauf ist nicht disponierbar, eine Ladealternative wird genannt.
  2. AT-58: E-Fahrzeug unter Lade-/Reichweitenschwelle erzeugt konfigurationsgerechte Warnung oder Sperre mit korrekter Dispositionswirkung.
  3. AT-21, AT-22, AT-55 sind im gemeinsamen Lauf mit M9a grün.
  4. `LH-6.8-FLT-009`, `LH-6.8-FLT-010` und `LH-6.9-GEO-009` haben je einen grünen Test; keine offenen Mängel der Klasse A.
  5. Nicht aufgenommene KANN-Funktionen sind nachweislich ausgeschaltet.
  6. Für jedes WP dieser Datei ist die Einhaltung der Querschnittsregeln (Lesehinweise) nachgewiesen: Migration mit RLS-Negativtest je neuer Tabelle, Rechteprüfung je Endpunkt, UI-Zustände leer/Fehler/fehlendes Recht, Audit je Zustandswechsel.
  7. Die in den WPs genannten Regressionsläufe fremder Referenztests (PH-AT-001, PH-AT-004, PH-AT-017, PH-AT-018, PH-AT-020, PH-AT-021, PH-AT-022) sind gegen den M9b-Stand grün; die Zuständigkeit je Prüfschritt von AT-21, AT-22 und AT-55 zwischen M9a, M9b und M7d ist dokumentiert (Gegenstück: WP-M9a-24).
- **Tests:** AT-20, AT-21, AT-22, AT-55, AT-58; negativ je Gate-Test die Gegenprobe (AT-20: ausreichender SoC bleibt disponierbar; AT-58: Verbrenner und frischer Wert über Schwelle ohne Sperre; AT-21: Prognose wieder verfügbar ohne Nachwirkung); Regressionslauf der P1-/P2-Gates gegen den M9b-Stand, namentlich PH-AT-001, PH-AT-004, PH-AT-017, PH-AT-018, PH-AT-020, PH-AT-021, PH-AT-022.
- **Abhängigkeiten:** intern: WP-M9b-05, WP-M9b-06, WP-M9b-08, WP-M9b-09, WP-M9b-11, WP-M9b-18, WP-M9b-22, WP-M9b-47, WP-M9b-48, WP-M9b-54 (sowie alle übrigen WPs für die Nachweisführung).
  - extern: WP-M9a-06, WP-M9a-09, WP-M9a-12, WP-M9a-14, WP-M9a-17 (Prognose- und Simulationsdienst für AT-21, AT-22, AT-55)
  - extern: WP-M5-01, WP-M5-31 (Abnahmeverfahren und Mangelklassen)
  - extern: WP-M7d-32, WP-M7d-20 (Vorschlagsübernahme und Publikation im Dienstplan für AT-55 (WP-M7d-32))
- **Entscheidungen:** D-12 (aufgenommene KANN-Funktionen), D-09 (reale Telemetrie-/Ladeinfrastruktur) blockieren die Produktivschaltung, nicht die Sandbox-Abnahme.
- **Größe:** S
- **Nicht im Umfang:** Abnahme der AI-/SIM-Anforderungen (M9a).

## Offene Punkte

1. **Modulzuordnung Telematik:** Die Zielstruktur (Umsetzungsplan §3) nennt unter `adapters/` weder Telematik noch Ladesystem noch Sprache. `LH-6.17-INT-013`, `LH-I.10-AST-010`, `LH-6.22-DMG-028` und `LH-6.13-SUP-011` brauchen eine Festlegung (Arbeitsnamen `backend/adapters/telematics`, `backend/adapters/voice`).
2. **Fehlende Parameter im Katalog:** `kataloge.csv` enthält für E-Mobilität nur `min_battery_soc_pct`, `min_return_soc_pct`, `charge_reservation_min`. Es fehlen Parameter für: Höchstalter von Batteriewerten (`LH-6.8-FLT-012`), Quellenrangfolge (`LH-6.17-INT-013`), Reservegrenze je Fahrzeug als Parameter oder Stammdatum (`LH-6.8-FLT-009`), Gewichte der Haltestellenbewertung (`LH-6.9-GEO-010`), Prognosenutzung in der Positionierung (`LH-6.5-OPT-020`), Warteliste und Nachrückregel (`LH-6.4-BKG-017`), maximale Anschlusswartezeit (`LH-I.5-FED-031`), Mindestzahl Mandanten je Benchmark (`LH-I.6-KPI-035`), gelernte Vorschläge je Mandant (`LH-I.1-DOC-029`) sowie die Release-Aufnahmeschalter der KANN-Funktionen. Ob der Katalog von 80 Parametern erweitert werden darf, ist zu entscheiden.
3. **Fehlende Kennzahlen im Katalog:** Der KPI-Katalog (48) enthält weder CO2e/Emissionen (`LH-I.2-FCO-021`, `LH-6.14-ANA-011`) noch API-Verfügbarkeit, Antwortzeit, Abbruchrate je Partner (`LH-I.5-FED-030`), Erstlösungsquote und Reaktionszeit (`LH-6.13-SUP-013`), Stillstände und Fahrleistung als eigene Kennzahl (`LH-6.8-FLT-013`) oder die Trefferquote von Anomaliehinweisen (`LH-I.8-AUT-014`). Definitionen mit Zähler, Nenner und Ausschlüssen fehlen im Pflichtenheft.
4. **UI-Zuordnung:** Kap. 06 §6.9 kennt keinen Bildschirm für Ladepunkt-/Ladeplanung (hier UI-11 angenommen), Vorlagenredaktion (`LH-6.12-COM-013`, hier UI-20/Admin angenommen), Releaseakte (`LH-I.11-GOV-011`, hier UI-20) und Emissionsfaktoren (hier UI-15). Bestätigung oder neue UI-IDs erforderlich.
5. **Ablehnungsgrund Reichweite/Warteliste:** Für `LH-6.5-OPT-021` und `LH-6.4-BKG-017` ist nicht festgelegt, unter welchem Normgrund eine Ablehnung wegen Reichweite zählt (`Kapazitätsablehnungen` oder neuer Grund) und ob ein Wartelisteneintrag die ursprüngliche Ablehnung in der Statistik verändert.
6. **Ladeplanung:** `LH-6.8-FLT-011` nennt „Strombedarf“, das Pflichtenheft aber weder Lastmanagement am Netzanschluss noch Strompreise oder Ladekurven. Annahme: Strombedarf = Energiebedarf des Folgeumlaufs; die Abnahme „Simulation vermeidet Überschneidung“ wird als Planer-Probelauf verstanden, nicht als Szenariosimulation aus M9a.
7. **„Qualifizierter Diagnosecode“** (`LH-I.10-AST-010`): Das Pflichtenheft definiert weder Codekatalog noch Qualifizierungsregel noch Datenformat der Hersteller-/Providerquelle (abhängig von D-09).
8. **„Definierte Automation“** (`LH-6.7-OPS-015`): Nicht festgelegt, welche Empfehlungen automatisch wirksam werden dürfen und wer die Regel freigibt. Annahme: nur über freigegebene Regelrevision aus dem Automatisierungsmodul (M8d).
9. **Warteliste und Föderation** (`LH-6.4-BKG-017`): „Definierte Regeln“ für das Nachrücken (Reihenfolge, Angebotsfrist, Preisbindung) fehlen; ebenso, ob Wartelisten für Partnersegmente gelten.
10. **Priorisierung von Ersatzvorschlägen** (`LH-6.13-SUP-012`): Kriterien der Priorisierung (z. B. Unterstützungsbedarf, Abfahrtsnähe, Anschluss) sind nicht genannt.
11. **Auslöser für Sonderbetrieb** (`LH-6.1-SYS-012`): Datenquellen für „Veranstaltung“ und „Wetterlage“ sind nicht benannt; die Abnahme prüft nur den zeitgesteuerten Fall. Annahme: Datum/Zeitraum zuerst, Veranstaltung/Wetter über manuell oder per Adapter gesetzte Auslöser.
12. **Semantik `sharing_vehicle_quota`** (`LH-I.5-FED-032`): Der Katalog nennt „Externes Fahrzeugkontingent“ auf Ebene Dienst/Partner; die Abnahme spricht von „überschüssigen Plätzen“. Unklar, ob das Kontingent Fahrzeuge oder Plätze zählt und wie es zu `min_capacity_reserve` steht.
13. **Sprachassistent** (`LH-6.13-SUP-011`): Anbieter, Sprachen, Schwelle für „unklar“, Aufzeichnungs- und Löschregeln sowie Einwilligung sind nicht spezifiziert; keine der Entscheidungen D-01…D-12 deckt die Anbieterwahl ab.
14. **Quelle der Emissionsfaktoren** (`LH-I.2-FCO-021`): Das Pflichtenheft verlangt die Nennung der Quelle, legt aber keine fest.
15. **„Sichere Planung“** (`LH-I.6-KPI-034`): Welche Kennzahlen die „Verschlechterung sicherer Planung“ messen, ist nicht definiert. Annahme: Pünktlichkeitskennzahlen und Verletzungen der geprüften Mindestfahrzeit aus M8b.
16. **„Faire Verteilung“ und Präferenzen** (`LH-6.23-SCH-033`): Maß für Fairness und Datenmodell der Präferenzen fehlen; mitbestimmungsrelevant (D-10).
17. **Parameterebene Mitarbeitergruppe** (`LH-6.24-TIM-018`): `max_daily_work_minutes` und `min_rest_hours` sind im Katalog auf Ebene Rechtsraum definiert; eine Ebene „Mitarbeitergruppe“ und eine Tabelle für Gruppen kennt das Pflichtenheft nicht.
18. **Mehrregionenbetrieb** (`LH-I.4-MUL-025`): Umfang von „lokal gültigen Verkehrsregeln“, Umrechnungsregeln zwischen Währungen, Steuerbehandlung und Feiertagsquelle sind nicht spezifiziert; die Abnahme prüft nur den Zeitzonenfall.
19. **Baseline und Bias** (`LH-6.20-QAT-011`): „Einfache Baseline“, Gütemaße und Bias-Dimensionen sind nicht definiert; Abstimmung mit M9a nötig, damit dieselben Definitionen gelten.
20. **Abgrenzung M9a/M9b:** `LH-6.5-OPT-020`, `LH-6.5-OPT-023`, `LH-6.23-SCH-034`, `LH-6.14-ANA-012`, `LH-6.10-PAY-017`, `LH-6.19-NFR-019` und `LH-6.20-QAT-011` hängen an Prognose- und Simulationsdiensten aus M9a. Deren Schnittstellen (Prognose je Gebiet/Horizont, Szenarioläufe, Model Registry) müssen vor Beginn dieser WPs feststehen; die Zuordnung der Gate-Tests AT-21, AT-22 und AT-55 zu einer führenden Einheit ist zu bestätigen.
21. **Konzernübersicht** (`LH-I.4-MUL-024`): „Rechtlich zulässig“ ist nicht konkretisiert; Kap. 04 §4.1 schließt Zugriff allein wegen gleichen Rechtsträgers aus. Welche Kennzahlen eine Konzernsicht zeigen darf, ist festzulegen (D-02, D-06/D-07).
22. **Telemetrie-Staleness** (`LH-6.8-FLT-012`): Die Abnahme lässt „Warnung oder sichere Fallback-Regel“ offen; welche Variante je Fahrzeugtyp gilt und ob ein veralteter Wert sperrt, ist nicht festgelegt.
23. **Datengrundlage Eco-Driving** (`LH-6.6-DRV-020`): Das Pflichtenheft nennt weder Datenquelle noch Hinweiskatalog. Fahrbeobachtungen (`driving_observation`) unterliegen der Zweckbindung der ETA (D-10); ob sie für Eco-Hinweise genutzt werden dürfen, ist zu entscheiden. Ohne Entscheidung liefert WP-M9b-17 nur fahrzeug-/energiebezogene Hinweise ohne Fahrerprofil.
24. **Fußwegqualität und Sicherheit** (`LH-6.9-GEO-010`, `LH-6.5-OPT-019`, `LH-6.3-PAX-017`): Kriterien für „Fußwegqualität“, „Sicherheit“ und „geprüft sicher“ sowie die Gewichtung gegen den Fahrzeugumweg sind nicht definiert. Ein Fußwegrouting über Distanz/Gehzeit hinaus liefert keine Nachbareinheit (M7a schließt es in WP-M7a-24 ausdrücklich aus, Offener Punkt 36 dort); Basis ist der Routingadapter aus M2. Datenquelle für Sicherheitsmerkmale (Beleuchtung, Querungen) hängt an D-09.
25. **Zuständigkeit AT-55:** Der Test berührt drei Einheiten – Nachfrageprognose (M9a), Bedarfsempfehlung und Besetzungsvorschlag (WP-M9b-47, WP-M9b-48) sowie Vorschlagsübernahme und Publikation (M7d, WP-M7d-32). `LH-6.23-SCH-034` (hier) und `LH-6.23-SCH-028` (M7d) überlappen fachlich; es ist festzulegen, ob WP-M9b-48 die Funktion aus WP-M7d-32 erweitert oder ersetzt.
26. **Überschneidung Tarif-Sandbox** (`LH-6.10-PAY-017`): M9a enthält mit `LH-6.16-SIM-008` (KANN, WP-M9a-22) eine fachlich überlappende Tarifsimulation. Beide sind KANN und getrennt zu priorisieren; wer die gemeinsame Implementierung führt, ist offen. WP-M9b-34 setzt deshalb nur die Simulationsumgebung voraus.
27. **Kopplung an M9a-Reihenfolge:** WP-M9b-01 und WP-M9a-01 hängen wechselseitig an der Abstimmung der Leseport-Verträge. Das ist keine technische Zyklusabhängigkeit: WP-M9a-01 liefert die Verträge zuerst, WP-M9b-01 hängt davon ab und zeichnet gegen (in WP-M9a-01 als „Vollabnahme erst mit: WP-M9b-01“ geführt). Ein gemeinsamer Vertragstermin vor Codebeginn beider Einheiten bleibt organisatorisch nötig.
28. **Rollen:** Für Ladepunktpflege, Energiestamm, Kandidatenfreigabe, Faktorpflege, Vorlagenredaktion, Releasefreigabe und Sprachwunsch-Bestätigung nennt das Pflichtenheft keine eigenen Rollen. Die Rechtematrix in WP-M9b-01 ordnet sie bestehenden Rollen zu; neue Rollen bedürfen der Bestätigung durch den Auftraggeber.
29. **Prüfbarkeit unscharfer Abnahmen:** Mehrere Originalabnahmen prüfen nur einen Teil der Beschreibung (`LH-6.1-SYS-012` nur Zeitraum, `LH-I.4-MUL-025` nur Zeitzonen, `LH-6.8-FLT-015` nur Zeitfenster/Ausstattung, `LH-I.5-FED-029` nur Fahrzeugausfall). Die zusätzlichen Kriterien dieser Datei für die übrigen Teilaspekte sind aus der Beschreibung abgeleitet und vom Auftraggeber als Abnahmeumfang zu bestätigen.
30. **Reihenfolgekonflikt: Zyklus WP-M9b-01 ↔ WP-M9a-01.** WP-M9b-01 hängt für die Gegenzeichnung der Leseport-Verträge an WP-M9a-01; WP-M9a-01 nennt umgekehrt WP-M9b-01 als Voraussetzung. Beide liegen im selben Meilenstein M9 (keine Abhängigkeit auf einen späteren Meilenstein), bilden aber einen Zyklus. **Aufgelöst:** WP-M9b-01 behält die Abhängigkeit auf WP-M9a-01 (Vertrag zuerst, Nutzung und Gegenzeichnung danach); die Gegenrichtung ist in WP-M9a-01 aus den Abhängigkeiten entfernt und dort als „Vollabnahme erst mit: WP-M9b-01“ geführt (vgl. Punkt 27 hier und Offener Punkt 22 in M9a). WP-M9b-57 zeigt bewusst nur auf die liefernden Fach-WPs aus M9a (WP-M9a-06, -09, -12, -14, -17) und nicht auf WP-M9a-24, weil WP-M9a-24 seinerseits von WP-M9b-57 abhängt (Offener Punkt 23 in M9a).
