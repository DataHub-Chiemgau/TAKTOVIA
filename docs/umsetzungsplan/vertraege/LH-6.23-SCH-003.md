# LH-6.23-SCH-003 – UI-12: Schichten per Klick, Kopie und Drag-and-drop

- **Verantwortlicher:** Rolle Personalplanung (Oberfläche `web/operator`)
- **Arbeitspaket:** WP-M4-19
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Neue Schichten lassen sich per Klick, Kopie oder Drag-and-drop anlegen und geeigneten Personen zuordnen.

**Originalabnahme (unverändert):** Schicht wird nach Drag-and-drop ohne vollständigen Seitenwechsel gespeichert.

## Vertrag

- **Tabellen / DDL:** keine neuen; Schreibzugriffe laufen über `staff-time` auf `staff_shift` im Zustand Entwurf (`roster_revision`, WP-M4-04).
- **API:** `/v1/rosters` (Schicht anlegen, kopieren, zuweisen mit `If-Match`); `POST /v1/rosters/{roster_id}/publish` (in `api/rosters.yaml` ausformuliert, Header `Idempotency-Key`, `If-Match`; 412/428/409/422). Speicherung asynchron ohne vollständigen Seitenwechsel, 412-Konflikt ohne stilles Überschreiben.
- **Zustandsautomat:** `vertraege/zustaende/dienstplan.md`. **JSON-Schema / Ereignisse:** keine neuen. **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-12: Anlegen per Klick in Rasterzelle, Kopieren einer Schicht, Verschieben/Zuweisen per Drag-and-drop mit gleichwertiger Tastaturalternative (Gate UX, Kap. 08 §8.4); Statusmeldung über Live-Region; Konfliktmeldung direkt an der Schicht; Angebot „geeigneter Personen“ (Qualifikation, keine Abwesenheit); Aktion „Entwurf veröffentlichen“ mit blockierenden Konflikten; Fehlertexte je Konfliktcode. Zustände Laden, Fehler, 412-Konfliktdialog, keine Rechte.
- **Entscheidungen:** keine.
- **Offene Punkte:** Interaktionsvertrag Drag-and-drop/Tastaturpfad, Filterregel „geeignete Personen“ und Fehlertexte je Konfliktcode entstehen in WP-M4-01 (kein fachlicher OP-Eintrag; Konfliktcodes hängen an WP-M4-07).

## Tests

- **Positiv:** UI-E2E: Anlegen per Klick, per Kopie, Verschieben/Zuweisen per Drag-and-drop; Schicht ist ohne vollständigen Seitenwechsel gespeichert (Statusmeldung über Live-Region) und nach Neuladen vorhanden; neue Schichten entstehen im Entwurf; bei Zuordnung werden geeignete Personen angeboten; jede Drag-and-drop-Aktion ist per Tastatur ausführbar; Veröffentlichen aus der Oberfläche.
- **Negativ:** Zuordnung unqualifizierter Person zeigt die Konfliktursache; veralteter Stand (412) zeigt Konfliktdialog und überschreibt nicht; fehlendes Recht verhindert die Aktion.
- **Konkurrierend:** zwei Planer verschieben dieselbe Schicht parallel, der zweite erhält 412 mit Konfliktdialog.
- **Referenztests:** AT-37, AT-39 (über die Oberfläche; vom WP genannt).
- Testfälle: AT-37; AT-39; geplant:web/operator/src/dienstplan/SchichtDragDrop.test.tsx::drag_and_drop_speichert_ohne_seitenwechsel; geplant:web/operator/src/dienstplan/SchichtDragDrop.test.tsx::anlegen_per_klick_und_kopie_erzeugt_entwurf; geplant:web/operator/src/dienstplan/SchichtDragDrop.test.tsx::geeignete_personen_werden_angeboten; geplant:web/operator/src/dienstplan/SchichtDragDrop.test.tsx::unqualifizierte_zuordnung_zeigt_konfliktursache; geplant:web/operator/src/dienstplan/SchichtDragDrop.test.tsx::tastaturpfad_entspricht_drag_and_drop; geplant:web/operator/src/dienstplan/SchichtDragDrop.test.tsx::veralteter_stand_zeigt_konfliktdialog; geplant:web/operator/src/dienstplan/SchichtDragDrop.test.tsx::fehlendes_recht_verhindert_aktion

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview).
