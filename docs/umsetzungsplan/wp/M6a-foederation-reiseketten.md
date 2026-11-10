# M6a – Föderation und Reiseketten

- **Meilenstein:** M6 – Föderation, gemeinsamer Checkout, Clearing
- **Phase:** P2 (Originalphase und -priorität aller Anforderungen unverändert: 29 MUSS, 11 SOLL)
- **Anforderungen:** 40 (FED 29, PTI 11)
- **Arbeitspakete:** 34 (27 mit Anforderungen, 7 Enabler)

**Ziel:** Nach M6a kann ein Vertriebsmandant Angebote mehrerer Plattformmandanten und angebundener ÖPNV-Partner zu einer Reisekette kombinieren, diese über eine persistente Saga halten, bestätigen, stornieren und nach Timeouts abgleichen, ohne dass ein Betreiber die Kapazitätshoheit über seine Fahrzeuge verliert. Fahrplan- und Echtzeitdaten erscheinen als klar gekennzeichnete Informationssegmente; Anschlüsse werden überwacht und Risiken sichtbar gemacht. Jede Partnerfähigkeit bleibt in der Capability Registry deaktiviert, bis ihr Sandbox-Nachweis vorliegt.

**Hinweise zum Zuschnitt:**

- M6a liefert Planung, Orchestrierung, Adapter und Anschlussüberwachung. Vertrags-/Grant-Lebenszyklus, Projektionen und allgemeine Integrationsbausteine liegen in M6b (MUL, INT), Mehrparteien-Checkout, Erstattung und Settlement in M6c (PAY, I.9/COM). Abhängigkeiten dorthin sind über die benötigte Fähigkeit beschrieben; wo die Nachbardatei ein konkretes Paket nennt, steht dessen WP-ID zur Orientierung in Klammern.
- **Schnittlinien zu den Nachbareinheiten (Abgleich mit `M6b-kooperation-integration.md` und `M6c-checkout-clearing.md`, zu bestätigen, siehe Offene Punkte 20):** (a) Die Tabellen `transfer_hub`, `transfer_rule`, `timetable_feed`, `realtime_observation` werden genau einmal migriert, nach M6b OP-03 in WP-M6b-02; M6a liefert die fachlichen Spaltenanforderungen und migriert selbst `adapter_capability` sowie die Föderationserweiterungen. (b) Feedabruf, Feedkonfiguration und Feedisolation von GTFS/GTFS-RT liefert WP-M6b-27; M6a liefert die fachliche Verarbeitung für die Reiseauskunft (kanonisches Fahrplanmodell, Herkunftskennzeichnung, Versions-/Reihenfolgeregel, Darstellung) und die PTI-/FED-Abnahmen. (c) Stammdatenpflege und API `/v1/transfer-hubs` liefern WP-M6b-10/WP-M6b-11; M6a liefert die profilabhängige Umsteigezeitberechnung und den Reisegraphen. (d) Projektionsbaustein, Partner-Fahrgastalias und Kontaktrelay liefert WP-M6b-09; M6a liefert Föderationsreferenzen, den Transport des Payloads über Adapter/Saga und die FED-Abnahmen. (e) Vertrags-/Grant-Lebenszyklus, Grant-Gate-Port, Partnerzugänge und UI-18 liefert M6b (WP-M6b-04 bis WP-M6b-08, WP-M6b-26); M6a ruft das Gate auf. (f) Aggregat Tarifberechtigung und UI-05-Hinterlegung liefert WP-M6c-11; M6a liefert den externen Ticketprüf-Adapter. (g) Exportprofil- und Exportlaufmechanik liefert WP-M6b-31; M6a liefert Datenkategorien, Freigabe und Fristnachweis für PTI-011.
- Alle Zeit- und Budgetwerte aus Kap. 02 (Hold 180 s, 15 s Commit-Restbudget, 1.500 ms/3.000 ms/5.000 ms Planungsbudget, Backoff 1/2/4/8 s, Leitstellenalarm nach 60 s) und Kap. 05 (Signaturfenster ±300 s, Outbound-Retry 1 s/5 s/30 s/2 min/10 min) sind **Planungsdefaults des Pflichtenhefts**, keine gemessenen Werte.
- Ohne Beschluss zu D-01, D-03, D-04 und D-08 wird mit dem jeweiligen Favoriten aus Kap. 07 in der Sandbox gebaut und abgenommen, aber nicht produktiv geschaltet (Umsetzungsplan §5).

## Übersicht

| WP-ID | Titel | Anforderungen (Anzahl) | Größe | hängt ab von |
|---|---|---:|---|---|
| WP-M6a-01 | Detailverträge Föderation und ÖPNV-Integration | – (Enabler) | M | WP-M0-13, WP-M0-31, WP-M5-31, WP-M6b-01, WP-M6c-01 |
| WP-M6a-02 | Schema-Migration Föderation, Hubs, Fahrplan, Capability | – (Enabler) | M | WP-M6a-01, WP-M0-15, WP-M0-18, WP-M1-01, WP-M1-09, WP-M2-03, WP-M2-04, WP-M2-05, WP-M2-09, WP-M2-22, WP-M2-23, WP-M6b-02 |
| WP-M6a-03 | Sandbox-Partner, Testfeeds und Konformitäts-Harness | – (Enabler) | M | WP-M6a-01, WP-M0-23, WP-M0-24 |
| WP-M6a-04 | Vertragsgate und Kooperationsfilter | 2 | M | WP-M6a-02, WP-M1-19, WP-M1-20, WP-M1-46, WP-M6b-04, WP-M6b-05, WP-M6b-06, WP-M6b-07, WP-M6b-08 |
| WP-M6a-05 | Capability Registry und versionierte Adapterschnittstelle | 2 | M | WP-M6a-02 |
| WP-M6a-06 | Partnervertrag `/v1/partner/*` auf Betreiberseite | 2 | L | WP-M6a-01, WP-M6a-04, WP-M6a-12, WP-M1-02, WP-M1-04, WP-M1-13, WP-M1-14, WP-M2-09, WP-M2-22, WP-M2-23, WP-M2-24, WP-M2-26, WP-M6b-23, WP-M6b-24, WP-M6b-26 |
| WP-M6a-07 | Interner TAKTOVIA-Adapter und Lebenszyklus-Vertragstest | 1 | M | WP-M6a-03, WP-M6a-05, WP-M6a-06 |
| WP-M6a-08 | Transfer-Hubs und Mindestumsteigezeiten | 3 | M | WP-M6a-02, WP-M1-19, WP-M1-20, WP-M1-22, WP-M2-03, WP-M2-04, WP-M2-05, WP-M6b-02, WP-M6b-10, WP-M6b-11 |
| WP-M6a-09 | Föderierte Reiseauskunft mit Teilverfügbarkeit | 2 | L | WP-M6a-03, WP-M6a-04, WP-M6a-05, WP-M6a-07, WP-M6a-08, WP-M1-02, WP-M2-07, WP-M2-11, WP-M2-21, WP-M2-37, WP-M6b-06, WP-M6b-11, WP-M7a-11 |
| WP-M6a-10 | Nachvollziehbares Ranking und Gebietsüberlappung | 1 | M | WP-M6a-09, WP-M1-20, WP-M1-22, WP-M6b-11 |
| WP-M6a-11 | Angebotsdarstellung mit Betreiberausweis und Buchungsfähigkeit | 2 | M | WP-M6a-09, WP-M6a-10, WP-M2-35, WP-M2-36, WP-M2-37, WP-M2-41, WP-M2-42, WP-M2-43, WP-M6c-08 |
| WP-M6a-12 | Pseudonyme Föderationsreferenzen und minimierter Betreiberpayload | 2 | M | WP-M6a-02, WP-M1-31, WP-M1-46, WP-M6b-05, WP-M6b-09 |
| WP-M6a-13 | Saga Teil 1: Hold und Reisekette mit Betreiberreferenzen | 2 | L | WP-M6a-04, WP-M6a-07, WP-M6a-09, WP-M6a-12, WP-M1-02, WP-M1-13, WP-M1-14, WP-M1-15, WP-M2-42, WP-M2-43, WP-M7a-11 |
| WP-M6a-14 | Saga Teil 2: Confirm, Kompensation und Fehlerzustände | 1 | L | WP-M6a-13, WP-M2-29, WP-M2-30, WP-M2-32, WP-M2-34, WP-M6c-07, WP-M6c-14 |
| WP-M6a-15 | Reconciliation-Worker, Wiederanlauf und Leitstellenalarm | – (Enabler) | M | WP-M6a-14, WP-M1-14, WP-M1-15, WP-M3-52 |
| WP-M6a-16 | UI-08 Reise-/Saga-Klärarbeitsplatz | – (Enabler) | M | WP-M6a-15, WP-M1-07, WP-M1-08, WP-M1-43, WP-M1-46, WP-M3-36 |
| WP-M6a-17 | Signierte, idempotente Partnerereignisse | 1 | M | WP-M6a-13, WP-M1-12, WP-M1-14, WP-M1-16, WP-M6b-26 |
| WP-M6a-18 | Globaler Reisestatus und Ausführungsstatus | 1 | M | WP-M6a-14, WP-M6a-17, WP-M3-29 |
| WP-M6a-19 | Anschlussüberwachung, Warnung und Ersatzangebot | 2 | M | WP-M6a-08, WP-M6a-09, WP-M6a-18, WP-M6a-27, WP-M2-32, WP-M2-34, WP-M3-34, WP-M3-36, WP-M3-54, WP-M4-61, WP-M4-62, WP-M6b-14, WP-M6b-27 |
| WP-M6a-20 | Konfigurierbare Anschlussgarantie | 1 | M | WP-M6a-04, WP-M6a-19, WP-M1-19, WP-M1-20, WP-M1-22 |
| WP-M6a-21 | Übergabeprojektion für Partnerleitstellen | 1 | M | WP-M6a-12, WP-M6a-18, WP-M3-36, WP-M6b-05, WP-M6b-09, WP-M6b-13 |
| WP-M6a-22 | Storno, Teilstorno und teilweise gefahrene Reisen | 1 | L | WP-M6a-14, WP-M6a-15, WP-M6a-17, WP-M2-26, WP-M6c-14, WP-M6c-16, WP-M6c-20 |
| WP-M6a-23 | Segmentbezogene Preisaufstellung mit Tarifgeber | 1 | M | WP-M6a-09, WP-M2-07, WP-M2-09, WP-M6c-06, WP-M6c-09, WP-M6c-10, WP-M6c-16 |
| WP-M6a-24 | Prüfung externer Ticketberechtigungen | 1 | M | WP-M6a-05, WP-M6a-23, WP-M2-36, WP-M6c-11 |
| WP-M6a-25 | Ausführungsnachweis und Abrechnungsbezug je Segment | 1 | M | WP-M6a-18, WP-M4-33, WP-M6c-19, WP-M6c-23, WP-M6c-30 |
| WP-M6a-26 | Fahrplanauskunftsadapter (GTFS statisch) mit Herkunftskennzeichnung | 1 | M | WP-M6a-02, WP-M6a-03, WP-M6a-05, WP-M2-05, WP-M6b-27 |
| WP-M6a-27 | Echtzeitverarbeitung (GTFS-RT) versioniert, unbekannt statt pünktlich | 3 | M | WP-M6a-26, WP-M6b-27 |
| WP-M6a-28 | ÖPNV-Alternativen und intermodale Anschlussplanung | 2 | M | WP-M6a-08, WP-M6a-09, WP-M6a-26, WP-M2-18, WP-M2-19, WP-M2-21, WP-M2-37, WP-M2-41 |
| WP-M6a-29 | Parallelfahrtenregel | 1 | M | WP-M6a-28, WP-M1-19, WP-M1-20, WP-M1-22, WP-M1-23 |
| WP-M6a-30 | Eignungsprüfung TOMP und OSDM (Architekturprotokoll) | 1 | S | WP-M6a-05 |
| WP-M6a-31 | TOMP-/OSDM-Adapter für buchbare Pilotpartner | – (Enabler) | L | WP-M6a-05, WP-M6a-07, WP-M6a-30 |
| WP-M6a-32 | Partner-Sandbox-Modus und Freischaltung | 1 | M | WP-M6a-03, WP-M6a-05, WP-M6a-14, WP-M6a-19, WP-M6a-22, WP-M6b-08, WP-M6b-26, WP-M6c-14 |
| WP-M6a-33 | Exportadapter für Mobilitätsdatenpflichten | 1 | M | WP-M6a-26, WP-M1-07, WP-M1-11, WP-M1-46, WP-M6b-31, WP-M8c-30 |
| WP-M6a-34 | Gate-Nachweis M6a und semantische Contract-Fälle | – (Enabler) | M | WP-M6a-01 bis WP-M6a-33, WP-M5-02, WP-M5-22, WP-M6b-05, WP-M6b-07, WP-M6b-34, WP-M6c-07, WP-M6c-19, WP-M6c-23, WP-M6c-32 |

## Arbeitspakete

### WP-M6a-01 – Detailverträge Föderation und ÖPNV-Integration

- **Ziel:** Vor Codebeginn liegen für alle 40 Anforderungen Verantwortlicher, Detailvertrag und mindestens ein positiver ausführbarer Test vor (Kap. 08 §8.4, Leitlinie 7 des Umsetzungsplans).
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - API: OpenAPI-3.1-Ergänzung für die in Kap. 05 §5.7 nur als Funktionszuschnitt benannten Pfade `/v1/partner/capabilities`, `/v1/partner/offers`, `/v1/partner/reservations`, `/v1/partner/reservations/{id}/confirm`, `/v1/partner/reservations/{id}/cancel`, `/v1/partner/reservations/{id}/status` sowie `/v1/transfer-hubs`; Überprüfung der sieben vorhandenen Föderationspfade der `openapi.yaml` (`/v1/federation/journeys/plan`, `/v1/federation/offers/{offer_id}`, `/v1/federation/offers/{offer_id}/hold`, `/v1/federation/journeys/{journey_id}`, `/v1/federation/journeys/{journey_id}/confirm`, `/v1/federation/journeys/{journey_id}/cancellation-quotes`, `/v1/federation/journeys/{journey_id}/cancel`) und von `/v1/federation/partners/{partner_id}/events` auf Lücken. Zu schließende, bereits bekannte Lücken: Ausführungsereignisse des Partners in `PartnerEvent` (siehe Offene Punkte 22), Klärfall-Endpunkte für UI-08 (Offene Punkte 7), Angebotsfelder (Offene Punkte 6), Abgleich `PARTNER_UNAVAILABLE`/`PARTNER_TIMEOUT` (Offene Punkte 21).
  - Backend: Schnittstellenprotokoll M6a↔M6b↔M6c zu den Schnittlinien (a) bis (g) aus dem Kopf dieser Datei mit benannter Zuständigkeit je Tabelle, Pfad, Adapter und Port (Grant-Gate-Port, Anbieterfilter-Port, Gebietsregel-Port, Projektionsbaustein, Prüfadapter-Schnittstelle).
  - Backend: Zustandsautomaten als Übergangstabellen für die Dimensionen aus Kap. 02 §7.3 (Journey-Buchung, Journey-Ausführung, Segment-Buchung, Segment-Ausführung, Operation) einschließlich erlaubter Auslöser und des Fehlervertrags §7.4.
  - Datenbank: DDL-Entwurf für `transfer_hub`, `transfer_rule`, `timetable_feed`, `realtime_observation`, `adapter_capability` (Kap. 04 §4.4) und für die Erweiterungen in WP-M6a-02.
  - Konfigurationsparameter: JSON-Schemas (Typ, Einheit, Min/Max, Ebene) für `partner_offer_ttl_sec`, `partner_api_timeout_ms`, `partner_retry_policy`, `interchange_min_time_min`, `interoperator_bookings`, `partner_liability_policy`, `partner_transport_modes`, `area_overlapping_priority`, `transfer_min_time_min`, `advance_arrival_buffer_min`, `stop_walk_max_m`.
  - Kennzahlen: Ereignisbindung für `Technische Partnerablehnungen`, `Nicht erreichte Anschlüsse`, `Partnerannahmequote`, `Partnerübergabe pünktlich`, `Ende-zu-Ende-Reiseabschluss`.
- **Detailverträge vorab:** entfällt; dieses WP erstellt sie. Zusätzlich: JSON-Schema des minimalen Betreiberpayloads und der Übergabeprojektion (Kap. 02 §3), JSON-Schema der Capability-Matrix (Kap. 02 §9), Katalog der Ranking-Gründe, Schema der Parallelfahrtenregel, Protokollvorlage für FED-021.
- **Akzeptanzkriterien:**
  1. Die ergänzte OpenAPI-Datei ist als OpenAPI 3.1 parsbar, alle `$ref` sind auflösbar, `operationId`s eindeutig, Beispiele schemagültig (Kap. 05 §5.8); `validate_artifacts.py` läuft grün.
  2. Jede der 40 Anforderungen ist in `arbeitspakete.csv` mit Detailvertrag und Testfall verknüpft.
  3. Jede Zustandsdimension hat eine vollständige Übergangstabelle; kein Zustand ohne eingehenden oder dokumentiert fehlenden ausgehenden Übergang.
  4. Jeder Punkt aus „Offene Punkte" dieser Datei ist entweder entschieden oder dem Auftraggeber mit source_key vorgelegt.
  5. Jede in Kap. 04 §4.4 bzw. Kap. 05 §5.7 genannte, von M6a berührte Tabelle und jeder Pfad hat genau eine liefernde Einheit; keine Tabelle wird in M6a und M6b doppelt migriert, kein Adapter doppelt gebaut.
  6. Jeder fachliche Fehlerfall der M6a-Pfade ist einem Code aus Kap. 05 §5.4 zugeordnet (`OFFER_EXPIRED`, `HOLD_EXPIRED`, `REQUOTE_REQUIRED`, `CAPACITY_UNAVAILABLE`, `OPERATION_IN_PROGRESS`, `REVISION_MISMATCH`, `PRECONDITION_REQUIRED`, `COOPERATION_NOT_ALLOWED`, `NOT_FOUND`, `PROVIDER_UNAVAILABLE`); neue Codes sind als Ergänzung gekennzeichnet.
- **Tests:** positiv: Vertragsvalidierung in CI. negativ: bewusst fehlerhafte Beispiel-Payloads werden vom Schema abgewiesen.
- **Abhängigkeiten:**
  - intern: keine
  - extern: WP-M0-31, WP-M0-13 (Detailvertragsprozess, CI-Vertragsprüfung mit `validate_artifacts.py`)
  - extern: WP-M5-31 (abgenommener P1-Stand als Ausgangsvertrag)
  - extern: WP-M6b-01 (Detailverträge Kooperation/Integration (WP-M6b-01) für die gemeinsame Schnittlinie)
  - extern: WP-M6c-01 (Detailverträge Zahlung/Clearing (WP-M6c-01) für Confirm, Stornoangebot und Ausführungsnachweis)
- **Entscheidungen:** D-01, D-03, D-04, D-08 blockieren nicht die Vertragsarbeit; die Verträge müssen aber alle Varianten als Konfiguration abbilden können. Blockiert nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Verträge für Grants/Widerruf (M6b) und Zahlung/Clearing (M6c); Implementierung.

### WP-M6a-02 – Schema-Migration Föderation, Hubs, Fahrplan, Capability

- **Ziel:** Alle für M6a benötigten Tabellen existieren als Flyway-Migration mit Mandantenschlüssel, zusammengesetzten Fremdschlüsseln und RLS und sind unter der Runtime-Rolle getestet.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Datenbank: neue Tabelle `adapter_capability` (Kap. 04 §4.4). Die Tabellen `transfer_hub`, `transfer_rule`, `timetable_feed`, `realtime_observation` (Kap. 04 §4.4) werden genau einmal angelegt: nach M6b OP-03 in WP-M6b-02; dieses WP liefert dafür die Spaltenanforderungen aus WP-M6a-01 zu (Profilmerkmale der Umsteigeregel, Quellenzeit/Empfangszeit/Sequenz, Lizenz, Staleness) und ergänzt per Folgemigration ausschließlich fehlende M6a-Spalten. Fällt die Abstimmung anders aus, migriert dieses WP die vier Tabellen selbst (Offene Punkte 20). Prüfung und nötigenfalls Erweiterung von `journey`, `journey_leg`, `operation`, `saga_step`, `partner_contract_version`, `cooperation_grant`, `inbox` aus `kernschema.sql` (siehe Offene Punkte 1 bis 4: Vertragsstatus – Zustandstabelle liefert WP-M6b-02 –, Ausführungsstatus der Journey, Diagnosefelder `correlation_id`/`saga_id`, Inbox-Schlüssel je Partner). Jede Tabelle mit `PRIMARY KEY(tenant_id,id)`, `ENABLE`/`FORCE ROW LEVEL SECURITY`, `USING` und `WITH CHECK`.
  - Datenbank: Indizes `(tenant_id,journey_id,sequence)` für Legs, `(tenant_id,state,updated_at)` für Arbeitslisten, Teilindex für offene Operationen (Kap. 04 §4.7).
  - Datenbank: Invarianten aus Kap. 04 §4.5 „Keine lokale Cross-Tenant-Referenz", „Kein Mandantenkontext → keine Zeile", „Gegenereignis statt Änderung" für die neuen Tabellen.
- **Detailverträge vorab:** DDL aus WP-M6a-01; Migrationsreihenfolge expand/contract; Testdaten für zwei Mandanten.
- **Akzeptanzkriterien:**
  1. Migration läuft auf leerer Datenbank und aus dem freigegebenen P1-Stand fehlerfrei.
  2. Unter der Runtime-Rolle ohne Tenantkontext liefert jede neue oder erweiterte Tabelle null Zeilen; mit Kontext A keine Zeile von B. Die Runtime-Rolle ist weder Tabelleneigentümer noch Superuser und besitzt kein `BYPASSRLS` (Kap. 02 §4.2).
  3. Ein INSERT mit fremder `tenant_id` oder fremdem zusammengesetztem FK wird abgewiesen.
  4. Ein Architekturtest bestätigt, dass nur das Modul `journey-federation` die neuen Föderationstabellen schreibt.
  5. `UPDATE`/`DELETE` auf unveränderliche Zeilen (`partner_contract_version`, `fare_quote`) wird abgewiesen; DB-Constraint-Fehlermeldungen gelangen nicht roh zur API (Kap. 02 §4.2).
  6. Backfill bestehender `journey`-/`journey_leg`-Zeilen für neue Pflichtspalten ist fortsetzbar und wird nach Abschluss validiert (Kap. 04 §4.7).
- **Tests:** positiv: Migration frisch und aus Vorstand. negativ: RLS ohne Kontext, fremder FK. konkurrierend: zwei parallele Transaktionen unterschiedlicher Mandanten nach Pool-Wiederverwendung (Kap. 04 §4.6).
- **Abhängigkeiten:**
  - intern: WP-M6a-01
  - extern: WP-M1-01, WP-M0-15, WP-M0-18 (RLS-Anbindung, DB-Rollen, Tenantkontext-Testharness)
  - extern: WP-M1-09, WP-M2-03, WP-M2-04, WP-M2-05, WP-M2-09, WP-M2-22, WP-M2-23 (vorhandene Tabellen `service`, `service_area`, `stop_revision`, `fare_quote`, `capacity_hold`, `booking`)
  - extern: WP-M6b-02 (Migration `transfer_hub`, `transfer_rule`, `timetable_feed`, `realtime_observation` und Vertragszustandstabelle (WP-M6b-02))
- **Entscheidungen:** D-02 (Isolationsstufe) blockiert nicht den Bau; opake Föderationsreferenzen müssen einen späteren Datenbankwechsel je Mandant überstehen (Kap. 04 §4.7).
- **Größe:** M
- **Nicht im Umfang:** Tabellen `settlement_statement`, `settlement_reconciliation`, `payout_instruction`, `transfer_reversal`, `refund_case`, `voucher_redemption` (M6c); `webhook_subscription`, `export_job` (M6b bzw. M8).

### WP-M6a-03 – Sandbox-Partner, Testfeeds und Konformitäts-Harness

- **Ziel:** Deterministische Testpartner und Fahrplan-Testfeeds mit steuerbaren Fehlern stehen bereit, sodass jedes folgende WP seine Positiv-, Negativ- und Konkurrenzfälle automatisiert ausführen kann.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: Erweiterung der Sandbox-Partner aus M0 unter `tests/acceptance` um die M6a-Szenarien: Hold mit kurzer Frist, Ablehnung, Confirm mit verlorener Antwort, kein Statuslookup, Duplikat- und Fehlreihenfolge-Ereignisse, verspätetes `confirmed` nach `cancelled`, Verspätungsmeldung.
  - Adapter/Worker: GTFS-Testfeed und GTFS-RT-Testfeed mit steuerbarer Reihenfolge und Zeitstempeln; Linienbus ohne Buchungs-API.
  - Adapter/Worker: Konformitäts-Harness, das dieselbe Testsuite gegen jeden Adapter ausführt (Kap. 02 §9: Standardversion im Contract-Test fixiert, nicht `latest`).
  - Datenbank: Fixtures Mandant A, Mandant B, Drittpartner C, gemeinsamer Hub, Vertrag A↔B für Dienst D (Kap. 02 §10).
- **Detailverträge vorab:** Szenariokatalog mit Seed, erwarteter Zustandsfolge und Fehlerinjektionspunkten; Fixture-Schema mit Vertrags-, Tarif- und Konfigurationsrevision (Kap. 08 §8.4).
- **Akzeptanzkriterien:**
  1. Jedes Szenario ist per Seed reproduzierbar und liefert bei Wiederholung identische Zustandsfolgen.
  2. Fehler lassen sich je Saga-Schritt gezielt injizieren (Timeout, verlorene Antwort, Duplikat, Prozessabbruch nach externem Erfolg).
  3. Die Fixtures für PH-AT-005 bis PH-AT-010 sind als versionierte Dateien abgelegt.
- **Tests:** positiv: Selbsttest des Harness gegen den Referenz-Sandbox-Partner. negativ: ein absichtlich nicht konformer Sandbox-Partner fällt in der Suite durch.
- **Abhängigkeiten:**
  - intern: WP-M6a-01
  - extern: WP-M0-24, WP-M0-23 (deterministische Sandbox-Partner und PSP-Simulator als Testinfrastruktur)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** PSP-Simulator-Erweiterungen für Mehrparteienzahlung (M6c); reale Partnerzugänge (WP-M6a-31).

### WP-M6a-04 – Vertragsgate und Kooperationsfilter

- **Ziel:** Ein Partner erscheint nur dann in Verfügbarkeit und Buchung, wenn ein bestätigter, wirksamer Vertrag und die technische Freigabe vorliegen; ohne bestätigten Vertrag ist der aktive Buchungsmodus nicht erreichbar.
- **Anforderungen:** `LH-I.5-FED-003`, `LH-I.5-FED-014`
- **Liefergegenstände:**
  - Backend: Modul `journey-federation`, Anbieterfilter nach Kap. 02 §6 Nr. 2 (aktive Kooperation, dienst-/gebietsbezogene Rechte, Partnerpause, Capability-Profil). Prüfung des Vertragszustands `DRAFT → REVIEWED → ACTIVE → PAUSED/EXPIRED/REVOKED` (Kap. 02 §5) und des halboffenen Intervalls `[valid_from, valid_until)` (im DDL `valid_from`/`valid_to`) gegen Serverzeit vor jeder Angebots-, Hold- und Confirm-Operation. Die Prüfung ruft den Grant-Gate-Port und den Anbieterfilter-Port aus M6b auf (online gegen den Grantstatus, kein veraltetes Token; Grantrevision unmittelbar vor dem lokalen Commit, Kap. 02 §5); Daten-, Auskunfts-, Buchungs-, Zahlungs- und Clearingrechte werden getrennt ausgewertet.
  - Backend: Validierung der Pflichtinhalte eines Vertrags in `partner_contract_version.terms`: technische Erreichbarkeit, Antwortfristen, Datenweitergabe, Storno, Haftungszuordnung.
  - Datenbank: `partner_contract_version`, `cooperation_grant` (lesend; Grantprüfung `actions`, `service_refs`, `valid_from`, `valid_to`, `revoked_at`); Invariante „Grantwiderruf sofort wirksam" (Kap. 04 §4.5).
  - API: `/v1/federation/journeys/plan` liefert für nicht freigegebene Partner keinen Eintrag; Hold/Confirm antworten `403 COOPERATION_NOT_ALLOWED` (Kap. 05 §5.4); `PartnerAvailability.reason_code = CONTRACT_INACTIVE` nur für Partner, deren Existenz der Anfragende kennen darf.
  - Konfigurationsparameter: `interoperator_bookings`, `sharing_service_ids`, `partner_transport_modes`.
  - Ereignisse/Outbox: Auditereignis bei abgewiesener Kooperationsprüfung ohne Fremdinhalte.
- **Detailverträge vorab:** JSON-Schema der Vertragsinhalte (`terms`); Zustandsautomat Vertrag mit Aktivierungsvoraussetzungen (beidseitig genehmigte Fassung, Capability-Prüfung, Rechtsfreigabe); Abgrenzung der Zuständigkeit zu M6b.
- **Akzeptanzkriterien:**
  1. Ein deaktivierter Partner erscheint weder in der Verfügbarkeitsantwort noch in Partnerbuchungen; ein Hold-Versuch mit bekannter Offer-ID wird abgewiesen (FED-003).
  2. Ein Vertrag ohne bestätigte Fassung kann nicht in den aktiven Buchungsmodus wechseln; der Versuch wird mit Grund abgewiesen und auditiert (FED-014).
  3. Ein Vertrag, dem eine der fünf Pflichtangaben (Erreichbarkeit, Antwortfristen, Datenweitergabe, Storno, Haftungszuordnung) fehlt, ist nicht aktivierbar (FED-014).
  4. `offer:read` verleiht weder `hold:create` noch `booking:confirm` (Kap. 02 §2) (FED-003).
  5. Wird der Grant zwischen Hold und Confirm entzogen, stoppt der Commit nach Grantprüfung; der In-flight-Fall wird abgeglichen (Kap. 02 §10) (FED-003).
  6. Ein technisch nicht freigegebener Partner (Vertrag `ACTIVE`, aber Capability ohne Sandboxnachweis) erscheint nicht als buchbar; vertragliche und technische Freigabe sind zwei getrennt geprüfte Bedingungen (FED-003).
  7. Endet der Vertrag vor der Ausführung einer bestätigten Reise, entsteht kein Neugeschäft; die bestätigte Reise bleibt mit ihrer eingefrorenen Vertragsrevision bestehen und wird nicht still gelöscht (Zusatznachweis „Vertragsablauf bei bestehender Zusage", Kap. 02 §10; Auslaufprozess selbst in M6b/M6c).
  8. Die Ablehnung nennt dem Anfragenden keine Existenzdetails eines ihm nicht bekannten Partners; das Auditereignis enthält keine Fremdinhalte.
- **Tests:** positiv: aktiver Vertrag A↔B für Dienst D, Angebot und Buchung möglich. negativ: Vertrag `DRAFT`, `REVIEWED`, `PAUSED`, `EXPIRED`, `REVOKED`, Dienst nicht im Grant, Drittpartner C, Anfrage genau zum Zeitpunkt `valid_to` (halboffenes Intervall), Vertrag mit fehlender Pflichtangabe. konkurrierend: Widerruf parallel zu Confirm (zwei Transaktionen), altes Token/Cache nach Widerruf. Referenztests: ermöglicht PH-AT-002, PH-AT-003.
- **Abhängigkeiten:**
  - intern: WP-M6a-02
  - extern: WP-M6b-04, WP-M6b-05, WP-M6b-06, WP-M6b-07, WP-M6b-08 (Vertrags- und Grant-Lebenszyklus mit Pause, Widerruf und Auslaufregeln, Grant-Gate-Port und Anbieterfilter-Port, UI-18 (WP-M6b-04 bis WP-M6b-08))
  - extern: WP-M1-19, WP-M1-20, WP-M1-46 (Konfigurationsregister, Audit)
- **Entscheidungen:** D-01 und D-08 blockieren nur die Produktivschaltung (Rechtsträgerrollen, tatsächliche Buchungsrechte); Bau mit Testverträgen möglich.
- **Größe:** M
- **Nicht im Umfang:** Erteilen, Pausieren und Entziehen von Grants samt UI-18 (M6b); Restabwicklungsgrant für laufende Reisen (M6b).

### WP-M6a-05 – Capability Registry und versionierte Adapterschnittstelle

- **Ziel:** Jeder Adapter ist versioniert, kapselt das Partnermodell hinter einem kanonischen Reiseobjekt und meldet seine Fähigkeiten dienstbezogen; Auskunfts- und Buchungsfähigkeit sind getrennte Merkmale.
- **Anforderungen:** `LH-I.5-FED-015`, `LH-I.5-FED-019`
- **Liefergegenstände:**
  - Backend: Adapter-SPI im Modul `journey-federation` mit den Operationen Discovery, Verfügbarkeit, Hold, Confirm, Status, Cancel; kanonisches internes Reiseobjekt ohne partnerspezifische Felder; Adapterversion je Aufruf protokolliert.
  - Datenbank: `adapter_capability` mit der Matrix aus Kap. 02 §9 (Standard und Version, Discovery, Auskunft, bindende Preise, Hold, Holdverlängerung, Confirm, deduplizierte Requests, Statuslookup, Storno, Refund, Ticketausgabe, Echtzeit, Signaturverfahren, Quoten, Sandboxnachweis), dienst- und teilweise produktbezogen.
  - Backend: Ableitung von `booking_capability` (`PLATFORM_BOOKABLE`, `EXTERNAL_BOOKING`, `INFORMATION_ONLY`) aus Vertrag und Capability; `PLATFORM_BOOKABLE` nur bei bestätigter Booking-/Status-/Storno-Fähigkeit (Kap. 05 §5.2). Unbekannte Zustände fail closed.
  - API: `/v1/partner/capabilities`.
  - Konfigurationsparameter: `partner_transport_modes` („Nur nutzbare Partner-APIs als buchbar deklarieren").
- **Detailverträge vorab:** JSON-Schema der Capability-Matrix; SPI-Signaturen; Mapping-Regel Capability → `booking_capability`; Versionierungsregel für Adapter.
- **Akzeptanzkriterien:**
  1. Eine Änderung des Partner-Datenmodells wird durch eine neue Adapterversion aufgenommen; bestehende Zeilen in `journey` und `journey_leg` werden nicht migriert, alte Reisen bleiben lesbar (FED-015).
  2. Ein Architekturtest belegt, dass kein Modul außerhalb der Adapter partnerspezifische Typen importiert (FED-015).
  3. Ein nur informationsliefernder ÖPNV-Partner erhält `INFORMATION_ONLY`; kein Hold, kein Confirm, keine Zahlung wird für sein Segment ausgelöst (FED-019).
  4. Ein Partner mit Fahrplanfeed, aber ohne Sandboxnachweis für Hold/Confirm, wird nie als `PLATFORM_BOOKABLE` ausgewiesen (FED-019).
  5. Eine Capability ohne Sandboxnachweis bleibt deaktiviert (Gate „Partnerfreigabe", Kap. 08 §8.4).
  6. Capability ist dienst- und teilweise produktbezogen: derselbe Partner kann für Dienst X `PLATFORM_BOOKABLE` und für Dienst Y `INFORMATION_ONLY` sein (Kap. 02 §9) (FED-019).
  7. `adapter_capability` ist nur von berechtigten Rollen des eigenen Mandanten änderbar, jede Änderung ist auditiert; `/v1/partner/capabilities` zeigt einem Partner ausschließlich die eigenen Fähigkeiten (Abgrenzung der Antwortfelder Scopes/Quoten zu WP-M6b-26, siehe Offene Punkte 20).
- **Tests:** positiv: Adapter v1 und v2 parallel, alte Reise lesbar. negativ: unbekannter Capability-Wert → fail closed; Hold gegen `INFORMATION_ONLY`-Leg wird abgewiesen; Partner B liest Capability von Partner C (404); Änderung ohne Rolle (403). konkurrierend: Capability wird während laufender Angebotsbildung deaktiviert – ein danach gestarteter Hold wird abgewiesen; zwei parallele Änderungen derselben Capability (ETag, 412). Referenztests: ermöglicht E2E-087, PH-AT-006.
- **Abhängigkeiten:**
  - intern: WP-M6a-02
- **Entscheidungen:** D-08 blockiert nur die Produktivschaltung konkreter Partner.
- **Größe:** M
- **Nicht im Umfang:** konkrete Adapter (WP-M6a-07, WP-M6a-26, WP-M6a-31); Sandbox-Freischaltprozess (WP-M6a-32).

### WP-M6a-06 – Partnervertrag `/v1/partner/*` auf Betreiberseite

- **Ziel:** Der ausführende Betreiber bietet Verfügbarkeit, befristete Reservierung, Bestätigung, Status und Storno über den internen Partnervertrag an und bleibt alleinige Kapazitätsautorität.
- **Anforderungen:** `LH-I.5-FED-004`, `LH-I.5-FED-005`
- **Liefergegenstände:**
  - API: `/v1/partner/offers`, `/v1/partner/reservations`, `/v1/partner/reservations/{id}/confirm`, `/v1/partner/reservations/{id}/cancel`, `/v1/partner/reservations/{id}/status`; Maschinenidentität mit OAuth Client Credentials und schmalen Scopes (Kap. 02 §4.1).
  - Backend: Modul `booking-dispatch` des Betreibers führt Hold und Confirm aus; der Aufrufer erhält nur `hold_id`, `expires_at`, `booking_ref`. Stabile Command-ID je Operation; Wiederholung liefert dasselbe Ergebnis. Ablaufzeit wird bei Confirm serverseitig erneut geprüft; Expiry-Job ist nicht die einzige Ablaufprüfung (Kap. 02 §7.1). Ein Hold wird nur bei erneuter Kapazitätsprüfung verlängert (Kap. 02 §7.2). Quoten je Partner mit `429` und `Retry-After` (Kap. 05 §5.1); `traceparent`/`correlation_id` werden übernommen und protokolliert.
  - Datenbank: `capacity_hold` (`federation_ref` eindeutig je Mandant, `expires_at`, Zustände `HELD`, `CONSUMED`, `RELEASED`, `EXPIRED`), `booking`, `vehicle_run`; Sperren der Kapazitätsabschnitte in stabiler Reihenfolge über alle Dimensionen (Sitz, Rollstuhlplatz, Ausstattung). Invariante „Kapazität/Qualifikation/Fahrzeugfreigabe" (Kap. 04 §4.5).
  - Konfigurationsparameter: `partner_offer_ttl_sec`, `sharing_vehicle_quota`, `min_capacity_reserve`. Planungsdefault interner Hold: 180 s.
  - Ereignisse/Outbox: Hold angelegt/abgelaufen/freigegeben, Buchung bestätigt/storniert, adressiert an den Vertriebsmandanten.
- **Detailverträge vorab:** OpenAPI der `/v1/partner/*`-Pfade mit Fehlercodes `HOLD_EXPIRED`, `CAPACITY_UNAVAILABLE`, `OPERATION_IN_PROGRESS`; Zustandsautomat `capacity_hold`; Scope-Liste.
- **Akzeptanzkriterien:**
  1. Partner B kann über keinen Endpunkt und keine Datenbankrolle die Kapazität, Zuweisung oder den Fahrtenplan eines Fahrzeugs von Partner A ändern; es existiert kein schreibender Pfad auf `vehicle_run` oder `capacity_hold` eines fremden Mandanten (FED-004).
  2. Jeder Hold trägt eine eindeutige Referenz und eine Ablaufzeit; nach Ablauf wird Confirm mit `409 HOLD_EXPIRED` abgewiesen (FED-005).
  3. Bei zwei parallel eingehenden Fahrgästen für denselben letzten Platz entsteht genau eine gültige Reservierung; Kapazität wird auf keinem Abschnitt negativ (FED-005).
  4. Ein freier Sitz ersetzt keine fehlende Rollstuhlkapazität (FED-005).
  5. Wiederholtes Senden derselben Command-ID erzeugt keinen zweiten Hold.
  6. Eine Holdverlängerung ohne erneut bestandene Kapazitätsprüfung wird abgewiesen; ein abgelaufener Hold gibt die Kapazität auf allen Abschnitten und Dimensionen wieder frei.
  7. Die Statusabfrage `/v1/partner/reservations/{id}/status` liefert für eine bekannte Command-ID auch nach verlorener Confirm-Antwort das tatsächliche Ergebnis (Grundlage für WP-M6a-15).
  8. Jeder Aufruf wird gegen Vertrag/Grant des aufrufenden Partners geprüft (`403 COOPERATION_NOT_ALLOWED`); der Aufrufer erhält nur die Datenprojektion seines Segments.
- **Tests:** positiv: Hold → Confirm → Status → Cancel. negativ: Confirm nach Ablauf; Zugriff mit Token B auf Reservierung A (404); fehlender Scope (403); Quotenüberschreitung (429); Holdverlängerung bei inzwischen vergebener Kapazität; Storno einer bereits stornierten Reservierung (idempotent). konkurrierend: 100 parallele Holds auf den letzten Platz, getrennt für Sitz und Rollstuhlplatz; paralleler Hold und Planrevision. Referenztests: semantische Contract-Fälle „zwei Betreiber/letzter Platz" und „abgelaufene Holds" (Kap. 05 §5.8); Nachweis „Letzter Platz parallel" (Kap. 02 §10).
- **Abhängigkeiten:**
  - intern: WP-M6a-01, WP-M6a-04, WP-M6a-12
  - extern: WP-M2-09, WP-M2-22, WP-M2-23, WP-M2-24, WP-M2-26 (lokaler Hold-/Confirm-/Storno-Kern mit Kapazitätssperren und `fare_quote`)
  - extern: WP-M1-02, WP-M1-04, WP-M1-13, WP-M1-14 (Idempotenzregister, Outbox, Maschinenidentitäten, Rate-Limit-Grundlage)
  - extern: WP-M6b-23, WP-M6b-24, WP-M6b-26 (Partnerzugänge mit Scopes, Quoten und Schlüsselentzug (WP-M6b-26); Zustellung ausgehender Ereignisse mit Retry und Dead-Letter (WP-M6b-23, WP-M6b-24))
- **Entscheidungen:** keine für den Bau; D-08 für Produktivschaltung externer Aufrufer.
- **Größe:** L
- **Nicht im Umfang:** Aufruferseite und Orchestrierung (WP-M6a-07, WP-M6a-13); Kontingentregeln für Fremdfahrzeuge (`resource_release`, M6b).

### WP-M6a-07 – Interner TAKTOVIA-Adapter und Lebenszyklus-Vertragstest

- **Ziel:** Der Referenzadapter für andere Plattformmandanten bedient den vollständigen Segment-Lebenszyklus über denselben Föderationsvertrag wie externe Partner, nachgewiesen durch einen automatisierten Vertragstest.
- **Anforderungen:** `LH-I.5-FED-016`
- **Liefergegenstände:**
  - Adapter/Worker: interner Zweitbetreiber-Adapter im Modul `journey-federation`, der ausschließlich `/v1/partner/*` aufruft, keine direkte Manipulation fremder Fahrzeug- oder Buchungstabellen (Kap. 02 §9).
  - Adapter/Worker: Konformitätssuite für Discovery, Verfügbarkeitsabfrage, Angebotsgültigkeit, Reservierung, Bestätigung, Statusupdate und Stornierung.
  - Datenbank: `adapter_capability`-Einträge des internen Adapters mit Sandboxnachweis.
  - Konfigurationsparameter: `partner_api_timeout_ms`, `partner_retry_policy` („Idempotenz vorausgesetzt").
- **Detailverträge vorab:** Vertragstest-Spezifikation je Lebenszyklusschritt mit erwarteten Zuständen und Fehlercodes.
- **Akzeptanzkriterien:**
  1. Ein automatisierter Vertragstest durchläuft Discovery, Verfügbarkeit, Angebotsgültigkeit, Reservierung, Bestätigung, Statusupdate und Stornierung für ein Segment und läuft in CI (FED-016).
  2. Derselbe Vertragstest ist ohne Änderung gegen jeden weiteren Adapter ausführbar (FED-016).
  3. Ein Timeout nach Confirm führt zu einer Statusabfrage mit unveränderter Command-ID, nicht zu einem zweiten Confirm.
- **Tests:** positiv: vollständiger Lebenszyklus. negativ: abgelaufenes Angebot, Storno einer unbekannten Reservierung. konkurrierend: gleiche Command-ID parallel von zwei Workern.
- **Abhängigkeiten:**
  - intern: WP-M6a-03, WP-M6a-05, WP-M6a-06
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** TOMP-/OSDM-Adapter (WP-M6a-31); Refund-Fähigkeit des Partners (M6c).

### WP-M6a-08 – Transfer-Hubs und Mindestumsteigezeiten

- **Ziel:** Umstiege sind nur über freigegebene Transfer-Hubs und nur mit ausreichender, profilabhängiger Umsteigezeit zulässig; auch nicht überlappende Bediengebiete lassen sich so verbinden.
- **Anforderungen:** `LH-I.5-FED-009`, `LH-I.5-FED-011`, `LH-6.11-PTI-007`
- **Liefergegenstände:**
  - Datenbank: `transfer_hub`, `transfer_rule` (Stoppaare, Gehweg, Puffer) – Anlage in WP-M6b-02; dieses WP nutzt sie und ergänzt die profilabhängigen Merkmale der Umsteigeregel; Bezug zu `stop_revision` und `service_area`.
  - API: `/v1/transfer-hubs` (GeoJSON/SRID-Konvention, Datengültigkeit, barrierefreie Zuordnung, Wirksamkeitsprüfung; Kap. 05 §5.7) – Grundpfad aus WP-M6b-10/WP-M6b-11; dieses WP ergänzt die Felder für profilabhängige Umsteigezeiten und barrierefreie Umsteigewege.
  - Backend: Modul `service-geo`/`journey-federation`: gerichteter Reisegraph aus Bediengebieten und genehmigten Hubs (Kap. 02 §6 Nr. 1); Zulässigkeitsregel `arrival_latest(A) + transfer_duration(accessibility, hub) + risk_buffer <= departure_earliest(B)`; `minimum_transfer_seconds` enthält Geh-, Assistenz- und Sicherheitspuffer genau einmal (Kap. 05 §5.9). Unterscheidung nach Fußweg, Rollstuhlbedarf, Stufen, Übergabetoleranz und Betriebsrisiko. ÖPNV-Hubs mit zusätzlichen Regeln für barrierefreie Umsteigewege.
  - Oberfläche: UI-17 (Gebiete/Stops/Tarife) Pflege der profilabhängigen Umsteigeregeln als Revision mit primärer Aktion „Revision freigeben" und den Zuständen „Ungültige Geometrie, konkurrierende Version" (Kap. 06 §6.9; Grundmaske Hubs/Übergabepunkte aus WP-M6b-11); Karte mit gleichwertiger Liste, Tastaturbedienung. UI-02 zeigt Umstiegszeit, enthaltenen Fußweg und Barrierefreiheitsstatus (`Transfer.walking_seconds`, `minimum_transfer_seconds`, `available_seconds`, `accessible`).
  - Konfigurationsparameter: `transfer_min_time_min`, `interchange_min_time_min`, `advance_arrival_buffer_min`, `stop_walk_max_m`, `wheelchair_boarding_sec`.
- **Detailverträge vorab:** DDL `transfer_hub`/`transfer_rule`; OpenAPI `/v1/transfer-hubs`; Berechnungsregel der Mindestumsteigezeit je Profil mit Rechenbeispiel.
- **Akzeptanzkriterien:**
  1. Ein Rollstuhlwechsel mit ungenügender Zeit wird nicht als sichere Verbindung angeboten; die Suche liefert stattdessen eine zulässige Alternative oder `reject_reason = NO_ACCESSIBLE_TRANSFER` (FED-009).
  2. Die fünf Einflussgrößen Fußweg, Rollstuhlbedarf, Stufen, Übergabetoleranz und Betriebsrisiko sind je Hub getrennt hinterlegbar und wirken nachweislich auf das Ergebnis (FED-009).
  3. Eine Reise zwischen zwei nicht verbundenen Gebieten liefert einen zulässigen Umstieg an einem definierten Hub und keine unzulässige Direktfahrt (FED-011).
  4. Ein Rollstuhlprofil verwendet an einem ÖPNV-Hub die für dieses Profil hinterlegte Umsteigezeit und den barrierefreien Knoten, nicht den Standardweg (PTI-007).
  5. Gegenprobe PH-AT-005: Ankunft A 10:00, fünf Minuten Fußweg plus fünf Minuten Sicherheit, Abfahrt B 10:09 ist unzulässig, 10:10 zulässig.
  6. Eine gemeinsame Haltestellen-ID ohne `transfer_rule` gilt nicht als zulässiger Umstieg.
  7. Gebietspolygone allein erlauben keine Fahrt: Ausschlusszonen, Servicezeiten und barrierefreie Übergänge wirken als harte Filter des Reisegraphen (Kap. 02 §6 Nr. 1) (FED-011).
  8. Eine geänderte Umsteigeregel wirkt erst ab Wirksamkeitsdatum auf neue Angebote; bestätigte Reisen behalten ihre gebundene Zusageversion. Zwei gleichzeitige Bearbeitungen derselben Revision führen zu `412 REVISION_MISMATCH` statt stillem Überschreiben.
  9. Ein Nutzer ohne Pflegerecht kann Hubs und Regeln nicht ändern (403); Hubs fremder Mandanten ohne Freigabe sind nicht sichtbar (404).
- **Tests:** positiv: zulässiger Umstieg mit und ohne Rollstuhl. negativ: Stufenweg bei Rollstuhlbedarf, Hub außerhalb Wirksamkeit, ungültige Geometrie, fehlendes Pflegerecht, Hub eines fremden Mandanten ohne Grant. konkurrierend: Regelrevision wird parallel zur Angebotsbildung veröffentlicht; zwei Bearbeiter derselben Revision. Referenztests: ermöglicht E2E-086, PH-AT-005; Nachweis „Verzögerung und nicht barrierefreier Ersatz" (Kap. 02 §10).
- **Abhängigkeiten:**
  - intern: WP-M6a-02
  - extern: WP-M2-03, WP-M2-04, WP-M2-05 (Dienste, Bediengebiete, Haltepunktrevisionen mit Geo-Validierung)
  - extern: WP-M1-19, WP-M1-20, WP-M1-22 (Konfigurationsregister mit Wirksamkeitsintervallen)
  - extern: WP-M6b-02, WP-M6b-10, WP-M6b-11 (gemeinsame Haltestellen/Gebiete, `/v1/transfer-hubs`, Gebietsregel-Port und UI-17-Grundmaske (WP-M6b-10, WP-M6b-11); Tabellenanlage (WP-M6b-02))
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Veröffentlichung gemeinsamer Haltestellen und Gebietsregeln (Überlappung, Ausschlusszone, Tarifgrenze; M6b); Fußwegrouting über einen Routingdienst (siehe Offene Punkte 8); Straßenrestriktionen (M7).

### WP-M6a-09 – Föderierte Reiseauskunft mit Teilverfügbarkeit

- **Ziel:** Eine Suche kombiniert Angebote mehrerer Plattformmandanten und ÖPNV-Partner zu Reiseketten; ein nicht antwortender Partner wird sichtbar als unverfügbar markiert, ohne die übrige Auskunft zu blockieren.
- **Anforderungen:** `LH-I.5-FED-001`, `LH-I.5-FED-017`
- **Liefergegenstände:**
  - API: `/v1/federation/journeys/plan` (`PlanRequest`, `PlanResponse` mit `offers`, `partners`, `reject_reason`), `/v1/federation/offers/{offer_id}` mit ETag; kein Treffer ist `200` mit leeren `offers` und normiertem Ablehnungsgrund, fehlerhafte Eingabe `422` (Kap. 05 §5.3).
  - Backend: Journey Planner im Modul `journey-federation`: begrenzt parallele Partnerabfragen, Deadline je Partner, Circuit Breaker; `JourneyOffer` mit geordneten `OfferLeg`s, `Transfer`, unveränderlichem Preisstand und `expires_at` als Minimum aller buchungsrelevanten Fristen abzüglich Reserve (Kap. 05 §5.2). Angebote sind unverbindlich und erzeugen keine Kapazitätszusage.
  - Datenbank: `travel_attempt` (Suchversuch mit `primary_rejection`), `fare_quote` (unveränderlich, `content_hash`).
  - Oberfläche: UI-01 (Passenger Suche) mit Zuständen „Keine Abdeckung, ungültige Zeit, Partnerausfall"; Hinweis auf fehlende Partnerdaten bei weiter verfügbaren Alternativen (Kap. 06 §6.2).
  - Konfigurationsparameter: `partner_api_timeout_ms`, `partner_offer_ttl_sec`, `partner_retry_policy`. Planungsdefaults: 1.500 ms bis zur ersten Partnerauskunft, 3.000 ms gemeinsames Partnerfenster, 5.000 ms Gesamtbudget (1.000 ms lokale Planung, 1.000 ms Reserve).
  - Kennzahlen: Ereignisse für `Technische Partnerablehnungen`, `Partnerannahmequote`, `Anfragen`, `Angebotsquote`.
- **Detailverträge vorab:** Algorithmusbeschreibung Reisegraph und Kombinationsbildung; Budget- und Abbruchregel; Zuordnung Partnerfehler → `PartnerAvailability.state`/`reason_code`.
- **Akzeptanzkriterien:**
  1. Eine Suche zwischen Gebiet A und Gebiet B erzeugt mindestens eine verfügbare Reisekette mit zwei unterschiedlichen Betreibersegmenten (FED-001).
  2. Eine Suche mit ÖPNV-Partner erzeugt eine Kette aus On-Demand-Segment und Informationssegment (FED-001); in diesem WP nachgewiesen mit dem Sandbox-Linienbus ohne Buchungs-API aus WP-M6a-03, mit realem Fahrplanfeed in WP-M6a-26.
  3. Antwortet Partner B nicht, liefert die Auskunft innerhalb des Gesamtbudgets die Angebote aller anderen Anbieter; B erscheint in `partners` mit `state = TIMEOUT` und `reason_code = PARTNER_TIMEOUT` (FED-017).
  4. Der Timeout je Partner ist über `partner_api_timeout_ms` konfigurierbar und wirkt nachweislich (FED-017).
  5. UI-01 zeigt die Teilverfügbarkeit klar erkennbar an, nicht als leeres Ergebnis (FED-017).
  6. `GET /v1/federation/offers/{offer_id}` liefert unter derselben ID/Revision keinen neu berechneten Stand.
  7. `PlanRequest.booking_subject` ist Pflicht; bei `DELEGATED` wird die Vertretungs-/Callcenter-Legitimation nach Zweck, Dienst und Laufzeit geprüft, der Bediener bleibt eigener Akteur im Audit (Kap. 05 §5.9).
  8. Ein leeres Ergebnis nennt den primären Ablehnungsgrund mit nächster Handlung; der Suchversuch ist in `travel_attempt` protokolliert, ohne Personen- oder Ortssuchen als Metriklabel (Kap. 05 §5.1, Kap. 06 §6.2).
  9. Ein Angebot eines anderen Mandanten oder Fahrgasts ist über `GET /v1/federation/offers/{offer_id}` nicht lesbar (`404 NOT_FOUND`).
  10. Die Suche ist abbrechbar; ein Abbruch erzeugt weder Hold noch Buchung. Die Teilverfügbarkeit wird nicht nur über Farbe vermittelt und per Live-Region angekündigt (Kap. 06 §6.1).
- **Tests:** positiv: zwei Betreiber, zulässiger Hub. negativ: Partner-Timeout, Partner liefert Fehler, kein Hub, ungültige Ortszeit in der DST-Lücke (`422`), doppelte Ortszeit ohne eindeutigen Offset, abgelaufene Vertretung bei `DELEGATED`, fremde Offer-ID (404), Quotenüberschreitung (429). konkurrierend: parallele Suchen bei offenem Circuit Breaker; Suche parallel zum Grantentzug liefert kein Angebot des entzogenen Partners. Referenztests: besteht E2E-088; ermöglicht E2E-086, E2E-087. Messung der Planungsbudgets gegen Sandbox-Partner siehe WP-M6a-34.
- **Abhängigkeiten:**
  - intern: WP-M6a-03, WP-M6a-04, WP-M6a-05, WP-M6a-07, WP-M6a-08
  - extern: WP-M2-07, WP-M2-11, WP-M2-21, WP-M2-37, WP-M7a-11 (lokale Angebotsbildung (`/v1/ride-offers`), `travel_attempt`, Tarifrevision, Vertretungen (`/v1/delegations`), Passenger-Web UI-01)
  - extern: WP-M1-02 (einheitliches Fehlermodell, Rate-Limit, Trace-Korrelation)
  - extern: WP-M6b-06, WP-M6b-11 (Anbieterfilter-Port (Pause/Grant) und Gebietsregel-Port (WP-M6b-06, WP-M6b-11))
- **Entscheidungen:** D-05 (Peaklast) für die Kalibrierung der Budgets im Referenzlasttest; blockiert nicht den Bau.
- **Größe:** L
- **Nicht im Umfang:** Ranking (WP-M6a-10); ÖPNV-Alternativen und intermodale Wunschankunft (WP-M6a-28); Preisaufstellung im Detail (WP-M6a-23).

### WP-M6a-10 – Nachvollziehbares Ranking und Gebietsüberlappung

- **Ziel:** Bei konkurrierenden Partnerangeboten ist die Reihenfolge begründet, versioniert und frei von sachlich unzulässiger Priorisierung.
- **Anforderungen:** `LH-I.5-FED-033`
- **Liefergegenstände:**
  - Backend: Ranking nach Kap. 02 §6 Nr. 6: zuerst Sicherheit und Zulässigkeit, danach Nutzerpräferenz, Reisezeit, Umstiege, Preis, Qualität und explizit dokumentierte Vertragsregeln. Speicherung der Rankingrevision und der wesentlichen Gründe am Angebot (`fare_quote.snapshot`).
  - Backend: überlappende Angebote bleiben getrennte Alternativen; Auswahlregel bei Gebietsüberlappung transparent.
  - API: Entscheidungsgrundlage im Angebot (Ergänzung zu `JourneyOffer`, siehe Offene Punkte 6).
  - Oberfläche: UI-02 zeigt die Entscheidungsgrundlage je Alternative.
  - Konfigurationsparameter: `area_overlapping_priority` („Transparenter Auswahlmechanismus").
- **Detailverträge vorab:** Katalog der Rankingkriterien und Gründe; Schema der Rankingrevision; Liste unzulässiger Priorisierungsmerkmale; OpenAPI-Ergänzung.
- **Akzeptanzkriterien:**
  1. Der Reisevergleich enthält je Alternative die Entscheidungsgrundlage (Kriterien, Rankingrevision) (FED-033).
  2. Eine Regel, die eine Alternative ohne dokumentierte Vertragsregel bevorzugt, wird bei der Veröffentlichung abgewiesen; eine vertraglich bevorzugte Alternative wird nicht als objektiv schnellste Reise ausgegeben (FED-033).
  3. Zwei Firmen am selben Hub: die Auswahlregel ist eindeutig und reproduzierbar; gleiche Eingabe und gleiche Revision ergeben dieselbe Reihenfolge (FED-033).
  4. Eine unzulässige oder unsichere Alternative steht nie vor einer zulässigen.
  5. Die drei in der Anforderung genannten Kriterien Servicequalität, Preis und vertragliche Regeln sind je Alternative als Grund ausgewiesen; die Rankingrevision des Angebots bleibt nach einer Regeländerung unverändert nachvollziehbar (FED-033).
- **Tests:** positiv: zwei gleichwertige Partner, deterministische Reihenfolge. negativ: Regel mit nicht dokumentiertem Bevorzugungsmerkmal; Regeländerung ohne Freigabe. konkurrierend: Rankingrevision wechselt während laufender Suche – das Angebot trägt genau eine Revision. Referenztests: ermöglicht E2E-085.
- **Abhängigkeiten:**
  - intern: WP-M6a-09
  - extern: WP-M1-20, WP-M1-22 (Konfigurationsrevision mit Vier-Augen-Freigabe)
  - extern: WP-M6b-11 (Gebietsregel-Port mit eindeutiger Auswahl nach `area_overlapping_priority` (WP-M6b-11))
- **Entscheidungen:** D-01 (wer verkauft) beeinflusst zulässige Vertragsregeln; blockiert nur Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Buchung beim bestätigenden Betreiber (WP-M6a-13).

### WP-M6a-11 – Angebotsdarstellung mit Betreiberausweis und Buchungsfähigkeit

- **Ziel:** Der Fahrgast erkennt vor der Zahlung je Teilstrecke Betreiber, Fahrzeugart, Barrierefreiheit, Preis, Übergaberisiko und welche Buchungs-, Zahlungs- und Ticketfunktion tatsächlich verfügbar ist.
- **Anforderungen:** `LH-I.5-FED-010`, `LH-I.5-FED-020`
- **Liefergegenstände:**
  - Oberfläche: UI-02 (Angebote/Details) mit kombinierten Angeboten: je Leg Betreiber, Verkehrsmittel/Fahrzeugart, Zugänglichkeitsstatus, Preis, Umstiegszeit, `assurance` (`NOT_GUARANTEED` als Default); Zustände „Abgelaufen, externe Tickets, unbestätigte Barrierefreiheit". UI-03 (Checkout) nennt Anbieter und Rechnungsaussteller je Abschnitt; externe Ticketabschnitte stehen vor dem Kaufbutton und verlangen ausdrückliche Bestätigung (Kap. 06 §6.3).
  - API: `OfferLeg` (`operator_id`, `seller_legal_entity_id`, `mode`, `booking_capability`, `fare_status`, `quoted_fare`, `external_booking_uri`, `accessibility_confirmed`, `cancellation_terms`), `JourneyOffer.fully_platform_bookable`, `PriceSummary` (`pay_at_platform`, `external_known`, `total_known_amount`, `external_unknown_leg_ids`, `is_complete_total`), `Transfer`.
  - Backend: externer Buchungsweg nur mit geprüftem HTTPS-Ziel (Kap. 05 §5.2).
- **Detailverträge vorab:** Textkatalog der Fähigkeits- und Risikohinweise (Deutsch, i18n-vorbereitet); OpenAPI-Ergänzung für Fahrzeugart (siehe Offene Punkte 6); Prüfregel für `external_booking_uri` (nur HTTPS, geprüftes Ziel); Barrierefreiheitsvertrag der Bildschirme (WCAG 2.2 AA als Abnahmeziel).
- **Akzeptanzkriterien:**
  1. Vor der Zahlung ist je Teilstrecke der durchführende Betreiber sichtbar; ebenso Fahrzeugart, Barrierefreiheit, Preis und wesentliche Übergaberisiken (FED-010).
  2. Eine Verbindung ohne Partner-Buchungs-API zeigt entweder einen externen Buchungsweg oder den Hinweis „nur Auskunft"; es gibt keinen Kaufbutton für dieses Segment (FED-020).
  3. Bei unbepreistem Transportleg wird `total_known_amount` nicht als vollständiger Reisepreis beschriftet (FED-020).
  4. Status wird nie nur über Farbe vermittelt; die Angebotsliste ist per Tastatur und Screenreader bedienbar (FED-010).
  5. Ein abgelaufenes Angebot wird nicht unter unverändertem Frontendpreis neu gekauft.
  6. Bei `accessibility_confirmed = false` zeigt UI-02 „Barrierefreiheit unbestätigt" und nie eine bestätigte Zugänglichkeit; `NOT_GUARANTEED` wird als „Anschluss nicht garantiert" ausgewiesen (FED-010).
  7. Externe Ticketabschnitte stehen in UI-03 vor dem Kaufbutton und verlangen eine eigene ausdrückliche Bestätigung (`acknowledged_external_leg_ids`); die Pflichtzustimmung ist nicht mit einer Marketingeinwilligung kombiniert (Kap. 06 §6.3) (FED-020).
  8. Ein bekannter externer Preis wird getrennt als „separat zu zahlen" ausgewiesen und nicht kassiert; je Kette ist erkennbar, ob Buchung, Zahlung und Ticket über die Plattform, extern oder gar nicht verfügbar sind (FED-020).
  9. Ein `external_booking_uri` ohne HTTPS oder mit ungeprüftem Ziel wird nicht ausgegeben.
  10. Die Bildschirme erfüllen 200-%-Textvergrößerung und Reflow bei 320 CSS-Pixeln; Mandant/Dienst, fachlicher Status und Datenstand sind benannt (Kap. 06 §6.1).
- **Tests:** positiv: Zwei-Betreiber-Angebot, gemischtes Angebot mit bekanntem und unbekanntem externem Preis. negativ: abgelaufenes Angebot, unbestätigte Barrierefreiheit, externer Link mit `http`-Ziel, fehlende Quittierung des externen Abschnitts. konkurrierend: Angebot läuft während der Anzeige in UI-02/UI-03 ab – der Kaufversuch führt zu `OFFER_EXPIRED` und neuer Suche, nicht zu einem Kauf zum alten Preis. Referenztests: ermöglicht E2E-087, PH-AT-006.
- **Abhängigkeiten:**
  - intern: WP-M6a-09, WP-M6a-10
  - extern: WP-M2-35, WP-M2-36, WP-M2-37, WP-M2-41, WP-M2-42, WP-M2-43 (Passenger-Web UI-01 bis UI-05 als Grundlage)
  - extern: WP-M6c-08 (Mehrparteien-Checkout in UI-03 (Zahlungsauswahl, Rechnungsausweis))
- **Entscheidungen:** D-01 (Rechnungsaussteller, Verkäufer je Dienst) und D-03 blockieren nur die Produktivschaltung des Checkout-Wortlauts.
- **Größe:** M
- **Nicht im Umfang:** Zahlungsabwicklung im Checkout (M6c); Reisebegleitung UI-04 (WP-M6a-18, WP-M6a-22).

### WP-M6a-12 – Pseudonyme Föderationsreferenzen und minimierter Betreiberpayload

- **Ziel:** Fahrgäste und Buchungen tragen über Mandantengrenzen nur pseudonyme, paarweise verschiedene Referenzen; Partner erhalten ausschließlich den minimal erforderlichen Datensatz.
- **Anforderungen:** `LH-I.5-FED-013`, `LH-I.5-FED-027`
- **Liefergegenstände:**
  - Backend: Erzeugung opaker Föderationsreferenzen (`journey_leg.federation_ref`) und des global pseudonymen Reisebezugs; Einbindung der je Partner eigenen Fahrgastaliasse und des Reise-/Segment-Relays mit Ablaufzeit aus dem Projektionsbaustein von WP-M6b-09 in jeden Adapteraufruf (Hold, Confirm, Status, Cancel) und jedes ausgehende Ereignis; an A und B ausgegebene Aliasse sind paarweise verschieden (Kap. 02 §3). Schemaprüfung jedes ausgehenden Payloads am Adapterausgang (fail closed).
  - Backend: Übertragener Inhalt des minimalen Betreiberpayloads: Segmentreferenz, Partner-Fahrgastalias, Start-/Zielhaltestelle, Zeitfenster, Personenzahl und Kapazitätsmerkmale, Boarding-Code, erforderliche Unterstützung ohne Diagnose, Kontaktrelay oder freigegebener Notkontakt, vertraglich nötiger Tarif-/Ticketnachweis. Feldfilter gegen `cooperation_grant.allowed_fields`.
  - Datenbank: `passenger_private` (`subject_token`), `journey_leg` (`federation_ref` eindeutig je Mandant); Aliasablage (siehe Offene Punkte 5).
  - Ereignisse/Outbox: Outbound-Projektionen mit Empfängerbindung und minimiertem Payload.
  - Konfigurationsparameter: `sharing_visibility_level` („Grundsatz Datenminimierung").
- **Detailverträge vorab:** JSON-Schema des Betreiberpayloads mit `additionalProperties: false`; Ableitungsverfahren und Rotationsregel der Aliasse; Liste ausgeschlossener Felder (volle Reisekette, Fahrgastkonto, Kartentoken, Kreditkartendaten, fremde Rechnungen, Vorgeschichte).
- **Akzeptanzkriterien:**
  1. Ein Testmitschnitt aller Aufrufe an `/v1/partner/*` und aller Outbound-Ereignisse einer Zwei-Betreiber-Reise enthält keine vollständigen Zahlungsdetails, kein Kartentoken und keine Felder außerhalb des Schemas (FED-013).
  2. Betreiberinterne IDs (Passagier-, Buchungs-, Fahrzeug-ID) erscheinen in keiner Föderationsantwort als globale personenbezogene ID (FED-027).
  3. Die an Betreiber A und Betreiber B übermittelten Fahrgastaliasse derselben Reise sind verschieden und nicht ineinander überführbar (FED-027).
  4. Ein nicht im Grant erlaubtes Feld wird nicht übertragen, auch wenn es intern vorhanden ist (FED-013).
  5. Unterstützungsbedarf wird ohne Diagnose übertragen.
  6. Logs, Traces, Metriken und Dead-Letter-Einträge der Partneraufrufe enthalten weder Kontaktdaten noch Unterstützungsmerkmale noch Zahlungsreferenzen (FED-013).
  7. Ein Payload, der das Schema verletzt, verlässt den Adapter nicht; der Vorfall wird als Sicherheitsereignis ohne Fremdinhalte protokolliert.
- **Tests:** positiv: Zwei-Betreiber-Reise, Schemaprüfung des Mitschnitts. negativ: Versuch, mit Alias A Daten bei B abzufragen (404); Feld außerhalb `allowed_fields`. konkurrierend: parallele Aliaserzeugung für denselben Fahrgast und Partner liefert genau einen Alias. Referenztests: ermöglicht PH-AT-002.
- **Abhängigkeiten:**
  - intern: WP-M6a-02
  - extern: WP-M1-31, WP-M1-46 (Feldschutz für PII, Audit ohne Fremdinhalte)
  - extern: WP-M6b-05, WP-M6b-09 (Projektionsbaustein „minimaler Betreiberpayload", Partner-Fahrgastalias, Kontaktrelay und Feldfilter gegen den Grant (WP-M6b-09, WP-M6b-05))
- **Entscheidungen:** D-01 (datenschutzrechtliche Verantwortlichkeit) und D-11 (Aufbewahrung) blockieren nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Aufbau von Projektionsbaustein, Aliasverfahren und Relay selbst (WP-M6b-09); Übergabeprojektion für Leitstellen (WP-M6a-21); Lösch- und Auskunftsjobs (M8).

### WP-M6a-13 – Saga Teil 1: Hold und Reisekette mit Betreiberreferenzen

- **Ziel:** Ein akzeptiertes Angebot wird als persistente Saga in gehaltene Segmente bei mehreren Betreibern überführt; die Reise ist ein Auftrag mit je Segment eigener Betreiberreferenz und eigenem Status.
- **Anforderungen:** `LH-I.5-FED-002`, `LH-I.5-FED-007`
- **Liefergegenstände:**
  - API: `/v1/federation/offers/{offer_id}/hold` (`HoldRequest`, `Idempotency-Key`, `If-Match`; `202` mit Operation und `Location`), `/v1/operations/{operation_id}`, `/v1/federation/journeys/{journey_id}` (`Journey`, `JourneyLeg`).
  - Backend: Journey-Orchestrator: Intent und deterministischer Operationsschlüssel werden vor jedem externen Schritt persistiert; Erfolg/Fehler und Outbox-Ereignis in einer lokalen Transaktion; Partneraufrufe nie in offener SQL-Transaktion. Zustände Journey-Buchung `HOLDING`, `HELD`, Segment-Buchung `OFFERED`, `HOLDING`, `HELD`, `FAILED`, `INFORMATION_ONLY`, `EXTERNAL_REQUIRED`; Operation `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`. Parallele Holds für unabhängige Segmente; frühestes Partnerablaufdatum bestimmt die gemeinsame Frist. `JourneyOffer.booking_policy` (`COMPENSATE_ON_FAILURE` als Standard, `ALLOW_PARTIAL_WITH_EXPLICIT_CONSENT` nur mit `partial_booking_consent`) wird an der Journey gebunden. Hold nur beim Betreiber, der das Angebot abgegeben hat (Eigentümer der Kapazität). `correlation_id`, Saga-/Operations-ID, `leg_id` und Partner-Operationsreferenz werden je Schritt gespeichert und per `traceparent` an den Partner weitergereicht (Kap. 02 §7.1, Kap. 05 §5.1). Grant- und Subjektprüfung (`booking_subject`) werden beim Hold erneut ausgeführt.
  - Datenbank: `journey` (`UNIQUE(tenant_id,quote_id)`), `journey_leg` (`operator_ref`, `service_ref`, `federation_ref`, `capability`, `state`, `contract_version_id`, `origin_snapshot`, `destination_snapshot`, Zusagefenster), `operation` (Lease, `fencing_token`), `saga_step` (`partner_command_ref` eindeutig), `idempotency_record`, `outbox`.
  - Oberfläche: UI-03 Zustand „Plätze werden reserviert" mit Countdown der bestätigten gemeinsamen Restfrist; UI-04 zeigt je Leg Betreiberreferenz und Status.
  - Kennzahlen: Ereignisse für `Partnerannahmequote`.
- **Detailverträge vorab:** Zustandsautomaten Journey-Buchung, Segment-Buchung und Operation für die Hold-Phase; Regel für `partial_booking_consent` (Standard `false`) und quittierte externe Legs.
- **Akzeptanzkriterien:**
  1. Start und Ziel zweier Segmentbuchungen bei verschiedenen Unternehmen ergeben eine zusammenhängende Reisekette mit gemeinsamem Übergabeort (`Transfer.hub_id`) (FED-002).
  2. Ein Kundenauftrag lässt sich über `journey` → `journey_leg.federation_ref` auf zwei unterschiedliche Betreiberbuchungen zurückverfolgen; jede Teilbuchung hat Betreiberreferenz, globalen Reisebezug und eigenen Status (FED-007).
  3. Lehnt B den Hold ab, während A hält, wird A freigegeben; Journey `COMPENSATING`, danach `FAILED`; keine Zahlung wird gestartet (Kap. 02 §7.4).
  4. Zwei verschiedene Idempotenzschlüssel auf derselben Offer-Revision erzeugen keine zweite Reise (`409 OPERATION_IN_PROGRESS`).
  5. Gleicher Schlüssel mit anderem Fingerprint ergibt `409 IDEMPOTENCY_CONFLICT`.
  6. Hold-Erfolg wird nicht als abgeschlossene Buchung dargestellt.
  7. Bei zwei konkurrierenden Angeboten am selben Hub wird ausschließlich beim Betreiber des gewählten Angebots gehalten und gebucht; beim nicht gewählten Betreiber entsteht weder Hold noch Buchung (E2E-085, zweiter Teil) (FED-007).
  8. Eine Journey oder Operation eines anderen Mandanten oder Fahrgasts ist nicht lesbar und nicht veränderbar (`404 NOT_FOUND`), auch mit manipulierter `tenant_id` in Body, Pfad oder Header und nach Connection-Pool-Wiederverwendung (FED-007).
  9. Die Wiederholung eines bereits angenommenen Hold-Kommandos mit gleichem Schlüssel liefert auch nach Angebotsablauf dieselbe Operation und nicht `OFFER_EXPIRED` (Kap. 05 §5.1).
  10. Externe und Informationssegmente werden nur mit Quittierung (`acknowledged_external_leg_ids`) übernommen und erhalten `EXTERNAL_REQUIRED` bzw. `INFORMATION_ONLY`; für sie entsteht kein Hold.
  11. Ist die kürzeste Partnerfrist kleiner als das Commit-Restbudget (Planungsdefault 15 s), wird der Hold abgelehnt oder neu geplant.
- **Tests:** positiv: beide Holds erfolgreich, Journey `HELD`. negativ: B lehnt ab, Angebot abgelaufen (`OFFER_EXPIRED`), fehlender `If-Match` (428), veraltete Revision (412), Partner vor Annahme nicht erreichbar (`503 PROVIDER_UNAVAILABLE`), fremde Journey-/Operation-ID (404, semantischer Contract-Fall „unzulässige Fremd-ID"), manipulierte `tenant_id`, abgelaufene Vertretung, fehlende Quittierung eines externen Legs. konkurrierend: Doppelklick mit gleichem Schlüssel, zwei Schlüssel parallel, Workerabbruch zwischen Hold A und Hold B. Referenztests: ermöglicht E2E-085, E2E-086, PH-AT-005; trägt zu PH-AT-011 bei.
- **Abhängigkeiten:**
  - intern: WP-M6a-04, WP-M6a-07, WP-M6a-09, WP-M6a-12
  - extern: WP-M1-02, WP-M1-13, WP-M1-14, WP-M1-15 (`operation`-Ressource mit Lease/Fencing, Outbox-Relay, Idempotenzregister, einheitliches Fehlermodell, Trace-Korrelation)
  - extern: WP-M2-42, WP-M2-43, WP-M7a-11 (Passenger-Web UI-03/UI-04 als Grundlage, Vertretungen)
- **Entscheidungen:** D-04 (Teilkauf) blockiert nur die Produktivschaltung von `partial_booking_consent=true`.
- **Größe:** L
- **Nicht im Umfang:** Zahlung, Confirm, Kompensation nach Bestätigung (WP-M6a-14); Reconciliation (WP-M6a-15).

### WP-M6a-14 – Saga Teil 2: Confirm, Kompensation und Fehlerzustände

- **Ziel:** Eine gehaltene Reise wird nur vollständig bestätigt; scheitert ein Segment, werden bestätigte Segmente und Zahlungsautorisierung nach Policy kompensiert und der Kunde informiert.
- **Anforderungen:** `LH-I.5-FED-006`
- **Liefergegenstände:**
  - API: `/v1/federation/journeys/{journey_id}/confirm` (`ConfirmRequest` mit maximal akzeptiertem Plattformbetrag; Abweichung `409 REQUOTE_REQUIRED`), `PaymentAction` bei SCA.
  - Backend: Confirm-Phase mit allen Zuständen aus Kap. 02 §7.3: Journey `ACTION_REQUIRED`, `CONFIRMING`, `RECONCILING`, `CONFIRMED`, `PARTIAL`, `COMPENSATING`, `FAILED`; Segment `CONFIRMING`, `UNKNOWN`, `CONFIRMED`, `FAILED`. Fehlervertrag §7.4 vollständig: B lehnt nach A-Bestätigung ab, Confirm-Timeout → `UNKNOWN`/`RECONCILING`, Zahlungsablehnung, PSP-Timeout, Capture-Fehler nach Bestätigung, Hold-Ablauf während SCA. Standardpolicy „nur vollständige buchbare Kette bestätigen". Confirmreihenfolge bevorzugt vertraglich gut kompensierbare Segmente. Mindestens 15 s Restbudget vor Beginn der Commitphase (Planungsdefault). `coverage = PLATFORM_SEGMENTS_ONLY` bei Informations- oder externen Segmenten.
  - Datenbank: `saga_step` (Aktion, Zustand, `partner_result_ref`, `attempts`), `journey` (`revision`), `payment_intent` (lesend/über Modul-API).
  - Ereignisse/Outbox: Reise bestätigt, Kompensation gestartet/abgeschlossen, Kundennachricht bei Teilfehler.
  - Oberfläche: UI-03 Zustände „Holdverlust, SCA, Preisänderung, Doppelklick"; „Bestätigung wird geprüft" ohne Erfolgs- oder Ablehnungsbehauptung; UI-04 Zustand `UNKNOWN`.
  - Konfigurationsparameter: `partner_liability_policy` („Verbindlich für existierende Buchungen").
- **Detailverträge vorab:** Zustandsautomaten Confirm-Phase je Dimension; Kompensationstabelle je Fehlerfall aus §7.4; Modul-API zum Zahlungsmodul (Authorize, Void, Status); Wortlaut der Kundennachrichten.
- **Akzeptanzkriterien:**
  1. Scheitert Segment 2 nach Bestätigung von Segment 1, wird Segment 1 gemäß Policy storniert, der unbestätigte Hold freigegeben und die Autorisierung aufgehoben; es verbleibt keine unbemerkte Teilbuchung oder Zahlung, und der Kunde erhält eine Nachricht (FED-006).
  2. Jede Kompensationsaktion und jeder Fehlerzustand ist in `saga_step` nachvollziehbar; `FAILED` verschleiert kein weiter bestehendes zahlungswirksames Segment, sondern führt zu `MANUAL_REVIEW` (FED-006).
  3. `CONFIRMED` wird erst gesetzt, wenn alle plattformbuchbaren Segmente bestätigt sind und die Zahlungsvoraussetzung erfüllt ist (FED-006).
  4. Läuft der B-Hold während der Zahlungsfreigabe ab, wird kein abgelaufener Platz bestätigt; A wird freigegeben und die Autorisierung abgewickelt oder ein neues Angebot mit neuer Zustimmung angeboten.
  5. Ein Confirm-Timeout führt zu Segment `UNKNOWN` und Journey `RECONCILING`; der Button erzeugt keine zweite Reise.
  6. Bei gemischter Reise lautet die Bestätigung „Plattformabschnitte bestätigt; externes Ticket erforderlich", nie „gesamte Reise gebucht".
  7. Ein kostenpflichtig verbleibendes Teilsegment (`PARTIAL`) entsteht nur mit vorab akzeptierter kommerzieller Regel und `partial_booking_consent`; ohne sie wird vollständig kompensiert. Können die Partner weder holden noch sicher kompensieren, ist die Kombination nicht als Komplettkauf buchbar (Kap. 02 §7.4) (FED-006).
  8. Scheitert eine Kompensation, entsteht ein sichtbarer Klärfall mit zuständigem Operator, Frist und Kundennachricht (`MANUAL_REVIEW`); die finanzielle Exposition ist ausgewiesen (PH-AT-008, Variante nicht stornierbar) (FED-006).
  9. Grant- und Subjektprüfung werden unmittelbar vor dem Confirm erneut ausgeführt; eine doppelte SCA-Rückkehr startet keine neue Autorisierung; rohe Zahlungsdaten erreichen die API nie.
  10. Fristen werden gegen Serverzeit mit definiertem Sicherheitsabstand geprüft; eine Preis- oder Umstiegsänderung fließt nicht still in die alte Zusage ein (PH-AT-010).
- **Tests:** positiv: beide Segmente bestätigt, eine Zahlung. negativ: B lehnt ab (kostenfrei kompensierbar und nicht stornierbar), Zahlungsablehnung, Betrag weicht ab (`409 REQUOTE_REQUIRED`), PSP-Timeout (Zahlung `UNKNOWN`, kein neuer Charge-Key), Capture-Fehler nach Bestätigung, Kompensation schlägt fehl, Confirm auf fremde Journey (404). konkurrierend: doppelte SCA-Rückkehr, paralleles Confirm und Hold-Ablauf, zwei Worker mit altem und neuem Fencing-Token. Referenztests: besteht mit M6c E2E-089, PH-AT-008, PH-AT-010; ermöglicht PH-AT-005; semantische Contract-Fälle „SCA länger als Hold" und „Confirm-Timeout mit spät positivem Status".
- **Abhängigkeiten:**
  - intern: WP-M6a-13
  - extern: WP-M2-29, WP-M2-30 (PSP-Adapter mit Zahlungsautomat (Authorize, Void, Status))
  - extern: WP-M6c-07, WP-M6c-14 (Mehrparteien-Checkout, Capture-Regel, Erstattung bei bereits erfolgtem Capture)
  - extern: WP-M2-32, WP-M2-34 (Betriebsnachrichten (Push/SMS/E-Mail))
- **Entscheidungen:** D-03 (Capturezeitpunkt, Geldfluss) und D-04 (nicht stornierbare Segmentkosten, Teilkauf) blockieren die Produktivschaltung; Bau mit Favorit „nur vollständige Kette" und Testvertrag.
- **Größe:** L
- **Nicht im Umfang:** automatischer Abgleich nach `UNKNOWN` (WP-M6a-15); Kundenstorno (WP-M6a-22); Mehrparteienzahlung selbst (M6c).

### WP-M6a-15 – Reconciliation-Worker, Wiederanlauf und Leitstellenalarm

- **Ziel:** Unbestimmte Ergebnisse werden über die unveränderte Operationsreferenz abgeglichen, nach Prozessneustart fortgesetzt und bei anhaltender Unklarheit an die Leitstelle eskaliert, ohne je zu Erfolg oder Fehler verkürzt zu werden.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: Reconciliation-Worker mit Lease und Fencing auf `operation`; begrenztes exponentielles Backoff mit Jitter (Planungsdefault 1/2/4/8 s, danach partnervertragskonforme Intervalle); Statusabfrage über `/v1/partner/reservations/{id}/status` mit stabiler Command-ID; Dead-letter bleibt bearbeitbar und wird mit Originalschlüssel erneut verarbeitet.
  - Backend: Operationszustände `RECONCILING`, `MANUAL_REVIEW`; fehlt dem Partner Status- oder Deduplikationsfähigkeit, kein automatischer erneuter nicht-idempotenter Kauf, sondern `MANUAL_REVIEW` und Betriebsalarm. Verspätetes positives Confirm nach begonnenem Abbruch erzeugt einen gezielten Stornoauftrag.
  - Datenbank: `operation` (`next_attempt_at`, `lease_until`, `fencing_token`, `last_error_code`, `deadline_at`), `saga_step`; `business_alert`, `alert_policy_revision` für den Leitstellenalarm. Invariante „Bestätigung/PSP-Retry exakt gleiche Operation" (Kap. 04 §4.5).
  - Konfigurationsparameter: `partner_retry_policy`, `alert_escalation_delay_min`. Planungsdefault: Leitstellenalarm nach 60 s unbestimmtem Buchungszustand als Eskalationsfrist, keine automatische Ablehnung.
- **Detailverträge vorab:** Backoff-Regel als `partner_retry_policy`-Schema; Zustandsautomat Operation; Alarmdefinition (Schwelle, Empfänger, Dedupe).
- **Akzeptanzkriterien:**
  1. Partner bestätigt, Antwort geht verloren: die Statusabfrage mit ursprünglicher Referenz findet die eine Reservierung; es entsteht kein neuer Kauf und genau eine Zahlung.
  2. Workerabbruch nach erfolgreichem externem Confirm vor lokalem Commit: nach Lease-Ende übernimmt ein neuer Worker, der alte kann nicht mehr fortschreiben.
  3. Ohne Statusfähigkeit des Partners endet der Abgleich in `MANUAL_REVIEW`; die Reise wird nie automatisch „nicht gebucht" oder „vollständig erstattet".
  4. Der Ablauf des ursprünglichen Holds gilt nicht als Beweis des Nichterfolgs.
  5. Nach der konfigurierten Frist existiert genau ein Leitstellenalarm je Operation (Dedupe `(policy,event,recipient)`, Kap. 04 §4.4); der Alarm ist eine Eskalation, keine automatische Ablehnung.
  6. Ein Outbox-Replay verändert keine bereits abschließend verarbeiteten Geschäftsvorfälle (PH-AT-009).
  7. Der Worker läuft mit explizitem Tenantkontext je Operation und iteriert begrenzt pro Mandant; er sieht ohne Kontext keine Zeile (Kap. 02 §4.2).
  8. Der Fahrgast sieht während des Abgleichs „Bestätigung wird geprüft" mit letztem Stand; es wird weder Erfolg noch Ablehnung behauptet (Kap. 06 §6.4).
- **Tests:** positiv: verlorene Antwort wird abgeglichen. negativ: Partner ohne Statuslookup, Dead-letter-Wiederaufnahme. konkurrierend: zwei Worker auf derselben Operation, Neustart in jedem Saga-Schritt (Kap. 02 §10). Referenztests: besteht PH-AT-007, PH-AT-009; ermöglicht E2E-090, E2E-104.
- **Abhängigkeiten:**
  - intern: WP-M6a-14
  - extern: WP-M1-14, WP-M1-15 (`operation` mit Lease/Fencing, Outbox-Relay)
  - extern: WP-M3-52 (grundlegende Alarme und Arbeitsliste der Leitstelle)
- **Entscheidungen:** keine für den Bau; partnervertragliche Abgleichintervalle folgen D-08.
- **Größe:** M
- **Nicht im Umfang:** Bedienoberfläche des Klärfalls (WP-M6a-16); PSP-Abgleich (M6c).

### WP-M6a-16 – UI-08 Reise-/Saga-Klärarbeitsplatz

- **Ziel:** Die Leitstelle kann unklare Reisen einsehen, den Abgleich erneut auslösen, gezielt kompensieren oder eine manuelle Entscheidung dokumentieren, ohne ungeprüft Erfolg zu behaupten.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Oberfläche: UI-08 (Reise-/Saga-Klärung), primäre Aktion „Statusabgleich / Kompensation", Zustände „Partner unerreichbar, Nachweis fehlt, Eskalation". Anzeige nach Kap. 06 §6.5: Saga-Schritte, letzte bestätigte Ergebnisse, Operations-/Partnerreferenz, Alter, nächste automatische Prüfung, Zuständigkeit. Rohpayloads mit PII bleiben gesperrt.
  - API: Lese- und Aktionsendpunkte für Klärfälle (in Kap. 05 nicht benannt, siehe Offene Punkte 7); `/v1/operations/{operation_id}`.
  - Backend: Aktionen „Status erneut abgleichen", „gezielte Kompensation", „dokumentierte manuelle Entscheidung" mit Begründung und Audit; kein freier Übergang „als erfolgreich markieren" ohne Nachweis.
  - Datenbank: `audit_event` für jede manuelle Aktion.
- **Detailverträge vorab:** OpenAPI der Klärfall-Endpunkte; Rollen- und Objektberechtigung; Nachweisarten, die eine manuelle Entscheidung zulassen.
- **Akzeptanzkriterien:**
  1. Ein Fall in `MANUAL_REVIEW` erscheint mit Zuständigkeit, Alter und nächster Prüfung in UI-08.
  2. Eine manuelle Entscheidung ohne Nachweis und Begründung wird abgewiesen.
  3. Ein Nutzer eines anderen Mandanten sieht den Fall nicht (404).
  4. Alle Aktionen sind per Tastatur und Screenreader ausführbar.
  5. Die Zustände „Partner unerreichbar", „Nachweis fehlt" und „Eskalation" sowie die leere Arbeitsliste sind jeweils eigens dargestellt; Rohpayloads mit PII sind auch für berechtigte Rollen nicht einsehbar.
  6. Jede Aktion erzeugt ein `audit_event` mit Akteur, Begründung und `correlation_id`; eine „gezielte Kompensation" läuft über dieselbe Saga und denselben Operationsschlüssel, nicht an ihr vorbei.
- **Tests:** positiv: erneuter Abgleich löst den Fall. negativ: fehlende Rolle (403), fehlender Nachweis, Versuch „als erfolgreich markieren" ohne Nachweis, Abruf eines Rohpayloads. konkurrierend: zwei Bearbeiter entscheiden gleichzeitig (ETag, 412).
- **Abhängigkeiten:**
  - intern: WP-M6a-15
  - extern: WP-M1-07, WP-M1-08, WP-M1-43, WP-M1-46 (Rollen- und Objektberechtigung, Designsystem, Audit)
  - extern: WP-M3-36 (Operator-Web UI-07 als Rahmen)
- **Entscheidungen:** D-04 (welche Fälle die Leitstelle entscheiden darf) blockiert die Produktivschaltung der manuellen Entscheidung.
- **Größe:** M
- **Nicht im Umfang:** Finanzklärung im Clearing UI-16 (M6c).

### WP-M6a-17 – Signierte, idempotente Partnerereignisse

- **Ziel:** Eingehende Partnerereignisse werden nur signiert angenommen, je Ereignis-ID genau einmal fachlich verarbeitet und überschreiben keinen neueren Zustand.
- **Anforderungen:** `LH-I.5-FED-018`
- **Liefergegenstände:**
  - API: `/v1/federation/partners/{partner_id}/events` (`PartnerEvent`, `PartnerEventData`, `EventReceipt`); Header `X-Partner-Key-Id`, `X-Partner-Timestamp`, `X-Partner-Signature` (HMAC-SHA256 über Zeitstempel und Rohbody); Zeitfenster ±300 s (Planungsdefault); identisches Duplikat `200 ALREADY_RECEIVED`, gleiche ID mit anderem Payload `409` mit Sicherheitsalarm und Quarantäne, neues Ereignis `202` erst nach dauerhaftem Inbox-Commit.
  - Backend: Ereignistypen laut `PartnerEvent.event_type`: `partner.offer_held`, `partner.booking_confirmed`, `partner.booking_canceled`, `partner.booking_failed`, `partner.leg_delayed`; `schema_version` `1.0`. Signatur über den Rohbody, nicht nach JSON-Reformatierung; TLS zusätzlich Pflicht; keine Ausweitung des Replayfensters bei Uhrfehler.
  - Backend: konstantzeitliche Signaturprüfung, maximale Körpergröße, Partnerbindung, zulässige Aussteller-`tenant_id` als Metadatum (keine Autorisierungsquelle), Schlüsselrotation mit begrenztem Übergang; Verarbeitung mit Aggregatversion; Versionslücken lösen Statusabgleich aus; widersprüchliche Nachrichten erzeugen einen Klärfall; unbekannte Schema-/Signaturversionen werden isoliert.
  - Datenbank: `inbox` (Unique-Key mindestens `(partner_id, event_id)` plus Payloadhash, siehe Offene Punkte 4).
  - Ereignisse/Outbox: Sicherheitsereignis bei Signatur- oder Payloadkonflikt.
- **Detailverträge vorab:** Ereignistypkatalog und `schema_version`; Zuordnung Ereignis → Zustandsübergang; Secret-Referenz je Partner (keine Secretwerte in der Datenbank).
- **Akzeptanzkriterien:**
  1. Ein doppelt zugestelltes Ereignis löst genau einen Zustandsübergang und keine doppelte Umbuchung oder Erstattung aus (FED-018).
  2. Ein Ereignis ohne gültige Signatur, mit abgelaufenem Zeitstempel oder fremder Partnerbindung wird abgewiesen und erzeugt keinen Schreibeffekt (FED-018).
  3. 100 identische Webhooks erzeugen einen Inbox-Eintrag und eine fachliche Wirkung (FED-018).
  4. Ein verspätetes `confirmed` nach erfolgreichem `cancelled` bucht nicht rückwärts, sondern erzeugt einen Klärfall.
  5. Gleiche Ereignis-ID mit verändertem Payload wird quarantänisiert und alarmiert.
  6. Während einer Schlüsselrotation sind alter und neuer Schlüssel begrenzt verifizierbar; ein entzogener Schlüssel wird sofort abgewiesen.
  7. Dieselbe `event_id` von zwei verschiedenen Partnern kollidiert nicht (Schlüssel je Partner); ein Ereignis zu einem Segment, das dem sendenden Partner nicht gehört, hat keinen Schreibeffekt (FED-018).
  8. `202` wird erst nach dauerhaftem Inbox-Commit gesendet; ein Absturz vor dem Commit führt bei erneuter Zustellung zu genau einer Wirkung.
- **Tests:** positiv: neues signiertes Ereignis. negativ: falsche Signatur, Replay außerhalb des Zeitfensters, manipulierte `tenant_id`, unbekannte Schemaversion, unbekannter Ereignistyp, übergroßer Body, reformatierter Body mit alter Signatur, Ereignis für fremdes Segment, Quotenüberschreitung (429). konkurrierend: 100 parallele identische Zustellungen; Revision 8 vor Revision 7. Referenztests: besteht E2E-090; ermöglicht E2E-104, PH-AT-007; semantische Contract-Fälle „100 identische Webhooks" und „Manipulation tenant_id".
- **Abhängigkeiten:**
  - intern: WP-M6a-13
  - extern: WP-M1-12, WP-M1-14, WP-M1-16 (Inbox, Webhook-Grundlage, Sicherheitsereignisse)
  - extern: WP-M6b-26 (Partnerzugänge, HMAC-Partnerschlüssel im Secret Manager, Rotation, Quoten und Schlüsselentzug (WP-M6b-26))
- **Entscheidungen:** D-08 (Signaturverfahren je realem Partner) blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** ausgehende Webhooks und Abonnements (`/v1/webhook-subscriptions`, M6b); PSP-Ereignisse (`/v1/payments/provider-events/{provider}`).

### WP-M6a-18 – Globaler Reisestatus und Ausführungsstatus

- **Ziel:** Die Reise führt einen eigenen Buchungs- und Ausführungsstatus unabhängig von den Betreiberstatus und wird bei jeder Segmentänderung neu bewertet.
- **Anforderungen:** `LH-I.5-FED-008`
- **Liefergegenstände:**
  - Backend: Journey-Ausführung `NOT_STARTED`, `IN_PROGRESS`, `TRANSFER_AT_RISK`, `DISRUPTED`, `COMPLETED`, `PARTIALLY_COMPLETED`, `ABANDONED`; Segment-Ausführung `PLANNED`, `APPROACHING`, `BOARDED`, `COMPLETED`, `NO_SHOW`, `DISRUPTED`. Betreiberstatus führt; keine aus der Oberfläche abgeleitete Erfüllung. Bestätigte Buchung bleibt als historischer Vertrag bestehen. Abschluss der Buchungsdimension: Journey-Buchung und Segment-Buchung `COMPLETED` (Kap. 02 §7.3). Ausführungsstatus fremder Segmente stammen aus Partnerereignissen bzw. Statusabfrage (Ereignistypen für Einstieg/Abschluss/No-Show fehlen im Vertrag, siehe Offene Punkte 22), die des eigenen Betreibers aus den Fahrerereignissen.
  - Datenbank: `journey_leg.execution_state`; Ausführungsstatus der `journey` (siehe Offene Punkte 2); `journey.revision` je Neubewertung.
  - API: `/v1/federation/journeys/{journey_id}` mit getrennten Feldern für Buchungsstatus, `execution_state`, Segmentstatus und Zahlungsprojektion (Kap. 05 §5.9).
  - Ereignisse/Outbox: „Reisestatus geändert" an Fahrgastkanal und berechtigte Leitstellen.
  - Oberfläche: UI-04 (Aktive/alte Reisen) zeigt Gesamtstatus, Segmentstatus und letzten Datenstand getrennt.
  - Kennzahlen: Ereignisse für `Ende-zu-Ende-Reiseabschluss`.
- **Detailverträge vorab:** Ableitungsregel Segmentstatus → Reisestatus als Entscheidungstabelle; Zustandsautomaten der beiden Ausführungsdimensionen.
- **Akzeptanzkriterien:**
  1. Meldet ein Segment Verspätung, wird die Gesamtreise neu bewertet, der globale Status aktualisiert und die Änderung mit neuer Revision gespeichert (FED-008).
  2. Der globale Buchungsstatus bleibt `CONFIRMED`, während die Ausführung `TRANSFER_AT_RISK` oder `DISRUPTED` zeigt (FED-008).
  3. Ein unbestimmtes Segmentergebnis geht nicht in einem Erfolgs- oder Fehlerboolean verloren.
  4. Ein älteres Ereignis setzt den Reisestatus nicht zurück.
  5. Jede der sieben Journey-Ausführungszustände ist über die Entscheidungstabelle aus den Segmentzuständen erreichbar und getestet, einschließlich `PARTIALLY_COMPLETED` (ein Segment gefahren, eines ausgefallen) und `ABANDONED` (FED-008).
  6. Buchungsstatus, `execution_state`, Segmentstatus und Zahlungsprojektion sind in API und UI-04 getrennte Felder; UI-04 nennt den letzten Datenstand und vermittelt Status nicht nur über Farbe (FED-008).
  7. Nach Wiederanlauf mit zwischenzeitlich eingegangenen Partnerereignissen und nachgereichten Offline-Fahrerereignissen ist die Reihenfolge konsistent; es entsteht keine doppelte Buchung oder Abrechnungswirkung (E2E-104).
- **Tests:** positiv: Segment A abgeschlossen, B gestartet → `IN_PROGRESS`. negativ: Ereignis für fremde Reise, veraltete Aggregatversion, No-Show auf Segment A bei bestätigtem Segment B. konkurrierend: gleichzeitige Statusereignisse beider Betreiber. Referenztests: ermöglicht E2E-104, AT-14.
- **Abhängigkeiten:**
  - intern: WP-M6a-14, WP-M6a-17
  - extern: WP-M3-29 (Fahrerereignisse und Segmentausführung des lokalen Betreibers)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Berechnung des Anschlussrisikos (WP-M6a-19).

### WP-M6a-19 – Anschlussüberwachung, Warnung und Ersatzangebot

- **Ziel:** Bei Verspätung eines Segments wird der Anschlussstatus ereignisbasiert neu berechnet, dem Fahrgast transparent angezeigt und nach Vertrag ein Ersatzangebot vorgeschlagen.
- **Anforderungen:** `LH-I.5-FED-012`, `LH-6.11-PTI-006`
- **Liefergegenstände:**
  - Backend: Anschlussrisiko gegen gespeicherte Zusageversion und aktuelle, qualitätsbewertete ETA (Kap. 02 §8); `TransferRisk` (`state` `UNKNOWN`, `ON_TRACK`, `AT_RISK`, `MISSED`; `predicted_margin_seconds`, `observed_at`, `reason_code`) mit Restreserve nach kompletter Mindestwechselzeit, auch negativ (Kap. 05 §5.9). Auslöser sind `partner.leg_delayed`, Fahrerereignisse/ETA des eigenen Betreibers und das Ereignis „Fahrplanabweichung" des ÖPNV-Feeds. Zulässige Optionen: vertraglich erlaubtes Warten, alternative Verbindung, Ersatzpartnerangebot, Kundenstorno, Leitstellenentscheidung. Ersatzsegment gilt erst nach neuem Angebot, Zustimmung und bestätigter Buchung als zugesagt; alte Segmenthistorie wird nicht überschrieben.
  - API: `transfer_risks` in `/v1/federation/journeys/{journey_id}`; Ersatzsuche über `/v1/federation/journeys/plan`.
  - Ereignisse/Outbox: „Anschluss gefährdet", „Anschluss verpasst"; Fahrgastbenachrichtigung.
  - Oberfläche: UI-04 Warnung mit Datenalter und Ursache sowie Alternativen; UI-07 Filter „Anschlussrisiken" und Aktionen „Ersatzoptionen prüfen", „Fahrgast informieren", „Fall eröffnen" (Kap. 06 §6.5).
  - Datenbank: `business_alert` für den Folgeprozess.
  - Konfigurationsparameter: `advance_arrival_buffer_min`, `interchange_min_time_min`, `transfer_min_time_min`, `eta_max_data_age_sec`, `alert_escalation_delay_min`.
  - Kennzahlen: Ereignisse für `Nicht erreichte Anschlüsse`, `Partnerübergabe pünktlich`.
- **Detailverträge vorab:** Berechnungsregel Restreserve und Schwellen; Zustandsautomat Anschluss; Definition des Folgeprozesses (siehe Offene Punkte 9); Wortlaut der Warnung.
- **Akzeptanzkriterien:**
  1. Eine Verspätung von 12 Minuten am Zubringersegment zeigt im Testfall einen gefährdeten Anschluss und verfügbare Alternativen (FED-012).
  2. Die Neuberechnung wird durch das Verspätungsereignis ausgelöst, nicht erst durch Seitenaufruf (FED-012).
  3. Sobald der definierte Puffer unterschritten ist, erscheint im Fahrgastclient eine Warnung; oberhalb der Schwelle erscheint keine (PTI-006).
  4. Die Warnung nennt Datenalter und Ursache; bei fehlenden Echtzeitdaten wird das Risiko als unbekannt gekennzeichnet (PTI-006).
  5. Ein nicht barrierefreier Ersatzweg wird einem Fahrgast mit Rollstuhlbedarf nicht angeboten; stattdessen zulässige Alternative oder Leitstellenfall.
  6. Ein Ersatz mit höherem Preis wird nicht ohne neue Zustimmung gebucht.
  7. Ein Ersatzpartnerangebot wird nur vorgeschlagen, wenn Vertrag und Grant des Ersatzpartners es erlauben; andernfalls erscheinen nur die übrigen zulässigen Optionen (Warten nach Vertrag, alternative Verbindung, Kundenstorno, Leitstellenfall) (FED-012).
  8. `TransferRisk.state` durchläuft `ON_TRACK` → `AT_RISK` → `MISSED` nachvollziehbar; fehlende oder veraltete Daten ergeben `UNKNOWN`, nie `ON_TRACK` (PTI-006).
  9. Die Warnung wird im Fahrgastclient per Live-Region angekündigt, nicht nur über Farbe vermittelt und zusätzlich als Betriebsnachricht zugestellt; die Leitstelle sieht denselben Fall im Filter „Anschlussrisiken" von UI-07 (PTI-006, AT-14).
  10. Mehrere Verspätungsereignisse desselben Segments erzeugen genau einen offenen Folgeprozess (`business_alert`), nicht je Ereignis einen neuen.
- **Tests:** positiv: 12 Minuten Verspätung, Alternative vorhanden. negativ: Verspätung unter Schwelle, veraltete ETA, kein zulässiger Ersatz, Ersatzpartner ohne Grant, Verspätungsereignis für fremde Reise. konkurrierend: Verspätungsereignis parallel zu Kundenstorno. Referenztests: besteht AT-14; Nachweis „Verzögerung und nicht barrierefreier Ersatz" (Kap. 02 §10).
- **Abhängigkeiten:**
  - intern: WP-M6a-08, WP-M6a-09, WP-M6a-18, WP-M6a-27
  - extern: WP-M3-34, WP-M3-36, WP-M3-54 (Livebetrieb UI-07, Standortalter, Störungsablauf)
  - extern: WP-M4-61, WP-M4-62 (ETA-Grundmodell)
  - extern: WP-M2-32, WP-M2-34 (Betriebsnachrichten)
  - extern: WP-M6b-14, WP-M6b-27 (Ereignis „Fahrplanabweichung" aus dem GTFS-RT-Feed (WP-M6b-27); adressierte Störungsmeldung an betroffene Partner (WP-M6b-14))
- **Entscheidungen:** D-04 (wer trägt Ersatzbeförderung und Gebühren, welche Fälle die Leitstelle entscheidet) blockiert die Produktivschaltung von Ersatzangeboten; Warnung ist ohne Beschluss lieferbar.
- **Größe:** M
- **Nicht im Umfang:** vertragliche Anschlussgarantie (WP-M6a-20); Preisdelta und Stornokosten des Ersatzes (M6c).

### WP-M6a-20 – Konfigurierbare Anschlussgarantie

- **Ziel:** Nur vertraglich freigegebene Anschlüsse werden als garantiert bezeichnet; Geltungsbereich, Schwellen und Eskalationsfolge sind konfigurierbar, und eine Verletzung löst einen definierten Folgeprozess aus.
- **Anforderungen:** `LH-6.11-PTI-013`
- **Liefergegenstände:**
  - Backend: Anschlussregel als eigenes Vertragsmerkmal (Kap. 02 §6 Nr. 4) mit Geltungsbereich (Dienst, Hub, Relation), Schwellenwerten und Eskalationsfolge; `Transfer.assurance = CONTRACTUAL_PROTECTION` nur bei hinterlegter und wirksamer Regel, sonst `NOT_GUARANTEED` (Kap. 05 §5.2).
  - Datenbank: Regel in `partner_contract_version.terms` bzw. `config_revision`; `transfer_rule`-Bezug; `business_alert`.
  - Oberfläche: UI-02/UI-03/UI-04 Kennzeichnung „garantiert" nur bei wirksamer Regel; UI-18 bzw. UI-17 Pflege der Regel (Zuordnung siehe Offene Punkte 10).
  - Ereignisse/Outbox: „Anschlussgarantie verletzt" mit Regelrevision.
  - Konfigurationsparameter: `partner_liability_policy`, `alert_escalation_delay_min`.
- **Detailverträge vorab:** JSON-Schema der Anschlussregel; Definition „Verletzung"; Eskalationsfolge als Schrittliste; `policy_revision`-Format.
- **Akzeptanzkriterien:**
  1. Die Oberfläche bezeichnet einen Anschluss nur dann als garantiert, wenn eine wirksame, vertraglich freigegebene Regel für genau diesen Geltungsbereich existiert (PTI-013).
  2. Geltungsbereich, Schwellenwerte und Eskalationsfolge sind ohne Codeänderung konfigurierbar und versioniert (PTI-013).
  3. Eine Verletzung löst den definierten Folgeprozess aus und ist mit Regelrevision nachvollziehbar (PTI-013).
  4. Nach Ablauf oder Widerruf der Regel zeigen neue Angebote `NOT_GUARANTEED`; bestätigte Reisen behalten ihre gebundene Zusageversion.
  5. Bietet der Betreiber keine Garantie an (Regel fehlt), bleibt es ohne Fehlermeldung beim Default `NOT_GUARANTEED`; `Transfer.policy_revision` ist nur bei `CONTRACTUAL_PROTECTION` gesetzt und verweist auf die wirksame Regelrevision (PTI-013).
  6. Die Regel wird nur von berechtigten Rollen mit Freigabe geändert; jede Änderung ist auditiert.
- **Tests:** positiv: garantierter Anschluss, Verletzung → Folgeprozess. negativ: Regel außerhalb Geltungsbereich, abgelaufene Regel. konkurrierend: Regeländerung parallel zur Angebotsbildung.
- **Abhängigkeiten:**
  - intern: WP-M6a-04, WP-M6a-19
  - extern: WP-M1-19, WP-M1-20, WP-M1-22 (Konfigurationsregister mit Wirksamkeitsintervallen und Freigabe)
- **Entscheidungen:** D-04 blockiert die Produktivschaltung jeder Garantie (technischer Default ist transparente Nichtgarantie); Bau mit Testregel möglich.
- **Größe:** M
- **Nicht im Umfang:** finanzielle Folgen einer Verletzung (Erstattung, Kostenträger; M6c).

### WP-M6a-21 – Übergabeprojektion für Partnerleitstellen

- **Ziel:** Die Leitstelle des Anschlussbetreibers sieht die für die Anschlusssicherung nötigen Segmentinformationen des Zubringers, nicht dessen Fahrgastakte.
- **Anforderungen:** `LH-I.5-FED-026`
- **Liefergegenstände:**
  - Backend: Übergabeprojektion mit genau `transfer_hub_id`, voraussichtlicher Ankunft, zugesagtem Übergabefenster, erforderlicher Umsteigezeit, notwendiger Assistenz und Aktualitätsindikator (Kap. 02 §3); beim Eigentümer erzeugt und unter Empfängerkontext gespeichert (Kap. 02 §4.2). Keine dynamische „alle Kooperationspartner"-RLS-Policy.
  - Ereignisse/Outbox: adressierte Aktualisierung der Projektion bei ETA-Änderung; Sperre bei Grantentzug.
  - Oberfläche: UI-07 (Operator Livebetrieb) Detailpanel „Übergabe" mit Partnerstatus und Kontaktrelay; Zustand „keine Fremdrechte".
  - Datenbank: Projektionstabelle im Empfängerkontext (in Kap. 04 §4.4 nicht benannt, siehe Offene Punkte 5).
- **Detailverträge vorab:** JSON-Schema der Übergabeprojektion mit `additionalProperties: false`; Berechtigungsregel (Rolle, Dienst, Zweck); Aktualisierungs- und Löschregel.
- **Akzeptanzkriterien:**
  1. Leitstelle B sieht für ihr Anschlusssegment die erwartete Übergabezeit aus Segment A (FED-026).
  2. Leitstelle B kann weder die Fahrgastakte noch andere Segmente, Reisen oder Fahrzeuge von A abrufen; der Zugriff liefert 404 (FED-026).
  3. Eine Karte mit Partnerpositionen erscheint nur bei ausdrücklichem, zweckgebundenem Grant.
  4. Nach Grantentzug ist die Projektion für den operativen Zugriff gesperrt, auch über Cache und Event-Subscription.
  5. Nur Leitstellenrollen von B mit Recht auf den betroffenen Dienst sehen die Projektion; andere Rollen in B erhalten 403 bzw. 404 (FED-026).
  6. Die Projektion zeigt ihren Aktualitätsindikator; bei veralteten Daten wird die Übergabezeit als veraltet gekennzeichnet, nicht als aktuell. Der Zustand „keine Fremdrechte" ist in UI-07 eigens dargestellt.
- **Tests:** positiv: Übergabezeit sichtbar und aktualisiert. negativ: Zugriff auf A-Rohdaten, Drittpartner C erhält kein Ereignis, B-Nutzer ohne Dienstrecht, Projektion nach Reiseende. konkurrierend: Grantentzug parallel zur Projektionsaktualisierung. Referenztests: besteht mit M6b PH-AT-002.
- **Abhängigkeiten:**
  - intern: WP-M6a-12, WP-M6a-18
  - extern: WP-M3-36 (Operator-Livebetrieb UI-07)
  - extern: WP-M6b-05, WP-M6b-09, WP-M6b-13 (Projektionsmechanik, Grantprüfung, adressierte Ereignisse, Föderationsübersicht in UI-07 (WP-M6b-05, WP-M6b-09, WP-M6b-13))
- **Entscheidungen:** D-01 (Datenverantwortlichkeit) blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Partnerübernahme anfragen und Fremdfahrzeugeinsatz (M6b, `resource_release`).

### WP-M6a-22 – Storno, Teilstorno und teilweise gefahrene Reisen

- **Ziel:** Abbrüche und Teilstornos einer Reisekette folgen definierten Regeln je Partner und Gesamtreise und erzeugen nachvollziehbare Entgelt- und Erstattungspositionen.
- **Anforderungen:** `LH-I.5-FED-024`
- **Liefergegenstände:**
  - API: `/v1/federation/journeys/{journey_id}/cancellation-quotes` (`CancellationQuoteRequest`, `CancellationQuote`, `CancellationLine` mit Gebühr/Erstattung je Segment, `remaining_journey_viable`), `/v1/federation/journeys/{journey_id}/cancel` (`CancelRequest` referenziert genau diese Quote; `409 REQUOTE_REQUIRED` bei geänderten Regeln).
  - Backend: Storno-Saga mit Journey `CANCEL_PENDING`, `CANCELED`, `PARTIAL`; Segment `CANCEL_PENDING`, `CANCELED`; nur in der Quote genannte Legs werden storniert; bereits gefahrene Leistungen bleiben sichtbar; Storno A bestätigt und B unklar → Einzelstatus und Abgleich (Kap. 02 §7.4). Regeln aus der eingefrorenen Vertragsrevision (`journey_leg.contract_version_id`).
  - Ereignisse/Outbox: Storno-/Refund-Ereignis mit Bezug auf die Originalposition; Übergabe der Entgelt- und Erstattungspositionen an das Zahlungsmodul.
  - Oberfläche: UI-04 zweistufiges Storno (Angebot → ausdrückliche Bestätigung), Zustände „UNKNOWN, Teilstorno, verspätete Rückmeldung"; „Erstattung beauftragt" bis zur PSP-Bestätigung.
  - Konfigurationsparameter: `partner_liability_policy`.
- **Detailverträge vorab:** Stornoregel-Schema je Partner und Gesamtreise (`CancellationTerms`); Zustandsautomat Storno; Positionsschema Entgelt/Erstattung; Regel für teilweise gefahrene Reisen.
- **Akzeptanzkriterien:**
  1. Eine teilweise abgesagte Reisekette erzeugt je betroffenem Segment nachvollziehbare Entgelt- und Erstattungspositionen mit Regel- und Vertragsrevision (FED-024).
  2. Für Abbruch, Erstattung und teilweise gefahrene Reise existiert je Partner und für die Gesamtreise eine hinterlegte Regel; fehlt sie, ist die Kombination nicht als Komplettkauf buchbar (FED-024).
  3. Die Quote bewirkt keine Stornierung; nach Ablauf oder Regeländerung wird `REQUOTE_REQUIRED` geliefert statt stiller Mehrbelastung.
  4. Ein Teilstorno weist aus, dass die ursprüngliche Kette nicht mehr vollständig ist, und verlangt Zustimmung zum Rest.
  5. Nur bestätigte Erstattungen werden als abgeschlossen angezeigt; die Cancel-Operation bleibt offen, bis die Zahlungsreversierung nachgewiesen ist.
  6. Storniert der Betreiber bzw. Partner ein bestätigtes Segment von sich aus (`partner.booking_canceled`), wird das Segment `CANCELED`, die Reise neu bewertet, der Kunde informiert und die Entgelt-/Erstattungsposition nach der eingefrorenen Vertragsregel erzeugt; das Ereignis bucht nicht still die ganze Reise ab (FED-024).
  7. Bei einer teilweise gefahrenen Reise bleibt die gefahrene Leistung als Entgeltposition bestehen; nur nicht erbrachte, stornierbare Segmente erzeugen Erstattungspositionen (FED-024).
  8. Ein fehlgeschlagenes Partnerstorno zeigt die verbleibende Verpflichtung und eröffnet einen Supportfall; Storno A bestätigt und B unklar führt zu Einzelstatus und Abgleich (Kap. 02 §7.4, Kap. 06 §6.4).
  9. Quote und Cancel verlangen den aktuellen Journey-ETag (`If-Match`) und einen `Idempotency-Key`; jede Stornoaktion ist mit Akteur (Fahrgast, Vertretung, Leitstelle) auditiert.
- **Tests:** positiv: B storniert, A bleibt. negativ: abgelaufene Quote, fremde Reise (404), Storno eines bereits gefahrenen Segments, Cancel mit Quote einer anderen Reise, fehlender `If-Match` (428), Partnerstorno schlägt fehl. konkurrierend: zwei parallele Stornos derselben Reise, Storno parallel zu spätem Confirm. Referenztests: besteht mit M6c PH-AT-014, PH-AT-008 (Variante Capture erfolgt); semantischer Contract-Fall „Storno unbekannt".
- **Abhängigkeiten:**
  - intern: WP-M6a-14, WP-M6a-15, WP-M6a-17
  - extern: WP-M6c-14, WP-M6c-16, WP-M6c-20 (Stornierungs- und Erstattungsregeln, Teilrefund, Refund-Obergrenzen, Korrekturbeleg, Provisionsrückrechnung (WP-M6c-14, WP-M6c-20))
  - extern: WP-M2-26 (lokales Storno im Betreiberkern)
- **Entscheidungen:** D-03 und D-04 blockieren die Produktivschaltung (Gebührenträger, nicht stornierbare Segmentkosten); Bau mit Testvertrag.
- **Größe:** L
- **Nicht im Umfang:** Geldbewegung und Settlement-Korrektur (M6c); Auslöser und Disposition eines Betreiberstornos wegen Fahrzeugausfall (M3/M7) – dessen Wirkung auf die Reisekette ist hier enthalten.

### WP-M6a-23 – Segmentbezogene Preisaufstellung mit Tarifgeber

- **Ziel:** Die Preisaufstellung einer Reisekette weist je Segment Tarifgeber, Ermäßigung und verrechnete Leistung aus und erlaubt die Kombination aus Einzeltickets, Zeitkarten und Partnertarifen.
- **Anforderungen:** `LH-I.5-FED-023`
- **Liefergegenstände:**
  - Backend: Zusammenführung der Partnerpreise im Angebot; `fare_components` als disjunkte additive Bestandteile (Kap. 05 §5.2); `entitlement_refs` als serverseitig geprüfte Zeitkarten/Ermäßigungen, keine frei vorgegebenen Rabatte (Kap. 05 §5.9); explizite Null bei gültigem Zeitkartenanspruch.
  - Datenbank: `fare_quote` (`pay_at_platform_minor`, `external_known_minor`, `external_unknown_leg_ids`, `tariff_revision_id`, `snapshot`), `fare_component`, `tariff_revision`, `journey_leg` (`gross_minor`, `currency`). Geld ausschließlich als `bigint` in kleinster Einheit.
  - API: `OfferLeg.fare_components`, `FareComponent`, `PriceSummary`.
  - Oberfläche: UI-02/UI-03 Preisaufstellung je Segment mit Tarifgeber und Ermäßigung.
- **Detailverträge vorab:** Schema `FareComponent` mit Tarifgeber, Ermäßigungsgrund und Leistungsbezug (siehe Offene Punkte 6); Kombinationsregeln Einzelticket/Zeitkarte/Partnertarif; Rundungsregel.
- **Akzeptanzkriterien:**
  1. Die Preisaufstellung weist je Segment Tarifgeber, Ermäßigung und verrechnete Leistung aus (FED-023).
  2. Eine Reise mit Zeitkarte auf Segment A und Einzelticket auf Segment B zeigt A mit expliziter Null und B mit Partnertarif; die Summe der Komponenten entspricht exakt `pay_at_platform` (FED-023).
  3. Steuer wird nicht zusätzlich auf brutto ausgewiesene Komponenten addiert.
  4. Ein Subjekt- oder Berechtigungswechsel erfordert ein neues Angebot.
  5. Kundenzahlung und B2B-Provision sind getrennte Kontexte: eine vom Betreiber getragene Provision erscheint nicht als Kundenkomponente; alle Beträge eines Checkouts haben dieselbe Währung und denselben Exponenten (Kap. 05 §5.2).
  6. Die Preisaufstellung des bestätigten Auftrags entspricht dem eingefrorenen Angebot (`content_hash`); eine spätere Tarifrevision ändert sie nicht (FED-023).
- **Tests:** positiv: zwei Tarife, zwei Beförderer. negativ: nicht verifizierte Berechtigung, Währungsmischung in einem Checkout. konkurrierend: Tarifrevision wechselt zwischen Angebot und Hold (`REQUOTE_REQUIRED`). Referenztests: ermöglicht E2E-093, PH-AT-005.
- **Abhängigkeiten:**
  - intern: WP-M6a-09
  - extern: WP-M2-07, WP-M2-09 (Tarifrevision und unveränderlicher `fare_quote`)
  - extern: WP-M6c-06, WP-M6c-09, WP-M6c-10, WP-M6c-16 (Preiskomponenten mit Rollen und centgenauer Rabattverteilung, Tarifregeln P2, Guthaben/Gutscheine, Kundenbeleg (WP-M6c-06, WP-M6c-16))
- **Entscheidungen:** D-01 und D-03 (Rechnungsaussteller, Verkäufer) blockieren nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Prüfung externer Tickets (WP-M6a-24); Clearingpositionen (M6c).

### WP-M6a-24 – Prüfung externer Ticketberechtigungen

- **Ziel:** Eine externe Ticket- oder Verbundberechtigung kann über einen Adapter geprüft werden und führt zum vorgesehenen Preisvorteil, ohne dass externe Ticketlogik nachgebaut wird.
- **Anforderungen:** `LH-6.11-PTI-008`
- **Liefergegenstände:**
  - Adapter/Worker: Berechtigungsprüf-Adapter hinter der Adapter-SPI; Ergebnis gültig/ungültig/unbekannt mit Gültigkeitszeitraum und Quelle; keine Nachbildung von Tarifzonen oder Ticketregeln.
  - Backend: das Prüfergebnis wird an das Aggregat Tarifberechtigung aus WP-M6c-11 übergeben und dort als `entitlement_refs`-fähige, serverseitig geprüfte Berechtigung geführt; Quote und Journey speichern die Berechtigungsrevision (Kap. 05 §5.9). Es werden nur die zur Prüfung nötigen Ticketmerkmale übertragen und keine vollständigen Ticketdaten gespeichert.
  - Datenbank: `adapter_capability` (Fähigkeit „Ticketprüfung"); Ablage der Prüfergebnisse im Aggregat Tarifberechtigung (WP-M6c-11; siehe Offene Punkte 11).
  - Oberfläche: UI-05 (Konto/Berechtigung) – Hinterlegung und Prüfstatus liefert WP-M6c-11; dieses WP ergänzt den Prüfweg „externe Ticketprüfung" mit den Zuständen gültig, ungültig, unbekannt und „Verifikation fehlt"; UI-02 zeigt den angewandten Preisvorteil.
- **Detailverträge vorab:** SPI-Vertrag der Prüfung; Zuordnungsregel Berechtigung → Preisvorteil als Tarifregel; Verhalten bei „unbekannt" (kein Vorteil, sichtbarer Hinweis).
- **Akzeptanzkriterien:**
  1. Ein gültiges Testticket führt im Angebot zum entsprechenden Preisvorteil (PTI-008).
  2. Ein ungültiges, abgelaufenes oder nicht prüfbares Ticket führt zu keinem Preisvorteil und einem sichtbaren Hinweis (PTI-008).
  3. Der Preisvorteil ergibt sich aus einer internen Tarifregel auf das Prüfergebnis, nicht aus kopierter externer Ticketlogik (PTI-008).
  4. Ein Adapterausfall blockiert die Angebotsbildung nicht.
  5. Ein Ticket eines anderen Fahrgasts oder ein frei im Request vorgegebener Rabatt führt zu keinem Preisvorteil; läuft das Ticket zwischen Angebot und Fahrt ab, gilt die im Detailvertrag festgelegte Regel und der Fall ist sichtbar (PTI-008).
- **Tests:** positiv: gültiges Testticket. negativ: ungültig, abgelaufen, Adapter-Timeout, fremdes Ticket, Ticket außerhalb des räumlichen/zeitlichen Geltungsbereichs laut Prüfantwort. konkurrierend: dieselbe Berechtigung parallel in zwei Angeboten.
- **Abhängigkeiten:**
  - intern: WP-M6a-05, WP-M6a-23
  - extern: WP-M2-36 (Konto/Berechtigung UI-05)
  - extern: WP-M6c-11 (Aggregat Tarifberechtigung, Prüfadapter-Schnittstelle, Tarifregel „Berechtigungsart → Tarifbestandteil" (WP-M6c-11))
- **Entscheidungen:** D-08 (realer Tarifverbund, Prüfschnittstelle) blockiert die Produktivschaltung; Bau gegen Sandbox-Prüfdienst.
- **Größe:** M
- **Nicht im Umfang:** Ticketausgabe; Verkauf externer Tickets; Nachweisprüfung durch Sachbearbeitung und Berechtigungen für Schüler/Unternehmen (WP-M6c-11).

### WP-M6a-25 – Ausführungsnachweis und Abrechnungsbezug je Segment

- **Ziel:** Jede Vermittlungsleistung ist einer vertraglichen Abrechnungsbeziehung und einem Ausführungsnachweis zugeordnet, sodass der monatliche Partnerbericht mit den durchgeführten Segmenten abgleichbar ist.
- **Anforderungen:** `LH-I.5-FED-025`
- **Liefergegenstände:**
  - Backend: bei Abschluss, No-Show oder Storno eines Segments wird ein Ausführungsnachweis mit `journey_leg`-Bezug, `contract_version_id`, Betreiberreferenz und Ausführungsstatus erzeugt und als Ereignis an das Finanzmodul übergeben.
  - Datenbank: `journey_leg` (`contract_version_id`, `execution_state`), `settlement_line` (`leg_id`, `contract_version_id`, `creditor_ref`, `debtor_ref`; geschrieben vom Finanzmodul), `economic_event` (Bezug).
  - Ereignisse/Outbox: „Segment ausgeführt" mit Nachweisreferenz, genau einmal je Segment und Revision.
  - API: Abgleichsicht über `/v1/clearing/statements` (geliefert in M6c).
  - Konfigurationsparameter: `partner_settlement_model`.
- **Detailverträge vorab:** Schema des Ausführungsnachweises; Ereignisvertrag zum Finanzmodul; Abgleichregel Bericht ↔ Segmente (Schlüssel, Periode, Zeitzone).
- **Akzeptanzkriterien:**
  1. Jedes vermittelte Segment trägt eine Vertragsrevision und nach Ausführung einen Ausführungsnachweis; ein Segment ohne Vertragsbezug ist nicht bestätigbar (FED-025).
  2. Der monatliche Partnerbericht lässt sich zeilenweise mit den durchgeführten Segmenten abgleichen; Abweichungen werden ausgewiesen (FED-025).
  3. Wiederholte Zustellung des Ausführungsereignisses erzeugt keine doppelte Abrechnungsposition.
  4. Informations- und extern gebuchte Segmente erzeugen keine Abrechnungsposition.
  5. Der Ausführungsnachweis ist unveränderlich; eine Korrektur erfolgt als Gegenereignis mit Bezug auf den Ursprungsnachweis (Invariante „Gegenereignis statt Änderung", Kap. 04 §4.5).
  6. Jeder Partner sieht im Abgleich nur seine eigenen Segmente und Nachweise; der Nachweis enthält keine Fahrgastdaten über den Partner-Fahrgastalias hinaus (FED-025).
- **Tests:** positiv: zwei ausgeführte Segmente, Bericht stimmt. negativ: Segment storniert, No-Show, fehlender Nachweis. konkurrierend: doppeltes Ausführungsereignis parallel. Referenztests: ermöglicht E2E-093, PH-AT-005.
- **Abhängigkeiten:**
  - intern: WP-M6a-18
  - extern: WP-M6c-19, WP-M6c-23, WP-M6c-30 (Verteilungsabrechnung je Segment, Clearingdatensätze und monatliche Gegenberichte, `/v1/clearing/statements`, UI-16 (WP-M6c-19, WP-M6c-23, WP-M6c-30))
  - extern: WP-M4-33 (Kostenereignis/Journal (`economic_event`))
- **Entscheidungen:** D-03 (Geldfluss) und D-07 (interne Verrechnung) blockieren nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Berechnung von Provision, Auszahlung und Rückforderung (M6c).

### WP-M6a-26 – Fahrplanauskunftsadapter (GTFS statisch) mit Herkunftskennzeichnung

- **Ziel:** Haltestellen, Abfahrten und Reisevorschläge aus einem Fahrplanfeed stehen der Planung mit Quellenangabe als Informationssegmente zur Verfügung.
- **Anforderungen:** `LH-6.11-PTI-001`
- **Liefergegenstände:**
  - Adapter/Worker: `adapters/timetable-gtfs`: fachliche Auskunftsschicht auf dem von WP-M6b-27 gelieferten Feedabruf/-import (Abruf, Validierung, Feedisolation, Feedkonfiguration dort): Abbildung von Haltestellen, Kalender, Fahrten und Fahrzeiten auf das kanonische Fahrplanmodell der Adapter-SPI, Verbindungssuche für Reisevorschläge, Herkunftskennzeichnung je Objekt; Standardversion im Contract-Test fixiert.
  - Datenbank: `timetable_feed` (Quellenzeit, Lizenz, Staleness; Anlage in WP-M6b-02); Zuordnung importierter Haltestellen zu `stop_revision`/`transfer_hub`.
  - Backend: Bereitstellung der Verbindungen an den Journey Planner als `INFORMATION_ONLY`-Legs mit Herkunftskennzeichnung und Aktualitätsdatum.
  - API: Herkunft und Datenstand je Leg im Angebot (siehe Offene Punkte 6).
  - Oberfläche: UI-02 zeigt die Datenquelle des Abschnitts.
- **Detailverträge vorab:** DDL `timetable_feed` und Fahrplantabellen; Mappingregel GTFS → kanonisches Modell; Regel für Haltestellenabgleich; Lizenz- und Quellenfelder.
- **Akzeptanzkriterien:**
  1. Ein Testfeed erzeugt in der Suche eine korrekte Alternative samt ausgewiesener Datenquelle (PTI-001).
  2. Haltestellen, Abfahrten und Reisevorschläge tragen in API und Oberfläche eine Herkunftskennzeichnung (PTI-001).
  3. Ein fehlerhafter Feed wird abgewiesen, ohne den letzten gültigen Stand zu ersetzen.
  4. Ein Fahrplanfeed allein führt nie zu `PLATFORM_BOOKABLE`.
  5. Die Herkunftskennzeichnung nennt Quelle, Lizenzangabe und Datenstand; Daten zweier Mandanten mit eigenem Feed werden nicht vermischt (PTI-001).
  6. Eine Feed-Haltestelle ohne eindeutige Zuordnung zu `stop_revision`/`transfer_hub` wird nicht stillschweigend als Umstiegspunkt verwendet.
- **Tests:** positiv: Testfeed-Import, Alternative korrekt. negativ: Feed mit ungültigem Kalender, doppelte Kennungen, abgelaufene Gültigkeit. konkurrierend: zwei Importläufe desselben Feeds parallel. Referenztests: ermöglicht E2E-087, PH-AT-006.
- **Abhängigkeiten:**
  - intern: WP-M6a-02, WP-M6a-03, WP-M6a-05
  - extern: WP-M2-05 (Haltepunktrevisionen)
  - extern: WP-M6b-27 (GTFS-Feedabruf, Validierung, Feedkonfiguration und Feedliste in UI-17 (WP-M6b-27))
- **Entscheidungen:** D-08 (welche Feeds, Datenlizenzen) blockiert nur die Produktivschaltung realer Feeds.
- **Größe:** M
- **Nicht im Umfang:** Feedabruf, Feedverwaltung und Feedisolation (WP-M6b-27); Echtzeit (WP-M6a-27); NeTEx/SIRI/VDV (siehe Offene Punkte 12); eigener ÖPNV-Routingalgorithmus über das im Detailvertrag festgelegte Maß hinaus.

### WP-M6a-27 – Echtzeitverarbeitung (GTFS-RT) versioniert, unbekannt statt pünktlich

- **Ziel:** Echtzeitänderungen angeschlossener ÖPNV-Dienste werden versioniert mit Zeitstempel verarbeitet; fehlende Echtzeit wird als unbekannt dargestellt, und Import wie Aktualisierung sind durch Standardtests abgesichert.
- **Anforderungen:** `LH-6.11-PTI-005`, `LH-6.11-PTI-009`, `LH-I.5-FED-022`
- **Liefergegenstände:**
  - Adapter/Worker: fachliche GTFS-RT-Verarbeitung in `adapters/timetable-gtfs` (Verspätungen, Ausfälle) auf dem von WP-M6b-27 gelieferten Feedabruf: Versions- und Reihenfolgeregel mit Quellenzeit, Empfangszeit und Version; ältere Meldung überschreibt keine neuere.
  - Datenbank: `realtime_observation` (Quellenzeit, Empfangszeit, Staleness; Anlage in WP-M6b-02), Bezug zu `timetable_feed`.
  - Backend: Aktualitätsstatus je Verbindung (`STALE`, unbekannt); getrennte Felder für Soll-Zeit und Echtzeitprognose; Weitergabe an die Anschlussüberwachung.
  - Oberfläche: UI-02 und UI-04 unterscheiden Soll-Zeit von Echtzeitprognose; ohne Echtzeit erscheint „Echtzeit unbekannt", nicht „pünktlich".
  - Adapter/Worker: Import- und Aktualisierungstestsuite für Kennungen, Kalender, Fahrzeiten und Echtzeitabweichungen.
  - Konfigurationsparameter: `eta_max_data_age_sec` (Datenhöchstalter; Anwendbarkeit auf ÖPNV-Echtzeit siehe Offene Punkte 13).
- **Detailverträge vorab:** DDL `realtime_observation`; Versions- und Reihenfolgeregel (Quellenzeit plus Sequenz, nicht Zeitstempel allein); Staleness-Schwelle; API-Felder Soll/Prognose/Datenalter.
- **Akzeptanzkriterien:**
  1. Eine veraltete Fahrplanmeldung überschreibt keine aktuellere Meldung; beide bleiben mit Zeitstempel nachvollziehbar (PTI-005).
  2. Die Fahrgastoberfläche unterscheidet Soll-Zeit von Echtzeitprognose; fehlen Echtzeitdaten, wird „unbekannt" angezeigt (PTI-009).
  3. Import- und Aktualisierungstests prüfen Kennungen, Kalender, Fahrzeiten und Echtzeitabweichungen und laufen in CI (FED-022).
  4. Überschreitet das Datenalter die Schwelle, wird die Verbindung als veraltet gekennzeichnet.
  5. Zwei Meldungen mit gleichem Zeitstempel, aber unterschiedlicher Sequenz werden deterministisch geordnet; ein Zeitstempel in der Zukunft wird nicht als aktuellste Meldung übernommen (PTI-005).
  6. Die Unterscheidung Soll-Zeit/Echtzeitprognose/unbekannt ist als Text bzw. Symbol mit Textalternative umgesetzt, nicht nur als Farbe (PTI-009).
  7. Ein gemeldeter Ausfall der Anschlussfahrt wird an die Anschlussüberwachung weitergegeben und nicht als Verspätung null dargestellt.
- **Tests:** positiv: Verspätungsmeldung aktualisiert Prognose. negativ: ältere Meldung nach neuerer, Meldung für unbekannte Fahrt, Feedausfall, Zeitstempel in der Zukunft, Ausfallmeldung. konkurrierend: zwei Meldungen derselben Fahrt parallel. Referenztests: ermöglicht AT-14.
- **Abhängigkeiten:**
  - intern: WP-M6a-26
  - extern: WP-M6b-27 (GTFS-RT-Feedabruf, Staleness-Konfiguration und Ereignis „Fahrplanabweichung" (WP-M6b-27))
- **Entscheidungen:** D-08 (Standards je Partner) blockiert nur die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** NeTEx- und SIRI-Adapter als eigene Implementierung (siehe Offene Punkte 12); Risikobewertung des Anschlusses (WP-M6a-19).

### WP-M6a-28 – ÖPNV-Alternativen und intermodale Anschlussplanung

- **Ziel:** Fahrgäste sehen bei verfügbarer Datenlage ÖPNV-Alternativen mit getrennten Abschnitten, und eine intermodale Buchung richtet die Abholzeit am gewünschten Anschluss aus.
- **Anforderungen:** `LH-6.11-PTI-002`, `LH-6.11-PTI-003`
- **Liefergegenstände:**
  - Backend: Journey Planner bildet Alternativen aus Fußweg (`mode = WALK`, `fare_status = NOT_APPLICABLE`), ÖPNV (`BUS`, `RAIL`) und Shuttle (`ON_DEMAND`); Rückwärtsplanung vom Anschlussziel: Abholzeit so, dass planmäßige Anschlussabfahrt abzüglich Mindestumsteigezeit und Puffer erreicht wird; `time_mode = ARRIVE_BY` am Hub.
  - API: `/v1/federation/journeys/plan`; Angabe von Anschlussziel, Umsteigepuffer und gewünschter Ankunft am Hub (Ergänzung zu `PlanRequest`, siehe Offene Punkte 6).
  - Oberfläche: UI-01 Eingabe des Anschlusswunsches; UI-02 getrennte Darstellung der Abschnitte mit Fußweganteil.
  - Konfigurationsparameter: `advance_arrival_buffer_min`, `transfer_min_time_min`, `stop_walk_max_m`, `pickup_window_min`.
- **Detailverträge vorab:** OpenAPI-Ergänzung `PlanRequest`; Berechnungsregel Abholzeit mit Rechenbeispiel; Definition „verfügbare Datenlage".
- **Akzeptanzkriterien:**
  1. Bei verfügbarem Fahrplan enthält das Suchergebnis mindestens eine ÖPNV-Alternative zum On-Demand-Angebot (PTI-002).
  2. Der Reisevorschlag trennt Fußwege, ÖPNV- und Shuttle-Abschnitte als eigene Legs mit eigenem Verkehrsmittel (PTI-002).
  3. Die Abholzeit wird passend zur planmäßigen Anschlussabfahrt berechnet: zugesagte späteste Ankunft plus Mindestumsteigezeit plus Puffer liegt nicht nach der Anschlussabfahrt (PTI-003).
  4. Anschlussziel, Umsteigepuffer und gewünschte Ankunft am Hub sind als Eingaben wirksam und im Angebot nachvollziehbar (PTI-003).
  5. Ohne Fahrplandaten wird keine ÖPNV-Alternative erfunden; die fehlende Datenlage ist sichtbar.
  6. Bei Rollstuhlbedarf verwendet die Rückwärtsplanung die profilabhängige Umsteigezeit aus WP-M6a-08; die Unsicherheit nicht garantierter Fahrplandaten ist am Anschluss sichtbar (`NOT_GUARANTEED`) (PTI-003).
  7. Fußweg-Legs tragen keinen Preis (`NOT_APPLICABLE`) und überschreiten `stop_walk_max_m` nicht; die Darstellung der Abschnitte ist per Screenreader in chronologischer Reihenfolge lesbar (PTI-002).
- **Tests:** positiv: Rufbus zum Bahnhof mit Anschluss 10:10. negativ: Anschluss nicht erreichbar, kein Feed, Fußweg über `stop_walk_max_m`, gewünschte Ankunft in der DST-Lücke (`422`). konkurrierend: Fahrplanrevision wechselt zwischen Angebot und Hold (neues Angebot statt stiller Änderung). Referenztests: ermöglicht E2E-087.
- **Abhängigkeiten:**
  - intern: WP-M6a-08, WP-M6a-09, WP-M6a-26
  - extern: WP-M2-18, WP-M2-19, WP-M2-21, WP-M2-37, WP-M2-41 (lokale Angebotsbildung mit harten Zeitfenstern, Passenger-Web UI-01/UI-02)
- **Entscheidungen:** keine
- **Größe:** M
- **Nicht im Umfang:** Parallelfahrtenregel (WP-M6a-29); Anschlusswarnung zur Laufzeit (WP-M6a-19).

### WP-M6a-29 – Parallelfahrtenregel

- **Ziel:** On-Demand-Angebote, die eine verfügbare ÖPNV-Verbindung nach konfigurierten Kriterien parallel bedienen würden, werden durch eine zulässige Alternative ersetzt oder abgelehnt.
- **Anforderungen:** `LH-6.11-PTI-004`
- **Liefergegenstände:**
  - Backend: Regelprüfung in der Angebotsbildung mit konfigurierbaren Vergleichskriterien zu verfügbaren ÖPNV-Verbindungen; Ergebnis: zulässig, Alternative (z. B. Zubringer zum Hub) oder Ablehnung mit normiertem Grund.
  - Datenbank: Regel als versionierte Konfiguration in `config_revision`; Ablehnungsgrund in `travel_attempt.primary_rejection`.
  - API: Ablehnungsgrund in `PlanResponse.reject_reason` bzw. am Angebot (Ergänzung, siehe Offene Punkte 14).
  - Oberfläche: UI-01/UI-02 Hinweis mit nächster Handlung; UI-17 Pflege der Regel.
  - Konfigurationsparameter: neuer Parameter für die Parallelfahrtenregel (in `kataloge.csv` nicht vorhanden, siehe Offene Punkte 14).
- **Detailverträge vorab:** JSON-Schema der Regel mit Vergleichskriterien; Ablehnungscode; Wirkungsbericht bei Regeländerung.
- **Akzeptanzkriterien:**
  1. Ein Testfall mit gesperrter Parallelrelation liefert eine zulässige Alternative oder eine Ablehnung mit Grund, aber keine Direktfahrt (PTI-004).
  2. Die Vergleichskriterien sind konfigurierbar, versioniert und wirken ab Wirksamkeitsdatum; bestätigte Zusagen bleiben unverändert (PTI-004).
  3. Fehlen ÖPNV-Daten, greift das im Detailvertrag festgelegte Verhalten nachvollziehbar.
  4. Die Regel wird mit Wirkungsbericht und Freigabe veröffentlicht; die angewandte Regelrevision ist am Suchversuch gespeichert und die Ablehnung nennt dem Fahrgast eine nächste Handlung (PTI-004).
  5. Fahrgäste mit Unterstützungsbedarf werden nicht auf eine nicht barrierefreie ÖPNV-Verbindung verwiesen; ob die Regel für sie gilt, ist Teil der Vergleichskriterien (Offene Punkte 14).
- **Tests:** positiv: nicht gesperrte Relation bleibt buchbar. negativ: gesperrte Relation, Regel ohne Kriterien wird nicht veröffentlicht. konkurrierend: Regelwechsel zwischen Angebot und Hold.
- **Abhängigkeiten:**
  - intern: WP-M6a-28
  - extern: WP-M1-19, WP-M1-20, WP-M1-22, WP-M1-23 (Konfigurationsregister mit Freigabe und Wirkungsbericht)
- **Entscheidungen:** keine aus D-01 bis D-12 ausdrücklich; die Vergleichskriterien sind fachlich vom Auftraggeber bzw. Aufgabenträger freizugeben (siehe Offene Punkte 14).
- **Größe:** M
- **Nicht im Umfang:** rechtliche Bewertung der Genehmigungslage.

### WP-M6a-30 – Eignungsprüfung TOMP und OSDM (Architekturprotokoll)

- **Ziel:** Für jeden Partnertyp ist dokumentiert, welche TOMP- und OSDM-Flows unterstützt werden, wo Abweichungen bestehen und welche Partnerfähigkeiten fehlen.
- **Anforderungen:** `LH-I.5-FED-021`
- **Liefergegenstände:**
  - Adapter/Worker: keine Laufzeitsoftware; Architekturprotokoll unter `docs/` mit Abbildung der Adapter-SPI-Operationen auf TOMP- und OSDM-Flows, gepinnter Standardversion, Abweichungen und fehlenden Fähigkeiten je Partnertyp.
  - Datenbank: Vorschlagswerte für `adapter_capability` je Partnertyp.
- **Detailverträge vorab:** Protokollvorlage aus WP-M6a-01; Liste der zu prüfenden Partnertypen.
- **Akzeptanzkriterien:**
  1. Das Architekturprotokoll weist je Partnertyp unterstützte Flows, Abweichungen und fehlende Partnerfähigkeiten nach und ist reviewt (FED-021).
  2. Für jeden Flow ist angegeben, ob Hold/Confirm, Statuslookup und Deduplikation dem internen Vertrag genügen; ein TOMP- oder OSDM-Label allein gilt nicht als Nachweis (FED-021).
  3. Die verwendeten Standardversionen sind benannt und im Contract-Test fixierbar.
- **Tests:** positiv: Review des Protokolls gegen die Capability-Matrix. negativ: entfällt (Dokument); die Aussagen werden in WP-M6a-31 durch Konformitätstests belegt.
- **Abhängigkeiten:**
  - intern: WP-M6a-05
- **Entscheidungen:** D-08 liefert Pilotpartner und Versionen; ohne Beschluss wird gegen die öffentlich dokumentierten Standards geprüft. Blockiert nicht das Protokoll, aber dessen Partnerbezug.
- **Größe:** S
- **Nicht im Umfang:** Adapterimplementierung (WP-M6a-31).

### WP-M6a-31 – TOMP-/OSDM-Adapter für buchbare Pilotpartner

- **Ziel:** Soweit ein Pilotpartner buchbar ist, existiert ein Adapter hinter derselben SPI, der die Konformitätssuite besteht; andernfalls bleibt der Partner `INFORMATION_ONLY` oder `EXTERNAL_BOOKING`.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: `adapters/partner-tomp` und `adapters/partner-osdm`, jeweils nur für die im Protokoll aus WP-M6a-30 als tragfähig bewerteten Flows; Standardrelease gepinnt; unbekannte Zustände fail closed.
  - Datenbank: `adapter_capability`-Einträge je Adapter und Dienst, zunächst deaktiviert.
  - Konfigurationsparameter: `partner_api_timeout_ms`, `partner_retry_policy`, `partner_offer_ttl_sec` je Partner.
- **Detailverträge vorab:** OpenAPI bzw. Mapping je Adapter; Konformitätstestliste; Abweichungsliste aus WP-M6a-30.
- **Akzeptanzkriterien:**
  1. Jeder gelieferte Adapter besteht die Lebenszyklus-Konformitätssuite aus WP-M6a-07 gegen die Partner-Sandbox oder einen standardkonformen Simulator.
  2. Fehlt Hold, Statuslookup oder Deduplikation, wird der betroffene Dienst nicht als `PLATFORM_BOOKABLE` freigeschaltet.
  3. Reale Partnerfristen, die kürzer als das Commit-Restbudget sind, führen zu Ablehnung oder Neuplanung.
- **Tests:** positiv: Konformitätssuite. negativ: Partner ohne Statusfähigkeit → `MANUAL_REVIEW`-Pfad. konkurrierend: doppelte Bestätigung des Partners.
- **Abhängigkeiten:**
  - intern: WP-M6a-05, WP-M6a-07, WP-M6a-30
- **Entscheidungen:** D-08 blockiert den Bau partnerspezifischer Teile (Pilotpartner, Versionen, Sandbox, Buchungsrechte); ohne Beschluss wird nur der interne Zweitbetreiber-Adapter geliefert (Umsetzungsplan §5). D-01 und D-03 blockieren die Produktivschaltung.
- **Größe:** L
- **Nicht im Umfang:** Adapter für Partner ohne nachgewiesene Buchungsrechte; proprietäre Partner-APIs ohne Auftrag.

### WP-M6a-32 – Partner-Sandbox-Modus und Freischaltung

- **Ziel:** Ein neuer Betreiber durchläuft vor der Freischaltung im Produktivverbund definierte Integrationsprüfungen in einem Sandbox-Modus; erst der Nachweis aktiviert die jeweilige Capability.
- **Anforderungen:** `LH-I.5-FED-028`
- **Liefergegenstände:**
  - Backend: Sandbox-Modus je Partner und Dienst: Sandbox-Buchungen sind von Produktivdaten getrennt gekennzeichnet, erzeugen keine echten Zahlungen, keine Clearingpositionen und keine Kennzahlenwirkung.
  - Adapter/Worker: Testlauf-Automat für Buchung, Verzögerung, Storno und Erstattung auf Basis des Konformitäts-Harness; Ergebnisprotokoll mit Build, Vertrags- und Konfigurationsrevision.
  - Datenbank: `adapter_capability` (Sandboxnachweis je Operation, Zeitpunkt, Protokollreferenz).
  - Oberfläche: UI-18 (Kooperation/Rollen) zeigt Capability-Teststatus getrennt vom Vertragsstatus (Kap. 06 §6.8); die Maske liefert WP-M6b-08, dieses WP liefert Daten, Testlauf-Auslösung und Protokollansicht.
- **Detailverträge vorab:** Testkatalog der Freischaltung; Regel „Nachweis → Capability aktiv"; Trennregel Sandbox/Produktion.
- **Akzeptanzkriterien:**
  1. Ein neuer Betreiber besteht in der Sandbox die definierten Tests für Buchung, Verzögerung, Storno und Erstattung; das Ergebnis ist protokolliert (FED-028).
  2. Ohne bestandenen Test bleibt die betroffene Capability deaktiviert; ein Aktivierungsversuch wird abgewiesen (FED-028).
  3. Sandbox-Vorgänge erscheinen in keinem Produktivbericht und lösen keine echte Zahlung aus.
  4. Ein fehlgeschlagener Einzeltest sperrt nur die zugehörige Operation, nicht den ganzen Partner.
  5. Sandbox- und Produktivzugang verwenden getrennte Zugangsschlüssel; ein Sandbox-Schlüssel wird an Produktivendpunkten abgewiesen und umgekehrt.
  6. Ändert sich die Adapterversion oder die gepinnte Standardversion, verfällt der Sandboxnachweis der betroffenen Operationen bis zum erneuten Durchlauf (Kap. 02 §9) (FED-028).
  7. Aktivierung und Deaktivierung einer Capability sind nur berechtigten Rollen möglich und mit Protokollreferenz auditiert.
- **Tests:** positiv: vollständiger Sandbox-Durchlauf, Capability wird aktivierbar. negativ: Erstattungstest schlägt fehl → Refund-Capability bleibt aus. konkurrierend: Aktivierung parallel zu laufendem Testlauf.
- **Abhängigkeiten:**
  - intern: WP-M6a-03, WP-M6a-05, WP-M6a-14, WP-M6a-19, WP-M6a-22
  - extern: WP-M6b-08, WP-M6b-26 (UI-18 als Rahmen (WP-M6b-08), getrennte Partnerzugänge (WP-M6b-26))
  - extern: WP-M6c-14 (Erstattungsablauf für den Erstattungstest (WP-M6c-14))
- **Entscheidungen:** D-08 (reale Partner-Sandboxes) blockiert nur die Anwendung auf reale Partner.
- **Größe:** M
- **Nicht im Umfang:** Vertragsverhandlung und Rechtsfreigabe; Lasttests gegen Partner.

### WP-M6a-33 – Exportadapter für Mobilitätsdatenpflichten

- **Ziel:** Die nach anwendbaren Mobilitätsdatenpflichten erforderlichen Datenkategorien werden über freigegebene Exportadapter fristgerecht bereitgestellt.
- **Anforderungen:** `LH-6.11-PTI-011`
- **Liefergegenstände:**
  - Adapter/Worker: Exportadapter für die im Detailvertrag festgelegten Datenkategorien und Formate auf der Exportprofil- und Exportlaufmechanik von WP-M6b-31 (dort Profil je Empfänger, Lauf, Validierung, Zustellung); dieses WP liefert die PTI-Datenkategorien, die Freigabe je Adapter/Kategorie und die Fristüberwachung; Protokoll von Zeitpunkt, Datenstand, Schema-/Policyversion und Checksumme (Kap. 04 §4.7).
  - Datenbank: `export_job`; Quellen `service`, `service_area`, `stop_revision`, `transfer_hub`.
  - Backend: Freigabe je Adapter und Datenkategorie; keine personenbezogenen Daten im Export.
  - API: `/v1/reports/jobs`, `/v1/reports/jobs/{id}` (asynchroner Exportlauf, Kap. 05 §5.7), soweit bereits vorhanden.
- **Detailverträge vorab:** Liste der anwendbaren Pflichten, Datenkategorien, Formate, Fristen und Zielstellen (im Pflichtenheft nicht benannt, siehe Offene Punkte 15); Exportschema; Freigaberegel.
- **Akzeptanzkriterien:**
  1. Ein fachlicher Test bestätigt die fristgerechte Veröffentlichung der erforderlichen Datenkategorien über einen freigegebenen Exportadapter (PTI-011).
  2. Ein nicht freigegebener Adapter oder eine nicht freigegebene Datenkategorie wird nicht exportiert (PTI-011).
  3. Jeder Exportlauf ist mit Datenstand und Checksumme protokolliert; ein wiederholter Lauf erzeugt keinen abweichenden Inhalt bei gleichem Datenstand.
  4. Der Export enthält keine Fahrgast-, Personal- oder Buchungsdaten.
- **Tests:** positiv: Exportlauf innerhalb der Frist. negativ: Frist überschritten → Alarm; fehlende Freigabe. konkurrierend: zwei Exportläufe derselben Periode parallel.
- **Abhängigkeiten:**
  - intern: WP-M6a-26
  - extern: WP-M1-07, WP-M1-11, WP-M1-46 (Rollen, Audit, Exportberechtigung)
  - extern: WP-M6b-31 (Exportprofile und Exportläufe je Empfänger (WP-M6b-31))
  - extern: WP-M8c-30 (Mobilitätsdatenexport für PBefG-/Mobilitätsdatenpflichten und Berichtsjobs (WP-M8c-30); Abgrenzung im Detailvertrag)
- **Entscheidungen:** keine aus D-01 bis D-12 ausdrücklich; die anwendbaren Pflichten sind rechtlich vom Auftraggeber zu benennen (siehe Offene Punkte 15). D-11 (Datenrechte) berührt die Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** Berichtswesen und KPI-Exporte (M8); rechtliche Prüfung der Pflichten.

### WP-M6a-34 – Gate-Nachweis M6a und semantische Contract-Fälle

- **Ziel:** Die auf M6a entfallenden Referenztests des M6-Gates sind mit Build, Evidenz und Entscheidung ausgeführt, und jede MUSS-Anforderung der Einheit hat einen grünen Test.
- **Anforderungen:** – (Enabler)
- **Liefergegenstände:**
  - Adapter/Worker: Akzeptanzszenarien unter `tests/acceptance` für E2E-085, E2E-086, E2E-087, E2E-088, E2E-089, E2E-090, E2E-104, AT-14, PH-AT-005, PH-AT-006, PH-AT-007, PH-AT-008, PH-AT-009, PH-AT-010 sowie die Zusatznachweise aus Kap. 02 §10.
  - Adapter/Worker: die föderationsbezogenen semantischen Contract-Fälle aus Kap. 05 §5.8 als CI-Pflicht: zwei Betreiber/letzter Platz, abgelaufene Holds, SCA länger als Hold, Confirm-Timeout mit spät positivem Status, Storno unbekannt, 100 identische Webhooks, Manipulation tenant_id, unzulässige Fremd-ID.
  - Kennzahlen: Nachweis, dass die Ereignisse für `Technische Partnerablehnungen`, `Nicht erreichte Anschlüsse`, `Partnerannahmequote`, `Partnerübergabe pünktlich`, `Ende-zu-Ende-Reiseabschluss` erzeugt werden (Berechnung in M8).
  - Datenbank: Negativtest mit mindestens zwei parallelen Transaktionen je berührter Invariante aus Kap. 04 §4.5: „Keine lokale Cross-Tenant-Referenz", „Kein Mandantenkontext → keine Zeile", „Gegenereignis statt Änderung", „Kapazität/Qualifikation/Fahrzeugfreigabe", „Grantwiderruf sofort wirksam", „Bestätigung/PSP-Retry exakt gleiche Operation".
  - Adapter/Worker: Zusatznachweise aus Kap. 02 §10 einzeln als Szenario: „Letzter Platz parallel", „Reise A→B", „A→Linienbus ohne Ticket-API", „Confirm angenommen, Antwort verloren", „Neustart in jedem Saga-Schritt", „Ablauf während SCA", „Widerruf während Buchung", „Vertragsablauf bei bestehender Zusage", „Verzögerung und nicht barrierefreier Ersatz", „Reconciliation bleibt unklar"; die drei Mandantentrennungsfälle („Mandant A/B, Drittpartner C", „Falscher Tenant in Body/URL/Cache", „Direktzugriff mit Runtime-DB-Rolle") führt WP-M6b-34, hier nur für die Föderationspfade wiederholt.
  - Adapter/Worker: Kalibrierlauf der Planungsbudgets unter `tests/performance` gegen Sandbox-Partner (Planungsdefaults 1.500 ms/3.000 ms/5.000 ms, Kap. 02 §6 Nr. 3) mit protokolliertem Ergebnis; kein Ersatz für den Referenzlasttest (Offene Punkte 23).
- **Detailverträge vorab:** Zuordnung Referenztest → WP → `source_key[]`; Evidenzformat nach Kap. 08 §8.4 (Build, Migrationsstand, Konfigurations-/Tarif-/Vertragsrevision, Seed, Rollen, Soll/Ist, Trace-IDs).
- **Akzeptanzkriterien:**
  1. Alle genannten Referenztests sind in `e2e.csv` mit Build-ID, Evidenz und Abnahmeentscheidung eingetragen.
  2. CI bricht ab, wenn eine als umgesetzt markierte MUSS-Zeile der Einheit keinen Testfall referenziert.
  3. Keine Partnerfähigkeit ist ohne Sandboxnachweis aktiv.
  4. Kein offener Mangel der Klasse A.
  5. SOLL-Abweichungen sind mit Begründung dokumentiert und dem Auftraggeber zur Freigabe vorgelegt.
  6. Jeder Referenztest ist genau einer führenden Einheit zugeordnet (M6a, M6b oder M6c); gemeinsam geführte Tests (E2E-085, PH-AT-002, PH-AT-003, PH-AT-005, PH-AT-008, PH-AT-014, E2E-093) nennen die beitragenden WPs aller Einheiten.
  7. Barrierefreiheitsprüfung der in M6a geänderten Bildschirme (UI-01, UI-02, UI-03, UI-04, UI-07, UI-08, UI-17) gegen WCAG 2.2 AA als Abnahmeziel ist protokolliert.
- **Tests:** positiv, negativ und konkurrierend wie in den Einzel-WPs; hier als zusammenhängender Lauf in Staging. Referenztests: E2E-085 bis E2E-090, E2E-104, AT-14, PH-AT-005 bis PH-AT-010; Beiträge zu PH-AT-002, PH-AT-003, PH-AT-014 und E2E-093 gemeinsam mit M6b und M6c; E2E-085 gemeinsam mit WP-M6b-34 (Gebietsregel) – M6a führt den Buchungsteil.
- **Abhängigkeiten:**
  - intern: WP-M6a-01 bis WP-M6a-33
  - extern: WP-M6b-05, WP-M6b-07, WP-M6b-34 (Grant-/Widerrufsnachweise für PH-AT-002, PH-AT-003)
  - extern: WP-M6c-07, WP-M6c-19, WP-M6c-23, WP-M6c-32 (Mehrparteienzahlung und Clearing für PH-AT-005, PH-AT-008, PH-AT-014, E2E-093)
  - extern: WP-M5-02, WP-M5-22 (Staging-Umgebung, Release-Gate in CI)
- **Entscheidungen:** D-01, D-03, D-04, D-08 blockieren nicht den Sandbox-Nachweis, aber jede Produktivschaltung.
- **Größe:** M
- **Nicht im Umfang:** E2E-092, E2E-094, E2E-095, PH-AT-016 (M6b); AT-16 (M6c); Last- und Restore-Nachweise der Föderation.

## Offene Punkte

1. **Vertragsstatus fehlt im Kern-DDL** (`LH-I.5-FED-014`, `LH-I.5-FED-003`): Kap. 02 §5 definiert `DRAFT → REVIEWED → ACTIVE → PAUSED/EXPIRED/REVOKED`, `partner_contract_version` in `kernschema.sql` hat aber keine Statusspalte und ist unveränderlich. M6b sieht dafür eine getrennte Zustandstabelle in WP-M6b-02 vor (dort OP-02, Tabellenname offen); M6a liest den Status nur über den Port. Zu bestätigen.
2. **Ausführungsstatus der Journey fehlt im Kern-DDL** (`LH-I.5-FED-008`): `journey` hat nur `state`; `execution_state` existiert nur an `journey_leg`. Kap. 02 §7.3 verlangt eine eigene Dimension „Journey-Ausführung".
3. **Diagnosefelder** (`LH-I.5-FED-007`): Kap. 02 §7.1 nennt `correlation_id`, `saga_id`, `leg_id`, `partner_operation_id`; im DDL gibt es nur `saga_step.partner_command_ref` und `partner_result_ref`. Abbildung ist zu klären.
4. **Inbox-Schlüssel** (`LH-I.5-FED-018`): Kap. 02 §8 und Kap. 05 §5.6 verlangen `(partner_id, event_id)` plus Payloadhash; `inbox` hat `PRIMARY KEY(tenant_id,consumer,event_id)` und `event_id uuid`. Ob `consumer` den Partner abbildet und ob Partner-Ereignis-IDs immer UUIDs sind, ist offen.
5. **Ablage von Aliasen und Projektionen** (`LH-I.5-FED-027`, `LH-I.5-FED-026`, `LH-I.5-FED-013`): Kap. 04 §4.4 benennt keine Tabelle für Partner-Fahrgastaliasse, Kontaktrelay oder Übergabeprojektionen. Das Ableitungsverfahren der Aliasse (Zufall oder Schlüsselableitung, Rotation) ist nicht festgelegt. M6b legt Projektion, Alias und Relay in WP-M6b-09 und den Transport in M6a (dort OP-04); die Tabelle der Übergabeprojektion nennt WP-M6b-13 nur als „Detailvertrag". Schnittlinie zu bestätigen.
6. **OpenAPI-Lücken im Angebot** (`LH-I.5-FED-010`, `LH-I.5-FED-033`, `LH-6.11-PTI-001`, `LH-6.11-PTI-003`, `LH-I.5-FED-023`, `LH-I.5-FED-020`): `OfferLeg.mode` kennt nur `ON_DEMAND`, `BUS`, `RAIL`, `WALK`, aber keine Fahrzeugart; es fehlen Felder für Entscheidungsgrundlage/Rankingrevision, Datenquelle und Datenstand, Tarifgeber und Ermäßigungsgrund je Komponente (`FareComponent` kennt nur `code`, `description`, `amount`, `tax_code`, `tariff_revision`). Der externe Buchungsweg ist mit `OfferLeg.external_booking_uri` vorhanden; die Prüfregel für das Ziel fehlt. `PlanRequest` kennt weder Anschlussziel noch Umsteigepuffer noch gewünschte Ankunft am Hub.
7. **UI-08 ohne API-Vertrag**: Kap. 05 §5.7 nennt keine Endpunkte für Klärfälle, erneuten Abgleich und dokumentierte manuelle Entscheidung; nur `/v1/operations/{operation_id}` und `/v1/operator/incidents` existieren. Welche Nachweise eine manuelle Entscheidung zulassen, ist nicht definiert.
8. **Fußweg und Hubgeometrie** (`LH-I.5-FED-009`, `LH-6.11-PTI-007`): Das Pflichtenheft legt nicht fest, ob Gehzeiten gepflegt (`transfer_rule`) oder über den Routingdienst berechnet werden, und nicht, wie „Stufen", „Übergabetoleranz" und „Betriebsrisiko" quantifiziert werden. Das Verhältnis von `transfer_min_time_min`, `interchange_min_time_min` und `advance_arrival_buffer_min` zueinander (additiv oder Präzedenz) ist offen.
9. **„Definierter Folgeprozess"** (`LH-6.11-PTI-006`, `LH-6.11-PTI-013`, AT-14): Der Folgeprozess nach Anschlusswarnung bzw. Garantieverletzung ist nicht beschrieben; Kap. 02 §8 nennt nur zulässige Optionen. Die Schwelle der Warnung („definierter Puffer") hat keinen Planungsdefault. Hängt an D-04.
10. **Pflegeort der Anschlussgarantie** (`LH-6.11-PTI-013`): unklar, ob die Regel Vertragsbestandteil (UI-18, M6b) oder Dienstkonfiguration (UI-17) ist; `Transfer.policy_revision` ist ein freier String ohne Formatvertrag.
11. **Externe Ticketprüfung** (`LH-6.11-PTI-008`): Weder Prüfschnittstelle noch Tarifverbund noch Speicherdauer der Prüfergebnisse sind benannt; die Zuordnung zu `entitlement_refs` ist eine Ableitung aus Kap. 05 §5.9.
12. **NeTEx, SIRI, VDV** (`LH-I.5-FED-022`): Die Anforderung nennt „passende Standards wie NeTEx, GTFS und SIRI/GTFS-RT"; Umsetzungsplan und Zielstruktur sehen nur `timetable-gtfs` vor. Ob NeTEx/SIRI in M6 zu liefern sind, hängt an D-08 und ist als SOLL-Abweichung zu dokumentieren, falls nur GTFS/GTFS-RT gebaut wird.
13. **Datenhöchstalter für ÖPNV-Echtzeit** (`LH-6.11-PTI-005`, `LH-6.11-PTI-009`): `kataloge.csv` enthält nur `eta_max_data_age_sec` (GPS-Datenhöchstalter); ein eigener Parameter für die Staleness von Fahrplan-Echtzeit fehlt.
14. **Parallelfahrtenregel** (`LH-6.11-PTI-004`): Die „konfigurierbaren Vergleichskriterien" sind nirgends benannt; `kataloge.csv` enthält keinen Parameter, `PlanResponse.reject_reason` keinen passenden Code, und das Verhalten bei fehlenden ÖPNV-Daten ist offen.
15. **Mobilitätsdatenpflichten** (`LH-6.11-PTI-011`): Anwendbare Pflichten, Datenkategorien, Formate, Fristen und Zielstelle sind nicht benannt. Abgrenzung zum Mobilitätsdatenexport in M8 (ANA) ist zu klären, um Doppelarbeit zu vermeiden.
16. **Zuständigkeit FED-003/FED-014 gegenüber M6b**: Vertragsaktivierung und Grantprüfung berühren die MUL-Anforderungen in M6b. Dieser Plan ordnet M6a das Gate und den Filter zu, M6b den Lebenszyklus und UI-18; das ist mit der Planung M6b abzugleichen.
17. **Capture- und Haftungsregeln** (`LH-I.5-FED-006`, `LH-I.5-FED-024`): Capturezeitpunkt, Haftung bei Zahlungsfehler nach Segmentbestätigung und Umgang mit nicht stornierbaren Segmenten sind laut Kap. 02 §7.2 AG-Entscheidungen (D-03, D-04); bis dahin gilt nur der Favorit „nur vollständige Kette".
18. **Kennzahlen ohne Eventbindung**: Für `Partnerannahmequote`, `Partnerübergabe pünktlich`, `Ende-zu-Ende-Reiseabschluss`, `Nicht erreichte Anschlüsse` und `Technische Partnerablehnungen` vermerkt `kataloge.csv` „Eventbindung/SQL, Zeitbasis und Version je Kennzahl noch auszuarbeiten". M6a liefert nur die Ereignisse; die Definition von „rechtzeitig" und „bewertbar" fehlt.
19. **FED-029 bis FED-032**: Die Einheit enthält FED-001 bis FED-028 und FED-033. Die vier dazwischenliegenden Anforderungen sind laut Kap. 08 §8.3 Phase 3 (I.5: 0 / 29 / 4) und nicht Teil von M6a; mögliche Vorleistungen sind bei der M9-Planung zu prüfen.
20. **Doppelte Zuständigkeit M6a/M6b/M6c** (`LH-6.11-PTI-001`, `LH-6.11-PTI-005`, `LH-I.5-FED-022`, `LH-I.5-FED-009`, `LH-I.5-FED-011`, `LH-I.5-FED-013`, `LH-I.5-FED-027`, `LH-6.11-PTI-008`, `LH-6.11-PTI-011`): Die Nachbardateien beanspruchen teils dieselben Liefergegenstände: WP-M6b-02 migriert `transfer_hub`, `transfer_rule`, `timetable_feed`, `realtime_observation` (dort OP-03); WP-M6b-27 baut `adapters/timetable-gtfs` für GTFS und GTFS-RT; WP-M6b-10/WP-M6b-11 liefern `/v1/transfer-hubs` und die UI-17-Pflege; WP-M6b-09 liefert Betreiberpayload, Alias und Relay; WP-M6b-26 nennt ebenfalls `/v1/partner/capabilities`; WP-M6c-11 liefert Tarifberechtigungen samt Prüfadapter-Schnittstelle; WP-M6b-31 und WP-M8c-30 liefern Mobilitätsdatenexporte. Diese Datei setzt die Schnittlinien (a) bis (g) aus dem Kopf als Arbeitsannahme; sie sind vor Codebeginn in WP-M6a-01 verbindlich zu bestätigen, damit nichts doppelt oder gar nicht gebaut wird. Für `LH-6.11-PTI-011` gibt es mit WP-M6b-31 und WP-M8c-30 drei Beteiligte in zwei Meilensteinen.
21. **Reason-Code bei langsamen Partnern** (`LH-I.5-FED-017`): Kap. 02 §6 Nr. 3 nennt `PARTNER_UNAVAILABLE` „mit Aktualitätsstatus", `openapi.yaml` kennt `PartnerAvailability.state` `TIMEOUT` und `reason_code` `PARTNER_TIMEOUT`. Welche Bezeichnung gilt und wann `UNAVAILABLE` statt `TIMEOUT` gemeldet wird, ist festzulegen.
22. **Ausführungsereignisse des Partners fehlen im Vertrag** (`LH-I.5-FED-008`, `LH-I.5-FED-025`, `LH-I.5-FED-028`): `PartnerEvent.event_type` kennt nur `partner.offer_held`, `partner.booking_confirmed`, `partner.booking_canceled`, `partner.booking_failed`, `partner.leg_delayed`; `PartnerEventData.state` nur `HELD`, `CONFIRMED`, `CANCELED`, `FAILED`, `DELAYED`. Für Segment-Ausführung (`APPROACHING`, `BOARDED`, `COMPLETED`, `NO_SHOW`, `DISRUPTED`) und damit für den Ausführungsnachweis eines Partnersegments gibt es weder Ereignistyp noch Statusfeld im Partnervertrag. Ohne Ergänzung sind WP-M6a-18 und WP-M6a-25 für fremde Segmente nicht abnehmbar.
23. **Last-, Fault-Injection- und Restore-Nachweis der Föderation**: M5 liefert diese Nachweise nur für den P1-Stand; WP-M6a-34, WP-M6b-34 und WP-M6c-32 schließen sie aus. Kap. 02 §6 Nr. 3 verlangt die Kalibrierung der Planungsbudgets im „vereinbarten Referenzlasttest" (D-05). Welche Einheit den P2-Lastnachweis für Planung, Saga und Partnerereignisse führt, ist offen; WP-M6a-34 liefert nur einen Kalibrierlauf gegen Sandbox-Partner.
24. **Holdverlängerung** (`LH-I.5-FED-005`): Kap. 02 §7.2 erlaubt Verlängerung „nur bei erneuter Kapazitätsprüfung", Kap. 05 §5.3 „nur innerhalb Partnerregeln". Maximale Anzahl bzw. Gesamtdauer, der zuständige Pfad unter `/v1/partner/reservations` und das Verhältnis von `partner_offer_ttl_sec` zum internen Hold (Planungsdefault 180 s) sind nicht festgelegt.
25. **Quoten und Aufbewahrung**: Kap. 05 §5.1 verlangt Quoten nach Tenant, Partner und Akteur, nennt aber keine Werte (vgl. M1). Aufbewahrungsfristen für `inbox`-Einträge, `realtime_observation`, Saga-Schritte, Testmitschnitte und Sandbox-Protokolle sind nicht benannt und hängen an D-11 (`LH-I.5-FED-018`, `LH-6.11-PTI-005`, `LH-I.5-FED-028`).
26. **Intervallbezeichnung** (`LH-I.5-FED-014`): Kap. 02 §5 nennt `[valid_from, valid_until)`, `kernschema.sql` führt `valid_from`/`valid_to` mit `CHECK(valid_to>valid_from)`; die Halboffenheit ist im DDL nicht ausgedrückt.
27. **Folgen eines Sicherheitswiderrufs für laufende Reisen** (`LH-I.5-FED-003`, `LH-I.5-FED-024`): Kap. 02 §5 verlangt „Reconciliation, Rebooking/Erstattung nach Freigabe"; WP-M6b-07 schließt Rebooking, Erstattung und Reconciliation nach Widerruf aus und verweist auf M6a/M6c. M6a deckt den In-flight-Abgleich (WP-M6a-04, WP-M6a-15) und das Storno (WP-M6a-22) ab; ein eigener Ablauf „Rebooking nach Sicherheitswiderruf" ist im Pflichtenheft nicht beschrieben und keinem WP zugeordnet.
28. **Reihenfolgekonflikt: Zyklen innerhalb des parallelen Meilensteins M6 (M6a ↔ M6b/M6c)**: Bei der Auflösung der externen Abhängigkeiten auf WP-IDs zeigen mehrere Abhängigkeiten in beide Richtungen; die Nachbardateien nennen ihrerseits `EXTERN[M6a]` mit genau der Fähigkeit des hier abhängigen WPs. Betroffen: WP-M6a-01 ↔ WP-M6c-01 (wechselseitige Detailverträge); WP-M6a-11 ↔ WP-M6c-08 (Angebotsdarstellung ↔ UI-03 Mehrparteien-Checkout); WP-M6a-14 ↔ WP-M6c-07 (Confirm-Saga ↔ Capture-Regel; M6c-07 verlangt zusätzlich WP-M6a-15/-16); WP-M6a-22 ↔ WP-M6c-14 (Cancel-Saga ↔ Erstattungsregeln; über WP-M6a-22 → WP-M6a-14 → WP-M6c-14 auch für WP-M6a-14); WP-M6a-23 ↔ WP-M6c-06 (Preisaufstellung ↔ Preiskomponenten); WP-M6a-24 ↔ WP-M6c-11 (Ticketprüf-Adapter ↔ Tarifberechtigung); WP-M6a-25 ↔ WP-M6c-23 (Ausführungsnachweis ↔ Clearingdatensätze); WP-M6a-04/WP-M6a-09 ↔ WP-M6b-06 (Anbieterfilter ruft Pausen-/Grant-Port); WP-M6a-04 → WP-M6b-07 → Saga WP-M6a-13/-14 → WP-M6a-04; WP-M6a-12 → WP-M6b-09 → Payload-Übergabe im Hold/Confirm WP-M6a-13/-14 → WP-M6a-12; WP-M6a-08/-09/-10 ↔ WP-M6b-11 (Gebietsregel-Port ↔ Journey Planner); WP-M6a-19/-26/-27 ↔ WP-M6b-27 (Feedabruf ↔ Verarbeitung der Feeddaten). Die Abhängigkeiten wurden nicht verbogen. Aufzulösen ist je Paar über vertragsbasierten Bau gegen Stub/Port aus den Detailverträgen (WP-M6a-01, WP-M6b-01, WP-M6c-01) mit festgelegter Lieferrichtung (Port-Lieferant zuerst, Aufrufer danach) oder durch Teilung des WPs in Port- und Integrationsanteil. Zusätzlich: Die in WP-M6a-09 und WP-M6a-13 als M2-Fähigkeit geführten Vertretungen (`/v1/delegations`) liefert laut Gesamtindex erst WP-M7a-11 (paralleler Strom M7, M2 schließt sie ausdrücklich aus); M6a hängt damit entgegen der Stromunabhängigkeit an M7a.
