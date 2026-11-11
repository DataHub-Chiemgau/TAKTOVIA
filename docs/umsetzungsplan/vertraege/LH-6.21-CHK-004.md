# LH-6.21-CHK-004 – Kritischer Mangel sperrt synchron

- **Verantwortlicher:** Rolle Betrieb (Modul `inspection`, Sperre über Modul `fleet`)
- **Arbeitspaket:** WP-M3-16
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Sicherheitskritische Prüfelemente werden gesondert gekennzeichnet und lösen bei negativer Antwort automatisch eine Fahrzeug- oder Dienstsperre aus.

**Originalabnahme (unverändert):** Ein als kritisch markierter Mangel verhindert die automatische Einsatzfreigabe.

## Vertrag

- **Tabellen / DDL:** Kern-DDL V1 (`db/migrations/V1__kernschema.sql`) enthält nur `vehicle` (`active_block`, `safety_revision`, `capacity_config`), `staff_shift` und `resource_release` (`vehicle_id`, `shift_id`, `safety_revision`, `inspection_ref`, `qualification_revision`, `valid_until`, `revoked_at`); die CHK-Tabellen `checklist_template_revision`, `checklist_step`, `inspection_run`, `inspection_answer` (Kap. 04 §4.4) fehlen in V1/V2/V3 und kommen mit einer neuen Migration `V<n>__inspection.sql` (WP-M3-10/-14). Für diese Anforderung zusätzlich: `checklist_step.safety_critical boolean NOT NULL` und `checklist_step.block_kind` (`VEHICLE`|`SERVICE`, Pflicht, wenn `safety_critical`), `inspection_run.result` (Ergebniswerte laut WP-M3-14: bestanden, eingeschränkt, gesperrt, technisch ungeklärt; die Schreibweise der Konstanten, im Folgenden `GESPERRT`/`BESTANDEN`, ist Arbeitsname und wird mit WP-M3-14 festgelegt). Alle Tabellen mit `tenant_id` und RLS (Kap. 04 §4.5). Invariante: Submit speichert Prüfergebnis, Sperre (`vehicle.active_block=true` bzw. Dienstsperre) und Outbox-Ereignis in EINER Transaktion; bei `result='GESPERRT'` existiert keine `resource_release` mit `inspection_ref` dieses Laufs (CHECK/Trigger oder Submit-Regel mit DB-Test). Die Sperre widerruft im selben Commit jede bereits gültige `resource_release` des Fahrzeugs (`revoked_at` gesetzt) und erhöht `vehicle.safety_revision` (WP-M3-08, Kriterien 2 und 7); dieselbe Sperre aus derselben Quelle erzeugt keinen zweiten Sperrgrund und keinen zweiten Revisionssprung. Die Antworten eines abgeschlossenen Laufs sind unveränderlich (WP-M3-14).
- **API:** `POST /v1/inspections/{id}/submit` (Kap. 05: Fahrer/Check, technische Einsatzfreigabe ausschließlich serverbestätigt; Header `Idempotency-Key` und `If-Match` laut WP-M3-14). Antwort bei kritischem Nein: Ergebnis `GESPERRT`, Sperrart, Vorgangsnummer, Kontakt Leitstelle, keine `resource_release`; Wiederholung mit `Idempotency-Key` liefert dieselbe Antwort, anderer Inhalt unter demselben Schlüssel `409 IDEMPOTENCY_CONFLICT`. Prüfer-ID, Mandant und Ergebnis kommen vom Server, vom Client gelieferte Werte werden ignoriert bzw. abgewiesen (WP-M3-14). Die technische Einsatzfreigabe ist nur durch die Serverbestätigung möglich: eine Bereitmeldung ohne gültige `resource_release` wird abgewiesen. 401/403 bei fremdem Prinzipal, 404 bei fremdem Mandanten, 409 bei veralteter `safety_revision`, 422 `application/problem+json` bei ungültigen Antworten. Der Fahrer hat keinen Endpunkt zum Aufheben (Aufheben: WP-M3-51).
- **Zustandsautomat:** Prüflauf: in Arbeit → abgeschlossen (Ergebnis `FREIGABEFÄHIG` | `GESPERRT`); Sperre wird aus dem Submit abgeleitet. Zustandsautomat `zustaende/inspection_run.md` (mit WP-M3-14 zu liefern).
- **JSON-Schema / Ereignisse:** Ereignis `Fahrzeug gesperrt (Quelle Prüfung)` über Outbox (`api/events/`, Schemaname mit Bau festzulegen); Alarm an die Leitstelle über WP-M3-52, genau ein Alarm je Sperre (Quellereignis aus Prüflauf+Schritt, Dedupe `(policy,event,recipient)` laut WP-M3-52; kritische Alarme umgehen die Eskalationsverzögerung; der Alarm enthält keine Fahrgast-PII und keine Diagnosen).
- **Parameter und Kennzahlen:** `workshop_auto_block_severity` (Mandant, Enum; Zusammenspiel laut WP, Werte offen, siehe OP-10); `safety_revision` am Fahrzeug.
- **Oberfläche:** UI-09: kritische Elemente mit Text-/Symbolkennzeichnung und Screenreader-Label (nicht nur Farbe), Sperranzeige mit Vorgangsnummer und Leitstellenkontakt, Fahrer ohne Aufhebe-Aktion. UI-07: Fahrzeug „gesperrt · kritischer Mangel“. Zustände: Fehler (Submit abgewiesen), Rechte (nur eigener Prüflauf). UI-Abnahmefall „Driverprüfpfad mit kritischem Nein“ (Kap. 06 §6.9, Kap. 06 §6.7: Sperre mit Vorgangsnummer und Kontakt zur Leitstelle, Wiederfreigabe nur durch berechtigte Fachrolle).
- **Entscheidungen:** keine
- **Offene Punkte:** OP-01 (Sperrgründe im Schema: `vehicle.active_block` ist ein einzelnes boolean, mehrere Sperrgründe/Aufhebungsrechte und die Bedeutung des Defaults `true` nicht abbildbar). Blockiert den Bau der Sperrtabelle, nicht den Submit-Vertrag, solange `active_block` als Minimalmittel dient; Entscheidung vor Bau von WP-M3-08 nötig. OP-10 (Werte von `workshop_auto_block_severity`). Einstufung „sicherheitskritisch“ und Sperrart je Element durch die Fachseite offen (WP „Detailverträge vorab“). Dienstsperre: Datenmodell der Dienstsperre in V1 nicht vorhanden (offen, gebündelt mit OP-01).

## Tests

- **Positiv:** unkritischer Mangel (`safety_critical=false`) erzeugt keine Sperre und lässt Freigabe zu; Prüflauf ohne Mangel erzeugt `resource_release`. Kritisches Nein mit `block_kind=VEHICLE` setzt `vehicle.active_block=true`, Ergebnis `GESPERRT`, genau ein Alarm. Kritisches Nein mit `block_kind=SERVICE` verhindert die Freigabe der Schicht, Fahrzeug bleibt für andere Schichten nutzbar (Voraussetzung: Datenmodell der Dienstsperre, OP-01). Die Sperrart bestimmt allein das Prüfelement (`block_kind`), nicht Rolle oder Antwort des Fahrers. Eine zuvor gültige `resource_release` des Fahrzeugs ist nach dem Submit widerrufen, `safety_revision` erhöht.
- **Negativ:** kritisches Nein: keine `resource_release`, Bereitmeldung scheitert (Server weist ab); Fahrer-Aufhebeversuch 403; fremder Mandant 404; UI: kritische Elemente ohne Farbe erkennbar (Screenreader-Test); Rollback: scheitert das Schreiben der Sperre, wird auch das Ergebnis nicht gespeichert (kein Zustand „Mangel gespeichert, Fahrzeug disponierbar“); Dispositions-Commit mit veralteter `safety_revision` scheitert (409); Client-Ergebnis `BESTANDEN` im Request wird ignoriert/abgewiesen; Wiederholung mit anderem Inhalt unter gleichem `Idempotency-Key`: 409 `IDEMPOTENCY_CONFLICT`; Alarm enthält keine Fahrgast-PII.
- **Konkurrierend:** Submit mit kritischem Nein parallel zu neuer Zuweisung (PH-AT-020): keine Zusage auf das Fahrzeug; zwei gleichzeitige Submits desselben Laufs: genau ein Ergebnis, genau ein Alarm; Sperre und neue Zuweisung in zwei parallelen Transaktionen: in keiner Serialisierung entsteht eine Zusage auf das gesperrte Fahrzeug.
- **Referenztests:** PH-AT-020

Testfälle: PH-AT-020; geplant:backend/inspection/src/test/kotlin/de/taktovia/inspection/CriticalDefectBlockTest.kt::kritisches_nein_sperrt_synchron_und_verweigert_freigabe; geplant:db/tests/test_inspection_block.py::test_sperre_und_ergebnis_in_einer_transaktion

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
