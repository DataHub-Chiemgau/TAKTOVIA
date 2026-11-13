# LH-I.6-KPI-010 – Kennzahlregister mit Definitionsversion, Nenner und Datenstand

- **Verantwortlicher:** Rolle Analytik (Modul `analytics`)
- **Arbeitspaket:** WP-M4-46
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Für alle Statistikkennzahlen existieren eindeutige Zähler, Nenner, Ausschlüsse, Zeitbezug und Filterdimensionen.

**Originalabnahme (unverändert):** Drei Benutzer erhalten bei identischen Filtern identische Kennzahlen einschließlich Datenstand.

## Vertrag

- **Tabellen / DDL:** `metric_definition_revision` (stabile technische ID, Definitionsversion, Zeitzonen-/Periodenbasis, Zähler, Nenner, Ausschlüsse, Zeitbezug, zulässige Filterdimensionen; unveränderlich nach Freigabe, geänderte Definition erzeugt neue Version), `aggregate_snapshot` (Definition und Version, Filter, Wert, Zähler, Nenner, Datenstand, Vollständigkeit, Datenqualitätsquote; behält seine Version), `dataset_watermark` (Datenstand). Berechnung gegen einen festen Snapshot. Nullnenner ergibt `null` + `ZERO_DENOMINATOR`, unvollständige Quellen `PROVISIONAL` + Abdeckungsgrad. Alle Faktentabellen im Modul `analytics` tragen `tenant_id` mit RLS wie in `db/migrations/V1__kernschema.sql`; kein Schreibzugriff auf Tabellen anderer Module (Architekturtest); keine Fahrgast-Klartextdaten in Fakten. Kleine Gruppen unterhalb der freigegebenen Schwelle werden auch per Differenzbildung benachbarter Filter nicht offengelegt (PH-AT-021, GOV-012).
- **API:** `GET /v1/analytics/metrics` (Definitionen lesen; Werte mit Definition/Version, Filter, Datenstand, Vollständigkeit, Nenner); unbekannte Kennzahl-ID 404, unzulässige Filterdimension 400. `api/analytics.yaml` fehlt noch.
- **Zustandsautomat:** Definition: Entwurf -> freigegeben (danach unveränderlich).
- **JSON-Schema / Ereignisse:** JSON-Schema der Kennzahldefinition unter `docs/umsetzungsplan/vertraege/schemas/` (`<name>.v1.schema.json`, im Bau anzulegen); Antwortschema von `/v1/analytics/metrics`.
- **Parameter und Kennzahlen:** Parameter `reporting_timezone` (IANA-Zeitzone, Ebene Mandant, DST-sicher; Betriebstag lokal, Zeitpunkte absolut). Register der 21 vorgeschlagenen P1-Kennzahlen: `Anfragen`, `Technische Anfragefehler`, `Kapazitätsablehnungen`, `Personalmangelablehnungen`, `Ausstattungsablehnungen`, `Gebietsablehnungen`, `Zeitfensterablehnungen`, `Angebotsquote`, `Buchungskonversion`, `Buchungsstornoquote Fahrgast`, `Buchungsstornoquote Betreiber`, `No-Show-Rate`, `Abholung pünktlich`, `Ankunft pünktlich`, `Mittlere Abholverspätung`, `Mittlere Ankunftsverspätung`, `Leerkilometerquote`, `Produktive Kilometer`, `Kapazitätsauslastung distanzgewichtet`, `Sitzplatz-Zeit-Auslastung`, `Fahrgastkilometer`.
- **Oberfläche:** UI-19 (Kennzahlen) bzw. Zuordnung offen (WP-Dokument OP 23); Zustände: keine Daten, Wert nicht bewertbar (`null` mit Grund), vorläufig (`PROVISIONAL`), fehlende Rechte. Anzeige von Definitionsversion, Nenner und Datenstand bei jedem Wert.
- **Entscheidungen:** keine für den Bau; Zielwerte und verbindliche Berichtsdefinitionen sind AG-Freigabe (D-05, blockiert nur Produktivschaltung).
- **Offene Punkte:** OP 24: "P1-KPIs" sind nicht benannt; die Liste der 21 Kennzahlen ist Vorschlag des WP-Dokuments und braucht Bestätigung, blockiert den Bau des Registers nicht. Kap. 01 §1.6 verlangt Definitionen für 48 Kennzahlen; ausführbar sind in P1 nur die P1-Kennzahlen. Schwelle für kleine Gruppen: freigegebener Wert fehlt (Testwert bis zum Beschluss).

## Tests

- **Positiv:** Fixture PH-AT-021 (zehn Versuche, drei Ablehnungen): drei Benutzer erhalten bei identischen Filtern identische Kennzahlen einschließlich Datenstand; jede Kennzahl weist Zähler, Nenner, Ausschlüsse, Zeitbezug und zulässige Filterdimensionen aus; zehn Versuche, drei Ablehnungen, Summe der Primärgründe drei, Sekundärgründe erzeugen keine zusätzlichen Ablehnungen; bei 20 % fehlenden GPS-Daten wird die Vollständigkeit ausgewiesen und der Wert als vorläufig gekennzeichnet (E2E-084); Betriebstag und Zeitraumgrenzen folgen `reporting_timezone`, DST-Tag 25.10.2026 `Europe/Berlin` hat 25 Stunden und zwei eindeutige Zeitpunkte um 02:30 (PH-AT-018).
- **Negativ:** Nullnenner oder fehlender Nenner ergibt "nicht berechenbar", nie 0 oder 100 %; unbekannte Kennzahl-ID; unzulässige Filterdimension; geänderte Definition erzeugt neue Version, gespeicherte Snapshots behalten ihre Version; UPDATE einer freigegebenen Definition unter der Runtime-Rolle scheitert; Differenzbildung benachbarter Filter bei kleinen Gruppen legt keine Einzelwerte offen; Abfrage in fremdem Mandanten liefert keine Daten; Abfrage ohne Berechtigung liefert 403; Definition mit fehlendem Pflichtfeld (Zähler, Nenner, Ausschlüsse, Zeitbezug, Filterdimensionen) wird nicht freigegeben.
- **Konkurrierend:** Parallele Abfragen während laufender Projektion liefern denselben ausgewiesenen Datenstand; drei Benutzer verschiedener Rollen mit identischen Filtern erhalten identische Werte; gleichzeitige Freigabe zweier Definitionsversionen derselben Kennzahl ergibt eindeutige Versionsfolge.
- **Referenztests:** PH-AT-021, E2E-084; PH-AT-018 (Version bei Definitionsänderung, E2E-103).
- Testfälle: PH-AT-021; E2E-084; db/tests/test_metric_register.py::test_definition_unveraenderlich_und_nullnenner_nicht_berechenbar; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/MetricRegisterTest.kt::drei_benutzer_identische_werte_und_datenstand; backend/app/src/test/kotlin/de/taktovia/app/analytics/MetricsApiTest.kt::drei_benutzer_verschiedener_rollen_erhalten_identische_kennzahlen_samt_datenstand; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/MetricRegisterTest.kt::zwanzig_prozent_fehlende_quelldaten_sind_vorlaeufig_mit_abdeckungsgrad

Umsetzung (wave13-c6, WP-M4-46): Register `metric_definition_revision` (je Mandant, Seed per `seed_metric_definitions()`: 21 P1-Kennzahlen plus Definition der technischen Partnerablehnungen; ausführbar sind in diesem Stand die sieben Versuchskennzahlen, die übrigen weisen `NOT_EXECUTABLE_IN_THIS_RELEASE` aus), `aggregate_snapshot` (nur anhängen), Berechnung in einem Statement (ein Lesestand). `reporting_timezone` kommt über die wirksame Konfiguration, ohne Wert gilt Europe/Berlin (Arbeitsannahme). Schwelle kleiner Gruppen: Testwert 3 (`taktovia.analytics.small-group-threshold`). Die 20 % fehlenden Daten (E2E-084) sind für die Versuchsquelle über Revisionslücken nachgewiesen (Abdeckungsgrad, `PROVISIONAL`); GPS-basierte Kennzahlen folgen mit WP-M4-56. Das JSON-Schema der Definition unter `schemas/` ist durch das API-Schema `MetricDefinition` in `api/analytics.yaml` ersetzt.

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
