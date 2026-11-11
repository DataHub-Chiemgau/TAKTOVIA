-- WP-M2-16: Rechte für route_stop. Der Dateiname sortiert hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
-- Anhängen und lesen; Planrevisionen sind unveränderlich (Trigger), kein UPDATE/DELETE-Recht.
GRANT SELECT, INSERT ON taktovia.route_stop TO taktovia_runtime;
