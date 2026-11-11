-- WP-M1-30: Einwilligungsrevisionen sind append-only: nur SELECT und INSERT (Dateiname sortiert hinter R__runtime_grants.sql).
GRANT SELECT, INSERT ON taktovia.consent_revision TO taktovia_runtime;
