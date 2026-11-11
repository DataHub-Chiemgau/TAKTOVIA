-- WP-M1-10: Rechte für Sitzungsregister und Kontozugang. Der Dateiname muss hinter R__runtime_grants.sql sortieren (diese beginnt mit REVOKE ALL).
-- Kein DELETE: Sperren sind Zustandsänderungen, keine Löschungen; das Register wird nur durch die Löschklassen des Dateninventars entfernt (Betriebsprozess).
GRANT SELECT, INSERT, UPDATE ON taktovia.session_register TO taktovia_runtime;
GRANT SELECT, INSERT, UPDATE ON taktovia.account_access TO taktovia_runtime;
