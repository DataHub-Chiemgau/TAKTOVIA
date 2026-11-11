-- WP-M1-07: Rechte (Dateiname sortiert hinter R__runtime_grants.sql, das mit REVOKE ALL beginnt). Katalog nur lesen, Ausrollprotokoll nur anhängen.
GRANT SELECT ON taktovia.role_catalog TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.rights_matrix_rollout TO taktovia_runtime;
