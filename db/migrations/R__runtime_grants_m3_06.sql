-- WP-M3-06: Rechte für vehicle_pool_assignment (löst die Nur-Lesen-Fixture-Rechte aus R__runtime_grants_m2_02.sql ab). Der Dateiname sortiert hinter R__runtime_grants.sql.
-- Anhängen und lesen; ändern darf die Runtime-Rolle nur das Ende eines laufenden Intervalls (Spaltenrecht, zusätzlich Trigger). Kein DELETE.
GRANT SELECT, INSERT ON taktovia.vehicle_pool_assignment TO taktovia_runtime;
GRANT UPDATE (valid_to) ON taktovia.vehicle_pool_assignment TO taktovia_runtime;
