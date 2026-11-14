-- WP-M4-43: Seed der Energiekosten-Kennzahlen (Funktion der Migration V40461). Dateiname hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
GRANT EXECUTE ON FUNCTION taktovia.seed_energy_metric_definitions() TO taktovia_runtime;
