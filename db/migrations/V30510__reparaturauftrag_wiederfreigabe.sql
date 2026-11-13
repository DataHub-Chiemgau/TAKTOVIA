-- WP-M3-51: Reparaturauftrag und Wiederfreigabe mit Rollenprüfung (Enabler, DMG P1 Umfeld; AT-35, AT-51, AT-60).
-- 1. `repair_order`: Zustandsautomat per Trigger (OPEN -> IN_PROGRESS | CANCELED, IN_PROGRESS -> PROOF_RECORDED | CANCELED; PROOF_RECORDED und CANCELED sind endgültig), Reparaturnachweis als
--    freigegebene Evidenz derselben Akte (`proof_evidence_id`) zusammen mit dem Kostenbezug, Stornogrund, höchstens ein nicht stornierter Auftrag je Akte, Revision je Änderung +1.
-- 2. `vehicle_release_decision` (unveränderlich seit V3020): Rolle der Entscheidenden, Schadenakte, Nachweis, Begründung und Bezug der zweiten Entscheidung auf die erste (Vieraugenpflicht, OP-11).
--    Je Reparaturauftrag höchstens eine ausstehende und höchstens eine abgeschlossene Entscheidung.
SET search_path = taktovia, public;

ALTER TABLE repair_order
  ADD COLUMN proof_evidence_id  uuid,
  ADD COLUMN proof_recorded_at  timestamptz,
  ADD COLUMN proof_recorded_by  text CHECK (proof_recorded_by IS NULL OR proof_recorded_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  ADD COLUMN cancel_reason      text CHECK (cancel_reason IS NULL OR length(btrim(cancel_reason)) BETWEEN 1 AND 500),
  ADD CONSTRAINT repair_order_proof_fk FOREIGN KEY (tenant_id, proof_evidence_id) REFERENCES defect_evidence(tenant_id, id);
CREATE UNIQUE INDEX repair_order_one_active_per_defect ON repair_order (tenant_id, defect_id) WHERE status <> 'CANCELED';
CREATE INDEX repair_order_by_vehicle ON repair_order (tenant_id, vehicle_id, created_at, id);
CREATE INDEX repair_order_by_defect ON repair_order (tenant_id, defect_id, created_at, id);

CREATE FUNCTION repair_order_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'repair_order: kein Loeschen' USING ERRCODE = '23514'; END IF;
  IF OLD.status IN ('PROOF_RECORDED', 'CANCELED') THEN
    RAISE EXCEPTION 'repair_order: % ist endgueltig', OLD.status USING ERRCODE = '23514';
  END IF;
  IF NEW.defect_id IS DISTINCT FROM OLD.defect_id OR NEW.vehicle_id IS DISTINCT FROM OLD.vehicle_id OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'repair_order: Bezug zu Akte und Fahrzeug ist unveraenderlich' USING ERRCODE = '23514';
  END IF;
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NOT ((OLD.status, NEW.status) IN (('OPEN', 'IN_PROGRESS'), ('OPEN', 'CANCELED'), ('IN_PROGRESS', 'PROOF_RECORDED'), ('IN_PROGRESS', 'CANCELED'))) THEN
      RAISE EXCEPTION 'repair_order: Uebergang % -> % nicht erlaubt', OLD.status, NEW.status USING ERRCODE = '23514';
    END IF;
    IF NEW.status = 'PROOF_RECORDED' AND (NEW.proof_evidence_id IS NULL OR NEW.proof_recorded_at IS NULL OR NEW.proof_recorded_by IS NULL OR NEW.cost_reference IS NULL
        OR NOT EXISTS (SELECT 1 FROM defect_evidence e WHERE e.tenant_id = NEW.tenant_id AND e.id = NEW.proof_evidence_id AND e.defect_id = NEW.defect_id)) THEN
      RAISE EXCEPTION 'repair_order: Reparaturnachweis braucht Nachweis der Akte und Kostenbezug' USING ERRCODE = '23514';
    END IF;
    IF NEW.status = 'CANCELED' AND NEW.cancel_reason IS NULL THEN
      RAISE EXCEPTION 'repair_order: Stornierung braucht eine Begruendung' USING ERRCODE = '23514';
    END IF;
  ELSIF NEW.proof_evidence_id IS DISTINCT FROM OLD.proof_evidence_id OR NEW.proof_recorded_at IS DISTINCT FROM OLD.proof_recorded_at OR NEW.cancel_reason IS DISTINCT FROM OLD.cancel_reason THEN
    RAISE EXCEPTION 'repair_order: Nachweis und Stornogrund nur mit dem Statuswechsel' USING ERRCODE = '23514';
  END IF;
  IF NEW.revision <> OLD.revision + 1 THEN RAISE EXCEPTION 'repair_order: Revision steigt je Aenderung um 1' USING ERRCODE = '23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER repair_order_guard BEFORE UPDATE OR DELETE ON repair_order FOR EACH ROW EXECUTE FUNCTION repair_order_guard();

ALTER TABLE vehicle_release_decision
  ADD COLUMN defect_id               uuid,
  ADD COLUMN decided_by_role         text CHECK (decided_by_role IS NULL OR decided_by_role ~ '^[a-z][a-z_]{1,39}$'),
  ADD COLUMN second_approver_role    text CHECK (second_approver_role IS NULL OR second_approver_role ~ '^[a-z][a-z_]{1,39}$'),
  ADD COLUMN reason                  text CHECK (reason IS NULL OR length(btrim(reason)) BETWEEN 1 AND 500),
  ADD COLUMN proof_evidence_id       uuid,
  ADD COLUMN supersedes_decision_id  uuid,
  ADD CONSTRAINT release_decision_defect_fk     FOREIGN KEY (tenant_id, defect_id)              REFERENCES defect_case(tenant_id, id),
  ADD CONSTRAINT release_decision_proof_fk      FOREIGN KEY (tenant_id, proof_evidence_id)      REFERENCES defect_evidence(tenant_id, id),
  ADD CONSTRAINT release_decision_supersedes_fk FOREIGN KEY (tenant_id, supersedes_decision_id) REFERENCES vehicle_release_decision(tenant_id, id),
  -- eine ausstehende Entscheidung benennt höchstens eine andere Person als zweite
  ADD CONSTRAINT release_pending_not_self CHECK (decision <> 'PENDING_SECOND_APPROVAL' OR second_approver IS NULL OR second_approver <> decided_by);
CREATE UNIQUE INDEX release_decision_one_released_per_order ON vehicle_release_decision (tenant_id, repair_order_id) WHERE decision = 'RELEASED';
CREATE UNIQUE INDEX release_decision_one_pending_per_order  ON vehicle_release_decision (tenant_id, repair_order_id) WHERE decision = 'PENDING_SECOND_APPROVAL';
CREATE INDEX release_decision_by_vehicle ON vehicle_release_decision (tenant_id, vehicle_id, decided_at);
