-- WP-M2-14: Rechte für passenger_trip. Der Dateiname sortiert hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
-- Anhängen und lesen; Zeilen sind unveränderlich (Trigger), kein UPDATE/DELETE-Recht.
GRANT SELECT, INSERT ON taktovia.passenger_trip TO taktovia_runtime;
