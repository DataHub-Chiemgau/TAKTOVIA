# booking-dispatch – Buchung, Kapazität, Disposition

Paket `de.taktovia.bookingdispatch` mit `api` (öffentliche Ports) und `internal` (privat).

**Führende Aggregate und Tabellen (Kap. 01 §1.3, Kap. 04 §4.3):** Booking, CapacityHold, RunPlanRevision; Tabellen travel_attempt, vehicle_run, capacity_hold, booking.

Regeln: schreibt nur eigene Tabellen (`db/table_owners.d/`); greift auf andere Module nur über deren `api`-Paket zu;
hängt im Gradle-Build nur von `platform` ab.
