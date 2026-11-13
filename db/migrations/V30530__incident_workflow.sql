-- WP-M3-53 / LH-I.8-AUT-001: regelbasierter Workflow, Incident bei Fahrzeugausfall (OP-02, OP-24).
-- Additiv zu V3020 (incident, workflow_rule_revision, workflow_run_log bestehen dort):
-- 1. Regelrevisionen tragen eine Revisionsnummer je (Mandant, Ereignisart); sie bleiben unveränderlich (Trigger aus V3020, reject_mutation).
-- 2. Der Incident bekommt Dienstbezug (Sichtbarkeit), auslösendes Ereignis, Abschlusszeit und einen Wächter: Inhalt unveränderlich, Zustandsautomat laut
--    docs/umsetzungsplan/vertraege/zustaende/incident.md, Revision steigt um 1, kein DELETE. Je Fahrzeugausfall höchstens eine Ersatzsuche (Unique auf die Operation).
SET search_path = taktovia, public;

ALTER TABLE workflow_rule_revision
  ADD COLUMN revision_no int NOT NULL DEFAULT 1 CHECK (revision_no >= 1),
  ADD CONSTRAINT workflow_rule_revision_no_unique UNIQUE (tenant_id, event_kind, revision_no);

-- Die Revisionsnummer vergibt die Datenbank fortlaufend je (Mandant, Ereignisart) unter einer Sperre: parallele Veröffentlichungen ergeben lückenlose, verschiedene Nummern.
CREATE FUNCTION workflow_rule_revision_number() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended('workflow_rule|' || NEW.tenant_id::text || '|' || NEW.event_kind, 0));
  SELECT coalesce(max(revision_no), 0) + 1 INTO NEW.revision_no FROM workflow_rule_revision WHERE tenant_id = NEW.tenant_id AND event_kind = NEW.event_kind;
  RETURN NEW;
END $$;
CREATE TRIGGER workflow_rule_revision_number BEFORE INSERT ON workflow_rule_revision FOR EACH ROW EXECUTE FUNCTION workflow_rule_revision_number();

ALTER TABLE incident
  ADD COLUMN service_id        uuid,
  ADD COLUMN trigger_event_id  uuid,
  ADD COLUMN subject_ref       text CHECK (subject_ref IS NULL OR length(subject_ref) BETWEEN 1 AND 120),
  ADD COLUMN closed_at         timestamptz,
  ADD CONSTRAINT incident_service_fk FOREIGN KEY (tenant_id, service_id) REFERENCES service(tenant_id, id),
  ADD CONSTRAINT incident_closed_consistent CHECK ((status IN ('RESOLVED', 'CLOSED_NO_REPLACEMENT')) = (closed_at IS NOT NULL)),
  ADD CONSTRAINT incident_search_consistent CHECK (status <> 'REPLACEMENT_SEARCH' OR replacement_operation_id IS NOT NULL);

-- höchstens eine Ersatzsuche je Operation und je Incident (eine Operation gehört nie zwei Incidents)
CREATE UNIQUE INDEX incident_replacement_operation_unique ON incident (tenant_id, replacement_operation_id) WHERE replacement_operation_id IS NOT NULL;
CREATE INDEX incident_by_vehicle ON incident (tenant_id, vehicle_id, status);

CREATE FUNCTION incident_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'incident is append-only' USING ERRCODE = '23514'; END IF;
  IF NEW.kind IS DISTINCT FROM OLD.kind OR NEW.vehicle_id IS DISTINCT FROM OLD.vehicle_id OR NEW.dedupe_key IS DISTINCT FROM OLD.dedupe_key
     OR NEW.opened_at IS DISTINCT FROM OLD.opened_at OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at
     OR NEW.service_id IS DISTINCT FROM OLD.service_id OR NEW.trigger_event_id IS DISTINCT FROM OLD.trigger_event_id OR NEW.subject_ref IS DISTINCT FROM OLD.subject_ref
     OR NEW.rule_revision_id IS DISTINCT FROM OLD.rule_revision_id THEN
    RAISE EXCEPTION 'incident identity is immutable' USING ERRCODE = '23514';
  END IF;
  IF OLD.replacement_operation_id IS NOT NULL AND NEW.replacement_operation_id IS DISTINCT FROM OLD.replacement_operation_id THEN
    RAISE EXCEPTION 'incident replacement search is started exactly once' USING ERRCODE = '23514';
  END IF;
  IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
       (OLD.status = 'OPEN' AND NEW.status IN ('REPLACEMENT_SEARCH', 'CLOSED_NO_REPLACEMENT'))
    OR (OLD.status = 'REPLACEMENT_SEARCH' AND NEW.status IN ('OPEN', 'RESOLVED', 'CLOSED_NO_REPLACEMENT'))) THEN
    RAISE EXCEPTION 'incident transition % -> % is not allowed', OLD.status, NEW.status USING ERRCODE = '23514';
  END IF;
  IF NEW.status IS DISTINCT FROM OLD.status AND NEW.revision <> OLD.revision + 1 THEN
    RAISE EXCEPTION 'incident revision must increase by one with a state change' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER incident_guard BEFORE UPDATE OR DELETE ON incident FOR EACH ROW EXECUTE FUNCTION incident_guard();
