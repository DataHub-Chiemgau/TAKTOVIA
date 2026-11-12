-- WP-M4-35: allocation_set und allocation_line behalten die Kernrechte (SELECT, INSERT, UPDATE; Unveränderlichkeit per Trigger, kein DELETE). Neu: Aktivierung wechseln (kein DELETE),
-- Beleg-Split ersetzen (DELETE nur für den Austausch im Entwurf, per Trigger auf den Belegzustand STORED begrenzt). Dateiname sortiert hinter R__runtime_grants.sql.
GRANT SELECT, INSERT, UPDATE ON taktovia.allocation_activation TO taktovia_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON taktovia.expense_split TO taktovia_runtime;
GRANT SELECT, INSERT, DELETE ON taktovia.expense_split_line TO taktovia_runtime;
