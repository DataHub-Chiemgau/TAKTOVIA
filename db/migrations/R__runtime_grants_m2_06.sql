-- WP-M2-06: Rechte für relation_rule. Der Dateiname sortiert hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
-- Anhängen und lesen; ändern darf die Runtime-Rolle nur das Ende einer offenen Version (Spaltenrecht, zusätzlich Trigger). Kein DELETE.
GRANT SELECT, INSERT ON taktovia.relation_rule TO taktovia_runtime;
GRANT UPDATE (valid_to) ON taktovia.relation_rule TO taktovia_runtime;
