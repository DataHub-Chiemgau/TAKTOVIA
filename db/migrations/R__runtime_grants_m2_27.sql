-- WP-M2-27: Lesesicht auf die Datenbasis der Stornoquoten (hinter R__runtime_grants.sql sortiert); die Sicht läuft mit den Rechten des Aufrufers, RLS der Tabellen gilt.
GRANT SELECT ON taktovia.booking_cancellation_basis TO taktovia_runtime;
