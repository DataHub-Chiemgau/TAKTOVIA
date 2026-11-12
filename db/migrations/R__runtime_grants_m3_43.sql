-- WP-M3-43: Rechte für den Fallnummernzähler (hinter R__runtime_grants.sql sortiert). Der Zähler wird nur über die Funktion in der Anlagetransaktion fortgeschrieben.
GRANT SELECT, INSERT, UPDATE ON taktovia.defect_case_counter TO taktovia_runtime;
GRANT EXECUTE ON FUNCTION taktovia.next_defect_case_number() TO taktovia_runtime;
