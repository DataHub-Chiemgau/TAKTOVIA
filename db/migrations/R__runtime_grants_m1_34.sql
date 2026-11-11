-- WP-M1-34: Rechte für vehicle_assignment. Der Dateiname sortiert hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
-- Anhängen und lesen; ändern darf die Runtime-Rolle nur das Ende eines laufenden Intervalls (Spaltenrecht, zusätzlich Trigger). Kein DELETE.
GRANT SELECT, INSERT ON taktovia.vehicle_assignment TO taktovia_runtime;
GRANT UPDATE (valid_to) ON taktovia.vehicle_assignment TO taktovia_runtime;
