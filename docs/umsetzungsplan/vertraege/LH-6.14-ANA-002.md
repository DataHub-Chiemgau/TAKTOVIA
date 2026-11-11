# LH-6.14-ANA-002 – Kilometer-, Betriebsstunden- und Fahrgastkilometer-Kennzahlen

- **Verantwortlicher:** Rolle Analytics (Modul `analytics`)
- **Arbeitspaket:** WP-M4-56
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Fahrzeugkilometer, Besetztkilometer und Fahrgastkilometer werden mit definierter Berechnung ausgewiesen.

**Originalabnahme (unverändert):** Dokumentierte Musterfahrt ergibt erwartete Kennzahlen.

## Vertrag

- **Tabellen / DDL:** `metric_definition_revision` (Formel, Erfassungsgrenzen, Version), `aggregate_snapshot`, `dataset_watermark` (Kap. 04 §4.4, neu in M4-Migration; `(tenant_id,id)`, RLS). Eingang ist die Kilometerklassifikation aus WP-M4-42 (Kategorien Fahrgastfahrt/besetzt, Leerfahrt, Überführung, Werkstattfahrt, nicht zuordenbar); die dafür nötigen Tabellen sind im Kern-DDL nicht vorhanden (OP-22).
- **API:** `GET /v1/analytics/metrics` mit Kennzahlname; die Antwort enthält Wert, `definition_version`, Zähler, Nenner, Vollständigkeit und `as_of`; Nullnenner ergibt `null` mit Code `ZERO_DENOMINATOR` (WP-M4-46); Abruf der Definition je Kennzahl (Berechnung dokumentiert und abrufbar).
- **Zustandsautomat:** keiner. **JSON-Schema / Ereignisse:** keine neuen; gelesen werden Fahrt-/Telemetrie-Fakten aus WP-M4-45.
- **Parameter und Kennzahlen:** `Fahrgastkilometer` (Summe individueller Fahrtstrecken aller Fahrgäste, nicht mit Fahrzeugkilometern verwechseln), `Produktive Kilometer` (mindestens ein Fahrgast, unabhängig von der Anzahl), `Leerkilometerquote` (Kilometer ohne Fahrgäste, Nenner alle Fahrzeugkilometer im Betriebsdienst, Werkstattüberführung getrennt); zusätzlich Fahrzeugkilometer gesamt und Betriebsstunden. Invariante: Leer + produktiv + nicht zuordenbar = Fahrzeugkilometer im Betriebsdienst.
- **Oberfläche:** Kennzahlenansicht Operator-Web (WP-M4-59); Zustand „Daten unvollständig“ mit ausgewiesener Vollständigkeit statt scheingenauem Wert.
- **Entscheidungen:** D-07 (Leer-/Besetztkilometer-Definition; blockiert nur die Produktivschaltung, nicht den Bau).
- **Offene Punkte:** OP-22 (WP-M4): Distanzquelle (Routingdistanz mit Version oder Messung), Definition Betriebsstunde, Behandlung von Überführung und Werkstattfahrt sowie die „dokumentierte Musterfahrt“ mit Sollwerten liegen nicht vor und werden in WP-M4-01 erstellt und freigegeben; ohne freigegebene Musterfahrt kann der Abnahmetest nicht endgültig ausgeführt werden, der Bau der Formeln ist nicht blockiert. D-07 offen. OP-24 (Kennzahlliste).

## Tests

- **Positiv:** dokumentierte Musterfahrt mit zwei Fahrgästen und Teilstrecken ergibt die erwarteten Fahrzeug-, Besetzt- und Fahrgastkilometer (Fahrgastkilometer = Summe der individuellen Strecken, bei zwei Fahrgästen auf gemeinsamer Teilstrecke größer als die Besetztkilometer); die Definition jeder der drei Größen ist abrufbar; Leer + produktiv + nicht zuordenbar = Fahrzeugkilometer im Betriebsdienst.
- **Negativ:** Überführungs- und Werkstattfahrt zählen nicht als produktive Fahrgaststrecke; Datenlücke (fehlende GPS-Daten für Teil der Fahrten) weist Vollständigkeit aus statt scheingenauem Wert; Nullnenner ergibt „nicht berechenbar“ (`null`, Code `ZERO_DENOMINATOR` laut WP-M4-46), nie 0 oder 100 %.
- **Konkurrierend:** nachträglich eintreffende Fahrten- oder GPS-Daten während der Berechnung ändern `aggregate_snapshot` nur über neuen Datenstand, nicht still; Mandant A und B parallel zeigen keine Vermischung.
- **Referenztests:** E2E-084 (Datenlücke, Vollständigkeit). Die Musterfahrt mit Sollwerten ist noch nicht freigegeben (OP-22); ohne sie ist der Abnahmetest nicht endgültig ausführbar.
- Testfälle: E2E-084; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/DistanceMetricsTest.kt::musterfahrt_ergibt_erwartete_kilometer; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/DistanceMetricsTest.kt::ueberfuehrung_zaehlt_nicht_als_produktiv; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/DistanceMetricsTest.kt::werkstattfahrt_zaehlt_nicht_als_produktiv; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/DistanceMetricsTest.kt::kategorien_summieren_zu_fahrzeugkilometern; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/DistanceMetricsTest.kt::definition_je_kennzahl_abrufbar; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/DistanceMetricsTest.kt::gps_luecke_weist_vollstaendigkeit_aus; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/DistanceMetricsTest.kt::nullnenner_ergibt_nicht_berechenbar; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/DistanceMetricsTest.kt::nachtraegliche_daten_aendern_snapshot_nur_ueber_neuen_datenstand; geplant:db/tests/test_kilometer_kennzahlen.py::test_fremder_mandant_sieht_keine_kilometerfakten

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
