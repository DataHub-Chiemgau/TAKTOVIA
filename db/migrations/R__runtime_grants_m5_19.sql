-- WP-M5-19: Rechte für export_job. Der Dateiname sortiert hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL). Anlegen, lesen, Zustand und Ergebnis fortschreiben (Trigger export_job_guard); kein DELETE.
GRANT SELECT, INSERT, UPDATE ON taktovia.export_job TO taktovia_runtime;
