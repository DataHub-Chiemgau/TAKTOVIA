-- WP-M4-45: Analytics-Faktenbasis. Eingang nur anhängen (Trigger sperrt UPDATE/DELETE zusätzlich); Fakten und Datenstand werden aus dem Eingang abgeleitet und beim Wiederaufbau ersetzt.
-- Dateiname hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
GRANT SELECT, INSERT ON taktovia.analytics_event_inbox TO taktovia_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON taktovia.fact_attempt, taktovia.fact_offer, taktovia.fact_booking, taktovia.fact_stop_event, taktovia.fact_vehicle_state, taktovia.dataset_watermark TO taktovia_runtime;
