-- WP-M1-35: Rechte für Fahrerstamm, Freigabe und Projektion. Der Dateiname sortiert hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
-- Kein DELETE. Qualifikationsrevisionen nur anhängen; Freigabe und Projektion nur anhängen, ändern nur den einmaligen Widerruf (Spaltenrecht, zusätzlich Trigger).
GRANT SELECT, INSERT, UPDATE ON taktovia.driver TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.qualification_revision TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.driver_release TO taktovia_runtime;
GRANT UPDATE (revoked_at, revoked_by) ON taktovia.driver_release TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.driver_release_projection TO taktovia_runtime;
GRANT UPDATE (revoked_at) ON taktovia.driver_release_projection TO taktovia_runtime;
