-- WP-M4-23 (LH-I.1-DOC-007): Unveränderliches Original, Versionen und Prüfspur (Modul documents-finance).
-- Das Original (Objekt und expense_document.original_sha256) wird nie geändert. Jede Bearbeitung der Belegfelder erzeugt eine neue, unveränderliche Revision (document_revision)
-- mit Bearbeiter, Zeitpunkt, Grund sowie Alt- und Neuwert je Feld; `expense_document.current_fields` führt den aktuellen Stand und ändert sich nur zusammen mit der Revision.
SET search_path = taktovia, public;

ALTER TABLE expense_document ADD COLUMN current_fields jsonb NOT NULL DEFAULT '{}'::jsonb CONSTRAINT expense_document_fields_object CHECK (jsonb_typeof(current_fields) = 'object');

CREATE TABLE document_revision (
  tenant_id       uuid        NOT NULL REFERENCES tenant(id),
  id              uuid        NOT NULL,
  document_id     uuid        NOT NULL,
  revision        bigint      NOT NULL CHECK (revision >= 1),
  field_values    jsonb       NOT NULL CHECK (jsonb_typeof(field_values) = 'object'),   -- je Feld {"old": …, "new": …} (+ "confirmed", "ocr_proposal")
  edited_view_key text        CHECK (edited_view_key IS NULL OR edited_view_key ~ '^[A-Za-z0-9._/-]{1,200}$'),   -- bearbeitete Ansicht als eigenes Objekt (nie das Original)
  edited_by       text        NOT NULL CHECK (length(edited_by) BETWEEN 1 AND 200),
  edited_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  reason          text        NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, document_id, revision),
  FOREIGN KEY (tenant_id, document_id) REFERENCES expense_document(tenant_id, id)
);
ALTER TABLE document_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_revision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON document_revision USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER document_revision_immutable BEFORE UPDATE OR DELETE ON document_revision FOR EACH ROW EXECUTE FUNCTION taktovia.reject_mutation();

-- Feldstand und Revision gehören zusammen: der Stand ändert sich nur, wenn die Revision steigt; die Revision steigt nie rückwärts und nie um mehr als 1; bearbeitbar ist nur der Entwurfszustand STORED
-- (ein gebuchter oder freigegebener Beleg erlaubt keine freie Bearbeitung, Kap. 06 §6.6; Korrektur dort nur per Gegenereignis, WP-M4-36).
CREATE FUNCTION expense_document_revision_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF OLD.upload_ticket_id IS NULL THEN RETURN NEW; END IF;
  IF NEW.revision < OLD.revision OR NEW.revision > OLD.revision + 1 THEN
    RAISE EXCEPTION 'expense_document: Revision darf nur um 1 steigen' USING ERRCODE = '23514';
  END IF;
  IF NEW.current_fields IS DISTINCT FROM OLD.current_fields THEN
    IF NEW.revision <> OLD.revision + 1 THEN
      RAISE EXCEPTION 'expense_document: Feldstand nur zusammen mit einer neuen Revision aenderbar' USING ERRCODE = '23514';
    END IF;
    IF OLD.state <> 'STORED' THEN
      RAISE EXCEPTION 'expense_document: Belegfelder sind im Zustand % nicht bearbeitbar', OLD.state USING ERRCODE = '23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER expense_document_revision_guard BEFORE UPDATE ON expense_document FOR EACH ROW EXECUTE FUNCTION expense_document_revision_guard();
