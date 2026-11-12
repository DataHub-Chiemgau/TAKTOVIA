-- WP-M3-25 / LH-6.6-DRV-001: Dienstantritt und Dienstende des Fahrers an der Schichtzuweisung (Zustandsübergang geplant -> angetreten -> beendet).
-- Der Antritt ist ausdrücklich keine Arbeitszeiterfassung (M4, time_event) und keine Einsatzfreigabe (resource_release, WP-M3-09); er bestätigt nur die Zuordnung
-- Fahrer <-> Schicht <-> Fahrzeug. Gerät und Zeitpunkt stehen zusätzlich im audit_event. Zustandsautomat: vertraege/zustaende/staff_shift.md.
SET search_path = taktovia, public;

ALTER TABLE staff_shift
  ADD COLUMN started_at        timestamptz,
  ADD COLUMN started_device_id uuid,
  ADD COLUMN ended_at          timestamptz,
  ADD CONSTRAINT staff_shift_started_device_fk FOREIGN KEY (tenant_id, started_device_id) REFERENCES driver_device(tenant_id, id),
  ADD CONSTRAINT staff_shift_started_consistent CHECK ((started_at IS NULL) = (started_device_id IS NULL)),
  ADD CONSTRAINT staff_shift_ended_after_started CHECK (ended_at IS NULL OR (started_at IS NOT NULL AND ended_at >= started_at));

-- Zulässige Übergänge der M3-Zustände; ein Zustand außerhalb dieser vier (PUBLISHED des Dienstplans, M4) bleibt hier unberührt.
-- ENDED ist endgültig. CANCELLED -> PLANNED (Rücknahme eines Entzugs) ist zulässig, wird aber wie eine Neuanlage auf Überlappung geprüft (Trigger der Migration V3070).
CREATE FUNCTION staff_shift_state_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.state IS NOT DISTINCT FROM OLD.state OR OLD.state NOT IN ('PLANNED', 'STARTED', 'ENDED', 'CANCELLED') THEN RETURN NEW; END IF;
  IF NOT ((OLD.state = 'PLANNED' AND NEW.state IN ('STARTED', 'CANCELLED')) OR (OLD.state = 'STARTED' AND NEW.state IN ('ENDED', 'CANCELLED')) OR (OLD.state = 'CANCELLED' AND NEW.state = 'PLANNED')) THEN
    RAISE EXCEPTION 'staff shift state transition % -> % not allowed', OLD.state, NEW.state USING ERRCODE = '23514', CONSTRAINT = 'staff_shift_state_transition';
  END IF;
  IF NEW.state = 'STARTED' AND (NEW.started_at IS NULL OR NEW.started_device_id IS NULL) THEN
    RAISE EXCEPTION 'staff shift start needs time and device' USING ERRCODE = '23514', CONSTRAINT = 'staff_shift_started_consistent';
  END IF;
  IF NEW.state = 'ENDED' AND NEW.ended_at IS NULL THEN
    RAISE EXCEPTION 'staff shift end needs a time' USING ERRCODE = '23514', CONSTRAINT = 'staff_shift_ended_after_started';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER staff_shift_state_guard BEFORE UPDATE ON staff_shift FOR EACH ROW EXECUTE FUNCTION staff_shift_state_guard();
