# service-geo – Dienste, Bediengebiete, Haltepunkte

Paket `de.taktovia.servicegeo` mit `api` (öffentliche Ports) und `internal` (privat).

**Führende Aggregate und Tabellen (Kap. 01 §1.3, Kap. 04 §4.3):** ServiceRevision, ServiceArea, StopRevision; Tabellen service, service_area, stop_revision.

Regeln: schreibt nur eigene Tabellen (`db/table_owners.d/`); greift auf andere Module nur über deren `api`-Paket zu;
hängt im Gradle-Build nur von `platform` ab.
