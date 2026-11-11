-- WP-M4-06: Rechte für absence_request. Der Dateiname sortiert hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
-- Anlegen, lesen, zurücknehmen (UPDATE, vom Trigger auf die Rücknahme begrenzt); kein DELETE.
GRANT SELECT, INSERT, UPDATE ON taktovia.absence_request TO taktovia_runtime;
