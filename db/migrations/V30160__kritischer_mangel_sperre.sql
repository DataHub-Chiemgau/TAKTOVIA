-- WP-M3-16 / LH-6.21-CHK-004: Kritischer Mangel sperrt synchron.
-- 1. checklist_step.block_kind: Sperrart des Prüfelements (VEHICLE = Fahrzeugsperre, SERVICE = Dienstsperre der Schicht); nur bei sicherheitskritischen Elementen. NULL bei einem kritischen Element
--    bedeutet VEHICLE (Altbestand). Die Art bestimmt allein das Prüfelement, nie Rolle oder Antwort des Fahrers.
-- 2. Vorgangsnummer der Sperre (SPE-<Jahr>-<laufende Nummer>): Zähler je Mandant und Jahr wie bei der Schadenakte; `vehicle_block.case_number` und `shift_block.case_number`.
-- 3. shift_block: Dienstsperre. Sie verhindert die Einsatzfreigabe genau dieser Schicht (Prüfung und Dispositionsprüfung), ohne das Fahrzeug für andere Schichten zu sperren und ohne
--    `safety_revision` zu erhöhen. Aufgehoben wird sie nur von der technischen Wiederfreigabe (WP-M3-51); der Fahrer hat keinen Weg dazu.
-- 4. Invariante (zurückgestellter Constraint-Trigger, gilt auch bei direktem SQL): ein abgeschlossener Prüflauf mit Ergebnis BLOCKED, der ein DEFECT auf einem sicherheitskritischen Element enthält,
--    existiert nur gemeinsam mit einer Sperre dieses Laufs (Fahrzeug- oder Dienstsperre mit Quellbezug `<Lauf>:<Element>`) in derselben Transaktion: kein Zustand „Mangel gespeichert, Fahrzeug disponierbar“.
SET search_path = taktovia, public;

ALTER TABLE checklist_step
  ADD COLUMN block_kind text CHECK (block_kind IS NULL OR block_kind IN ('VEHICLE', 'SERVICE')),
  ADD CONSTRAINT checklist_step_block_kind_critical CHECK (block_kind IS NULL OR safety_critical);

CREATE TABLE block_case_counter (
  tenant_id   uuid   NOT NULL REFERENCES tenant(id),
  case_year   int    NOT NULL CHECK (case_year BETWEEN 2000 AND 2999),
  last_number bigint NOT NULL CHECK (last_number >= 1),
  PRIMARY KEY (tenant_id, case_year)
);
ALTER TABLE block_case_counter ENABLE ROW LEVEL SECURITY;
ALTER TABLE block_case_counter FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON block_case_counter USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON block_case_counter FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE FUNCTION block_case_counter_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'block_case_counter: kein Loeschen' USING ERRCODE = '23514'; END IF;
  IF NEW.case_year IS DISTINCT FROM OLD.case_year OR NEW.last_number <> OLD.last_number + 1 THEN
    RAISE EXCEPTION 'block_case_counter: der Zaehler steigt nur um 1' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER block_case_counter_guard BEFORE UPDATE OR DELETE ON block_case_counter FOR EACH ROW EXECUTE FUNCTION block_case_counter_guard();

CREATE FUNCTION next_block_case_number() RETURNS text LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE y int := extract(year FROM clock_timestamp() AT TIME ZONE 'UTC')::int; n bigint;
BEGIN
  INSERT INTO block_case_counter AS c (tenant_id, case_year, last_number) VALUES (taktovia.current_tenant(), y, 1)
  ON CONFLICT (tenant_id, case_year) DO UPDATE SET last_number = c.last_number + 1
  RETURNING c.last_number INTO n;
  RETURN 'SPE-' || y || '-' || lpad(n::text, 6, '0');
END $$;

ALTER TABLE vehicle_block ADD COLUMN case_number text CHECK (case_number IS NULL OR case_number ~ '^SPE-[0-9]{4}-[0-9]{6,}$');

CREATE OR REPLACE FUNCTION vehicle_block_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'vehicle block is append-only' USING ERRCODE = '23514'; END IF;
  IF OLD.status <> 'ACTIVE' OR NEW.status <> 'LIFTED'
     OR NEW.id IS DISTINCT FROM OLD.id OR NEW.vehicle_id IS DISTINCT FROM OLD.vehicle_id OR NEW.source IS DISTINCT FROM OLD.source
     OR NEW.reason_code IS DISTINCT FROM OLD.reason_code OR NEW.reason_ref IS DISTINCT FROM OLD.reason_ref OR NEW.set_by IS DISTINCT FROM OLD.set_by
     OR NEW.set_at IS DISTINCT FROM OLD.set_at OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.case_number IS DISTINCT FROM OLD.case_number THEN
    RAISE EXCEPTION 'vehicle block may only be lifted once' USING ERRCODE = '23514';
  END IF;
  NEW.revision := OLD.revision + 1;
  RETURN NEW;
END $$;

CREATE TABLE shift_block (
  tenant_id   uuid        NOT NULL REFERENCES tenant(id),
  id          uuid        NOT NULL,
  shift_id    uuid        NOT NULL,
  vehicle_id  uuid        NOT NULL,
  source      text        NOT NULL DEFAULT 'SAFETY' CHECK (source = 'SAFETY'),
  reason_code text        NOT NULL DEFAULT 'INSPECTION_FAILED' CHECK (reason_code = 'INSPECTION_FAILED'),
  reason_ref  text        NOT NULL CHECK (length(reason_ref) BETWEEN 1 AND 120),
  case_number text        NOT NULL CHECK (case_number ~ '^SPE-[0-9]{4}-[0-9]{6,}$'),
  status      text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'LIFTED')),
  set_by      text        NOT NULL CHECK (set_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  set_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  lifted_by   text        CHECK (lifted_by IS NULL OR lifted_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  lifted_at   timestamptz,
  revision    bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, shift_id)   REFERENCES staff_shift(tenant_id, id),
  FOREIGN KEY (tenant_id, vehicle_id) REFERENCES vehicle(tenant_id, id),
  CONSTRAINT shift_block_lift_consistent CHECK ((status = 'LIFTED') = (lifted_at IS NOT NULL AND lifted_by IS NOT NULL))
);
CREATE UNIQUE INDEX shift_block_dedupe ON shift_block (tenant_id, shift_id, reason_ref) WHERE status = 'ACTIVE';
CREATE INDEX shift_block_by_shift ON shift_block (tenant_id, shift_id) WHERE status = 'ACTIVE';
CREATE INDEX shift_block_by_vehicle ON shift_block (tenant_id, vehicle_id) WHERE status = 'ACTIVE';
ALTER TABLE shift_block ENABLE ROW LEVEL SECURITY;
ALTER TABLE shift_block FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shift_block USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON shift_block FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();

CREATE FUNCTION shift_block_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'shift block is append-only' USING ERRCODE = '23514'; END IF;
  IF OLD.status <> 'ACTIVE' OR NEW.status <> 'LIFTED'
     OR NEW.id IS DISTINCT FROM OLD.id OR NEW.shift_id IS DISTINCT FROM OLD.shift_id OR NEW.vehicle_id IS DISTINCT FROM OLD.vehicle_id
     OR NEW.reason_ref IS DISTINCT FROM OLD.reason_ref OR NEW.case_number IS DISTINCT FROM OLD.case_number OR NEW.set_by IS DISTINCT FROM OLD.set_by
     OR NEW.set_at IS DISTINCT FROM OLD.set_at OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'shift block may only be lifted once' USING ERRCODE = '23514';
  END IF;
  NEW.revision := OLD.revision + 1;
  RETURN NEW;
END $$;
CREATE TRIGGER shift_block_guard BEFORE UPDATE OR DELETE ON shift_block FOR EACH ROW EXECUTE FUNCTION shift_block_guard();

CREATE FUNCTION inspection_critical_block_check() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM inspection_answer a JOIN checklist_step s ON s.tenant_id = a.tenant_id AND s.template_revision_id = NEW.template_revision_id AND s.step_code = a.step_code
              WHERE a.tenant_id = NEW.tenant_id AND a.inspection_id = NEW.id AND a.answer = 'DEFECT' AND s.safety_critical)
     AND NOT EXISTS (SELECT 1 FROM vehicle_block b WHERE b.tenant_id = NEW.tenant_id AND b.vehicle_id = NEW.vehicle_id AND b.reason_ref LIKE NEW.id::text || ':%')
     AND NOT EXISTS (SELECT 1 FROM shift_block b WHERE b.tenant_id = NEW.tenant_id AND b.shift_id = NEW.shift_id AND b.reason_ref LIKE NEW.id::text || ':%') THEN
    RAISE EXCEPTION 'CRITICAL_DEFECT_WITHOUT_BLOCK: a completed inspection with a safety-critical defect needs its vehicle or shift block in the same transaction' USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER inspection_critical_block_check AFTER UPDATE ON inspection_run DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
  WHEN (OLD.status = 'IN_PROGRESS' AND NEW.status = 'COMPLETED' AND NEW.outcome = 'BLOCKED') EXECUTE FUNCTION inspection_critical_block_check();
