# LH-6.21-CHK-008 – Schichtstartwerte mit Plausibilitätsprüfung

- **Verantwortlicher:** Rolle Betrieb (Modul `inspection`)
- **Arbeitspaket:** WP-M3-17
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Kilometerstand, Energie- bzw. Tankstatus sowie Fahrzeugidentität sind als Schichtstartwerte erfassbar; Plausibilitätsprüfungen erkennen Unterschreitungen oder Verwechslungen.

**Originalabnahme (unverändert):** Offensichtlich unplausible Werte müssen überprüft oder mit Begründung freigegeben werden.

## Vertrag

- **Tabellen / DDL:** Kern-DDL V1 (`db/migrations/V1__kernschema.sql`) enthält nur `vehicle` (`active_block`, `safety_revision`, `capacity_config`), `staff_shift` und `resource_release` (`vehicle_id`, `shift_id`, `safety_revision`, `inspection_ref`, `qualification_revision`, `valid_until`, `revoked_at`); die CHK-Tabellen `checklist_template_revision`, `checklist_step`, `inspection_run`, `inspection_answer` (Kap. 04 §4.4) fehlen in V1/V2/V3 und kommen mit einer neuen Migration `V<n>__inspection.sql` (WP-M3-10/-14). `inspection_answer` mit typisierten Werten für Eingabetypen Eingabetypen Kilometerstand, Energie/Tank und Fahrzeugidentität (Typnamen Arbeitsnamen, mit WP-M3-10 festzulegen): Kilometerstand als ganze Zahl, Energie in Prozent 0–100 (Darstellung des Tankstatus bei Verbrennern ist im WP nicht festgelegt, Teil von OP-15), Fahrzeugidentität im Abgleich mit `staff_shift.vehicle_id` des Prüflaufs; Spalte für Plausibilitätsstatus (Arbeitsnamen: `PLAUSIBEL` | `UEBERPRUEFUNG_ERFORDERLICH` | `BEGRUENDET_FREIGEGEBEN`; Zustand „korrigiert“ ist ein neuer plausibler Wert) sowie Akteur, Zeit und Begründung der Freigabe. Spalte am `vehicle` für den letzten bestätigten Kilometerstand (Arbeitsname `last_confirmed_odometer_km`, Migration, in V1 nicht vorhanden); Fortschreibung erst beim Prüfabschluss mit plausiblem oder begründet freigegebenem Wert, nur monoton steigend (Fortschreibung als bedingtes UPDATE unter Zeilensperre auf `vehicle`, nie auf einen kleineren Wert). Startwerte je Prüflauf unveränderlich lesbar (kein UPDATE nach Abschluss).
- **API:** Antwortabgabe/`POST /v1/inspections/{id}/submit`: Kilometerstand < `last_confirmed_odometer_km` oder abweichende Identität liefert Zustand `UEBERPRUEFUNG_ERFORDERLICH` mit Vorwert (Fehlerantwort als `application/problem+json`; HTTP-Status im OpenAPI-Detailvertrag, WP-M3-14) bis Korrektur oder Begründung; der Abschluss des Prüflaufs ist solange abgewiesen; begründete Freigabe als eigene Aktion mit Pflichtfeld `reason`. Energie außerhalb 0–100: 422. Begründung ohne Text (leer): abgewiesen. Akteur und Zeit der Freigabe setzt der Server aus der Sitzung.
- **Zustandsautomat:** Antwort: erfasst → Überprüfung erforderlich → korrigiert | begründet freigegeben; Prüflauf kann bei offener Überprüfung nicht abgeschlossen werden (`zustaende/inspection_run.md`).
- **JSON-Schema / Ereignisse:** Prüfnachweis (unveränderlich) enthält Akteur, Zeit, Begründung der Freigabe.
- **Parameter und Kennzahlen:** `min_battery_soc_pct` (Fahrzeugtyp): Anwendung beim Schichtstart offen (OP-15); keine weiteren.
- **Oberfläche:** UI-09: Erfassung Kilometerstand/Energie/Fahrzeugidentität, Warnung mit Vorwert, Pflichtbegründung; Zustände: Fehler, Überprüfung erforderlich, Rechte für begründete Freigabe.
- **Entscheidungen:** keine
- **Offene Punkte:** OP-15 (Plausibilitätsregeln): Schwellen für „offensichtlich unplausibel“ über die Unterschreitung des letzten Werts hinaus, Art der Identitätsprüfung (Eingabe oder Scan), die Rolle, die mit Begründung freigeben darf (Fahrer selbst oder Leitstelle), und ob `min_battery_soc_pct` beim Schichtstart greift, sind nicht festgelegt. Bau blockiert: nur die Regeln Kilometerstand < Vorwert und Identität ≠ zugewiesenes Fahrzeug sind festgelegt und baubar; die Freigaberolle muss vor Bau von UI-09-Freigabe entschieden sein.

## Tests

- **Positiv:** plausible Werte (Kilometerstand >= Vorwert, Energie 0–100, Identität = zugewiesenes Fahrzeug) werden je Prüflauf gespeichert und später unverändert gelesen.
- **Negativ:** Kilometerstand unter Vorwert ohne Begründung wird nicht gespeichert; Start-Werte eines abgeschlossenen Laufs lassen sich weder per API noch per Datenbank ändern (UPDATE/DELETE scheitert); Prüflauf/Fahrzeug eines fremden Mandanten 404; falsches Fahrzeug verhindert den Abschluss bis zur Klärung; Energie außerhalb 0–100 abgewiesen; begründete Freigabe durch nicht berechtigte Rolle abgewiesen; begründete Freigabe speichert Akteur, Zeit, Begründung.
- **Konkurrierend:** zwei gleichzeitige Submits desselben Laufs: genau ein Abschluss; zwei Prüfabschlüsse desselben Fahrzeugs mit unterschiedlichem Kilometerstand: letzter bestätigter Wert am Fahrzeug bleibt konsistent und fällt nicht zurück (ein später abgeschlossener Lauf mit kleinerem Wert verlangt Überprüfung und senkt den Vorwert nicht).
- **Referenztests:** keine Katalog-ID im WP genannt

Testfälle: geplant:backend/inspection/src/test/kotlin/de/taktovia/inspection/ShiftStartValuesTest.kt::kilometerstand_unter_vorwert_verlangt_begruendung; geplant:backend/inspection/src/test/kotlin/de/taktovia/inspection/ShiftStartValuesTest.kt::falsche_fahrzeugidentitaet_und_energie_ausserhalb_0_100_verhindern_abschluss; geplant:db/tests/test_inspection_odometer.py::test_startwerte_nach_abschluss_unveraenderlich; geplant:db/tests/test_inspection_odometer.py::test_letzter_kilometerstand_faellt_nicht_zurueck

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
