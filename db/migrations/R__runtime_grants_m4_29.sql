-- WP-M4-29: Zuordnung Beleg <-> Kostenobjekt: lesen, anlegen, ersetzen (DELETE im Entwurfszustand, Trigger begrenzt); kein UPDATE. Dateiname sortiert hinter R__runtime_grants.sql.
GRANT SELECT, INSERT, DELETE ON taktovia.expense_cost_assignment TO taktovia_runtime;
