# LH-I.6-KPI-013 – Kilometer-, Betriebsstunden- und Fahrgastkilometer-Kennzahlen

- **Verantwortlicher:** Rolle Analytics (Modul `analytics`)
- **Arbeitspaket:** WP-M4-56
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Leerkilometer, produktive Kilometer, Betriebsstunden und Fahrgastkilometer haben definierte Erfassungsgrenzen.

**Originalabnahme (unverändert):** Eine Überführungsfahrt wird nicht als produktive Fahrgaststrecke gezählt.

## Vertrag

- **Tabellen / DDL:** `metric_definition_revision` (Formel und Erfassungsgrenzen je Größe), `aggregate_snapshot`, `dataset_watermark` (Vollständigkeit, z. B. GPS-Abdeckung); Eingang ist die Kilometerklassifikation aus WP-M4-42 (Klassen: besetzt/produktiv, leer, Überführung, Werkstattfahrt, nicht zuordenbar). RLS und `tenant_id`-Indizes nach Kap. 04 §4.5.
- **API:** `GET /v1/analytics/metrics` (in Kap. 05 genannt, noch nicht in `api/openapi.yaml`) liefert `Fahrgastkilometer`, `Produktive Kilometer`, `Leerkilometerquote` mit Zähler, Nenner, Definition und Vollständigkeit.
- **Zustandsautomat:** keiner.
- **JSON-Schema / Ereignisse:** Definitionsschema der Kennzahl mit Feldern Formel, Erfassungsgrenze, Distanzquelle (Version).
- **Parameter und Kennzahlen:** `Produktive Kilometer` = Kilometer mit mindestens einem Fahrgast / alle Fahrzeugkilometer im Betriebsdienst; `Leerkilometerquote` = Kilometer ohne Fahrgäste / alle Fahrzeugkilometer im Betriebsdienst (Werkstattüberführung getrennt); `Fahrgastkilometer` = Summe individueller Fahrtstrecken aller Fahrgäste, kein Nenner. Betriebsstunden als Größe mit Definition. Erfassungsgrenzen: Beginn/Ende des Betriebsdienstes; Überführung und Werkstattfahrt zählen nie als produktiv. Invariante (laut WP-M4-56; WP-M4-42 führt zusätzlich Überführung und Werkstattfahrt als eigene Kategorien, ihre Einordnung in diese Summe ist offen): Leer + produktiv + nicht zuordenbar = Fahrzeugkilometer im Betriebsdienst.
- **Oberfläche:** Kennzahlkarten in UI-19 (WP-M4-59) mit Formelverweis und Vollständigkeit; Zustand „geringe Qualität“ bei Datenlücke.
- **Entscheidungen:** D-07 (Leer-/Besetztkilometer-Definition; blockiert nur Produktivschaltung, nicht den Bau).
- **Offene Punkte:** WP-Dokument OP 22: Für Überführung und Werkstattfahrt fehlt ein Objekt im Datenmodell (Klassifikation in WP-M4-42); Distanzquelle (Routingdistanz mit Version oder Messung) und Definition der „Betriebsstunde“ (LH-I.2-FCO-004) fehlen; die dokumentierte Musterfahrt mit Sollwerten ist in WP-M4-01 zu erstellen. Ob Überführung/Werkstattfahrt im Nenner „Fahrzeugkilometer im Betriebsdienst“ liegen, ist ebenfalls offen (Katalog: „Werkstattüberführung getrennt“). Der Bau der Tests mit Fixture-Werten ist möglich, die Produktivschaltung hängt an D-07.

## Tests

- **Positiv:** Musterfahrt mit zwei Fahrgästen und Teilstrecken ergibt erwartete Fahrzeug-, Besetzt-, Leer- und Fahrgastkilometer; Summe Leer + produktiv + nicht zuordenbar = Fahrzeugkilometer im Betriebsdienst (Invariante gemäß WP-Akzeptanzkriterium 4); Erfassungsgrenzen und Formel je Größe sind über die API abrufbar.
- **Negativ:** Überführungsfahrt wird nicht als produktive Fahrgaststrecke gezählt (Originalabnahme); Werkstattfahrt zählt nicht produktiv und wird getrennt ausgewiesen; Datenlücke (fehlende GPS-Daten für Teil der Fahrten) weist Vollständigkeit aus statt eines scheingenauen Werts (E2E-084); Fahrt außerhalb Betriebsdienst wird nicht gezählt; Betriebsstunden zählen nur innerhalb der definierten Erfassungsgrenze (Beginn/Ende Betriebsdienst) und nie Überführung/Werkstattfahrt als produktiv; Fahrzeug eines anderen Mandanten fließt nicht ein; nicht zuordenbare Kilometer (GPS-Lücke) werden separat ausgewiesen und nicht auf andere Kategorien verteilt.
- **Konkurrierend:** nachträglich korrigierte Kilometerklassifikation während der Berechnung erzeugt eine neue Snapshot-Version statt Überschreiben.
- **Referenztests:** E2E-084.
- Testfälle: E2E-084; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/KilometerMetricsTest.kt::ueberfuehrung_zaehlt_nicht_als_produktive_fahrgaststrecke; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/KilometerMetricsTest.kt::musterfahrt_ergibt_erwartete_kilometer

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
