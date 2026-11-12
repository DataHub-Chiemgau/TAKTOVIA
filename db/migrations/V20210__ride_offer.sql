-- WP-M2-21 / LH-6.4-BKG-002: Angebotsobjekt mit Ablauf und Revisionsbindung (Entscheidung OP-19: eigene Tabelle `ride_offer`, `m2-detailvertraege.md` §6).
-- Ein Angebot bindet Angebots-ID, Ablauf (`expires_at`), Preisstand (`fare_quote`, unveränderlich), Abhol- und Zielhaltepunkt (Haltepunktrevision), Zeitfenster, Dienst und die
-- Planrevision, gegen die später gehalten wird. Es verspricht **keine** Kapazität: erst der Hold (`capacity_hold`) sperrt sie.
--  * Der Inhalt ist nach dem Anlegen unveränderlich; nur `state`, `hold_id`, `hold_scope`, `revision`, `updated_at` ändern sich (Trigger `ride_offer_guard`), jede Änderung zählt
--    die Revision genau um 1 hoch (starker ETag für `If-Match`), `hold_id` wird höchstens einmal gesetzt.
--  * Zustandsautomat `ride_offer` (OFFERED -> HELD | EXPIRED | DECLINED, HELD -> EXPIRED | DECLINED) als Übergangsregeln plus CHECK, auch gegen direktes SQL.
--  * `hold_scope` = SHA-256 aus Akteur und `Idempotency-Key` des erfolgreichen Holds: eine Wiederholung liefert den ursprünglichen Hold, auch nach `expires_at`.
SET search_path = taktovia, public;

INSERT INTO state_transition_rule(machine, version, from_state, to_state) VALUES
  ('ride_offer',1,'OFFERED','HELD'), ('ride_offer',1,'OFFERED','EXPIRED'), ('ride_offer',1,'OFFERED','DECLINED'),
  ('ride_offer',1,'HELD','EXPIRED'), ('ride_offer',1,'HELD','DECLINED');

CREATE TABLE ride_offer (
  tenant_id                uuid        NOT NULL REFERENCES tenant(id),
  id                       uuid        NOT NULL,
  attempt_id               uuid        NOT NULL,
  quote_id                 uuid        NOT NULL,
  service_id               uuid        NOT NULL,
  actor_ref                text        NOT NULL CHECK (length(actor_ref) BETWEEN 1 AND 200),
  state                    text        NOT NULL DEFAULT 'OFFERED',
  booking_kind             text        NOT NULL CHECK (booking_kind IN ('IMMEDIATE', 'ADVANCE')),
  time_kind                text        NOT NULL CHECK (time_kind IN ('DEPARTURE', 'ARRIVAL')),
  pickup_earliest          timestamptz NOT NULL,
  pickup_latest            timestamptz NOT NULL,
  arrival_earliest         timestamptz NOT NULL,
  arrival_latest           timestamptz NOT NULL,
  arrival_grace_min        integer     NOT NULL CHECK (arrival_grace_min BETWEEN 0 AND 240),
  direct_seconds           integer     NOT NULL CHECK (direct_seconds BETWEEN 1 AND 86400),
  expires_at               timestamptz NOT NULL,
  cancellation_policy_ref  text        NOT NULL CHECK (length(cancellation_policy_ref) BETWEEN 1 AND 64),
  demand                   jsonb       NOT NULL,
  pickup_stop_id           uuid,
  pickup_stop_revision_id  uuid,
  dropoff_stop_id          uuid,
  dropoff_stop_revision_id uuid,
  -- Bindung an die Planrevision (alle vier Angaben oder keine): gegen diese Revision und dieses Abschnittsintervall wird gehalten
  run_id                   uuid,
  plan_revision            bigint,
  interval_from            integer,
  interval_to              integer,
  hold_id                  uuid,
  hold_scope               char(64),
  revision                 bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_at               timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at               timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, quote_id),
  UNIQUE (tenant_id, attempt_id),
  FOREIGN KEY (tenant_id, attempt_id) REFERENCES travel_attempt (tenant_id, id),
  FOREIGN KEY (tenant_id, quote_id) REFERENCES fare_quote (tenant_id, id),
  FOREIGN KEY (tenant_id, service_id) REFERENCES service (tenant_id, id),
  FOREIGN KEY (tenant_id, run_id) REFERENCES vehicle_run (tenant_id, id),
  FOREIGN KEY (tenant_id, hold_id) REFERENCES capacity_hold (tenant_id, id),
  FOREIGN KEY (tenant_id, pickup_stop_revision_id) REFERENCES stop_revision (tenant_id, id),
  FOREIGN KEY (tenant_id, dropoff_stop_revision_id) REFERENCES stop_revision (tenant_id, id),
  CONSTRAINT ride_offer_windows CHECK (pickup_latest >= pickup_earliest AND arrival_latest >= arrival_earliest),
  CONSTRAINT ride_offer_expiry CHECK (expires_at > created_at),
  CONSTRAINT ride_offer_assignment CHECK (
    (run_id IS NULL AND plan_revision IS NULL AND interval_from IS NULL AND interval_to IS NULL)
    OR (run_id IS NOT NULL AND plan_revision >= 1 AND interval_from >= 0 AND interval_to > interval_from)),
  CONSTRAINT ride_offer_hold_pair CHECK ((hold_id IS NULL) = (hold_scope IS NULL) AND (hold_id IS NULL OR state <> 'OFFERED')),
  CONSTRAINT ride_offer_hold_scope_format CHECK (hold_scope IS NULL OR hold_scope ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ride_offer_demand_shape CHECK (
    jsonb_typeof(demand) = 'object'
    AND (demand - ARRAY['schema_version', 'seats', 'wheelchair', 'equipment']) = '{}'::jsonb
    AND jsonb_typeof(demand -> 'seats') = 'number' AND (demand ->> 'seats') ~ '^[0-9]{1,2}$'
    AND (NOT demand ? 'wheelchair' OR (jsonb_typeof(demand -> 'wheelchair') = 'number' AND (demand ->> 'wheelchair') ~ '^[0-9]{1,2}$'))
    AND (NOT demand ? 'equipment' OR jsonb_typeof(demand -> 'equipment') = 'array'))
);
ALTER TABLE ride_offer ENABLE ROW LEVEL SECURITY;
ALTER TABLE ride_offer FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ride_offer USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON ride_offer FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
SELECT harden_state_column('taktovia.ride_offer', 'state', 'ride_offer');

CREATE FUNCTION ride_offer_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF (NEW.id, NEW.attempt_id, NEW.quote_id, NEW.service_id, NEW.actor_ref, NEW.booking_kind, NEW.time_kind, NEW.pickup_earliest, NEW.pickup_latest, NEW.arrival_earliest, NEW.arrival_latest,
      NEW.arrival_grace_min, NEW.direct_seconds, NEW.expires_at, NEW.cancellation_policy_ref, NEW.demand, NEW.pickup_stop_id, NEW.pickup_stop_revision_id, NEW.dropoff_stop_id,
      NEW.dropoff_stop_revision_id, NEW.run_id, NEW.plan_revision, NEW.interval_from, NEW.interval_to, NEW.created_at)
     IS DISTINCT FROM
     (OLD.id, OLD.attempt_id, OLD.quote_id, OLD.service_id, OLD.actor_ref, OLD.booking_kind, OLD.time_kind, OLD.pickup_earliest, OLD.pickup_latest, OLD.arrival_earliest, OLD.arrival_latest,
      OLD.arrival_grace_min, OLD.direct_seconds, OLD.expires_at, OLD.cancellation_policy_ref, OLD.demand, OLD.pickup_stop_id, OLD.pickup_stop_revision_id, OLD.dropoff_stop_id,
      OLD.dropoff_stop_revision_id, OLD.run_id, OLD.plan_revision, OLD.interval_from, OLD.interval_to, OLD.created_at) THEN
    RAISE EXCEPTION 'ride_offer content is immutable' USING ERRCODE = '23514';
  END IF;
  IF OLD.hold_id IS NOT NULL AND (NEW.hold_id IS DISTINCT FROM OLD.hold_id OR NEW.hold_scope IS DISTINCT FROM OLD.hold_scope) THEN
    RAISE EXCEPTION 'ride_offer hold is set at most once' USING ERRCODE = '23514';
  END IF;
  IF NEW.revision <> OLD.revision + 1 THEN
    RAISE EXCEPTION 'ride_offer revision must increase by exactly one' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER ride_offer_guard BEFORE UPDATE ON ride_offer FOR EACH ROW EXECUTE FUNCTION ride_offer_guard();

CREATE INDEX ride_offer_by_hold ON ride_offer (tenant_id, hold_id) WHERE hold_id IS NOT NULL;
CREATE INDEX ride_offer_open ON ride_offer (tenant_id, expires_at) WHERE state IN ('OFFERED', 'HELD');
