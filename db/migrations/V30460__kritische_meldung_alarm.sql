-- WP-M3-46 / LH-6.22-DMG-006: Sicherheitskritische Meldung, Sofortsperre und priorisierter Alarm. Der Alarm nennt Fahrzeug, Fallnummer und die Zahl der betroffenen Fahrten (AT-28) und trägt
-- keine Fahrgastdaten und keine Diagnosen: zwei neue, unveränderliche Sachfelder am Alarm (`case_number` = Fallnummer der Schadenakte, `affected_run_count` = Zahl der offenen Fahrten des
-- Fahrzeugs zum Zeitpunkt der Sperre). Index für die Zählung der betroffenen Umläufe je Fahrzeug.
SET search_path = taktovia, public;

ALTER TABLE business_alert
  ADD COLUMN case_number        text CHECK (case_number IS NULL OR length(case_number) BETWEEN 1 AND 40),
  ADD COLUMN affected_run_count int  CHECK (affected_run_count IS NULL OR affected_run_count >= 0);

CREATE OR REPLACE FUNCTION business_alert_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.alert_kind IS DISTINCT FROM OLD.alert_kind OR NEW.priority IS DISTINCT FROM OLD.priority OR NEW.dedupe_key IS DISTINCT FROM OLD.dedupe_key
     OR NEW.policy_revision_id IS DISTINCT FROM OLD.policy_revision_id OR NEW.source_event_id IS DISTINCT FROM OLD.source_event_id OR NEW.recipient_role IS DISTINCT FROM OLD.recipient_role
     OR NEW.service_id IS DISTINCT FROM OLD.service_id OR NEW.vehicle_id IS DISTINCT FROM OLD.vehicle_id OR NEW.ref_type IS DISTINCT FROM OLD.ref_type OR NEW.ref_id IS DISTINCT FROM OLD.ref_id
     OR NEW.reason_code IS DISTINCT FROM OLD.reason_code OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at
     OR NEW.case_number IS DISTINCT FROM OLD.case_number OR NEW.affected_run_count IS DISTINCT FROM OLD.affected_run_count THEN
    RAISE EXCEPTION 'business_alert content is immutable' USING ERRCODE = '23514';
  END IF;
  IF NEW.state IS DISTINCT FROM OLD.state AND NOT ((OLD.state = 'OPEN' AND NEW.state IN ('ACKNOWLEDGED', 'RESOLVED')) OR (OLD.state = 'ACKNOWLEDGED' AND NEW.state = 'RESOLVED')) THEN
    RAISE EXCEPTION 'business_alert transition % -> % is not allowed', OLD.state, NEW.state USING ERRCODE = '23514';
  END IF;
  IF NEW.escalation_level < OLD.escalation_level THEN RAISE EXCEPTION 'business_alert escalation level never decreases' USING ERRCODE = '23514'; END IF;
  IF NEW.revision IS DISTINCT FROM OLD.revision AND NEW.revision <> OLD.revision + 1 THEN RAISE EXCEPTION 'business_alert revision must increase by one' USING ERRCODE = '23514'; END IF;
  RETURN NEW;
END $$;

CREATE INDEX IF NOT EXISTS vehicle_run_by_vehicle_state ON vehicle_run (tenant_id, vehicle_id, state);
