-- WP-M4-22: Rechte für document_settings (lesen, anlegen, ändern; kein DELETE). Der Dateiname sortiert hinter R__runtime_grants.sql.
GRANT SELECT, INSERT, UPDATE ON taktovia.document_settings TO taktovia_runtime;
