-- WP-M4-54: Toleranzbindung der Ankunftspünktlichkeit nur lesen und anhängen (append-only), Seed der ausführbaren Ankunftskennzahlen.
GRANT SELECT, INSERT ON taktovia.arrival_tolerance_binding TO taktovia_runtime;
GRANT EXECUTE ON FUNCTION taktovia.seed_arrival_punctuality_definitions() TO taktovia_runtime;
