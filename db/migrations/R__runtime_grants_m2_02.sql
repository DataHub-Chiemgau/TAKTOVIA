-- WP-M2-02: Rechte für vehicle_pool_assignment. Der Dateiname sortiert hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
-- Die Runtime-Rolle liest nur; gepflegt wird die Zuordnung bis M3 (WP-M3-06) durch Seed-Skripte unter der Migrator-Rolle.
GRANT SELECT ON taktovia.vehicle_pool_assignment TO taktovia_runtime;
