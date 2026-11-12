-- WP-M4-21: Rechte für upload_ticket. Der Dateiname sortiert hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
-- Ticket ausstellen, lesen, Zustand vorwärts ändern (Trigger begrenzt); kein DELETE. expense_document behält die Rechte aus R__runtime_grants.sql.
GRANT SELECT, INSERT, UPDATE ON taktovia.upload_ticket TO taktovia_runtime;
