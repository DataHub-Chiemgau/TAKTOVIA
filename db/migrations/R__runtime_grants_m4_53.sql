-- WP-M4-53: Grenzwertbindung nur lesen und anhängen (append-only), Seed der ausführbaren Abholkennzahlen. Dateiname sortiert hinter R__runtime_grants.sql.
GRANT SELECT, INSERT ON taktovia.pickup_tolerance_binding TO taktovia_runtime;
GRANT EXECUTE ON FUNCTION taktovia.seed_pickup_punctuality_definitions() TO taktovia_runtime;
