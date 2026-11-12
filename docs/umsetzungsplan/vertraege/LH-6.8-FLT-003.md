# LH-6.8-FLT-003 – Fahrer-/Fahrzeugzuweisung mit eindeutigen Schichtgrenzen

- **Verantwortlicher:** Rolle Flotte/Sicherheit (Modul Staff/Time, Schichtzuweisung)
- **Arbeitspaket:** WP-M3-07
- **Status:** Bereit (unter Planungsannahme PA-03, siehe planungsannahmen.md; keine Entscheidung des Auftraggebers)
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Fahrer- und Fahrzeugzuweisung besitzt eindeutige Schichtgrenzen.

**Originalabnahme (unverändert):** Doppelte Fahrerschicht ohne Ausnahme erzeugt Konflikt.

## Vertrag

- **Tabellen / DDL:** `staff_shift` (V1: `tenant_id`, `id`, `driver_ref uuid NOT NULL`, `vehicle_id uuid NOT NULL`, `starts_at timestamptz NOT NULL`, `ends_at timestamptz NOT NULL`, `timezone text NOT NULL`, `roster_revision bigint NOT NULL`, `state text NOT NULL`; `PRIMARY KEY(tenant_id,id)`; `CHECK(ends_at>starts_at)`; FK `(tenant_id, vehicle_id) -> vehicle`). Additiv (WP-M3-02): FK `driver_ref -> driver`. Neu: Überlappungsverbot je Fahrer und je Fahrzeug über das halboffene Intervall `tstzrange(starts_at, ends_at, '[)')` als Exclusion Constraint (btree_gist) oder gesperrte Intervallprüfung; `[)` erlaubt aneinandergrenzende Schichten (Ende = Beginn). Die Ausnahme (Doppelschicht) muss das Verbot gezielt aufheben können, daher keine pauschale Constraint-Lösung ohne Ausnahmepfad; Felder der Ausnahme: Genehmiger, Grund, Zeitpunkt. Eine Schicht blockiert eine andere nicht, wenn sie selbst nicht mehr gilt (z. B. storniert); die Zustandswerte von `staff_shift.state` sind noch nicht festgelegt (WP-M3-01). Dauer aus absoluten Zeitpunkten, `timezone` nur zur Anzeige (Sommerzeitende 25.10.2026). RLS wie V1; Schichten und Fahrer fremder Mandanten per zusammengesetzte Fremdschlüssel nicht referenzierbar.
- **API:** Schichtzuweisung für die Leitstelle (Pfad im OpenAPI-Entwurf festzulegen; `/v1/rosters` gehört zu M4). Anlegen und Ändern. Überlappung ohne dokumentierte Ausnahme: 409 mit Konfliktursache (Fahrer oder Fahrzeug, kollidierende Schicht); Änderung einer Schicht prüft gegen alle anderen Schichten, nicht gegen sich selbst; Schicht oder Fahrer anderer Mandanten: 404 `NOT_FOUND`; Pflichtfelder `starts_at`, `ends_at` (fehlend oder `ends_at <= starts_at`: 422).
- **Zustandsautomat:** `staff_shift.state`: Zustandswerte `PLANNED`, `STARTED`, `ENDED`, `CANCELLED` (Annahme des Plans), siehe [zustaende/staff_shift.md](zustaende/staff_shift.md); nur `CANCELLED` blockiert nichts.
- **JSON-Schema / Ereignisse:** Audit Anlegen/Ändern im `audit_event`; die Ausnahme speichert Genehmiger, Grund, Zeitpunkt. Ereignisschema nicht festgelegt.
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** Zuweisungsdialog mit Konfliktanzeige im Web-Anwendungsrahmen (WP-M1-43), erreichbar aus UI-11; Navigationseinbindung UI-07 mit WP-M3-36; Konfliktanzeige nennt Ursache (Fahrer oder Fahrzeug) und die kollidierende Schicht.
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-07 (Umgang mit `roster_revision NOT NULL` vor M4; Definition und Verfahren der „Ausnahme": wer genehmigt eine Doppelschicht, wie dokumentiert). Blockiert den Bau der Ausnahme-Funktion und die Festlegung des Wertes von `roster_revision`; der Konfliktkern (Kriterien ohne Ausnahme) ist baubar. Offen außerdem: Zustandswerte `staff_shift.state` und welche Zustände das Überlappungsverbot ausnehmen (WP-M3-01). Aus diesen Gründen bleibt der Vertrag in Arbeit. Vollabnahme für Schichten aus veröffentlichtem Dienstplan erst mit WP-M4-04, WP-M4-13, WP-M4-14, WP-M4-65; AT-39 gehört zu M4 und ist nicht Teil dieses Vertrags.

- **Planungsannahme:** PA-03 in [planungsannahmen.md](../planungsannahmen.md) gilt als Arbeitsstand; offene Punkte oben bleiben bis zur Entscheidung des Auftraggebers bestehen.

## Tests

- **Positiv:** zwei aufeinanderfolgende Schichten (Ende = Beginn) desselben Fahrers und desselben Fahrzeugs werden gespeichert; Pflicht von Beginn/Ende, `ends_at` nach `starts_at`; Anlegen und Ändern stehen im `audit_event`; Ausnahme mit Genehmiger, Grund und Zeitpunkt wird gespeichert (abhängig von OP-07).
- **Negativ:** überlappende Schicht desselben Fahrers: 409 mit Ursache (Originalabnahme); überlappende Belegung desselben Fahrzeugs: gleicher Konflikt; Ausnahme ohne Genehmiger, Grund oder Zeitpunkt bleibt Konflikt; Änderung einer Schicht in die Überlappung hinein: 409; Zuweisung über das Sommerzeitende 25.10.2026 rechnet mit absoluten Zeitpunkten (Dauer, Überlappung); Schicht oder Fahrer anderer Mandanten: 404; `ends_at <= starts_at` und fehlende Zeiten abgewiesen (auch direkt per SQL, CHECK).
- **Konkurrierend:** zwei parallele Transaktionen legen überlappende Schichten desselben Fahrers an: höchstens eine wird gespeichert; ebenso für dasselbe Fahrzeug mit verschiedenen Fahrern.
- **Referenztests:** keine Katalog-ID im WP für M3 genannt (AT-39 ausdrücklich M4).
- Pfadkorrektur WP-M3-07: das Modul `staff-time` (nicht `fleet-safety`) besitzt `staff_shift` (`table_owners.d/00_kern.csv`); die Modultests liegen deshalb unter `backend/modules/staff-time/`. Weitere Tests: `backend/app/src/test/kotlin/de/taktovia/app/driver/ShiftApiTest.kt` (HTTP), `web/operator/src/flotte/Schichtzuweisung.test.tsx` (Oberfläche).
- Testfälle: geplant:db/tests/test_schichtueberlappung.py::test_ueberlappung_fahrer_und_fahrzeug_verboten; geplant:db/tests/test_schichtueberlappung.py::test_aneinandergrenzende_schichten_erlaubt; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftAssignmentTest.kt::doppelschicht_ohne_ausnahme_ergibt_409; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftAssignmentTest.kt::dauer_ueber_sommerzeitende_aus_absoluten_zeitpunkten; geplant:backend/modules/staff-time/src/test/kotlin/de/taktovia/stafftime/ShiftAssignmentTest.kt::parallele_ueberlappende_schichten_hoechstens_eine

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
