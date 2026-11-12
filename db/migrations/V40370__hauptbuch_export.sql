-- WP-M4-37: ERP-Export als wiederholsicherer Job (Modul documents-finance).
-- Exportbatch (`ledger_export`, Inhalt und Hash unveränderlich nach der Bereitstellung), Batchzeilen (`ledger_export_line`: je Journal genau ein Batch, Bestätigungs-ID je Zeile) und der Exportstatus am Beleg
-- (NOT_EXPORTED -> EXPORTED = bereitgestellt -> CONFIRMED = Import bestätigt), den nur der Exportjob setzen kann (transaktionslokaler Schalter `app.export_job`, wie `app.roster_draft_access`).
SET search_path = taktovia, public;

ALTER TABLE expense_document DROP CONSTRAINT expense_document_export_state, DROP CONSTRAINT expense_document_exported_pair;
ALTER TABLE expense_document
  ADD CONSTRAINT expense_document_export_state CHECK (export_state IN ('NOT_EXPORTED', 'EXPORTED', 'CONFIRMED')),
  ADD CONSTRAINT expense_document_exported_pair CHECK ((export_state <> 'NOT_EXPORTED') = (exported_at IS NOT NULL AND exported_by IS NOT NULL)),
  ADD COLUMN export_confirmed_at timestamptz,
  ADD CONSTRAINT expense_document_export_confirmed_pair CHECK ((export_state = 'CONFIRMED') = (export_confirmed_at IS NOT NULL));

CREATE OR REPLACE FUNCTION expense_document_export_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.export_state IS DISTINCT FROM OLD.export_state THEN
    IF coalesce(current_setting('app.export_job', true), '') <> 'on' THEN RAISE EXCEPTION 'expense_document: den Exportstatus setzt nur der Exportjob' USING ERRCODE = '23514'; END IF;
    IF NEW.state <> 'POSTED' THEN RAISE EXCEPTION 'expense_document: Export nur fuer gebuchte Belege (Zustand %)', NEW.state USING ERRCODE = '23514'; END IF;
    IF NOT ((OLD.export_state = 'NOT_EXPORTED' AND NEW.export_state = 'EXPORTED') OR (OLD.export_state = 'EXPORTED' AND NEW.export_state = 'CONFIRMED')) THEN
      RAISE EXCEPTION 'expense_document: Exportstatus % -> % ist nicht erlaubt', OLD.export_state, NEW.export_state USING ERRCODE = '23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;

CREATE TABLE ledger_export (
  tenant_id        uuid        NOT NULL REFERENCES tenant(id),
  id               uuid        NOT NULL,
  legal_entity_id  uuid        NOT NULL,
  target_system    text        NOT NULL CHECK (target_system ~ '^[A-Z][A-Z0-9_]{0,39}$'),
  period_from      date        NOT NULL,
  period_to        date        NOT NULL,
  state            text        NOT NULL DEFAULT 'REQUESTED' CONSTRAINT ledger_export_state CHECK (state IN ('REQUESTED', 'PROVIDED', 'CONFIRMED', 'EMPTY', 'FAILED')),
  operation_id     uuid        NOT NULL,
  batch_ref        text        CHECK (batch_ref ~ '^[A-Z0-9-]{8,40}$'),
  format_name      text        CHECK (length(format_name) BETWEEN 1 AND 60),
  format_version   text        CHECK (length(format_version) BETWEEN 1 AND 20),
  media_type       text        CHECK (length(media_type) BETWEEN 3 AND 100),
  content          bytea,
  content_sha256   char(64)    CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
  content_size     integer     CHECK (content_size >= 0),
  journal_count    integer     CHECK (journal_count >= 0),
  result_code      text        CHECK (result_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
  requested_by     text        NOT NULL CHECK (length(requested_by) BETWEEN 1 AND 200),
  created_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  provided_at      timestamptz,
  confirmed_at     timestamptz,
  confirmed_by     text        CHECK (length(confirmed_by) BETWEEN 1 AND 200),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, operation_id),
  FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES legal_entity(tenant_id, id),
  FOREIGN KEY (tenant_id, operation_id) REFERENCES operation(tenant_id, id),
  CONSTRAINT ledger_export_period CHECK (period_to > period_from),
  CONSTRAINT ledger_export_provided_fields CHECK ((state IN ('PROVIDED', 'CONFIRMED')) = (batch_ref IS NOT NULL AND content IS NOT NULL AND content_sha256 IS NOT NULL AND journal_count IS NOT NULL AND provided_at IS NOT NULL
    AND format_name IS NOT NULL AND format_version IS NOT NULL AND media_type IS NOT NULL AND content_size IS NOT NULL)),
  CONSTRAINT ledger_export_confirmed_fields CHECK ((state = 'CONFIRMED') = (confirmed_at IS NOT NULL AND confirmed_by IS NOT NULL))
);
-- Höchstens ein offener Auftrag je Umfang: die Wiederholung derselben Anforderung liefert denselben Auftrag.
CREATE UNIQUE INDEX ledger_export_one_open ON ledger_export (tenant_id, legal_entity_id, target_system, period_from, period_to) WHERE state = 'REQUESTED';
CREATE UNIQUE INDEX ledger_export_batch_ref ON ledger_export (tenant_id, batch_ref) WHERE batch_ref IS NOT NULL;
CREATE INDEX ledger_export_by_entity ON ledger_export (tenant_id, legal_entity_id, created_at);

CREATE TABLE ledger_export_line (
  tenant_id     uuid        NOT NULL REFERENCES tenant(id),
  journal_id    uuid        NOT NULL,
  export_id     uuid        NOT NULL,
  document_id   uuid        NOT NULL,
  import_ref    text        CHECK (import_ref ~ '^[A-Za-z0-9._:/-]{1,100}$'),
  confirmed_at  timestamptz,
  -- ein Journal erscheint in genau einem Batch
  PRIMARY KEY (tenant_id, journal_id),
  FOREIGN KEY (tenant_id, export_id) REFERENCES ledger_export(tenant_id, id),
  FOREIGN KEY (tenant_id, journal_id) REFERENCES journal(tenant_id, id),
  FOREIGN KEY (tenant_id, document_id) REFERENCES expense_document(tenant_id, id),
  CONSTRAINT ledger_export_line_confirmed_pair CHECK ((import_ref IS NULL) = (confirmed_at IS NULL))
);
CREATE INDEX ledger_export_line_by_export ON ledger_export_line (tenant_id, export_id);
-- Eine Bestätigungs-ID kennzeichnet höchstens ein Journal je Rechtsträger-Import (Eindeutigkeit je Batch).
CREATE UNIQUE INDEX ledger_export_line_import_ref ON ledger_export_line (tenant_id, export_id, import_ref) WHERE import_ref IS NOT NULL;

DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['ledger_export', 'ledger_export_line'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant())', t);
  END LOOP;
END $$;
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON ledger_export FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change();
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON ledger_export_line FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change();

-- Batch: nie löschen; Zustände REQUESTED -> PROVIDED | EMPTY | FAILED, PROVIDED -> CONFIRMED, sonst endgültig; Inhalt, Hash und Kennung sind ab der Bereitstellung unveränderlich.
CREATE FUNCTION ledger_export_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'ledger_export: ein Exportbatch wird nie geloescht' USING ERRCODE = '23514'; END IF;
  IF (NEW.id, NEW.legal_entity_id, NEW.target_system, NEW.period_from, NEW.period_to, NEW.operation_id, NEW.requested_by, NEW.created_at)
     IS DISTINCT FROM (OLD.id, OLD.legal_entity_id, OLD.target_system, OLD.period_from, OLD.period_to, OLD.operation_id, OLD.requested_by, OLD.created_at) THEN
    RAISE EXCEPTION 'ledger_export: Umfang und Auftrag sind unveraenderlich' USING ERRCODE = '23514';
  END IF;
  IF OLD.state IN ('PROVIDED', 'CONFIRMED') AND (NEW.batch_ref, NEW.content, NEW.content_sha256, NEW.content_size, NEW.journal_count, NEW.format_name, NEW.format_version, NEW.media_type, NEW.provided_at)
     IS DISTINCT FROM (OLD.batch_ref, OLD.content, OLD.content_sha256, OLD.content_size, OLD.journal_count, OLD.format_name, OLD.format_version, OLD.media_type, OLD.provided_at) THEN
    RAISE EXCEPTION 'ledger_export: bereitgestellter Inhalt und Hash sind unveraenderlich' USING ERRCODE = '23514';
  END IF;
  IF NEW.state IS DISTINCT FROM OLD.state AND NOT ((OLD.state = 'REQUESTED' AND NEW.state IN ('PROVIDED', 'EMPTY', 'FAILED')) OR (OLD.state = 'PROVIDED' AND NEW.state = 'CONFIRMED')) THEN
    RAISE EXCEPTION 'ledger_export: Zustand % -> % ist nicht erlaubt', OLD.state, NEW.state USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER ledger_export_guard BEFORE UPDATE OR DELETE ON ledger_export FOR EACH ROW EXECUTE FUNCTION ledger_export_guard();

-- Zeile: nur POSTED-Journale des Rechtsträgers im Zeitraum, deren Beleg gebucht, freigegeben (Budgetfreigabe vorhanden) und nicht schon in einem Batch ist; angelegt nur, solange der Batch offen (REQUESTED) ist;
-- danach ändert sich nur die Bestätigungs-ID (einmal, NULL -> Wert); nie löschen.
CREATE FUNCTION ledger_export_line_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE x ledger_export%ROWTYPE; j journal%ROWTYPE; doc_ok boolean;
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'ledger_export_line: Batchzeilen werden nie geloescht' USING ERRCODE = '23514'; END IF;
  IF TG_OP = 'UPDATE' THEN
    IF (NEW.journal_id, NEW.export_id, NEW.document_id) IS DISTINCT FROM (OLD.journal_id, OLD.export_id, OLD.document_id) OR (OLD.import_ref IS NOT NULL AND NEW.import_ref IS DISTINCT FROM OLD.import_ref) THEN
      RAISE EXCEPTION 'ledger_export_line: nur die Bestaetigungs-ID wird einmal gesetzt' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
  END IF;
  SELECT * INTO x FROM ledger_export WHERE tenant_id = NEW.tenant_id AND id = NEW.export_id FOR SHARE;
  IF NOT FOUND THEN RETURN NEW; END IF;   -- der Fremdschluessel meldet den fehlenden Batch
  IF x.state <> 'REQUESTED' THEN RAISE EXCEPTION 'ledger_export_line: dem Batch (Zustand %) werden keine Zeilen mehr hinzugefuegt', x.state USING ERRCODE = '23514'; END IF;
  SELECT * INTO j FROM journal WHERE tenant_id = NEW.tenant_id AND id = NEW.journal_id;
  IF NOT FOUND THEN RETURN NEW; END IF;
  IF j.state <> 'POSTED' OR j.legal_entity_id <> x.legal_entity_id OR j.accounting_date < x.period_from OR j.accounting_date >= x.period_to THEN
    RAISE EXCEPTION 'ledger_export_line: nur gebuchte Journale des Rechtstraegers im Zeitraum des Batches' USING ERRCODE = '23514';
  END IF;
  SELECT EXISTS (SELECT 1 FROM economic_event e JOIN expense_document d ON d.tenant_id = e.tenant_id AND d.id = e.document_id
                  WHERE e.tenant_id = j.tenant_id AND e.id = j.event_id AND d.id = NEW.document_id AND d.state = 'POSTED' AND d.approved_by IS NOT NULL
                    AND EXISTS (SELECT 1 FROM approval_step s WHERE s.tenant_id = d.tenant_id AND s.document_id = d.id AND s.step = 'BUDGET' AND s.decision = 'APPROVED')) INTO doc_ok;
  IF NOT doc_ok THEN RAISE EXCEPTION 'ledger_export_line: der Beleg des Journals ist nicht vollstaendig freigegeben und gebucht' USING ERRCODE = '23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER ledger_export_line_guard BEFORE INSERT OR UPDATE OR DELETE ON ledger_export_line FOR EACH ROW EXECUTE FUNCTION ledger_export_line_guard();
