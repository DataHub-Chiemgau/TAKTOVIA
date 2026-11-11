-- WP-M1-09: Rechte für Region und Betriebshof (Dateiname sortiert hinter R__runtime_grants.sql, die mit REVOKE ALL beginnt).
-- Stammdaten werden angelegt und geändert, nie gelöscht (Nachvollziehbarkeit über Audit und Revision).
GRANT SELECT, INSERT, UPDATE ON taktovia.region, taktovia.depot TO taktovia_runtime;
