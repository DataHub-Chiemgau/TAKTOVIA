# LH-6.21-CHK-005 – Pflichtvorlagen Fahrzeugtechnik und Fahrgastraum

- **Verantwortlicher:** Rolle Betrieb (Modul `inspection`)
- **Arbeitspaket:** WP-M3-11
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Zur technischen Fahrzeugprüfung gehören mindestens Beleuchtung, Reifen-Sichtprüfung, Scheiben, Spiegel, Türen, Sicherheitsgurte, sichtbare Leckagen und Warnanzeigen.

**Originalabnahme (unverändert):** Eine Fahrzeugvorlage enthält alle Pflichtpunkte und erlaubt die Erfassung eines Mangelbefunds je Punkt.

## Vertrag

- **Tabellen / DDL:** Kern-DDL V1 (`db/migrations/V1__kernschema.sql`) enthält nur `vehicle` (`active_block`, `safety_revision`, `capacity_config`), `staff_shift` und `resource_release` (`vehicle_id`, `shift_id`, `safety_revision`, `inspection_ref`, `qualification_revision`, `valid_until`, `revoked_at`); die CHK-Tabellen `checklist_template_revision`, `checklist_step`, `inspection_run`, `inspection_answer` (Kap. 04 §4.4) fehlen in V1/V2/V3 und kommen mit einer neuen Migration `V<n>__inspection.sql` (WP-M3-10/-14). Seed-Daten der Standardvorlage Fahrzeugtechnik in `checklist_template_revision`/`checklist_step` mit stabilen Schlüsseln: `beleuchtung`, `reifen_sichtpruefung`, `scheiben`, `spiegel`, `tueren`, `sicherheitsgurte`, `sichtbare_leckagen`, `warnanzeigen` (die acht Pflichtpunkte; Schlüsselschreibweise mit dem Bau festzulegen, WP „Detailverträge vorab“). Mangelbefund je Punkt in `inspection_answer` (Wert `MANGEL` mit Abweichung). Invariante: Freigabe der Vorlagenrevision nur, wenn alle acht Pflichtschlüssel vorhanden sind; freigegebene Revision unveränderlich (Änderung erzeugt eine neue Revision, WP-M3-10). Die Einstufung `safety_critical` je Punkt ist Fachseite und offen (siehe Offene Punkte).
- **API:** Vorlagenfreigabe über die Vorlagenverwaltung aus WP-M3-10 (Pfad unterhalb `/v1/inspections` bzw. Konfiguration, dort festzulegen); Freigabe nach dem Revisionsmuster der Konfiguration (Vier-Augen, WP-M3-10/WP-M1-22). Freigabe einer unvollständigen Vorlage: 422 `application/problem+json` mit Liste fehlender Pflichtschlüssel. Erfassung des Mangels über `POST /v1/inspections` / Antwort je Schritt (WP-M3-14).
- **Zustandsautomat:** Vorlagenrevision: Entwurf → freigegeben → abgelöst (WP-M3-10, `zustaende/checklist_template_revision.md`).
- **JSON-Schema / Ereignisse:** Vorlagen-JSON-Schema aus WP-M3-10 (Eingabetypen); Validator „Pflichtpunkte vollständig“ je Vorlagentyp.
- **Parameter und Kennzahlen:** keine neuen.
- **Oberfläche:** UI-09: Darstellung je Prüfpunkt mit Einzelstatus und Mangelerfassung (Antwortwerte OK, Mangel, Nicht anwendbar). UI-11: Fehlermeldung bei unvollständiger Vorlage. Zustände: leer/Fehler/Rechte nach Rolle Vorlagenpflege.
- **Entscheidungen:** keine
- **Offene Punkte:** Einstufung „sicherheitskritisch“ je Punkt durch die Fachseite (WP „Detailverträge vorab“) offen, blockiert den Bau der Seed-Daten nicht (als Planungsannahme, nicht aus dem Pflichtenheft: Seed ohne Kennzeichen sicherheitskritisch, bis die Fachseite einstuft; das ist vor Produktivschaltung zu entscheiden). OP-16 (Nicht prüfbar als vierte Antwort) betrifft die Antwortwerte. Betreiberspezifische Zusatzpunkte nicht im Umfang.

## Tests

- **Positiv:** freigegebene Standardvorlage enthält alle acht technischen Pflichtpunkte; je Punkt lässt sich ein Mangelbefund erfassen; Abschluss mit allen Punkten OK.
- **Negativ:** Freigabe einer Vorlage, der einer der acht Pflichtpunkte fehlt, wird abgewiesen (je fehlendem Schlüssel ein Testfall, 422 mit Liste der fehlenden Schlüssel); Änderung einer freigegebenen Revision abgewiesen (auch per direktem Datenbankzugriff); Freigabe ohne Rolle Vorlagenpflege 403, Vorlage eines fremden Mandanten 404; Antwortwert außerhalb OK/MANGEL/NICHT ANWENDBAR abgewiesen.
- **Konkurrierend:** zwei gleichzeitige Freigaben derselben Vorlage: genau eine wirksame Revision (Verhalten aus WP-M3-10).
- **Referenztests:** keine Katalog-ID im WP genannt

Testfälle: backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/MandatoryTemplateTest.kt::fahrzeugvorlage_enthaelt_acht_pflichtpunkte_und_erfasst_mangel_je_punkt; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/MandatoryTemplateTest.kt::freigabe_ohne_pflichtpunkt_wird_je_schluessel_abgewiesen; backend/app/src/test/kotlin/de/taktovia/app/fleet/MandatoryTemplateApiTest.kt::unvollstaendige_pflichtvorlage_422_vollstaendige_wird_freigegeben; db/tests/test_inspection_template.py::test_freigegebene_revision_ist_unveraenderlich; db/tests/test_inspection_template.py::test_unvollstaendige_pflichtvorlage_nicht_veroeffentlichbar_je_fehlendem_schluessel

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
