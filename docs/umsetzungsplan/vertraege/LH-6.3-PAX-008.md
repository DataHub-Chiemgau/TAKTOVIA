# LH-6.3-PAX-008 – UI-04 Buchungshistorie in vier Bereichen

- **Verantwortlicher:** Rolle Passenger-PWA (Web `web/passenger`) mit Rolle Buchung/Disposition (Modul `booking-dispatch`)
- **Arbeitspaket:** WP-M2-45
- **Status:** Bereit
- **Phase / Priorität (unverändert aus Lastenheft):** P1 / MUSS

## Originaltext (unverändert)

> Buchungshistorie trennt bevorstehende, laufende, vergangene und stornierte Fahrten.

**Originalabnahme (unverändert):** Statuswechsel verschiebt Buchung in den korrekten Bereich.

## Vertrag

- **Tabellen / DDL:** keine neue Tabelle. Lesesicht auf `booking` (`state`, `revision`, `promise_snapshot`) und die Statushistorie aus WP-M2-25; Sortierschlüssel `(created_at,id)` setzt die Migration für `booking.created_at` aus WP-M2-23 voraus. Mandantenisolation per RLS (V1, `tenant_isolation`); zusätzlich Objektfilter „eigene Buchung“ (Fahrgastbezug `passenger_private`, WP-M1-06/WP-M1-08).
- **API:** `GET /v1/bookings?bereich=bevorstehend|laufend|vergangen|storniert&cursor=…&limit=…`; Cursor-Liste nach Kap. 05 §5.1, stabiler Sortierschlüssel `(created_at,id)`; `limit` 1–100 (Default 50), Antwort `{items,next_cursor,as_of}` (Kap. 05 §5.1); ungültiger Bereichswert → 422 `application/problem+json`; fremde Buchungen werden nie geliefert, Detail fremder ID → 404. Jede Buchung trägt serverseitig berechnet das Feld `bereich`. Änderungsstrom für Aktualisierung ohne Neuladen aus WP-M2-28/WP-M2-43 (Protokoll offen, OP-25 des WP-Dokuments).
- **Zustandsautomat:** `booking.state` aus WP-M2-01 (lokale Buchung, OP-4) und Ausführungszustände aus M3. Serverseitige Zuordnungstabelle (Arbeitsstand, im Detailvertrag der Zustandsautomaten zu bestätigen): BESTAETIGT (vor Abholfenster) → bevorstehend; Ausführung begonnen (Check-in bis Fahrtende) → laufend; Fahrt abgeschlossen sowie No-Show/abgelaufen → vergangen; Zustand storniert (jeder Stornogrund, auch Fahrzeugausfall) → storniert. Jede Buchung liegt in genau einem Bereich; die Zuordnung ist eine reine Funktion `(booking.state, Ausführungszustand) → Bereich` an genau einer Stelle im Backend.
- **JSON-Schema / Ereignisse:** Verbrauch der Ereignisse „Buchung bestätigt“, „Buchung storniert“ und Planrevision (Ereigniskatalog WP-M2-01); in M2 für „laufend/abgeschlossen“ Testereignisse (OP-37).
- **Parameter und Kennzahlen:** keine.
- **Oberfläche:** UI-04 Übersicht mit vier Bereichen (Reiter mit Anzahl), Sortierung je Bereich, Leerzustand je Bereich, Fehlerzustand bei Ladefehler; Aktualisierung bei Statuswechsel über den Änderungsstrom ohne manuelles Neuladen; Bedienbarkeit per Tastatur und Textalternative (WP-M2-47).
- **Entscheidungen:** keine.
- **Offene Punkte:** OP-37 (Bereich „laufend“ braucht Ausführungszustände aus M3; Nachweis in M2 mit Testereignissen, Vollabnahme erst mit WP-M3-29; blockiert den Bau nicht); OP-4 (Zustandsautomat `booking.state`, WP-M2-01); OP-21 (Pfade/Filterparameter), OP-25 (Technik des Änderungsstroms). Einordnung von „No-Show“ und „abgelaufen“ ist im Pflichtenheft nicht festgelegt: Arbeitsannahme vergangen.

## Tests

- **Positiv:** je Bereich mindestens eine Buchung erscheint im richtigen Bereich; Statuswechsel bestätigt → storniert verschiebt die Buchung in „storniert“ in der geöffneten Ansicht ohne Neuladen; bestätigt → laufend → abgeschlossen per Testereignis verschiebt nach „laufend“ und „vergangen“; API und UI liefern dieselbe Einteilung.
- **Negativ:** fremde Buchungen (anderer Fahrgast, anderer Mandant) nicht in der Liste, Detail fremder ID → 404; ungültiger Bereichsfilter → 422; Buchung liegt nie in zwei oder in keinem Bereich (Eigenschaftstest über alle zulässigen Zustandskombinationen); Cursor einer anderen Sicht wird abgewiesen.
- **Konkurrierend:** Statuswechsel (Storno) parallel zum Laden der Liste: die Buchung erscheint nach dem Commit in genau einem Bereich, nie doppelt oder gar nicht; zwei Mandanten über denselben Connection-Pool sehen nie fremde Zeilen.
- **Referenztests:** Kein Katalogtest direkt zugeordnet; Statuswechsel storniert wirkt im Nachweis zu AT-07 (WP-M2-46) mit. Vollabnahme der Ausführungszustände mit WP-M3-29.
- Testfälle: geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/BookingHistoryAreaTest.kt::jede_buchung_genau_ein_bereich; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/BookingHistoryAreaTest.kt::fremde_buchungen_nicht_in_liste; geplant:web/passenger/src/booking/BookingHistory.test.tsx::statuswechsel_verschiebt_ohne_neuladen; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/BookingHistoryAreaTest.kt::ungueltiger_bereich_422_und_fremder_cursor_abgewiesen; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/BookingHistoryAreaTest.kt::storno_parallel_zum_laden_genau_ein_bereich; geplant:backend/booking-dispatch/src/test/kotlin/de/taktovia/booking/BookingHistoryAreaTest.kt::mandanten_ueber_gleichen_pool_getrennt; geplant:web/passenger/src/booking/BookingHistory.test.tsx::api_und_ui_gleiche_einteilung

## Reviews

Noch keine menschlichen Reviewer benannt (D-12). Vertrag aus WP-Dokument abgeleitet und gegen Originaltext/-abnahme geprüft (Agentenprüfung, ersetzt kein Fachreview). Zweitprüfung erforderlich.
