-- WP-M3-24: Driver-App-Basis. Geräte-/Sitzungsbindung (driver_device) und dauerhafter Eingang der Fahrerereignisse mit Deduplikation (driver_event).
-- Entwurf: docs/umsetzungsplan/vertraege/m3-ddl-entwurf.sql (WP-M3-01); die übrigen M3-Tabellen folgen mit WP-M3-02.
SET search_path = taktovia, public;

-- Ein Gerät gehört genau einem Konto (Issuer+Subject). Die Sitzungsbindung (IdP-Sitzungs-ID) wechselt bei erneuter Anmeldung desselben Kontos (Revision+1);
-- ein anderes Konto kann dasselbe Gerät nie übernehmen. Die Beendigung der Bindung ist endgültig.
CREATE TABLE driver_device (
  tenant_id       uuid        NOT NULL REFERENCES tenant(id),
  id              uuid        NOT NULL,
  issuer          text        NOT NULL CHECK (length(issuer) BETWEEN 1 AND 512),
  subject         text        NOT NULL CHECK (length(subject) BETWEEN 1 AND 200),
  idp_session_id  text        NOT NULL CHECK (length(idp_session_id) BETWEEN 8 AND 128),
  platform        text        NOT NULL CHECK (platform IN ('ANDROID', 'IOS')),
  app_version     text        NOT NULL CHECK (length(app_version) BETWEEN 1 AND 32),
  push_token_ref  text        CHECK (push_token_ref IS NULL OR length(push_token_ref) BETWEEN 1 AND 256),
  registered_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
  revoked_at      timestamptz,
  revision        bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  PRIMARY KEY (tenant_id, id)
);
CREATE INDEX driver_device_by_subject ON driver_device (tenant_id, issuer, subject);

-- Eingang der Fahrerereignisse (unveränderlich): ein Ereignis wirkt höchstens einmal (PK = Dedupe), die Serverempfangszeit steht getrennt von der Gerätezeit.
CREATE TABLE driver_event (
  tenant_id          uuid        NOT NULL REFERENCES tenant(id),
  event_id           uuid        NOT NULL,
  device_id          uuid        NOT NULL,
  shift_id           uuid        NOT NULL,
  vehicle_id         uuid        NOT NULL,
  event_type         text        NOT NULL CHECK (event_type IN ('CHECK_IN', 'CHECK_OUT', 'NO_SHOW', 'ARRIVAL', 'BREAK_START', 'BREAK_END', 'DISRUPTION', 'SHIFT_END')),
  booking_id         uuid,
  route_stop_id      uuid,
  payload_sha256     text        NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
  outcome            text        NOT NULL CHECK (outcome IN ('APPLIED', 'REVIEW_REQUIRED')),
  reason_code        text        CHECK (reason_code IS NULL OR reason_code IN ('STALE_PLAN', 'OUTSIDE_STOP', 'CLOCK_SKEW', 'INVALID_ORDER', 'RELEASE_REQUIRED', 'SHIFT_ENDED')),
  plan_revision      bigint      NOT NULL CHECK (plan_revision >= 1),
  device_recorded_at timestamptz NOT NULL,
  occurred_at        timestamptz NOT NULL,
  received_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, event_id),
  FOREIGN KEY (tenant_id, device_id) REFERENCES driver_device(tenant_id, id),
  FOREIGN KEY (tenant_id, shift_id) REFERENCES staff_shift(tenant_id, id),
  FOREIGN KEY (tenant_id, vehicle_id) REFERENCES vehicle(tenant_id, id),
  CONSTRAINT driver_event_review_has_reason CHECK ((outcome = 'REVIEW_REQUIRED') = (reason_code IS NOT NULL))
);
CREATE INDEX driver_event_by_shift ON driver_event (tenant_id, shift_id, received_at);

DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['driver_device', 'driver_event'] LOOP
    EXECUTE format('ALTER TABLE taktovia.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE taktovia.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON taktovia.%I USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant())', t);
    EXECUTE format('CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON taktovia.%I FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change()', t);
  END LOOP;
END $$;

CREATE TRIGGER driver_event_immutable BEFORE UPDATE OR DELETE ON driver_event FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- Gerät: Identität unveränderlich, Beendigung endgültig; die Revision zählt jede Änderung (Rebind, Beendigung).
CREATE FUNCTION driver_device_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.id IS DISTINCT FROM OLD.id OR NEW.issuer IS DISTINCT FROM OLD.issuer OR NEW.subject IS DISTINCT FROM OLD.subject
     OR NEW.platform IS DISTINCT FROM OLD.platform OR NEW.registered_at IS DISTINCT FROM OLD.registered_at THEN
    RAISE EXCEPTION 'driver device identity is immutable' USING ERRCODE = '23514';
  END IF;
  IF OLD.revoked_at IS NOT NULL AND NEW IS DISTINCT FROM OLD THEN
    RAISE EXCEPTION 'driver device revocation is final' USING ERRCODE = '23514';
  END IF;
  IF NEW IS DISTINCT FROM OLD THEN NEW.revision := OLD.revision + 1; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER driver_device_guard BEFORE UPDATE ON driver_device FOR EACH ROW EXECUTE FUNCTION driver_device_guard();
