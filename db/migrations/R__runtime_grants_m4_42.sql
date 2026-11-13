-- WP-M4-42: Kilometerklassifikation, nur anhängen (Trigger sperrt UPDATE/DELETE zusätzlich). Dateiname hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
GRANT SELECT, INSERT ON taktovia.vehicle_km_run, taktovia.vehicle_km_segment TO taktovia_runtime;
