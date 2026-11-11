-- WP-M1-19: Parameterkatalog und Schema-Versionen sind für die Runtime-Rolle nur lesbar (Änderung nur per Migration, LH-I.7-CFG-004).
-- Dateiname hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
GRANT SELECT ON taktovia.policy_schema, taktovia.policy_schema_version TO taktovia_runtime;
GRANT EXECUTE ON FUNCTION taktovia.current_policy_version(timestamptz), taktovia.config_value_violations(text, text, jsonb) TO taktovia_runtime;
