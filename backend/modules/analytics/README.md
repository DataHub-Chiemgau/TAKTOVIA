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

`AnalyticsApi.km` ([KmClassification](src/main/kotlin/de/taktovia/analytics/api/KmClassification.kt)): Klassifikationsjob je Fahrzeug und Betriebstag (UTC) und Auswertung `GET /v1/analytics/vehicle-km` (`api/analytics.yaml`). Gesamtstrecke = Positionsverlauf (`position_sample`), jeder Abschnitt zwischen zwei Positionen wird nach Regelversion 1 genau einer Kategorie `PASSENGER`, `EMPTY`, `TRANSFER`, `WORKSHOP` oder `UNASSIGNED` zugeordnet (Fahrtenplan aus `staff_shift`/`vehicle_run`/`route_stop`, Einstieg/Ausstieg aus `fact_stop_event`); Lücken, fehlender Plan oder fehlende Schicht ergeben `UNASSIGNED`, nie eine erfundene Kategorie. Tabellen `vehicle_km_run` (Lauf mit Datenstand und Eingangsfingerabdruck) und `vehicle_km_segment` (Abschnitte, ganzzahlig in Tausendstel km = Meter), beide append-only (V40420); ein geänderter Eingangszustand erzeugt einen neuen Lauf. Der periodische Job läuft nur mit `taktovia.analytics.km-worker-enabled=true`. Vertrag: [LH-I.2-FCO-005.md](../../../docs/umsetzungsplan/vertraege/LH-I.2-FCO-005.md).

## Kennzahlregister und Ablehnungsstatistik (WP-M4-46 bis -50)

`AnalyticsApi.metrics` ([Metrics](src/main/kotlin/de/taktovia/analytics/api/Metrics.kt), `GET /v1/analytics/metrics`, `api/analytics.yaml`): Kennzahldefinitionen je Mandant und Version (`metric_definition_revision`, nach der Freigabe unveränderlich; Seed `seed_metric_definitions()`: 21 P1-Kennzahlen plus technische Partnerablehnungen als Definition), Kennzahlwerte mit Definitionsversion, Zähler, Nenner, Datenstand (`as_of`) und Vollständigkeit, Snapshots in `aggregate_snapshot` (nur anhängen). Ein Nullnenner oder fehlende Daten ergeben `NOT_COMPUTABLE` (nie 0 oder 100 %), unvollständige Quellen `PROVISIONAL` mit Abdeckungsgrad, kleine Gruppen `SUPPRESSED`. Werte und Datenstand entstehen in **einem** SQL-Statement (ein Lesestand). Ausführbar sind in diesem Stand die Versuchskennzahlen (`Anfragen`, `Technische Anfragefehler`, Kapazitäts-, Personalmangel-, Ausstattungs-, Gebiets- und Zeitfensterablehnungen); die übrigen tragen `NOT_EXECUTABLE_IN_THIS_RELEASE`.

Versuche (`fact_attempt`): je `attempt_id` ein Fakt, Wiederholungen derselben Suche sind höhere Revisionen und zählen nicht neu, Testanfragen (`is_test`) sind ausgeschlossen; `FactBase.reconcileAttempts` gleicht gegen `travel_attempt` ab. Ablehnungen tragen genau einen Primärgrund und Nebengründe als Labels; der Katalog `rejection_reason` (Gruppe, Gültigkeit, Texte je Sprache) wird beim Verarbeiten und per Trigger durchgesetzt (`ConsumeOutcome.REJECTED_REASON`).
Vertragsstand: [LH-I.6-KPI-001/-002/-003/-004/-010](../../../docs/umsetzungsplan/vertraege/LH-I.6-KPI-010.md).

## Zeitfakten (WP-M4-52)

`AnalyticsApi.timeFacts` ([TimeFacts](src/main/kotlin/de/taktovia/analytics/api/TimeFacts.kt)): geplante, angekündigte, zugesagte, prognostizierte und tatsächliche Abhol- und Ankunftszeiten je Fahrgastfahrt, append-only in `time_fact` (V40520). Verspätung = Ist minus Zielzeit der gebundenen (höchsten) Zusageversion, `null` mit Grund bei fehlender Ist-Zeit oder Zusage. `announced` ist bis zur Festlegung von OP 28 nur mit Testwert belegbar. Vertrag: [LH-I.6-KPI-006.md](../../../docs/umsetzungsplan/vertraege/LH-I.6-KPI-006.md).
