-- WP-M4-52: Zeitfakten nur lesen und anhängen (append-only), Verspätungsfunktion ausführen. Dateiname sortiert hinter R__runtime_grants.sql.
GRANT SELECT, INSERT ON taktovia.time_fact TO taktovia_runtime;
GRANT EXECUTE ON FUNCTION taktovia.time_fact_delay(uuid) TO taktovia_runtime;
