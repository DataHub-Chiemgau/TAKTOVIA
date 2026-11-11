# Zustandsautomat Incident (`incident.status`)

- **Arbeitspaket:** WP-M3-01 (Vertrag, Entscheidung OP-02), Umsetzung WP-M3-53 (Workflow), WP-M3-54 (Störungsablauf), WP-M3-55 (Ablaufprotokoll).
- **Status:** Vorschlag dieses Plans (LH-I.8-AUT-001/-002, OP-02, OP-24). Ablage: Tabelle `incident` (Dedupe-Schlüssel, partieller Unique-Index auf offene Incidents je Fahrzeug und Ursache), `incident_link` (betroffener Umlauf/Buchung), `workflow_rule_revision` (unveränderlich), `workflow_run_log` (append-only Ablaufprotokoll). Modul `fleet-safety` führt Incident und Workflow; die Ersatzfahrzeugsuche ruft einen Port des Moduls `booking-dispatch` und läuft als `operation` (OP-02: `journey_id` bleibt NULL-fähig per expand, Annahme).
- **Rollen:** Workflow = System; Betriebsleitung/Disposition = `dispatcher`.
- **Regeln:** Ein Fahrzeugausfall (Ereignis `vehicle.blocked`, Quelle SAFETY/OPERATIONS oder `DISRUPTION`) erzeugt genau einen Incident je Dedupe-Schlüssel, auch bei wiederzugestellten Ereignissen (Inbox) und parallelen Auslösern; die Ersatzsuche startet genau einmal. Bleibt die Suche ohne Ergebnis, bleibt der Incident `OPEN`/`REPLACEMENT_SEARCH`, er verschwindet nie still. Unbekannter Ereignistyp: protokollieren und abweisen (Annahme); Ablehnung und kritische Verspätung ohne hinterlegte Regel: Ablaufprotokoll „keine Aktion“ (OP-24).

Zustände: `OPEN`, `REPLACEMENT_SEARCH`, `RESOLVED`, `CLOSED_NO_REPLACEMENT`
Anfangszustand: `OPEN`
Endzustände: `RESOLVED`, `CLOSED_NO_REPLACEMENT`

| von | nach | Auslöser | Akteur | Fehler bei unzulässigem Übergang |
|---|---|---|---|---|
| `OPEN` | `REPLACEMENT_SEARCH` | Workflowregel startet die Ersatzsuche | System, Disposition | 409 `INVALID_STATE_TRANSITION` |
| `REPLACEMENT_SEARCH` | `RESOLVED` | Ersatz zugewiesen oder Fahrten umgeplant | Disposition, System | 409 `INVALID_STATE_TRANSITION` |
| `REPLACEMENT_SEARCH` | `OPEN` | Suche ohne Ergebnis, bleibt offen und sichtbar | System | 409 `INVALID_STATE_TRANSITION` |
| `OPEN` | `CLOSED_NO_REPLACEMENT` | bewusster Abschluss ohne Ersatz (Begründung) | Disposition | 409 `INVALID_STATE_TRANSITION` |
| `REPLACEMENT_SEARCH` | `CLOSED_NO_REPLACEMENT` | bewusster Abschluss ohne Ersatz (Begründung) | Disposition | 409 `INVALID_STATE_TRANSITION` |

## Negativbeispiele

- `RESOLVED` -> `OPEN`: abgewiesen (Endzustand), 409 `INVALID_STATE_TRANSITION`; ein neuer Ausfall ist ein neuer Incident.
- Zweites Ereignis „Fahrzeugausfall“ desselben Fahrzeugs und derselben Ursache, solange ein Incident offen ist: kein zweiter Incident (Unique-Index), das Ereignis wird dem bestehenden zugeordnet.
- `transitionOperatorIncident` durch eine Rolle ohne `operator:write`: 403 `FORBIDDEN`; fremder Incident: 404 `NOT_FOUND`.
