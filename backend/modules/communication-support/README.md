# communication-support – Kommunikation und Support

Paket `de.taktovia.communicationsupport` mit `api` (öffentliche Ports) und `internal` (privat).

**Führende Aggregate und Tabellen (Kap. 01 §1.3, Kap. 04 §4.3):** Case, NotificationAttempt (Tabellen mit M2/M7).

Regeln: schreibt nur eigene Tabellen (`db/table_owners.csv`); greift auf andere Module nur über deren `api`-Paket zu;
hängt im Gradle-Build nur von `platform` ab.
