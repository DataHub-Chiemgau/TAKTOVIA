-- WP-M2-28: Rechte für den Änderungsstrom der Planrevisionen (hinter R__runtime_grants.sql sortiert). Append-only: kein UPDATE/DELETE.
GRANT SELECT, INSERT ON taktovia.plan_change TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.plan_change_booking TO taktovia_runtime;
