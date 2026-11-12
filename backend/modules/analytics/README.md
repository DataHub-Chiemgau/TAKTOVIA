# analytics – Auswertung und Prognose

Paket `de.taktovia.analytics` mit `api` (öffentliche Ports) und `internal` (privat).

**Führende Aggregate und Tabellen (Kap. 01 §1.3, Kap. 04 §4.3):** MetricDefinition, DatasetRevision, Scenario (Tabellen mit M4/M8).

Regeln: schreibt nur eigene Tabellen (`db/table_owners.d/`); greift auf andere Module nur über deren `api`-Paket zu;
hängt im Gradle-Build nur von `platform` ab.

## Exportaufträge (WP-M5-19)

`AnalyticsApi.reportJobs` (`/v1/reports/jobs`, `api/reports.yaml`): asynchrone, mandantengebundene Exportaufträge mit Idempotenzschlüssel, Datenstand (`as_of`), Nachweisen (SHA-256, Größe, Kategorien mit Status) und kurzlebigem, signiertem Abruflink; Tabelle `export_job` (V50190). Gelesen werden Prüf-, Schaden-, Zeit- und Dienstplantabellen der führenden Module nur lesend und in einem Statement; geschrieben wird nur `export_job`. Vertrag, Annahmen und offene Punkte: [m4-cluster-wave12-c7.md](../../../docs/umsetzungsplan/vertraege/m4-cluster-wave12-c7.md).

