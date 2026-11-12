-- WP-M2-21: ride_offer. Der Inhalt ist unveränderlich (Trigger); nur Zustand, Hold-Bezug, Revision und Änderungszeit dürfen fortgeschrieben werden, kein DELETE.
-- Der Dateiname sortiert hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
GRANT SELECT, INSERT ON taktovia.ride_offer TO taktovia_runtime;
GRANT UPDATE (state, hold_id, hold_scope, revision, updated_at) ON taktovia.ride_offer TO taktovia_runtime;
