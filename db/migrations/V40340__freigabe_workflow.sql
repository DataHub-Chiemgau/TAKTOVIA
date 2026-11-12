-- WP-M4-34 (LH-I.1-DOC-010): Freigabe-Workflow mit Rollentrennung und Vier-Augen-Schwelle (Modul documents-finance).
-- `approval_step`: jeder Schritt (Erfassung CAPTURE, fachliche Prüfung REVIEW, Budgetfreigabe BUDGET, zweite Freigabe SECOND_APPROVAL) mit Entscheider, Dokumentrevision, Entscheidung, Begründung und Zeit,
-- append-only. Die Rollentrennung hält zusätzlich die Datenbank: weder Budgetfreigabe noch zweite Freigabe durch den Erfasser, die zweite Freigabe nie durch den Entscheider der Erstfreigabe derselben Revision.
-- Der Exportstatus ist ein eigenes Statusfeld am Beleg, das nur nach dem Posting und nur einmal gesetzt werden kann (den Exportjob und seine Berechtigung liefert WP-M4-37, hier die Sperre).
-- Belegzustände (`expense_document.state`, Text ohne CHECK wie bisher): STORED (Erfassung), IN_REVIEW, REVIEWED, WAITING_SECOND_APPROVAL, APPROVED, POSTED; Ablehnung führt nach STORED zurück.
SET search_path = taktovia, public;

ALTER TABLE expense_document
  ADD COLUMN export_state text        NOT NULL DEFAULT 'NOT_EXPORTED' CONSTRAINT expense_document_export_state CHECK (export_state IN ('NOT_EXPORTED', 'EXPORTED')),
  ADD COLUMN exported_at  timestamptz,
  ADD COLUMN exported_by  text        CONSTRAINT expense_document_exported_by CHECK (length(exported_by) BETWEEN 1 AND 200),
  ADD CONSTRAINT expense_document_exported_pair CHECK ((export_state = 'EXPORTED') = (exported_at IS NOT NULL AND exported_by IS NOT NULL));

-- Kein Export ohne Posting (und damit ohne beide Freigaben); einmal exportiert bleibt exportiert.
CREATE FUNCTION expense_document_export_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.export_state IS DISTINCT FROM OLD.export_state THEN
    IF OLD.export_state = 'EXPORTED' THEN RAISE EXCEPTION 'expense_document: ein exportierter Beleg bleibt exportiert' USING ERRCODE = '23514'; END IF;
    IF NEW.state <> 'POSTED' THEN RAISE EXCEPTION 'expense_document: Export nur fuer gebuchte Belege (Zustand %)', NEW.state USING ERRCODE = '23514'; END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER expense_document_export_guard BEFORE UPDATE ON expense_document FOR EACH ROW EXECUTE FUNCTION expense_document_export_guard();

-- Vier-Augen-Schwelle (`approval_stage_thresholds`, Planungsstand D-06): Beträge über der Schwelle brauchen eine zweite Freigabe durch eine andere Person; NULL = keine zweite Stufe.
ALTER TABLE finance_policy
  ADD COLUMN approval_second_threshold_minor bigint   CONSTRAINT finance_policy_second_threshold CHECK (approval_second_threshold_minor > 0 AND approval_second_threshold_minor <= 9007199254740991),
  ADD COLUMN approval_threshold_currency     char(3)  NOT NULL DEFAULT 'EUR' REFERENCES currency(code);

CREATE TABLE approval_step (
  tenant_id         uuid        NOT NULL REFERENCES tenant(id),
  id                uuid        NOT NULL,
  document_id       uuid        NOT NULL,
  document_revision bigint      NOT NULL CHECK (document_revision >= 1),
  step              text        NOT NULL CONSTRAINT approval_step_step CHECK (step IN ('CAPTURE', 'REVIEW', 'BUDGET', 'SECOND_APPROVAL')),
  decision          text        NOT NULL CONSTRAINT approval_step_decision CHECK (decision IN ('APPROVED', 'REJECTED')),
  decided_by        text        NOT NULL CHECK (length(decided_by) BETWEEN 1 AND 200),
  reason            text        NOT NULL CHECK (length(reason) BETWEEN 3 AND 1000),
  decided_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  CONSTRAINT approval_step_capture_approved CHECK (step <> 'CAPTURE' OR decision = 'APPROVED'),
  FOREIGN KEY (tenant_id, document_id) REFERENCES expense_document(tenant_id, id)
);
CREATE INDEX approval_step_by_document ON approval_step (tenant_id, document_id, decided_at);
ALTER TABLE approval_step ENABLE ROW LEVEL SECURITY;
ALTER TABLE approval_step FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON approval_step USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER approval_step_immutable BEFORE UPDATE OR DELETE ON approval_step FOR EACH ROW EXECUTE FUNCTION taktovia.reject_mutation();

-- Rollentrennung in der Datenbank (Selbstfreigabe und Doppelfreigabe sind auch an der Anwendung vorbei unmöglich).
CREATE FUNCTION approval_step_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE creator text; first_by text;
BEGIN
  IF NEW.decision = 'APPROVED' AND NEW.step IN ('BUDGET', 'SECOND_APPROVAL') THEN
    SELECT created_by INTO creator FROM expense_document WHERE tenant_id = NEW.tenant_id AND id = NEW.document_id;
    IF creator IS NOT NULL AND creator = NEW.decided_by THEN
      RAISE EXCEPTION 'approval_step: der Erfasser gibt seinen Beleg nicht selbst frei' USING ERRCODE = '23514';
    END IF;
  END IF;
  IF NEW.decision = 'APPROVED' AND NEW.step = 'SECOND_APPROVAL' THEN
    SELECT decided_by INTO first_by FROM approval_step WHERE tenant_id = NEW.tenant_id AND document_id = NEW.document_id AND document_revision = NEW.document_revision
       AND step = 'BUDGET' AND decision = 'APPROVED' ORDER BY decided_at DESC, id DESC LIMIT 1;
    IF first_by IS NULL THEN RAISE EXCEPTION 'approval_step: zweite Freigabe ohne Erstfreigabe dieser Revision' USING ERRCODE = '23514'; END IF;
    IF first_by = NEW.decided_by THEN RAISE EXCEPTION 'approval_step: die zweite Freigabe erteilt eine andere Person als die Erstfreigabe' USING ERRCODE = '23514'; END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER approval_step_guard BEFORE INSERT ON approval_step FOR EACH ROW EXECUTE FUNCTION approval_step_guard();
