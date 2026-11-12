-- WP-M4-29 (LH-I.1-DOC-004): Kostenzuordnung eines Belegs zu Firma (Rechtsträger), Kostenstelle oder Fahrzeug (Modul documents-finance).
-- Die Firma ist `expense_document.legal_entity_id` (NOT NULL, FK, nach Ticketanlage unveränderlich). Die Zuordnung Beleg <-> Kostenobjekt ist eine eigene Tabelle, deren
-- zusammengesetzte Fremdschlüssel erzwingen: gleicher Mandant, gleicher Rechtsträger, nur Kostenobjekte der Arten VEHICLE und COST_CENTER (Kap. 04 §4.5, keine lokale Cross-Tenant-Referenz).
SET search_path = taktovia, public;

-- Kostenobjekt: aktiv/inaktiv (ein inaktives Kostenobjekt nimmt keine neuen Zuordnungen mehr an) und Fahrzeugbezug für kind = VEHICLE.
ALTER TABLE cost_object
  ADD COLUMN active         boolean     NOT NULL DEFAULT true,
  ADD COLUMN deactivated_at timestamptz,
  ADD COLUMN vehicle_id     uuid,
  ADD CONSTRAINT cost_object_active_pair CHECK (active = (deactivated_at IS NULL)),
  ADD CONSTRAINT cost_object_vehicle_kind CHECK ((kind = 'VEHICLE') = (vehicle_id IS NOT NULL)),
  ADD CONSTRAINT cost_object_vehicle_fk FOREIGN KEY (tenant_id, vehicle_id) REFERENCES vehicle(tenant_id, id),
  ADD CONSTRAINT cost_object_target_unique UNIQUE (tenant_id, id, legal_entity_id, kind);
-- Je Fahrzeug und Rechtsträger höchstens ein Fahrzeug-Kostenobjekt (paralleles Anlegen derselben Zuordnung: genau eines entsteht).
CREATE UNIQUE INDEX cost_object_one_vehicle_per_entity ON cost_object (tenant_id, vehicle_id, legal_entity_id) WHERE kind = 'VEHICLE';

-- Ein einmal deaktiviertes Kostenobjekt bleibt deaktiviert (Wiederaufnahme = neues Kostenobjekt; vermeidet Auf-und-zu-Rennen mit laufenden Zuordnungen); Art und Rechtsträger sind unveränderlich.
CREATE FUNCTION cost_object_lifecycle_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.kind IS DISTINCT FROM OLD.kind OR NEW.legal_entity_id IS DISTINCT FROM OLD.legal_entity_id OR NEW.vehicle_id IS DISTINCT FROM OLD.vehicle_id THEN
    RAISE EXCEPTION 'cost_object: Art, Rechtstraeger und Fahrzeug sind unveraenderlich' USING ERRCODE = '23514';
  END IF;
  IF OLD.active = false AND NEW.active = true THEN
    RAISE EXCEPTION 'cost_object: ein deaktiviertes Kostenobjekt wird nicht reaktiviert' USING ERRCODE = '23514';
  END IF;
  IF OLD.active = true AND NEW.active = false AND NEW.deactivated_at IS NULL THEN NEW.deactivated_at := clock_timestamp(); END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER cost_object_lifecycle_guard BEFORE UPDATE ON cost_object FOR EACH ROW EXECUTE FUNCTION cost_object_lifecycle_guard();

-- Ziel der zusammengesetzten Fremdschlüssel der Zuordnung: Beleg mit Rechtsträger.
ALTER TABLE expense_document ADD CONSTRAINT expense_document_entity_unique UNIQUE (tenant_id, id, legal_entity_id);

CREATE TABLE expense_cost_assignment (
  tenant_id        uuid        NOT NULL REFERENCES tenant(id),
  document_id      uuid        NOT NULL,
  cost_object_id   uuid        NOT NULL,
  legal_entity_id  uuid        NOT NULL,
  cost_object_kind text        NOT NULL CHECK (cost_object_kind IN ('VEHICLE', 'COST_CENTER')),
  assigned_by      text        NOT NULL CHECK (length(assigned_by) BETWEEN 1 AND 200),
  assigned_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, document_id, cost_object_id),
  -- gleicher Mandant UND gleicher Rechtsträger bei Beleg und Kostenobjekt, Art begrenzt: per Schema, nicht nur per Anwendung
  FOREIGN KEY (tenant_id, document_id, legal_entity_id) REFERENCES expense_document(tenant_id, id, legal_entity_id),
  FOREIGN KEY (tenant_id, cost_object_id, legal_entity_id, cost_object_kind) REFERENCES cost_object(tenant_id, id, legal_entity_id, kind)
);
CREATE INDEX expense_cost_assignment_by_object ON expense_cost_assignment (tenant_id, cost_object_id);
ALTER TABLE expense_cost_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE expense_cost_assignment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON expense_cost_assignment USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());

-- Neue Zuordnungen nur zu aktiven Kostenobjekten; die Kostenobjektzeile wird dabei `FOR SHARE` gesperrt, ein gleichzeitiges Deaktivieren wartet auf das Ende der Zuordnung
-- (danach ist der Beleg wegen des inaktiven Kostenobjekts nicht freigabefähig, siehe Anwendung). Zuordnungen ändert man nur im Entwurfszustand STORED des Belegs.
CREATE FUNCTION expense_cost_assignment_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE is_active boolean; doc_state text; tid uuid; did uuid;
BEGIN
  IF TG_OP = 'UPDATE' THEN RAISE EXCEPTION 'expense_cost_assignment: Zuordnungen werden ersetzt, nicht geaendert' USING ERRCODE = '23514'; END IF;
  IF TG_OP = 'INSERT' THEN tid := NEW.tenant_id; did := NEW.document_id; ELSE tid := OLD.tenant_id; did := OLD.document_id; END IF;
  SELECT state INTO doc_state FROM expense_document WHERE tenant_id = tid AND id = did FOR SHARE;
  IF FOUND AND doc_state <> 'STORED' THEN
    RAISE EXCEPTION 'expense_cost_assignment: Zuordnung nur im Entwurfszustand STORED aenderbar (Zustand %)', doc_state USING ERRCODE = '23514';
  END IF;
  IF TG_OP = 'INSERT' THEN
    SELECT active INTO is_active FROM cost_object WHERE tenant_id = NEW.tenant_id AND id = NEW.cost_object_id FOR SHARE;
    IF FOUND AND is_active = false THEN   -- ein nicht sichtbares/fehlendes Kostenobjekt scheitert am Fremdschlüssel
      RAISE EXCEPTION 'expense_cost_assignment: Kostenobjekt ist inaktiv' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
  END IF;
  RETURN OLD;
END $$;
CREATE TRIGGER expense_cost_assignment_guard BEFORE INSERT OR UPDATE OR DELETE ON expense_cost_assignment FOR EACH ROW EXECUTE FUNCTION expense_cost_assignment_guard();
