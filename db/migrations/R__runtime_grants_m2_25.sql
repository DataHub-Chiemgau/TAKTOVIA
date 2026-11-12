-- WP-M2-25: Rechte für Statushistorie und Gründekatalog (hinter R__runtime_grants.sql sortiert). Die Historie ist append-only: kein UPDATE, kein DELETE (Grants und Trigger).
GRANT SELECT, INSERT ON taktovia.booking_status_history TO taktovia_runtime;
GRANT SELECT ON taktovia.booking_status_reason TO taktovia_runtime;
