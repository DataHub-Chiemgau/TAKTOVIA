-- WP-M1-09 / LH-I.4-MUL-002: Organisationsstruktur eines Mandanten: Regionen, Betriebshöfe, Dienste, Kostenstellen.
-- OP-07 (Entwurf für den Bau, Review durch Architektur): Region und Betriebshof stehen weder im Kernschema noch in Kap. 04 §4.4. Beide sind
-- mandantenlokale Stammobjekte mit PK (tenant_id,id), RLS, Revision und Urheber; ein Betriebshof gehört genau einer Region.
-- Zuordnung Dienst -> Region: Spalte `service.region_id` (optional, ein Dienst gehört höchstens einer Region; Zuordnungstabelle wäre erst bei
-- Mehrfachzuordnung nötig). Kostenstellen bleiben in M1 reine Stammobjekte (`cost_object`, kind = 'COST_CENTER', `legal_entity_id` Pflicht
-- wegen V1; Finanzlogik ist M4) und sind optional einer Region und/oder einem Dienst zuordenbar.
-- `master_data_guard()` (V1050) erhöht die Revision je Änderung genau um 1 und hält Urheber und Entstehungszeit fest.
SET search_path = taktovia, public;

CREATE TABLE region (
  tenant_id  uuid        NOT NULL REFERENCES tenant(id),
  id         uuid        NOT NULL,
  name       text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
  revision   bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  created_by text        NOT NULL CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  PRIMARY KEY (tenant_id, id)
);
CREATE TABLE depot (
  tenant_id  uuid        NOT NULL REFERENCES tenant(id),
  id         uuid        NOT NULL,
  region_id  uuid        NOT NULL,
  name       text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
  revision   bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  created_by text        NOT NULL CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, region_id) REFERENCES region(tenant_id, id)
);
-- Die RLS-Schleife aus V1 erfasst nur die Tabellen des Kernschemas.
ALTER TABLE region ENABLE ROW LEVEL SECURITY;  ALTER TABLE region FORCE ROW LEVEL SECURITY;
ALTER TABLE depot  ENABLE ROW LEVEL SECURITY;  ALTER TABLE depot  FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON region USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE POLICY tenant_isolation ON depot  USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON region FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON depot  FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE TRIGGER region_guard BEFORE UPDATE ON region FOR EACH ROW EXECUTE FUNCTION master_data_guard();
CREATE TRIGGER depot_guard  BEFORE UPDATE ON depot  FOR EACH ROW EXECUTE FUNCTION master_data_guard();
CREATE INDEX region_by_created ON region (tenant_id, created_at, id);
CREATE INDEX depot_by_created  ON depot  (tenant_id, created_at, id);
CREATE INDEX depot_by_region   ON depot  (tenant_id, region_id);

-- Dienst: Region, Revision (If-Match), Urheber. Alte Zeilen und Fixtures tragen den Platzhalter `system:unattributed`.
ALTER TABLE service
  ADD COLUMN region_id  uuid,
  ADD COLUMN revision   bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  ADD COLUMN created_by text        NOT NULL DEFAULT 'system:unattributed'
    CONSTRAINT service_created_by_format CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  ADD CONSTRAINT service_region_fk FOREIGN KEY (tenant_id, region_id) REFERENCES region(tenant_id, id),
  ADD CONSTRAINT service_name_format CHECK (length(name) BETWEEN 1 AND 200);
CREATE TRIGGER service_guard BEFORE UPDATE ON service FOR EACH ROW EXECUTE FUNCTION master_data_guard();
CREATE INDEX service_by_created ON service (tenant_id, created_at, id);
CREATE INDEX service_by_region  ON service (tenant_id, region_id);

-- Kostenstelle (`cost_object`, kind = 'COST_CENTER'): Name, Gebiet/Dienst, Revision, Urheber. Andere kinds (M4) lassen die neuen Spalten leer.
ALTER TABLE cost_object
  ADD COLUMN name       text,
  ADD COLUMN region_id  uuid,
  ADD COLUMN service_id uuid,
  ADD COLUMN revision   bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  ADD COLUMN created_by text        NOT NULL DEFAULT 'system:unattributed'
    CONSTRAINT cost_object_created_by_format CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  ADD CONSTRAINT cost_object_region_fk  FOREIGN KEY (tenant_id, region_id)  REFERENCES region(tenant_id, id),
  ADD CONSTRAINT cost_object_service_fk FOREIGN KEY (tenant_id, service_id) REFERENCES service(tenant_id, id),
  -- Name bei Altzeilen/Fixtures leer (die Verwaltungsschnittstelle verlangt ihn und zeigt sonst die Kennung); Kennung höchstens 40 Zeichen.
  ADD CONSTRAINT cost_center_fields CHECK ((name IS NULL OR length(name) BETWEEN 1 AND 200) AND (kind <> 'COST_CENTER' OR length(business_ref) BETWEEN 1 AND 40));
CREATE TRIGGER cost_object_guard BEFORE UPDATE ON cost_object FOR EACH ROW EXECUTE FUNCTION master_data_guard();
CREATE INDEX cost_object_by_created ON cost_object (tenant_id, created_at, id);
