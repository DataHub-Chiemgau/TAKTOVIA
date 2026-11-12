-- WP-M4-41: Kilometerstandsregister. Bestand und Konflikte sind append-only (kein UPDATE, kein DELETE); die Policy ist je Mandant änderbar. Dateiname sortiert hinter R__runtime_grants.sql.
GRANT SELECT, INSERT ON taktovia.odometer_reading, taktovia.odometer_conflict, taktovia.odometer_conflict_resolution TO taktovia_runtime;
GRANT SELECT, INSERT, UPDATE ON taktovia.odometer_policy TO taktovia_runtime;
