# LH-6.21-CHK-007 – Barrierefreiheits-Prüfpunkte und eingeschränkte Freigabe

- **Verantwortlicher:** Rolle Betrieb (Modul `inspection`, Readiness für `dispatch`)
- **Arbeitspaket:** WP-M3-12
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Bei entsprechend ausgestatteten Fahrzeugen sind Rampe/Lift, Rollstuhlverankerung, zugängliche Einstiegsstelle und notwendige Bedienelemente als eigene Prüfpunkte verfügbar.

**Originalabnahme (unverändert):** Ein Rollstuhlfahrzeug kann bei nicht funktionsfähiger notwendiger Ausstattung nicht für passende Fahrten freigegeben werden.

## Vertrag

- **Tabellen / DDL:** Kern-DDL V1 (`db/migrations/V1__kernschema.sql`) enthält nur `vehicle` (`active_block`, `safety_revision`, `capacity_config`), `staff_shift` und `resource_release` (`vehicle_id`, `shift_id`, `safety_revision`, `inspection_ref`, `qualification_revision`, `valid_until`, `revoked_at`); die CHK-Tabellen `checklist_template_revision`, `checklist_step`, `inspection_run`, `inspection_answer` (Kap. 04 §4.4) fehlen in V1/V2/V3 und kommen mit einer neuen Migration `V<n>__inspection.sql` (WP-M3-10/-14). Zusätzlich `checklist_step.equipment_condition` (Ausstattungsbedingung, abgeleitet aus `vehicle.capacity_config`), `inspection_run.result='EINGESCHRAENKT'` (Arbeitsname; WP-M3-14: eingeschränkt) mit Angabe der nicht verfügbaren Ressourcendimension (`restriction jsonb`). Vier Prüfpunkte nur bei Ausstattung: Rampe/Lift, Rollstuhlverankerung, zugängliche Einstiegsstelle, notwendige Bedienelemente (Schlüssel mit dem Bau festzulegen). RLS über `tenant_id`.
- **API:** `POST /v1/inspections` erzeugt die Punkte ausstattungsabhängig; `POST /v1/inspections/{id}/submit` liefert Ergebnis `BESTANDEN` | `EINGESCHRAENKT` | `GESPERRT` (Arbeitsnamen laut WP-M3-14; ein Ergebnis „technisch ungeklärt“ bleibt OP-16 vorbehalten); Readiness liefert der Disposition den reduzierten Ressourcenvektor (Leseport WP-M2-14/-15). Fehler: 422 `application/problem+json`, 409 bei veralteter Revision.
- **Zustandsautomat:** Prüflauf (`zustaende/inspection_run.md`); Semantik „eingeschränkt“ im Ressourcenvektor laut OP-17.
- **JSON-Schema / Ereignisse:** Readiness-Änderung mit Ressourcenvektor ohne Rollstuhldimension als Ereignis/Leseport an die Disposition (Schema mit WP-M2-15 abzustimmen).
- **Parameter und Kennzahlen:** `capacity_config` (Fahrzeug) als Quelle der Ausstattung; keine neuen Katalogparameter.
- **Oberfläche:** UI-09: Frage „Rollstuhlrampe funktionsfähig?“ mit Ja / Nein – Mangel melden / Nicht prüfbar (Kap. 06 §6.7); UI-07: Fahrzeug „eingeschränkt“ mit Grund.
- **Entscheidungen:** keine
- **Offene Punkte:** OP-16 (ist „Nicht prüfbar“ eine vierte Antwort neben OK/Mangel/Nicht anwendbar, Status „technisch ungeklärt“?): Vertrag legt fest, dass „Nicht prüfbar“ nie als Ja gilt und nie zur Freigabe für passende Fahrten führt; die Statusabbildung bleibt offen. OP-17 (Wirkung „eingeschränkt“: welche weiteren Mängel führen zu eingeschränkt statt gesperrt; wie die Disposition die Einschränkung erhält) – blockiert den Bau der Disposition-Anbindung, nicht der Prüfpunkte. Einstufung je Punkt (eingeschränkt/gesperrt) durch Fachseite offen.

## Tests

- **Positiv:** Fahrzeug mit Rampe/Lift erhält die vier Prüfpunkte; alle funktionsfähig: volle Freigabe inklusive Rollstuhldimension.
- **Negativ:** Fahrzeug ohne Rampe/Lift (laut `capacity_config`) erhält die vier Punkte nicht und wird nie für Rollstuhlfahrten freigegeben; ein Request mit Antworten zu nicht vorhandenen Punkten wird abgewiesen (422); fremder Mandant 404; „Rampe nicht funktionsfähig“: Anfrage mit Rollstuhlbedarf wird diesem Fahrzeug nicht zugewiesen (je nach Einstufung bleibt es für andere Fahrten nutzbar oder ist gesperrt); „Nicht prüfbar“ gilt nicht als Ja und gibt die Fahrzeuge nicht für passende Fahrten frei.
- **Konkurrierend:** Einschränkung parallel zu einem Hold mit Rollstuhlplatz: kein Commit auf die entfallene Dimension; zwei gleichzeitige Prüfabschlüsse des Fahrzeugs: genau ein wirksames Ergebnis; Submit „Rampe defekt“ parallel zu Dispositions-Commit mit Rollstuhlbedarf: in keiner Serialisierung entsteht eine Zusage auf die entfallene Dimension (Prüfung gegen `safety_revision`/Readiness im selben Commit, WP-M2-22/WP-M3-08).
- **Referenztests:** keine Katalog-ID im WP genannt (inhaltlich verwandt: AT-04 Rollstuhltransport)

Testfälle: backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/AccessibilityReadinessTest.kt::defekte_rampe_schliesst_rollstuhlfahrten_aus; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/AccessibilityReadinessTest.kt::fahrzeug_mit_rampe_erhaelt_vier_pruefpunkte_ohne_rampe_nicht; backend/modules/fleet-safety/src/test/kotlin/de/taktovia/fleetsafety/AccessibilityReadinessTest.kt::nicht_pruefbar_gilt_nicht_als_ja; backend/app/src/test/kotlin/de/taktovia/app/fleet/AccessibilityHoldTest.kt::einschraenkung_parallel_zu_hold_mit_rollstuhlplatz_kein_commit_auf_die_entfallene_dimension

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
