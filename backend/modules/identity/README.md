# identity – Identität und Berechtigung

Paket `de.taktovia.identity` mit `api` (öffentliche Ports) und `internal` (privat).

**Führende Aggregate und Tabellen (Kap. 01 §1.3, Kap. 04 §4.3):** Membership, Delegation, PolicyRevision; Tabellen tenant, membership, legal_entity, organization, passenger_private.

Regeln: schreibt nur eigene Tabellen (`db/table_owners.d/`); greift auf andere Module nur über deren `api`-Paket zu;
hängt im Gradle-Build nur von `platform` ab.

## Stand WP-M1-06

Öffentliche Ports (`identity.api.IdentityApi`): `MembershipLookup` (wählbare Mandanten über den subjektgebundenen Verzeichnisindex `membership_subject_index`, aktive Mitgliedschaft je Mandant), `TenantSwitch` (Mitgliedschafts- und Dienstprüfung, Audit), `MembershipAdmin` (erteilen/entziehen/auflisten mit Audit und Outbox in einer Transaktion), `ContextRevalidator` (Wiederprüfung in der Fachtransaktion), `ScopePolicy` (Rolle -> Funktionsscopes). Entscheidungen zu OP-04 (Gültigkeit, `service_scope NULL` = keine Freigabe) und OP-06 (Lookup ohne Allzweck-Definer): [identitaet-kontext.md](../../../docs/umsetzungsplan/vertraege/identitaet-kontext.md).

## Stand WP-M1-07

`identity.api`: `RoleCatalog` (sieben Pflichtrollen + Platzhalter), `RightsMatrix` (lädt `rechtematrix/rechtematrix.csv`, Strukturprüfung, Vertragsabgleich, `scopesFor`), `Authorizer`/`MatrixAuthorizer` (zentrale Entscheidung, deny-by-default), `RightsMatrixRollout` (Protokoll des Ausrollens). Die Matrix ist die einzige Rechtequelle; `ScopePolicy` leitet daraus die Funktionsscopes ab. Siehe [rechtematrix.md](../../../docs/umsetzungsplan/vertraege/rechtematrix.md).

## Stand WP-M1-28, -29, -30 (Fahrgastkonto)

Eigene Fabrik `identity.api.PassengerApi` (lässt `IdentityApi` unverändert): `accounts` (Registrierung, Kontaktverifikation je Kanal, Wiederherstellungsregel „nur verifizierter Kanal“, minimierte Betreiberliste), `profiles` (eigenes Profil mit Revision/ETag, Präferenzen, eigene Auswahl des Unterstützungsbedarfs), `consents` (Einwilligungsrevisionen, append-only) und `delivery` (`DeliveryPolicy.isDeliveryAllowed` für den Benachrichtigungsdienst, WP-M2-32). Kontaktdaten und Unterstützungsmerkmal laufen ausschließlich über den Feldschutz (`taktovia_fields`); Codes und Kontakte erscheinen nirgends im Klartext (HMAC-Token, Ciphertext). Verträge und Entscheidungen zu OP-23/OP-30/OP-34: [fahrgastkonto.md](../../../docs/umsetzungsplan/vertraege/fahrgastkonto.md). Test-Adapter für den Versand: `InMemoryVerificationSender` (kein produktiver SMS-/E-Mail-Kanal vor M2).
