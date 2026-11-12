-- WP-M4-06: Abwesenheit als Verfügbarkeitsrestriktion (Minimalmodell, Modul staff-time). Nur genehmigte Abwesenheiten (kein Antragsworkflow, TIM-008 ist M7).
-- Neutrale Art ohne Diagnose-, Kommentar- oder Nachweisfeld (Kap. 04 §4.6, Kap. 06 §6.8); Krankheitsabwesenheit ist TIM-010 (M7) und hier nicht abbildbar.
SET search_path = taktovia, public;

CREATE TABLE absence_request (
  tenant_id   uuid        NOT NULL REFERENCES tenant(id),
  id          uuid        NOT NULL,
  person_ref  text        NOT NULL CHECK (length(person_ref) BETWEEN 1 AND 200),   -- IdP-Subject der Person (wie staff_shift.driver_ref)
  starts_at   timestamptz NOT NULL,
  ends_at     timestamptz NOT NULL,                                                -- halboffen [starts_at, ends_at)
  kind        text        NOT NULL CHECK (kind IN ('LEAVE', 'TRAINING', 'OTHER')),
  state       text        NOT NULL DEFAULT 'APPROVED' CHECK (state IN ('APPROVED', 'WITHDRAWN')),
  created_by  text        NOT NULL CHECK (length(created_by) BETWEEN 1 AND 200),
  created_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  revision    bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  PRIMARY KEY (tenant_id, id),
  CONSTRAINT absence_request_period CHECK (ends_at > starts_at)
);
CREATE INDEX absence_request_by_person ON absence_request (tenant_id, person_ref, starts_at);
CREATE INDEX absence_request_by_period ON absence_request (tenant_id, starts_at, ends_at) WHERE state = 'APPROVED';

ALTER TABLE absence_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE absence_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON absence_request USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON absence_request FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change();

-- Einzige zulässige Änderung ist die Rücknahme (APPROVED -> WITHDRAWN, Revision +1); sie ist endgültig, alle Sachfelder sind unveränderlich.
CREATE FUNCTION absence_request_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.id IS DISTINCT FROM OLD.id OR NEW.person_ref IS DISTINCT FROM OLD.person_ref OR NEW.starts_at IS DISTINCT FROM OLD.starts_at
     OR NEW.ends_at IS DISTINCT FROM OLD.ends_at OR NEW.kind IS DISTINCT FROM OLD.kind OR NEW.created_by IS DISTINCT FROM OLD.created_by
     OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'absence_request: Sachfelder sind unveraenderlich, nur die Ruecknahme ist zulaessig' USING ERRCODE = '23514';
  END IF;
  IF OLD.state = 'WITHDRAWN' AND NEW IS DISTINCT FROM OLD THEN
    RAISE EXCEPTION 'absence_request: Ruecknahme ist endgueltig' USING ERRCODE = '23514';
  END IF;
  IF NEW.state IS DISTINCT FROM OLD.state THEN NEW.revision := OLD.revision + 1; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER absence_request_guard BEFORE UPDATE ON absence_request FOR EACH ROW EXECUTE FUNCTION absence_request_guard();
