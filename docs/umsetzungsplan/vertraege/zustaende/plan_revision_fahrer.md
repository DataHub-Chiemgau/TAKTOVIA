# Zustandsautomat Planrevision aus Fahrersicht (abgeleitet)

- **Arbeitspaket:** WP-M3-27 (`LH-6.6-DRV-004`); Umsetzung `staff-time` (`DriverShifts.run`, `DriverPlanAcks`).
- **Status:** **Vorschlag dieses Plans.** Das Pflichtenheft legt weder die Regel „bestätigungspflichtig oder automatisch übernommen“ noch die Erkennung der Fahrbewegung fest (OP-29 und Vertrag DRV-004); beide sind hier als Planungsannahme bestimmt und vor Produktivschaltung fachlich zu bestätigen.
- **Ableitung:** Der Zustand wird bei jedem Abruf aus der aktuellen Planrevision (`vehicle_run.revision`), der zuletzt bestätigten Revision (`driver_plan_ack`, höchste Revision der Schicht) und der Vergleichsbasis berechnet; er wird nie gespeichert. Der Client kennt zusätzlich „vergangen“.

Zustände: `CURRENT`, `NEW_UNCONFIRMED`, `AUTO_ADOPTED`, `CONFIRMED`
Anfangszustand: `CURRENT`
Endzustände: –

| von | nach | Auslöser | Akteur | Fehler bei unzulässigem Übergang |
|---|---|---|---|---|
| `CURRENT` | `NEW_UNCONFIRMED` | neue Planrevision mit neuem oder entfallenem Stopp, geänderter Reihenfolge oder Zeitverschiebung ab `plan_ack_time_shift_min` (Standard 5 min) gegenüber der Vergleichsbasis | System (Plan-Commit) | – |
| `CURRENT` | `AUTO_ADOPTED` | neue Planrevision nur mit Zeitverschiebungen unter der Schwelle | System | – |
| `AUTO_ADOPTED` | `NEW_UNCONFIRMED` | weitere Änderung; die Verschiebungen summieren sich gegen dieselbe Basis | System | – |
| `NEW_UNCONFIRMED` | `CONFIRMED` | Bestätigung der **aktuellen** Revision (`POST …/run/acknowledgements`, `If-Match` = Planrevision) | Fahrer | 412 `REVISION_MISMATCH` bei veralteter Revision, 404 fremde Schicht, 409 `INVALID_STATE_TRANSITION` bei beendeter Schicht |
| `AUTO_ADOPTED` | `CONFIRMED` | freiwillige Bestätigung | Fahrer | wie oben |
| `CONFIRMED` | `NEW_UNCONFIRMED` | neue bestätigungspflichtige Revision | System | – |
| `CONFIRMED` | `AUTO_ADOPTED` | neue Revision mit kleinen Verschiebungen | System | – |
| `CONFIRMED` | `CURRENT` | neue Revision ohne Inhaltsänderung gegenüber der bestätigten Basis | System | – |

## Regeln

- **Vergleichsbasis:** zuletzt bestätigte Revision; ohne Bestätigung der Plan beim Dienstantritt (letzte Revision mit `plan_change.recorded_at` ≤ `started_at`, sonst die erste). Ist die Basis nicht lesbar, gilt die Revision als bestätigungspflichtig ohne Einzelkennzeichnung (nie still übernommen).
- **Stoppidentität:** Haltepunktrevision, Art und Buchungsmenge (die Zeilenkennung von `route_stop` wechselt je Revision). Kennzeichnung je Stopp: `NEW`, `MOVED` (mit früherer Planzeit); entfallene Stopps stehen in `removed_stops` (nur Haltepunktname, Art, frühere Planzeit, Buchungsreferenzen).
- **Bestätigung:** nur die aktuelle Revision; eine übersprungene Revision lässt sich nie bestätigen. Je Schicht und Revision genau ein Eintrag (zwei Geräte, parallele Wiederholung). Der Umlauf wird dabei `FOR SHARE` gesperrt: ein gleichzeitiger Plan-Commit wartet, es entsteht nie die Bestätigung einer überholten Revision.
- **Bedingter Abruf:** `ETag` = Planrevision; solange `acknowledgement_required` gilt, antwortet der Abruf nie 304.
- **Hinweis an die Leitstelle:** bestätigungspflichtige Revision seit `plan_ack_overdue_min` (Standard 5 min) unbestätigt, Schicht `STARTED`: `PlanAckOverdue` an die Alarmgrundlage (`PLAN_ACK_OVERDUE`, WP-M3-52; Dedupe je Schicht und Revision).
- **Fahrerereignis auf veralteter Revision:** `REVIEW_REQUIRED` mit `STALE_PLAN` (WP-M3-24), nie ein stiller Zustandswechsel.
- **Client (UI-10):** „vergangen“ = zwischengespeicherte Revision < gemeldete Serverrevision (Live-Kanal, Weckruf, Abruf). Offline: letzter Plan mit Stand und „nicht aktuell“. **Kein Bestätigungsdialog während der Fahrbewegung:** Bewegung = Geschwindigkeit ≥ 1,5 m/s in einer höchstens 30 s alten Messung; ohne frische Messung gilt „unbekannt“ wie Bewegung (nie ein Dialog im Zweifel); die Änderung wird angezeigt bzw. angesagt, die Bestätigung beim nächsten Halt verlangt.

## Negativbeispiele

- Bestätigung einer veralteten Revision: 412, keine Zeile.
- Bestätigung fremder Schicht oder fremden Mandanten: 404.
- Bestätigung durch die Leitstelle für den Fahrer: 403.
