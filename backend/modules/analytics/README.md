# analytics – Auswertung und Prognose

Paket `de.taktovia.analytics` mit `api` (öffentliche Ports) und `internal` (privat).

**Führende Aggregate und Tabellen (Kap. 01 §1.3, Kap. 04 §4.3):** MetricDefinition, DatasetRevision, Scenario (Tabellen mit M4/M8).

Regeln: schreibt nur eigene Tabellen (`db/table_owners.d/`); greift auf andere Module nur über deren `api`-Paket zu;
hängt im Gradle-Build nur von `platform` ab.

## Faktenbasis und Datenstand (WP-M4-45)

`AnalyticsApi.facts` ([FactBase](src/main/kotlin/de/taktovia/analytics/api/AnalyticsApi.kt)): Ereigniskonsument mit Dedupe je `(Mandant, event_id)`, reihenfolgeunabhängige Ableitung der Fakten (`fact_attempt`, `fact_offer`, `fact_booking`, `fact_stop_event`, `fact_vehicle_state`), Datenstand je Quelle (`dataset_watermark`, Vollständigkeit) und Wiederaufbau. Vertrag: [m4-analytics-faktenbasis.md](../../../docs/umsetzungsplan/vertraege/m4-analytics-faktenbasis.md).
