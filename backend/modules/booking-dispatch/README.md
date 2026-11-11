# booking-dispatch – Buchung, Kapazität, Disposition

Paket `de.taktovia.bookingdispatch` mit `api` (öffentliche Ports) und `internal` (privat).

**Führende Aggregate und Tabellen (Kap. 01 §1.3, Kap. 04 §4.3):** Booking, CapacityHold, RunPlanRevision; Tabellen travel_attempt, vehicle_run, capacity_hold, booking.

Regeln: schreibt nur eigene Tabellen (`db/table_owners.d/`); greift auf andere Module nur über deren `api`-Paket zu;
hängt im Gradle-Build nur von `platform` ab.

## Ressourcen-Leseport (WP-M2-02)

`BookingdispatchApi.resources` (`ResourceAvailability`) liest Fahrzeug, `capacity_config`, `active_block`, `safety_revision`, Schicht, Einsatzfreigabe und Fahrzeugpool je Dienst in **einem** SQL-Statement (konsistenter Datenstand je Abfrage, nur lesend, nur der Mandant des Kontexts). Ein Fahrzeug ist nicht einsetzbar bei: Sperre (`VEHICLE_BLOCKED`), fehlender deckender Schicht (`NO_SHIFT`), fehlender, widerrufener, abgelaufener oder gegen eine veraltete `safety_revision` ausgestellter Freigabe (`NO_VALID_RELEASE`) oder Pool-Fremdheit (`NOT_IN_POOL`). Bis M3/M4 stammen die Zeilen aus dem Seed [`tests/acceptance/fixtures/m2_referenzregion/`](../../../tests/acceptance/fixtures/m2_referenzregion/README.md); die Schnittstelle bleibt bei der Ablösung unverändert (Vollabnahme mit WP-M3-03/-06/-08, WP-M4-03/-65). Die Tabelle `vehicle_pool_assignment` (Besitzer `fleet-safety`) ist Fixture-Stand bis WP-M3-06.
