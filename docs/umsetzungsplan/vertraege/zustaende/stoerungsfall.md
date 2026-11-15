# Zustandsautomat Störungsfall: betroffene Buchung eines Fahrzeugausfalls (`outage_booking.state`)

- **Arbeitspaket:** WP-M3-54 (`LH-6.22-DMG-010`); ersetzt die Platzhalterdatei [stoerung.md](stoerung.md) (OP-21) für den Ablauf bei Fahrzeugausfall. Der Incident selbst folgt [incident.md](incident.md).
- **Status:** Vorschlag dieses Plans (das Pflichtenheft nennt keine Zustände, OP-21; die Zustände leiten sich aus Akzeptanzkriterium 3 ab: jede betroffene Buchung endet in „umgebucht“ oder „storniert“ oder bleibt als offener Punkt im Incident; keine bleibt still auf dem gesperrten Fahrzeug).
- **Ablage:** Tabelle `outage_booking` (Modul `booking-dispatch`, eine Zeile je Incident und Buchung, Wächter `outage_booking_guard`), Ersatzvorschläge in `outage_proposal` (append-only, je Prüfung eine Generation). Die Entscheidung (Akteur, Code, Zeit) steht an der Zeile und im Ablaufprotokoll des Incidents (`workflow_run_log`, Aktion `DISPATCH_DECISION`).
- **Rollen:** Suche = System (Betriebsauftrag `REPLACEMENT_SEARCH`); Entscheidung = Disposition (`dispatcher`, Scope `operator:write`, Dienstscope); Lesen = Rollen der Leitstelle mit `operator:read`.
- **Regeln:**
  - Nichts geschieht ohne Entscheidung der Leitstelle (keine automatische Umbuchung oder Stornierung, M7/M8).
  - Vorschläge enthalten nur Umläufe anderer Fahrzeuge, für die die Probe der Übernahme besteht (dieselben Prüfungen wie der Commit der manuellen Umplanung: Sperre, Pool, Schicht, gültige Einsatzfreigabe, Dienststatus, Ressourcenvektor inklusive Rollstuhlplatz, Routeninvarianten, Kapazität je Dimension, geschützter Stopphorizont, `max_reassignments`). Die **Wirkung auf das Zusagefenster** steht am Vorschlag (`IN_WINDOW`, `OUTSIDE_WINDOW`); die Übernahme eines Vorschlags außerhalb des Fensters verlangt die ausdrückliche Annahme (`accept_window_effect`, Ausnahmeweg der Umplanung).
  - Eine Ersatzfahrt gilt erst nach bestätigter Umbuchung als zugesagt: Umbuchung = Plan-Revision des Ersatzumlaufs (Plan-CAS, Sicherheitsprüfung im Commit), Hold-Wechsel und `booking_change` in derselben Transaktion wie der Zustandswechsel.
  - Storno = Betreiberstorno der Buchung mit Ursache `VEHICLE_OUTAGE` (Verursacherklasse `FAHRZEUGAUSFALL`, WP-M2-27); Erstattung und Benachrichtigung über die M2-Wege (`booking.cancelled`); die Wiederholung erzeugt keine zweite Erstattung (die Buchung kann nur einmal storniert werden, der Fall ist danach entschieden).
  - Regel für Buchungen ohne Ersatz: kein Vorschlag -> `OPEN_ISSUE` (`NO_REPLACEMENT`), die Leitstelle klärt oder storniert (Storno-/Klärpfad); die Buchung bleibt sichtbar im Incident.
  - Bereits eingestiegene Fahrgäste (`journey_leg.execution_state = BOARDED`) werden gesondert ausgewiesen (`BOARDED`) und nie automatisch umgebucht oder storniert (Umbuchung und Storno: 409 `CONFLICT`, Code `PASSENGER_ONBOARD`); das Verfahren (Rückfahrt zum Ziel mit dem Fahrer, Klärung durch die Leitstelle) ist im Pflichtenheft nicht festgelegt. Nach dem Ausstieg wird der Fall `ONBOARD_DONE`.
  - Wird eine Buchung anderweitig storniert (Fahrgast, Betrieb), gleicht der Ablauf den Fall ab (`CANCELLED`, Akteur `system:booking`, Code `CANCELLED_OTHERWISE`); die Entscheidung der Leitstelle dazu wird mit 409 abgewiesen.
  - Fahrgastmitteilung: nach einer Umbuchung genau einmal je Änderung (`outage.passenger_notice_requested`, `notice_revision` = Revision der Zeile); der Storno löst seine Mitteilung über `booking.cancelled` aus. Vorlage und Kanal der Umbuchungsmitteilung sind offen (OP-27).
  - Der Incident schließt, sobald keine Buchung mehr offen ist: mindestens eine Umbuchung -> `RESOLVED`, sonst `CLOSED_NO_REPLACEMENT`; ohne betroffene Buchung sofort `RESOLVED`.
  - Zwei Disponenten: `If-Match` = Revision der betroffenen Buchung unter Zeilensperre (412 `REVISION_MISMATCH` beim überholten Stand, 409 `INVALID_STATE_TRANSITION` bei bereits entschiedenem Fall).

Zustände: `OPEN`, `PROPOSED`, `OPEN_ISSUE`, `BOARDED`, `REBOOKED`, `CANCELLED`, `ONBOARD_DONE`
Anfangszustand: `OPEN` (eingestiegen: `BOARDED`; Umlauf nicht änderbar: `OPEN_ISSUE` mit `RUN_NOT_CHANGEABLE`)
Endzustände: `REBOOKED`, `CANCELLED`, `ONBOARD_DONE`

| von | nach | Auslöser | Akteur | Fehler bei unzulässigem Übergang |
|---|---|---|---|---|
| `OPEN` | `PROPOSED` | Suche/Prüfung findet Ersatz | System, Disposition | 409 `INVALID_STATE_TRANSITION` |
| `OPEN`, `PROPOSED` | `OPEN_ISSUE` | kein Ersatz oder Umlauf nicht änderbar | System, Disposition | 409 `INVALID_STATE_TRANSITION` |
| `OPEN_ISSUE` | `PROPOSED`, `OPEN` | neue Prüfung findet Ersatz | System, Disposition | 409 `INVALID_STATE_TRANSITION` |
| `OPEN`, `PROPOSED`, `OPEN_ISSUE` | `BOARDED` | Fahrgast ist eingestiegen | System (Abgleich) | 409 `INVALID_STATE_TRANSITION` |
| `OPEN`, `PROPOSED`, `OPEN_ISSUE` | `REBOOKED` | Umbuchung auf Vorschlag | Disposition | 409 `INVALID_STATE_TRANSITION` |
| `OPEN`, `PROPOSED`, `OPEN_ISSUE` | `CANCELLED` | Betreiberstorno Fahrzeugausfall oder anderweitiger Storno | Disposition, System | 409 `INVALID_STATE_TRANSITION` |
| `BOARDED` | `ONBOARD_DONE` | Fahrt des eingestiegenen Fahrgasts beendet | System (Abgleich) | 409 `INVALID_STATE_TRANSITION` |

## Negativbeispiele

- `REBOOKED` -> `CANCELLED` oder `OPEN`: abgewiesen (Endzustand); eine Korrektur ist eine neue Entscheidung über die Buchungsänderung der Leitstelle.
- `BOARDED` -> `REBOOKED`/`CANCELLED`: 409 `CONFLICT` (`PASSENGER_ONBOARD`), Datenbank: Trigger.
- Zweiter Disponent mit veralteter Revision: 412 `REVISION_MISMATCH` mit aktuellem `ETag`; mit aktueller Revision auf entschiedenem Fall: 409.
- Incident eines fremden Mandanten oder Dienstes: 404; Rolle ohne `operator:write`: 403.
