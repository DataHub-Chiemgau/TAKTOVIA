# M7a – Fahrgast, Buchung, Disposition und Geodaten (Betriebsausbau)

- **Meilenstein:** M7 – Betriebsausbau, Strom „Fahrgast und Leitstelle“ (setzt M5 voraus; Nachbareinheiten M7b Kommunikation/Support/Leitstelle, M7c Fahrer/Flotte, M7d Personal/Zeit)
- **Phase:** P2 (8 MUSS, 25 SOLL; Phase und Priorität jeder Anforderung unverändert)
- **Anzahl Anforderungen:** 33 (PAX 7, BKG 10, OPT 8, GEO 8)
- **Anzahl WPs:** 40 (33 mit je genau einer Anforderung, 7 Enabler)

**Ziel:** Nach M7a kann ein Fahrgast eine bestehende Buchung ändern, Serien- und Gruppenfahrten sowie Fahrten für Dritte buchen, multimodale Reiseoptionen sehen, Fahrten bewerten, ein Abholfenster weitergeben und dieselbe Buchung in Web, nativer App und Callcenter wiederfinden. Die Disposition beachtet Sperrbereiche, verbotene Strecken, versionierte Restriktionen, Parallelfahrtenverbot, Anschlusspuffer, Nahhorizont, Pausen-, Depot- und Rückkehrregeln und protokolliert ihre Entscheidungen datensparsam. Haltepunkte erhalten Barrierefreiheitseigenschaften, einen redaktionellen Freigabeprozess, befristete Gültigkeit und einen validierten Geodatenimport. Alle Erweiterungen verwenden denselben Tarif-, Kapazitäts- und Planrevisionskern aus M2/M3; es entsteht kein zweiter Buchungs- oder Kapazitätsweg.

**Gate-Anteil M7a (Umsetzungsplan §4, Gate M7):** AT-06 (Serienbuchung mit Ausnahme) und AT-12 (Callcenter kontra App). AT-12 benötigt zusätzlich den Callcenter-Arbeitsplatz UI-06 aus M7b (siehe Offene Punkte 2). Die übrigen Gate-Tests des M7 (AT-17, AT-38, AT-42 bis AT-44, AT-46, AT-52, AT-56, AT-57, AT-62, AT-63, E2E-069, E2E-071, E2E-100) gehören zu M7b, M7c und M7d.

**Lesehinweise:**

- Kurz-IDs in Klammern hinter Akzeptanzkriterien (z. B. BKG-007) verweisen auf den `source_key` des jeweiligen WP.
- „Vorschlag“ kennzeichnet Namen von Tabellen, Pfaden oder Parametern, die in `kernschema.sql`, Kap. 04 §4.4, `openapi.yaml`, Kap. 05 §5.7 oder `kataloge.csv` **nicht** vorkommen. Sie sind im Detailvertrag (WP-M7a-01) festzulegen und stehen unter „Offene Punkte“.
- „Planungsdefault“ kennzeichnet Werte, die das Pflichtenheft selbst als Vorschlag ausweist (z. B. Hold-Dauer 180 s, Kap. 02 §7.2).
- Größen: S bis 1 Personenwoche, M bis 3, L bis 6.
- Zustandsnamen in Zustandsautomaten (z. B. `SKIPPED`, `IN_REVIEW`, `VALIDATED`) sind Entwurfsvorschläge, soweit sie nicht in Kap. 02 §7.3, Kap. 05 oder `openapi.yaml` stehen; verbindlich werden sie erst mit dem Detailvertrag aus WP-M7a-01.
- Jede neue oder erweiterte Ansicht braucht die Zustände leer, Fehler, fehlendes Recht und veralteter Stand sowie eine karten- und Drag-and-drop-freie Bedienalternative (Kap. 06 §6.9, Kap. 08 §8.4 Gate „UX“); das gilt auch dort, wo ein WP sie nicht einzeln aufzählt, und wird in WP-M7a-01 (Vertrag) und WP-M7a-40 (Nachweis) geprüft.

## Übersicht

| WP-ID | Titel | Anforderungen (Anzahl) | Größe | hängt ab von |
|---|---|---|---|---|
| WP-M7a-01 | Detailverträge und Zustandsautomaten M7a | – (Enabler) | M | WP-M0-31, WP-M0-13, WP-M5-31 |
| WP-M7a-02 | Migration der M7a-Tabellen mit RLS | – (Enabler) | M | WP-M7a-01, WP-M1-01, WP-M0-15, WP-M1-46 |
| WP-M7a-03 | Buchungsänderung als Revalidierung | 1 | M | WP-M7a-01, WP-M7a-02, WP-M2-21, WP-M2-22, WP-M2-23, WP-M2-30, WP-M3-02, WP-M3-39, WP-M3-40 |
| WP-M7a-04 | Ereigniszustellung von Fahrgaständerungen an Fahrer und Leitstelle | 1 | S | WP-M7a-03, WP-M2-28, WP-M2-32, WP-M3-35, WP-M3-24 |
| WP-M7a-05 | Buchungsregeln je Verkehrsprodukt | 1 | M | WP-M7a-01, WP-M1-20, WP-M1-21, WP-M1-22, WP-M2-03, WP-M2-12, WP-M2-21 |
| WP-M7a-06 | Bestätigungsfristen für zeitkritische Angebote | 1 | S | WP-M7a-05, WP-M1-20, WP-M1-22, WP-M2-21, WP-M2-22, WP-M2-23 |
| WP-M7a-07 | Serienbuchung: Serienregel und Einzeltermine | 1 | M | WP-M7a-02, WP-M7a-05, WP-M2-21, WP-M2-22, WP-M2-23, WP-M2-30, WP-M2-24, WP-M1-15, WP-M7d-11 |
| WP-M7a-08 | Serienbuchung: Vorbuchungsgrenze und Regel für fehlgeschlagene Termine | 1 | M | WP-M7a-07, WP-M1-20, WP-M1-22 |
| WP-M7a-09 | Serienbuchung in der Passenger-Weboberfläche | – (Enabler) | M | WP-M7a-07, WP-M7a-08, WP-M2-35, WP-M2-37, WP-M2-41, WP-M2-42, WP-M2-43, WP-M2-45, WP-M1-43 |
| WP-M7a-10 | Gruppenbuchung mit individuellen Kapazitätsanforderungen | 1 | M | WP-M7a-02, WP-M2-14, WP-M2-22 |
| WP-M7a-11 | Buchung für Dritte | 1 | M | WP-M7a-02, WP-M1-04, WP-M1-08, WP-M1-46, WP-M2-21, WP-M2-22, WP-M2-23, WP-M2-36, WP-M3-36, WP-M3-37 |
| WP-M7a-12 | Ein Buchungsbestand für Web, native App und Callcenter | 1 | M | WP-M7a-11, WP-M7a-38, WP-M7b-35, WP-M7b-17 |
| WP-M7a-13 | Standardisierte No-Show-Regeln | 1 | M | WP-M7a-01, WP-M7a-02, WP-M1-20, WP-M1-22, WP-M3-29, WP-M3-34, WP-M3-36, WP-M3-52 |
| WP-M7a-14 | Buchungscode und QR-Token für datensparsamen Check-in | 1 | M | WP-M7a-02, WP-M3-30, WP-M3-29 |
| WP-M7a-15 | Multimodale Reiseoption mit Umstiegen | 1 | M | WP-M7a-01, WP-M2-37, WP-M2-41, WP-M6a-09, WP-M6a-28, WP-M6a-26, WP-M6a-08 |
| WP-M7a-16 | Erneut buchen: wiederkehrende Ziele und letzte Routen | 1 | S | WP-M7a-02, WP-M2-36, WP-M2-38, WP-M2-21 |
| WP-M7a-17 | Bewertung und kategorisiertes Feedback | 1 | M | WP-M7a-02, WP-M2-45, WP-M3-29 |
| WP-M7a-18 | Live-Fahrzeugposition nur in berechtigter Phase | 1 | M | WP-M7a-01, WP-M2-43, WP-M3-34, WP-M3-35 |
| WP-M7a-19 | Abholfenster weitergeben | 1 | M | WP-M7a-02, WP-M7a-18, WP-M2-32, WP-M2-33 |
| WP-M7a-20 | Einfache Sprache, Hilfetexte und kontextsensitive Fehlermeldungen | 1 | M | WP-M7a-01, WP-M2-13, WP-M2-35, WP-M2-36, WP-M2-37, WP-M2-41, WP-M2-42, WP-M2-43 |
| WP-M7a-21 | Sperrbereiche nach Zeit, Raum, Dienst und Fahrzeugklasse | 1 | M | WP-M7a-02, WP-M1-20, WP-M1-22, WP-M1-46, WP-M2-04, WP-M2-12, WP-M2-15 |
| WP-M7a-22 | Verbotene Strecken und Fahrzeugrestriktionen im Routing | 1 | M | WP-M7a-21, WP-M0-30, WP-M2-10, WP-M2-17, WP-M3-03, WP-M3-28 |
| WP-M7a-23 | Versionen und geplante Aktivierung von Zonen und Restriktionen | 1 | M | WP-M7a-21, WP-M1-20, WP-M1-22, WP-M2-04, WP-M2-21 |
| WP-M7a-24 | Haltestelleneigenschaften für Barrierefreiheit | 1 | M | WP-M7a-02, WP-M2-05, WP-M2-38, WP-M2-21 |
| WP-M7a-25 | Redaktionelle Freigabe virtueller Haltepunkte | 1 | M | WP-M7a-02, WP-M7a-24, WP-M1-07, WP-M1-08, WP-M1-22, WP-M1-46, WP-M2-05, WP-M1-43 |
| WP-M7a-26 | Temporäre Veranstaltungshaltestellen | 1 | S | WP-M7a-25 |
| WP-M7a-27 | Versionierter Geodatenimport mit Validierung | 1 | M | WP-M7a-23, WP-M7a-24, WP-M7a-25, WP-M1-15, WP-M1-11 |
| WP-M7a-28 | Knotenspezifische Umsteige- und Wegezeiten | 1 | M | WP-M7a-02, WP-M6a-08, WP-M6b-02, WP-M6b-10 |
| WP-M7a-29 | Anschlussprioritäten für Bahnhöfe | 1 | M | WP-M7a-28, WP-M2-17, WP-M2-18, WP-M2-19, WP-M2-20, WP-M6a-26, WP-M6a-27, WP-M6a-28 |
| WP-M7a-30 | Parallelfahrtenverbot mit Linienalternativen und Ausnahmen | 1 | M | WP-M7a-01, WP-M1-20, WP-M1-23, WP-M2-21, WP-M2-13, WP-M6a-29, WP-M6a-28, WP-M6a-26 |
| WP-M7a-31 | ETA mit prognostizierter Fahrzeugposition und Unsicherheit | 1 | M | WP-M7a-01, WP-M2-10, WP-M2-28, WP-M3-34, WP-M4-61, WP-M4-62 |
| WP-M7a-32 | Stabilitätsschutz und nicht umplanbarer Nahhorizont | 1 | M | WP-M7a-01, WP-M7a-02, WP-M1-20, WP-M1-21, WP-M2-16, WP-M2-17, WP-M2-19 |
| WP-M7a-33 | Auswirkungsprüfung manueller Leitstelleneingriffe | 1 | M | WP-M7a-32, WP-M3-40, WP-M3-36 |
| WP-M7a-34 | Fahrzeugabhängige Erreichbarkeit, Pausen und Depotbedingungen | 1 | M | WP-M7a-22, WP-M2-17, WP-M2-15, WP-M3-07, WP-M3-03, WP-M4-03, WP-M4-17 |
| WP-M7a-35 | Rückkehr- und Abstellortregeln | 1 | M | WP-M7a-34, WP-M1-20, WP-M1-21 |
| WP-M7a-36 | Datensparsames Entscheidungsprotokoll der Disposition | 1 | M | WP-M7a-02, WP-M2-11, WP-M2-13, WP-M2-17, WP-M3-36 |
| WP-M7a-37 | Native Passenger-App: Grundgerüst | – (Enabler) | L | WP-M7a-01, WP-M7a-02, WP-M2-32, WP-M1-04, WP-M1-06, WP-M1-10, WP-M3-24, WP-M0-06 |
| WP-M7a-38 | Native Passenger-App: Kernreise UI-01 bis UI-05 | – (Enabler) | L | WP-M7a-37, WP-M2-23, WP-M2-30, WP-M2-29, WP-M2-35, WP-M2-36, WP-M2-37, WP-M2-41, WP-M2-42, WP-M2-43 |
| WP-M7a-39 | Native Passenger-App: P2-Funktionen dieser Einheit | – (Enabler) | M | WP-M7a-38, WP-M7a-03, WP-M7a-09, WP-M7a-10, WP-M7a-11, WP-M7a-14 bis WP-M7a-20 |
| WP-M7a-40 | Gate-Nachweis M7a und Regressionssuite | – (Enabler) | M | WP-M7a-01 bis WP-M7a-39, WP-M7b-35, WP-M7b-18 |

## Arbeitspakete

### WP-M7a-01 – Detailverträge und Zustandsautomaten M7a

- **Ziel:** Vor Codebeginn liegen für alle 33 Anforderungen der Einheit OpenAPI-Ergänzungen, DDL-Entwürfe, Zustandsautomaten und JSON-Schemas reviewt vor; jede MUSS-Anforderung ist mit Verantwortlichem, Detailvertrag und mindestens einem positiven Test verknüpft (Kap. 08 §8.4).
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - API: OpenAPI-3.1-Ergänzungen zu den Katalogpfaden aus Kap. 05 §5.7: `/v1/bookings` (Änderung, Bewertung, Freigabe-Token, Buchungscode), `/v1/booking-series`, `/v1/ride-offers` (Gruppe, Bestätigungsfrist), `/v1/delegations`, `/v1/passengers`, `/v1/services`, `/v1/service-areas`, `/v1/stops`, `/v1/transfer-hubs`, `/v1/runs/{id}/revisions`; jeweils mit Negativbeispielen im Fehlermodell Kap. 05 §5.1 (`409`, `412`, `422`). Pfade, die der Katalog nicht nennt (Restriktionen, Geodatenimport, Feedback, Teilen), werden als Vorschlag ausgewiesen und mit dem Auftraggeber bestätigt (Offene Punkte 5).
  - Datenbank: DDL-Entwürfe für `booking_series`, `series_occurrence`, `booking_change`, `passenger_trip` (Erweiterung), `delegated_access`, `passenger_preference` (Erweiterung), `transfer_hub`/`transfer_rule` (Erweiterung), `stop_revision` (Erweiterung) sowie für die in Kap. 04 §4.4 fehlenden Objekte (Offene Punkte 4).
  - Backend: Zustandsautomaten „Buchungsänderung“, „Serie“ und „Serientermin“, „virtueller Haltepunkt/Redaktionsfreigabe“, „Geodatenimport“, „Restriktionsrevision“, „No-Show-Feststellung“, „Freigabe-Token“.
  - Konfigurationsparameter: Bestandsprüfung und – wo aus M1/M2 noch nicht vorhanden – JSON-Schemas (Werttyp, Einheit, Min/Max, Ebene laut `kataloge.csv`) für **alle** in dieser Einheit verwendeten Katalogparameter: `min_booking_lead_min`, `max_booking_horizon_days`, `pickup_window_min`, `pooling_allowed`, `arrival_grace_min`, `no_show_grace_min`, `stop_walk_max_m`, `transfer_min_time_min`, `interchange_min_time_min`, `advance_arrival_buffer_min`, `max_reassignments`, `protected_stop_horizon_min`, `min_capacity_reserve`, `wheelchair_boarding_sec`, `stop_dwell_sec`, `eta_model_quantile`, `eta_max_data_age_sec`, `eta_fallback_policy`, `eta_personalization_enabled`, `min_battery_soc_pct`, `min_return_soc_pct`, `max_daily_work_minutes`, `customer_notify_before_pickup_min`, `notification_quiet_hours`; Liste der fehlenden Parameter (Offene Punkte 6).
  - Oberfläche: Bildschirmverträge für die erweiterten Ansichten UI-01 bis UI-05, UI-07, UI-10 und UI-17 mit den Zuständen leer, Fehler, fehlendes Recht, veralteter Stand und der karten-/Drag-and-drop-freien Alternative (Kap. 06 §6.9); Festlegung, in welcher Ansicht dienstbezogene Dispositions- und Buchungsparameter gepflegt werden (Offene Punkte 37).
  - Datenbank: Löschklasse und Zweck je neuer Tabelle mit Personenbezug (Präferenzen, Feedback, Vertretung, Freigabe-Token, Geräteregistrierung, Entscheidungsprotokoll) als Eintrag für das Lösch-/Auskunftsinventar.
  - Tests: Zuordnungstabelle Anforderung → Detailvertrag → Testfall für `arbeitspakete.csv` (Spalten `detailvertrag`, `testfall`); Liste der semantischen Contract-Tests nach Kap. 05 §5.8, die neue Pfade dieser Einheit betreffen (letzter Platz, abgelaufene Holds, Manipulation `tenant_id`, unzulässige Fremd-ID, Offline-Check-in auf alter Planrevision); Fixturekatalog (zwei Mandanten, Testgraph mit gesperrtem Abschnitt, Fahrplan-Testfeed, Positionsaufzeichnung, Feiertags-/Schließtagskalender) mit lieferndem WP.
- **Detailverträge vorab:** dieses WP ist die Vertragsarbeit; Eingang sind Kap. 01 §1.4–1.6, Kap. 04 §4.4–4.5, Kap. 05 §5.7/§5.9, Kap. 06 §6.9.
- **Akzeptanzkriterien:**
  1. Für jede der 33 Anforderungen existiert ein reviewter Detailvertrag mit benanntem Verantwortlichen.
  2. Die ergänzte OpenAPI besteht `validate_artifacts.py`; alle `$ref` sind auflösbar, `operationId`s eindeutig.
  3. Jeder Zustandsautomat nennt Zustände, Übergänge, auslösenden Akteur, Fehlerübergänge und das Verhalten bei konkurrierender Revision.
  4. Jeder als „Vorschlag“ markierte Name ist entweder bestätigt oder als offener Punkt mit Verantwortlichem geführt.
  5. Für jede erweiterte Ansicht liegt ein Bildschirmvertrag mit Leer-, Fehler-, Rechte- und Veraltet-Zustand sowie tastaturbedienbarer Alternative vor.
  6. Für jeden verwendeten Katalogparameter ist ein Schema mit Wertebereich aus `kataloge.csv` nachgewiesen (vorhanden oder ergänzt); jede neue Tabelle mit Personenbezug hat Löschklasse und Zweck.
- **Tests:** positiv: Vertragsprüfung in CI (Parsbarkeit, Beispiele gültig). negativ: absichtlich ungültiges Beispiel schlägt in CI fehl.
- **Abhängigkeiten:** intern: keine.
  - extern: WP-M0-31, WP-M0-13 (Detailvertragsprozess, Vorlage, CI-Vertragsprüfung)
  - extern: WP-M5-31 (abgenommener P1-Stand der Verträge für Buchung, Disposition, Geodaten)
- **Entscheidungen:** D-09 (Routinganbieter bestimmt Vertragsform der Restriktionen; blockiert nicht den Vertragsentwurf), D-11 (Löschklassen für Feedback, Positionsfreigabe, Entscheidungsprotokoll; blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Verträge für Callcenter UI-06, Supportfälle und Massenkommunikation (M7b); ETA-Modellverträge `/v1/eta/models/{id}/quality` (M8).

### WP-M7a-02 – Migration der M7a-Tabellen mit RLS

- **Ziel:** Alle in dieser Einheit neuen oder erweiterten Tabellen sind per Flyway migriert, mandantengetrennt und unter der Runtime-Rolle getestet.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Datenbank: Migrationen (expand/contract, Kap. 04 §4.7) für `booking_series`, `series_occurrence` (Serie 1:n Termine mit eigenem Erfolg/Fehler), Erweiterungen von `booking_change`, `passenger_trip`, `passenger_preference`, `delegated_access`, `stop_revision`, `route_stop` (Schutzkennzeichen, Stopptyp Rückkehr), `transfer_hub`, `transfer_rule`, `service_area`; neue Tabellen für Restriktionen, Geodatenimport, Fahrgastfeedback, Freigabe-Token, Buchungscode/QR-Token, Dispositionsentscheidung und Geräte-/Push-Registrierung der nativen App (Namen als Vorschlag, Offene Punkte 4 und 34). Tabellen, die laut Kap. 04 §4.4 bereits in M2, M3 oder M6a entstehen, werden nur erweitert, nicht neu angelegt (Bestandsabgleich im Detailvertrag). Jede Tabelle: Primärschlüssel `(tenant_id,id)`, zusammengesetzte lokale FKs, RLS USING/WITH CHECK über `current_tenant()`, `revision`, `created_at`, Urheber, Löschklasse (Kap. 04 §4.1/§4.4).
  - Datenbank: Geo-GiST-Indizes für Restriktionsgeometrien; Arbeitslistenindex `(tenant_id,state,updated_at)`.
  - Datenbank: Invariante „Nicht überlappende aktive Tarif-/Policy-/Dienstversion“ (Kap. 04 §4.5, dort „noch umzusetzen“) als Exclusion Constraint bzw. Scope-Sperre für Restriktions- und Zonenrevisionen vorbereiten.
  - Tests: Fixtures mit zwei Mandanten für alle neuen Tabellen.
- **Detailverträge vorab:** DDL aus WP-M7a-01; Backfillplan für neue Pflichtspalten an `stop_revision` und `booking_change`.
- **Akzeptanzkriterien:**
  1. Migration läuft frisch und aus dem freigegebenen M5-Stand fehlerfrei.
  2. Ohne Tenantkontext liefert jede neue Tabelle null Zeilen; ein Schreibversuch mit fremder `tenant_id` scheitert an WITH CHECK.
  3. Ein lokaler Fremdschlüssel auf ein Objekt eines anderen Mandanten ist nicht anlegbar.
  4. Backfilljobs sind fortsetzbar; neue Pflichtspalten werden erst nach Backfill validiert.
  5. Jede neue Tabelle mit Personenbezug trägt eine Löschklasse und ist im Lösch-/Auskunftsinventar eingetragen; Token- und Codetabellen speichern nur Hashes.
  6. Die Entscheidungstabelle ist für die Runtime-Rolle append-only (kein UPDATE/DELETE); Löschung nur über den kontrollierten Lifecycleprozess (Kap. 04 §4.5 „Gegenereignis statt Änderung“ sinngemäß).
- **Tests:** positiv: Migration frisch/aus Vorstand. negativ: Cross-Tenant-Lesen und -Schreiben, fehlender Kontext. konkurrierend (Datentrennung): Poolwiederverwendung mit wechselndem Tenantkontext; zwei parallele Aktivierungen überlappender Restriktionsrevisionen.
- **Abhängigkeiten:** intern: WP-M7a-01.
  - extern: WP-M1-01, WP-M0-15, WP-M1-46 (RLS-Harness, DB-Rollen (Migrator/Runtime), Audit-Grundlage)
- **Entscheidungen:** D-02 (Isolationsstufe; RLS-Default, blockiert nicht den Bau).
- **Größe:** M
- **Nicht im Umfang:** Fachlogik auf den Tabellen (jeweiliges Fach-WP); Partitionierung (erst nach gemessenem Volumen, Kap. 04 §4.7); Bearbeitung von Auskunfts-/Löschanfragen über `/v1/privacy/requests` (M8).

### WP-M7a-03 – Buchungsänderung als Revalidierung

- **Ziel:** Ändert ein Fahrgast oder Disponent Zeit, Start, Ziel, Personenzahl oder Mobilitätshilfen, wird die Anfrage vollständig neu validiert; die alte Zusage bleibt bis zur erfolgreichen Übernahme der neuen bestehen.
- **Anforderungen:** `LH-6.4-BKG-006`
- **Liefergegenstände:**
  - Backend: Änderungsbefehl im Modul `booking-dispatch`: neue Zulässigkeitsprüfung (Gebiet, Zeit, Produkt, Unterstützungsbedarf, Kap. 01 §1.4 Schritt 1–3), neues Angebot mit Tarif-/Policyversion, Hold auf die geänderte Kapazität im selben Kapazitätslock wie die Freigabe der alten Abschnitte, atomarer Tausch; bei Konflikt bleibt die bestehende Buchung unverändert. Zustände der Änderung: `REQUESTED → OFFERED → ACCEPTED | REJECTED | EXPIRED`.
  - Datenbank: `booking_change` (Änderungsannahme, Akteur, Grund, alte/neue Zusage), `booking` (`promise_snapshot`, `revision`), `capacity_hold`, `passenger_trip`, `fare_quote` (neuer unveränderlicher Snapshot bei Preisänderung).
  - API: `/v1/bookings` – Änderungsanfrage und Annahme (Pfadvorschlag `POST /v1/bookings/{id}/changes`, `POST /v1/bookings/{id}/changes/{change_id}/accept`) mit `If-Match` und `Idempotency-Key`; Fehler `409 CAPACITY_UNAVAILABLE`, `412` bei veralteter Revision.
  - Oberfläche: UI-04 (Aktive Reisen): Aktion „Ändern“ mit Gegenüberstellung alt/neu einschließlich Preis- und Zeitfensterdifferenz; Zustände „Änderung nicht mehr möglich“ (Frist/Fahrtzustand), „Änderungsangebot abgelaufen“, „Zahlung wird geprüft“ (UNKNOWN); UI-07 nutzt denselben Befehl mit Pflichtbegründung.
  - Ereignisse/Outbox: Buchung geändert (mit alter und neuer Revision), Änderung abgelehnt.
- **Detailverträge vorab:** Zustandsautomat Buchungsänderung; Regel für Preisdifferenz (Nachbelastung/Erstattung über den Zahlungsautomaten aus M2); Änderungsfristen je Dienst; Verhalten bei bereits disponierter Fahrt.
- **Akzeptanzkriterien:**
  1. Eine Änderung mit Kapazitätskonflikt wird mit eindeutigem Grund abgelehnt; die ursprüngliche Buchung und ihre Kapazität bleiben unverändert bestehen (BKG-006).
  2. Eine erfolgreiche Änderung erzeugt eine neue Buchungsrevision mit neuem `promise_snapshot`; die alte Zusage bleibt in `booking_change` nachvollziehbar (BKG-006).
  3. Änderung von Personenzahl oder Mobilitätshilfe prüft den Ressourcenvektor auf allen betroffenen Abschnitten neu (BKG-006).
  4. Eine Preisänderung fließt nie still in die alte Zusage ein; der Fahrgast bestätigt den neuen Betrag ausdrücklich.
  5. Wiederholter Änderungsbefehl mit gleichem `Idempotency-Key` liefert dasselbe Ergebnis; anderer Inhalt → `409`.
  6. Eine doppelt oder parallel ausgelöste Annahme führt zu genau einer Nachbelastung bzw. Erstattung; scheitert die Zahlung der Preisdifferenz oder bleibt sie unklar, gilt weiter die alte Zusage und der Vorgang ist als laufende Operation sichtbar.
  7. Jede Änderung und jede Ablehnung ist mit Akteur, Kanal, Zeitpunkt und Grund im Audit nachvollziehbar; nach Ablauf der dienstbezogenen Änderungsfrist oder nach Einstieg wird der Befehl begründet abgewiesen.
- **Tests:** positiv: Zeit ändern, Ziel ändern, Rollstuhlplatz ergänzen. negativ: Konflikt, außerhalb Bediengebiet, veraltete Revision (`412`), abgelaufenes Änderungsangebot. konkurrierend (Kapazität): Änderung auf den letzten Platz parallel zu einer Neubuchung – höchstens eine gewinnt, Kapazität nie negativ; Änderung parallel zu Storno derselben Buchung. konkurrierend (Geld): zwei gleichzeitige Annahmen desselben Änderungsangebots und PSP-Timeout bei der Preisdifferenz – genau eine Zahlungswirkung (Muster PH-AT-011, AT-15). Erweitert die Fixtures von PH-AT-004.
- **Abhängigkeiten:** intern: WP-M7a-01, WP-M7a-02.
  - extern: WP-M2-21, WP-M2-22, WP-M2-23, WP-M2-30, WP-M3-02 (Angebotsbildung, Hold/Confirm-Kern, Zahlungsautomat, `booking_change`-Grundtabelle)
  - extern: WP-M3-39, WP-M3-40 (Disponentenbefehl Buchung ändern, Plan-CAS)
- **Entscheidungen:** D-03 (Zahlungsregel bei Preisdifferenz; blockiert nur Produktivschaltung der Nachbelastung).
- **Größe:** M
- **Nicht im Umfang:** Änderung föderierter Mehrbetreiberreisen (M6); Zustellung der Änderung an Fahrer und Leitstelle (WP-M7a-04); Serienänderung (WP-M7a-07).

### WP-M7a-04 – Ereigniszustellung von Fahrgaständerungen an Fahrer und Leitstelle

- **Ziel:** Ändert oder storniert ein Fahrgast innerhalb der Vorlaufzeit, sehen Fahrer-App und Leitstelle ohne Neuladen denselben neuen Stand.
- **Anforderungen:** `LH-6.4-BKG-016`
- **Liefergegenstände:**
  - Backend: Consumer für die Ereignisse „Buchung geändert/storniert“ mit Inbox-Deduplikation `(consumer, event_id)` und Prüfung der Aggregatrevision (Kap. 01 §1.5); Lücken werden per autorisiertem Snapshotabruf geschlossen.
  - Ereignisse/Outbox: `outbox`/`inbox`; Ereignis enthält Buchungs- und Planrevision, betroffene Stopps, keine PII über den minimalen Betreiberpayload hinaus.
  - Oberfläche: UI-07 (Operator Livebetrieb) markiert geänderte Buchungen in Arbeitsliste und Stoppfolge; UI-10 (Driver Fahrt) zeigt geänderte Personenzahl/Unterstützung/Stopp mit Planstand.
  - Adapter/Worker: Zustellung über den Live-Aktualisierungskanal; Push an den Fahrer über `notification`/`notification_attempt`.
  - Kennzahlen: Zustelllatenz Ereignis → Clientdarstellung (technische Messgröße; kein Katalog-KPI).
- **Detailverträge vorab:** Ereignisschema „booking.changed“ (Version, Pflichtfelder); Definition „Vorlaufzeit“ (Offene Punkte 12); Verhalten bei Offline-Fahrer.
- **Akzeptanzkriterien:**
  1. Nach einer Fahrgaständerung zeigen UI-07 und UI-10 denselben Daten- und Revisionsstand wie die Buchungs-API (BKG-016).
  2. Ein doppelt oder verspätet zugestelltes Ereignis verändert den Stand nicht und setzt ihn nicht zurück (BKG-016).
  3. Ein Client mit veralteter Revision wird erkannt und synchronisiert; ein Offline-Fahrer sieht den Stand „zuletzt bestätigt“ mit Zeitangabe.
  4. Die Zeit vom Commit der Änderung bis zur dargestellten Clientrevision wird gemessen und gegen das vorläufige Lastenheftziel „Fahrerplan p95 ≤ 10 s bei bestehender Verbindung“ (Kap. 08 §8.6) ausgewiesen.
  5. Ein Fahrerereignis, das sich auf die alte Planrevision bezieht, führt nicht zu einem Zustandsrücksprung, sondern wird gegen die neue Revision geprüft oder als Klärfall geführt (Kap. 01 §1.5).
- **Tests:** positiv: Personenzahl ändern → beide Clients aktuell. negativ: Ereignis in falscher Reihenfolge, Duplikat, Fremdmandant erhält nichts. konkurrierend: Änderung und Fahrerereignis „Ankunft“ gleichzeitig. Nutzt PH-AT-017 (Reihenfolge und Verspätung von Events) als Muster; Regression AT-07 (Storno nach Disposition) und AT-11 (Offline Driver App) mit geänderter Buchung.
- **Abhängigkeiten:** intern: WP-M7a-03.
  - extern: WP-M2-28, WP-M2-32 (Verteilung von Planrevisionen, Nachrichtenversand)
  - extern: WP-M3-35, WP-M3-24 (Live-Aktualisierungskanal, Driver-App-Synchronisation)
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Fahrgastbenachrichtigung über die eigene Änderung (M2-Auslöser); Massenkommunikation (M7b).

### WP-M7a-05 – Buchungsregeln je Verkehrsprodukt

- **Ziel:** Jedes Verkehrsprodukt (Dienst) kann eigene Buchungsregeln und eine eigene Kanalfreigabe führen; zwei Dienste desselben Mandanten verhalten sich nachweislich unterschiedlich.
- **Anforderungen:** `LH-6.4-BKG-011`
- **Liefergegenstände:**
  - Backend: Auflösung der Buchungsregeln über die Präzedenzkette globale Sicherheitsgrenze → Mandant → Region → Dienst → Produkt (Kap. 01 §1.6); Angebot und Buchung speichern die angewendete Policyversion.
  - Datenbank: `config_revision` (Scope Dienst/Produkt), `service` (`config_revision_id`).
  - API: `/v1/services`, `/v1/config/effective`; `/v1/ride-offers` wendet die Regeln an.
  - Oberfläche: UI-17 (Gebiete/Stops/Tarife): Regelsatz je Dienst pflegen und freigeben; UI-01 zeigt dienstspezifische Hinweise (z. B. Mindestvorlauf).
  - Konfigurationsparameter: `min_booking_lead_min`, `max_booking_horizon_days`, `pickup_window_min`, `pooling_allowed`; Kanalfreigabe je Dienst (kein Katalogparameter, Offene Punkte 6).
- **Detailverträge vorab:** JSON-Schema des Regelsatzes je Dienst; Liste der kanalabhängigen Regeln; Bedeutung von „Kanaloberflächen“ (Offene Punkte 13).
- **Akzeptanzkriterien:**
  1. Zwei Dienste mit unterschiedlichem `min_booking_lead_min` lehnen bzw. akzeptieren dieselbe Anfragezeit unterschiedlich; der Ablehnungsgrund nennt die Regel (BKG-011).
  2. Die angewendete Regelversion ist an Angebot und Buchung gespeichert und bleibt nach Regeländerung für Bestandsbuchungen gültig.
  3. Ein Dienst, der für einen Kanal nicht freigegeben ist, erscheint dort nicht als buchbar.
- **Tests:** positiv: zwei Dienste, zwei Vorläufe. negativ: Wert außerhalb `0..43200`, Regel ohne Freigabe nicht wirksam. Nutzt PH-AT-018 (Konfigurationswechsel und Zusage) als Muster.
- **Abhängigkeiten:** intern: WP-M7a-01.
  - extern: WP-M1-20, WP-M1-21, WP-M1-22 (Konfigurationsregister mit Präzedenz, Wirksamkeit, Vier-Augen-Freigabe)
  - extern: WP-M2-03, WP-M2-12, WP-M2-21 (Dienste, Zulässigkeitsprüfung, Angebotsobjekt)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** eigenständig gestaltete Buchungsoberflächen je Produkt über Konfiguration hinaus (Offene Punkte 13); Tarifregeln (M2/M6).

### WP-M7a-06 – Bestätigungsfristen für zeitkritische Angebote

- **Ziel:** Ein Dienst kann für zeitkritische Angebote eine Bestätigungsfrist festlegen; ein nicht fristgerecht bestätigtes Angebot wird nie zur Buchung.
- **Anforderungen:** `LH-6.4-BKG-018`
- **Liefergegenstände:**
  - Backend: Frist als Bestandteil des Angebots (`expires_at`, Kap. 01 §1.4 Schritt 4) und des Holds (`capacity_hold.expires_at`); Confirm prüft Serverzeit `now >= expires_at` als abgelaufen; Expiry-Job gibt Kapazität im selben Kapazitätslock frei.
  - API: `/v1/ride-offers` (Feld Bestätigungsfrist), `/v1/bookings` (Confirm → `409` bei abgelaufener Frist).
  - Oberfläche: UI-02/UI-03 zeigen die verbleibende Frist als Text (nicht nur als Ablaufbalken) und den Zustand „Abgelaufen“ mit Aktion „Neu suchen“; UI-17: Frist je Dienst pflegen und freigeben (über `/v1/config/revisions`).
  - Konfigurationsparameter: Bestätigungsfrist je Dienst (kein Katalogparameter, Offene Punkte 6); Hold-Dauer 180 s bleibt Planungsdefault (Kap. 02 §7.2).
- **Detailverträge vorab:** Definition „zeitkritisches Angebot“ (Offene Punkte 14); Verhältnis Bestätigungsfrist ↔ Hold-Dauer ↔ `partner_offer_ttl_sec`.
- **Akzeptanzkriterien:**
  1. Ein Confirm nach Ablauf der konfigurierten Frist erzeugt keine Buchung und keine Belastung; die Kapazität ist wieder frei (BKG-018).
  2. Ein Confirm innerhalb der Frist wird genau einmal wirksam.
  3. Die Frist wird gegen Serverzeit geprüft; eine manipulierte Clientuhr ändert das Ergebnis nicht.
  4. Zwei Dienste mit unterschiedlicher Bestätigungsfrist verhalten sich im selben Ablauf unterschiedlich; die angewendete Frist und Regelversion stehen am Angebot.
  5. Die Bestätigungsfrist verlängert nie einen bereits abgelaufenen Hold; ein laufender Zahlungsschritt (SCA) über die Frist hinaus endet ohne Buchung und ohne Belastung.
- **Tests:** positiv: Bestätigung vor Ablauf. negativ: Bestätigung nach Ablauf, Grenzzeitpunkt `now == expires_at`. konkurrierend (Kapazität/Geld): Ablauf-Job parallel zum Confirm – genau ein Ausgang, nie „abgelaufen und gebucht“ und nie „abgelaufen und belastet“; SCA länger als die Frist (Kap. 05 §5.8). Nutzt PH-AT-010 (Hold läuft während Checkout aus) als Muster.
- **Abhängigkeiten:** intern: WP-M7a-05.
  - extern: WP-M1-20, WP-M1-22 (Konfigurationsregister mit Freigabe)
  - extern: WP-M2-21, WP-M2-22, WP-M2-23 (Angebotsobjekt mit Ablauf, Hold/Confirm, Expiry-Job)
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Partner-Hold-TTL (M6); Wartelisten und Alternativangebote (BKG-017, P3).

### WP-M7a-07 – Serienbuchung: Serienregel und Einzeltermine

- **Ziel:** Eine Serie mit Zeitraum, Wochentagen, Uhrzeiten und Ausnahmedaten erzeugt genau die erwarteten Einzeltermine; jeder Termin ist eine eigene, nachvollziehbare Reservierung über den normalen Buchungskern.
- **Anforderungen:** `LH-6.4-BKG-007`
- **Liefergegenstände:**
  - Backend: Aggregat `BookingSeries` im Modul `booking-dispatch`: Terminexpansion in der IANA-Zeitzone des Dienstes (DST-fest, Intervalle links geschlossen, rechts offen), Ausnahmedaten, je Termin eigene Angebotsbildung, Hold und Confirm über denselben Tarif-/Kapazitätskern (Kap. 05 §5.7). Zustände Serie: `DRAFT → ACTIVE → ENDED | CANCELLED`; Termin: `PENDING → CONFIRMED | FAILED | SKIPPED | CANCELLED`.
  - Datenbank: `booking_series` (Regel, Zeitraum, Zeitzone, Besteller, Fahrgast), `series_occurrence` (Datum, eigener Erfolg/Fehler, Fehlergrund, Verweis auf `booking`), `booking`, `capacity_hold`, `travel_attempt` (eine `attempt_id` je Termin).
  - API: `/v1/booking-series` – anlegen, lesen (Ergebnis pro Datum), Ausnahme ergänzen, Serie oder Einzeltermin stornieren; `Idempotency-Key` je Befehl; lange Serien als `operation` mit Fortschritt über `/v1/operations/{operation_id}`.
  - Ereignisse/Outbox: Serie angelegt, Termin bestätigt, Termin fehlgeschlagen, Serie beendet.
  - Adapter/Worker: fortsetzbarer Serien-Worker mit Lease/Fencing, je Mandant mit Tenantkontext.
- **Detailverträge vorab:** JSON-Schema der Serienregel; Zustandsautomaten Serie/Termin; Quelle für Feiertage und Schließtage (Offene Punkte 8); Zahlungsmodell je Termin (Offene Punkte 9).
- **Akzeptanzkriterien:**
  1. Eine Serie „Mo/Mi/Fr, 4 Wochen, ein Ausnahmedatum“ ergibt 12 Kalendertermine: genau 11 Einzelreservierungen, jede mit eigener Buchungsreferenz oder eigenem Fehlergrund, und genau 1 als Ausnahme ausgewiesenen Termin ohne Reservierung; kein Termin fehlt, keiner ist doppelt (BKG-007).
  2. Jeder Termin ist einzeln einsehbar, stornierbar und verweist auf Serie, Regelversion und angewendete Tarif-/Policyversion (BKG-007).
  3. Ein Ausnahmedatum (Feiertag oder Schließtag) erzeugt keinen bestätigten Termin; die Ausnahme ist mit Grund sichtbar (BKG-007, AT-06).
  4. Über eine Zeitumstellung hinweg behalten die Termine ihre lokale Uhrzeit.
  5. Wiederholtes Anlegen mit gleichem `Idempotency-Key` erzeugt keine zweite Serie und keine doppelten Termine; ein Workerabbruch führt nach Wiederanlauf zu keinem doppelten Termin.
  6. Je Termin entsteht höchstens eine Zahlungswirkung; Wiederanlauf des Workers oder wiederholter Befehl löst keine zweite Autorisierung oder Belastung aus (Zahlungsmodell laut Offene Punkte 9).
  7. Storno der Restserie gibt die Kapazität aller noch nicht gefahrenen Termine frei und lässt gefahrene Termine unverändert; Besteller, Fahrgast, Kanal und Regelversion der Serie sind im Audit nachvollziehbar.
- **Tests:** positiv: Serie mit Ausnahme (AT-06), Storno Einzeltermin, Storno Restserie. negativ: Zeitraum über `max_booking_horizon_days`, leere Wochentagsmenge, ungültige Zeitzone. konkurrierend (Kapazität): zwei Serien konkurrieren um denselben letzten Platz an einem Termin – höchstens eine bestätigt; Workerabsturz mitten in der Expansion. konkurrierend (Geld): Workerwiederanlauf nach Zahlungs-Timeout eines Termins – keine Doppelbelastung (Muster AT-15). DST-Fall analog AT-47; Serie über Mitternacht und Zeitumstellung analog E2E-102. Testfixture: Feiertags-/Schließtagskalender für AT-06.
- **Abhängigkeiten:** intern: WP-M7a-02, WP-M7a-05.
  - extern: WP-M2-21, WP-M2-22, WP-M2-23, WP-M2-30, WP-M2-24 (Angebotsbildung, Hold/Confirm, Zahlungsautomat, Idempotenz der Buchungsbefehle)
  - extern: WP-M1-15 (`operation`-Ressource mit Lease/Fencing)
  - extern: WP-M7d-11 (Kalender-Leseport für Feiertage/Sondertage je Ort und Datum; WP-M7d-11 ist alleiniger Lieferant des Kalenders in M7, dieses WP ist nur Abnehmer. Manuelle Ausnahmedaten funktionieren ohne den Kalender; ob Feiertage/Schließtage automatisch wirken, siehe Offene Punkte 8)
- **Entscheidungen:** D-03 (Capturezeitpunkt bei Vorausbuchung; blockiert nur Produktivschaltung bezahlter Serien).
- **Größe:** M
- **Nicht im Umfang:** Vorbuchungsgrenze und Fehlerregel (WP-M7a-08); Oberfläche (WP-M7a-09); föderierte Serien über mehrere Betreiber (nicht spezifiziert).

### WP-M7a-08 – Serienbuchung: Vorbuchungsgrenze und Regel für fehlgeschlagene Termine

- **Ziel:** Für Serien sind eine Vorbuchungsgrenze und eine Regel für fehlgeschlagene Einzeltermine einstellbar; eine nur teilweise verfügbare Serie wird pro Datum transparent ausgewiesen, nie pauschal zugesagt.
- **Anforderungen:** `LH-6.4-BKG-008`
- **Liefergegenstände:**
  - Backend: rollierende Terminbuchung bis zur Vorbuchungsgrenze (spätere Termine bleiben `PENDING` und werden vom Worker nachgezogen); Fehlerregel als Konfiguration (Optionen im Detailvertrag, Offene Punkte 10); Serienergebnis mit Zählern bestätigt/fehlgeschlagen/ausstehend.
  - Datenbank: `booking_series` (Vorbuchungsgrenze, Fehlerregel, Regelversion), `series_occurrence` (Fehlergrund aus dem Katalog der Ablehnungsgründe).
  - API: `/v1/booking-series` – Antwort mit Ergebnis pro Datum und Gesamtstatus `COMPLETE | PARTIAL | FAILED`; Vorschau vor verbindlicher Anlage.
  - Ereignisse/Outbox: Termin nachgebucht, Termin endgültig fehlgeschlagen; Benachrichtigung über `notification`.
  - Konfigurationsparameter: `max_booking_horizon_days` als Obergrenze; serienspezifische Vorbuchungsgrenze und Fehlerregel (keine Katalogparameter, Offene Punkte 6).
  - Oberfläche: UI-17: Vorbuchungsgrenze und Fehlerregel je Dienst pflegen und freigeben (über `/v1/config/revisions`); Anzeige des Serienergebnisses für Fahrgäste in WP-M7a-09.
  - Adapter/Worker: täglicher Nachbuchungs-Job (fortsetzbar, idempotent je Termin).
- **Detailverträge vorab:** Enum der Fehlerregel; Verhalten bei Gesamtabbruch (Freigabe bereits bestätigter Termine in derselben Operation); Benachrichtigungsvorlage.
- **Akzeptanzkriterien:**
  1. Eine Serie mit zwei nicht verfügbaren Terminen zeigt in API und Oberfläche pro Datum „bestätigt“ bzw. „nicht verfügbar“ mit Grund; der Gesamtstatus ist `PARTIAL` (BKG-008).
  2. Kein fehlgeschlagener Termin erscheint als zugesagt; es gibt keine pauschale Serienbestätigung (BKG-008, Kap. 05 §5.7).
  3. Termine jenseits der Vorbuchungsgrenze werden nicht reserviert und als „noch nicht buchbar“ ausgewiesen; sie werden beim Erreichen der Grenze automatisch geprüft (BKG-008).
  4. Die konfigurierte Fehlerregel wird nachweislich angewendet (z. B. Serie mit Lücken fortsetzen gegenüber Serie verwerfen).
  5. Vorbuchungsgrenze und Fehlerregel sind je Dienst einstellbar, versioniert und freigabepflichtig; die an der Serie gespeicherte Regelversion bleibt nach einer Regeländerung gültig (BKG-008).
  6. Über einen nachgebuchten oder endgültig fehlgeschlagenen Termin wird der Besteller genau einmal benachrichtigt.
- **Tests:** positiv: Teilverfügbarkeit, Nachbuchung beim Erreichen der Grenze. negativ: Grenze größer als `max_booking_horizon_days`, Fehlerregel „verwerfen“ gibt bestätigte Termine vollständig frei. konkurrierend (Kapazität): Nachbuchungs-Job und manuelle Einzelbuchung um denselben Platz; zwei Job-Instanzen parallel (kein doppelter Termin). Bestandteil von AT-06.
- **Abhängigkeiten:** intern: WP-M7a-07.
  - extern: WP-M1-20, WP-M1-22 (Konfigurationsregister mit Freigabe)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Warteliste und Alternativangebote bei fehlender Kapazität (BKG-017, P3).

### WP-M7a-09 – Serienbuchung in der Passenger-Weboberfläche

- **Ziel:** Fahrgäste legen Serien in der Passenger-PWA an, sehen das Ergebnis pro Datum und verwalten Einzeltermine barrierefrei.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Oberfläche: UI-01 (Suche): Option „Wiederholen“ mit Zeitraum, Wochentagen, Uhrzeit, Ausnahmedaten; UI-02: Vorschau der Termine mit Verfügbarkeit je Datum; UI-03 (Checkout): Zusammenfassung je Termin, Zahlungsplan; UI-04 (Aktive/alte Reisen): Serienansicht mit Terminliste, Status je Termin, Storno Einzeltermin/Restserie.
  - API: Nutzung von `/v1/booking-series`, `/v1/operations/{operation_id}`.
- **Detailverträge vorab:** Bildschirmvertrag Serienanlage (Leere-/Fehler-/Rechtezustände analog Kap. 06 §6.9); Texte für Teilverfügbarkeit.
- **Akzeptanzkriterien:**
  1. Serienanlage, Ergebnisprüfung und Einzelstorno sind vollständig per Tastatur und Screenreader bedienbar; Status nie nur als Farbe.
  2. Teilverfügbarkeit wird vor der verbindlichen Bestätigung pro Datum angezeigt.
  3. Ein Doppelklick auf „Serie buchen“ erzeugt keine zweite Serie.
- **Tests:** positiv: E2E-Durchlauf AT-06 über die Oberfläche. negativ: Verbindungsabbruch während der Anlage öffnet dieselbe Operation. Barrierefreiheitsprüfung analog AT-19.
- **Abhängigkeiten:** intern: WP-M7a-07, WP-M7a-08.
  - extern: WP-M2-35, WP-M2-37, WP-M2-41, WP-M2-42, WP-M2-43, WP-M2-45, WP-M1-43 (Passenger-PWA UI-01 bis UI-04, Designsystem)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Serienanlage im Callcenter UI-06 (M7b nutzt dieselbe API); native App (WP-M7a-39).

### WP-M7a-10 – Gruppenbuchung mit individuellen Kapazitätsanforderungen

- **Ziel:** Eine Buchung kann mehrere Mitreisende mit je eigenen Kapazitätsanforderungen enthalten; die Gesamtressourcen werden vollständig je Fahrzeug geprüft und reserviert.
- **Anforderungen:** `LH-6.4-BKG-009`
- **Liefergegenstände:**
  - Backend: Gruppenanfrage mit Personenliste; Summenbildung zum Ressourcenvektor (Sitz, Rollstuhlplatz, Ausstattung, Kap. 01 §1.4 Schritt 2); Prüfung auf allen betroffenen Run-Abschnitten und allen Dimensionen in einem Hold.
  - Datenbank: `passenger_trip` (n:1 zu `booking`, je Person Kapazitätsmerkmale ohne Diagnose), `capacity_hold.capacity_vector`, `vehicle.capacity_config`.
  - API: `/v1/ride-offers`, `/v1/bookings` (Personenliste mit Kapazitätsmerkmalen).
  - Oberfläche: UI-01 (Reisende und Unterstützung je Person), UI-02 (Hinweis, wenn die Gruppe nicht in ein Fahrzeug passt), UI-04 (Personenliste).
  - Konfigurationsparameter: `min_capacity_reserve`, `wheelchair_boarding_sec`, `stop_dwell_sec` (Haltezeit skaliert mit Gruppengröße laut Detailvertrag).
- **Detailverträge vorab:** Schema der Personenliste; Regel für Gruppen, die die Kapazität eines Fahrzeugs übersteigen (Offene Punkte 11); maximale Gruppengröße.
- **Akzeptanzkriterien:**
  1. Eine Gruppe „4 Sitzplätze + 1 Rollstuhlplatz“ wird nur einem Fahrzeug zugewiesen, dessen Ressourcenvektor auf allen Abschnitten in jeder Dimension ausreicht; die reservierte Summe entspricht exakt der Anforderung (BKG-009).
  2. Reicht eine Dimension nicht aus, wird die Gruppe mit eindeutigem Grund abgelehnt; es entsteht keine Teilreservierung (BKG-009).
  3. Teilstorno einer Person gibt genau deren Ressourcen frei.
- **Tests:** positiv: Gruppe passt, Teilstorno einer Person. negativ: Rollstuhlplatz fehlt, Gruppe größer als Fahrzeug. konkurrierend (Kapazität): zwei Gruppen konkurrieren um die Restkapazität – nie Überbuchung in einer Dimension (Erweiterung PH-AT-004; Bezug AT-04).
- **Abhängigkeiten:** intern: WP-M7a-02.
  - extern: WP-M2-14, WP-M2-22 (Ressourcenvektor, Mehrpersonenbuchung, Hold)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Gruppentarife und Rabatte (Tarifmodul); Aufteilung einer Gruppe auf mehrere Fahrzeuge (Offene Punkte 11).

### WP-M7a-11 – Buchung für Dritte

- **Ziel:** Buchende Person und tatsächlicher Fahrgast werden getrennt geführt; die Leitstelle erkennt eine Vertretungsbuchung, ohne dass Konten zusammengelegt werden.
- **Anforderungen:** `LH-6.4-BKG-010`
- **Liefergegenstände:**
  - Backend: `booking_subject` = `SELF | DELEGATED` verpflichtend (Kap. 05 §5.9); Server prüft Zweck, Dienst und Laufzeit der Vertretung bei Planung, Hold und Confirm erneut; Quote und Buchung speichern aufgelöste Person und Berechtigungsrevision; Besteller bleibt eigener Akteur im Audit; Subjektwechsel erfordert neues Angebot.
  - Datenbank: `delegated_access` (Vertreter, Zweck, Scope, Ablauf), `passenger_private`, `booking` (Besteller- und Fahrgastreferenz getrennt), `audit_event`.
  - API: `/v1/delegations` (anlegen, widerrufen, lesen), `/v1/passengers`, `/v1/ride-offers`, `/v1/bookings`.
  - Oberfläche: UI-05 (Konto/Berechtigung: Vertreter verwalten; Zustände „Verifikation fehlt“, „Vertretung abgelaufen“), UI-01 („Für wen buchen?“), UI-07 (Kennzeichen „Vertretungsbuchung“ mit Besteller und Fahrgast für berechtigte Rollen).
  - Ereignisse/Outbox: Vertretung erteilt/widerrufen/abgelaufen.
- **Detailverträge vorab:** Schema `delegated_access`; Regeln für Fahrgäste ohne eigenes Konto (Offene Punkte 15); Benachrichtigungsempfänger (Besteller, Fahrgast oder beide).
- **Akzeptanzkriterien:**
  1. Eine für eine dritte Person gebuchte Fahrt zeigt in UI-07 Besteller und Fahrgast als zwei getrennte Personen; beide Konten bleiben getrennt und unverändert (BKG-010).
  2. Mit abgelaufener oder widerrufener Vertretung scheitern Planung, Hold und Confirm jeweils serverseitig.
  3. Der Besteller sieht nur die von ihm gebuchten Fahrten des Fahrgasts, nicht dessen übriges Konto.
  4. Der Fahrer erhält nur den minimalen Betreiberpayload zum tatsächlichen Fahrgast.
- **Tests:** positiv: Buchung für Angehörigen. negativ: abgelaufene Vertretung, Subjektwechsel ohne neues Angebot, Zugriff auf fremdes Konto. konkurrierend (Sicherheit): Widerruf der Vertretung parallel zum Confirm – Confirm scheitert oder ist vor dem Widerruf abgeschlossen, nie danach wirksam. Mandantentrennung analog PH-AT-001.
- **Abhängigkeiten:** intern: WP-M7a-02.
  - extern: WP-M1-04, WP-M1-08, WP-M1-46 (Identität, Objektberechtigung, Audit)
  - extern: WP-M2-21, WP-M2-22, WP-M2-23, WP-M2-36 (Angebot/Hold/Confirm, UI-05-Grundlage)
  - extern: WP-M3-36, WP-M3-37 (Arbeitsliste UI-07)
- **Entscheidungen:** D-11 (Aufbewahrung von Vertretungsnachweisen; blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Callcenter-Legitimation und Zustimmungsdokumentation in UI-06 (M7b); Auskunft/Löschung `/v1/privacy/requests` (M8).

### WP-M7a-12 – Ein Buchungsbestand für Web, native App und Callcenter

- **Ziel:** Alle Buchungskanäle arbeiten über dieselbe Fach-API auf demselben Buchungs- und Kapazitätsbestand; dieselbe Buchung ist mit berechtigtem Zugriff in jedem Kanal sichtbar und bearbeitbar.
- **Anforderungen:** `LH-6.3-PAX-014`
- **Liefergegenstände:**
  - Backend: Kanalattribut an Anfrage und Buchung (`travel_attempt.channel`, Buchungskanal im Audit); keine kanalspezifischen Buchungs- oder Kapazitätspfade (Architekturtest); kanalübergreifende Idempotenz je Mandant, Akteur und Operationstyp.
  - Datenbank: `travel_attempt` (`channel`), `booking`, `booking_change`, `audit_event`.
  - API: `/v1/bookings`, `/v1/ride-offers`, `/v1/booking-series` – identische Verträge für alle Kanäle; Lesezugriff nach Objektberechtigung (eigener Fahrgast, gültige Vertretung, berechtigte Betriebsrolle).
  - Oberfläche: UI-04 (Web und native App) zeigt auch im Callcenter angelegte Buchungen mit Kanalangabe; UI-06 (Callcenter) und UI-07 finden App-Buchungen.
  - Kennzahlen: `Anfragen` und `Buchungskonversion` nach Kanal segmentierbar.
- **Detailverträge vorab:** Enum der Kanäle; Sichtbarkeitsmatrix Rolle × Kanal × Feld; Architekturtest-Regel „eine Fach-API“ (Kap. 01 §1.1).
- **Akzeptanzkriterien:**
  1. Eine im Callcenter angelegte Buchung erscheint für den berechtigten Fahrgast in Web und nativer App mit identischem Status und identischer Revision und umgekehrt (PAX-014).
  2. Ein nicht berechtigter Nutzer sieht die Buchung in keinem Kanal (PAX-014).
  3. Gleichzeitige Nachfrage aus App und Callcenter auf den letzten Platz führt zu genau einer Buchung; beide Kanäle zeigen denselben Kapazitäts- und Buchungsstatus (AT-12).
  4. Eine im einen Kanal begonnene Änderung führt im anderen Kanal bei veralteter Revision zu `412` ohne stilles Überschreiben.
- **Tests:** positiv: Kanalwechsel Web → App → Callcenter. negativ: fremder Fahrgast, fremder Mandant. konkurrierend (Kapazität): AT-12 mit parallelen Confirms aus zwei Kanälen; paralleles Storno (App) und Ändern (Callcenter). Sicherheit (Kap. 05 §5.8): Manipulation `tenant_id` und unzulässige Fremd-ID in jedem Kanal abgewiesen. AT-12 ist erst mit UI-06 aus M7b vollständig über Oberflächen abnehmbar; auf API-Ebene besteht dieses WP den Test (für den API-Nachweis genügt die PWA als „App“, WP-M7a-38 wird nur für die Sichtbarkeit in der nativen App benötigt).
- **Abhängigkeiten:** intern: WP-M7a-11, WP-M7a-38.
  - extern: WP-M7b-35, WP-M7b-17 (Callcenter-Arbeitsplatz UI-06 mit Vertretungsprüfung)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Callcenter-Oberfläche selbst (M7b); Partnerkanäle über `/v1/partner/reservations` (M6).

### WP-M7a-13 – Standardisierte No-Show-Regeln

- **Ziel:** Ein No-Show entsteht nur nach konfigurierter Wartezeit und nachgewiesener Anwesenheit des Fahrzeugs am Haltepunkt; Fahrerbestätigung und Leitstellenoption sind einheitlich geregelt.
- **Anforderungen:** `LH-6.4-BKG-015`
- **Liefergegenstände:**
  - Backend: No-Show-Regel im Modul `booking-dispatch`: Voraussetzung „Ankunft am Stopp erfasst“ (Fahrerereignis mit Ereignis-, Geräte- und Serverempfangszeit sowie Positionsplausibilität), Ablauf der Wartefrist ab dem späteren von Ankunft und zugesagtem Fensterbeginn, Fahrerbestätigung; Leitstellenoption (bestätigen, verlängern, zurücknehmen) mit Begründung. Übergang der Buchung nur über diese Regel.
  - Datenbank: `booking` (`state`), `booking_change` bzw. Statushistorie (Regelversion, Ankunftsnachweis, Akteur), `audit_event`.
  - API: `POST /v1/driver/events` (Ereignisart No-Show; Antwort `APPLIED`, `DUPLICATE`, `REVIEW_REQUIRED`); Leitstellenbefehl unter `/v1/bookings` (Pfadvorschlag).
  - Oberfläche: UI-10 (Driver Fahrt): No-Show-Aktion erst nach Fristablauf aktiv, mit Restzeit; UI-07: Option der Leitstelle; UI-17: No-Show-Regel je Dienst pflegen und freigeben; UI-04: Fahrgast sieht den Status No-Show mit Grund.
  - Konfigurationsparameter: `no_show_grace_min`, `arrival_grace_min`.
  - Kennzahlen: `No-Show-Rate` (Zähler nur regelkonforme No-Shows; „Wartezeit nach Regel berücksichtigen“).
- **Detailverträge vorab:** Zustandsautomat No-Show-Feststellung; Definition „erfasste Anwesenheit“ (Positionsradius, Standortalter); Verhalten bei Offline-Ereignis (Kap. 01 §1.5: Klärfall statt Zustandsrücksprung).
- **Akzeptanzkriterien:**
  1. Ein No-Show-Ereignis vor Ablauf von `no_show_grace_min` wird abgewiesen bzw. als `REVIEW_REQUIRED` geführt und ändert den Buchungsstatus nicht (BKG-015).
  2. Ein No-Show ohne erfasste Ankunft am zugeordneten Haltepunkt wird nicht wirksam (BKG-015).
  3. Nach Wartefrist, Ankunftsnachweis und Fahrerbestätigung wechselt die Buchung genau einmal in No-Show; Regelversion und Nachweise sind gespeichert (BKG-015).
  4. Die Leitstelle kann einen No-Show mit Begründung zurücknehmen; die Historie bleibt erhalten.
  5. Ein offline erfasstes, verspätet synchronisiertes No-Show-Ereignis wird mit Ereignis-, Geräte- und Serverempfangszeit bewertet; widerspricht es einem inzwischen erfolgten Check-in oder Storno, entsteht ein Klärfall statt eines Zustandswechsels.
  6. Zwei Dienste mit unterschiedlichem `no_show_grace_min` verhalten sich im selben Ablauf unterschiedlich; die Regel ist für alle Dienste nach demselben Schema aufgebaut (standardisiert).
- **Tests:** positiv: regelkonformer No-Show (baut auf AT-08 auf). negativ: zu früh, falscher Stopp, fehlende Ankunft, doppeltes Ereignis. konkurrierend: verspäteter Check-in und No-Show gleichzeitig – genau ein definierter Ausgang oder Klärfall; Fahrer-No-Show und Leitstellenverlängerung gleichzeitig. Regression AT-08 einschließlich Abrechnungswirkung nach bestehender Regel.
- **Abhängigkeiten:** intern: WP-M7a-01, WP-M7a-02.
  - extern: WP-M1-20, WP-M1-22 (Konfigurationsregister mit Freigabe)
  - extern: WP-M3-29, WP-M3-34, WP-M3-36, WP-M3-52 (Fahrerereignisse Ankunft/No-Show, Positionserfassung, Arbeitsliste UI-07)
- **Entscheidungen:** D-03 (No-Show-Entgelt; blockiert nur dessen Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** No-Show-Gebühr und Zahlungsabwicklung (Tarif-/Zahlungsmodul); Sperrung wiederholt nicht erscheinender Fahrgäste (nicht spezifiziert).

### WP-M7a-14 – Buchungscode und QR-Token für datensparsamen Check-in

- **Ziel:** Jede Buchung erhält einen zufälligen, befristeten Code bzw. QR-Token, der nur für genau diese Buchung gilt und beim Check-in ohne Offenlegung weiterer Personendaten geprüft wird.
- **Anforderungen:** `LH-6.4-BKG-019`
- **Liefergegenstände:**
  - Backend: Tokenerzeugung aus kryptografisch sicherem Zufall; Speicherung nur als Hash; Gültigkeitsfenster aus dem Zusagefenster der Buchung; Bindung an Buchung, Stopp und Planrevision; Einmalverwendung; Fehlversuchsbegrenzung.
  - Datenbank: Buchungscode an `booking` bzw. eigene Tabelle (Vorschlag, Offene Punkte 4); `audit_event` für Fehlversuche ohne Fremdinhalte.
  - API: `/v1/bookings` (Code/QR für berechtigten Fahrgast abrufen), `POST /v1/driver/events` (Check-in mit Code; Antwort `APPLIED`, `DUPLICATE`, `REVIEW_REQUIRED`).
  - Oberfläche: UI-04 (Boarding-Code und QR mit Textalternative), UI-10 (Codeeingabe/Scan; Zustand „Falscher Stopp/Code“).
- **Detailverträge vorab:** Tokenformat, Länge/Entropie, Gültigkeitsregel, Offline-Verhalten der Driver-App (Prüfung nur serverbestätigt oder gegen vorab synchronisierte Hashliste; Offene Punkte 16).
- **Akzeptanzkriterien:**
  1. Tokens sind nicht aus Buchungs-ID oder Personendaten ableitbar; in einer Stichprobe von 100.000 Tokens tritt je Mandant keine Dublette auf (BKG-019).
  2. Ein Token wird außerhalb seines Gültigkeitsfensters abgewiesen (BKG-019).
  3. Ein gültiger Token einer anderen Buchung, eines anderen Stopps oder eines anderen Mandanten wird abgewiesen (BKG-019).
  4. Der QR-Inhalt enthält keine Klartext-Personendaten; der Fahrer sieht nach dem Scan nur den minimalen Betreiberpayload.
  5. Wiederholte Fehlversuche werden begrenzt und als Sicherheitsereignis protokolliert.
- **Tests:** positiv: Check-in mit gültigem Code. negativ: abgelaufen, fremde Buchung, bereits verwendet, fremder Mandant, Brute-Force. Offline-Check-in auf alter Planrevision (Kap. 05 §5.8) führt zu `REVIEW_REQUIRED`, nicht zu einem stillen Erfolg. konkurrierend (Sicherheit): zwei Geräte lösen denselben Token gleichzeitig ein – genau ein `APPLIED`.
- **Abhängigkeiten:** intern: WP-M7a-02.
  - extern: WP-M3-30, WP-M3-29 (Check-in mit Codeprüfung in der Driver-App, Fahrerereignis-Endpunkt)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Ticketkontrolle externer Abschnitte (M6); Boarding-Code je Betreiber in föderierten Reisen (M6).

### WP-M7a-15 – Multimodale Reiseoption mit Umstiegen

- **Ziel:** Eine Reiseoption zeigt bei verfügbaren Daten mehrere Verkehrsmittel mit den relevanten Umstiegen; Shuttle und ÖPNV-Alternative sind nachvollziehbar nebeneinander dargestellt.
- **Anforderungen:** `LH-6.3-PAX-010`
- **Liefergegenstände:**
  - Backend: BFF-Zusammenführung der Angebotsalternativen: geordnete Segmente mit Verkehrsmittel, Betreiber, Buchungsmodus, Zusagefenster, Umstiegsminimum (Kap. 02 §6 Nr. 5); Kennzeichnung der Datenherkunft und des Datenalters.
  - API: `POST /v1/federation/journeys/plan`, `GET /v1/federation/offers/{offer_id}` (`OfferLeg`, `pickup_window`/`arrival_window`, `minimum_transfer_seconds`).
  - Oberfläche: UI-01/UI-02: Ergebniskarte je Option mit Segmentliste, Umstiegszeit und Fußweg, Hinweis „Anschluss nicht garantiert“, Kennzeichnung „hier buchbar“ gegenüber „Ticket separat“ (Kap. 06 §6.2); gleichwertige Listenansicht; Zustände „Partnerausfall“, „unbestätigte Barrierefreiheit“.
  - Kennzahlen: `Anfragen`, `Angebotsquote` (Zählung je `attempt_id` unverändert).
- **Detailverträge vorab:** Anzeigevertrag für gemischte Optionen (kein Gesamtpreis bei `external_unknown_leg_ids`); Sortier- und Vergleichskriterien; Verhalten ohne Fahrplandaten.
- **Akzeptanzkriterien:**
  1. Im Testbeispiel werden ein Shuttle-Angebot und eine ÖPNV-Alternative mit Verkehrsmitteln, Zeiten, Umstiegspunkt und Umstiegsdauer angezeigt (PAX-010).
  2. Jede Option weist Betreiber, Buchbarkeit und Datenherkunft je Segment aus; ein nur auskunftsfähiges Segment erhält keinen fingierten Buchungsstatus (PAX-010).
  3. Fehlen Fahrplandaten, werden lokale Angebote weiter angezeigt und der Datenausfall benannt.
  4. Die Darstellung ist per Screenreader in Segmentreihenfolge lesbar.
- **Tests:** positiv: Testrelation mit Shuttle + ÖPNV. negativ: Partnerausfall, unbekannter Fremdpreis, kein Umstieg unterhalb des Minimums. Nutzt PH-AT-006 (nur auskunftsfähiger Linienbus) als Fixture.
- **Abhängigkeiten:** intern: WP-M7a-01.
  - extern: WP-M2-37, WP-M2-41 (Passenger-PWA UI-01/UI-02)
  - extern: WP-M6a-09, WP-M6a-28, WP-M6a-26, WP-M6a-08 (föderierte Reiseauskunft, ÖPNV-Alternativen, Fahrplanadapter, Transfer-Hubs)
- **Entscheidungen:** D-04 (Anschlussrisiko: Wortlaut „nicht garantiert“ ist technischer Default; blockiert nur Produktivschaltung abgesicherter Anschlüsse), D-08 (Partnerstandards; ohne Beschluss nur Sandbox-Feed).
- **Größe:** M
- **Nicht im Umfang:** Planung und Buchung der Kette selbst (M6a); Anschlussüberwachung zur Laufzeit (M6a).

### WP-M7a-16 – Erneut buchen: wiederkehrende Ziele und letzte Routen

- **Ziel:** Fahrgäste starten aus wiederkehrenden Zielen oder zuletzt gewählten Routen mit einem Schritt eine neue Anfrage, die stets aktuell auf Verfügbarkeit geprüft wird.
- **Anforderungen:** `LH-6.3-PAX-011`
- **Liefergegenstände:**
  - Backend: Ableitung „zuletzt gewählte Routen“ aus eigenen Buchungen bzw. gespeicherten Relationen; „Erneut buchen“ erzeugt eine neue Suche mit neuer `attempt_id`, kein Kopieren von Angebot, Preis oder Hold.
  - Datenbank: `passenger_preference` (Favoriten, wiederkehrende Ziele, letzte Relationen mit Löschklasse), `travel_attempt`.
  - API: `/v1/me` bzw. `/v1/passengers` (Präferenzen), `/v1/ride-offers`.
  - Oberfläche: UI-01 (Schnellauswahl „Letzte Routen“/„Häufige Ziele“; Leerzustand ohne Historie), UI-04 (Aktion „Erneut buchen“ an alter Reise), UI-05 (Liste löschen/abschalten).
- **Detailverträge vorab:** Speicherdauer und Höchstzahl der letzten Routen; Opt-out; Umgang mit inzwischen ungültigen Haltepunktrevisionen.
- **Akzeptanzkriterien:**
  1. „Erneut buchen“ erzeugt eine neue Anfrage mit neuer `attempt_id` und aktueller Verfügbarkeits-, Tarif- und Regelprüfung; alte Preise oder Zusagen werden nicht übernommen (PAX-011).
  2. Ist die Relation nicht mehr zulässig (Haltepunkt inaktiv, Gebiet geändert), erhält der Fahrgast eine begründete Ablehnung mit Handlungsoption.
  3. Der Fahrgast kann die Liste einsehen, einzelne Einträge löschen und die Funktion abschalten.
- **Tests:** positiv: Wiederholung einer alten Reise. negativ: stillgelegter Haltepunkt, fremde Präferenzen nicht lesbar. Datentrennung: Präferenzen eines anderen Fahrgasts/Mandanten nie sichtbar.
- **Abhängigkeiten:** intern: WP-M7a-02.
  - extern: WP-M2-36, WP-M2-38, WP-M2-21 (UI-05 Favoriten, Ortssuche, Angebotsbildung)
- **Entscheidungen:** D-11 (Speicherdauer; blockiert nur Produktivschaltung).
- **Größe:** S
- **Nicht im Umfang:** Serienbuchung (WP-M7a-07); Vorschläge aus Prognosen (P3).

### WP-M7a-17 – Bewertung und kategorisiertes Feedback

- **Ziel:** Fahrgäste bewerten eine abgeschlossene Fahrt und geben kategorisiertes Feedback; die Bewertung ist der Fahrt zugeordnet und für andere Nutzer nicht sichtbar.
- **Anforderungen:** `LH-6.3-PAX-012`
- **Liefergegenstände:**
  - Backend: Feedback nur für eigene abgeschlossene Fahrten, einmal je Fahrt (änderbar innerhalb einer Frist laut Detailvertrag); Kategorienkatalog je Mandant; Freitext als PII-verdächtig behandelt (Feldschutz).
  - Datenbank: Feedbacktabelle (Vorschlag `passenger_feedback`, nicht in Kap. 04 §4.4; Offene Punkte 4) mit Bezug zu `booking`/`passenger_trip`, Kategorie, Bewertung, Löschklasse.
  - API: Feedback anlegen/lesen unter `/v1/bookings` (Pfadvorschlag `POST /v1/bookings/{id}/feedback`); Lesezugriff für berechtigte Betriebsrollen des eigenen Mandanten.
  - Oberfläche: UI-04 (alte Reisen): Bewertungsdialog mit Kategorien, per Tastatur und Screenreader bedienbar (Skala nicht nur als Symbol/Farbe), Zustände „bereits bewertet“ und „Frist abgelaufen“; Auswertung für Betriebsrollen in UI-20 bzw. Supportsicht (M7b) als redigierte Liste.
  - Ereignisse/Outbox: Feedback abgegeben (ohne Freitext).
- **Detailverträge vorab:** Kategorienkatalog und Skala (Offene Punkte 17); Sichtbarkeitsmatrix (wer sieht Zuordnung zu Fahrer/Fahrzeug); Verknüpfung mit `support_case`.
- **Akzeptanzkriterien:**
  1. Eine Bewertung ist eindeutig einer Fahrt (und darüber Dienst, Fahrzeug, Zeitpunkt) zugeordnet (PAX-012).
  2. Kein anderer Fahrgast und kein fremder Mandant kann die Bewertung oder ihre Existenz abrufen; es gibt keine öffentliche Bewertungsanzeige (PAX-012).
  3. Eine Bewertung für eine fremde, stornierte oder nicht abgeschlossene Fahrt wird abgewiesen.
  4. Feedback erzeugt keine automatische personenbezogene Leistungsbewertung von Fahrern.
  5. Lesezugriffe von Betriebsrollen auf Feedback mit Freitext werden auditiert; der Freitext erscheint nicht in Ereignissen, Logs oder Exporten.
- **Tests:** positiv: Bewertung nach Fahrtende. negativ: fremde Fahrt, doppelte Bewertung, Lesen durch anderen Fahrgast oder Mandanten. Datentrennung analog PH-AT-001.
- **Abhängigkeiten:** intern: WP-M7a-02.
  - extern: WP-M2-45 (UI-04 Buchungshistorie)
  - extern: WP-M3-29 (Fahrtabschluss-Ereignis)
- **Entscheidungen:** D-10 (Fahrerdaten/Mitbestimmung: Zuordnung von Feedback zu Fahrern; blockiert Produktivschaltung der fahrerbezogenen Auswertung), D-11 (Löschfrist).
- **Größe:** M
- **Nicht im Umfang:** Supportfallbearbeitung und SLA (M7b, Kennzahl `Kundenkontakt-Auflösung`); Analyseberichte über Feedback (M8).

### WP-M7a-18 – Live-Fahrzeugposition nur in berechtigter Phase

- **Ziel:** Der Abholbildschirm zeigt die Live-Position des zugewiesenen Fahrzeugs ausschließlich in der dafür berechtigten Phase; nach Fahrtende endet die Freigabe serverseitig.
- **Anforderungen:** `LH-6.3-PAX-013`
- **Liefergegenstände:**
  - Backend: serverseitige Positionsfreigabe je Buchung, abgeleitet aus Buchungs-/Ausführungsstatus und Zeitfenster; Positionsprojektion nur des zugewiesenen Fahrzeugs mit Messzeit und Standortalter; sofortiges Ende bei Fahrtende, Storno, No-Show oder Umplanung auf ein anderes Fahrzeug.
  - Datenbank: `position_sample` (Lesen über Modul-API `fleet-safety`), `booking`, `journey_leg` (`execution_state`).
  - API: Positionsabruf/-stream unter `/v1/bookings` (Pfadvorschlag `GET /v1/bookings/{id}/vehicle-position`); `403`/`404` außerhalb der Phase.
  - Oberfläche: UI-04 (Abholbildschirm): Karte mit gleichwertiger Textangabe („Fahrzeug etwa 3 Minuten entfernt, Stand 08:42“), Hinweis bei veraltetem Standort; verbindliche Zusage getrennt von aktueller ETA.
  - Konfigurationsparameter: `eta_max_data_age_sec` (Kennzeichnung veralteter Position), `customer_notify_before_pickup_min`.
- **Detailverträge vorab:** Definition der „berechtigten Phase“ je Dienst (Offene Punkte 18); Cache-Schlüssel mit Tenant, Buchung und Freigaberevision (Kap. 02 §4.3).
- **Akzeptanzkriterien:**
  1. Nach Fahrtende liefert der Positionsendpunkt keine Position mehr; ein bereits geöffneter Stream wird beendet (PAX-013).
  2. Vor Beginn der berechtigten Phase und nach Storno ist keine Position abrufbar (PAX-013).
  3. Der Fahrgast sieht nie Positionen anderer Fahrzeuge oder die Stopps anderer Fahrgäste.
  4. Ein veralteter Standort wird mit Alter ausgewiesen und nicht als aktuell dargestellt.
  5. Die Positionsfreigabe speichert keine zusätzliche Standorthistorie für den Fahrgast; ausgeliefert wird nur die jeweils letzte Position (Standortfrische gegen das vorläufige Ziel „höchstens 30 s im Normalbetrieb“, Kap. 08 §8.6, gemessen).
- **Tests:** positiv: Position in der Abholphase. negativ: nach Fahrtende, vor Phase, fremde Buchung, gecachte Antwort nach Freigabeende. konkurrierend (Sicherheit): Fahrtende und Positionsabruf gleichzeitig – kein Abruf nach bestätigtem Ende. Bezug AT-10 (GPS-Ausfall) für die Anzeige veralteter Daten und AT-59 (Standortdaten minimieren). Testfixture: abspielbare Positionsaufzeichnung mit Lücke und Fahrtende.
- **Abhängigkeiten:** intern: WP-M7a-01.
  - extern: WP-M2-43 (UI-04 Buchungsdetail mit Status und ETA)
  - extern: WP-M3-34, WP-M3-35 (Positionserfassung und Standortalter, Live-Aktualisierungskanal)
- **Entscheidungen:** D-11 (minimale Standorthaltung; blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Weitergabe an Dritte (WP-M7a-19); Positionsprojektion für Partnerleitstellen (M6).

### WP-M7a-19 – Abholfenster weitergeben

- **Ziel:** Fahrgäste geben ein laufendes Abholfenster als Push-, SMS- oder Kalenderinformation weiter; die geteilte Information enthält nur freigegebene, nicht personenbezogene Angaben.
- **Anforderungen:** `LH-6.3-PAX-015`
- **Liefergegenstände:**
  - Backend: Freigabeobjekt je Buchung mit Feld-Whitelist (Abholfenster, Treffpunktbezeichnung, Status), zufälligem befristetem Token, Widerruf; Kalendereintrag (iCalendar) und SMS/Push über den Nachrichtenversand.
  - Datenbank: Freigabe-Token (Vorschlag, Offene Punkte 4); `notification`, `notification_attempt`, `channel_template_revision`.
  - API: Freigabe erzeugen/widerrufen unter `/v1/bookings` (Pfadvorschlag `POST /v1/bookings/{id}/shares`); öffentlicher, ratenbegrenzter Lesepfad nur über Token; `/v1/notifications`.
  - Oberfläche: UI-04: Aktion „Abholfenster teilen“ mit Vorschau des exakt geteilten Inhalts und Kanalwahl; Liste aktiver Freigaben mit Widerruf; Zustand „Teilen nicht möglich“ außerhalb eines laufenden Abholfensters.
  - Konfigurationsparameter: `notification_quiet_hours` (Versandregel), Token-Gültigkeit (kein Katalogparameter, Offene Punkte 6).
- **Detailverträge vorab:** Whitelist der teilbaren Felder; Tokenlaufzeit (endet spätestens mit Fahrtende); Vorlagentexte; Klärung „Push an wen“ (Offene Punkte 19).
- **Akzeptanzkriterien:**
  1. SMS, Push und Kalendereintrag enthalten keinen Namen, keine Kontaktdaten, keinen Unterstützungsbedarf, keinen Boarding-Code, keine Zahlungsdaten und keine Buchungs-ID im Klartext (PAX-015).
  2. Der geteilte Inhalt entspricht exakt der dem Fahrgast gezeigten Vorschau (PAX-015).
  3. Der Freigabelink ist nach Fahrtende, Storno oder Widerruf ungültig.
  4. Ein erratener oder fremder Token liefert keine Daten; Abrufe sind ratenbegrenzt.
  5. Ändert sich das Abholfenster nach dem Teilen, zeigt der Freigabelink den aktuellen Stand; bereits versandte SMS/Kalendereinträge werden nicht stillschweigend als aktuell ausgegeben (Hinweis „Stand“ mit Zeitangabe im Inhalt).
- **Tests:** positiv: Teilen per SMS und Kalender. negativ: abgelaufener/widerrufener Token, Token eines anderen Mandanten, Feldprüfung der Payload gegen die Whitelist. konkurrierend (Sicherheit): Widerruf parallel zum Abruf.
- **Abhängigkeiten:** intern: WP-M7a-02, WP-M7a-18.
  - extern: WP-M2-32, WP-M2-33 (Nachrichtenversand mit Zustellversuchen, Vorlagen)
- **Entscheidungen:** D-11 (zulässige Datenweitergabe; blockiert nur Produktivschaltung).
- **Größe:** M
- **Nicht im Umfang:** Live-Positionsfreigabe an Dritte (nicht gefordert); Massenkommunikation (M7b).

### WP-M7a-20 – Einfache Sprache, Hilfetexte und kontextsensitive Fehlermeldungen

- **Ziel:** Die Passenger-Anwendung erklärt jeden Fehlschlag mit Ursache und konkreter Handlungsoption, bietet kontextbezogene Hilfe und eine Variante in einfacher Sprache.
- **Anforderungen:** `LH-6.3-PAX-016`
- **Liefergegenstände:**
  - Backend: stabiler Katalog Fehler-/Ablehnungscode → Ursache → Handlungsoption; Fehlermodell Kap. 05 §5.1 liefert maschinenlesbaren Code und Parameter (z. B. nächster buchbarer Zeitpunkt), keine fertigen Texte.
  - Oberfläche: UI-01 bis UI-05: Textkatalog je Sprache und Sprachniveau (Standard, einfache Sprache), Hilfetexte je Feld/Schritt, Fehlermeldungen mit Aktion („andere Zeit“, „andere Haltestelle“, „Callcenter“, „später erneut suchen“, Kap. 06 §6.2); Umschalter in UI-05.
  - Datenbank: `passenger_preference` (Sprachniveau); Texte versioniert im Web-Monorepo bzw. `channel_template_revision` für Nachrichten.
  - Kennzahlen: `Gebietsablehnungen`, `Zeitfensterablehnungen`, `Kapazitätsablehnungen` als Grundlage der Ursachenanzeige (Primärgrund je Versuch).
- **Detailverträge vorab:** vollständige Zuordnungstabelle Ablehnungsgrund → Text → Handlungsoption; Redaktionsregeln einfache Sprache; Prüfverfahren (Offene Punkte 20).
- **Akzeptanzkriterien:**
  1. Eine fehlgeschlagene Suche zeigt für jeden primären Ablehnungsgrund eine verständliche Ursache und mindestens eine konkret ausführbare Handlungsoption (PAX-016).
  2. Kein Fehlerzustand der Kernreise zeigt nur einen technischen Code oder eine leere Seite (PAX-016).
  3. Die Variante in einfacher Sprache deckt alle Texte der Kernreise Suche → Buchung → Storno ab.
  4. Hilfetexte sind per Tastatur erreichbar und werden vom Screenreader dem Feld zugeordnet.
- **Tests:** positiv: je Ablehnungsgrund ein UI-Test mit Ursache und Aktion. negativ: unbekannter Fehlercode fällt auf eine generische, aber handlungsleitende Meldung zurück; fehlender Textschlüssel schlägt in CI fehl. Barrierefreiheitsprüfung analog AT-19.
- **Abhängigkeiten:** intern: WP-M7a-01.
  - extern: WP-M2-13, WP-M2-35, WP-M2-36, WP-M2-37, WP-M2-41, WP-M2-42, WP-M2-43 (standardisierte Ablehnungsgründe, Passenger-PWA UI-01 bis UI-05)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** weitere Fremdsprachen über die vorbereitete Internationalisierung hinaus; Texte für Operator- und Driver-Oberflächen.

### WP-M7a-21 – Sperrbereiche nach Zeit, Raum, Dienst und Fahrzeugklasse

- **Ziel:** Sperrbereiche lassen sich mit Geometrie, Zeitfenster und Geltung je Dienst bzw. Fahrzeugklasse definieren; jede neue Planung hält aktive Sperrbereiche ein.
- **Anforderungen:** `LH-6.9-GEO-004`
- **Liefergegenstände:**
  - Backend: Restriktionsaggregat im Modul `service-geo` (Typ Sperrbereich, Geometrie WGS84/SRID 4326, Gültigkeit links geschlossen/rechts offen, Wiederholungsregel, Scope Dienst/Fahrzeugklasse); Leseport „wirksame Restriktionen zum Zeitpunkt t“ für `booking-dispatch`; Anwendung in Zulässigkeitsprüfung (Haltepunkte im Sperrbereich) und Kandidatensuche.
  - Datenbank: Restriktionstabelle (Vorschlag `geo_restriction`/`geo_restriction_revision`, nicht in Kap. 04 §4.4; Offene Punkte 4) mit GiST-Index, `CHECK(ST_IsValid(...))`; Bezug zu `service`, `service_area`.
  - API: Restriktionen pflegen (Pfadvorschlag `/v1/service-areas/{id}/restrictions` bzw. `/v1/restrictions`; Offene Punkte 5), GeoJSON/SRID-Konvention (Kap. 05 §5.7).
  - Oberfläche: UI-17 (Gebiete/Stops/Tarife): Sperrbereich zeichnen oder per Koordinatenliste erfassen, Zeitraum, Dienst/Fahrzeugklasse; Zustände „Ungültige Geometrie“, „konkurrierende Version“; UI-07 zeigt aktive Sperrbereiche als Ebene mit Listenalternative.
  - Ereignisse/Outbox: Restriktion aktiviert/deaktiviert (löst Prüfung betroffener Pläne aus).
  - Kennzahlen: `Gebietsablehnungen` (Ablehnung wegen Sperrbereich als eigener Sekundärgrund).
- **Detailverträge vorab:** DDL und JSON-Schema Restriktion; Enum Fahrzeugklasse (Quelle Fahrzeugstamm); Verhalten für bereits zugesagte Fahrten bei neu aktivem Sperrbereich (Offene Punkte 21).
- **Akzeptanzkriterien:**
  1. Bei aktivem Sperrbereich enthält keine neu geplante Route einen Stopp oder Wegabschnitt innerhalb des Bereichs für den betroffenen Dienst bzw. die betroffene Fahrzeugklasse (GEO-004).
  2. Außerhalb des Zeitfensters oder für nicht betroffene Dienste/Fahrzeugklassen wird derselbe Bereich normal bedient (GEO-004).
  3. Eine ungültige Geometrie oder ein Zeitraum mit Ende ≤ Beginn wird abgewiesen.
  4. Eine Anfrage mit Start oder Ziel im Sperrbereich wird mit eindeutigem Grund und Handlungsoption abgelehnt.
  5. Anlegen, Ändern, Aktivieren und Deaktivieren eines Sperrbereichs sind nur berechtigten Rollen des eigenen Mandanten möglich und mit Akteur, Zeitpunkt und Grund im Audit gespeichert.
- **Tests:** positiv: Planung um den Sperrbereich. negativ: ungültiges Polygon, Zeitfenster abgelaufen, Grenzzeitpunkt (links geschlossen, rechts offen), Rolle ohne Pflegerecht, fremder Mandant sieht Restriktion nicht. konkurrierend (Sicherheit/Kapazität): Aktivierung des Sperrbereichs parallel zu einem Hold – Hold prüft die wirksame Restriktionsrevision im Commit.
- **Abhängigkeiten:** intern: WP-M7a-02.
  - extern: WP-M1-20, WP-M1-22, WP-M1-46 (Konfigurations-/Freigaberegister, Audit)
  - extern: WP-M2-04, WP-M2-12, WP-M2-15 (Bediengebiete, Zulässigkeitsprüfung, Kandidatenfilter)
- **Entscheidungen:** D-09 (Routinganbieter; blockiert nicht den Bau der Flächenprüfung).
- **Größe:** M
- **Nicht im Umfang:** Streckenverbote im Routinggraph (WP-M7a-22); Versionierung und geplante Aktivierung (WP-M7a-23).

### WP-M7a-22 – Verbotene Strecken und Fahrzeugrestriktionen im Routing

- **Ziel:** Das Routing meidet ausgeschlossene Streckenabschnitte und beachtet Fahrzeugrestriktionen (z. B. Höhe, Gewicht, Breite) je Fahrzeugprofil.
- **Anforderungen:** `LH-6.9-GEO-005`
- **Liefergegenstände:**
  - Backend: Restriktionstyp „verbotene Strecke“ (Linien-/Flächengeometrie) und Fahrzeugprofil (Abmessungen, Gewicht, Klasse) im Routingauftrag.
  - Adapter/Worker: `workers/routing` und Valhalla-Adapter: Ausschluss von Kanten/Flächen und Kostenmodell je Fahrzeugprofil; Matrix- und Routenabfragen tragen Profil und Restriktionsrevision.
  - Datenbank: `route_matrix_cache` (Start/Ziel/Profil/Version im Cache-Key – Restriktionsrevision wird Teil der Version), Restriktionstabelle aus WP-M7a-21, `vehicle` (Profilmerkmale über Modul-API `fleet-safety`).
  - API: Pflege über die Restriktionspfade aus WP-M7a-21.
  - Oberfläche: UI-17: Strecke auf Karte wählen oder als Koordinatenfolge erfassen; UI-07: Routenverlauf zeigt gemiedene Abschnitte.
  - Tests: Testfixture „Testgraph“ mit zwei Wegen zwischen zwei Punkten (kürzester Weg über den gesperrten Abschnitt) und einem Abschnitt mit Fahrzeugrestriktion; wird von WP-M7a-21, WP-M7a-34 und WP-M7a-40 mitbenutzt.
- **Detailverträge vorab:** Abbildung Restriktion → Routingdienst (Ausschlussflächen, Kantenattribute); Fahrzeugprofilschema; Cache-Invalidierung bei Restriktionswechsel.
- **Akzeptanzkriterien:**
  1. Der Test-Routenverlauf zwischen zwei Punkten, deren kürzester Weg über den ausgeschlossenen Abschnitt führt, vermeidet diesen Abschnitt (GEO-005).
  2. Ein Fahrzeugprofil, das eine Fahrzeugrestriktion verletzt, erhält eine andere Route oder wird als Kandidat ausgeschlossen; ein zulässiges Profil darf den Abschnitt nutzen (GEO-005).
  3. Nach Aktivierung einer Restriktion liefert der Matrixcache keine Fahrzeiten der alten Version mehr.
  4. Ist kein zulässiger Weg vorhanden, wird begründet abgelehnt; es wird keine unzulässige Route geliefert.
  5. Die an den Fahrer ausgegebene Route bzw. Navigation (UI-10) verwendet dieselbe Restriktionsrevision und dasselbe Fahrzeugprofil wie die Disposition.
  6. Bei Ausfall oder Timeout des Routingdienstes wird kein Plan ohne Restriktionsprüfung zugesagt (begründete Ablehnung, Kap. 01 §1.4).
- **Tests:** positiv: Umfahrung auf Teststrecke. negativ: kein zulässiger Weg, veralteter Cacheeintrag, Profil ohne Pflichtmerkmal. Lastbezug: Matrixlatenz mit Ausschlüssen innerhalb des Routingbudgets (1 s lokale Kandidatensuche, Planungsdefault Kap. 01 §1.4).
- **Abhängigkeiten:** intern: WP-M7a-21.
  - extern: WP-M0-30 (Routing-Spike mit Restriktionen (D-09-Vergleich))
  - extern: WP-M2-10, WP-M2-17 (Routingadapter, Fahrzeitmatrix, Einfügeheuristik)
  - extern: WP-M3-03, WP-M3-28 (Fahrzeugstamm mit Profilmerkmalen, Navigation der Driver-App)
- **Entscheidungen:** D-09 (Routing/Karten: Fähigkeit des Anbieters zu Ausschlüssen und Fahrzeugprofilen; blockiert Produktivschaltung, Bau mit Valhalla/OSM als Arbeitsstand).
- **Größe:** M
- **Nicht im Umfang:** Echtzeit-Verkehrssperrungen aus Fremdquellen; Pausen- und Depotbedingungen (WP-M7a-34).

### WP-M7a-23 – Versionen und geplante Aktivierung von Zonen und Restriktionen

- **Ziel:** Zonen und Restriktionen besitzen unveränderliche Versionen mit geplantem Aktivierungszeitpunkt; jede Buchung weist die auf sie angewendete Regelversion nach.
- **Anforderungen:** `LH-6.9-GEO-006`
- **Liefergegenstände:**
  - Backend: Revisionsmodell Entwurf → geprüft → geplant → wirksam → abgelöst für `service_area` und Restriktionen; Aktivierung zum `valid_from` ohne Deployment; Rücknahme als neue Revision; Angebot und Buchung speichern die Geo-Regelversionen im `promise_snapshot`.
  - Datenbank: `service_area` (`revision`, `valid_from`, `valid_to`), Restriktionsrevision; Invariante „Nicht überlappende aktive Tarif-/Policy-/Dienstversion“ (Kap. 04 §4.5) als Exclusion Constraint bzw. Scope-Sperre; `booking.promise_snapshot`.
  - API: `/v1/service-areas` (Revision anlegen, planen, freigeben), Restriktionspfade; `If-Match` für konkurrierende Bearbeitung.
  - Oberfläche: UI-17: Revisionsliste mit Status, geplantem Wirksamwerden, Vergleich zur Vorversion; Zustand „konkurrierende Version“.
  - Ereignisse/Outbox: Revision geplant, wirksam geworden, abgelöst.
  - Adapter/Worker: Aktivierungs-Job zum Wirksamkeitszeitpunkt (idempotent), Cache-Invalidierung.
- **Detailverträge vorab:** Zustandsautomat Restriktions-/Zonenrevision; Vier-Augen-Regel; Wirkung auf Bestandsbuchungen (Offene Punkte 21).
- **Akzeptanzkriterien:**
  1. Eine Buchung zeigt in API und UI-07 die angewendete Zonen- und Restriktionsversion; nach Aktivierung einer neuen Version bleibt die Angabe der Bestandsbuchung unverändert (GEO-006).
  2. Eine für einen späteren Zeitpunkt geplante Version wirkt exakt ab `valid_from` (Serverzeit) und nicht davor (GEO-006).
  3. Zwei gleichzeitig wirksame, widersprüchliche Versionen desselben Scopes sind nicht aktivierbar.
  4. Eine veröffentlichte Version ist unveränderlich; Korrektur erfolgt als neue Revision.
- **Tests:** positiv: geplante Aktivierung, Rücknahme. negativ: Überlappung, Änderung einer veröffentlichten Version, Freigabe durch den Ersteller. konkurrierend (Datenkonsistenz): zwei parallele Aktivierungen desselben Scopes – genau eine gewinnt; Buchung genau am Wirksamkeitszeitpunkt (Muster PH-AT-018).
- **Abhängigkeiten:** intern: WP-M7a-21.
  - extern: WP-M1-20, WP-M1-22 (Konfigurationsregister-Muster (Wirksamkeit, Vier-Augen, Rollback als neue Revision))
  - extern: WP-M2-04, WP-M2-21 (Bediengebiete, Angebotsobjekt mit Revisionsbindung)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Versionierung der Haltepunkte (bereits `stop_revision`, M2); Tarifzonenversionen (Tarifmodul).

### WP-M7a-24 – Haltestelleneigenschaften für Barrierefreiheit

- **Ziel:** Haltestellen führen strukturierte Eigenschaften zu Rollstuhlzugang, Querung, Steigung und Fußwegbeschreibung; für ein Mobilitätsprofil ungeeignete Halte werden ausgeschlossen.
- **Anforderungen:** `LH-6.9-GEO-007`
- **Liefergegenstände:**
  - Backend: versioniertes JSON-Schema für `stop_revision.accessibility` (Rollstuhlzugang ja/nein/unbekannt, Querung, Steigung, Fußwegbeschreibung); Filter in Haltepunktauswahl und Angebotsbildung nach Mobilitätsprofil; „unbekannt“ gilt nicht als geeignet.
  - Datenbank: `stop_revision` (`accessibility`, Schema-Version), `passenger_preference` bzw. Anfrageprofil.
  - API: `/v1/stops` (Eigenschaften pflegen, filtern), `/v1/ride-offers` (Ablehnungsgrund bei fehlendem geeignetem Halt).
  - Oberfläche: UI-17 (Eigenschaften pflegen), UI-01/UI-02 (Eigenschaften und Fußwegbeschreibung am Haltepunkt; Zustand „unbestätigte Barrierefreiheit“), UI-10 (Hinweis für Fahrer).
  - Konfigurationsparameter: `stop_walk_max_m` (Ebene Dienst/Profil; „Barrierefreiheit wird vorrangig geprüft“).
  - Kennzahlen: `Ausstattungsablehnungen` (Sekundärgrund „kein zugänglicher Halt“ laut Detailvertrag).
- **Detailverträge vorab:** JSON-Schema `accessibility` v2 mit Backfillregel für Bestandsdaten; Zuordnung Mobilitätsprofil → Mindestanforderungen; Einheit und Grenzwert der Steigung (Offene Punkte 22).
- **Akzeptanzkriterien:**
  1. Für ein Profil mit Rollstuhlbedarf wird ein als nicht zugänglich gekennzeichneter Halt weder als Einstieg noch als Ausstieg angeboten oder zugeteilt (GEO-007).
  2. Ein Halt mit unbekannter Zugänglichkeit wird für dieses Profil ebenfalls nicht automatisch zugeteilt und als „unbestätigt“ ausgewiesen.
  3. Ohne geeigneten Halt innerhalb von `stop_walk_max_m` wird begründet abgelehnt.
  4. Die Fußwegbeschreibung ist als Text verfügbar und screenreaderlesbar.
- **Tests:** positiv: zugänglicher Alternativhalt wird gewählt. negativ: nur unzugängliche Halte, ungültiges Schema, Bestandsdaten ohne Angabe. Bezug AT-04 (Rollstuhltransport) und AT-19.
- **Abhängigkeiten:** intern: WP-M7a-02.
  - extern: WP-M2-05, WP-M2-38, WP-M2-21 (Haltepunktrevisionen, Ortssuche, Angebotsbildung)
- **Entscheidungen:** D-09 (Verfügbarkeit realer Barrierefreiheitsdaten; blockiert nur Produktivschaltung mit Echtdaten).
- **Größe:** M
- **Nicht im Umfang:** barrierefreies Fußwegrouting über einen Routingdienst; gemeinsame Optimierung von Fußweg, Sicherheit und Umweg (GEO-010, P3).

### WP-M7a-25 – Redaktionelle Freigabe virtueller Haltepunkte

- **Ziel:** Virtuelle Haltepunkte durchlaufen vor der Aktivierung einen redaktionellen Sicherheits-Freigabeprozess; ein nicht freigegebener Punkt kann nie automatisch zugeteilt werden.
- **Anforderungen:** `LH-6.9-GEO-008`
- **Liefergegenstände:**
  - Backend: Zustandsautomat `DRAFT → IN_REVIEW → APPROVED → ACTIVE → RETIRED` (und `REJECTED`) für Haltepunktrevisionen vom Typ „virtuell“; Sicherheitsprüfliste als Pflichtangaben; Vier-Augen-Regel (Prüfer ≠ Ersteller); Haltepunktauswahl der Disposition liest ausschließlich freigegebene wirksame Revisionen.
  - Datenbank: `stop_revision` (Typ, Freigabestatus, Prüfer, Prüfzeitpunkt, Prüfangaben); Anpassung, da `approved_by` im Kern-DDL `NOT NULL` ist und Entwürfe damit nicht speicherbar wären (Offene Punkte 23); `audit_event`.
  - API: `/v1/stops` (einreichen, prüfen, freigeben, ablehnen) mit `If-Match`.
  - Oberfläche: UI-17: Arbeitsliste „zu prüfende Haltepunkte“, Prüfdialog mit Sicherheitskriterien, Zustand „konkurrierende Version“; Kartenlage mit Listenalternative.
  - Ereignisse/Outbox: Haltepunkt eingereicht, freigegeben, abgelehnt.
- **Detailverträge vorab:** Zustandsautomat; Katalog der Sicherheitskriterien (Offene Punkte 24); Rollen „Redaktion“ und „Freigabe“.
- **Akzeptanzkriterien:**
  1. Ein virtueller Haltepunkt im Status Entwurf, in Prüfung oder abgelehnt wird von der automatischen Zuteilung nie verwendet – auch nicht bei direkter Angabe seiner ID in einer Anfrage (GEO-008).
  2. Erst nach Freigabe durch eine berechtigte zweite Person und Erreichen von `valid_from` ist der Punkt zuteilbar (GEO-008).
  3. Jede Freigabe und Ablehnung ist mit Akteur, Zeitpunkt und Prüfangaben im Audit nachvollziehbar.
  4. Der Ersteller kann seinen eigenen Punkt nicht freigeben.
- **Tests:** positiv: Einreichen → Freigabe → Zuteilung. negativ: Zuteilung eines nicht freigegebenen Punkts, Selbstfreigabe, fehlende Pflichtprüfangabe. konkurrierend (Sicherheit): Rücknahme der Freigabe parallel zu einem Hold auf diesen Punkt.
- **Abhängigkeiten:** intern: WP-M7a-02, WP-M7a-24.
  - extern: WP-M1-07, WP-M1-08, WP-M1-22, WP-M1-46 (Rollen-/Objektberechtigung, Vier-Augen-Muster, Audit)
  - extern: WP-M2-05, WP-M1-43 (Haltepunktrevisionen mit Aktivstatus, UI-17-Grundlage)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** automatische Erzeugung virtueller Haltepunkte; temporäre Gültigkeit (WP-M7a-26).

### WP-M7a-26 – Temporäre Veranstaltungshaltestellen

- **Ziel:** Veranstaltungshaltestellen werden über befristete Kalenderregeln aktiviert und sind außerhalb ihres Gültigkeitsfensters nicht buchbar.
- **Anforderungen:** `LH-6.9-GEO-011`
- **Liefergegenstände:**
  - Backend: Kalenderregel je Haltepunktrevision (ein oder mehrere befristete Fenster in der Zeitzone des Dienstes, links geschlossen/rechts offen); Buchbarkeitsprüfung gegen den **Fahrtzeitpunkt** (nicht den Buchungszeitpunkt) in Suche, Hold und Confirm.
  - Datenbank: `stop_revision` (`valid_from`, `valid_to`; Kalenderregel als versionierte Angabe).
  - API: `/v1/stops` (Kalenderregel pflegen), `/v1/ride-offers`.
  - Oberfläche: UI-17 (Typ „temporär“, Fenster erfassen), UI-01 (temporäre Halte nur für passende Fahrtzeiten in Suche und Karte).
- **Detailverträge vorab:** Schema der Kalenderregel; Verhalten bestehender Buchungen bei Verkürzung des Fensters (Offene Punkte 21).
- **Akzeptanzkriterien:**
  1. Für eine Fahrtzeit außerhalb des Gültigkeitsfensters ist der Punkt weder suchbar noch buchbar; ein direkter API-Aufruf mit seiner ID wird abgelehnt (GEO-011).
  2. Für eine Fahrtzeit innerhalb des Fensters ist der freigegebene Punkt buchbar, auch wenn die Buchung vor Fensterbeginn erfolgt.
  3. Am Grenzzeitpunkt gilt Serverzeit mit rechts offenem Intervall.
- **Tests:** positiv: Buchung im Fenster. negativ: vor/nach Fenster, Grenzzeitpunkt, DST-Wechsel im Fenster, nicht freigegebener temporärer Punkt.
- **Abhängigkeiten:** intern: WP-M7a-25.
- **Entscheidungen:** keine.
- **Größe:** S
- **Nicht im Umfang:** Veranstaltungsverkehr mit Sonderfahrplan oder Sondertarif.

### WP-M7a-27 – Versionierter Geodatenimport mit Validierung

- **Ziel:** Geodaten (Gebiete, Haltepunkte, Restriktionen) werden als versionierter Import geladen, vollständig validiert und erst nach fehlerfreier Prüfung und Freigabe aktiviert.
- **Anforderungen:** `LH-6.9-GEO-013`
- **Liefergegenstände:**
  - Backend: Importjob als `operation` mit Zuständen `UPLOADED → VALIDATING → VALIDATED | REJECTED → ACTIVATED`; Validierung von Schema, Koordinatensystem (Transformation nach bzw. Abweisung außerhalb WGS84/SRID 4326), Geometriegültigkeit und Dubletten (gleiche Kennung, nahezu identische Lage); vollständiger Fehlerbericht statt Abbruch beim ersten Fehler; Aktivierung erzeugt neue Revisionen über WP-M7a-23/-25, nie ein Überschreiben.
  - Datenbank: Importtabellen (Vorschlag `geo_import`/`geo_import_finding`, nicht in Kap. 04 §4.4; Offene Punkte 4); Quelldatei im Objektspeicher mit Hash; Staging getrennt von wirksamen Tabellen.
  - API: Import anlegen, Bericht lesen, aktivieren (Pfadvorschlag `/v1/geo-imports`; Offene Punkte 5); Upload über Uploadticket mit Größe/MIME/Hash; Fortschritt über `/v1/operations/{operation_id}`.
  - Oberfläche: UI-17: Importliste, Fehlerbericht je Objekt mit Zeile und Regel, Aktivierung nur bei null Fehlern.
  - Adapter/Worker: fortsetzbarer Validierungs-Worker mit Tenantkontext und Malware-/Formatprüfung der Datei.
- **Detailverträge vorab:** unterstützte Formate (Offene Punkte 25); Dublettenregel (Distanzschwelle, Kennung); Fehlerkatalog; Größenlimits.
- **Akzeptanzkriterien:**
  1. Ein Import mit Schemafehler, falschem Koordinatensystem und Dublette weist **alle** Fehler vor einer Aktivierung im Bericht aus (GEO-013).
  2. Ein Import mit mindestens einem Fehler kann nicht aktiviert werden; wirksame Daten bleiben unverändert (GEO-013).
  3. Jeder Import ist als Version mit Quelle, Hash, Importeur und Zeitpunkt nachvollziehbar; die Aktivierung erzeugt neue Revisionen (GEO-013).
  4. Ein abgebrochener Import ist fortsetzbar und führt zu keiner Teilaktivierung.
- **Tests:** positiv: fehlerfreier Import → Aktivierung. negativ: je Fehlerklasse eine Datei, übergroße Datei, fremder Mandant liest Bericht nicht. konkurrierend: zwei Importe aktivieren gleichzeitig denselben Scope – genau einer wird wirksam.
- **Abhängigkeiten:** intern: WP-M7a-23, WP-M7a-24, WP-M7a-25.
  - extern: WP-M1-15, WP-M1-11 (`operation`-Ressource mit Lease/Fencing, Objektspeicher mit Tenanttrennung)
- **Entscheidungen:** D-09 (Daten- und Kartenlizenzen; blockiert nur Import produktiver Fremddaten).
- **Größe:** M
- **Nicht im Umfang:** Fahrplanimport GTFS/NeTEx (`timetable_feed`, M6a); allgemeine Datenimporte der INT-Anforderungen.

### WP-M7a-28 – Knotenspezifische Umsteige- und Wegezeiten

- **Ziel:** ÖPNV-Knoten führen Umsteige- und Wegezeiten je Verbindung oder Plattform; die Anschlussberechnung verwendet den knotenspezifischen Mindestpuffer statt eines Pauschalwerts.
- **Anforderungen:** `LH-6.9-GEO-012`
- **Liefergegenstände:**
  - Backend: Auflösung der Mindestumsteigezeit in der Reihenfolge Verbindung/Plattformpaar → Knoten → Region/Dienst-Default; Ergebnis als `minimum_transfer_seconds` (enthält den gesamten Geh-/Assistenz-/Sicherheitspuffer, wird nicht doppelt addiert, Kap. 05 §5.9).
  - Datenbank: `transfer_hub`, `transfer_rule` (Stoppaare, Gehweg, Puffer; Erweiterung um Plattform-/Verbindungsbezug und Profil laut Detailvertrag, Offene Punkte 26), Bezug zu `stop_revision`.
  - API: `/v1/transfer-hubs` (Regeln je Verbindung/Plattform pflegen, versioniert).
  - Oberfläche: UI-17: Knoten mit Matrix der Umsteigezeiten; UI-02: Umstiegszeit und Fußweg je Option.
  - Konfigurationsparameter: `transfer_min_time_min` (Ebene Region/Transfer) als Rückfallwert, `interchange_min_time_min` (Übergabepunkt Partner).
- **Detailverträge vorab:** Erweiterungs-DDL `transfer_rule`; Präzedenz der drei Parameterquellen; Profilzuschlag bei Mobilitätseinschränkung.
- **Akzeptanzkriterien:**
  1. Für einen Knoten mit hinterlegten 8 Minuten zwischen zwei Plattformen und einem Regionsdefault von 5 Minuten rechnet die Anschlussprüfung mit 8 Minuten (GEO-012).
  2. Fehlt eine knotenspezifische Angabe, gilt nachweislich der Rückfallwert `transfer_min_time_min`; die verwendete Quelle ist am Angebot ausgewiesen.
  3. `arrival_latest(A) + minimum_transfer_seconds <= departure_earliest(B)` wird serverseitig geprüft; ein zu knapper Umstieg wird nicht angeboten.
- **Tests:** positiv: knotenspezifischer Wert greift. negativ: negativer Wert, Regel für nicht existierendes Stoppaar, Überlappung zweier wirksamer Regelversionen. Grundlage für AT-14 (Anschlusswarnung, Gate M6).
- **Abhängigkeiten:** intern: WP-M7a-02.
  - extern: WP-M6a-08, WP-M6b-02, WP-M6b-10 (`transfer_hub`/`transfer_rule`-Grundmodell und `/v1/transfer-hubs`)
- **Entscheidungen:** D-04 (Anschlussrisiko; blockiert nur Produktivschaltung abgesicherter Übergänge).
- **Größe:** M
- **Nicht im Umfang:** Echtzeit-Gleiswechsel; Fußwegrouting innerhalb von Bahnhöfen.

### WP-M7a-29 – Anschlussprioritäten für Bahnhöfe

- **Ziel:** Die Disposition plant Zubringerfahrten zu Bahnhöfen so, dass der konfigurierte Mindestpuffer zum Anschluss eingehalten wird, und nutzt verfügbare Echtzeitdaten.
- **Anforderungen:** `LH-6.5-OPT-013`
- **Liefergegenstände:**
  - Backend: Anschlussziel an der Anfrage (gewünschte Abfahrt/Ankunft am Knoten); spätestes Ankunftsfenster = Abfahrt − knotenspezifische Umsteigezeit − Anschlusspuffer als harte Nebenbedingung; Priorität anschlussgebundener Fahrten in der Mehrzielbewertung; Echtzeitabweichung der Anschlussfahrt verschiebt das Ziel nur innerhalb zugesagter Fenster.
  - Adapter/Worker: `workers/routing` (Constraint „Anschlusspuffer“), Lesen von `realtime_observation` und `timetable_feed` mit Quellenzeit und Staleness; unbekannte Echtzeit bleibt unbekannt (nicht „pünktlich“).
  - Datenbank: `transfer_rule`, `realtime_observation`, `journey_leg` (Fenster), `booking.promise_snapshot`.
  - API: `/v1/ride-offers`, `POST /v1/federation/journeys/plan` (Anschlusswunsch).
  - Konfigurationsparameter: `advance_arrival_buffer_min` (Ebene Dienst/Transfer), `transfer_min_time_min`.
  - Kennzahlen: `Nicht erreichte Anschlüsse` (unbekannte Daten separat).
- **Detailverträge vorab:** Constraint-Definition; Verhalten bei veralteter Echtzeit; Abgrenzung zur intermodalen Anschlussplanung aus M6a (Offene Punkte 3).
- **Akzeptanzkriterien:**
  1. Im Test liegt die geplante späteste Ankunft der Anschlussfahrt mindestens um den konfigurierten Mindestpuffer (`advance_arrival_buffer_min` plus knotenspezifische Umsteigezeit) vor der Abfahrt des Anschlusses (OPT-013).
  2. Ist der Puffer nicht einhaltbar, wird kein Angebot mit Anschlussbezug zugesagt; der Ablehnungsgrund ist eindeutig.
  3. Verfügbare Echtzeitdaten mit gültigem Alter werden berücksichtigt; veraltete oder fehlende Echtzeit führt zur Planung gegen Solldaten mit entsprechendem Kennzeichen.
- **Tests:** positiv: Zubringer mit Puffer. negativ: Puffer nicht einhaltbar, Echtzeit veraltet. konkurrierend (Kapazität): zwei Anschlussfahrten zum selben Zug um den letzten Platz. Bezug AT-14. Testfixture: Fahrplan- und Echtzeit-Testfeed aus M6a mit einer Bahnhofsabfahrt und steuerbarer Verspätung.
- **Abhängigkeiten:** intern: WP-M7a-28.
  - extern: WP-M2-17, WP-M2-18, WP-M2-19, WP-M2-20 (Einfügeheuristik, harte Zeitfenster, Mehrzielbewertung)
  - extern: WP-M6a-26, WP-M6a-27, WP-M6a-28 (Fahrplan- und Echtzeitadapter, intermodale Anschlussplanung)
- **Entscheidungen:** D-04 (Anschlussabsicherung; blockiert nur Produktivschaltung garantierter Anschlüsse), D-09 (Echtzeitdatenquellen).
- **Größe:** M
- **Nicht im Umfang:** Anschlusswarnung und Ersatzangebot zur Laufzeit (M6a); Anschlussgarantie (M6a).

### WP-M7a-30 – Parallelfahrtenverbot mit Linienalternativen und Ausnahmen

- **Ziel:** Die Dispositionsregeln zum Parallelfahrtenverbot berücksichtigen relevante Linienalternativen und definierte Ausnahmen; unzulässiger Parallelverkehr wird auf Testrelationen verhindert.
- **Anforderungen:** `LH-6.5-OPT-012`
- **Liefergegenstände:**
  - Backend: Regelauswertung im Constraint Validator des Moduls `booking-dispatch`: Bestimmung der relevanten Linienalternative (Vergleichskriterien aus der Parallelfahrtenregel des ÖPNV-Moduls), Ausnahmekatalog (z. B. Mobilitätseinschränkung, Tageszeit, Relation, Dienst – abschließende Liste im Detailvertrag, Offene Punkte 27), Anwendung in Angebot **und** Hold/Commit sowie bei manuellen Eingriffen; Ablehnung mit Verweis auf die Alternative.
  - Datenbank: Regel und Ausnahmen als versionierte Konfiguration in `config_revision`; `travel_attempt` (`primary_rejection`, `secondary_rejections`); angewendete Regelversion am Angebot.
  - API: `/v1/config/revisions`, `/v1/config/revisions/{id}`, `/validate`, `/impact`; `/v1/ride-offers`.
  - Oberfläche: UI-17 (Regel und Ausnahmen pflegen, Wirkungsbericht), UI-02 (ÖPNV-Alternative statt On-Demand-Angebot mit Begründung).
  - Kennzahlen: Ablehnungen wegen Parallelfahrt als eigener Ablehnungsgrund im Nachfragetrichter (Zuordnung zu `Zeitfensterablehnungen`/`Gebietsablehnungen` oder eigenem Grund laut Detailvertrag).
- **Detailverträge vorab:** JSON-Schema Regel + Ausnahmen; Abgrenzung zur Parallelfahrtenregel aus M6a (Offene Punkte 3); Testrelationen mit erwartetem Ergebnis.
- **Akzeptanzkriterien:**
  1. Auf einer Testrelation mit zumutbarer Linienalternative nach den konfigurierten Kriterien entsteht kein On-Demand-Angebot; die Alternative wird genannt (OPT-012).
  2. Auf derselben Relation entsteht bei greifender Ausnahme ein Angebot; die angewendete Ausnahme ist protokolliert (OPT-012).
  3. Ohne verfügbare Fahrplandaten greift das im Detailvertrag festgelegte Verhalten; es wird nie stillschweigend „keine Alternative“ angenommen, ohne den Datenstand auszuweisen.
  4. Die Regel wird auch im Hold/Commit und bei manueller Disposition geprüft; eine Umgehung über einen anderen Kanal ist nicht möglich.
- **Tests:** positiv: Verbot greift, Ausnahme greift. negativ: Regel ohne Freigabe unwirksam, Fahrplandaten veraltet, Umgehungsversuch über Leitstellenbuchung ohne Ausnahmerecht. Erweitert AT-13 (Parallelfahrtenverbot; „Ablehnung/Alternative und Ausnahme korrekt“). Testfixture: Fahrplan-Testfeed aus M6a mit den Testrelationen der Abnahme.
- **Abhängigkeiten:** intern: WP-M7a-01.
  - extern: WP-M1-20, WP-M1-23 (Konfigurationsregister mit Wirkungsbericht)
  - extern: WP-M2-21, WP-M2-13 (Angebotsbildung, Ablehnungsgründe)
  - extern: WP-M6a-29, WP-M6a-28, WP-M6a-26 (Parallelfahrtenregel mit Vergleichskriterien, ÖPNV-Alternativen, Fahrplanadapter)
- **Entscheidungen:** D-08 (verfügbare Fahrplanstandards; ohne Beschluss nur Testfeeds). Die genehmigungsrechtliche Bewertung liegt beim Auftraggeber (blockiert Produktivschaltung, nicht den Bau).
- **Größe:** M
- **Nicht im Umfang:** Vergleichskriterien der Regel selbst (PTI-004, M6a); rechtliche Bewertung der Genehmigungslage.

### WP-M7a-31 – ETA mit prognostizierter Fahrzeugposition und Unsicherheit

- **Ziel:** Die Engine berechnet ETAs aus prognostizierter Fahrzeugposition und Fahrzeitunsicherheit und aktualisiert sie, sobald eine planrelevante Verzögerung eintritt.
- **Anforderungen:** `LH-6.5-OPT-014`
- **Liefergegenstände:**
  - Backend: Positionsprognose entlang der geplanten Route aus letzter Messung, Messzeit und Planfortschritt; ETA = Routingzeit + Haltezeit + Unsicherheitspuffer zum konfigurierten Quantil (Kap. 01 §1.6); Schwellwertlogik „planrelevant“; jede Prognose liefert Modellversion, Unsicherheit und Fallbackgrund.
  - Adapter/Worker: ETA-Neuberechnung ereignisgetrieben (Position, Fahrerereignis, Planrevision), entkoppelt von der Angebotslatenz.
  - Datenbank: `position_sample` (Mess-/Empfangszeit, Qualität), `route_stop`, `eta_model_revision` (Lesen), `route_matrix_cache`.
  - API: ETA-Felder an `/v1/bookings` und `/v1/operator/vehicles`.
  - Ereignisse/Outbox: ETA geändert (nur bei Überschreiten der Schwelle; Auslöser für Benachrichtigung und Anschlussprüfung).
  - Oberfläche: UI-04 (ETA getrennt von Zusage), UI-07 (ETA mit Standortalter).
  - Konfigurationsparameter: `eta_model_quantile`, `eta_max_data_age_sec`, `eta_fallback_policy`, `eta_personalization_enabled` (bleibt aus), `customer_notify_before_pickup_min`.
  - Kennzahlen: `ETA-MAE`, `ETA-Bias`, `ETA-p90-Abdeckung` (Datenlieferung; Berechnung M8).
- **Detailverträge vorab:** Definition „planrelevante Verzögerung“ (Schwelle; kein Katalogparameter, Offene Punkte 6); Fallback bei Datenalter über `eta_max_data_age_sec`; Abgrenzung zu den ETA-Anforderungen des M8 (Offene Punkte 28).
- **Akzeptanzkriterien:**
  1. Verzögert sich ein Fahrzeug im Test über die Schwelle, wird die ETA der betroffenen Stopps neu berechnet, als Ereignis verteilt und in UI-04/UI-07 aktualisiert (OPT-014).
  2. Unterhalb der Schwelle entsteht kein Änderungsereignis (kein Benachrichtigungsflattern).
  3. Bei Positionsdaten älter als `eta_max_data_age_sec` wird auf das fahrerneutrale Referenzmodell zurückgefallen und der Fallbackgrund ausgewiesen.
  4. Die ETA verändert nie die gespeicherte Zusage; beide werden getrennt geführt.
- **Tests:** positiv: Verzögerung → neue ETA. negativ: veraltete Position, unplausible Messung, Personalisierung bleibt trotz Parameter ohne Freigabe aus. Bezug AT-10 (GPS-Ausfall); Vorbereitung für PH-AT-022 (Sichere ETA, Gate M8). Testfixture: abspielbare Positionsaufzeichnung mit steuerbarer Verzögerung (gemeinsam mit WP-M7a-18).
- **Abhängigkeiten:** intern: WP-M7a-01.
  - extern: WP-M2-10, WP-M2-28 (Routingadapter, Planrevisionsverteilung)
  - extern: WP-M3-34 (Positionserfassung und Standortalter)
  - extern: WP-M4-61, WP-M4-62 (ETA-Grundmodell ohne Fahrerprofil)
- **Entscheidungen:** D-10 (Fahrerdaten/ETA: Personalisierung bleibt aus; blockiert nicht den Bau), D-09 (Verkehrsdatenquelle).
- **Größe:** M
- **Nicht im Umfang:** lernende bzw. fahrerindividuelle Prognosen, Modellgüte-Endpunkte `/v1/eta/models/{id}/quality` (M8); Anschlussrisikoberechnung (M6a).

### WP-M7a-32 – Stabilitätsschutz und nicht umplanbarer Nahhorizont

- **Ziel:** Je Dienst sind Stabilitätsschutz und ein nicht mehr umplanbarer Nahhorizont konfigurierbar; kurzfristige Neuanfragen stellen unmittelbar bevorstehende Stopps nicht um.
- **Anforderungen:** `LH-6.5-OPT-016`
- **Liefergegenstände:**
  - Backend: Markierung „geschützt“ für Stopps, deren geplante Zeit innerhalb des Nahhorizonts liegt; Einfügeheuristik und periodische Verbesserung dürfen geschützte Stopps weder in Reihenfolge noch Fahrzeugzuordnung ändern; Zähler der Umplanungen je Buchung; autorisierter Ausnahmeprozess (Leitstelle mit Begründung).
  - Adapter/Worker: `workers/routing` – geschützte Stopps als fixierte Präfixe der Route.
  - Datenbank: `route_stop` (Schutzkennzeichen bzw. Ableitung), `vehicle_run` (`revision`), `booking_change` (Umplanungszähler).
  - API: `/v1/runs/{id}/revisions` (Konfliktantwort bei Verletzung), `/v1/config/effective`.
  - Oberfläche: UI-07: geschützte Stopps als Text/Symbol gekennzeichnet; Parameterpflege je Dienst in UI-17 (Zuordnung der Ansicht siehe Offene Punkte 37).
  - Konfigurationsparameter: `protected_stop_horizon_min` („nur unter autorisiertem Ausnahmeprozess verletzbar“), `max_reassignments` („Zugesagte Fahrten schützen“).
- **Detailverträge vorab:** genaue Definition „Umstellung“ (Reihenfolge, Fahrzeug, Zeit); Bezugszeit des Horizonts (Planzeit oder ETA); Inhalt von „Stabilitätsschutz“ über den Horizont hinaus (Offene Punkte 29).
- **Akzeptanzkriterien:**
  1. Eine kurzfristige Neuanfrage ändert weder Reihenfolge noch Fahrzeug eines Stopps innerhalb von `protected_stop_horizon_min`; die Anfrage wird hinter dem Horizont eingefügt, einem anderen Fahrzeug zugewiesen oder begründet abgelehnt (OPT-016).
  2. Zwei Dienste mit unterschiedlichem Horizont verhalten sich im selben Szenario unterschiedlich (OPT-016).
  3. Eine Buchung wird höchstens `max_reassignments`-mal einem anderen Fahrzeug zugewiesen.
  4. Eine Verletzung ist nur über den autorisierten Ausnahmeprozess mit Begründung und Audit möglich.
- **Tests:** positiv: Einfügung hinter dem Horizont. negativ: erzwungene Umstellung ohne Ausnahmerecht, Parameter außerhalb des Wertebereichs. konkurrierend (Kapazität/Plan): Neuanfrage und Horizonteintritt eines Stopps gleichzeitig – Planrevision bleibt konsistent, kein geschützter Stopp verschoben; periodische Verbesserung parallel zu einer Neuanfrage.
- **Abhängigkeiten:** intern: WP-M7a-01, WP-M7a-02.
  - extern: WP-M1-20, WP-M1-21 (Konfigurationsregister)
  - extern: WP-M2-16, WP-M2-17, WP-M2-19 (Fahrtenplanmodell, Einfügeheuristik, Schutz zugesagter Zeitfenster)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Prüfung manueller Eingriffe mit Auswirkungsanzeige (WP-M7a-33).

### WP-M7a-33 – Auswirkungsprüfung manueller Leitstelleneingriffe

- **Ziel:** Vor der Freigabe eines manuellen Eingriffs sieht der Disponent alle Regelverletzungen und alle betroffenen Zeitfenster anderer Buchungen.
- **Anforderungen:** `LH-6.5-OPT-015`
- **Liefergegenstände:**
  - Backend: Vorschau („dry run“) einer manuellen Planänderung gegen den Constraint Validator: Regelverletzungen (harte Sperrregel, prüfpflichtiger Hinweis) und Liste aller Buchungen, deren Abhol-/Ankunftsfenster sich ändern oder verletzt werden; Freigabe nur mit der Vorschau-Revision (Plan-CAS) und Begründung; harte Sicherheitsregeln sind nicht übersteuerbar.
  - Datenbank: `vehicle_run` (`revision`), `route_stop`, `booking_change` (Begründung, Akteur), `audit_event`.
  - API: `/v1/runs/{id}/revisions` (Vorschau und Commit mit `If-Match`; Konfliktantwort und Planvergleich, Kap. 05 §5.7).
  - Oberfläche: UI-07: Warndialog mit vollständiger, sortierbarer Liste betroffener Buchungen (altes/neues Fenster, Zusageverletzung ja/nein), tastaturbedienbare Alternative zu Drag-and-drop.
  - Ereignisse/Outbox: Planrevision manuell geändert (mit Begründung).
- **Detailverträge vorab:** Vorschau-Antwortschema; Klassifikation harte Regel / prüfpflichtiger Hinweis; Gültigkeitsdauer einer Vorschau.
- **Akzeptanzkriterien:**
  1. Vor der Freigabe zeigt die Warnung alle Buchungen, deren Zeitfenster durch den Eingriff betroffen sind – im Test mit drei betroffenen Buchungen genau diese drei mit altem und neuem Fenster (OPT-015).
  2. Regelverletzungen werden mit Regel und betroffenem Objekt benannt; harte Sicherheitsregeln verhindern die Freigabe (OPT-015).
  3. Hat sich der Plan seit der Vorschau geändert, scheitert die Freigabe mit `412` und es wird eine neue Vorschau verlangt; kein stilles Überschreiben.
  4. Jede Freigabe trotz Hinweis ist mit Begründung und Akteur im Audit gespeichert.
- **Tests:** positiv: Eingriff mit Warnung und Freigabe. negativ: fehlende Begründung, harte Regelverletzung, veraltete Vorschau. konkurrierend (Kapazität): zwei Disponenten ändern denselben Run – genau eine Freigabe gewinnt; Eingriff parallel zu automatischer Einfügung.
- **Abhängigkeiten:** intern: WP-M7a-32.
  - extern: WP-M3-40, WP-M3-36 (manuelle Umplanung mit Begründung und Plan-CAS, UI-07)
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Eingriffe in Partnerressourcen (M6); erweiterte Leitstellenfunktionen der OPS-Anforderungen (M7b).

### WP-M7a-34 – Fahrzeugabhängige Erreichbarkeit, Pausen und Depotbedingungen

- **Ziel:** Das Routing plant Umläufe so, dass fahrzeugabhängige Erreichbarkeit, geplante Pausen und Depotanforderungen eingehalten werden; jeder Umlauf endet innerhalb der zulässigen Schicht- und Depotbedingungen.
- **Anforderungen:** `LH-6.5-OPT-017`
- **Liefergegenstände:**
  - Backend: Umlaufgrenzen je `vehicle_run` aus Schichtbeginn/-ende, Pausenfenstern und Start-/Enddepot; Erreichbarkeitsfilter je Fahrzeugprofil (Restriktionen aus WP-M7a-22); Angebot nur, wenn die Rückfahrt zum Depot vor Schichtende einschließlich Fahrzeit möglich ist.
  - Adapter/Worker: `workers/routing` – Pausen als nicht belegbare Intervalle, Depot als Pflichtstart/-ende, Lade-/Reichweitenreserve als Nebenbedingung.
  - Datenbank: `staff_shift` (`starts_at`, `ends_at`, Pausenplan über Modul-API `staff-time`), `vehicle_run`, `route_stop`, `vehicle_assignment`; Depotstammdaten über Modul-API (Offene Punkte 30).
  - API: `/v1/operator/vehicles` (Umlauf mit Pausen und Depotfahrt), `/v1/runs/{id}/revisions`.
  - Oberfläche: UI-07: Pausen und Depotfahrten in der Stoppfolge sichtbar.
  - Konfigurationsparameter: `min_return_soc_pct` (Ebene Depot/Fahrzeug), `min_battery_soc_pct`, `max_daily_work_minutes` (als Obergrenze gelesen).
- **Detailverträge vorab:** Leseports zu Schicht/Pause (staff-time) und Depot (Flotte/Asset); Regel für Pausenverschiebung (fix oder Fenster); Verhalten bei Schichtänderung nach Zusage.
- **Akzeptanzkriterien:**
  1. Im Test endet jeder geplante Umlauf am vorgeschriebenen Depot vor Schichtende; eine Anfrage, die das verletzen würde, wird diesem Fahrzeug nicht zugewiesen (OPT-017).
  2. In ein geplantes Pausenintervall wird kein Stopp gelegt (OPT-017).
  3. Ein für das Fahrzeugprofil nicht erreichbarer Haltepunkt führt zum Ausschluss dieses Fahrzeugs, nicht zu einer unzulässigen Route (OPT-017).
  4. Eine prognostizierte Unterschreitung von `min_return_soc_pct` bei der Rückkehr verhindert die Zuweisung.
- **Tests:** positiv: Umlauf mit Pause und Depotende. negativ: Anfrage kurz vor Schichtende, Pause würde überfahren, unerreichbarer Halt. konkurrierend (Kapazität): Schichtverkürzung parallel zu einem Hold – Hold prüft Schichtrevision im Commit.
- **Abhängigkeiten:** intern: WP-M7a-22.
  - extern: WP-M2-17, WP-M2-15 (Einfügeheuristik, Fahrzeugkandidatenfilter)
  - extern: WP-M3-07, WP-M3-03 (Fahrer-/Fahrzeugzuweisung mit Schichtgrenzen, Fahrzeugstamm)
  - extern: WP-M4-03, WP-M4-17 (Dienstplan mit Schichten und Pausenplan)
- **Entscheidungen:** D-09 (Telematik/Ladeinfrastrukturdaten; blockiert nur Produktivschaltung der SoC-Bedingung).
- **Größe:** M
- **Nicht im Umfang:** Pausen aus der Personalplanung in die Fahrzeugverfügbarkeit übernehmen (SCH-018, M7d – liefert die Daten); Betriebshofverwaltung (AST, M7c); E-Reichweitenkonflikt als eigener Test (AT-20, M9).

### WP-M7a-35 – Rückkehr- und Abstellortregeln

- **Ziel:** Für passende Genehmigungsmodelle ist eine Rückkehr- bzw. Abstellortregel aktivierbar; nach Fahrabschluss wird die vorgeschriebene Rückkehrfahrt geplant, sofern keine Ausnahme greift.
- **Anforderungen:** `LH-6.5-OPT-018`
- **Liefergegenstände:**
  - Backend: Regel je Dienst (aktiv/inaktiv, Rückkehrziel = Betriebssitz/Abstellort, Ausnahmen laut Detailvertrag, z. B. bereits vorliegender Folgeauftrag); nach dem letzten Ausstieg ohne Folgeauftrag wird ein Rückkehrstopp in den `vehicle_run` eingefügt; ein während der Rückkehr eingehender zulässiger Auftrag ersetzt die Rückkehr gemäß Ausnahmeregel.
  - Datenbank: Regel als versionierte Konfiguration in `config_revision`; `route_stop` (Stopptyp Rückkehr), `vehicle_run`.
  - API: `/v1/services`, `/v1/config/revisions`; `/v1/operator/vehicles` (Rückkehrfahrt sichtbar).
  - Oberfläche: UI-17 (Regel je Dienst aktivieren), UI-07 und UI-10 (Rückkehrfahrt als eigener Schritt mit Grund).
  - Ereignisse/Outbox: Rückkehrfahrt geplant, Rückkehr durch Ausnahme ersetzt.
  - Konfigurationsparameter: Rückkehrregel und Ausnahmen (keine Katalogparameter, Offene Punkte 6).
  - Kennzahlen: `Leerkilometerquote` (Rückkehrfahrten zählen als Leerkilometer; Kennzeichnung für die Auswertung).
- **Detailverträge vorab:** Regelschema mit abschließendem Ausnahmekatalog (Offene Punkte 31); Zuordnung Dienst ↔ Genehmigungsmodell; Abstellortquelle (Depot/Betriebssitz).
- **Akzeptanzkriterien:**
  1. Bei aktivierter Regel und ohne Ausnahme enthält der Plan nach Fahrabschluss eine Rückkehrfahrt zum vorgeschriebenen Ort (OPT-018).
  2. Greift eine konfigurierte Ausnahme, entfällt die Rückkehrfahrt; die Ausnahme ist mit Regelversion protokolliert (OPT-018).
  3. Bei deaktivierter Regel (anderes Genehmigungsmodell) wird keine Rückkehrfahrt erzwungen.
  4. Die Rückkehrfahrt respektiert Schicht-, Pausen- und Depotbedingungen aus WP-M7a-34.
- **Tests:** positiv: Rückkehr geplant, Ausnahme ersetzt Rückkehr. negativ: Regel ohne Rückkehrziel nicht freigebbar, Ausnahme außerhalb des Katalogs. konkurrierend: Fahrabschluss und neuer Auftrag gleichzeitig – deterministisches Ergebnis nach Regel.
- **Abhängigkeiten:** intern: WP-M7a-34.
  - extern: WP-M1-20, WP-M1-21 (Konfigurationsregister)
- **Entscheidungen:** D-01 (Rolle/Genehmigungsmodell des Beförderers bestimmt, für welche Dienste die Regel gilt; blockiert Produktivschaltung, nicht den Bau).
- **Größe:** M
- **Nicht im Umfang:** rechtliche Einordnung der Genehmigungsmodelle; Abstellplatzverwaltung der Betriebshöfe (M7c).

### WP-M7a-36 – Datensparsames Entscheidungsprotokoll der Disposition

- **Ziel:** Für jede Zuweisung und Ablehnung ist der Grund datensparsam gespeichert; ein Disponent kann einen Fall über einen anonymisierten Diagnosebericht auswerten.
- **Anforderungen:** `LH-6.5-OPT-022`
- **Liefergegenstände:**
  - Backend: Protokolleintrag je Dispositionsentscheidung: `attempt_id`, Planrevision, Regel-/Policyversionen, geprüfte Kandidaten als Anzahl und je Ausschlussgrund aggregiert, bindende Nebenbedingung, gewähltes Fahrzeug als Referenz; keine Namen, Kontaktdaten, exakten Adressen oder Unterstützungsdetails (grobe Start-/Zielangabe wie in `travel_attempt`); Diagnosebericht als redigierte Projektion.
  - Datenbank: Entscheidungstabelle (Vorschlag `dispatch_decision`, nicht in Kap. 04 §4.4; Offene Punkte 4), Bezug zu `travel_attempt`, `rejection_reason`; append-only; Löschklasse.
  - API: Diagnosebericht lesen (Pfadvorschlag `GET /v1/operator/decisions/{attempt_id}`; Offene Punkte 5), nur für berechtigte Rollen des eigenen Mandanten.
  - Oberfläche: UI-07: „Warum?“ an Buchung bzw. abgelehnter Anfrage öffnet den Diagnosebericht.
  - Kennzahlen: `Kapazitätsablehnungen`, `Personalmangelablehnungen`, `Ausstattungsablehnungen`, `Gebietsablehnungen`, `Zeitfensterablehnungen` (Primärgrund konsistent zum Protokoll).
- **Detailverträge vorab:** JSON-Schema Entscheidungseintrag; Feldliste „anonymisiert“; Aufbewahrungsfrist; Protokollierungsumfang unter Last (Stichprobe oder vollständig).
- **Akzeptanzkriterien:**
  1. Für eine zugewiesene und eine abgelehnte Testanfrage kann der Disponent den Diagnosebericht mit Grund, geprüften Regeln und Kandidatenübersicht abrufen (OPT-022).
  2. Der Bericht enthält keine direkt identifizierenden Fahrgast- oder Fahrerdaten; ein Feldtest gegen die Whitelist besteht (OPT-022).
  3. Der protokollierte Primärgrund stimmt mit `travel_attempt.primary_rejection` überein.
  4. Einträge sind unveränderlich und für fremde Mandanten nicht lesbar.
  5. Jeder Abruf eines Diagnoseberichts wird mit Akteur und Zweck auditiert; der Bericht enthält Standortangaben nur in der groben Form von `travel_attempt` (`coarse_origin`, `coarse_destination`).
- **Tests:** positiv: Bericht für Zuweisung und Ablehnung. negativ: PII-Scan des Berichts, fremder Mandant, Rolle ohne Recht, Änderungsversuch an einem Eintrag. Bezug E2E-080/E2E-081 (Normgrund der Ablehnung konsistent zur Statistik). Lastbezug: Protokollierung verletzt das Routingbudget nicht (asynchron bzw. in derselben Transaktion laut Detailvertrag).
- **Abhängigkeiten:** intern: WP-M7a-02.
  - extern: WP-M2-11, WP-M2-13, WP-M2-17 (`travel_attempt`, standardisierte Ablehnungsgründe, Einfügeheuristik)
  - extern: WP-M3-36 (Arbeitsliste UI-07)
- **Entscheidungen:** D-11 (Aufbewahrung; blockiert nur Produktivschaltung), D-10 (keine fahrerbezogene Auswertung aus dem Protokoll).
- **Größe:** M
- **Nicht im Umfang:** Ablaufprotokoll automatisierter Workflows (M3/M8); Analyseberichte über Ablehnungen (M8).

### WP-M7a-37 – Native Passenger-App: Grundgerüst

- **Ziel:** Unter `mobile/passenger/` existiert eine lauffähige Flutter-App mit Anmeldung, Mandanten-/Dienstkontext, Push-Registrierung und Build-/Signaturkette; sie nutzt ausschließlich die bestehende Fach-API.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Oberfläche: App-Gerüst (Flutter) mit Navigation, Designsystem-Entsprechungen, Internationalisierung (Deutsch plus vorbereitet), Screenreader-Semantik, Schriftvergrößerung, reduzierte Bewegung.
  - Backend: OIDC mit PKCE über den bestehenden IdP; Geräteregistrierung für Push; keine eigene Geschäftslogik im Client.
  - API: `/v1/me`; Push-Token-Registrierung unter `/v1/notifications` (Pfaddetail im Vertrag).
  - Adapter/Worker: Push-Zustellung über den `notification`-Adapter; CI-Build, Signatur, Verteilung an Testgeräte.
  - Datenbank: Geräte-/Push-Registrierung im Modul `communication-support` (Tabelle aus WP-M7a-02; in Kap. 04 §4.4 nicht genannt, Offene Punkte 34) mit Tenantbezug, Löschklasse und Entfernung bei Abmeldung/Kontolöschung.
- **Detailverträge vorab:** unterstützte Betriebssystemversionen und Geräte (Offene Punkte 1); sichere Tokenablage; Verhalten ohne Netz (nur Anzeige des zuletzt bestätigten Stands, keine Offline-Buchung, Kap. 01 §1.5).
- **Akzeptanzkriterien:**
  1. Anmeldung, Abmeldung und serverseitige Sitzungssperre wirken in der App wie im Web.
  2. Die App enthält keine Secrets und keine mandantenübergreifenden Caches; ein Kontowechsel leert lokale Daten.
  3. Push-Registrierung und Testzustellung funktionieren auf beiden Zielplattformen.
  4. Der Build ist reproduzierbar, signiert und mit SBOM versehen.
  5. Ohne Netz zeigt die App den zuletzt bestätigten Stand mit Zeitangabe; sie erzeugt offline keine Buchung, keine Änderung und kein Storno (Kap. 01 §1.5).
  6. Ein Push an ein abgemeldetes oder einem anderen Konto zugeordnetes Gerät unterbleibt; Push-Inhalte enthalten keine über die Betriebsnachricht hinausgehenden Personendaten.
- **Tests:** positiv: Login, Push-Empfang. negativ: gesperrte Sitzung, abgelaufenes Token, Kontowechsel, Push nach Abmeldung, Bedienung ohne Netz. Sicherheit: kein Klartext-Token im Gerätespeicher; Gerät eines fremden Mandanten erhält keinen Push.
- **Abhängigkeiten:** intern: WP-M7a-01, WP-M7a-02.
  - extern: WP-M2-32 (Nachrichtenversand mit Zustellversuchen (`notification`, `notification_attempt`))
  - extern: WP-M1-04, WP-M1-06, WP-M1-10 (OIDC/PKCE, `/v1/me`, Sitzungssperre)
  - extern: WP-M3-24, WP-M0-06 (Flutter-Erfahrungen und Build-Kette der Driver-App)
- **Entscheidungen:** D-12 (Reihenfolge der Lieferung: native Passenger-App gemäß P2; blockiert nicht den Bau).
- **Größe:** L
- **Nicht im Umfang:** Fachbildschirme (WP-M7a-38, WP-M7a-39); garantierte Hintergrundortung des Fahrgasts (nicht gefordert).

### WP-M7a-38 – Native Passenger-App: Kernreise UI-01 bis UI-05

- **Ziel:** Die Kernreise Suche → Angebot → Checkout → Verfolgen/Storno → Konto ist in der nativen App mit denselben Fachzuständen wie in der PWA nutzbar.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Oberfläche: native Umsetzung von UI-01 (Suche), UI-02 (Angebote/Details), UI-03 (Checkout mit Holdverlust, SCA, Preisänderung, Doppelklickschutz), UI-04 (aktive/alte Reisen mit UNKNOWN-Anzeige), UI-05 (Konto/Berechtigung) einschließlich aller Leere-/Fehler-/Rechtezustände aus Kap. 06 §6.9; Karte immer mit gleichwertiger Liste.
  - API: `/v1/ride-offers`, `/v1/bookings`, `POST /v1/federation/journeys/plan`, `GET /v1/federation/offers/{offer_id}`, `GET /v1/operations/{operation_id}`, `/v1/me` – unverändert gegenüber dem Web.
  - Adapter/Worker: PSP-SDK bzw. PSP-Weiterleitung für SCA in der App laut PSP-Adaptervertrag.
- **Detailverträge vorab:** Zustandskatalog je Bildschirm als gemeinsame Testspezifikation für PWA und App; Deep-Link-Schema (Rückkehr aus SCA, Benachrichtigung → Buchung).
- **Akzeptanzkriterien:**
  1. Eine in der App gebuchte Fahrt ist sofort in der PWA sichtbar und umgekehrt (Grundlage für PAX-014 in WP-M7a-12).
  2. Jeder Zustand aus Kap. 06 §6.9 für UI-01 bis UI-05 ist in der App erreichbar und textlich erklärt.
  3. Nach Verbindungsabbruch im Checkout öffnet die App dieselbe Operation; es entsteht kein zweiter Checkout.
  4. Die Kernreise ist mit Screenreader und vergrößerter Schrift vollständig bedienbar.
- **Tests:** positiv: Kernreise auf beiden Plattformen. negativ: Holdverlust, SCA-Abbruch, Timeout mit „Bestätigung wird geprüft“. konkurrierend (Geld/Kapazität): Doppelauslösung Confirm aus App und PWA mit gleichem Idempotenzschlüssel bzw. gleicher Journey – genau eine Buchung, genau eine Belastung. Barrierefreiheit analog AT-19.
- **Abhängigkeiten:** intern: WP-M7a-37.
  - extern: WP-M2-23, WP-M2-30, WP-M2-29, WP-M2-35, WP-M2-36, WP-M2-37, WP-M2-41, WP-M2-42, WP-M2-43 (Buchungs- und Zahlungs-API, PWA-Zustandskatalog UI-01 bis UI-05, PSP-Adapter)
- **Entscheidungen:** D-03 (PSP-Auswahl bestimmt das App-SDK; blockiert Produktivschaltung der In-App-Zahlung).
- **Größe:** L
- **Nicht im Umfang:** P2-Funktionen dieser Einheit (WP-M7a-39); föderierter Checkout über zwei Betreiber in der App (folgt der Freigabe aus M6).

### WP-M7a-39 – Native Passenger-App: P2-Funktionen dieser Einheit

- **Ziel:** Die in M7a gelieferten Fahrgastfunktionen stehen auch in der nativen App zur Verfügung.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Oberfläche: native Umsetzung von Buchungsänderung, Serienbuchung, Gruppenbuchung, Buchung für Dritte, Boarding-Code/QR, multimodaler Optionsdarstellung, „Erneut buchen“, Bewertung, Live-Position in berechtigter Phase, Teilen des Abholfensters (natives Teilen-/Kalender-Sheet), einfacher Sprache und Hilfetexten – jeweils in UI-01 bis UI-05.
  - API: `/v1/booking-series`, `/v1/delegations`, `/v1/bookings`, `/v1/ride-offers` – unverändert gegenüber dem Web.
- **Detailverträge vorab:** Paritätsliste PWA ↔ App mit begründeten Abweichungen.
- **Akzeptanzkriterien:**
  1. Jede Funktion der Paritätsliste ist in der App mit denselben Fachzuständen wie in der PWA abnehmbar.
  2. Das native Teilen überträgt ausschließlich den in WP-M7a-19 freigegebenen Inhalt.
  3. Die Live-Position endet in der App gleichzeitig mit der serverseitigen Freigabe; kein lokal gecachter Standort wird danach angezeigt.
- **Tests:** positiv: je Funktion ein App-E2E. negativ: Freigabeende der Position, abgelaufene Vertretung, Teilverfügbarkeit einer Serie. AT-06 zusätzlich über die App.
- **Abhängigkeiten:** intern: WP-M7a-38, WP-M7a-03, WP-M7a-09, WP-M7a-10, WP-M7a-11, WP-M7a-14, WP-M7a-15, WP-M7a-16, WP-M7a-17, WP-M7a-18, WP-M7a-19, WP-M7a-20.
- **Entscheidungen:** keine.
- **Größe:** M
- **Nicht im Umfang:** Fahrgastfunktionen aus M7b (Supportfall, Massenkommunikation) – dort einzuplanen.

### WP-M7a-40 – Gate-Nachweis M7a und Regressionssuite

- **Ziel:** Der M7a-Anteil des M7-Gates ist mit Build, Evidenz und Entscheidung nachgewiesen; alle acht MUSS-Anforderungen haben einen grünen Test, SOLL-Abweichungen sind dokumentiert.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Tests: ausführbare Szenarien in `tests/acceptance` für AT-06 und AT-12; Regressionsläufe der berührten P1-Tests (AT-03, AT-04, AT-08, AT-13, AT-19, PH-AT-004, PH-AT-010, PH-AT-017, PH-AT-018) gegen den M7a-Stand; Negativtests mit mindestens zwei parallelen Transaktionen je berührter Invariante aus Kap. 04 §4.5 für neue Schreibpfade (Buchungsänderung, Serie, Gruppenhold, Restriktions-/Zonenaktivierung, Token-Einlösung, Vertretungswiderruf); semantische Contract-Tests nach Kap. 05 §5.8 für neue Pfade; zusätzliche Regression AT-05, AT-07, AT-10, AT-11, E2E-096 (Vorlaufzeit-Änderung in der Zukunft gegen dienstbezogene Buchungsregeln).
  - Oberfläche: UX-Nachweis nach Kap. 08 §8.4: alle in M7a neuen Fahrgast-, Leitstellen- und Pflegeabläufe per Tastatur und Screenreader, ohne Karten- oder Drag-and-drop-Zwang (PWA und native App).
  - Kennzahlen: Lastlauf der Angebotsbildung mit aktiven Restriktionen, Nahhorizont und Entscheidungsprotokoll gegen die p95-Ziele (Kap. 08 §8.6).
  - Dokumentation: Evidenz je Test gemäß Kap. 08 §8.4 (`source_key[]`, Build, Migrationsstand, Konfigurationsrevision, Seed, Rollen/Mandanten, Soll/Ist, Trace-IDs, Ergebnis); Eintrag in `e2e.csv` ausschließlich aus Testläufen; Liste der SOLL-Abweichungen mit Begründung für die AG-Freigabe.
- **Detailverträge vorab:** Testspezifikationen AT-06 und AT-12 mit Fixtures (zwei Mandanten, Feiertags-/Schließtagskalender, letzter Platz).
- **Akzeptanzkriterien:**
  1. AT-06: Termine korrekt, Ausnahmen nachvollziehbar – grün mit Evidenz.
  2. AT-12: zentral konsistente Kapazität und Status bei gleichzeitiger Nachfrage aus Callcenter und App – auf API-Ebene grün mit Evidenz; das Oberflächenszenario mit UI-06 führt die Einheit M7b in ihrem Gate-WP aus, der Eintrag in `e2e.csv` erfolgt einmal und gemeinsam (Offene Punkte 35).
  3. Jede der acht MUSS-Anforderungen (`PAX-010`, `BKG-006`, `BKG-007`, `BKG-008`, `OPT-012`, `OPT-013`, `GEO-004`, `GEO-005`) ist in `arbeitspakete.csv` mit Detailvertrag und Testfall verknüpft.
  4. Kein offener Mangel der Klasse A; berührte P1-Referenztests bleiben grün.
  5. Jede in M7a berührte Invariante aus Kap. 04 §4.5 ist durch einen Negativtest mit mindestens zwei parallelen Transaktionen belegt; reine Schemavalidierung zählt nicht.
  6. Jede der 25 SOLL-Anforderungen ist entweder mit grünem Test belegt oder als Abweichung mit Begründung zur AG-Freigabe gelistet.
- **Tests:** positiv: AT-06, AT-12. negativ/konkurrierend: Regressionssuite wie oben. Evidenz ohne unnötige Fahrgast- oder Personaldaten.
- **Abhängigkeiten:** intern: WP-M7a-01, WP-M7a-02, WP-M7a-03, WP-M7a-04, WP-M7a-05, WP-M7a-06, WP-M7a-07, WP-M7a-08, WP-M7a-09, WP-M7a-10, WP-M7a-11, WP-M7a-12, WP-M7a-13, WP-M7a-14, WP-M7a-15, WP-M7a-16, WP-M7a-17, WP-M7a-18, WP-M7a-19, WP-M7a-20, WP-M7a-21, WP-M7a-22, WP-M7a-23, WP-M7a-24, WP-M7a-25, WP-M7a-26, WP-M7a-27, WP-M7a-28, WP-M7a-29, WP-M7a-30, WP-M7a-31, WP-M7a-32, WP-M7a-33, WP-M7a-34, WP-M7a-35, WP-M7a-36, WP-M7a-37, WP-M7a-38, WP-M7a-39.
  - extern: WP-M7b-35, WP-M7b-18 (Callcenter-Arbeitsplatz UI-06 für den Oberflächenanteil von AT-12)
- **Entscheidungen:** D-05 (Peaklast/Lastprofil für den Lastlauf; blockiert nicht den Bau).
- **Größe:** M
- **Nicht im Umfang:** Gate-Tests der Einheiten M7b, M7c und M7d; Pilot- oder Produktivfreigabe.

## Offene Punkte

1. **Native Passenger-App ohne eigene Anforderung:** Der Umsetzungsplan nennt die native Passenger-App als M7-Inhalt, Kap. 01 §1.1 nennt „native Passenger App P2“ (Flutter). In den 33 Anforderungen wird sie nur in `LH-6.3-PAX-014` erwähnt. Zielplattformen, Mindest-Betriebssystemversionen, Funktionsparität zur PWA und Store-Prozess sind nicht spezifiziert. WP-M7a-37 bis WP-M7a-39 sind deshalb Enabler; Umfang ist mit dem Auftraggeber zu bestätigen (D-12).
2. **AT-12 braucht M7b:** „Callcenter kontra App“ ist Gate-Test des M7, der Callcenter-Arbeitsplatz UI-06 mit Vertretungsprüfung gehört aber zu den SUP-Anforderungen der Einheit M7b. M7a kann AT-12 nur auf API-Ebene vollständig bestehen; der Oberflächenanteil hängt von M7b ab.
3. **M7a hängt entgegen dem Meilensteinplan von M6 ab:** Laut Umsetzungsplan sind M6, M7 und M8 unabhängige P2-Ströme. `LH-6.5-OPT-012` (Parallelfahrtenverbot), `LH-6.5-OPT-013` (Anschlussprioritäten), `LH-6.9-GEO-012` (Knotenzeiten) und `LH-6.3-PAX-010` (multimodale Option) setzen jedoch Fahrplanadapter, ÖPNV-Alternativen, Parallelfahrtenregel (`LH-6.11-PTI-004`) und `transfer_hub`/`transfer_rule` aus M6a voraus. Zudem überschneiden sich OPT-012 und PTI-004 sowie OPT-013 und die intermodale Anschlussplanung (`LH-6.11-PTI-003`) fachlich; die Abgrenzung (M6a = Vergleichskriterien und Auskunft, M7a = Durchsetzung in der Disposition mit Ausnahmen bzw. Bahnhofspriorität) ist eine Planungsannahme und im Detailvertrag zu bestätigen. AT-13 (Parallelfahrtenverbot) steht im Gate M2, obwohl beide zugehörigen Anforderungen P2 sind.
4. **Fehlende Tabellen in Kap. 04 §4.4:** Für folgende Objekte nennt das Pflichtenheft keine Tabelle: Sperrbereiche/Streckenrestriktionen (`LH-6.9-GEO-004`, `LH-6.9-GEO-005`, `LH-6.9-GEO-006`), Geodatenimport (`LH-6.9-GEO-013`), Fahrgastfeedback (`LH-6.3-PAX-012`), Freigabe-Token für das Abholfenster (`LH-6.3-PAX-015`), Buchungscode/QR-Token (`LH-6.4-BKG-019`), Entscheidungsprotokoll (`LH-6.5-OPT-022`), Depot/Abstellort (`LH-6.5-OPT-017`, `LH-6.5-OPT-018`). Die in den WPs genannten Namen (`geo_restriction`, `geo_import`, `passenger_feedback`, `dispatch_decision`) sind Vorschläge.
5. **Fehlende Endpunkte in Kap. 05 §5.7 und `openapi.yaml`:** Für Restriktionen, Geodatenimport, Feedback, Teilen des Abholfensters, Fahrzeugposition für Fahrgäste, Buchungsänderung, No-Show-Entscheidung der Leitstelle und Diagnosebericht nennt der Katalog keine Pfade. `openapi.yaml` formuliert von den hier benötigten Pfaden nur `/v1/driver/events`, `/v1/federation/journeys/plan`, `/v1/federation/offers/{offer_id}`, `/v1/operations/{operation_id}` und `/v1/config/revisions/{revision_id}/publish` aus; `/v1/bookings`, `/v1/ride-offers`, `/v1/booking-series`, `/v1/delegations`, `/v1/stops`, `/v1/service-areas`, `/v1/transfer-hubs`, `/v1/runs/{id}/revisions` stehen nur im Katalog. Alle Pfadangaben mit „Pfadvorschlag“ sind zu bestätigen.
6. **Fehlende Konfigurationsparameter in `kataloge.csv`:** Kein Katalogparameter existiert für: Bestätigungsfrist zeitkritischer Angebote (`LH-6.4-BKG-018`), Serien-Vorbuchungsgrenze und Fehlerregel (`LH-6.4-BKG-008`), Kanalfreigabe je Dienst (`LH-6.4-BKG-011`), Schwelle „planrelevante Verzögerung“ (`LH-6.5-OPT-014`), Rückkehrregel und Ausnahmen (`LH-6.5-OPT-018`), Parallelfahrt-Ausnahmen (`LH-6.5-OPT-012`), Gültigkeit von Freigabe- und Check-in-Token (`LH-6.3-PAX-015`, `LH-6.4-BKG-019`), „Stabilitätsschutz“ über `protected_stop_horizon_min` und `max_reassignments` hinaus (`LH-6.5-OPT-016`). Zu klären, ob der Katalog erweitert wird oder die Werte als Dienst-Policy geführt werden.
7. **Summenabweichung im Meilensteinkopf:** Der Umsetzungsplan nennt für den Strom „Fahrgast und Leitstelle“ 63 Anforderungen; M7a enthält 33, M7b 30 (COM 9, SUP 10, OPS 11) – konsistent. Die Modulzahlen (BKG 10, OPT 8, GEO 8, PAX 7) stimmen mit der Eingabe überein; kein Handlungsbedarf, nur zur Kontrolle vermerkt.
8. **Feiertags- und Schließtagskalender (`LH-6.4-BKG-007`, AT-06):** AT-06 verlangt Serien „inkl. Feiertag oder Schließtag“. Die Anforderung nennt nur „Ausnahmedaten“. Offen ist, ob Feiertage/Schließtage automatisch aus einem Kalender (Betriebszeiten des Dienstes; Feiertagslogik nach Ort gehört zu `LH-6.23-SCH-019` in M7d) oder nur manuell als Ausnahme wirken, und ob ein solcher Termin als `SKIPPED` oder `FAILED` gilt.
9. **Zahlung bei Serien (`LH-6.4-BKG-007`, `LH-6.4-BKG-008`):** Nicht spezifiziert ist, ob je Termin autorisiert/erfasst wird, wann, und was bei Zahlungsfehler eines späteren Termins geschieht. Kap. 02 §7.2 verbietet, eine Autorisierung bis zum Reisetag als garantiert anzunehmen; die Regel hängt an D-03.
10. **Regel für fehlgeschlagene Einzeltermine (`LH-6.4-BKG-008`):** Das Lastenheft nennt die Regel, aber nicht ihre zulässigen Ausprägungen (fortsetzen mit Lücken, ganze Serie verwerfen, Alternativzeit anbieten, Nachfrage beim Fahrgast). Ebenso fehlt die Definition der „Vorbuchungsgrenze“ (Tage vor Termin, Anzahl Termine oder Kalendergrenze) und ihr Verhältnis zu `max_booking_horizon_days`.
11. **Gruppen größer als ein Fahrzeug (`LH-6.4-BKG-009`):** „Vollständig pro Fahrzeug geprüft“ lässt offen, ob eine Gruppe auf mehrere Fahrzeuge aufgeteilt werden darf. Planungsannahme: keine Aufteilung, sondern Ablehnung mit Grund. Maximale Gruppengröße und „individuell relevante Kapazitätsanforderungen“ (z. B. Kindersitz, Gepäck) sind nicht katalogisiert.
12. **„Vorlaufzeit“ in `LH-6.4-BKG-016`:** Unklar, ob die Zeit zwischen Buchung und Abholung insgesamt gemeint ist oder ein konfigurierter Zeitraum vor der Abholung. Kein Zustell-Latenzziel angegeben; als Messgröße wird die Fahrerplan-Latenz aus Kap. 08 §8.6 vorgeschlagen.
13. **„Mehrere Buchungslösungen … oder Kanaloberflächen“ (`LH-6.4-BKG-011`):** Die Abnahme prüft nur unterschiedliche Bestellvorläufe. Ob eigenständige Oberflächen/Markenauftritte je Verkehrsprodukt gefordert sind, ist offen; geplant ist nur die konfigurative Unterscheidung.
14. **„Zeitkritische Angebote“ (`LH-6.4-BKG-018`):** Nicht definiert, welche Angebote zeitkritisch sind und wie sich die Bestätigungsfrist zur Hold-Dauer (Planungsdefault 180 s) und zu `partner_offer_ttl_sec` verhält.
15. **Fahrgast ohne eigenes Konto (`LH-6.4-BKG-010`):** Kap. 05 §5.9 verlangt für `DELEGATED` einen verifizierten Fahrgast und eine gültige Vertretung. Ob eine Buchung für eine dritte Person ohne eigenes Konto (z. B. Angehörige, Besucher) zulässig ist und welche Mindestdaten dann erfasst werden, ist nicht festgelegt.
16. **Offline-Prüfung des Buchungscodes (`LH-6.4-BKG-019`):** Kap. 01 §1.5 lässt Offline-Erfassung zu, aber keine neue verbindliche Wirkung ohne Serverprüfung. Unklar, ob ein Check-in-Code offline gegen vorab synchronisierte Daten geprüft werden darf. Tokenlänge, Gültigkeitsdauer und Fehlversuchsgrenze sind nicht vorgegeben. Abgrenzung zum Check-in mit Codeprüfung der Driver-App aus M3 (`LH-6.6-DRV-006`) ist zu bestätigen.
17. **Feedbackkategorien und Skala (`LH-6.3-PAX-012`):** Kategorien, Bewertungsskala, Änderungsfrist und die Frage, welche Betriebsrollen Feedback fahrtbezogen (und damit mittelbar fahrerbezogen) sehen dürfen, fehlen. Letzteres berührt D-10.
18. **„Berechtigte Phase“ (`LH-6.3-PAX-013`):** Beginn der Positionsfreigabe ist nicht definiert (ab Zuweisung, ab x Minuten vor Abholfenster, ab Anfahrt). Die Abnahme legt nur das Ende (Fahrtende) fest. Unklar auch, ob die Position während der Fahrt (an Bord) gezeigt wird.
19. **„Push-/SMS-/Kalenderinformation weitergeben“ (`LH-6.3-PAX-015`):** Push setzt einen registrierten Empfänger voraus; ob an andere TAKTOVIA-Nutzer gepusht wird oder nur an eigene Geräte, ist offen. „Unzulässige persönliche Daten“ ist nicht als Feldliste definiert; geplant ist eine Whitelist.
20. **Einfache Sprache (`LH-6.3-PAX-016`):** Kein Regelwerk oder Prüfverfahren benannt (z. B. redaktionelle Prüfung, Nutzertest). Die Abnahme prüft nur die Fehlermeldung der Suche.
21. **Wirkung neuer Restriktionen auf Bestandsbuchungen (`LH-6.9-GEO-004`, `LH-6.9-GEO-006`, `LH-6.9-GEO-011`):** Die Abnahme von GEO-004 spricht nur von „neuer Planung“. Offen ist, ob bereits zugesagte Fahrten bei neu aktivem Sperrbereich, geänderter Zone oder verkürztem Gültigkeitsfenster umgeplant, gewarnt oder storniert werden. Planungsannahme: Zusage bleibt, Leitstelle erhält einen Hinweis.
22. **Schwellen für Barrierefreiheit (`LH-6.9-GEO-007`):** Einheit und Grenzwert der Steigung, Kategorien der Querung und die Zuordnung zu Mobilitätsprofilen fehlen. Mobilitätsprofile selbst sind im Pflichtenheft nicht katalogisiert.
23. **Schemakonflikt `stop_revision.approved_by` (`LH-6.9-GEO-008`):** Im Kern-DDL ist `approved_by` `NOT NULL`; ein Entwurf oder ein Punkt in Prüfung ist damit nicht speicherbar. Zudem kennt `stop_revision` weder Typ (virtuell/physisch/temporär) noch Freigabestatus. Entweder eigene Entwurfstabelle oder Schemaänderung; Rückwirkung auf M2 prüfen.
24. **Sicherheitskriterien virtueller Haltepunkte (`LH-6.9-GEO-008`):** Der Kriterienkatalog der redaktionellen Sicherheitsprüfung und die freigabeberechtigte Rolle sind nicht benannt.
25. **Importformate (`LH-6.9-GEO-013`):** Unterstützte Formate (GeoJSON, Shapefile, GPKG, CSV), zulässige Quellkoordinatensysteme, Dublettenschwelle und Größenlimits sind nicht angegeben.
26. **Plattformbezug in `transfer_rule` (`LH-6.9-GEO-012`):** Kap. 04 §4.4 beschreibt `transfer_rule` mit „Stoppaare + Gehweg/Puffer“; ein Plattform- oder Verbindungsbezug fehlt. Offen ist, ob Plattformen als eigene Stopps oder als Attribut modelliert werden und woher die Daten stammen.
27. **Ausnahmen vom Parallelfahrtenverbot (`LH-6.5-OPT-012`):** „Definierte Ausnahmen“ und „relevante Linienalternativen“ sind inhaltlich nicht festgelegt; die Testrelationen der Abnahme fehlen.
28. **Abgrenzung ETA (`LH-6.5-OPT-014`) zu den ETA-Anforderungen in M8:** „Fahrzeitunsicherheit“ wird hier mit dem Quantil-Parameter `eta_model_quantile` umgesetzt; die lernenden Modelle folgen in M8. Die Schwelle „planrelevante Verzögerung“ ist nicht definiert.
29. **„Stabilitätsschutz“ (`LH-6.5-OPT-016`):** Neben dem Nahhorizont ist nicht beschrieben, was Stabilitätsschutz umfasst (z. B. Begrenzung von ETA-Änderungen, Fahrzeugwechseln, Reihenfolgeänderungen). Planungsannahme: `max_reassignments` plus Fixierung im Horizont.
30. **Depotstammdaten (`LH-6.5-OPT-017`):** Depots/Betriebshöfe gehören zu den AST-Anforderungen (M7c); `staff_shift` kennt im Kern-DDL weder Depot noch Pausenplan. Der Leseport und die liefernde Einheit sind festzulegen; „fahrzeugabhängige Erreichbarkeit“ ist gegenüber `LH-6.9-GEO-005` nicht abgegrenzt.
31. **Rückkehrregel (`LH-6.5-OPT-018`):** „Passende Genehmigungsmodelle“ und der Ausnahmekatalog sind nicht definiert; die rechtliche Zuordnung je Dienst liegt beim Auftraggeber (D-01).
32. **No-Show: „erfasste Anwesenheit“ (`LH-6.4-BKG-015`):** Nachweisform (Fahrerereignis allein oder mit Positionsplausibilität), Radius und zulässiges Standortalter fehlen. AT-08 (Fahrer No-Show) liegt im Gate M3, die standardisierte Regel ist P2; die M3-Umsetzung muss durch dieses WP ohne Bruch erweitert werden.
33. **Kanalübergreifende Sichtbarkeit (`LH-6.3-PAX-014`):** „Berechtigter Zugriff“ ist nicht als Rollen-/Feldmatrix ausgeführt (z. B. ob ein Fahrgast sieht, welcher Callcenter-Bediener gebucht hat).
34. **Geräte-/Push-Registrierung der nativen App:** Kap. 04 §4.4 nennt für Kommunikation nur `notification`, `notification_attempt` und `channel_template_revision`; eine Tabelle für registrierte Fahrgastgeräte/Push-Token und ein Pfad zur Registrierung fehlen in Kap. 04 und Kap. 05 §5.7. Zu klären ist auch, ob die Driver-App aus M3 bereits eine wiederverwendbare Geräteregistrierung liefert (betrifft `LH-6.3-PAX-014`, WP-M7a-02, WP-M7a-37).
35. **AT-12 und `delegated_access` doppelt bzw. widersprüchlich verortet:** Die Einheit M7b führt AT-12 ebenfalls als ausführbares Szenario in ihrem Gate-WP; hier gilt: M7a weist die API-Ebene nach, M7b die Oberfläche mit UI-06, ein gemeinsamer Evidenzeintrag. Für `delegated_access` nennt M7b als Herkunft „M1/M8e“, M8e dagegen „EXTERN[M7a]“; nach diesem Plan liefert M7a die Tabelle (WP-M7a-02) und `/v1/delegations` (WP-M7a-11, `LH-6.4-BKG-010`). Zwischen den Einheiten zu bestätigen.
36. **Erwartungen anderer Einheiten, die M7a nicht erfüllt:** M9b nennt als Voraussetzung aus M7a „Fußwegrouting und Restriktionen“; ein Fußwegrouting ist in keiner der 33 Anforderungen enthalten und in WP-M7a-24 ausdrücklich nicht im Umfang (`LH-6.9-GEO-007` verlangt nur Eigenschaften und Ausschluss). M2 erwartet für AT-13 die Parallelfahrtenregel aus `LH-6.5-OPT-012`; ohne D-12-Beschluss entsteht sie erst hier in WP-M7a-30.
37. **Pflegeansicht für dienstbezogene Parameter:** Kap. 06 §6.9 kennt UI-17 „Gebiete/Stops/Tarife“, aber keine eigene Ansicht für Buchungs-, No-Show-, Serien- und Dispositionsparameter je Dienst (`LH-6.4-BKG-008`, `LH-6.4-BKG-011`, `LH-6.4-BKG-015`, `LH-6.4-BKG-018`, `LH-6.5-OPT-016`, `LH-6.5-OPT-018`). Planungsannahme: Pflege in UI-17 über das Konfigurationsregister; die Zuordnung ist im Bildschirmvertrag zu bestätigen.
38. **Änderungsfrist und Änderbarkeit nach Disposition (`LH-6.4-BKG-006`):** Bis wann und in welchem Fahrtzustand eine Buchung noch geändert werden darf, welche Gebühr dabei anfällt und ob eine Änderung den Stabilitätsschutz (`LH-6.5-OPT-016`) verletzen darf, ist nicht festgelegt.
39. **Testdaten für die Abnahmen:** Die „Testrelationen“ (`LH-6.5-OPT-012`), der „Test-Routenverlauf“ (`LH-6.9-GEO-005`), das „Testbeispiel“ (`LH-6.3-PAX-010`) und der „konfigurierte Mindestpuffer“ (`LH-6.5-OPT-013`) sind im Pflichtenheft nicht als konkrete Daten vorgegeben; sie werden als Fixtures in WP-M7a-01 definiert und müssen vom Auftraggeber als abnahmerelevant bestätigt werden.
40. **Aufgelöst: Zyklus WP-M7a-07 ↔ WP-M7d-11 (Feiertags-/Schließtagskalender):** WP-M7a-07 zeigt auf WP-M7d-11, während WP-M7d-11 seinerseits eine Abstimmung mit M7a als Abhängigkeit führte. Festgelegt: WP-M7d-11 liefert Kalender und Leseport ohne Vorbedarf aus M7a, WP-M7a-07 konsumiert; die Rückrichtung ist in M7d entfallen (M7d, Offene Punkte 22 und 34). Die fachliche Frage, ob Feiertage/Schließtage automatisch oder nur als manuelle Ausnahme wirken, bleibt Offene Punkte 8.
