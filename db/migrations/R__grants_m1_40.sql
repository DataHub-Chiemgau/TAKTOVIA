-- WP-M1-40: Die Runtime-Rolle darf die Umgebungskennung lesen (Startpruefung des Backends), sonst nichts im Metaschema.
GRANT USAGE ON SCHEMA taktovia_meta TO taktovia_runtime;
REVOKE ALL ON taktovia_meta.environment_marker FROM taktovia_runtime;
GRANT SELECT ON taktovia_meta.environment_marker TO taktovia_runtime;
