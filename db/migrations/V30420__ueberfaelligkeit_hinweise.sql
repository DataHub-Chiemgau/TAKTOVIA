-- WP-M3-42 / LH-6.7-OPS-006: Überfälligkeit und ausstehende Informationen.
-- `operator_flag` ist der Hinweis der Leitstellenansichten: genau ein OFFENER Hinweis je Sachverhalt (Teilindex auf dem Dedupe-Schlüssel, auch gegen zwei gleichzeitige Auswertungsläufe), die
-- betroffenen Buchungen des berechtigten Dienstes als Kennungen (nie Namen, nie Kontaktdaten), die beim Auslösen wirksame Schwelle (ändert sich nie: eine spätere Schwellenänderung wirkt nicht
-- rückwirkend), Auslöse- und Erledigungszeit. Verspätung (`OVERDUE`) und ausstehende Information (`INFO_PENDING`) sind getrennte Arten mit festen Ursachen.
SET search_path = taktovia, public;

CREATE TABLE operator_flag (
  tenant_id         uuid        NOT NULL REFERENCES tenant(id),
  id                uuid        NOT NULL,
  service_id        uuid,
  vehicle_id        uuid,
  run_id            uuid,
  kind              text        NOT NULL CHECK (kind IN ('OVERDUE', 'INFO_PENDING')),
  cause             text        NOT NULL CHECK (cause IN ('PICKUP_OVERDUE', 'POSITION_STALE', 'PLAN_ACK_MISSING', 'REVIEW_REQUIRED_OPEN')),
  dedupe_key        text        NOT NULL CHECK (length(dedupe_key) BETWEEN 1 AND 200),
  state             text        NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN', 'RESOLVED')),
  affected_bookings uuid[]      NOT NULL DEFAULT '{}' CHECK (cardinality(affected_bookings) <= 500),
  affected_count    integer     NOT NULL DEFAULT 0 CHECK (affected_count >= 0),
  threshold_min     integer     CHECK (threshold_min IS NULL OR threshold_min BETWEEN 0 AND 100000),
  alert_id          uuid,
  opened_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
  last_evaluated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  resolved_at       timestamptz,
  revision          bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  PRIMARY KEY (tenant_id, id),
  -- eine Verspätung gibt es nur als überfällige Abholung, alles andere sind ausstehende Informationen
  CONSTRAINT operator_flag_kind_cause CHECK ((kind = 'OVERDUE') = (cause = 'PICKUP_OVERDUE')),
  CONSTRAINT operator_flag_count_matches CHECK (affected_count = cardinality(affected_bookings)),
  CONSTRAINT operator_flag_resolved_time CHECK ((state = 'RESOLVED') = (resolved_at IS NOT NULL))
);
-- genau ein offener Hinweis je Sachverhalt, auch bei gleichzeitigen Läufen
CREATE UNIQUE INDEX operator_flag_one_open ON operator_flag (tenant_id, dedupe_key) WHERE state = 'OPEN';
CREATE INDEX operator_flag_by_state ON operator_flag (tenant_id, state, opened_at, id);
CREATE INDEX operator_flag_by_vehicle ON operator_flag (tenant_id, vehicle_id) WHERE state = 'OPEN';
CREATE INDEX operator_flag_affected ON operator_flag USING gin (affected_bookings) WHERE state = 'OPEN';

ALTER TABLE operator_flag ENABLE ROW LEVEL SECURITY;
ALTER TABLE operator_flag FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON operator_flag USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON operator_flag FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();

-- Identität, Art, Ursache, Schwelle und Auslösezeit sind nach dem Anlegen unveränderlich (die Schwelle wirkt nicht rückwirkend); ein erledigter Hinweis bleibt erledigt und wird nie gelöscht.
CREATE FUNCTION operator_flag_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'operator_flag is never deleted' USING ERRCODE = '23514'; END IF;
  IF (NEW.id, NEW.service_id, NEW.vehicle_id, NEW.run_id, NEW.kind, NEW.cause, NEW.dedupe_key, NEW.threshold_min, NEW.opened_at)
     IS DISTINCT FROM (OLD.id, OLD.service_id, OLD.vehicle_id, OLD.run_id, OLD.kind, OLD.cause, OLD.dedupe_key, OLD.threshold_min, OLD.opened_at) THEN
    RAISE EXCEPTION 'operator_flag identity, cause and threshold are immutable' USING ERRCODE = '23514';
  END IF;
  IF OLD.state = 'RESOLVED' THEN RAISE EXCEPTION 'a resolved operator_flag is final' USING ERRCODE = '23514'; END IF;
  IF NEW.revision <> OLD.revision + 1 THEN RAISE EXCEPTION 'operator_flag.revision advances by exactly one' USING ERRCODE = '23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER operator_flag_guard BEFORE UPDATE OR DELETE ON operator_flag FOR EACH ROW EXECUTE FUNCTION operator_flag_guard();
