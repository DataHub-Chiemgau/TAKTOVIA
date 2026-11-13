# analytics – Auswertung und Prognose

Paket `de.taktovia.analytics` mit `api` (öffentliche Ports) und `internal` (privat).

**Führende Aggregate und Tabellen (Kap. 01 §1.3, Kap. 04 §4.3):** MetricDefinition, DatasetRevision, Scenario (Tabellen mit M4/M8).

Regeln: schreibt nur eigene Tabellen (`db/table_owners.d/`); greift auf andere Module nur über deren `api`-Paket zu;
hängt im Gradle-Build nur von `platform` ab.

## Faktenbasis und Datenstand (WP-M4-45)

`AnalyticsApi.facts` ([FactBase](src/main/kotlin/de/taktovia/analytics/api/AnalyticsApi.kt)): Ereigniskonsument mit Dedupe je `(Mandant, event_id)`, reihenfolgeunabhängige Ableitung der Fakten (`fact_attempt`, `fact_offer`, `fact_booking`, `fact_stop_event`, `fact_vehicle_state`), Datenstand je Quelle (`dataset_watermark`, Vollständigkeit) und Wiederaufbau. Vertrag: [m4-analytics-faktenbasis.md](../../../docs/umsetzungsplan/vertraege/m4-analytics-faktenbasis.md).

## Exportaufträge (WP-M5-19)

`AnalyticsApi.reportJobs` (`/v1/reports/jobs`, `api/reports.yaml`): asynchrone, mandantengebundene Exportaufträge mit Idempotenzschlüssel, Datenstand (`as_of`), Nachweisen (SHA-256, Größe, Kategorien mit Status) und kurzlebigem, signiertem Abruflink; Tabelle `export_job` (V50190). Gelesen werden Prüf-, Schaden-, Zeit- und Dienstplantabellen der führenden Module nur lesend und in einem Statement; geschrieben wird nur `export_job`. Vertrag, Annahmen und offene Punkte: [m4-cluster-wave12-c7.md](../../../docs/umsetzungsplan/vertraege/m4-cluster-wave12-c7.md).


## Klassifikation der Fahrzeugkilometer (WP-M4-42)

`AnalyticsApi.km` ([KmClassification](src/main/kotlin/de/taktovia/analytics/api/KmClassification.kt)): Klassifikationsjob je Fahrzeug und Betriebstag (UTC) und Auswertung `GET /v1/analytics/metrics` (`api/analytics.yaml`). Gesamtstrecke = Positionsverlauf (`position_sample`), jeder Abschnitt zwischen zwei Positionen wird nach Regelversion 1 genau einer Kategorie `PASSENGER`, `EMPTY`, `TRANSFER`, `WORKSHOP` oder `UNASSIGNED` zugeordnet (Fahrtenplan aus `staff_shift`/`vehicle_run`/`route_stop`, Einstieg/Ausstieg aus `fact_stop_event`); Lücken, fehlender Plan oder fehlende Schicht ergeben `UNASSIGNED`, nie eine erfundene Kategorie. Tabellen `vehicle_km_run` (Lauf mit Datenstand und Eingangsfingerabdruck) und `vehicle_km_segment` (Abschnitte, ganzzahlig in Tausendstel km = Meter), beide append-only (V40420); ein geänderter Eingangszustand erzeugt einen neuen Lauf. Der periodische Job läuft nur mit `taktovia.analytics.km-worker-enabled=true`. Vertrag: [LH-I.2-FCO-005.md](../../../docs/umsetzungsplan/vertraege/LH-I.2-FCO-005.md).
