-- WP-M4-16: Rechte für roster_depot_grant. Der Dateiname sortiert hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
-- Vergeben, lesen, entziehen (UPDATE, vom Trigger auf den Entzug begrenzt); kein DELETE.
GRANT SELECT, INSERT, UPDATE ON taktovia.roster_depot_grant TO taktovia_runtime;
