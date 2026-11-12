-- WP-M4-14 / LH-6.23-SCH-005: Veröffentlichung des Dienstplans. Eine Publikation schreibt keine neue Zeile, sondern befördert den Entwurf (`roster_revision` DRAFT -> PUBLISHED) und ersetzt die bis dahin
-- veröffentlichte Revision (PUBLISHED -> SUPERSEDED); `staff_shift` der Revision wird verbindlich (DRAFT -> PUBLISHED), die der Vorgängerrevision SUPERSEDED, alles in einer Transaktion (Anwendung).
-- Publikationsdaten stehen an der Revision (wirksam ab, Begründung, Wirkungsbericht, Auditereignis, Idempotenzschlüssel mit Inhaltsabdruck: dieselbe Wiederholung liefert dieselbe Revision, ein anderer Inhalt
-- unter demselben Schlüssel ist ein Konflikt). Die Schicht trägt eine stabile Kennung über alle Revisionen (`lineage_id`; die Kopie im neuen Entwurf behält sie), damit „Änderungen je Schicht“
-- (WP-M4-15) und „Benachrichtigung nur bei geänderten Schichten“ revisionsübergreifend zuordenbar sind.
-- Parameter `shift_publish_notice_hours` (Veröffentlichungsvorlauf, Mandant): vorläufig ohne Sperrwirkung (OP 9), nur im Register.
SET search_path = taktovia, public;

ALTER TABLE roster_revision
  ADD COLUMN effective_from            timestamptz,
  ADD COLUMN publish_reason            text CONSTRAINT roster_revision_publish_reason CHECK (publish_reason IS NULL OR length(publish_reason) BETWEEN 3 AND 1000),
  ADD COLUMN impact_report_id          uuid,
  ADD COLUMN publish_audit_event_id    uuid,
  ADD COLUMN publish_idempotency_key   text CONSTRAINT roster_revision_publish_key CHECK (publish_idempotency_key IS NULL OR length(publish_idempotency_key) BETWEEN 1 AND 255),
  ADD COLUMN publish_fingerprint       text;
CREATE UNIQUE INDEX roster_revision_publish_key_unique ON roster_revision (tenant_id, roster_id, publish_idempotency_key) WHERE publish_idempotency_key IS NOT NULL;

-- Stabile Schichtkennung über Revisionen, vom Trigger gesetzt, nullbar nur für Zeilen ohne Trigger (die Kopie im neuen Entwurf trägt dieselbe `lineage_id`), danach unveränderlich.
ALTER TABLE staff_shift ADD COLUMN lineage_id uuid;
ALTER TABLE staff_shift DISABLE TRIGGER staff_shift_derive;      -- Rückfüllung ändert weder Inhalt noch Revision der Altzeilen
UPDATE staff_shift SET lineage_id = id WHERE lineage_id IS NULL;
ALTER TABLE staff_shift ENABLE TRIGGER staff_shift_derive;
CREATE FUNCTION staff_shift_lineage() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    NEW.lineage_id := COALESCE(NEW.lineage_id, NEW.id);
  ELSIF NEW.lineage_id IS DISTINCT FROM OLD.lineage_id THEN
    RAISE EXCEPTION 'staff_shift: die Schichtkennung ueber Revisionen ist unveraenderlich' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER staff_shift_lineage BEFORE INSERT OR UPDATE ON staff_shift FOR EACH ROW EXECUTE FUNCTION staff_shift_lineage();
CREATE INDEX staff_shift_by_lineage ON staff_shift (tenant_id, lineage_id, roster_revision) WHERE roster_id IS NOT NULL;

INSERT INTO policy_schema(schema_version, parameter, json_schema) VALUES
  ('1', 'shift_publish_notice_hours', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/shift_publish_notice_hours.v1.schema.json", "title": "shift_publish_notice_hours", "description": "Veröffentlichungsvorlauf des Dienstplans", "type": "integer", "minimum": 0, "maximum": 720, "default": 0, "x-unit": "HOURS", "x-levels": ["TENANT"], "x-security-class": "STANDARD", "x-limit-source": "PLANUNGSANNAHME", "x-catalog": "LH-J.4-shift_publish_notice_hours", "x-catalog-range": "0..720 Stunden (Planungsannahme); Wirkung bei Unterschreitung offen (OP 9), keine Sperre"}'::jsonb);
