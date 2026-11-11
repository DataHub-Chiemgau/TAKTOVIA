-- WP-M1-21: die Freigabeliste der Fahrzeug-Overrides ist für die Runtime-Rolle nur lesbar (Pflege per Migration).
GRANT SELECT ON taktovia.config_vehicle_override_release TO taktovia_runtime;
