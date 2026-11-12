-- WP-M3-08: Sperr- und Freigabekern (Safety-Revision, resource_release), Modul fleet-safety.
-- Additiv zu V1 (vehicle.active_block, vehicle.safety_revision, resource_release) und V3020 (vehicle_block, OP-01):
-- 1. vehicle_block bekommt einen Quellbezug (reason_ref, z. B. Schadenakte oder Prüflauf). Dieselbe Sperre (Fahrzeug, Quelle, Grund, Bezug) ist höchstens einmal ACTIVE:
--    der partielle Unique-Index macht das Setzen idempotent (kein zweiter Sperrgrund, kein zweiter Revisionssprung), auch bei gleichzeitigen Aufrufen.
-- 2. resource_release bekommt Ausstellung (Zeit, Akteur) und Widerruf (Akteur, Grund). Eine Einsatzfreigabe gehört genau zu einem Prüflauf (Unique-Index auf inspection_ref):
--    nach einem Widerruf entsteht keine Wiederaufnahme, sondern nur eine neue Freigabe aus einer neuen Prüfung (Zustandsautomat einsatzfreigabe.md).
--    Der Wächter lässt nur noch den Übergang "ausgestellt -> widerrufen" zu; der Zustand EXPIRED wird aus valid_until abgeleitet, nie gespeichert.
-- 3. vehicle.safety_revision steigt nur (Wächter): niemand kann sie senken und damit eine veraltete Freigabe wieder gültig machen.
SET search_path = taktovia, public;

ALTER TABLE vehicle_block
  ADD COLUMN reason_ref text CHECK (reason_ref IS NULL OR length(reason_ref) BETWEEN 1 AND 120);
CREATE UNIQUE INDEX vehicle_block_dedupe ON vehicle_block (tenant_id, vehicle_id, source, reason_code, COALESCE(reason_ref, '')) WHERE status = 'ACTIVE';

CREATE OR REPLACE FUNCTION vehicle_block_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'vehicle block is append-only' USING ERRCODE = '23514'; END IF;
  IF OLD.status <> 'ACTIVE' OR NEW.status <> 'LIFTED'
     OR NEW.id IS DISTINCT FROM OLD.id OR NEW.vehicle_id IS DISTINCT FROM OLD.vehicle_id OR NEW.source IS DISTINCT FROM OLD.source
     OR NEW.reason_code IS DISTINCT FROM OLD.reason_code OR NEW.reason_ref IS DISTINCT FROM OLD.reason_ref OR NEW.set_by IS DISTINCT FROM OLD.set_by
     OR NEW.set_at IS DISTINCT FROM OLD.set_at OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'vehicle block may only be lifted once' USING ERRCODE = '23514';
  END IF;
  NEW.revision := OLD.revision + 1;
  RETURN NEW;
END $$;

ALTER TABLE resource_release
  ADD COLUMN issued_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  ADD COLUMN issued_by     text CHECK (issued_by IS NULL OR issued_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  ADD COLUMN revoked_by    text CHECK (revoked_by IS NULL OR revoked_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  ADD COLUMN revoke_reason text CHECK (revoke_reason IS NULL OR revoke_reason IN ('BLOCK_SET', 'VEHICLE_CHANGED', 'QUALIFICATION_REVOKED', 'MANUAL'));
CREATE UNIQUE INDEX resource_release_one_per_inspection ON resource_release (tenant_id, inspection_ref);

CREATE FUNCTION resource_release_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'resource release is append-only' USING ERRCODE = '23514'; END IF;
  IF OLD.revoked_at IS NOT NULL
     OR NEW.revoked_at IS NULL
     OR NEW.id IS DISTINCT FROM OLD.id OR NEW.vehicle_id IS DISTINCT FROM OLD.vehicle_id OR NEW.shift_id IS DISTINCT FROM OLD.shift_id
     OR NEW.safety_revision IS DISTINCT FROM OLD.safety_revision OR NEW.inspection_ref IS DISTINCT FROM OLD.inspection_ref
     OR NEW.qualification_revision IS DISTINCT FROM OLD.qualification_revision OR NEW.valid_until IS DISTINCT FROM OLD.valid_until
     OR NEW.issued_at IS DISTINCT FROM OLD.issued_at OR NEW.issued_by IS DISTINCT FROM OLD.issued_by THEN
    RAISE EXCEPTION 'resource release may only be revoked once' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER resource_release_guard BEFORE UPDATE OR DELETE ON resource_release FOR EACH ROW EXECUTE FUNCTION resource_release_guard();

CREATE FUNCTION vehicle_safety_revision_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.safety_revision < OLD.safety_revision THEN
    RAISE EXCEPTION 'vehicle safety revision may only increase' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER vehicle_safety_revision_guard BEFORE UPDATE OF safety_revision ON vehicle FOR EACH ROW EXECUTE FUNCTION vehicle_safety_revision_guard();
