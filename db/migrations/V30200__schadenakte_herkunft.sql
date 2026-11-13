-- WP-M3-20 / LH-6.21-CHK-011, LH-6.22-DMG-003: Herkunft der Schadenakte (Prüfschritt oder Störung/Incident), in beide Richtungen verknüpft.
-- Additiv zu V3020 (defect_case.source_inspection_id, source_step_code bestehen dort) und V30430:
-- 1. Je Prüfschritt höchstens eine Akte (Unique über Mandant, Prüflauf, Prüfschritt): wiederholtes Auslösen, auch von zwei Geräten gleichzeitig, ergibt dieselbe Akte.
-- 2. Die Incident-Referenz ist jetzt ein echter Fremdschlüssel (V30530 legt den Incident an); eine Akte hat höchstens eine Herkunft.
-- 3. Die Herkunft ist unveränderlich (Wächter). Die Gegenreferenz Prüfschritt -> Akte steht NUR hier (Herkunftsspalten von defect_case): `inspection_answer` bekommt keine Spalte
--    `defect_case_id`, damit die Unveränderlichkeit abgeschlossener Antworten (CHK-013, `inspection_answer_guard`) nie berührt wird (Invariante b des Vertrags CHK-011).
SET search_path = taktovia, public;

ALTER TABLE defect_case
  ADD COLUMN source_incident_id uuid,
  ADD CONSTRAINT defect_case_incident_fk FOREIGN KEY (tenant_id, source_incident_id) REFERENCES incident(tenant_id, id),
  ADD CONSTRAINT defect_case_single_origin CHECK (NOT (source_inspection_id IS NOT NULL AND source_incident_id IS NOT NULL));

CREATE UNIQUE INDEX defect_case_origin_step_unique ON defect_case (tenant_id, source_inspection_id, source_step_code)
  WHERE source_inspection_id IS NOT NULL AND source_step_code IS NOT NULL;
CREATE INDEX defect_case_by_inspection ON defect_case (tenant_id, source_inspection_id) WHERE source_inspection_id IS NOT NULL;
CREATE INDEX defect_case_by_incident ON defect_case (tenant_id, source_incident_id) WHERE source_incident_id IS NOT NULL;

CREATE FUNCTION defect_case_origin_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.source_inspection_id IS DISTINCT FROM OLD.source_inspection_id OR NEW.source_step_code IS DISTINCT FROM OLD.source_step_code OR NEW.source_incident_id IS DISTINCT FROM OLD.source_incident_id THEN
    RAISE EXCEPTION 'defect_case origin is immutable' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER defect_case_origin_guard BEFORE UPDATE OF source_inspection_id, source_step_code, source_incident_id ON defect_case FOR EACH ROW EXECUTE FUNCTION defect_case_origin_guard();
