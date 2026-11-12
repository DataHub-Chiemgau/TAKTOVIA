-- WP-M2-44 / LH-6.3-PAX-007: Fußweg zum Abholhaltepunkt als Snapshot des Angebots (Ablageort laut m2-detailvertraege OP-19: am Angebot, die Buchung liest ihn über ihr Angebot).
-- Ein Snapshot je Angebot (Primärschlüssel), nach dem Anlegen unveränderlich (kein UPDATE, kein DELETE). Karte und Textanleitung tragen denselben Snapshot (`id`).
-- Datenminimierung (OP-13): gespeichert wird nie die Koordinate eines freien Startpunkts. Die Geometrie gibt es nur für Wege zwischen öffentlichen Haltepunkten (`status = ROUTE`); der
-- Rückfall (`FALLBACK`) trägt nur die auf 10 m aufgerundete Luftlinie, `NO_WALK` (Start gleich Haltepunkt) keine Strecke.
SET search_path = taktovia, public;

CREATE TABLE ride_offer_walk (
  tenant_id        uuid        NOT NULL REFERENCES tenant(id),
  offer_id         uuid        NOT NULL,
  id               uuid        NOT NULL,
  status           text        NOT NULL CHECK (status IN ('ROUTE', 'FALLBACK', 'NO_WALK')),
  distance_m       integer     NOT NULL CHECK (distance_m >= 0),
  duration_s       integer     NOT NULL CHECK (duration_s >= 0),
  profile          text        NOT NULL CHECK (length(profile) BETWEEN 1 AND 32),
  map_version      text        CHECK (map_version IS NULL OR length(map_version) BETWEEN 1 AND 64),
  fallback_reason  text        CHECK (fallback_reason IN ('ROUTING_UNAVAILABLE', 'NO_ORIGIN_STOP')),
  geometry         jsonb       NOT NULL CHECK (jsonb_typeof(geometry) = 'array'),
  steps            jsonb       NOT NULL CHECK (jsonb_typeof(steps) = 'array'),
  pickup_stop_name text        NOT NULL CHECK (length(pickup_stop_name) BETWEEN 1 AND 200),
  computed_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, offer_id),
  UNIQUE (tenant_id, id),
  FOREIGN KEY (tenant_id, offer_id) REFERENCES ride_offer (tenant_id, id),
  CONSTRAINT ride_offer_walk_fallback_reason CHECK ((status = 'FALLBACK') = (fallback_reason IS NOT NULL)),
  CONSTRAINT ride_offer_walk_geometry_only_route CHECK (status = 'ROUTE' OR geometry = '[]'::jsonb)
);
ALTER TABLE ride_offer_walk ENABLE ROW LEVEL SECURITY;
ALTER TABLE ride_offer_walk FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ride_offer_walk USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON ride_offer_walk FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
