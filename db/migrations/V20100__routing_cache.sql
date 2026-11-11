-- WP-M2-10 / LH-6.5-OPT-007: Cache der Fahrzeitmatrix (Kap. 04 §4.4). Cache-Key: Mandant, Start-Haltepunkt, Ziel-Haltepunkt, Fahrzeugprofil und Kartendatenversion
-- (UNIQUE). Einträge mit anderer Kartendatenversion werden nie geliefert (die Abfrage verlangt die aktuelle Version) und bei einem neuen Kartenstand gelöscht.
-- Technische, jederzeit ersetzbare Daten: keine Revision, keine Auditpflicht (Ausnahme von der DDL-Konvention, siehe m2-detailvertraege.md §4); nur Ergebnisse
-- erfolgreicher Berechnungen, nie Schätzwerte und nie "unerreichbar". Die Haltepunkt-IDs sind `stop_revision.stop_id` (kein Fremdschlüssel: stop_id ist dort nicht eindeutig).
SET search_path = taktovia, public;

CREATE TABLE route_matrix_cache (
  tenant_id     uuid        NOT NULL REFERENCES tenant(id),
  id            uuid        NOT NULL,
  origin_stop   uuid        NOT NULL,
  dest_stop     uuid        NOT NULL,
  profile       text        NOT NULL CHECK (profile ~ '^[a-z][a-z0-9_]{0,31}$'),
  map_version   text        NOT NULL CHECK (map_version ~ '^[A-Za-z0-9._-]{1,64}$'),
  time_s        integer     NOT NULL CHECK (time_s >= 0),
  distance_m    integer     NOT NULL CHECK (distance_m >= 0),
  provider      text        NOT NULL CHECK (provider ~ '^[a-z][a-z0-9_-]{0,31}$'),
  computed_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  CONSTRAINT route_matrix_cache_key UNIQUE (tenant_id, origin_stop, dest_stop, profile, map_version)
);
ALTER TABLE route_matrix_cache ENABLE ROW LEVEL SECURITY;
ALTER TABLE route_matrix_cache FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON route_matrix_cache USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON route_matrix_cache FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE INDEX route_matrix_cache_by_version ON route_matrix_cache (tenant_id, map_version);
