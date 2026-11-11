# Audit-Port: Formate und Startkatalog (WP-M1-46, Enabler)

- **`actor_ref`:** `<art>:<kennung>`, Art `user` (Subject-UUID), `service`, `system`, `partner`; kein Name, keine Mailadresse, kein Token. In der Datenbank per CHECK (`V1460__audit_event_formate.sql`) erzwungen.
- **`object_ref`:** `<typ>:<kennung>` mit Typ in Kleinbuchstaben (z. B. `membership:<uuid>`).
- **`action`:** `bereich.objekt.verb`, 2 bis 4 Teile, Kleinbuchstaben. Sicherheitsereignisse beginnen mit `security.`.
- **`redacted_changes`:** [schemas/audit-redacted-changes.v1.schema.json](schemas/audit-redacted-changes.v1.schema.json).
- **Startkatalog (Code: `platform.audit.AuditActions`, wird in WP-M1-12 fortgeschrieben):** `identity.membership.granted|revoked`, `identity.organization.changed`, `config.revision.published|reverted`, `security.tenant.mismatch`, `security.event.payload_hash_mismatch`, `security.event.recipient_mismatch`, `security.access.denied`, `security.auth.mfa_missing` (WP-M1-04: privilegiertes Konto ohne zweiten Faktor abgewiesen; `details`: `required_acr`, `presented_acr`, `client_id`, `privileged_via`).
- **Ereignisse ohne Mandantenkontext (OP-14, offen):** `audit_event.tenant_id` ist Pflicht. `SecurityEventPort.reportWithoutTenant` schreibt bis zur Klärung (Plattformmandant, OP-08) nur eine strukturierte Logzeile.
- **Gegenereignis statt Änderung (§4.5):** UPDATE, DELETE und TRUNCATE auf `audit_event` scheitern für die Runtime-Rolle (nur SELECT/INSERT vergeben); UPDATE/DELETE zusätzlich für jede andere Rolle (Trigger `audit_immutable`). TRUNCATE bleibt der Administration vorbehalten (kein Trigger, um die Triggerliste des Kernschematests nicht zu berühren).
- **Architekturregel:** Methoden mit `@AuditRequired` müssen `AuditPort.record` aufrufen (`ArchitectureRules.auditRequiredCallsAuditPort`, roter Selbsttest gegen Fixture).
