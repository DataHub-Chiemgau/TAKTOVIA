-- WP-M3-16: Dienstsperre und Vorgangsnummer der Sperre (hinter R__runtime_grants.sql sortiert). Sperren werden nie gelöscht (kein DELETE); der Wächter lässt nur ACTIVE -> LIFTED zu.
GRANT SELECT, INSERT, UPDATE ON taktovia.shift_block TO taktovia_runtime;
GRANT SELECT, INSERT, UPDATE ON taktovia.block_case_counter TO taktovia_runtime;
GRANT EXECUTE ON FUNCTION taktovia.next_block_case_number() TO taktovia_runtime;
