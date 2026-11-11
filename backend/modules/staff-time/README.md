# staff-time – Personal und Zeit

Paket `de.taktovia.stafftime` mit `api` (öffentliche Ports) und `internal` (privat).

**Führende Aggregate und Tabellen (Kap. 01 §1.3, Kap. 04 §4.3):** RosterRevision, Shift, Qualification, TimeEvent; Tabelle staff_shift.

Regeln: schreibt nur eigene Tabellen (`db/table_owners.d/`); greift auf andere Module nur über deren `api`-Paket zu;
hängt im Gradle-Build nur von `platform` ab.

**Fahrer-App-Basis (WP-M3-24):** `StafftimeApi.driverDevices` (Geräte-/Sitzungsbindung, `driver_device`) und `driverEvents` (Ereigniseingang mit dauerhafter Deduplikation, `driver_event`); Routen `POST /v1/driver/devices`, `DELETE /v1/driver/devices/{device_id}`, `POST /v1/driver/events` (Controller in `app`). Annahme bis WP-M3-05: `staff_shift.driver_ref` ist die IdP-Subject-UUID. Die fachliche Wirkung auf Stopps und Buchungen (WP-M3-29) hängt später am Port `DriverEvents`.
