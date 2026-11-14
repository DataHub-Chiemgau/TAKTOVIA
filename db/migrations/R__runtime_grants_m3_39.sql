-- WP-M3-39: Bindung der Leitstellenangebote an den Fahrgast. Anlegen und Lesen durch booking-dispatch; kein UPDATE/DELETE (Trigger). Dateiname hinter R__runtime_grants.sql (REVOKE ALL dort).
GRANT SELECT, INSERT ON taktovia.operator_offer_subject TO taktovia_runtime;
