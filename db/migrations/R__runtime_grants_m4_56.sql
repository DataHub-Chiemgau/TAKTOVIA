-- WP-M4-56: Besetzung je Klassifikationslauf, nur anhängen (Trigger sperrt UPDATE/DELETE zusätzlich); Seed der ausführbaren Kilometerkennzahlen. Dateiname hinter R__runtime_grants.sql.
GRANT SELECT, INSERT ON taktovia.vehicle_km_occupancy TO taktovia_runtime;
GRANT EXECUTE ON FUNCTION taktovia.seed_km_metric_definitions() TO taktovia_runtime;
