-- WP-M4-08: Rechte für time_event. Der Dateiname sortiert hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
-- Nur anhängen und lesen; UPDATE und DELETE sperrt zusätzlich der Trigger time_event_immutable.
GRANT SELECT, INSERT ON taktovia.time_event TO taktovia_runtime;
