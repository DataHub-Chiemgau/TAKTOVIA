# fleet-safety – Flotte und Sicherheitsfreigabe

Paket `de.taktovia.fleetsafety` mit `api` (öffentliche Ports) und `internal` (privat).

**Führende Aggregate und Tabellen (Kap. 01 §1.3, Kap. 04 §4.3):** Vehicle, Inspection, Defect, Release; Tabellen vehicle, resource_release.

Regeln: schreibt nur eigene Tabellen (`db/table_owners.d/`); greift auf andere Module nur über deren `api`-Paket zu;
hängt im Gradle-Build nur von `platform` ab.

**Fahrzeugpool (WP-M3-06):** Port `FleetsafetyApi.vehiclePool` (`VehiclePool`): Dienstzuordnung mit Intervall und Gebietsbezug auf `vehicle_pool_assignment`; liest zum Hinweis beim Beenden die Tabellen `booking`, `capacity_hold` und `vehicle_run` des Moduls `booking-dispatch` nur lesend (wie der Wirkungsanbieter in `service-geo`).
