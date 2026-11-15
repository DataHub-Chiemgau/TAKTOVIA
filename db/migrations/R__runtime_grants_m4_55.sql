-- WP-M4-55: Ursachenkatalog (je Mandant, nur anlegen und lesen), Fakt "Fahrt nicht durchgeführt" (abgeleitet, wiederaufbaubar: SELECT, INSERT, UPDATE, DELETE) und Korrekturvermerke (nur anhängen und lesen).
-- Dateiname sortiert hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
GRANT SELECT, INSERT ON taktovia.not_performed_cause TO taktovia_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON taktovia.not_performed_trip TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.not_performed_correction TO taktovia_runtime;
GRANT EXECUTE ON FUNCTION taktovia.not_performed_refresh(uuid) TO taktovia_runtime;
GRANT EXECUTE ON FUNCTION taktovia.seed_not_performed_causes() TO taktovia_runtime;
GRANT EXECUTE ON FUNCTION taktovia.seed_not_performed_definitions() TO taktovia_runtime;
