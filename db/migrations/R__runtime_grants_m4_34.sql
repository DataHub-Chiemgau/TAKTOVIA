-- WP-M4-34: Freigabeschritte nur lesen und anlegen (append-only). Dateiname sortiert hinter R__runtime_grants.sql.
GRANT SELECT, INSERT ON taktovia.approval_step TO taktovia_runtime;
