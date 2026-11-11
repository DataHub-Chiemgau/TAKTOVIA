# LH-6.1-SYS-006 – Zeit-, Zeitzonen- und DST-Baustein

- **Verantwortlicher:** Rolle Plattform (Modul `backend/platform`, Web-Designsystem für die Anzeige)
- **Arbeitspaket:** WP-M1-18
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Zeiten werden intern mit Zeitzonenbezug verwaltet und lokal korrekt angezeigt, einschließlich Zeitumstellung.

**Originalabnahme (unverändert):** Buchungen über Sommer-/Winterzeitgrenzen besitzen eindeutige Zeitstempel.

## Vertrag

- **Tabellen / DDL:** Alle Zeitpunkte `timestamptz` (UTC) mit zusätzlicher IANA-Zone je Aggregat; Lint "keine `timestamp without time zone`-Spalte" über das Schema; `service.timezone` und `staff_shift.timezone` als IANA-Zone geprüft. Intervalle halboffen "links geschlossen, rechts offen".
- **API:** Zeitpunkte als RFC 3339 mit Offset; gemeinsame OpenAPI-Komponenten für Zeitpunkt und Zone. Anfrage ohne Offset: 422 `application/problem+json` mit Feldfehler. Weitere Feldfehler: nicht existente Ortszeit (DST-Lücke), mehrdeutige Ortszeit ohne Offset, `time_zone` widerspricht Offset, unbekannte Zone. Validator "`desired_at` und `time_zone` beschreiben denselben lokalen Zeitpunkt". Die Feldfehlercodes sind im Bau festzulegen (Detailvertrag Fehlercodes, kein Katalog im Pflichtenheft).
- **Zustandsautomat:** keiner (zustandsloser Baustein).
- **JSON-Schema / Ereignisse:** JSON-Schema der Zeittypen (Zeitpunkt, lokales Datum/Zeit + Zone, Gültigkeitsintervall) unter `schemas/`; Regel zur Speicherung der Zone je Aggregat. Injizierbare Serveruhr; lokaler Betriebstag in der Zone des Dienstes.
- **Parameter und Kennzahlen:** `reporting_timezone` (IANA TZ, Ebene Mandant, DST-sicher).
- **Oberfläche:** Designsystem-Komponente Datum/Zeit mit Zonenanzeige und Offset-Auswahl bei mehrdeutiger Ortszeit (Kap. 06 §6.2: Auswahl mit UTC-Offset bei uneindeutiger Ortszeit, nicht existente Ortszeit verlangt neue Eingabe); Anzeige in der Zone des Dienstes mit Offset und lokalem Betriebstag (PH-AT-018: "aktuelle Ansicht zeigt … den lokalen Betriebstag"); Fehlerzustand bei Lücke/Mehrdeutigkeit.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-01/OP-38 – Buchung über die Sommer-/Winterzeitgrenze als fachlicher Nachweis folgt mit M2 (Vollabnahme WP-M2-11, WP-M2-23); M1 weist den Baustein an Testanfragen und Anzeige nach. Blockiert den Bau nicht. Schichtdauer-/Arbeitszeitrechnung ist M4.

## Tests

- **Positiv:** 25.10.2026 02:30 `Europe/Berlin` mit +02:00 und mit +01:00 werden als zwei verschiedene, eindeutige Zeitpunkte gespeichert und unterscheidbar zurückgegeben; Dauer 01:00–04:00 Ortszeit am 25.10.2026 = 4 h aus absoluten Zeitpunkten; Sommer-/Winterzeitpunkte und Zonenwechsel; lokaler Betriebstag in Dienstzone; UI zeigt Zone und Offset.
- **Negativ:** Anfrage ohne Offset (Feldfehler, 422), auch bei mehrdeutiger Ortszeit; nicht existente Ortszeit (Frühjahrsumstellung 02:30) abgewiesen; `time_zone` widerspricht Offset; unbekannte Zone; Schema-Lint findet `timestamp without time zone`; `service.timezone`/`staff_shift.timezone` mit unbekannter IANA-Zone wird abgewiesen; Intervall mit Ende vor oder gleich Beginn wird abgewiesen (halboffen links geschlossen/rechts offen, angrenzende Intervalle überlappen nicht); Uhr nicht injiziert (feste Testuhr) liefert deterministische Ergebnisse.
- **Konkurrierend:** nicht einschlägig (zustandsloser Baustein, keine geteilten Daten).
- **Referenztests:** PH-AT-018 (DST-Teil).

Testfälle: PH-AT-018; backend/platform/src/test/kotlin/de/taktovia/platform/time/DstTimeTest.kt::doppelte_ortszeit_zwei_eindeutige_zeitpunkte; backend/platform/src/test/kotlin/de/taktovia/platform/time/DstTimeTest.kt::dst_luecke_und_fehlender_offset_werden_abgewiesen; backend/platform/src/test/kotlin/de/taktovia/platform/time/DstTimeTest.kt::dauer_ueber_zeitumstellung_aus_absoluten_zeitpunkten; backend/platform/src/test/kotlin/de/taktovia/platform/time/DstTimeTest.kt::zone_widerspricht_offset_und_unbekannte_zone_werden_abgewiesen; backend/platform/src/test/kotlin/de/taktovia/platform/time/DstTimeTest.kt::lokaler_betriebstag_in_dienstzone; web/design-system/src/components/DateTimeField.test.tsx::zeigt_zone_offset_und_verlangt_offsetwahl_bei_mehrdeutiger_ortszeit; db/tests/test_zeitlint.py::test_keine_timestamp_without_time_zone_spalte; backend/app/src/test/kotlin/de/taktovia/app/TimeApiTest.kt::feldfehler_ohne_offset_luecke_zone_und_intervall; tests/acceptance/api/test_zeit_schema.py::test_ohne_offset_und_unbekannte_felder_werden_abgewiesen; db/tests/test_zeitlint.py::test_service_und_staff_shift_zone_wird_geprueft

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).

| Rolle | Person | Datum | Ergebnis |
|---|---|---|---|
| Fachanalyse | | | |
| Architektur | | | |
| QA | | | |
