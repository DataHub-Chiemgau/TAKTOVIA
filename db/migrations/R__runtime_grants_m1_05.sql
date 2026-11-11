-- WP-M1-05: Mandantenanlage und Statuswechsel durch die Plattformverwaltung (Server prüft das Betreiberkonto). Der Dateiname sortiert hinter
-- R__runtime_grants.sql (diese beginnt mit REVOKE ALL). Spaltenweise: Name und Kennung eines Mandanten bleiben nach dem Anlegen unveränderlich.
GRANT INSERT (id, name, status) ON taktovia.tenant TO taktovia_runtime;
GRANT UPDATE (status) ON taktovia.tenant TO taktovia_runtime;
GRANT SELECT ON taktovia.platform_operator_account TO taktovia_runtime;
GRANT EXECUTE ON FUNCTION taktovia.organization_roles_valid(text[]) TO taktovia_runtime;
