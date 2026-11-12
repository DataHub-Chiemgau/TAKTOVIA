-- WP-M4-27: extraction_run (Zustand ändert sich, kein DELETE) und extracted_field (append-only, Trigger). Dateiname sortiert hinter R__runtime_grants.sql.
GRANT SELECT, INSERT, UPDATE ON taktovia.extraction_run TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.extracted_field TO taktovia_runtime;
