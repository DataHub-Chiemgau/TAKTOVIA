-- WP-M4-24: document_integrity_incident ist append-only (Trigger); die Runtime-Rolle darf nur lesen und anhängen. Dateiname sortiert hinter R__runtime_grants.sql.
GRANT SELECT, INSERT ON taktovia.document_integrity_incident TO taktovia_runtime;
