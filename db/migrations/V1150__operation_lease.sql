-- WP-M1-15 (OP-15): generische `operation` mit Lease und Fencing (Kap. 02 §7.3, Kap. 04 §4.7).
-- operation.journey_id wird optional (nicht reisebezogene M1-Befehle), ein Fencing-Guard sichert monoton steigende Tokens und unveränderliche
-- Endzustände. Das kind-Enum (HOLD, CONFIRM, CANCEL, RECONCILE und die M1-Befehle) führen API-Vertrag und `OperationKind`; die Spalte bleibt Text, damit
-- spätere Meilensteine Arten ohne Migration ergänzen (expand). Die Zustandsübergänge selbst regelt seit V1170 `enforce_state_transition`.
SET search_path = taktovia, public;

ALTER TABLE operation
  ALTER COLUMN journey_id DROP NOT NULL,
  ALTER COLUMN status SET DEFAULT 'QUEUED',
  ADD COLUMN created_at  timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN updated_at  timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN result_code text        CHECK (result_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),   -- Ausgang bzw. Grund (z. B. PARTNER_RESULT_UNKNOWN), nie Freitext
  ADD COLUMN attempts    integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  ADD COLUMN lease_owner text        CHECK (lease_owner ~ '^[A-Za-z0-9._:-]{1,120}$'),
  ADD CONSTRAINT operation_final_without_lease CHECK (status NOT IN ('SUCCEEDED', 'FAILED') OR lease_until IS NULL),
  ADD CONSTRAINT operation_fencing_nonneg CHECK (fencing_token >= 0);

-- Teilindex offener Operationen (Kap. 04 §4.7): fällige Einträge und Leaseübernahme.
CREATE INDEX operation_open ON operation (tenant_id, next_attempt_at NULLS FIRST, lease_until) WHERE status IN ('QUEUED', 'RUNNING', 'RECONCILING');

CREATE FUNCTION operation_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  -- Endzustand: weitere Änderungen an der Zeile sind verboten; ein Statuswechsel scheitert bereits an den Übergangsregeln (enforce_state_transition).
  IF OLD.status IN ('SUCCEEDED', 'FAILED') AND NEW.status = OLD.status THEN
    RAISE EXCEPTION 'operation is final' USING ERRCODE = '23514';
  END IF;
  IF NEW.fencing_token < OLD.fencing_token THEN
    RAISE EXCEPTION 'fencing token must not decrease' USING ERRCODE = '23514';
  END IF;
  IF NEW.kind IS DISTINCT FROM OLD.kind OR NEW.journey_id IS DISTINCT FROM OLD.journey_id OR NEW.created_at IS DISTINCT FROM OLD.created_at
     OR NEW.deadline_at IS DISTINCT FROM OLD.deadline_at THEN
    RAISE EXCEPTION 'operation identity is immutable' USING ERRCODE = '23514';
  END IF;
  NEW.updated_at := clock_timestamp();
  RETURN NEW;
END $$;
CREATE TRIGGER operation_guard BEFORE UPDATE ON operation FOR EACH ROW EXECUTE FUNCTION operation_guard();
