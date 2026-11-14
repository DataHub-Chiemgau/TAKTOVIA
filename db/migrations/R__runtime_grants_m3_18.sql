-- WP-M3-18: Rechte für tech_event (nur anhängen und lesen; Trigger sperrt UPDATE/DELETE zusätzlich). Der Dateiname sortiert hinter R__runtime_grants.sql.
GRANT SELECT, INSERT ON taktovia.tech_event TO taktovia_runtime;
