# LH-6.21-CHK-006 – Pflichtvorlagen Fahrzeugtechnik und Fahrgastraum

- **Verantwortlicher:** Rolle Betrieb (Modul `inspection`)
- **Arbeitspaket:** WP-M3-11
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Vor der Fahrgastaufnahme sind Sauberkeit, Fahrgastraum, Sitzplätze, Einstiegsbereich, vorhandene Rückhaltesysteme und erforderliche Sicherheitsausrüstung zu prüfen.

**Originalabnahme (unverändert):** Die Prüfung kann mit Einzelstatus und bei Abweichung dokumentiertem Mangel abgeschlossen werden.

## Vertrag

- **Tabellen / DDL:** Kern-DDL V1 (`db/migrations/V1__kernschema.sql`) enthält nur `vehicle` (`active_block`, `safety_revision`, `capacity_config`), `staff_shift` und `resource_release` (`vehicle_id`, `shift_id`, `safety_revision`, `inspection_ref`, `qualification_revision`, `valid_until`, `revoked_at`); die CHK-Tabellen `checklist_template_revision`, `checklist_step`, `inspection_run`, `inspection_answer` (Kap. 04 §4.4) fehlen in V1/V2/V3 und kommen mit einer neuen Migration `V<n>__inspection.sql` (WP-M3-10/-14). Seed der Standardvorlage „Prüfung vor Fahrgastaufnahme“ mit sechs Pflichtschlüsseln: `sauberkeit`, `fahrgastraum`, `sitzplaetze`, `einstiegsbereich`, `rueckhaltesysteme`, `sicherheitsausruestung`. Invariante: Abweichung (Antwort `MANGEL`) verlangt dokumentierten Mangel (Pflichtfeld Mangeldokumentation in `inspection_answer`, Spaltenname Arbeitsname, mit WP-M3-14 festzulegen); Freigabe der Vorlage nur mit allen sechs Pflichtschlüsseln.
- **API:** Wie CHK-005: Vorlagenfreigabe (422 bei fehlendem Pflichtpunkt), Antwortabgabe und `POST /v1/inspections/{id}/submit` (WP-M3-14): Abschluss nur, wenn jeder Punkt einen Einzelstatus hat; Status `MANGEL` ohne Mangeldokumentation: 422 mit Verweis auf den Punkt.
- **Zustandsautomat:** Vorlagenrevision (WP-M3-10) und Prüflauf (`zustaende/inspection_run.md`, WP-M3-14).
- **JSON-Schema / Ereignisse:** Vorlagen-Schema aus WP-M3-10.
- **Parameter und Kennzahlen:** keine neuen.
- **Oberfläche:** UI-09: Einzelstatus je Punkt, bei Abweichung Pflichtfeld Mangeldokumentation; Abschluss-Schaltfläche erst bei vollständigen Einzelstatus. Zustände: Fehler je Punkt.
- **Entscheidungen:** keine
- **Offene Punkte:** Einstufung sicherheitskritisch je Punkt offen (Fachseite). Wann die Prüfung „vor Fahrgastaufnahme“ ausgelöst wird (einmal je Schichtstart im Prüflauf oder vor jeder Fahrgastaufnahme) ist im WP nicht festgelegt und wird hier nicht angenommen; der Vertrag deckt Inhalt und Abschluss. Ob Abweichung bei diesen Punkten sperrt oder nur einen Mangel dokumentiert, ergibt sich aus der Einstufung (CHK-004) und ist bis dahin als nicht sperrend angenommen. OP-16 (Nicht prüfbar).

## Tests

- **Positiv:** Abschluss mit allen sechs Punkten OK; Abschluss mit einem dokumentierten Mangel (Einzelstatus `MANGEL` mit Beschreibung).
- **Negativ:** Abweichung ohne Mangeldokumentation: Abschluss abgewiesen; Freigabe einer Vorlage ohne einen der sechs Pflichtpunkte abgewiesen; Punkt ohne Einzelstatus blockiert den Abschluss (422 mit Verweis auf das Element, WP-M3-10 Kriterium 2); Abschluss-Aufruf eines fremden Prüflaufs/Mandanten 404; Vorlagenfreigabe ohne Rolle Vorlagenpflege 403.
- **Konkurrierend:** zwei gleichzeitige Abschlüsse desselben Prüflaufs: genau ein Ergebnis (Verhalten aus WP-M3-14).
- **Referenztests:** keine Katalog-ID im WP genannt

Testfälle: backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/PassengerCompartmentCheckTest.kt::abschluss_mit_einzelstatus_und_dokumentiertem_mangel; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/PassengerCompartmentCheckTest.kt::abschluss_ohne_einzelstatus_oder_ohne_mangeldokumentation_wird_abgewiesen; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/PassengerCompartmentCheckTest.kt::freigabe_ohne_einen_der_sechs_pflichtpunkte_wird_abgewiesen; db/tests/test_inspection_template.py::test_typ_ist_geschlossene_menge_und_pruefart_vor_fahrgastaufnahme_zulaessig

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
