-- WP-M4-45: Analytics-Faktenbasis und Datenstand (Modul analytics, Enabler).
-- Eingang: analytics_event_inbox (je Mandant und Ereignis genau eine Zeile = Inbox-Deduplikation, nur auf Fachfelder reduzierte Nutzlast, unveränderlich).
-- Fakten: aus der Menge der Eingangszeilen abgeleitet (unabhängig von Reihenfolge, Wiederholung und Verspätung) und jederzeit daraus neu aufbaubar.
-- dataset_watermark: Quelle, verarbeitet bis, Vollständigkeit, Stand. Keine Fahrgast-Klartextdaten; Start und Ziel nur als vergröberte Werte der Versuchsereignisse.
SET search_path = taktovia, public;

CREATE TABLE analytics_event_inbox (
  tenant_id          uuid        NOT NULL REFERENCES tenant(id),
  event_id           uuid        NOT NULL,
  source             text        NOT NULL CHECK (source IN ('ATTEMPT', 'OFFER', 'BOOKING', 'DRIVER_EVENT', 'VEHICLE_STATE')),
  event_type         text        NOT NULL CHECK (event_type ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'),
  aggregate_id       uuid        NOT NULL,
  aggregate_revision bigint      NOT NULL CHECK (aggregate_revision >= 1),
  occurred_at        timestamptz NOT NULL,
  recorded_at        timestamptz NOT NULL,
  payload_sha256     char(64)    NOT NULL,
  payload            jsonb       NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
  received_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, event_id)
);
CREATE INDEX analytics_event_inbox_by_aggregate ON analytics_event_inbox (tenant_id, source, aggregate_id, aggregate_revision);
CREATE INDEX analytics_event_inbox_by_booking ON analytics_event_inbox (tenant_id, (payload->>'booking_id')) WHERE payload ? 'booking_id';

CREATE TABLE fact_attempt (
  tenant_id uuid NOT NULL REFERENCES tenant(id), attempt_id uuid NOT NULL, service_id uuid NOT NULL,
  outcome text NOT NULL, primary_reason text, time_mode text NOT NULL, requested_at timestamptz NOT NULL, passengers integer NOT NULL, wheelchair integer,
  coarse_origin text, coarse_destination text, channel text,
  PRIMARY KEY (tenant_id, attempt_id)
);
CREATE TABLE fact_offer (
  tenant_id uuid NOT NULL REFERENCES tenant(id), offer_id uuid NOT NULL, attempt_id uuid NOT NULL, service_id uuid NOT NULL,
  created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL, pickup_earliest timestamptz NOT NULL, pickup_latest timestamptz NOT NULL, gross_minor bigint NOT NULL, currency char(3) NOT NULL,
  PRIMARY KEY (tenant_id, offer_id)
);
CREATE TABLE fact_booking (
  tenant_id uuid NOT NULL REFERENCES tenant(id), booking_id uuid NOT NULL, service_id uuid,
  confirmed_at timestamptz, pickup_earliest timestamptz, pickup_latest timestamptz, gross_minor bigint, currency char(3),
  cancelled_at timestamptz, cancel_reason text, cause_class text,
  boarded_at timestamptz, alighted_at timestamptz, no_show_at timestamptz, no_show_wait_seconds integer,
  state text NOT NULL CHECK (state IN ('CONFIRMED', 'BOARDED', 'COMPLETED', 'CANCELED', 'NO_SHOW', 'UNKNOWN')),
  PRIMARY KEY (tenant_id, booking_id)
);
CREATE TABLE fact_stop_event (
  tenant_id uuid NOT NULL REFERENCES tenant(id), event_id uuid NOT NULL, shift_id uuid NOT NULL, vehicle_id uuid NOT NULL, booking_id uuid, route_stop_id uuid,
  event_type text NOT NULL, outcome text NOT NULL, reason_code text, effective_at timestamptz NOT NULL, occurred_at timestamptz NOT NULL, offline_recorded boolean NOT NULL,
  no_show_wait_seconds integer, no_show_grace_min integer,
  PRIMARY KEY (tenant_id, event_id)
);
CREATE TABLE fact_vehicle_state (
  tenant_id uuid NOT NULL REFERENCES tenant(id), event_id uuid NOT NULL, vehicle_id uuid NOT NULL,
  kind text NOT NULL CHECK (kind IN ('BLOCKED', 'BLOCK_LIFTED', 'RELEASE_ISSUED', 'RELEASE_REVOKED')), ref_id uuid NOT NULL, effective_at timestamptz NOT NULL, safety_revision bigint,
  PRIMARY KEY (tenant_id, event_id)
);
CREATE INDEX fact_vehicle_state_by_vehicle ON fact_vehicle_state (tenant_id, vehicle_id, effective_at);
CREATE INDEX fact_stop_event_by_booking ON fact_stop_event (tenant_id, booking_id) WHERE booking_id IS NOT NULL;

CREATE TABLE dataset_watermark (
  tenant_id       uuid        NOT NULL REFERENCES tenant(id),
  source          text        NOT NULL CHECK (source IN ('ATTEMPT', 'OFFER', 'BOOKING', 'DRIVER_EVENT', 'VEHICLE_STATE')),
  processed_until timestamptz,
  completeness    text        NOT NULL CHECK (completeness IN ('COMPLETE', 'INCOMPLETE')),
  gap_count       integer     NOT NULL DEFAULT 0 CHECK (gap_count >= 0),
  event_count     bigint      NOT NULL DEFAULT 0 CHECK (event_count >= 0),
  updated_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, source),
  CONSTRAINT dataset_watermark_gap_means_incomplete CHECK ((gap_count > 0) = (completeness = 'INCOMPLETE'))
);

DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['analytics_event_inbox', 'fact_attempt', 'fact_offer', 'fact_booking', 'fact_stop_event', 'fact_vehicle_state', 'dataset_watermark'] LOOP
    EXECUTE format('ALTER TABLE taktovia.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE taktovia.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON taktovia.%I USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant())', t);
    EXECUTE format('CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON taktovia.%I FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change()', t);
  END LOOP;
END $$;
-- Eingang unveränderlich; die Fakten sind abgeleitet und werden beim Wiederaufbau ersetzt (kein Löschen einzelner Eingangszeilen)
CREATE TRIGGER analytics_event_inbox_immutable BEFORE UPDATE OR DELETE ON analytics_event_inbox FOR EACH ROW EXECUTE FUNCTION reject_mutation();
