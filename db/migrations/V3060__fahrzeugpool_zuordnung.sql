-- WP-M3-06 / LH-6.8-FLT-002: Fahrzeugpool-Zuordnung zu Diensten (Dienstzuordnung mit Gültigkeitsintervall `[valid_from, valid_to)` und optionalem Gebietsbezug).
-- Namenskollision (Vertrag LH-6.8-FLT-002, offener Punkt c): `vehicle_assignment` (V1340, WP-M1-34) ist die Rollenzuordnung Fahrzeug <-> Organisation. Die Dienstzuordnung
-- bleibt in `vehicle_pool_assignment` (V2020, WP-M2-02, M2 OP-43), der Tabelle, die der Leseport `ResourceAvailability` bereits liest; sie wird hier additiv erweitert.
-- Das Überlappungsverbot `vehicle_pool_no_overlap` gilt je (Dienst, Fahrzeug): dasselbe Fahrzeug kann gleichzeitig mehreren Diensten zugeordnet sein.
-- Planungsannahme PA-02 / M3-Detailvertrag Nr. 25 (OP-25 offen): „geografisch“ = Bediengebiet. `service_area_id` verweist auf eine Revision eines Bediengebiets desselben
-- Dienstes; wirksam ist die zum Prüfzeitpunkt gültige Revision derselben Kette (`COALESCE(area_key, id)`), nicht die genannte Zeile.
SET search_path = taktovia, public;

-- Zusammengesetzter Schlüssel (Mandant, Dienst, Gebiet): ein Gebiet eines anderen Dienstes oder Mandanten ist nicht referenzierbar.
ALTER TABLE service_area ADD CONSTRAINT service_area_tenant_service_id_key UNIQUE (tenant_id, service_id, id);

ALTER TABLE vehicle_pool_assignment
  ADD COLUMN service_area_id uuid,
  ADD CONSTRAINT vehicle_pool_service_area_fk FOREIGN KEY (tenant_id, service_id, service_area_id) REFERENCES service_area (tenant_id, service_id, id);
CREATE INDEX vehicle_pool_by_created ON vehicle_pool_assignment (tenant_id, created_at, id);

-- Unveränderlichkeit wie bei den Fahrzeugrollen (auch für die Administration): nie löschen; ein laufendes Intervall darf nur geschlossen werden
-- (valid_to setzen, Revision +1), ein abgeschlossenes bleibt unverändert. Ein anderes Gebiet oder ein anderer Zeitraum ist eine neue Zuordnung.
CREATE FUNCTION vehicle_pool_assignment_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'vehicle pool assignment is append-only' USING ERRCODE = '23514';
  END IF;
  IF OLD.valid_to IS NOT NULL OR NEW.valid_to IS NULL
     OR NEW.id IS DISTINCT FROM OLD.id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.service_id IS DISTINCT FROM OLD.service_id
     OR NEW.vehicle_id IS DISTINCT FROM OLD.vehicle_id OR NEW.service_area_id IS DISTINCT FROM OLD.service_area_id OR NEW.valid_from IS DISTINCT FROM OLD.valid_from
     OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'closed vehicle pool assignment is immutable; only an open interval may be closed' USING ERRCODE = '23514';
  END IF;
  NEW.revision := OLD.revision + 1;
  RETURN NEW;
END $$;
CREATE TRIGGER vehicle_pool_assignment_guard BEFORE UPDATE OR DELETE ON vehicle_pool_assignment FOR EACH ROW EXECUTE FUNCTION vehicle_pool_assignment_guard();
