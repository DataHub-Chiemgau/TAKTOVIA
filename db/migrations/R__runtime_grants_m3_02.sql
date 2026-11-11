-- WP-M3-02: Rechte der M3-Tabellen. Der Dateiname sortiert hinter R__runtime_grants.sql (diese beginnt mit REVOKE ALL).
-- Unveränderliche Nachweise: nur lesen und anhängen. Ein Löschrecht gibt es nirgends (kein TRUNCATE, keine Rechte auf Partitionen).
GRANT SELECT, INSERT, UPDATE ON taktovia.checklist_template_revision TO taktovia_runtime;
GRANT SELECT, INSERT, UPDATE ON taktovia.checklist_step TO taktovia_runtime;
GRANT SELECT, INSERT, UPDATE ON taktovia.inspection_run TO taktovia_runtime;
GRANT SELECT, INSERT, UPDATE ON taktovia.inspection_answer TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.inspection_amendment TO taktovia_runtime;
GRANT SELECT, INSERT, UPDATE ON taktovia.defect_case TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.known_defect_acknowledgement TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.defect_evidence TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.defect_status_history TO taktovia_runtime;
GRANT SELECT, INSERT, UPDATE ON taktovia.repair_order TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.vehicle_release_decision TO taktovia_runtime;
GRANT SELECT, INSERT, UPDATE ON taktovia.vehicle_block TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.alert_policy_revision TO taktovia_runtime;
GRANT SELECT, INSERT, UPDATE ON taktovia.business_alert TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.alert_acknowledgement TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.workflow_rule_revision TO taktovia_runtime;
GRANT SELECT, INSERT, UPDATE ON taktovia.incident TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.incident_link TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.workflow_run_log TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.position_sample TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.vehicle_status_history TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.driver_plan_ack TO taktovia_runtime;
GRANT SELECT, INSERT, UPDATE ON taktovia.boarding_code TO taktovia_runtime;
GRANT SELECT, INSERT ON taktovia.booking_change TO taktovia_runtime;
GRANT EXECUTE ON FUNCTION taktovia.capacity_dimension_valid(jsonb, text) TO taktovia_runtime;
