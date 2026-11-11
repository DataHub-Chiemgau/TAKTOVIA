# Zustandsautomat Fahrtenplan (`vehicle_run.state`)

- **Arbeitspaket:** WP-M2-01 (Vorschlag), Umsetzung WP-M2-16; Datenbank: `vehicle_run.state` (freier Text in V1, Härtung per Migration in WP-M2-16).
- **Status:** **Vorschlag dieses Plans**. Das Pflichtenheft definiert keine Werte (M1 OP-21, M2 OP-4, OP-48); die Liste ist vor Abnahme von Fachanalyse und M3 zu bestätigen. `ACTIVE` ist der Wert, mit dem Spike (a) (WP-M0-26) gemessen hat.
- **Regeln:** Planänderung nur per Revision (`vehicle_run.revision`, CAS); neue Holds nur in `ACTIVE`; `LOCKED` (z. B. Sicherheitssperre, Schichtende nahe) verhindert neue Holds und Planänderungen ohne Bestandsverschlechterung. Bestehende Zusagen verschlechtern sich nie durch einen Zustandswechsel.

Zustände: `PLANNED`, `ACTIVE`, `LOCKED`, `IN_PROGRESS`, `COMPLETED`, `CANCELED`
Anfangszustand: `PLANNED`
Endzustände: `COMPLETED`, `CANCELED`

| von | nach | Auslöser | Akteur | Fehler bei unzulässigem Übergang |
|---|---|---|---|---|
| `PLANNED` | `ACTIVE` | gültige Einsatzfreigabe und Schicht vorhanden | System | 409 `INVALID_STATE_TRANSITION` |
| `PLANNED` | `CANCELED` | Schicht gestrichen | Disponent, System | 409 `INVALID_STATE_TRANSITION` |
| `ACTIVE` | `LOCKED` | Sperre (Safety), Schichtende, manuelle Sperre | System, Disponent | 409 `INVALID_STATE_TRANSITION` |
| `LOCKED` | `ACTIVE` | Sperre aufgehoben | System, Disponent | 409 `INVALID_STATE_TRANSITION` |
| `ACTIVE` | `IN_PROGRESS` | Fahrt beginnt (Fahrer-Check-in, M3) | Fahrer | 409 `INVALID_STATE_TRANSITION` |
| `LOCKED` | `IN_PROGRESS` | Fahrt beginnt trotz Planänderungssperre | Fahrer | 409 `INVALID_STATE_TRANSITION` |
| `IN_PROGRESS` | `COMPLETED` | alle Stopps abgearbeitet | Fahrer, System | 409 `INVALID_STATE_TRANSITION` |
| `ACTIVE` | `CANCELED` | Umlauf entfällt, Buchungen werden umgeplant oder storniert | Disponent | 409 `INVALID_STATE_TRANSITION` |
| `LOCKED` | `CANCELED` | Umlauf entfällt | Disponent | 409 `INVALID_STATE_TRANSITION` |
