-- WP-M4-39/-40: Kostenartenkatalog (lesen, anlegen; kein Ändern und Löschen, Katalogpflege folgt mit D-07), Seed-Funktion; manuelles Kostenereignis und seine Freigabeschritte.
-- Dateiname sortiert hinter R__runtime_grants.sql.
GRANT SELECT, INSERT ON taktovia.cost_category TO taktovia_runtime;
GRANT EXECUTE ON FUNCTION taktovia.seed_cost_categories() TO taktovia_runtime;
GRANT SELECT, INSERT, UPDATE ON taktovia.manual_cost_event TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.manual_cost_event_step TO taktovia_runtime;
