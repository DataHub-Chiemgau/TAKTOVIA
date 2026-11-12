-- WP-M3-44: Rechte für das Uploadticket der Schadenevidenz (hinter R__runtime_grants.sql sortiert). Kein DELETE (Trigger sperrt es zusätzlich).
GRANT SELECT, INSERT, UPDATE ON taktovia.defect_evidence_upload TO taktovia_runtime;
