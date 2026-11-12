-- WP-M4-33: Kontierungsregeln lesen/anlegen/ändern. economic_event, journal und journal_line behalten ihre Rechte aus R__runtime_grants.sql (Trigger schützen). Dateiname sortiert hinter R__runtime_grants.sql.
GRANT SELECT, INSERT, UPDATE ON taktovia.accounting_mapping TO taktovia_runtime;
