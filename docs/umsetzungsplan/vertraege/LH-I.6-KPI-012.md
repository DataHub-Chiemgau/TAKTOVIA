# LH-I.6-KPI-012 – Auslastung als Kapazitätszeit- und Fahrgastkilometerkennzahl

- **Verantwortlicher:** Rolle Analytics (Modul `analytics`)
- **Arbeitspaket:** WP-M4-57
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Auslastung wird sowohl als Kapazitätszeit- als auch als Fahrgastkilometerkennzahl berechnet.

**Originalabnahme (unverändert):** Testumlauf mit vier Fahrgästen zeigt beide Metriken mit dokumentierten Formeln.

## Vertrag

- **Tabellen / DDL:** `metric_definition_revision` (Formel, Zähler/Nenner, Version, Gültigkeit), `aggregate_snapshot` (Zähler, Nenner, Vollständigkeit, Status); Kapazität je Fahrzeug aus `vehicle.capacity_config` (Ressourcenvektor, WP-M3-04) mit Gültigkeitszeitraum, damit Kapazitätswechsel im Zeitraum zeitanteilig berücksichtigt werden. RLS und `tenant_id`-Indizes nach Kap. 04 §4.5.
- **API:** `GET /v1/analytics/metrics` (in Kap. 05 genannt, noch nicht in `api/openapi.yaml`) liefert beide Kennzahlen samt Zähler, Nenner, Definitionsversion und Formel (abrufbar); Nenner 0 oder keine verfügbare Kapazität im Zeitraum ergibt Status „nicht berechenbar“, nie 0 oder 100 %.
- **Zustandsautomat:** keiner.
- **JSON-Schema / Ereignisse:** Definitionsschema der Kennzahl (siehe KPI-013); Eingang: Fahrtfortschritts- und Belegungsereignisse.
- **Parameter und Kennzahlen:** `Sitzplatz-Zeit-Auslastung` = Summe belegter Sitzplatzminuten / Summe verfügbarer Sitzplatzminuten (Kapazitätswechsel berücksichtigen); Formeln und Definitionsversion sind je Kennzahl in `metric_definition_revision` dokumentiert; `Kapazitätsauslastung distanzgewichtet` = Summe besetzter Platz-km / Summe angebotener Platz-km (Rollstuhlplätze gesondert zählen). Verfügbare Plätze aus `vehicle.capacity_config`.
- **Oberfläche:** Kennzahlkarten in UI-19 (WP-M4-59) mit Zähler/Nenner und Formelverweis; Zustand „nicht berechenbar“.
- **Entscheidungen:** keine.
- **Offene Punkte:** WP-Dokument OP 31: Die Anforderung nennt „Kapazitätszeit“ und „Fahrgastkilometer“, der Katalog `Sitzplatz-Zeit-Auslastung` und `Kapazitätsauslastung distanzgewichtet` (Platz-km); Zuordnung und Behandlung der Rollstuhlplätze/Umrüstzustände im Nenner sind zu bestätigen. Nicht blockierend: Vorläufig gelten die Katalogformeln, Rollstuhlplätze gesondert gezählt. Das „Fahrgastkilometer“ der Anforderung ist die Basis des Zählers, nicht die eigenständige Kennzahl `Fahrgastkilometer` (KPI-013).

## Tests

- **Positiv:** Testumlauf mit vier Fahrgästen und überlappenden Teilstrecken: beide Metriken mit den erwarteten Werten (Sollwerte in der Fixture dokumentiert); Formeln über die API abrufbar.
- **Negativ:** Rollstuhlplätze werden in `Kapazitätsauslastung distanzgewichtet` gesondert gezählt und nicht den Sitzplätzen zugeschlagen (vorläufig, OP 31); Nullnenner / keine verfügbare Kapazität im Zeitraum ergibt „nicht berechenbar“; Fahrzeug mit geänderter Kapazität im Zeitraum wird zeitanteilig gewichtet; Fahrzeug eines anderen Mandanten fließt nicht ein.
- **Konkurrierend:** Kapazitätsänderung (Umrüstung) während laufender Berechnung ändert einen bereits veröffentlichten `aggregate_snapshot` nicht rückwirkend.
- **Referenztests:** keine Katalog-Testfälle im WP-Dokument genannt.
- Testfälle: geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/UtilizationMetricsTest.kt::testumlauf_vier_fahrgaeste_beide_auslastungen; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/UtilizationMetricsTest.kt::nullnenner_ist_nicht_berechenbar

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
