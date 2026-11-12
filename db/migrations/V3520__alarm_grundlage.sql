-- WP-M3-52: Alarm-Grundlage (Alarm, Dedupe, Quittierung, Eskalation) auf den Tabellen aus V3020 (`alert_policy_revision`, `business_alert`, `alert_acknowledgement`).
-- Additiv: neue Spalten nullbar oder mit Default. Dedupe über `(Regel, Quellereignis, Empfänger)` gilt in jedem Zustand (partieller Unique-Index), der bestehende Index
-- `business_alert_dedupe_open` hält zusätzlich bei bedingungsbasierten (technischen) Alarmen höchstens einen offenen Alarm je Schlüssel.
SET search_path = taktovia, public;

ALTER TABLE business_alert
  ADD COLUMN service_id       uuid,
  ADD COLUMN source_event_id  uuid,
  ADD COLUMN recipient_role   text        NOT NULL DEFAULT 'dispatcher' CHECK (recipient_role ~ '^[a-z][a-z_]{1,39}$'),
  ADD COLUMN escalation_level int         NOT NULL DEFAULT 0 CHECK (escalation_level BETWEEN 0 AND 3),
  ADD COLUMN escalate_at      timestamptz,
  ADD COLUMN escalated_at     timestamptz,
  ADD COLUMN resolved_at      timestamptz,
  ADD COLUMN reason_code      text        CHECK (reason_code IS NULL OR reason_code ~ '^[A-Z][A-Z0-9_]{1,59}$'),
  ADD COLUMN ref_type         text        CHECK (ref_type IS NULL OR ref_type ~ '^[a-z][a-z_]{1,39}$'),
  ADD COLUMN ref_id           uuid;

ALTER TABLE business_alert DROP CONSTRAINT business_alert_state_check;
ALTER TABLE business_alert ADD CONSTRAINT business_alert_state_check CHECK (state IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED'));
ALTER TABLE business_alert ADD CONSTRAINT business_alert_resolved_consistent CHECK ((state = 'RESOLVED') = (resolved_at IS NOT NULL));
ALTER TABLE business_alert ADD CONSTRAINT business_alert_ref_consistent CHECK ((ref_type IS NULL) = (ref_id IS NULL));

-- dasselbe Quellereignis erzeugt je Regel und Empfänger genau einen Alarm, auch nach Quittierung und bei Wiederzustellung
CREATE UNIQUE INDEX business_alert_source_dedupe ON business_alert (tenant_id, alert_kind, source_event_id, recipient_role) WHERE source_event_id IS NOT NULL;
CREATE INDEX business_alert_escalation_due ON business_alert (tenant_id, escalate_at) WHERE state = 'OPEN' AND escalate_at IS NOT NULL;
CREATE INDEX business_alert_by_state ON business_alert (tenant_id, state, created_at, id);

ALTER TABLE alert_acknowledgement
  ADD COLUMN cause_code text CHECK (cause_code IS NULL OR cause_code IN ('REPAIRED', 'HANDLED', 'NOT_RELEVANT', 'FALSE_ALARM', 'ESCALATED_ELSEWHERE', 'OTHER'));

-- Zustandsautomat und Unveränderlichkeit (zweite Schicht neben dem Dienst): OPEN -> ACKNOWLEDGED | RESOLVED, ACKNOWLEDGED -> RESOLVED; RESOLVED ist endgültig.
-- Inhalt (Art, Priorität, Schlüssel, Regelrevision, Quelle, Empfänger, Dienst, Bezug, Urheber, Anlagezeit) ändert sich nie; die Eskalationsstufe steigt nur.
CREATE FUNCTION business_alert_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.alert_kind IS DISTINCT FROM OLD.alert_kind OR NEW.priority IS DISTINCT FROM OLD.priority OR NEW.dedupe_key IS DISTINCT FROM OLD.dedupe_key
     OR NEW.policy_revision_id IS DISTINCT FROM OLD.policy_revision_id OR NEW.source_event_id IS DISTINCT FROM OLD.source_event_id OR NEW.recipient_role IS DISTINCT FROM OLD.recipient_role
     OR NEW.service_id IS DISTINCT FROM OLD.service_id OR NEW.vehicle_id IS DISTINCT FROM OLD.vehicle_id OR NEW.ref_type IS DISTINCT FROM OLD.ref_type OR NEW.ref_id IS DISTINCT FROM OLD.ref_id
     OR NEW.reason_code IS DISTINCT FROM OLD.reason_code OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'business_alert content is immutable' USING ERRCODE = '23514';
  END IF;
  IF NEW.state IS DISTINCT FROM OLD.state AND NOT ((OLD.state = 'OPEN' AND NEW.state IN ('ACKNOWLEDGED', 'RESOLVED')) OR (OLD.state = 'ACKNOWLEDGED' AND NEW.state = 'RESOLVED')) THEN
    RAISE EXCEPTION 'business_alert transition % -> % is not allowed', OLD.state, NEW.state USING ERRCODE = '23514';
  END IF;
  IF NEW.escalation_level < OLD.escalation_level THEN RAISE EXCEPTION 'business_alert escalation level never decreases' USING ERRCODE = '23514'; END IF;
  IF NEW.revision IS DISTINCT FROM OLD.revision AND NEW.revision <> OLD.revision + 1 THEN RAISE EXCEPTION 'business_alert revision must increase by one' USING ERRCODE = '23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER business_alert_guard BEFORE UPDATE ON business_alert FOR EACH ROW EXECUTE FUNCTION business_alert_guard();
