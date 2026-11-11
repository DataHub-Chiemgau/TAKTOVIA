# journey-federation – Reiseketten und Föderation

Paket `de.taktovia.journeyfederation` mit `api` (öffentliche Ports) und `internal` (privat).

**Führende Aggregate und Tabellen (Kap. 01 §1.3, Kap. 04 §4.3):** Journey, JourneyLeg, SagaStep, CooperationGrant; Tabellen journey, journey_leg, operation, saga_step, partner_contract_version, cooperation_grant.

Regeln: schreibt nur eigene Tabellen (`db/table_owners.csv`); greift auf andere Module nur über deren `api`-Paket zu;
hängt im Gradle-Build nur von `platform` ab.
