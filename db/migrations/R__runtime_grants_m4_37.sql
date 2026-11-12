-- WP-M4-37: Exportbatch und -zeilen anlegen und fortschreiben (Unveränderlichkeit und Löschverbot per Trigger, kein DELETE-Recht). Dateiname sortiert hinter R__runtime_grants.sql.
GRANT SELECT, INSERT, UPDATE ON taktovia.ledger_export TO taktovia_runtime;
GRANT SELECT, INSERT, UPDATE ON taktovia.ledger_export_line TO taktovia_runtime;
