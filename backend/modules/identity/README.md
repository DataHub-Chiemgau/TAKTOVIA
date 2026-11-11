# identity – Identität und Berechtigung

Paket `de.taktovia.identity` mit `api` (öffentliche Ports) und `internal` (privat).

**Führende Aggregate und Tabellen (Kap. 01 §1.3, Kap. 04 §4.3):** Membership, Delegation, PolicyRevision; Tabellen tenant, membership, legal_entity, organization, passenger_private.

Regeln: schreibt nur eigene Tabellen (`db/table_owners.d/`); greift auf andere Module nur über deren `api`-Paket zu;
hängt im Gradle-Build nur von `platform` ab.

## Stand WP-M1-06

Öffentliche Ports (`identity.api.IdentityApi`): `MembershipLookup` (wählbare Mandanten über den subjektgebundenen Verzeichnisindex `membership_subject_index`, aktive Mitgliedschaft je Mandant), `TenantSwitch` (Mitgliedschafts- und Dienstprüfung, Audit), `MembershipAdmin` (erteilen/entziehen/auflisten mit Audit und Outbox in einer Transaktion), `ContextRevalidator` (Wiederprüfung in der Fachtransaktion), `ScopePolicy` (Rolle -> Funktionsscopes). Entscheidungen zu OP-04 (Gültigkeit, `service_scope NULL` = keine Freigabe) und OP-06 (Lookup ohne Allzweck-Definer): [identitaet-kontext.md](../../../docs/umsetzungsplan/vertraege/identitaet-kontext.md).
