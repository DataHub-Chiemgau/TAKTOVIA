# LH-I.6-KPI-007 – Abholpünktlichkeit gegen Zusagefenster

- **Verantwortlicher:** Rolle Analytik (Modul `analytics`)
- **Arbeitspaket:** WP-M4-53
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Pünktlichkeit der Abholung wird anhand eines konfigurierten Zusagefensters samt Vergleichsstichtag gemessen.

**Originalabnahme (unverändert):** Fahrt mit 3 Minuten Überschreitung wird nach gebietsspezifischem Grenzwert korrekt klassifiziert.

## Vertrag

- **Tabellen / DDL:** Nutzt `metric_definition_revision` (unveränderlich nach Freigabe) und `aggregate_snapshot` aus KPI-010 sowie Zeitfakten aus KPI-006; der Snapshot trägt die Version des verwendeten Grenzwerts (`config_revision_id`). Alle Faktentabellen im Modul `analytics` tragen `tenant_id` mit RLS wie in `db/migrations/V1__kernschema.sql`; kein Schreibzugriff auf Tabellen anderer Module (Architekturtest); keine Fahrgast-Klartextdaten in Fakten.
- **API:** `GET /v1/analytics/metrics` (Werte `Abholung pünktlich`, `Mittlere Abholverspätung` mit Definitionsversion, Filter, Datenstand, Vollständigkeit, Nenner; WP-M4-46); `api/analytics.yaml` existiert noch nicht und ist mit dem Bau anzulegen.
- **Zustandsautomat:** keiner.
- **JSON-Schema / Ereignisse:** Definition der Kennzahlen als JSON-Schema der Kennzahldefinition (KPI-010).
- **Parameter und Kennzahlen:** Parameter `on_time_pickup_tolerance_min` (Katalog Ebene Dienst, Validierung "an historische SLA-Version gebunden"), `pickup_window_min`. Kennzahlen `Abholung pünktlich` (Zähler: Abholungen innerhalb zugesagtem Zeitfenster; Nenner: tatsächliche qualifizierte Abholungen; Zusageversion unveränderlich) und `Mittlere Abholverspätung` (Summe positiver Verspätungsminuten; Nenner: alle bewertbaren Abholungen; frühe Abholung wird nicht negativ verrechnet). Abholverspätung = tatsächliche Abholung minus Ende des zugesagten Abholfensters; Klassifikation: pünktlich bei Verspätung <= Grenzwert. Grenzfälle 2/3/4 Minuten; bei 3 Minuten Überschreitung pünktlich bei Grenzwert >= 3, verspätet bei Grenzwert < 3.
- **Oberfläche:** UI-19 (Kennzahlen) bzw. Zuordnung offen (WP-Dokument OP 23); Zustände: keine Daten, Wert nicht bewertbar (`null` mit Grund), vorläufig (`PROVISIONAL`), fehlende Rechte.
- **Entscheidungen:** D-05 (SLA-Grenzwerte des Piloten; bis zum Beschluss Testwerte, blockiert nur Produktivschaltung).
- **Offene Punkte:** OP 29: Katalog definiert `on_time_pickup_tolerance_min` auf Ebene Dienst, die Anforderung verlangt gebietsspezifisch; Ebene (Dienst/Gebiet) und Bedeutung von "Vergleichsstichtag" (Zeitpunkt der Zusage oder Berichtsdatum) sind festzulegen. Arbeitsannahme für den Bau: Stichtag ist der Zusagezeitpunkt, Grenzwert wird am Gebiet des Dienstes aufgelöst; Änderung der Annahme berührt nur die Auflösung der Konfiguration. Bestätigung steht aus, blockiert den Bau nicht.

## Tests

- **Positiv:** Grenzfälle 2, 3 und 4 Minuten Überschreitung gegen Grenzwert 3 und gegen Grenzwert 2; Zusage 08:10-08:15, Abholung 08:22 ergibt 7 Minuten Abholverspätung gegen die Zusageversion, getrennt von der Ankunft (E2E-082); Zusage 08:10-08:15, Abholung 08:22 ist bei Grenzwert 3 verspätet; zwei Gebiete mit Grenzwert 3 und 2 klassifizieren dieselbe 3-Minuten-Überschreitung unterschiedlich (gebietsspezifisch); frühe Abholung geht mit 0 statt negativer Zahl in `Mittlere Abholverspätung` ein; Grenzwert wird nach Zusagezeitpunkt geändert: die Fahrt bleibt nach dem zum Vergleichsstichtag (Arbeitsannahme: Zusagezeitpunkt, OP 29) wirksamen Grenzwert klassifiziert.
- **Negativ:** Abholung ohne Ist-Zeit ist nicht bewertbar und nicht im Nenner; spätere Änderung des Grenzwerts verändert bereits gespeicherte Berichtstände nicht; unzulässiger Filter wird mit 400 abgewiesen; Fahrt eines fremden Mandanten geht weder in Zähler noch Nenner ein.
- **Konkurrierend:** Grenzwertänderung (neue Konfigurationsrevision) während laufender Berechnung: Snapshot nennt eindeutig die verwendete Revision; parallele Abfragen liefern denselben Wert und Datenstand.
- **Referenztests:** E2E-082.
- Testfälle: E2E-082; geplant:backend/analytics/src/test/kotlin/de/taktovia/analytics/PickupPunctualityTest.kt::drei_minuten_ueberschreitung_nach_gebietsgrenzwert; geplant:db/tests/test_pickup_punctuality.py::test_grenzwertaenderung_aendert_gespeicherten_stand_nicht

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
