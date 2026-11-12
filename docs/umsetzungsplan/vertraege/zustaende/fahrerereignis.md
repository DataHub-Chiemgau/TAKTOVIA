# Zustandsübergänge der Fahrerereignisse (WP-M3-29, `LH-6.6-DRV-005`)

- **Arbeitspaket:** WP-M3-29; Code: `booking-dispatch` `BookingExecution` (Wirkung), `staff-time` `JdbcDriverEvents` (Prüfkette); Datenbank: `V30290` (`journey_leg_execution_guard`, `driver_event`-Spalten).
- **Status:** Planungsannahme. Das Pflichtenheft spezifiziert den Fahrtautomaten nicht (M3 OP-21, `fahrt.md`); die Ausführungszustände unten sind der kleinste Satz, den die P1-Abnahme braucht, und **vor Abnahme fachlich zu bestätigen**.
- **Zustandsspalte der Fahrt:** `journey_leg.execution_state` (Besitzer `journey-federation`, Schreibweg ausschließlich über `JourneyShells.setExecutionState` aus der Wirkung); die Buchung ändert sich nur über den Automaten `buchung.md` mit Statushistorie.

| Ereignis (`event_type`) | Voraussetzung | Fahrtsegment | Buchung | Ergebnis |
|---|---|---|---|---|
| `ARRIVAL` (Stopp) | Stopp der aktuellen Planrevision der eigenen Schicht | – | – | startet die Wartefrist (plausibilisierte Zeit `effective_at`) |
| `CHECK_IN` | Buchung steigt am Stopp ein, `PLANNED`, Buchung `CONFIRMED` | `PLANNED -> BOARDED` | bleibt `CONFIRMED` | `driver.passenger_boarded` |
| `CHECK_OUT` | Buchung steigt am Stopp aus, `BOARDED` | `BOARDED -> ALIGHTED` | `CONFIRMED -> COMPLETED` (`TRIP_COMPLETED`) | `driver.passenger_alighted` |
| `NO_SHOW` | Ankunft gemeldet, Wartefrist `no_show_grace_min` abgelaufen, Grund, `PLANNED` | `PLANNED -> NO_SHOW` | `CONFIRMED -> CANCEL_PENDING -> CANCELED` (`NO_SHOW`, `CANCEL_COMPLETED`, Verursacher Fahrgast) | `driver.no_show_recorded` mit Grund, Wartezeit, Regelstand; keine Gebühr (BKG-015 ist P2, OP-21); Hold bleibt unverändert |

`ALIGHTED` und `NO_SHOW` sind endgültig; kein Rücksprung, auch nicht per SQL (Trigger `journey_leg_execution_guard`, SQLSTATE 23514). Ein Storno einer Buchung, deren Fahrt begonnen hat, ist 409 (`INVALID_STATE_TRANSITION`).

## Regel für `REVIEW_REQUIRED` (nie eine Statuswirkung, Ereignis bleibt als historische Beobachtung)

Reihenfolge der Prüfung; die erste zutreffende gilt. 404 vor allem: Schicht, Fahrzeug, Stopp oder Buchung außerhalb der eigenen Schicht. 409: Schicht nicht angetreten.

| Grund | Bedingung |
|---|---|
| `SHIFT_ENDED` | Ereigniszeit nach dem Schichtende oder Schicht storniert |
| `CLOCK_SKEW` | Ereigniszeit in der Zukunft (über der Toleranz), oder online die Gerätezeit weicht um mehr als die Toleranz von der Serveruhr ab, oder Ereigniszeit weit nach der Gerätezeit |
| `STALE_PLAN` | Planrevision des Geräts älter als die des Umlaufs, oder Stopp einer ersetzten Revision |
| `RELEASE_REQUIRED` | keine gültige Einsatzfreigabe oder Fahrzeug gesperrt (nicht für `CHECK_OUT`: ein Fahrgast im Fahrzeug muss aussteigen können). Das Ereignis hebt nie eine Sperre auf, dieses Modul schreibt keine Sperren |
| `INVALID_ORDER` | Ausstieg ohne Einstieg, Einstieg bei nicht `PLANNED`er Fahrt, No-Show ohne gemeldete Ankunft, Buchung noch nicht bestätigt |
| `OUTSIDE_STOP` | die Buchung steigt an diesem Stopp nicht ein bzw. aus |
| `BOOKING_CANCELED` | Widerspruch zu einem serverseitigen Storno (Klärfall, kein Zustandsrücksprung) |

Plausibilisierte Zeit: online die Serverempfangszeit, offline die Ereigniszeit (höchstens die Empfangszeit). Die Wartezeit des No-Shows ist `effective_at(NO_SHOW) - effective_at(ARRIVAL)`; zu früh ist 422 `NO_SHOW_GRACE_NOT_ELAPSED` ohne Speicherung.
