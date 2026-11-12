-- WP-M4-27 (LH-I.1-DOC-002): OCR-Adapter und Feldextraktion (Modul documents-finance).
-- extraction_run: ein Lauf je Beleg und OCR-Version (Eindeutigkeit = kein Doppellauf), Lease und Fencing über `attempt`. extracted_field: append-only; der OCR-Vorschlag (origin OCR) bleibt
-- unverändert, Bestätigung und Korrektur sind eigene Zeilen (origin HUMAN) mit Bezug auf den Vorschlag (corrects_field_id).
SET search_path = taktovia, public;

CREATE TABLE extraction_run (
  tenant_id         uuid        NOT NULL REFERENCES tenant(id),
  id                uuid        NOT NULL,
  document_id       uuid        NOT NULL,
  document_revision bigint      NOT NULL CHECK (document_revision >= 1),   -- Revision des Belegs beim Anstoßen
  engine            text        NOT NULL CHECK (length(engine) BETWEEN 1 AND 100),
  ocr_version       text        NOT NULL CHECK (length(ocr_version) BETWEEN 1 AND 100),
  status            text        NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED')),
  attempt           integer     NOT NULL DEFAULT 0 CHECK (attempt >= 0),    -- Fencing-Token: nur der aktuelle Versuch darf das Ergebnis schreiben
  lease_until       timestamptz,
  failure_code      text        CHECK (failure_code IS NULL OR failure_code IN ('TIMEOUT', 'ENGINE_ERROR', 'UNSUPPORTED_CONTENT', 'ORIGINAL_UNAVAILABLE')),
  field_count       integer     CHECK (field_count IS NULL OR field_count >= 0),
  requested_by      text        NOT NULL CHECK (length(requested_by) BETWEEN 1 AND 200),
  requested_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  started_at        timestamptz,
  finished_at       timestamptz,
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, document_id, engine, ocr_version),
  FOREIGN KEY (tenant_id, document_id) REFERENCES expense_document(tenant_id, id),
  CONSTRAINT extraction_run_failed_has_code CHECK ((status = 'FAILED') = (failure_code IS NOT NULL)),
  CONSTRAINT extraction_run_finished CHECK ((status IN ('COMPLETED', 'FAILED')) = (finished_at IS NOT NULL))
);
ALTER TABLE extraction_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE extraction_run FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON extraction_run USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON extraction_run FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change();

-- Zustände: QUEUED -> RUNNING; RUNNING -> RUNNING (Übernahme nach abgelaufener Lease, attempt steigt) | COMPLETED | FAILED; FAILED -> RUNNING (Wiederholung); COMPLETED ist endgültig.
CREATE FUNCTION extraction_run_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.id IS DISTINCT FROM OLD.id OR NEW.document_id IS DISTINCT FROM OLD.document_id OR NEW.document_revision IS DISTINCT FROM OLD.document_revision
     OR NEW.engine IS DISTINCT FROM OLD.engine OR NEW.ocr_version IS DISTINCT FROM OLD.ocr_version OR NEW.requested_by IS DISTINCT FROM OLD.requested_by OR NEW.requested_at IS DISTINCT FROM OLD.requested_at THEN
    RAISE EXCEPTION 'extraction_run: Sachfelder sind unveraenderlich' USING ERRCODE = '23514';
  END IF;
  IF NEW.attempt < OLD.attempt THEN RAISE EXCEPTION 'extraction_run: attempt darf nicht sinken' USING ERRCODE = '23514'; END IF;
  IF OLD.status = 'COMPLETED' AND NEW IS DISTINCT FROM OLD THEN RAISE EXCEPTION 'extraction_run: abgeschlossener Lauf ist endgueltig' USING ERRCODE = '23514'; END IF;
  IF NEW.status IS DISTINCT FROM OLD.status AND NOT ((OLD.status = 'QUEUED' AND NEW.status = 'RUNNING') OR (OLD.status = 'RUNNING' AND NEW.status IN ('COMPLETED', 'FAILED'))
     OR (OLD.status = 'FAILED' AND NEW.status = 'RUNNING')) THEN
    RAISE EXCEPTION 'extraction_run: Zustandsuebergang % -> % nicht erlaubt', OLD.status, NEW.status USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER extraction_run_guard BEFORE UPDATE ON extraction_run FOR EACH ROW EXECUTE FUNCTION extraction_run_guard();

CREATE TABLE extracted_field (
  tenant_id          uuid        NOT NULL REFERENCES tenant(id),
  id                 uuid        NOT NULL,
  seq                bigint      GENERATED ALWAYS AS IDENTITY,
  document_id        uuid        NOT NULL,
  run_id             uuid        NOT NULL,
  field_kind         text        NOT NULL CHECK (field_kind IN ('DOCUMENT_DATE', 'AMOUNT', 'CURRENCY', 'MERCHANT', 'DOCUMENT_NUMBER', 'VAT_AMOUNT')),
  occurrence         smallint    NOT NULL DEFAULT 0 CHECK (occurrence >= 0),   -- mehrere Mehrwertsteuerbeträge (je Satz)
  origin             text        NOT NULL CHECK (origin IN ('OCR', 'HUMAN')),
  status             text        NOT NULL CHECK (status IN ('PROPOSED', 'CONFIRMED', 'CORRECTED')),
  raw_value          text        CHECK (length(raw_value) <= 500),             -- Rohtext der Fundstelle, nur Daten
  normalized_value   text        CHECK (length(normalized_value) <= 500),      -- Betrag als Ganzzahl `amount_minor`, Datum ISO-8601, Währung ISO-4217; NULL = nicht erkannt
  display_value      text        CHECK (length(display_value) <= 500),         -- neutralisierte Darstellung (kein Markup, keine Formel)
  qualifier          text        CHECK (length(qualifier) <= 40),              -- z. B. Mehrwertsteuersatz in Prozent
  confidence         numeric(4,3) CHECK (confidence BETWEEN 0 AND 1),
  source_ref         jsonb       CHECK (source_ref IS NULL OR jsonb_typeof(source_ref) = 'object'),   -- Fundstelle: Seite und Koordinaten
  suspicious         boolean     NOT NULL DEFAULT false,                       -- Inhalt wirkt wie Anweisung, Formel oder Markup
  corrects_field_id  uuid,
  decided_by         text        CHECK (length(decided_by) BETWEEN 1 AND 200),
  decided_at         timestamptz,
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, document_id) REFERENCES expense_document(tenant_id, id),
  FOREIGN KEY (tenant_id, run_id) REFERENCES extraction_run(tenant_id, id),
  FOREIGN KEY (tenant_id, corrects_field_id) REFERENCES extracted_field(tenant_id, id),
  CONSTRAINT extracted_field_origin_status CHECK (
    (origin = 'OCR' AND status = 'PROPOSED' AND corrects_field_id IS NULL AND decided_by IS NULL AND decided_at IS NULL)
    OR (origin = 'HUMAN' AND status IN ('CONFIRMED', 'CORRECTED') AND corrects_field_id IS NOT NULL AND decided_by IS NOT NULL AND decided_at IS NOT NULL))
);
CREATE INDEX extracted_field_by_document ON extracted_field (tenant_id, document_id, field_kind, occurrence, seq);
ALTER TABLE extracted_field ENABLE ROW LEVEL SECURITY;
ALTER TABLE extracted_field FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON extracted_field USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER extracted_field_immutable BEFORE UPDATE OR DELETE ON extracted_field FOR EACH ROW EXECUTE FUNCTION taktovia.reject_mutation();
