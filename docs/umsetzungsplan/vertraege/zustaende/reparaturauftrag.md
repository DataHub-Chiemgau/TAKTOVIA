# Zustandsautomat Reparaturauftrag (`repair_order.status`)

- **Arbeitspaket:** WP-M3-01 (Vertrag), Umsetzung WP-M3-51.
- **Status:** Vorschlag dieses Plans (Kap. 05 §5.7 „Sperr-/Freigabeberechtigung, Evidenz, Vieraugenpflicht, qualifizierte Folgeprüfung, Kostenbezug“, OP-11).
- **Rollen:** Werkstatt = `workshop`; Disposition = `dispatcher` (nur lesen); Betriebsleitung = `tenant_admin` (Zweitfreigabe bei Wiederfreigabe).
- **Regeln:** Anlegen nur zu einer Schadenakte im Status `IN_PROGRESS` (`defect_case` wechselt auf `REPAIR`). `PROOF_RECORDED` verlangt Nachweis (Evidenz-Referenz) und Kostenbezug (Verweis auf Kostenstelle/Kostenträger, kein Betrag aus dem Client). Die Wiederfreigabe des Fahrzeugs ist ein getrennter Schritt (`createVehicleRelease`, Zustandsautomat fahrzeug_sperre).

Zustände: `OPEN`, `IN_PROGRESS`, `PROOF_RECORDED`, `CANCELED`
Anfangszustand: `OPEN`
Endzustände: `PROOF_RECORDED`, `CANCELED`

| von | nach | Auslöser | Akteur | Fehler bei unzulässigem Übergang |
|---|---|---|---|---|
| `OPEN` | `IN_PROGRESS` | Werkstatt übernimmt den Auftrag | Werkstatt | 409 `INVALID_STATE_TRANSITION` |
| `OPEN` | `CANCELED` | Auftrag zurückgezogen | Werkstatt | 409 `INVALID_STATE_TRANSITION` |
| `IN_PROGRESS` | `PROOF_RECORDED` | Reparaturnachweis erfasst | Werkstatt | 409 `INVALID_STATE_TRANSITION` |
| `IN_PROGRESS` | `CANCELED` | Auftrag abgebrochen (Akte fällt auf `IN_PROGRESS`) | Werkstatt | 409 `INVALID_STATE_TRANSITION` |

## Negativbeispiele

- `OPEN` -> `PROOF_RECORDED` (Nachweis ohne Bearbeitung): abgewiesen, 409 `INVALID_STATE_TRANSITION`.
- `PROOF_RECORDED` -> `IN_PROGRESS` (Nachweis zurücknehmen): abgewiesen, 409 `IMMUTABLE_RECORD`.
- Anlegen zu einer Akte im Status `NEW`: 409 `INVALID_STATE_TRANSITION`; Fahrer oder Disposition als Akteur: 403 `FORBIDDEN`.
