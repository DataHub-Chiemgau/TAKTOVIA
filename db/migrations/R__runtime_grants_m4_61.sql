-- WP-M4-61/-62: Modellrevisionen des ETA-Grundmodells sind fuer die Runtime-Rolle nur lesbar (Anlage und Freigabe durch den Betrieb); die Systemgrenzen der Konfiguration sind lesbar.
-- Dateiname hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
GRANT SELECT ON taktovia.eta_model_revision TO taktovia_runtime;
GRANT EXECUTE ON FUNCTION taktovia.policy_system_floor(), taktovia.eta_buffer_table_valid(jsonb) TO taktovia_runtime;
