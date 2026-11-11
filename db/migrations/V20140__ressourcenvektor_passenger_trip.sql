-- WP-M2-14 / LH-6.4-BKG-004: Ressourcenvektor und Mehrpersonenbuchung.
-- 1. capacity_hold.capacity_vector: Form nach docs/umsetzungsplan/vertraege/schemas/resource-vector.v1.schema.json (Sitz, Rollstuhlplatz, Ausstattung getrennt; keine
--    unbekannte Dimension, Zähler nur als nichtnegative ganze Zahlen).
-- 2. passenger_trip: Personen und Plätze einer Buchung (n:1 zu booking), ohne Diagnosen (Unterstützungsbedarf nur als Katalogauswahl).
-- 3. booking.promise_snapshot ist nach dem Anlegen unveränderlich (der Ressourcenvektor gehört dazu); eine Änderung ist eine neue Zusage-Revision, nie ein Überschreiben.
SET search_path = taktovia, public;

ALTER TABLE capacity_hold ADD CONSTRAINT capacity_hold_vector_shape CHECK (
  jsonb_typeof(capacity_vector) = 'object'
  AND (capacity_vector - ARRAY['schema_version', 'seats', 'wheelchair', 'equipment']) = '{}'::jsonb
  AND (NOT capacity_vector ? 'seats' OR (jsonb_typeof(capacity_vector -> 'seats') = 'number' AND (capacity_vector ->> 'seats') ~ '^[0-9]{1,2}$'))
  AND (NOT capacity_vector ? 'wheelchair' OR (jsonb_typeof(capacity_vector -> 'wheelchair') = 'number' AND (capacity_vector ->> 'wheelchair') ~ '^[0-9]{1,2}$'))
  AND (NOT capacity_vector ? 'equipment' OR jsonb_typeof(capacity_vector -> 'equipment') = 'array')
  AND (NOT capacity_vector ? 'schema_version' OR capacity_vector ->> 'schema_version' = '1.0')
);

CREATE TABLE passenger_trip (
  tenant_id         uuid        NOT NULL REFERENCES tenant(id),
  id                uuid        NOT NULL,
  booking_id        uuid        NOT NULL,
  seq               integer     NOT NULL CHECK (seq BETWEEN 1 AND 99),
  place_kind        text        NOT NULL CHECK (place_kind IN ('SEAT', 'WHEELCHAIR')),
  assistance_codes  text[]      NOT NULL DEFAULT '{}'
                    CHECK (assistance_codes <@ ARRAY['BOARDING_HELP', 'AUDIO_GUIDANCE', 'LOW_FLOOR', 'WHEELCHAIR_SPACE', 'ASSISTANCE_ANIMAL', 'ACCOMPANYING_PERSON']::text[]),
  revision          bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, booking_id, seq),
  FOREIGN KEY (tenant_id, booking_id) REFERENCES booking(tenant_id, id)
);
ALTER TABLE passenger_trip ENABLE ROW LEVEL SECURITY;
ALTER TABLE passenger_trip FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON passenger_trip USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON passenger_trip FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE TRIGGER passenger_trip_immutable BEFORE UPDATE OR DELETE ON passenger_trip FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE INDEX passenger_trip_by_booking ON passenger_trip (tenant_id, booking_id);

CREATE FUNCTION booking_promise_snapshot_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.promise_snapshot IS DISTINCT FROM OLD.promise_snapshot THEN
    RAISE EXCEPTION 'booking promise snapshot is immutable; a change is a new promise revision' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER booking_promise_snapshot_immutable BEFORE UPDATE OF promise_snapshot ON booking FOR EACH ROW EXECUTE FUNCTION booking_promise_snapshot_guard();
