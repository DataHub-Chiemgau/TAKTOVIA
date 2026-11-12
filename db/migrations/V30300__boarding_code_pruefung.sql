-- WP-M3-30 / LH-6.6-DRV-006: Prüfung des Boarding-Codes beim Check-in.
-- 1. boarding_code: Sperre nach Fehlversuchsgrenze (locked_at, endgültig) und das entwertende Fahrerereignis (used_event_id, zusammen mit used_at); der Code selbst steht nie in der Datenbank.
-- 2. driver_event: Ergebnis der Codeprüfung (code_result) und die Klärgründe CODE_INVALID, CODE_ALREADY_USED, CODE_LOCKED (offline erfasste Codes gelten erst nach der Serverprüfung).
SET search_path = taktovia, public;

ALTER TABLE boarding_code
  ADD COLUMN locked_at     timestamptz,
  ADD COLUMN used_event_id uuid,
  ADD CONSTRAINT boarding_code_use_pair CHECK ((used_at IS NULL) = (used_event_id IS NULL));

CREATE OR REPLACE FUNCTION boarding_code_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'boarding code is append-only' USING ERRCODE = '23514'; END IF;
  IF NEW.code_hash IS DISTINCT FROM OLD.code_hash OR NEW.booking_id IS DISTINCT FROM OLD.booking_id OR NEW.valid_until IS DISTINCT FROM OLD.valid_until
     OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.failed_attempts < OLD.failed_attempts
     OR (OLD.used_at IS NOT NULL AND (NEW.used_at IS DISTINCT FROM OLD.used_at OR NEW.used_event_id IS DISTINCT FROM OLD.used_event_id))
     OR (OLD.locked_at IS NOT NULL AND NEW.locked_at IS DISTINCT FROM OLD.locked_at) THEN
    RAISE EXCEPTION 'boarding code is immutable except attempt counter, lock and single use' USING ERRCODE = '23514';
  END IF;
  IF NEW IS DISTINCT FROM OLD THEN NEW.revision := OLD.revision + 1; END IF;
  RETURN NEW;
END $$;

ALTER TABLE driver_event
  ADD COLUMN code_result text CHECK (code_result IS NULL OR code_result IN ('VALID', 'INVALID', 'USED', 'LOCKED'));
ALTER TABLE driver_event DROP CONSTRAINT driver_event_reason_code_check;
ALTER TABLE driver_event ADD CONSTRAINT driver_event_reason_code_check
  CHECK (reason_code IS NULL OR reason_code IN ('STALE_PLAN', 'OUTSIDE_STOP', 'CLOCK_SKEW', 'INVALID_ORDER', 'RELEASE_REQUIRED', 'SHIFT_ENDED', 'BOOKING_CANCELED',
                                                'CODE_INVALID', 'CODE_ALREADY_USED', 'CODE_LOCKED'));
