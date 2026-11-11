-- WP-M2-16 / LH-6.5-OPT-004: Fahrtenplanmodell. Ein Umlauf je Schicht, Planrevisionen als append-only Stoppfolgen (`route_stop`), Zustandsautomat des Umlaufs.
-- 1. vehicle_run: genau ein Umlauf je Schicht (UNIQUE), Revision >= 1 und je Änderung genau +1 (CAS im Dienst, hier als Datenbanksicherung), Zustand gehärtet.
-- 2. route_stop: Stoppsequenz je (Umlauf, Revision) eindeutig; frühere Revisionen bleiben unverändert lesbar (kein UPDATE/DELETE).
-- Abschnittsindex: Abschnitt i liegt zwischen Stopp i und i+1 der Revision (0-basiert, nach `seq` sortiert); er gilt nur innerhalb einer Revision und wird auf
-- `capacity_hold.interval_from/interval_to` (halboffen) abgebildet. Holds tragen `run_revision`; eine neue Revision macht ältere Holds über den Revisionsvergleich ungültig.
SET search_path = taktovia, public;

ALTER TABLE vehicle_run ADD CONSTRAINT vehicle_run_one_per_shift UNIQUE (tenant_id, shift_id);
ALTER TABLE vehicle_run ADD CONSTRAINT vehicle_run_revision_positive CHECK (revision >= 1);

CREATE FUNCTION vehicle_run_revision_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.revision <> OLD.revision AND NEW.revision <> OLD.revision + 1 THEN
    RAISE EXCEPTION 'vehicle_run revision must increase by exactly one' USING ERRCODE = '23514';
  END IF;
  IF NEW.vehicle_id <> OLD.vehicle_id OR NEW.shift_id <> OLD.shift_id THEN
    RAISE EXCEPTION 'vehicle_run vehicle and shift are immutable' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER vehicle_run_revision_monotonic BEFORE UPDATE ON vehicle_run FOR EACH ROW EXECUTE FUNCTION vehicle_run_revision_guard();

-- Zustandsautomat nach docs/umsetzungsplan/vertraege/zustaende/fahrtenplan.md (Vorschlag, OP-4/OP-48); Kotlin-Seite: bookingdispatch.api.RunState.
INSERT INTO state_transition_rule(machine, version, from_state, to_state) VALUES
  ('vehicle_run',1,'PLANNED','ACTIVE'), ('vehicle_run',1,'PLANNED','CANCELED'),
  ('vehicle_run',1,'ACTIVE','LOCKED'), ('vehicle_run',1,'LOCKED','ACTIVE'),
  ('vehicle_run',1,'ACTIVE','IN_PROGRESS'), ('vehicle_run',1,'LOCKED','IN_PROGRESS'),
  ('vehicle_run',1,'IN_PROGRESS','COMPLETED'),
  ('vehicle_run',1,'ACTIVE','CANCELED'), ('vehicle_run',1,'LOCKED','CANCELED');
SELECT harden_state_column('taktovia.vehicle_run', 'state', 'vehicle_run');

CREATE TABLE route_stop (
  tenant_id         uuid        NOT NULL REFERENCES tenant(id),
  id                uuid        NOT NULL,
  run_id            uuid        NOT NULL,
  run_revision      bigint      NOT NULL CHECK (run_revision >= 1),
  seq               integer     NOT NULL CHECK (seq >= 0),
  stop_revision_id  uuid        NOT NULL,
  planned_at        timestamptz NOT NULL,
  -- je Eintrag {"booking_id": uuid, "vector": Ressourcenvektor (resource-vector.v1)}
  boardings         jsonb       NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(boardings) = 'array'),
  alightings        jsonb       NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(alightings) = 'array'),
  -- Fahrzeit und Distanz des Abschnitts zum Folgestopp; beim letzten Stopp beide leer
  leg_duration_sec  integer     CHECK (leg_duration_sec >= 0),
  leg_distance_m    integer     CHECK (leg_distance_m >= 0),
  created_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, run_id, run_revision, seq),
  CHECK ((leg_duration_sec IS NULL) = (leg_distance_m IS NULL)),
  FOREIGN KEY (tenant_id, run_id) REFERENCES vehicle_run(tenant_id, id),
  FOREIGN KEY (tenant_id, stop_revision_id) REFERENCES stop_revision(tenant_id, id)
);
ALTER TABLE route_stop ENABLE ROW LEVEL SECURITY;
ALTER TABLE route_stop FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON route_stop USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON route_stop FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE TRIGGER route_stop_immutable BEFORE UPDATE OR DELETE ON route_stop FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE INDEX route_stop_by_run ON route_stop (tenant_id, run_id, run_revision, seq);

-- Stopps gehören nur zu einer Revision, die der Umlauf bereits erreicht hat (die Revision wird vor den Stopps hochgezählt).
CREATE FUNCTION route_stop_revision_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE cur bigint;
BEGIN
  SELECT revision INTO cur FROM vehicle_run WHERE tenant_id = NEW.tenant_id AND id = NEW.run_id;
  IF NEW.run_revision > cur THEN
    RAISE EXCEPTION 'route_stop revision is ahead of vehicle_run' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER route_stop_revision_check BEFORE INSERT ON route_stop FOR EACH ROW EXECUTE FUNCTION route_stop_revision_guard();
