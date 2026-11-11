-- WP-M1-17: Rechte für state_transition_rule. Der Dateiname sortiert hinter R__runtime_grants.sql, weil diese Datei zuerst alle Tabellenrechte
-- der Runtime-Rolle entzieht (db.py führt R__-Dateien alphabetisch aus).
GRANT SELECT ON taktovia.state_transition_rule TO taktovia_runtime;
