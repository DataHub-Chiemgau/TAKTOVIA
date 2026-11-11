-- WP-M1-16: Rechte der Webhook-Tabellen (Dateiname sortiert hinter R__runtime_grants.sql, das mit REVOKE ALL beginnt).
GRANT SELECT, INSERT, UPDATE ON taktovia.webhook_subscription TO taktovia_runtime;
GRANT SELECT, INSERT, UPDATE ON taktovia.webhook_delivery TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.webhook_quarantine TO taktovia_runtime;
