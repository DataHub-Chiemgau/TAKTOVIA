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

## Energiekosten je 100 km und je Betriebsstunde (WP-M4-43)

Zwei zusätzliche Kennzahlen im Register (`energy_cost_per_100km`, `energy_cost_per_operating_hour`; Seed `seed_energy_metric_definitions()`, Migration V40461, Offener Punkt 40: Name, Formel und Aufnahme in den Katalog sind zu bestätigen), berechnet in [JdbcEnergyCosts](src/main/kotlin/de/taktovia/analytics/internal/JdbcEnergyCosts.kt). Zähler = Kostenbasis (`cost_basis_minor`, brutto abzüglich abziehbarer Vorsteuer) der **freigegebenen, gebuchten** Tankvorgänge (Beleg `POSTED`, ursprüngliches Ist-Kostenereignis, nicht storniert, kein offener Hinweis); Nenner = Kilometer (alle Kategorien der Klassifikation, jüngster Lauf je Fahrzeug und Tag) bzw. Betriebsstunden der Fahrzeuge mit eingegangenem Tankvorgang. **Betriebsstunde** (Vorschlag, Offener Punkt 22): Dauer der Kilometerabschnitte Fahrgast, Leerfahrt und Überführung (ohne Werkstatt und nicht zugeordnete Zeit). Wert = Zähler x 100 / km bzw. x 3600 / Stunden, auf ganze Minor Units kaufmännisch gerundet (`MoneyRounding`, ROUND_HALF_UP). Ausgabe je Energieart (Kraftstoff in Liter, Strom in kWh) und Währung getrennt, nie gemischt oder addiert; ein Fahrzeug mit beiden Energiearten wird mit Grund ausgeschlossen. Nicht eingegangene Vorgänge stehen mit Grund in `exclusions` (auch im Snapshot), fehlende Energiedaten senken den Abdeckungsgrad (`PROVISIONAL`) und ergeben nie 0 Kosten (ohne eingegangenen Vorgang: `NO_DATA`), Nenner 0 ergibt `ZERO_DENOMINATOR`. Alle Eingaben und der Datenstand werden in **einem** Statement gelesen (ein Lesestand, reproduzierbar auf demselben Datenstand). P1 ist eine Erwerbsnäherung ohne Bestandsführung und Ladeverluste (`approximation = ACQUISITION_BASIS_P1`). Filter `vehicle_id`, `energy`, `currency`; unbekanntes oder fremdes Fahrzeug: 404. Vertragsstand: [LH-I.2-FCO-004](../../../docs/umsetzungsplan/vertraege/LH-I.2-FCO-004.md).

## Zeitfakten (WP-M4-52)

`AnalyticsApi.timeFacts` ([TimeFacts](src/main/kotlin/de/taktovia/analytics/api/TimeFacts.kt)): geplante, angekündigte, zugesagte, prognostizierte und tatsächliche Abhol- und Ankunftszeiten je Fahrgastfahrt, append-only in `time_fact` (V40520). Verspätung = Ist minus Zielzeit der gebundenen (höchsten) Zusageversion, `null` mit Grund bei fehlender Ist-Zeit oder Zusage. `announced` ist bis zur Festlegung von OP 28 nur mit Testwert belegbar. Vertrag: [LH-I.6-KPI-006.md](../../../docs/umsetzungsplan/vertraege/LH-I.6-KPI-006.md).


## Nachfragetrichter (WP-M4-51)

`Metrics.funnel` / `GET /v1/analytics/metrics?breakdown=funnel`: vier getrennte Zählgrößen (Anfragen, Angebote angezeigt, bestätigte, abgeschlossene Buchungen; nie eine Summe) und die Endzustände Anfrage abgelehnt, Angebot nicht gebucht, Zahlung fehlgeschlagen, Fahrgaststorno, Betreiberstorno, sonstiges Storno. Alle Zahlen kommen aus einem Statement (`taktovia.funnel_counts`, V40510); `Metrics.reconcileFunnel` (`funnel_reconciliation`) zählt dieselben Stufen unabhängig aus `analytics_event_inbox` neu (Differenz muss 0 sein). `payment.authorization_failed` ist Teil der Quelle `BOOKING`. Annahmen und Lücken: [LH-I.6-KPI-005.md](../../../docs/umsetzungsplan/vertraege/LH-I.6-KPI-005.md).

## Ankunftspünktlichkeit (WP-M4-54)

Kennzahlen `arrival_on_time` (Ankunft pünktlich) und `mean_arrival_delay` (Mittlere Ankunftsverspätung, Minuten), Definitionsversion 2, getrennt von der Abholung ([JdbcArrivalPunctuality](src/main/kotlin/de/taktovia/analytics/internal/JdbcArrivalPunctuality.kt)): Verspätung = Ist-Ankunft minus zugesagte späteste Ankunft der höchsten Zusageversion (bei Servicefenster dessen Ende), nur positive Minuten; pünktlich = höchstens wirksame Toleranz (größerer Wert aus `on_time_arrival_tolerance_min` und `arrival_grace_min`, Arbeitsannahme). Die Toleranz wird über den Port `ArrivalToleranceSource` (Anwendung: `ConfigArrivalToleranceSource`) zum Zusagezeitpunkt aufgelöst und je Buchung und Zusageversion in `arrival_tolerance_binding` gebunden: eine Änderung wirkt nie rückwirkend. Fahrten ohne Ankunftsereignis stehen nicht im Nenner, Ankünfte ohne Zusage gesondert in `without_promise`.
