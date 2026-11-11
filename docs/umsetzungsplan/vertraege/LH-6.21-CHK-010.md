# LH-6.21-CHK-010 – Einsatzbereitschaft und Qualifikationsprüfung

- **Verantwortlicher:** Rolle Betrieb (Modul `inspection`, Qualifikation aus `drivers`)
- **Arbeitspaket:** WP-M3-19
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Der Fahrer bestätigt seine grundsätzliche Einsatzbereitschaft ohne Diagnosen oder medizinische Detailangaben; Berechtigungen/Qualifikationen werden anhand freigegebener Stammdaten geprüft.

**Originalabnahme (unverändert):** Die Anwendung speichert keine Freitext-Gesundheitsdaten zur Selbstauskunft und sperrt fehlende erforderliche Qualifikationen.

## Vertrag

- **Tabellen / DDL:** Kern-DDL V1 (`db/migrations/V1__kernschema.sql`) enthält nur `vehicle` (`active_block`, `safety_revision`, `capacity_config`), `staff_shift` und `resource_release` (`vehicle_id`, `shift_id`, `safety_revision`, `inspection_ref`, `qualification_revision`, `valid_until`, `revoked_at`); die CHK-Tabellen `checklist_template_revision`, `checklist_step`, `inspection_run`, `inspection_answer` (Kap. 04 §4.4) fehlen in V1/V2/V3 und kommen mit einer neuen Migration `V<n>__inspection.sql` (WP-M3-10/-14). `inspection_answer`: für das Element „Einsatzbereitschaft“ ausschließlich Wert `JA`|`NEIN`, KEIN Textfeld (per Schema und CHECK erzwungen: Freitextspalte für dieses Element NULL/verboten). Qualifikationsabgleich gegen `qualification_revision` (Modul DRV/FLT, nur freigegebene Revisionen); `resource_release.qualification_revision` (V1) hält die verwendete Revision. `tenant_id`, RLS; Qualifikationsdetails verlassen das Personalmodul nicht.
- **API:** `POST /v1/inspections/{id}/submit`: Request mit Zusatztext/Zusatzfeld zur Einsatzbereitschaft wird abgewiesen (422 `application/problem+json`, strikte Schemaprüfung `additionalProperties:false`); fehlende oder abgelaufene Qualifikation: keine `resource_release`, Dienst gesperrt, Fahrermeldung neutral ohne Personaldetails; „Nein“ zur Einsatzbereitschaft: keine Freigabe, nur Ja/Nein gespeichert. Logs enthalten keine Selbstauskunft-Inhalte. Die Prüfung erfolgt beim Submit und in der Einsatzfreigabe (WP-M3-08, Kriterium 1: gültige Schicht, gültige Qualifikationsrevision, bestätigte Prüfung, keine aktive Sperre im selben Commit); `Idempotency-Key`/`If-Match` laut WP-M3-14. Ob „Dienst gesperrt“ als Dienstsperre abgebildet wird, hängt am Datenmodell der Dienstsperre (OP-01).
- **Zustandsautomat:** Prüflauf (`zustaende/inspection_run.md`); Einsatzfreigabe nur bei Qualifikation gültig und Bestätigung `JA`.
- **JSON-Schema / Ereignisse:** JSON-Schema der Selbstauskunft `schemas/self-declaration.v1.schema.json` (nur Boolean, keine Freitextfelder; Dateiname mit dem Bau). Hinweis an die Leitstelle ohne HR-Inhalte (über WP-M3-52, ohne Diagnosen).
- **Parameter und Kennzahlen:** keine neuen.
- **Oberfläche:** UI-09: Bestätigung mit neutralem Wortlaut (Wortlaut offen); verständliche Meldung bei fehlender Qualifikation ohne Personaldetails. Zustände: Fehler, gesperrt.
- **Entscheidungen:** D-10 (Fahrerdaten, Mitbestimmung): blockiert nur die Produktivschaltung, nicht den Bau.
- **Offene Punkte:** Anforderungsmatrix Qualifikation je Fahrzeugtyp/Dienst, Wortlaut der Selbstauskunft und Verhalten bei „nicht einsatzbereit“ (WP „Detailverträge vorab“) offen; Die Matrix blockiert den Bau des Abgleichs (bis dahin nur Mechanismus gegen `qualification_revision`). Ruhezeit/Höchstarbeitszeit und AT-39 sind M4 und nicht Teil dieses Vertrags.

## Tests

- **Positiv:** gültige freigegebene Qualifikation und Bestätigung `JA`: Freigabe mit verwendeter `qualification_revision` in `resource_release`.
- **Negativ:** Request mit Zusatztext zur Einsatzbereitschaft abgewiesen, weder Datenbank noch Logs enthalten Freitext; fehlende oder abgelaufene Qualifikation: keine Freigabe, Dienst gesperrt, Meldung ohne Personaldetails; Bestätigung `NEIN`: keine Freigabe, kein Grund gespeichert; nicht freigegebene Qualifikationsrevision wird nicht verwendet; fremder Mandant/Dienst ohne Zugriff (404/403); Hinweis an die Leitstelle enthält keine HR-Inhalte und keine Diagnosen.
- **Konkurrierend:** Qualifikationsentzug parallel zum Submit: keine Freigabe auf alter Revision; zwei gleichzeitige Submits: genau eine Freigabe.
- **Referenztests:** keine Katalog-ID im WP genannt (AT-39 ist M4)

Testfälle: geplant:backend/inspection/src/test/kotlin/de/taktovia/inspection/ReadinessQualificationTest.kt::fehlende_qualifikation_sperrt_und_selbstauskunft_ohne_freitext; geplant:backend/inspection/src/test/kotlin/de/taktovia/inspection/ReadinessQualificationTest.kt::nein_zur_einsatzbereitschaft_gibt_nicht_frei_und_speichert_keinen_grund; geplant:db/tests/test_inspection_selfdeclaration.py::test_einsatzbereitschaft_hat_keine_textspalte; geplant:backend/inspection/src/test/kotlin/de/taktovia/inspection/ReadinessQualificationTest.kt::qualifikationsentzug_parallel_zum_submit_gibt_nicht_frei

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
