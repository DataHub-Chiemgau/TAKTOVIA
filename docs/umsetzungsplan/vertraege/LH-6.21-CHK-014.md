# LH-6.21-CHK-014 – Prüfentwurf und versionierte Korrektureinträge

- **Verantwortlicher:** Rolle Flottensicherheit (Modul `fleet-safety`)
- **Arbeitspaket:** WP-M3-15
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Zwischengespeicherte Prüfungen sind bis zum Abschluss bearbeitbar; nach Abschluss nur über versionierte Korrektur- oder Ergänzungseinträge.

**Originalabnahme (unverändert):** Nachträgliche Bearbeitung überschreibt niemals die ursprünglichen Antworten.

## Vertrag

- **Tabellen / DDL:** `inspection_answer`: Entwurfsantworten bis Abschluss änderbar; danach append-only Nachträge mit Bezug auf die Originalantwort, Feldern Autor, Zeit, Grund (NOT NULL), Version (je Originalantwort fortlaufend, UNIQUE (Original, Version)) und Art Korrektur|Ergänzung. DDL aus WP-M3-02 (in V1–V3 nicht vorhanden); `tenant_id` und RLS. Invariante: Originalzeile eines abgeschlossenen Prüflaufs wird nie geändert (siehe CHK-013).
- **API:** Pfade in `api/*.yaml` noch nicht vorhanden. Antworten speichern (Entwurf) und Nachtrag anlegen unterhalb `/v1/inspections/{id}` (Pfade im OpenAPI-Entwurf festzulegen, z. B. `.../answers` und `.../amendments`). Nachtrag ohne Grund: 422; direkte Änderung nach Abschluss: 409/422 (Fehlercode im OpenAPI festzulegen); unberechtigte Rolle: 403/404 nach Konvention Kap. 05. Antwort-Speichern nach Abschluss ist nur als Nachtrag zulässig; Speichern eines Entwurfs parallel zum Abschluss darf keine Antwort still verlieren (`If-Match` des Prüflaufs: 412/428 nach Kap. 05 §5.1). Nachtrag hebt keine Sperre auf und erteilt keine Freigabe (Planungsannahme des WP: nur neue Prüfung bzw. WP-M3-51).
- **Zustandsautomat:** Prüflauf: „in Bearbeitung" (Entwurf änderbar) -> „abgeschlossen" (nur Nachträge).
- **JSON-Schema / Ereignisse:** keine eigenen; Nachtrag im `audit_event`.
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-09 (`mobile/driver`) – Fortsetzen einer begonnenen Prüfung (Entwurf nach App-Neustart vorhanden); UI-11 – Nachtrag mit Pflichtgrund, Original und Nachträge nebeneinander.
- **Entscheidungen:** keine.
- **Offene Punkte:** Regel, ob ein Nachtrag Ergebnis oder Freigabe verändern darf, ist nicht im Pflichtenheft festgelegt; Planungsannahme „nein" muss vor Codebeginn bestätigt werden. Berechtigte Rolle für Nachträge nicht festgelegt (gebaut als Planungsannahme: Werkstatt und Betriebsleitung, Scope `inspections:amend`). Offline-Entwurf auf dem Gerät ist WP-M3-33.

## Tests

- **Positiv:** Entwurf ändern, nach App-Neustart vorhanden; nach Abschluss Nachtrag mit Grund erzeugt neuen versionierten Eintrag, Originalantwort bleibt unverändert lesbar.
- **Negativ:** direkte Änderung (UPDATE/DELETE) nach Abschluss auf API- und DB-Ebene abgewiesen und Original unverändert; Entwurf-Speichern einer Antwort nach Abschluss abgewiesen; Nachtrag ohne Grund abgewiesen; Nachtrag durch unberechtigte Rolle abgewiesen; Nachtrag ändert weder Sperre noch Freigabe noch Ergebnis (Planungsannahme, siehe Offene Punkte); Nachtrag zu fremdem Prüflauf bzw. anderem Mandanten (404).
- **Konkurrierend:** zwei gleichzeitige Nachträge zur selben Antwort – beide erhalten, mit fortlaufenden, eindeutigen Versionen (UNIQUE (Original, Version) verhindert Doppelvergabe); Entwurf-Speichern parallel zum Abschluss – Antwort ist entweder im Abschluss enthalten oder wird abgewiesen, nie still verworfen.
- **Referenztests:** keine Katalog-ID im WP genannt (verwandt: AT-32).
- Testfälle: backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionAmendmentTest.kt::entwurf_aenderbar_und_nach_neustart_vorhanden; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionAmendmentTest.kt::nachtrag_ueberschreibt_original_nicht; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionAmendmentTest.kt::nachtrag_ohne_grund_wird_abgewiesen; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionAmendmentTest.kt::direkte_aenderung_nach_abschluss_abgewiesen; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionAmendmentTest.kt::nachtrag_durch_unberechtigte_rolle_abgewiesen; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionAmendmentTest.kt::nachtrag_aendert_weder_sperre_noch_freigabe_noch_ergebnis; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionAmendmentTest.kt::gleichzeitige_nachtraege_erhalten_fortlaufende_versionen; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/InspectionAmendmentTest.kt::entwurf_speichern_parallel_zum_abschluss_kein_stiller_verlust; backend/app/src/test/kotlin/de/taktovia/app/fleet/InspectionAmendmentApiTest.kt::entwurf_speichern_if_match_und_nach_abschluss_nur_nachtrag; backend/app/src/test/kotlin/de/taktovia/app/fleet/InspectionAmendmentApiTest.kt::nachtrag_mit_pflichtgrund_rechten_und_unveraenderter_originalantwort; db/tests/test_inspection_amendment.py::test_original_antwort_nach_abschluss_nicht_aenderbar; db/tests/test_inspection_amendment.py::test_nachtrag_grund_not_null_und_version_eindeutig; geplant:mobile/driver/test/pruefung_fortsetzen_test.dart::begonnene_pruefung_nach_app_neustart_fortsetzbar

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich (Unveränderlichkeit des Prüfnachweises, Sicherheit).
