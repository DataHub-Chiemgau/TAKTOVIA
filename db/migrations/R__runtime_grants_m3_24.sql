-- WP-M3-24: Rechte für driver_device und driver_event. Der Dateiname sortiert hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
-- Gerät: anlegen, lesen, Sitzung neu binden bzw. beenden; kein DELETE. Ereignis: nur anhängen und lesen (Trigger sperrt UPDATE/DELETE zusätzlich).
GRANT SELECT, INSERT, UPDATE ON taktovia.driver_device TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.driver_event TO taktovia_runtime;
