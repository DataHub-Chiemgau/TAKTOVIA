-- WP-M2-10: Rechte für route_matrix_cache. Der Dateiname sortiert hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
-- Anhängen, lesen, bei neuem Kartenstand löschen; kein UPDATE (Einträge sind unveränderlich, ein neuer Stand ist ein neuer Eintrag).
GRANT SELECT, INSERT, DELETE ON taktovia.route_matrix_cache TO taktovia_runtime;
