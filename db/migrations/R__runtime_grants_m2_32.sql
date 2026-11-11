-- WP-M2-32: Rechte für notification und notification_attempt (Dateiname sortiert hinter R__runtime_grants.sql, das mit REVOKE ALL beginnt).
-- Auftrag: anlegen, lesen, Zustand ändern (Zustandsautomat per Trigger), nie löschen. Zustellversuch: nur anfügen und lesen.
GRANT SELECT, INSERT, UPDATE ON taktovia.notification TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.notification_attempt TO taktovia_runtime;
