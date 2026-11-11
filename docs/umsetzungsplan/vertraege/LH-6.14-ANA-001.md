# LH-6.14-ANA-001 – Nachfragetrichter und getrennte Zählung der Buchungsstufen

- **Verantwortlicher:** Rolle Analytics (Modul `analytics`)
- **Arbeitspaket:** WP-M4-51
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Plattform zählt Anfragen, Angebote, bestätigte und abgeschlossene Buchungen getrennt.

**Originalabnahme (unverändert):** Testzeitraum liefert mit Ereignisprotokoll übereinstimmende Zahlen.

## Vertrag

- **Tabellen / DDL:** Quelle ist `travel_attempt` (V1: `tenant_id`, `id` = `attempt_id`, `created_at`, `channel`, `primary_rejection`, `secondary_rejections`) und das Ereignisprotokoll. Neu in M4-Migration (Kap. 04 §4.4, Zeile ANA/KPI; Tabellennamen der Funnelfakten legt WP-M4-45 im Detailvertrag fest): Funnelfakten je Versuch und Stufe, `aggregate_snapshot`, `dataset_watermark`; Schlüssel `(tenant_id,id)`, RLS, Index beginnend mit `tenant_id`. Invariante: jeder Versuch hat je Stufe genau einen Endzustand; Fakten werden idempotent über Ereignis-ID geschrieben (doppelte Zustellung ändert keine Zahl).
- **API:** `GET /v1/analytics/metrics` (Katalogpfad, Kap. 05 §5.7; noch nicht in `api/*.yaml` ausformuliert). Antwort `{items,next_cursor,as_of}`; vier getrennte Zählgrößen Anfragen, Angebote, bestätigte Buchungen, abgeschlossene Buchungen, nie als Summe. Statuscodes nach `common.yaml` (401/403/404/422).
- **Zustandsautomat:** keiner eigener; Stufen folgen den Buchungsereignissen aus M2 (WP-M2-21/23/26/30) und Fahrtabschluss (WP-M3-29).
- **JSON-Schema / Ereignisse:** gelesen werden die Ereignisse Anfrage, Angebot angezeigt, Buchung bestätigt, Zahlung fehlgeschlagen, Fahrgaststorno, Fahrt abgeschlossen; Schemas unter `api/events/` sind beim Bau gegen die M2-Definitionen abzugleichen. Abgleichbericht Funnel gegen Ereignisprotokoll (Differenz muss 0 sein).
- **Parameter und Kennzahlen:** `Anfragen`, `Angebotsquote`, `Buchungskonversion`, `Buchungsstornoquote Fahrgast`, `Buchungsstornoquote Betreiber` (kataloge.csv K.1); Definitionsversion über `metric_definition_revision` (WP-M4-46).
- **Oberfläche:** Anzeige im Kennzahlenbereich des Operator-Web (WP-M4-59, Zuordnung zu UI-19 laut OP-23 zu bestätigen); Zustände leer, Fehler, keine Rechte, Datenstand sichtbar.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-27 (WP-M4): Ereignis „Angebot angezeigt“ und Abgrenzung „abgeschlossene Buchung“ gegen „durchgeführte Fahrt“ sind im Pflichtenheft nicht definiert. Vorschlag: „abgeschlossene Buchung“ = Ereignis Fahrtabschluss (Ausstieg, WP-M3-29); Angebot angezeigt = explizites Anzeigeereignis. Die Festlegung ist Teil von WP-M4-01 und muss vor Codebeginn des Abgleichberichts bestätigt werden (blockiert den Bau der Stufen „Angebot“ und „abgeschlossen“, nicht der Stufen „Anfrage“ und „bestätigt“). OP-23 (Oberfläche).

## Tests

- **Positiv:** Fixture mit bekanntem Ereignisprotokoll über einen Testzeitraum: Zahlen für Anfragen, Angebote, bestätigte und abgeschlossene Buchungen stimmen mit dem Protokoll exakt überein (Abgleichbericht: Differenz 0); die API weist die vier Größen als getrennte Felder aus und liefert nie eine Summe.
- **Negativ:** Storno nach Abschluss verändert die Stufe „abgeschlossen“ nicht; Betreiber- und Fahrgaststorno sind unterscheidbar; technischer Retry einer Anfrage zählt nicht neu (`attempt_id`); fremder Mandant sieht keine Zählwerte (RLS, 404/leer, auch bei direkter Abfrage der Funnelfakten).
- **Konkurrierend:** doppelte und umsortierte Ereigniszustellung (parallel) verändert keine Zahl; Zählung während laufender Projektion trägt `as_of`/Datenstand und bleibt je Stufe konsistent; parallele Abfragen zweier Mandanten zeigen keine Vermischung.
- **Referenztests:** Das WP-Dokument nennt für diese Anforderung keine Katalog-ID. Das Zehn-Versuche-Fixture aus PH-AT-021 kann als Vorlage dienen, ist aber kein Nachweis dieser Anforderung (E2E-080 betrifft die Kapazitätsablehnung, nicht diese Zählung).
- Testfälle: geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/FunnelCountTest.kt::stufen_stimmen_mit_ereignisprotokoll_ueberein; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/FunnelCountTest.kt::vier_zaehlgroessen_getrennt_nie_summe; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/FunnelCountTest.kt::storno_nach_abschluss_aendert_stufe_abgeschlossen_nicht; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/FunnelCountTest.kt::doppelte_ereigniszustellung_aendert_keine_zahl; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/FunnelCountTest.kt::umsortierte_ereignisse_aendern_keine_zahl; geplant:db/tests/test_funnel_fakten.py::test_fremder_mandant_sieht_keine_funnelfakten

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
