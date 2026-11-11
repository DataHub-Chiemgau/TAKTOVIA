# Zustandsautomat Schadenakte (`defect_case.status`)

- **Arbeitspaket:** WP-M3-01 (Vertrag), Umsetzung WP-M3-47 (Automat), WP-M3-43 (Anlage), WP-M3-45 (Triage), WP-M3-51 (Reparatur und Wiederfreigabe).
- **Status:** Vorschlag dieses Plans (LH-6.22-DMG-007, OP-10). Die Enum-Werte folgen den sechs Statusnamen des Pflichtenhefts (`neu`, `geprüft`, `in Bearbeitung`, `Reparatur`, `abgeschlossen`, `verworfen`).
- **Rollen:** Meldender (Fahrer, Disposition) = `driver`, `dispatcher`; Prüfer/Werkstatt = `workshop`, `dispatcher`; Betriebsleitung = `tenant_admin`.
- **Regeln:** `DISMISSED` nur mit Begründung; `CLOSED` bei Klasse `SAFETY_CRITICAL` nur mit Freigabeentscheidung (`vehicle_release_decision`); `CLOSED` und `DISMISSED` heben eine bestehende Sperre nie auf (nur `createVehicleRelease`, WP-M3-51). Jeder Übergang schreibt Statushistorie (append-only) und `audit_event`; Änderung nur mit `If-Match` (412/428).
- **Klassen** (OP-10): `SAFETY_CRITICAL` (entspricht „S0“ in PH-AT-020), `OPERATIONALLY_RELEVANT`, `COSMETIC`, `OTHER`. Parameter `workshop_auto_block_severity` (Katalog) benennt die Klasse, ab der die Triage sperrt: Annahme `SAFETY_CRITICAL`.

Zustände: `NEW`, `REVIEWED`, `IN_PROGRESS`, `REPAIR`, `CLOSED`, `DISMISSED`
Anfangszustand: `NEW`
Endzustände: `CLOSED`, `DISMISSED`

| von | nach | Auslöser | Akteur | Fehler bei unzulässigem Übergang |
|---|---|---|---|---|
| `NEW` | `REVIEWED` | `triageDefect` mit Klasse und Zuständigem | Disposition, Werkstatt | 409 `INVALID_STATE_TRANSITION` |
| `NEW` | `DISMISSED` | `triageDefect` mit Entscheidung `DISMISSED` und Begründung (Fehlmeldung) | Werkstatt | 409 `INVALID_STATE_TRANSITION` |
| `REVIEWED` | `IN_PROGRESS` | Bearbeitung übernommen | Werkstatt, Disposition | 409 `INVALID_STATE_TRANSITION` |
| `REVIEWED` | `DISMISSED` | Verwerfen mit Begründung | Werkstatt | 409 `INVALID_STATE_TRANSITION` |
| `REVIEWED` | `CLOSED` | Klasse `COSMETIC` oder `OTHER`, keine Reparatur nötig | Werkstatt | 409 `INVALID_STATE_TRANSITION` |
| `IN_PROGRESS` | `REPAIR` | `createRepairOrder` | Werkstatt | 409 `INVALID_STATE_TRANSITION` |
| `IN_PROGRESS` | `CLOSED` | Abschluss ohne Reparatur (nicht sicherheitskritisch) | Werkstatt | 409 `INVALID_STATE_TRANSITION` |
| `IN_PROGRESS` | `DISMISSED` | Verwerfen mit Begründung | Werkstatt | 409 `INVALID_STATE_TRANSITION` |
| `REPAIR` | `CLOSED` | Reparaturnachweis erfasst und, bei `SAFETY_CRITICAL`, Freigabeentscheidung vorhanden | Werkstatt, Betriebsleitung | 409 `INVALID_STATE_TRANSITION` |
| `REPAIR` | `IN_PROGRESS` | Reparaturauftrag storniert | Werkstatt | 409 `INVALID_STATE_TRANSITION` |

## Negativbeispiele

- `NEW` -> `CLOSED` (Sprung über Prüfung und Bearbeitung): abgewiesen, 409 `INVALID_STATE_TRANSITION` (DMG-007, Originalabnahme).
- `CLOSED` -> `REVIEWED` (Wiedereröffnung ohne dokumentierte Wiedereröffnung): abgewiesen, 409 `INVALID_STATE_TRANSITION`; `DISMISSED` -> `REVIEWED` ebenso.
- Abschluss aus `REPAIR` bei Klasse `SAFETY_CRITICAL` ohne Freigabeentscheidung: 409 `INVALID_STATE_TRANSITION` (Vorbedingung nicht erfüllt).
- Übergang durch die Rolle `passenger`/`driver` (nur Meldung, keine Triage): 403 `FORBIDDEN`.
