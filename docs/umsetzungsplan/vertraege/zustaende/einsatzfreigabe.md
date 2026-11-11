# Zustandsautomat Einsatzfreigabe (`resource_release`)

- **Arbeitspaket:** WP-M3-01 (Vertrag), Umsetzung WP-M3-08 (Kern), WP-M3-09 (Gate), WP-M3-14 (Prüfabschluss). Datenbank: `resource_release` (V1) mit `revoked_at`, `valid_until`; zusätzlich `state` als abgeleiteter Wert (Annahme: nicht gespeichert, sondern aus `revoked_at` und `valid_until` berechnet).
- **Status:** Vorschlag dieses Plans (LH-6.21-CHK-001, OP-09, OP-33). `valid_until`: Annahme Schichtende, höchstens 16 h nach Erteilung (Planungsdefault); nach einer Pause innerhalb derselben Schicht ist keine neue Prüfung nötig, solange `safety_revision` unverändert ist. `vehicle_readiness_require_checks=false` schaltet nur die nicht sicherheitskritischen Prüfschritte ab, nie das Gate selbst (Validierung bei Publikation).
- **Rollen:** Erteilen: Server nach `COMPLETED` und Ergebnis `PASSED`/`PASSED_RESTRICTED` (Akteur im Audit: der abschließende Fahrer oder Prüfer). Widerruf: Server (Sperre, Fahrzeugwechsel, Entzug der Qualifikation), Disposition (`dispatcher`).
- **Regel:** Die Freigabe gilt für genau (Fahrer, Fahrzeug, Schicht). Der Client liefert weder Freigabe noch Prüfergebnis.

Zustände: `ISSUED`, `REVOKED`, `EXPIRED`
Anfangszustand: `ISSUED`
Endzustände: `REVOKED`, `EXPIRED`

| von | nach | Auslöser | Akteur | Fehler bei unzulässigem Übergang |
|---|---|---|---|---|
| `ISSUED` | `REVOKED` | Sperre gesetzt, Fahrzeugwechsel, Qualifikationsentzug, Disposition widerruft | System, Disposition | 409 `INVALID_STATE_TRANSITION` |
| `ISSUED` | `EXPIRED` | `valid_until` erreicht | System | 409 `INVALID_STATE_TRANSITION` |

## Negativbeispiele

- Bereitmeldung ohne `ISSUED`-Freigabe: abgewiesen, 409 `INVALID_STATE_TRANSITION` mit Liste `missing_requirements`; Eintrag im `audit_event` (AT-26).
- `REVOKED` -> `ISSUED` (Wiederaufnahme der alten Freigabe): abgewiesen, 409 `IMMUTABLE_RECORD`; eine neue Prüfung erzeugt eine neue Freigabe.
- Freigabe für eine andere Kombination (anderes Fahrzeug, Schicht oder Fahrer): nie `ISSUED` für die angefragte Kombination; 404 `NOT_FOUND` bei fremden Objekten.
