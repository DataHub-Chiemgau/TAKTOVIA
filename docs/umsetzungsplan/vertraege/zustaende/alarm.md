# Zustandsautomat Alarm (`business_alert.state`)

- **Arbeitspaket:** WP-M3-52 (Alarm-Grundlage), WP-M5-04 (technische Alarme, Schließen nach Behebung).
- **Status:** **Vorschlag dieses Plans** (`LH-I.8-AUT-*` ist P2, M8; die Alarm-Grundlage ist Enabler). Die Datenbank erzwingt die Kanten zusätzlich (Trigger `business_alert_guard`, `V3520`).
- **Regeln:** Dedupe ereignisbasiert über `(alert_kind, source_event_id, recipient_role)` in jedem Zustand, bedingungsbasiert (technische Alarme) höchstens ein offener Alarm je `dedupe_key`; Inhalt unveränderlich, Eskalationsstufe steigt nur; kritische Alarme entstehen bereits eskaliert (umgehen `alert_escalation_delay_min`); nicht quittierte eskalieren nach der Verzögerung genau einmal je Stufe (P1: eine Stufe).

Zustände: `OPEN`, `ACKNOWLEDGED`, `RESOLVED`
Anfangszustand: `OPEN`
Endzustände: `RESOLVED`

| von | nach | Auslöser | Akteur | Fehler bei unzulässigem Übergang |
|---|---|---|---|---|
| `OPEN` | `ACKNOWLEDGED` | Quittierung mit Ursache (`POST /v1/operator/alerts/{id}/acknowledgements`, `If-Match`) | Disponent | 409 `INVALID_STATE_TRANSITION`, 412 `REVISION_MISMATCH` bei gleichzeitiger zweiter Quittierung |
| `OPEN` | `RESOLVED` | Behebung der Bedingung (technischer Alarm) | System | 409 `INVALID_STATE_TRANSITION` |
| `ACKNOWLEDGED` | `RESOLVED` | Behebung der Bedingung | System | 409 `INVALID_STATE_TRANSITION` |

## Negativbeispiele

- Quittierung eines quittierten oder gelösten Alarms: 409.
- Quittierung ohne Rolle `dispatcher` (nur lesende Rollen): 403; fremder Dienst oder Mandant: 404.
- Rücksprung `ACKNOWLEDGED -> OPEN` oder aus `RESOLVED` heraus: von der Datenbank abgewiesen (SQLSTATE 23514).
