# LH-I.6-KPI-008 – Ankunftspünktlichkeit getrennt von der Abholung

- **Verantwortlicher:** Rolle Analytik (Modul `analytics`)
- **Arbeitspaket:** WP-M4-54
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Ankunftspünktlichkeit berücksichtigt zugesagte Ankunftszeit oder gewähltes Servicefenster getrennt von Abholung.

**Originalabnahme (unverändert):** Verspätete Ankunft trotz pünktlicher Abholung erscheint in eigener Kennzahl.

## Vertrag

- **Tabellen / DDL:** Nutzt `metric_definition_revision` und `aggregate_snapshot` (KPI-010) sowie Zeitfakten (KPI-006). Abholung und Ankunft werden als getrennte Zeilen bzw. Werte geführt, nie in einem gemeinsamen Pünktlichkeitsflag. Alle Faktentabellen im Modul `analytics` tragen `tenant_id` mit RLS wie in `db/migrations/V1__kernschema.sql`; kein Schreibzugriff auf Tabellen anderer Module (Architekturtest); keine Fahrgast-Klartextdaten in Fakten.
- **API:** `GET /v1/analytics/metrics` (Werte `Ankunft pünktlich`, `Mittlere Ankunftsverspätung` mit Definitionsversion, Filter, Datenstand, Vollständigkeit, Nenner; WP-M4-46); `api/analytics.yaml` existiert noch nicht und ist mit dem Bau anzulegen.
- **Zustandsautomat:** keiner.
- **JSON-Schema / Ereignisse:** Kennzahldefinition nach JSON-Schema aus KPI-010.
- **Parameter und Kennzahlen:** Parameter `on_time_arrival_tolerance_min` (Validierung "keine rückwirkende Statistikänderung"), `arrival_grace_min` (Katalog: vertragsabhängig, nicht nachträglich rückwirkend ändern). Kennzahlen `Ankunft pünktlich` (Zähler: Ankünfte innerhalb zugesagter Ankunftszeit plus Toleranz; Nenner: qualifizierte angekommene Fahrgastfahrten; ohne Zusage gesondert) und `Mittlere Ankunftsverspätung` (Summe positiver Verspätungsminuten; Nenner: bewertbare Ankünfte). Verspätungsminuten (für `Mittlere Ankunftsverspätung`) = tatsächliche Ankunft minus zugesagte späteste Ankunft, nur positive Werte, so dass E2E-083 17 Minuten ergibt; Klassifikation `Ankunft pünktlich` = tatsächliche Ankunft <= zugesagte späteste Ankunft plus Toleranz (Katalog: "innerhalb zugesagter Ankunftszeit plus Toleranz"); unabhängig vom Abholergebnis. Der WP-Wortlaut "minus zugesagte späteste Ankunft plus Toleranz" ist so ausgelegt (siehe Offene Punkte). Bezugsgröße bei gewähltem Servicefenster: Arbeitsannahme Ende des Servicefensters (im WP als Detailvertrag verlangt, nicht festgelegt).
- **Oberfläche:** UI-19 (Kennzahlen) bzw. Zuordnung offen (WP-Dokument OP 23); Zustände: keine Daten, Wert nicht bewertbar (`null` mit Grund), vorläufig (`PROVISIONAL`), fehlende Rechte.
- **Entscheidungen:** D-05 (SLA-Grenzwerte; blockiert nur Produktivschaltung).
- **Offene Punkte:** Verhältnis von `arrival_grace_min` zu `on_time_arrival_tolerance_min` ist nicht festgelegt (Detailvertrag vorab laut WP); Arbeitsannahme (umgesetzt in WP-M4-54): eine wirksame Toleranz je Zusageversion = größerer Wert aus `on_time_arrival_tolerance_min` und `arrival_grace_min` (zum Zusagezeitpunkt aufgelöst, mit beiden Konfigurationsrevisionen in `arrival_tolerance_binding` unveränderlich gebunden; ohne wirksame Karenz gilt 0); Klärung durch Fachanalyse, blockiert den Bau nicht. E2E-083 nennt zusätzlich eine "Ursache" der Ankunftsverspätung; die Ursachenklassifikation ist KPI-016 (P2) und nicht Teil dieses Vertrags (Ursachenfeld siehe KPI-009). OP 29 (Ebene Dienst/Gebiet) gilt analog. Auslegung der Verspätungsformel (Toleranz nur für die Klassifikation, nicht für die Minuten) und Bezugsgröße Servicefenster stehen zur Bestätigung durch die Fachanalyse aus, blockieren den Bau nicht.

## Tests

- **Positiv:** Abholung pünktlich, Ankunft 17 Minuten nach zugesagter Ankunft: Ankunftsverspätung 17 Minuten in `Ankunft pünktlich` bzw. `Mittlere Ankunftsverspätung`, Abholkennzahl bleibt "pünktlich" (E2E-083); Toleranz-Grenzfälle (Ankunft genau zugesagte späteste Ankunft plus Toleranz pünktlich, eine Minute später verspätet); gewähltes Servicefenster als Bezugsgröße; frühe Ankunft wird nicht negativ verrechnet. E2E-083 wird nur im Teil Ankunftsverspätung getrennt von Abholung erfüllt, der Teil "Ursache" gehört zu KPI-016 (P2).
- **Negativ:** Fahrt ohne Ankunftsereignis ist nicht im Nenner; Fahrt ohne Zusage wird gesondert ausgewiesen; geänderter Grenzwert (`on_time_arrival_tolerance_min` oder `arrival_grace_min`) ändert gespeicherte Berichtstände nicht; Fahrt eines fremden Mandanten geht nicht in die Kennzahl ein.
- **Konkurrierend:** Gleichzeitiges Eintreffen von Abhol- und Ankunftsereignis (Reihenfolge vertauscht): beide Kennzahlen bleiben getrennt und korrekt; parallele Abfragen nennen denselben Datenstand.
- **Referenztests:** E2E-083.
- Testfälle: E2E-083; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/ArrivalPunctualityTest.kt::verspaetete_ankunft_trotz_puenktlicher_abholung_eigene_kennzahl; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/ArrivalPunctualityTest.kt::toleranzgrenzfaelle_genau_zugesagt_plus_toleranz_ist_puenktlich_eine_sekunde_spaeter_nicht; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/ArrivalPunctualityTest.kt::bezugsgroesse_bei_gewaehltem_servicefenster_ist_dessen_ende; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/ArrivalPunctualityTest.kt::ankunft_ohne_zusage_wird_gesondert_ausgewiesen_und_nicht_gezaehlt; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/ArrivalPunctualityTest.kt::vertauschte_reihenfolge_von_abhol_und_ankunftsereignis_aendert_beide_kennzahlen_nicht; backend/modules/analytics/src/test/kotlin/de/taktovia/analytics/ArrivalPunctualityTest.kt::parallele_abfragen_und_wechsel_der_toleranz_waehrend_der_berechnung_liefern_denselben_wert_und_datenstand; backend/app/src/test/kotlin/de/taktovia/app/analytics/ConfigArrivalToleranceSourceTest.kt::zwei_gebiete_klassifizieren_dieselbe_ankunftsverspaetung_unterschiedlich_getrennt_von_der_abholung_ende_zu_ende; db/tests/test_arrival_punctuality.py::test_fahrt_ohne_ankunftsereignis_nicht_im_nenner; db/tests/test_arrival_punctuality.py::test_toleranzaenderung_aendert_gespeicherten_stand_nicht

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
