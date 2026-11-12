-- WP-M3-31: aktueller Dienststatus je Schicht (kein DELETE; der Zustandswächter lässt nur Kanten des Automaten zu). Der Dateiname sortiert hinter R__runtime_grants.sql.
GRANT SELECT, INSERT, UPDATE ON taktovia.vehicle_service_status TO taktovia_runtime;
