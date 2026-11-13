-- WP-M3-45 / LH-6.22-DMG-005: Schadenklassifikation und Triage. Jede Akte trägt genau eine der vier Klassen (SAFETY_CRITICAL, OPERATIONALLY_RELEVANT, COSMETIC, OTHER; CHECK aus V3020)
-- und die Regelversion, nach der sie vorgeschlagen wurde; Klasse und Zuständiger ändern sich nur mit einer Zeile der Klassifikationshistorie (append-only: Akteur, Zeit, Grund,
-- alte/neue Klasse, Regelversion, Zuständiger vorher/nachher). Die Sperre selbst setzt der Sperrkern (WP-M3-08) im selben Commit; hier steht nur die Einstufung.
SET search_path = taktovia, public;

ALTER TABLE defect_case
  ADD COLUMN severity_rule_version text CHECK (severity_rule_version IS NULL OR severity_rule_version ~ '^[a-z0-9][a-z0-9._-]{0,39}$'),
  ADD COLUMN severity_source text CHECK (severity_source IS NULL OR severity_source IN ('RULE', 'REPORTER_HINT', 'TRIAGE')),
  ADD COLUMN classified_at timestamptz,
  ADD COLUMN classified_by text CHECK (classified_by IS NULL OR classified_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$');
-- eine Klasse gibt es nie ohne die Regelversion; NOT VALID: Altzeilen mit Klasse aus der Zeit vor der Regelversion (nur per Administration gesetzt) bleiben unberührt (expand/contract),
-- jede neue und jede geänderte Zeile wird geprüft
ALTER TABLE defect_case ADD CONSTRAINT defect_severity_needs_rule_version CHECK (severity IS NULL OR (severity_rule_version IS NOT NULL AND severity_source IS NOT NULL)) NOT VALID;

CREATE TABLE defect_classification_history (
  tenant_id        uuid        NOT NULL REFERENCES tenant(id),
  id               uuid        NOT NULL,
  defect_id        uuid        NOT NULL,
  from_severity    text        CHECK (from_severity IN ('SAFETY_CRITICAL', 'OPERATIONALLY_RELEVANT', 'COSMETIC', 'OTHER')),
  to_severity      text        NOT NULL CHECK (to_severity IN ('SAFETY_CRITICAL', 'OPERATIONALLY_RELEVANT', 'COSMETIC', 'OTHER')),
  rule_version     text        NOT NULL CHECK (rule_version ~ '^[a-z0-9][a-z0-9._-]{0,39}$'),
  rule_id          text        CHECK (rule_id IS NULL OR rule_id ~ '^[A-Z][A-Z0-9_]{1,19}$'),
  source           text        NOT NULL CHECK (source IN ('RULE', 'REPORTER_HINT', 'TRIAGE')),
  reason           text        CHECK (reason IS NULL OR length(btrim(reason)) BETWEEN 1 AND 500),
  assignee_before  uuid,
  assignee_after   uuid,
  actor            text        NOT NULL CHECK (actor ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  defect_revision  bigint      NOT NULL CHECK (defect_revision >= 1),
  created_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, defect_id) REFERENCES defect_case(tenant_id, id),
  -- eine Korrektur der Klasse ist ohne Begründung nicht speicherbar (die Erstklassifikation und die Bestätigung brauchen keine)
  CONSTRAINT defect_class_correction_needs_reason CHECK (source <> 'TRIAGE' OR from_severity IS NULL OR from_severity = to_severity OR reason IS NOT NULL)
);
CREATE INDEX defect_classification_history_by_defect ON defect_classification_history (tenant_id, defect_id, created_at);
ALTER TABLE defect_classification_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE defect_classification_history FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON defect_classification_history USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON defect_classification_history FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE TRIGGER defect_classification_history_immutable BEFORE UPDATE OR DELETE ON defect_classification_history FOR EACH ROW EXECUTE FUNCTION reject_mutation();
