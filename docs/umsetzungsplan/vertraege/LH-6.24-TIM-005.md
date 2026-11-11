# LH-6.24-TIM-005 – Zeitrechnung über Mitternacht, Zeitzone und DST

- **Verantwortlicher:** Rolle Personal/Zeit (Modul `staff-time`)
- **Arbeitspaket:** WP-M4-10
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Berechnungen berücksichtigen Datum, Zeitzonenwechsel, Schichten über Mitternacht und Sommer-/Winterzeitumstellung.

**Originalabnahme (unverändert):** Test über DST-Umstellung ergibt korrekte absolute Arbeitszeit und lokale Darstellung.

## Vertrag

- **Tabellen / DDL:** Dauern werden aus absoluten `timestamptz` berechnet, nie aus lokaler Wanduhrzeit. `staff_shift.timezone` (V1, IANA-Zone) und `time_event` mit Zeitpunkt plus Zone (siehe TIM-002). Keine gespeicherte lokale Zeit ohne Offset/Zone.
- **API:** `GET /v1/time-events` und Roster-Lesemodell liefern Zeitpunkte in UTC mit Offset sowie lokale Darstellung (IANA-Zone, Offset, Bezugstag); doppelt vorkommende Ortszeit wird mit Offset angezeigt. Lokale Zeit in der DST-Lücke (z. B. 29.03.2026 02:30 `Europe/Berlin`) wird abgelehnt; Statuscode 422 mit Problem nach `api/common.yaml` ist Vorschlag des Autors, das WP legt nur „wird abgelehnt“ fest.
- **Zustandsautomat:** keiner.
- **JSON-Schema / Ereignisse:** keine; Baustein WP-M1-18 (Zeit-, Zeitzonen- und DST-Baustein) wird wiederverwendet.
- **Parameter und Kennzahlen:** `reporting_timezone` (IANA TZ, Mandant, DST-sicher, `kataloge.csv` LH-J.7).
- **Oberfläche:** Anzeige lokaler Zeit mit Offset bei doppelter Ortszeit (Darstellungskonvention).
- **Entscheidungen:** keine.
- **Offene Punkte:** Darstellungskonvention für doppelte Ortszeit und Testtabelle mit Sollwerten sind laut WP-M4-10 vorab festzulegen; Bezugstag einer Schicht über Mitternacht ist vom Pflichtenheft nicht festgelegt und wird in der Testtabelle definiert (Vorschlag: Tag des Schichtbeginns, vom Fachbereich zu bestätigen); die Sollwerte für „Zeitzonenwechsel“ (z. B. Person oder Schicht in anderer IANA-Zone als `reporting_timezone`) legt das Pflichtenheft nicht fest und sind in der Testtabelle vorab zu definieren; Feiertage und Sonderregeln nach Ort sind nicht Umfang (SCH-019, M7).

## Tests

- **Positiv:** Sollwerttabelle: Nachtschicht 22:00–06:00 über die Herbstumstellung (25.10.2026, `Europe/Berlin`) ergibt 9 h absolute Arbeitszeit, über die Frühjahrsumstellung (29.03.2026) 7 h, lokale Darstellung bleibt 22:00–06:00 (AT-47); Schicht über Mitternacht mit korrekter Dauer und definiertem Bezugstag; Zeitzonenwechsel (Sollwerte der Testtabelle, siehe Offene Punkte).
- **Negativ:** lokale Zeit in der DST-Lücke wird abgelehnt; keine stille Auswahl oder Verschiebung.
- **Konkurrierend:** zwei Stempel um 02:30 mit UTC+02 und UTC+01 sind zwei eindeutige Zeitpunkte und werden bei parallelem Eingang beide gespeichert, keiner überschreibt den anderen.
- **Referenztests:** AT-47, PH-AT-018 (Zeiterfassungsteil).
- Testfälle: AT-47; PH-AT-018; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ZeitrechnungDstTest.kt::nachtschicht_herbstumstellung_9_stunden_absolut; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ZeitrechnungDstTest.kt::lokale_zeit_in_dst_luecke_wird_abgelehnt; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ZeitrechnungDstTest.kt::nachtschicht_fruehjahrsumstellung_7_stunden_absolut; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ZeitrechnungDstTest.kt::schicht_ueber_mitternacht_dauer_und_bezugstag; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ZeitrechnungDstTest.kt::zwei_stempel_0230_utc_plus_2_und_plus_1_parallel_beide_gespeichert; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ZeitrechnungDstTest.kt::doppelte_ortszeit_wird_mit_offset_dargestellt

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
