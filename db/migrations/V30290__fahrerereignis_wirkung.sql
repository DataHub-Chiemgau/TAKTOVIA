-- WP-M3-29 / LH-6.6-DRV-005: Wirkung der Fahrerereignisse (Ankunft, Einstieg, Ausstieg, Nicht-Erscheinen) auf Fahrt (journey_leg.execution_state) und Buchung.
-- 1. driver_event: plausibilisierte Zeit (effective_at), Offline-Kennzeichen, No-Show-Angaben (Grund, Wartezeit, Regelstand) und der Klärgrund BOOKING_CANCELED (Widerspruch zu einem Storno).
--    Ereignis-, Geräte- und Serverempfangszeit bleiben getrennte Spalten (occurred_at, device_recorded_at, received_at).
-- 2. journey_leg.execution_state: Wertemenge und Übergangswächter (PLANNED -> BOARDED | NO_SHOW, BOARDED -> ALIGHTED; ALIGHTED und NO_SHOW endgültig), auch bei direktem SQL.
SET search_path = taktovia, public;

ALTER TABLE driver_event
  ADD COLUMN effective_at         timestamptz,
  ADD COLUMN offline_recorded     boolean NOT NULL DEFAULT false,
  ADD COLUMN no_show_reason       text    CHECK (no_show_reason IS NULL OR no_show_reason IN ('NOT_PRESENT', 'REFUSED_TO_BOARD', 'OTHER')),
  ADD COLUMN no_show_wait_seconds integer CHECK (no_show_wait_seconds IS NULL OR no_show_wait_seconds >= 0),
  ADD COLUMN no_show_grace_min    integer CHECK (no_show_grace_min IS NULL OR no_show_grace_min >= 1);

ALTER TABLE driver_event DROP CONSTRAINT driver_event_reason_code_check;
ALTER TABLE driver_event ADD CONSTRAINT driver_event_reason_code_check
  CHECK (reason_code IS NULL OR reason_code IN ('STALE_PLAN', 'OUTSIDE_STOP', 'CLOCK_SKEW', 'INVALID_ORDER', 'RELEASE_REQUIRED', 'SHIFT_ENDED', 'BOOKING_CANCELED'));
-- Der No-Show-Satz (Grund, gemessene Wartezeit, Regelstand) gehört zusammen und nur zu einem wirksamen No-Show.
ALTER TABLE driver_event ADD CONSTRAINT driver_event_no_show_fields
  CHECK ((no_show_reason IS NULL AND no_show_wait_seconds IS NULL AND no_show_grace_min IS NULL)
      OR (event_type = 'NO_SHOW' AND outcome = 'APPLIED' AND no_show_reason IS NOT NULL AND no_show_wait_seconds IS NOT NULL AND no_show_grace_min IS NOT NULL));
CREATE INDEX driver_event_by_stop ON driver_event (tenant_id, shift_id, route_stop_id, event_type, received_at) WHERE route_stop_id IS NOT NULL;
CREATE INDEX driver_event_by_booking ON driver_event (tenant_id, booking_id, received_at) WHERE booking_id IS NOT NULL;

ALTER TABLE journey_leg ADD CONSTRAINT journey_leg_execution_state_check CHECK (execution_state IN ('PLANNED', 'BOARDED', 'ALIGHTED', 'NO_SHOW'));

CREATE FUNCTION journey_leg_execution_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.execution_state IS DISTINCT FROM OLD.execution_state AND NOT (
       (OLD.execution_state = 'PLANNED' AND NEW.execution_state IN ('BOARDED', 'NO_SHOW'))
    OR (OLD.execution_state = 'BOARDED' AND NEW.execution_state = 'ALIGHTED')) THEN
    RAISE EXCEPTION 'journey_leg.execution_state: Uebergang % -> % nicht zulaessig', OLD.execution_state, NEW.execution_state USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER journey_leg_execution_guard BEFORE UPDATE OF execution_state ON journey_leg FOR EACH ROW EXECUTE FUNCTION journey_leg_execution_guard();
