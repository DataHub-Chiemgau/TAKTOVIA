-- WP-M4-46: Kennzahlregister. Definitionen anlegen, lesen und freigeben (UPDATE nur im Entwurf, Trigger metric_definition_revision_guard; kein DELETE); Snapshots nur anhängen und lesen.
-- Dateiname sortiert hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
GRANT SELECT, INSERT, UPDATE ON taktovia.metric_definition_revision TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.aggregate_snapshot TO taktovia_runtime;
GRANT EXECUTE ON FUNCTION taktovia.seed_metric_definitions() TO taktovia_runtime;
