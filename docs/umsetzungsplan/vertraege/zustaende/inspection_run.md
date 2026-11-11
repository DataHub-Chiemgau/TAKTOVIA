# Zustandsautomat Prüflauf (`inspection_run.status`)

- **Arbeitspaket:** WP-M3-01 (Vertrag), Umsetzung WP-M3-13 (Start), WP-M3-14 (Abschluss), WP-M3-02 (Tabelle, CHECK, Trigger).
- **Status:** Vorschlag dieses Plans (Pflichtenheft ohne Zustandsliste, M3 OP-16, OP-33); Fachanalyse und Auftraggeber bestätigen vor Abnahme.
- **Rollen** (Arbeitsnamen auf die Rollencodes der Rechtematrix, OP-34): Fahrer = `driver`, Werkstatt/Prüfer = `workshop`, Disposition = `dispatcher`, System = Server (Zeitablauf).
- **Regeln:** Ein Prüflauf gilt je Kombination (Fahrer, Fahrzeug, Schicht, Art). Höchstens ein Lauf je Kombination ist `IN_PROGRESS` (partieller Unique-Index). Mit `COMPLETED` werden Antworten unveränderlich (Trigger `reject_mutation()`, SQLSTATE 23514); das Ergebnis (`outcome`: `PASSED`, `PASSED_RESTRICTED`, `BLOCKED`, `CLARIFICATION_REQUIRED`) und die Einsatzfreigabe (`resource_release`) entscheidet allein der Server im selben Commit. Die Gültigkeit der Freigabe (`valid_until`) liegt an der Freigabe, nicht am Lauf (OP-33: Annahme Schichtende, höchstens 16 h).
- Korrekturen nach Abschluss sind Ergänzungseinträge (WP-M3-15), nie Änderungen der Antworten.

Zustände: `IN_PROGRESS`, `COMPLETED`, `EXPIRED`, `CANCELED`
Anfangszustand: `IN_PROGRESS`
Endzustände: `COMPLETED`, `EXPIRED`, `CANCELED`

| von | nach | Auslöser | Akteur | Fehler bei unzulässigem Übergang |
|---|---|---|---|---|
| `IN_PROGRESS` | `COMPLETED` | `submitInspection` mit allen Pflichtschritten | Fahrer, Werkstatt | 409 `INVALID_STATE_TRANSITION` |
| `IN_PROGRESS` | `EXPIRED` | Frist des Laufs überschritten (Schichtbeginn + Karenz) | System | 409 `INVALID_STATE_TRANSITION` |
| `IN_PROGRESS` | `CANCELED` | Abbruch durch Fahrer oder Leitstelle (Fahrzeugwechsel, Dienstende) | Fahrer, Disposition | 409 `INVALID_STATE_TRANSITION` |

## Negativbeispiele

- `COMPLETED` -> `IN_PROGRESS` (Wiedereröffnung eines abgeschlossenen Laufs): abgewiesen, 409 `IMMUTABLE_RECORD`; Datenbank: Trigger 23514.
- `EXPIRED` -> `COMPLETED` (Abschluss nach Fristende): abgewiesen, 409 `INVALID_STATE_TRANSITION`.
- Abschluss durch die Rolle `dispatcher` oder `passenger`: 403 `FORBIDDEN`; Abschluss eines fremden Laufs: 404 `NOT_FOUND`.
