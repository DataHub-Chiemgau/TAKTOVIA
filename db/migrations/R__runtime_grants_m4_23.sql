-- WP-M4-23: document_revision ist append-only (Trigger); die Runtime-Rolle darf nur lesen und anhängen. Dateiname sortiert hinter R__runtime_grants.sql.
GRANT SELECT, INSERT ON taktovia.document_revision TO taktovia_runtime;
