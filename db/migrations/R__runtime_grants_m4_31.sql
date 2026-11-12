-- WP-M4-31: Hinweise und Quittierungen nur lesen und anlegen (append-only, Trigger sperren UPDATE/DELETE zusätzlich), Fahrzeugparameter lesen/anlegen/ändern. Dateiname sortiert hinter R__runtime_grants.sql.
GRANT SELECT, INSERT ON taktovia.expense_hint TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.expense_hint_ack TO taktovia_runtime;
GRANT SELECT, INSERT, UPDATE ON taktovia.fuel_vehicle_policy TO taktovia_runtime;
