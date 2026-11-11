-- WP-M2-03: Rechte für service_revision. Der Dateiname sortiert hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
-- Anhängen und lesen; ändern darf die Runtime-Rolle nur das Ende einer offenen Version (Spaltenrecht, zusätzlich Trigger). Kein DELETE.
GRANT SELECT, INSERT ON taktovia.service_revision TO taktovia_runtime;
GRANT UPDATE (valid_to) ON taktovia.service_revision TO taktovia_runtime;
