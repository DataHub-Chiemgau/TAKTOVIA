-- WP-M3-48: Rechte für die Revisionen der Schadenakte (hinter R__runtime_grants.sql sortiert): nur anhängen und lesen; Trigger sperrt UPDATE/DELETE zusätzlich.
GRANT SELECT, INSERT ON taktovia.defect_case_revision TO taktovia_runtime;
