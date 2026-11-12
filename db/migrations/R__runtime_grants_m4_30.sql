-- WP-M4-30: Tankvorgang je Beleg (lesen, anlegen, ändern im Entwurf; kein DELETE) und Fachparameter des Moduls. Dateiname sortiert hinter R__runtime_grants.sql.
GRANT SELECT, INSERT, UPDATE ON taktovia.fuel_transaction TO taktovia_runtime;
GRANT SELECT, INSERT, UPDATE ON taktovia.finance_policy TO taktovia_runtime;
