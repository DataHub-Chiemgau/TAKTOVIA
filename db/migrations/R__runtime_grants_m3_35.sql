-- WP-M3-35: Rechte für den Live-Kanal (hinter R__runtime_grants.sql sortiert). INSERT nur über den Outbox-Trigger (Wächter live_event_insert_guard: Triggertiefe >= 2),
-- DELETE nur für die Bereinigung alter Kanalzeilen, UPDATE per Trigger verboten.
GRANT SELECT, INSERT, DELETE ON taktovia.live_event TO taktovia_runtime;
