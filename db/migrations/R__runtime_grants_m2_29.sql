-- WP-M2-29: Rechte für payment_event. Der Dateiname sortiert hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
-- Anhängen und lesen; Ereignisse sind unveränderlich (kein UPDATE, kein DELETE).
GRANT SELECT, INSERT ON taktovia.payment_event TO taktovia_runtime;
