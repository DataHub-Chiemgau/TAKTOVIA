-- WP-M2-02 / M2 OP-43: Zuordnung Fahrzeug <-> Dienst (Fahrzeugpool). Im Kern-DDL nicht vorhanden; `LH-6.5-OPT-002` filtert Kandidaten "nach Dienstzeit"
-- und braucht den Pool. Führend wird die Pflege durch M3 (WP-M3-06); bis dahin füllen nur Seed-Skripte (Fixture, Migrator-Rolle) die Tabelle, die Runtime-Rolle
-- liest nur. Besitzer laut db/table_owners.d/m2-02.csv: fleet-safety. Das Modul `booking-dispatch` liest sie ausschließlich über den Leseport `ResourceAvailability`.
SET search_path = taktovia, public;

CREATE TABLE vehicle_pool_assignment (
  tenant_id   uuid        NOT NULL REFERENCES tenant(id),
  id          uuid        NOT NULL,
  service_id  uuid        NOT NULL,
  vehicle_id  uuid        NOT NULL,
  valid_from  timestamptz NOT NULL,
  valid_to    timestamptz,
  revision    bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_by  text        NOT NULL CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  -- zusammengesetzte Schlüssel: Dienst und Fahrzeug müssen demselben Mandanten gehören
  FOREIGN KEY (tenant_id, service_id) REFERENCES service(tenant_id, id),
  FOREIGN KEY (tenant_id, vehicle_id) REFERENCES vehicle(tenant_id, id),
  CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT vehicle_pool_no_overlap EXCLUDE USING gist (tenant_id WITH =, service_id WITH =, vehicle_id WITH =, tstzrange(valid_from, valid_to) WITH &&)
);
ALTER TABLE vehicle_pool_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE vehicle_pool_assignment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON vehicle_pool_assignment USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON vehicle_pool_assignment FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE INDEX vehicle_pool_by_service ON vehicle_pool_assignment (tenant_id, service_id, valid_from);
CREATE INDEX vehicle_pool_by_vehicle ON vehicle_pool_assignment (tenant_id, vehicle_id);
