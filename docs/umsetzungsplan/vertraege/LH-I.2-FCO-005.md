# LH-I.2-FCO-005 – Klassifikation der Fahrzeugkilometer

- **Verantwortlicher:** Rolle Analytics (Modul `analytics`)
- **Arbeitspaket:** WP-M4-42
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Fahrzeugkilometer werden getrennt nach Fahrgastfahrt, Leerfahrt, Überführung und Werkstattfahrt klassifiziert.

**Originalabnahme (unverändert):** Gesamtkilometer entsprechen der Summe der Kategorien; nicht zuordenbare Kilometer sind separat sichtbar.

## Vertrag

- **Tabellen / DDL:** Neue Migration nach V3 (im Kern-DDL V1/V2/V3 nicht vorhanden; Vorschlag dieses Plans, OP-22): Tabelle `vehicle_km_segment` im Modul `analytics` mit `tenant_id`, `id`, `vehicle_id`, `service_day`, `period_from`, `period_to`, `category` (CHECK IN `PASSENGER`, `EMPTY`, `TRANSFER`, `WORKSHOP`, `UNASSIGNED`), `km_thousandths bigint CHECK(km_thousandths>=0)` (ganzzahlig, keine Gleitkommasummen), `source` (`ROUTING`/`ODOMETER`/`POSITION`; Distanzquelle offen, OP-22), `rule_version`, `dataset_watermark_id`, `CHECK(period_to>period_from)`. Schlüssel `(tenant_id,id)`, zusammengesetzter FK auf `vehicle(tenant_id,id)`, RLS wie V1, Revision/Audit. Lesend über die Leseports aus WP-M4-02 (kein Fremdtabellenzugriff): `vehicle_run`, `route_stop`, Fahrerereignisse, Positionsdaten; `dataset_watermark` für den Datenstand. Invariante je Fahrzeug und Betriebstag: Gesamtkilometer = Summe der vier Kategorien + `UNASSIGNED`; `UNASSIGNED` ist der Rest zur Gesamtstrecke und wird nie auf andere Kategorien verteilt.
- **API:** `GET /v1/analytics/metrics` (Kilometer je Kategorie, Gesamtkilometer, `UNASSIGNED` separat, `definition_version`, `as_of`, Vollständigkeit); `/v1/vehicles/{id}/total-cost` nur als Bezugsgröße (OP-20). 404 für Fahrzeuge fremder Mandanten/Dienste, 422 bei ungültigem Zeitraum. Der Pfad steht im Endpunktkatalog (Kap. 05 §5.7), ist aber nicht in `api/*.yaml` ausformuliert; der Bau ergänzt das Schema.
- **Zustandsautomat:** keiner. **JSON-Schema / Ereignisse:** keine neuen; gelesen werden Fahrtenplan (WP-M2-16, WP-M2-10), Fahrerereignisse Einstieg/Ausstieg und Positionsdaten (WP-M3-29, WP-M3-34) über die Leseports aus WP-M4-02.
- **Parameter und Kennzahlen:** `Leerkilometerquote` (Kilometer ohne Fahrgäste, Nenner alle Fahrzeugkilometer im Betriebsdienst, Werkstattüberführung getrennt) und `Produktive Kilometer` (Katalog K.2) nur als Datenbasis; Formeln selbst in WP-M4-56. Nullnenner ergibt `null` + `ZERO_DENOMINATOR`.
- **Klassifikationsregel (Vorschlag, in WP-M4-01 freizugeben):** Fahrgastfahrt = Abschnitt mit mindestens einem Fahrgast an Bord (Einstieg bis Ausstieg, unabhängig von der Anzahl); Leerfahrt = Betriebsdienst ohne Fahrgast; Überführung = Fahrt ohne Fahrgast zwischen Depot/Standorten ohne Dienstbezug; Werkstattfahrt = Fahrt zu/von einer Werkstatt; alles ohne Fahrtenplan-/Ereignisdeckung = `UNASSIGNED` (z. B. Lücke in den Positionsdaten).
- **Oberfläche:** Anzeige in der Kostenübersicht UI-15 und im Kennzahlenteil (WP-M4-44, WP-M4-59): Kilometerklassen mit separater Zeile „nicht zuordenbar“, Datenstand und Vollständigkeit; leer: „keine Fahrten im Zeitraum“; Rechte: Finanz-/Analyserolle, keine Fahrerbezüge.
- **Entscheidungen:** D-07 (Leer-/Besetztkilometer-Definition) und D-09 (Telematik) blockieren nur die Produktivschaltung, nicht den Bau.
- **Offene Punkte:** OP-22 (Kategorien Überführung/Werkstattfahrt ohne Datenmodell, Distanzquelle Routing oder Messung, „dokumentierte Musterfahrt“ muss in WP-M4-01 erstellt und freigegeben werden; Fixture-Seed in WP-M4-02); OP-21 (kein Kilometerstand-Modell). Der Bau der Klassifikation ist nicht blockiert, der endgültige Abnahmelauf braucht die freigegebene Musterfahrt.

## Tests

- **Positiv:** dokumentierte Musterfahrt (Fahrgastfahrt, Leerfahrt, Überführung, Werkstattfahrt) ergibt die erwarteten Kilometer je Kategorie; Gesamtkilometer = Summe der vier Kategorien + `UNASSIGNED` je Fahrzeug und Betriebstag (exakt, in `km_thousandths`); die Anzeige weist `UNASSIGNED` als eigene Zeile „nicht zuordenbar“ aus.
- **Negativ:** Lücke in den Positionsdaten erhöht `UNASSIGNED` statt eine Kategorie aufzufüllen; Fahrzeug ohne Fahrtenplan: gesamte Strecke `UNASSIGNED`, keine Kategorie erfunden; negative Kilometer, unbekannte Kategorie und überlappende Abschnitte desselben Fahrzeugs werden abgewiesen (CHECK/Prüfung); Nullnenner bei Quoten ergibt `ZERO_DENOMINATOR`; ungültiger Zeitraum ergibt 422.
- **Konkurrierend:** nachträglich eintreffende Positionsdaten während der Klassifikation ändern das Ergebnis nur über neuen Datenstand (`dataset_watermark`), nicht still; Mandant A und B parallel zeigen keine Vermischung (RLS), Fahrzeug eines fremden Mandanten liefert 404.
- **Referenztests:** keine Katalog-Testfälle im WP genannt; Fixture Musterfahrt aus WP-M4-02.

Testfälle: backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/KmClassificationTest.kt::musterfahrt_summe_der_kategorien_gleich_gesamtkilometer; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/KmClassificationTest.kt::luecke_in_positionsdaten_erhoeht_nicht_zuordenbar; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/KmClassificationTest.kt::fahrzeug_ohne_fahrtenplan_alles_nicht_zuordenbar; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/KmClassificationTest.kt::nachtraegliche_daten_aendern_nur_ueber_neuen_datenstand; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/KmClassificationTest.kt::fremdes_fahrzeug_ergibt_404_und_ungueltiger_zeitraum_422; db/tests/test_vehicle_km_segment.py::test_check_und_ueberlappung_werden_abgewiesen; db/tests/test_vehicle_km_segment.py::test_rls_trennt_mandanten; web/operator/src/costs/KmClassesView.test.tsx::zeigt_nicht_zuordenbar_als_eigene_zeile; backend/app/src/test/kotlin/de/taktovia/app/reports/AnalyticsMetricsApiTest.kt::kilometer_je_kategorie_mit_nicht_zuordenbar_separat

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).

## Bau-Korrekturen (WP-M4-42)

- Statt `dataset_watermark_id` trägt jeder Lauf (`vehicle_km_run`) Datenstand `data_as_of` und Eingangsfingerabdruck `input_sha256` (`dataset_watermark` hat keine Kennung; die Vollständigkeit der Fahrerereignisse geht als `events_complete` ein). Ein geänderter Eingangszustand ist ein neuer Lauf, der alte bleibt lesbar.
- Überlappungsverbot gilt je Lauf (Exclusion Constraint); Summeninvariante als deferred Constraint-Trigger. Distanzquelle ist `POSITION` (Gesamtstrecke = Positionsverlauf); `ROUTING`/`ODOMETER` sind im CHECK zugelassen, aber nicht gebaut.
- Betriebstag = UTC-Tag; Lücke = mehr als 300 s zwischen Positionen; Überführung/Werkstattfahrt über die Schichtfunktion (Text enthält `transfer`/`überführ` bzw. `workshop`/`werkstatt`). Alles Planungsannahmen zu OP-22 (Regelversion 1).
- Nicht gebaut: Quoten (`Leerkilometerquote`, Nullnenner `ZERO_DENOMINATOR`) gehören laut WP zu WP-M4-56; `/v1/vehicles/{id}/total-cost` als Bezugsgröße (OP-20) nicht verändert; Dienstfilter (404 für fremde Dienste) nicht umgesetzt; Anbindung der Maske an die Kostenübersicht UI-15 folgt mit WP-M4-44.

