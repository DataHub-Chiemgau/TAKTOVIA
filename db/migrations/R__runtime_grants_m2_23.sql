-- WP-M2-23: Rechte für den Buchungsnummernzähler (hinter R__runtime_grants.sql sortiert). Der Zähler wird nur über die Funktion in der Transaktion der Bestätigung fortgeschrieben.
GRANT SELECT, INSERT, UPDATE ON taktovia.booking_number_counter TO taktovia_runtime;
GRANT EXECUTE ON FUNCTION taktovia.next_booking_number() TO taktovia_runtime;
