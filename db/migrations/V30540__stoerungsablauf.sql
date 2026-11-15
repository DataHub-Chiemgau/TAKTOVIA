-- WP-M3-54 / LH-6.22-DMG-010: Störungsablauf. Je Incident (Fahrzeugausfall) werden die betroffenen Buchungen des gesperrten Fahrzeugs geführt (Zustand je Buchung), dazu die Ersatzvorschläge je Buchung.
-- Eigentum des Moduls booking-dispatch (die Buchungen und Pläne gehören ihm); Incident, Ablaufprotokoll und Entscheidungsprotokoll bleiben beim Modul fleet-safety.
-- 1. outage_booking: genau eine Zeile je (Incident, Buchung); Zustandsautomat laut docs/umsetzungsplan/vertraege/zustaende/stoerungsfall.md per Trigger (auch bei direktem SQL). Revision +1 je Zustandsänderung
--    (Plan-CAS der Leitstellenentscheidung: der zweite Disponent mit veralteter Revision wird abgewiesen). Entscheidung (Akteur, Code, Zeit) steht an der Zeile und zusätzlich im Ablaufprotokoll des Incidents.
-- 2. outage_proposal: Ersatzvorschläge je Buchung, unveränderlich (append-only); eine neue Prüfung legt eine neue Generation an, die höchste gilt.
-- Keine Fahrgast-Klartextdaten: nur Kennungen, Codes und Zeiten.
SET search_path = taktovia, public;

CREATE TABLE outage_booking (
  tenant_id           uuid        NOT NULL REFERENCES tenant(id),
  id                  uuid        NOT NULL,
  incident_id         uuid        NOT NULL,
  booking_id          uuid        NOT NULL,
  run_id              uuid        NOT NULL,
  vehicle_id          uuid        NOT NULL,
  state               text        NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN', 'PROPOSED', 'REBOOKED', 'CANCELLED', 'OPEN_ISSUE', 'BOARDED', 'ONBOARD_DONE')),
  issue_code          text        CHECK (issue_code IS NULL OR issue_code ~ '^[A-Z][A-Z0-9_]{1,59}$'),
  decision            text        CHECK (decision IS NULL OR decision IN ('REBOOK', 'CANCEL')),
  decision_reason     text        CHECK (decision_reason IS NULL OR decision_reason ~ '^[A-Z][A-Z0-9_]{1,59}$'),
  decided_by          text        CHECK (decided_by IS NULL OR decided_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  decided_at          timestamptz,
  replacement_run_id  uuid,
  notice_revision     bigint      CHECK (notice_revision IS NULL OR notice_revision >= 1),
  revision            bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  CONSTRAINT outage_booking_unique UNIQUE (tenant_id, incident_id, booking_id),
  FOREIGN KEY (tenant_id, incident_id)        REFERENCES incident(tenant_id, id),
  FOREIGN KEY (tenant_id, booking_id)         REFERENCES booking(tenant_id, id),
  FOREIGN KEY (tenant_id, run_id)             REFERENCES vehicle_run(tenant_id, id),
  FOREIGN KEY (tenant_id, vehicle_id)         REFERENCES vehicle(tenant_id, id),
  FOREIGN KEY (tenant_id, replacement_run_id) REFERENCES vehicle_run(tenant_id, id),
  -- eine Entscheidung gehört immer vollständig zusammen (Akteur, Code, Zeit) und nur zu einem Entscheidungszustand
  CONSTRAINT outage_booking_decision_complete CHECK ((decision IS NULL AND decided_by IS NULL AND decided_at IS NULL AND decision_reason IS NULL) OR (decision IS NOT NULL AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND decision_reason IS NOT NULL)),
  CONSTRAINT outage_booking_decision_state CHECK ((state IN ('REBOOKED', 'CANCELLED')) = (decision IS NOT NULL)),
  CONSTRAINT outage_booking_replacement CHECK ((state = 'REBOOKED') = (replacement_run_id IS NOT NULL)),
  CONSTRAINT outage_booking_issue CHECK ((state = 'OPEN_ISSUE') = (issue_code IS NOT NULL))
);
-- Eine Buchung hat höchstens einen offenen Störungsfall zugleich (mehrere Incidents derselben Buchung wären widersprüchlich): nicht abgeschlossene Zeilen sind je Buchung eindeutig.
CREATE UNIQUE INDEX outage_booking_one_open_per_booking ON outage_booking (tenant_id, booking_id) WHERE state IN ('OPEN', 'PROPOSED', 'OPEN_ISSUE', 'BOARDED');
CREATE INDEX outage_booking_by_incident ON outage_booking (tenant_id, incident_id, state);

CREATE FUNCTION outage_booking_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'outage_booking is append-only' USING ERRCODE = '23514'; END IF;
  IF (NEW.incident_id, NEW.booking_id, NEW.run_id, NEW.vehicle_id, NEW.created_at) IS DISTINCT FROM (OLD.incident_id, OLD.booking_id, OLD.run_id, OLD.vehicle_id, OLD.created_at) THEN
    RAISE EXCEPTION 'outage_booking identity is immutable' USING ERRCODE = '23514';
  END IF;
  IF NEW.state IS DISTINCT FROM OLD.state THEN
    IF NOT (
         (OLD.state = 'OPEN'       AND NEW.state IN ('PROPOSED', 'OPEN_ISSUE', 'BOARDED', 'REBOOKED', 'CANCELLED'))
      OR (OLD.state = 'PROPOSED'   AND NEW.state IN ('OPEN', 'OPEN_ISSUE', 'REBOOKED', 'CANCELLED'))
      OR (OLD.state = 'OPEN_ISSUE' AND NEW.state IN ('OPEN', 'PROPOSED', 'REBOOKED', 'CANCELLED'))
      OR (OLD.state = 'BOARDED'    AND NEW.state = 'ONBOARD_DONE')
      ) THEN
      RAISE EXCEPTION 'outage_booking transition % -> % is not allowed', OLD.state, NEW.state USING ERRCODE = '23514';
    END IF;
    IF NEW.revision <> OLD.revision + 1 THEN
      RAISE EXCEPTION 'outage_booking revision must increase by one with a state change' USING ERRCODE = '23514';
    END IF;
  ELSIF NEW.revision <> OLD.revision AND NEW.revision <> OLD.revision + 1 THEN
    RAISE EXCEPTION 'outage_booking revision advances by exactly one' USING ERRCODE = '23514';
  END IF;
  -- die Entscheidung ist nach dem Setzen unveränderlich (Akteur, Code, Zeit)
  IF OLD.decision IS NOT NULL AND (NEW.decision, NEW.decision_reason, NEW.decided_by, NEW.decided_at) IS DISTINCT FROM (OLD.decision, OLD.decision_reason, OLD.decided_by, OLD.decided_at) THEN
    RAISE EXCEPTION 'outage_booking decision is immutable' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER outage_booking_guard BEFORE UPDATE OR DELETE ON outage_booking FOR EACH ROW EXECUTE FUNCTION outage_booking_guard();

CREATE TABLE outage_proposal (
  tenant_id          uuid        NOT NULL REFERENCES tenant(id),
  id                 uuid        NOT NULL,
  outage_booking_id  uuid        NOT NULL,
  generation         int         NOT NULL CHECK (generation >= 1),
  vehicle_id         uuid        NOT NULL,
  run_id             uuid        NOT NULL,
  run_revision       bigint      NOT NULL CHECK (run_revision >= 1),
  pickup_at          timestamptz NOT NULL,
  dropoff_at         timestamptz NOT NULL,
  window_effect      text        NOT NULL CHECK (window_effect IN ('IN_WINDOW', 'OUTSIDE_WINDOW')),
  window_violations  text        CHECK (window_violations IS NULL OR window_violations ~ '^[A-Z][A-Z0-9_,]{1,200}$'),
  created_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, outage_booking_id) REFERENCES outage_booking(tenant_id, id),
  FOREIGN KEY (tenant_id, vehicle_id)        REFERENCES vehicle(tenant_id, id),
  FOREIGN KEY (tenant_id, run_id)            REFERENCES vehicle_run(tenant_id, id),
  CONSTRAINT outage_proposal_unique UNIQUE (tenant_id, outage_booking_id, generation, run_id),
  CONSTRAINT outage_proposal_window CHECK ((window_effect = 'OUTSIDE_WINDOW') = (window_violations IS NOT NULL)),
  CONSTRAINT outage_proposal_times CHECK (dropoff_at >= pickup_at)
);
CREATE INDEX outage_proposal_by_booking ON outage_proposal (tenant_id, outage_booking_id, generation);
CREATE TRIGGER outage_proposal_append_only BEFORE UPDATE OR DELETE ON outage_proposal FOR EACH ROW EXECUTE FUNCTION reject_mutation();

DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['outage_booking', 'outage_proposal'] LOOP
    EXECUTE format('ALTER TABLE taktovia.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE taktovia.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON taktovia.%I USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant())', t);
    EXECUTE format('CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON taktovia.%I FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change()', t);
  END LOOP;
END $$;
