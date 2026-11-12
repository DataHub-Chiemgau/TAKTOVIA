-- WP-M4-24 (LH-I.1-DOC-008): Hash und Integritätsprüfung der Originale (Modul documents-finance).
-- `integrity_state` ist die Freigabesperre: VIOLATED (Hash weicht ab oder Objekt fehlt) ist endgültig und sperrt Freigabe und Posting. Jeder Vorfall steht unveränderlich in document_integrity_incident.
SET search_path = taktovia, public;

ALTER TABLE expense_document
  ADD COLUMN integrity_state      text        NOT NULL DEFAULT 'UNCHECKED' CONSTRAINT expense_document_integrity_state CHECK (integrity_state IN ('UNCHECKED', 'VERIFIED', 'VIOLATED')),
  ADD COLUMN integrity_checked_at timestamptz;
CREATE INDEX expense_document_integrity_due ON expense_document (tenant_id, integrity_checked_at NULLS FIRST, id) WHERE state = 'STORED';

CREATE TABLE document_integrity_incident (
  tenant_id       uuid        NOT NULL REFERENCES tenant(id),
  id              uuid        NOT NULL,
  document_id     uuid        NOT NULL,
  trigger_kind    text        NOT NULL CHECK (trigger_kind IN ('DELIVERY', 'APPROVAL', 'SCHEDULED', 'MANUAL')),   -- Prüfanlass
  failure         text        NOT NULL CHECK (failure IN ('HASH_MISMATCH', 'OBJECT_MISSING')),
  expected_sha256 text        NOT NULL CHECK (expected_sha256 ~ '^[0-9a-f]{64}$'),
  actual_sha256   text        CHECK (actual_sha256 ~ '^[0-9a-f]{64}$'),
  detected_by     text        NOT NULL CHECK (length(detected_by) BETWEEN 1 AND 200),
  detected_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, document_id) REFERENCES expense_document(tenant_id, id),
  CONSTRAINT incident_hash_matches_failure CHECK ((failure = 'OBJECT_MISSING') = (actual_sha256 IS NULL) AND (failure <> 'HASH_MISMATCH' OR actual_sha256 <> expected_sha256))
);
CREATE INDEX document_integrity_incident_by_doc ON document_integrity_incident (tenant_id, document_id, detected_at);
ALTER TABLE document_integrity_incident ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_integrity_incident FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON document_integrity_incident USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER document_integrity_incident_immutable BEFORE UPDATE OR DELETE ON document_integrity_incident FOR EACH ROW EXECUTE FUNCTION taktovia.reject_mutation();

-- Eine festgestellte Verletzung wird nicht still zurückgenommen; ein Beleg mit Verletzung verlässt den Zustand STORED nicht Richtung Freigabe (nur Fachfreigabe-WPs setzen später weitere Zustände).
CREATE FUNCTION expense_document_integrity_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF OLD.integrity_state = 'VIOLATED' AND NEW.integrity_state <> 'VIOLATED' THEN
    RAISE EXCEPTION 'expense_document: Integritaetsverletzung ist endgueltig' USING ERRCODE = '23514';
  END IF;
  IF OLD.integrity_state = 'VIOLATED' AND NEW.state IN ('APPROVED', 'POSTED') AND OLD.state IS DISTINCT FROM NEW.state THEN
    RAISE EXCEPTION 'expense_document: Beleg mit Integritaetsverletzung ist nicht freigabefaehig' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER expense_document_integrity_guard BEFORE UPDATE ON expense_document FOR EACH ROW EXECUTE FUNCTION expense_document_integrity_guard();
