-- WP-M4-04: Rechte für roster_revision. Der Dateiname sortiert hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
-- Anlegen, lesen, Entwurf fortschreiben und Zustand wechseln (UPDATE, vom Trigger roster_revision_guard begrenzt); kein DELETE.
GRANT SELECT, INSERT, UPDATE ON taktovia.roster_revision TO taktovia_runtime;
