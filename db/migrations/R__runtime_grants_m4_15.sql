-- WP-M4-15: Rechte für roster_shift_change. Der Dateiname sortiert hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
-- Nur anhängen und lesen; UPDATE und DELETE sperrt zusätzlich der Trigger roster_shift_change_immutable.
GRANT SELECT, INSERT ON taktovia.roster_shift_change TO taktovia_runtime;
