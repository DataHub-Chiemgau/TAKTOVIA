# Zustandsautomat Schichtzuweisung (`staff_shift.state`)

- **Arbeitspakete:** WP-M3-07 (Leitstelle legt an, ändert, entzieht), WP-M3-25 (Fahrer tritt an, beendet); Verträge `LH-6.8-FLT-003`, `LH-6.6-DRV-001`.
- **Modul / Tabelle:** `staff-time`, `staff_shift`. Der Dienstplan (M4, WP-M4-04/-13/-14/-65) übernimmt später die Schichterzeugung und führt zusätzlich den Zustand `PUBLISHED`; er ist hier nicht definiert, wird von M3 weder gesetzt noch gewertet und lässt sich nur durch den Dienstplan ändern (409 `INVALID_STATE_TRANSITION`). Der Fahrer kann eine veröffentlichte Schicht wie eine geplante antreten.
- **Planungsannahme:** Zustandswerte sind Annahmen dieses Plans (WP-M3-01 „Zustandswerte `staff_shift.state`“, OP-07); sie sind nicht vom Auftraggeber bestätigt.

| Zustand | Bedeutung | Blockiert Fahrer und Fahrzeug im Überlappungsverbot |
|---|---|---|
| `PLANNED` | geplant: Fahrer und Fahrzeug sind für das Intervall `[starts_at, ends_at)` zugewiesen | ja |
| `STARTED` | angetreten: der Fahrer hat die Zuordnung Fahrer, Schicht, Fahrzeug an einem Gerät bestätigt (`started_at`, `started_device_id`) | ja |
| `ENDED` | beendet (`ended_at`); endgültig | ja (die Zeit war belegt) |
| `CANCELLED` | Zuweisung entzogen (Entzug, Stornierung) | **nein** |

| Von | Nach | Auslöser | Wer | Prüfungen und Fehlerfälle |
|---|---|---|---|---|
| (neu) | `PLANNED` | `POST /v1/shifts` | Leitstelle (`shifts:write`) | `ends_at > starts_at`; Fahrer und Fahrzeug im Mandanten und aktiv; keine Überlappung je Fahrer und je Fahrzeug, sonst 409 `CONFLICT` (Ursache `/driver_id` oder `/vehicle_id`), außer dokumentierte Ausnahme |
| `PLANNED` | `STARTED` | Dienstantritt `POST /v1/driver/shift-starts` | Fahrer der Schicht | eigene Schicht (sonst 404); Fahrzeug der Schicht (sonst 422); Gerät des Kontos, an die aktuelle Sitzung gebunden (sonst 403); Zeit ab `starts_at` minus Antrittstoleranz bis vor `ends_at` (sonst 409 `INVALID_STATE_TRANSITION`) |
| `STARTED` | `STARTED` | Wiederholung oder zweites Gerät | Fahrer | kein zweiter Antritt: die Antwort nennt den bestehenden Zustand (`already_started`); anderes Fahrzeug: 409 |
| `STARTED` | `ENDED` | Dienstende `POST /v1/driver/shift-ends` | Fahrer der Schicht | Gerät wie beim Antritt; Wiederholung liefert den Stand |
| `PLANNED`, `STARTED` | `CANCELLED` | Entzug `PUT /v1/shifts/{id}` mit `withdrawn=true` | Leitstelle | nach dem Entzug keine Aufträge mehr (Run leer), kein Antritt (409) |
| `CANCELLED` | `PLANNED` | Rücknahme des Entzugs (`withdrawn=false`) | Leitstelle | wie eine Neuanlage auf Überlappung geprüft |
| `ENDED` | – | – | – | unveränderlich; jede Änderung 409 `INVALID_STATE_TRANSITION` |

**Durchsetzung:** Anwendung (Konfliktprüfung mit Sperre) und Datenbank (Trigger `staff_shift_overlap_guard` aus V3070, `staff_shift_state_guard` aus V3250) gelten für jeden Schreibpfad. Die Überlappung wird über das halboffene Intervall `tstzrange(starts_at, ends_at, '[)')` aus absoluten Zeitpunkten geprüft: Ende gleich Beginn überlappt nicht; die Zeitzone dient nur der Anzeige (Sommerzeitende am 25.10.2026: die Wandzeit 02:30 kommt zweimal vor, die Zeitpunkte bleiben unterscheidbar).

**Dokumentierte Ausnahme (Doppelschicht, PA-03):** `overlap_exception_by`, `overlap_exception_reason`, `overlap_exception_at` (alle drei oder keines). Sie hebt das Verbot nur für diese Schicht auf und wird nur gespeichert, wenn tatsächlich eine Überlappung besteht; spätere reguläre Schichten werden dennoch gegen die Ausnahmeschicht geprüft. Der Server setzt den Zeitpunkt. Genehmiger und Grund fehlen oder sind leer: es bleibt beim Konflikt. Das Genehmigungsverfahren (wer genehmigt, wie dokumentiert) ist OP-07 und nicht vom Auftraggeber entschieden.

**Antrittstoleranz:** Planungsdefault 30 Minuten vor `starts_at` (`StafftimeSettings.shiftStartTolerance`, `taktovia.stafftime.shift-start-tolerance-minutes`); kein Katalogparameter in `kataloge.csv`.

**Audit:** `staff.shift.created|changed` (Leitstelle), `staff.shift.started|ended` (Fahrer: Gerät und Zeit in den Details). Der Dienstantritt ist weder Arbeitszeiterfassung (M4, `time_event`) noch Einsatzfreigabe (`resource_release`, WP-M3-08/-09).
