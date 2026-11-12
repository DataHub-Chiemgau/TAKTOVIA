-- WP-M4-21: Uploadticket, Quarantäne und Malware-Prüfung (Modul documents-finance). Dateien gelangen nur über ein autorisiertes, einmal verbrauchbares Ticket in die
-- Quarantäne; ein Beleg-Original entsteht erst nach bestandener Prüfung. Die fachliche Belegstruktur folgt mit WP-M4-22, Aufbewahrungsfristen mit M8 (D-11).
SET search_path = taktovia, public;

CREATE TABLE upload_ticket (
  tenant_id        uuid        NOT NULL REFERENCES tenant(id),
  id               uuid        NOT NULL,
  legal_entity_id  uuid        NOT NULL,
  purpose          text        NOT NULL CHECK (purpose IN ('RECEIPT', 'DAMAGE_PHOTO')),     -- DAMAGE_PHOTO: Zusage an M3 (Fotoevidenz Schadenmeldung, AT-33)
  declared_mime    text        NOT NULL CHECK (declared_mime IN ('image/jpeg', 'image/png', 'application/pdf')),
  declared_size    bigint      NOT NULL CHECK (declared_size > 0),
  declared_sha256  text        NOT NULL CHECK (declared_sha256 ~ '^[0-9a-f]{64}$'),
  quarantine_key   text        NOT NULL CHECK (quarantine_key ~ '^[A-Za-z0-9._/-]{1,200}$' AND quarantine_key !~ '(^|/)\.\.?(/|$)' AND quarantine_key !~ '//'),   -- Objektname im Quarantänepräfix (ohne Mandantenpräfix)
  state            text        NOT NULL DEFAULT 'ISSUED' CHECK (state IN ('ISSUED', 'COMPLETING', 'CONSUMED', 'REJECTED')),
  reject_reason    text        CHECK (reject_reason IS NULL OR reject_reason IN ('OBJECT_MISSING', 'SIZE_MISMATCH', 'HASH_MISMATCH', 'MIME_MISMATCH', 'SIZE_LIMIT')),
  issued_by        text        NOT NULL CHECK (length(issued_by) BETWEEN 1 AND 200),
  issued_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  expires_at       timestamptz NOT NULL,
  document_id      uuid,                                                               -- gesetzt mit CONSUMED
  revision         bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, quarantine_key),
  FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES legal_entity(tenant_id, id),
  CONSTRAINT upload_ticket_expiry CHECK (expires_at > issued_at),
  CONSTRAINT upload_ticket_consumed_has_document CHECK ((state = 'CONSUMED') = (document_id IS NOT NULL)),
  CONSTRAINT upload_ticket_rejected_has_reason CHECK ((state = 'REJECTED') = (reject_reason IS NOT NULL))
);
CREATE INDEX upload_ticket_by_expiry ON upload_ticket (tenant_id, expires_at) WHERE state IN ('ISSUED', 'COMPLETING');

-- Erweiterung expense_document (expand: nur nullbare Spalten; bestehende Zeilen bleiben unberührt)
ALTER TABLE expense_document
  ADD COLUMN upload_ticket_id uuid,
  ADD COLUMN size_bytes       bigint CHECK (size_bytes IS NULL OR size_bytes >= 0),
  ADD COLUMN scan_engine      text,
  ADD COLUMN scanned_at       timestamptz,
  ADD COLUMN scan_signature   text;
ALTER TABLE expense_document ADD CONSTRAINT expense_document_ticket_fk FOREIGN KEY (tenant_id, upload_ticket_id) REFERENCES upload_ticket(tenant_id, id);
CREATE UNIQUE INDEX expense_document_one_per_ticket ON expense_document (tenant_id, upload_ticket_id) WHERE upload_ticket_id IS NOT NULL;

DO $$ BEGIN
  ALTER TABLE taktovia.upload_ticket ENABLE ROW LEVEL SECURITY;
  ALTER TABLE taktovia.upload_ticket FORCE ROW LEVEL SECURITY;
  CREATE POLICY tenant_isolation ON taktovia.upload_ticket USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
  CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON taktovia.upload_ticket FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change();
END $$;

-- Ticket: Sachfelder unveränderlich; Zustand nur vorwärts ISSUED -> COMPLETING -> CONSUMED | REJECTED (endgültig); jede Änderung zählt die Revision.
CREATE FUNCTION upload_ticket_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.id IS DISTINCT FROM OLD.id OR NEW.legal_entity_id IS DISTINCT FROM OLD.legal_entity_id OR NEW.purpose IS DISTINCT FROM OLD.purpose
     OR NEW.declared_mime IS DISTINCT FROM OLD.declared_mime OR NEW.declared_size IS DISTINCT FROM OLD.declared_size OR NEW.declared_sha256 IS DISTINCT FROM OLD.declared_sha256
     OR NEW.quarantine_key IS DISTINCT FROM OLD.quarantine_key OR NEW.issued_by IS DISTINCT FROM OLD.issued_by OR NEW.issued_at IS DISTINCT FROM OLD.issued_at
     OR NEW.expires_at IS DISTINCT FROM OLD.expires_at THEN
    RAISE EXCEPTION 'upload_ticket: Sachfelder sind unveraenderlich' USING ERRCODE = '23514';
  END IF;
  IF NEW.state IS DISTINCT FROM OLD.state THEN
    IF NOT ((OLD.state = 'ISSUED' AND NEW.state = 'COMPLETING') OR (OLD.state = 'COMPLETING' AND NEW.state IN ('CONSUMED', 'REJECTED'))) THEN
      RAISE EXCEPTION 'upload_ticket: Zustandsuebergang % -> % nicht erlaubt', OLD.state, NEW.state USING ERRCODE = '23514';
    END IF;
    NEW.revision := OLD.revision + 1;
  ELSIF NEW IS DISTINCT FROM OLD THEN
    RAISE EXCEPTION 'upload_ticket: Aenderung ohne Zustandswechsel nicht erlaubt' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER upload_ticket_guard BEFORE UPDATE ON upload_ticket FOR EACH ROW EXECUTE FUNCTION upload_ticket_guard();

-- Original: ein über ein Ticket erfasstes Original (Ticketbezug gesetzt) ist unveränderlich (Schlüssel, Hash, MIME, Größe, Urheber, Eingangszeit); aus der Quarantäne
-- führt der Weg nur zu STORED (Prüfung bestanden) oder QUARANTINE_REJECTED (endgültig, nie freigegeben). Spätere Belegzustände (WP-M4-22 ff.) beginnen bei STORED.
CREATE FUNCTION expense_document_original_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.upload_ticket_id IS DISTINCT FROM OLD.upload_ticket_id THEN
    RAISE EXCEPTION 'expense_document: Ticketbezug ist unveraenderlich' USING ERRCODE = '23514';
  END IF;
  IF OLD.upload_ticket_id IS NULL THEN RETURN NEW; END IF;
  IF NEW.id IS DISTINCT FROM OLD.id OR NEW.legal_entity_id IS DISTINCT FROM OLD.legal_entity_id OR NEW.object_key IS DISTINCT FROM OLD.object_key
     OR NEW.original_sha256 IS DISTINCT FROM OLD.original_sha256 OR NEW.mime IS DISTINCT FROM OLD.mime OR NEW.size_bytes IS DISTINCT FROM OLD.size_bytes
     OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.original_received_at IS DISTINCT FROM OLD.original_received_at THEN
    RAISE EXCEPTION 'expense_document: das Original ist unveraenderlich' USING ERRCODE = '23514';
  END IF;
  IF OLD.state = 'QUARANTINE_REJECTED' AND NEW IS DISTINCT FROM OLD THEN
    RAISE EXCEPTION 'expense_document: Ablehnung aus der Quarantaene ist endgueltig' USING ERRCODE = '23514';
  END IF;
  IF OLD.state = 'QUARANTINED' AND NEW.state NOT IN ('QUARANTINED', 'STORED', 'QUARANTINE_REJECTED') THEN
    RAISE EXCEPTION 'expense_document: aus der Quarantaene nur nach STORED oder QUARANTINE_REJECTED' USING ERRCODE = '23514';
  END IF;
  IF OLD.state <> 'QUARANTINED' AND (NEW.scan_engine IS DISTINCT FROM OLD.scan_engine OR NEW.scanned_at IS DISTINCT FROM OLD.scanned_at OR NEW.scan_signature IS DISTINCT FROM OLD.scan_signature) THEN
    RAISE EXCEPTION 'expense_document: Pruefergebnis ist unveraenderlich' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER expense_document_original_guard BEFORE UPDATE ON expense_document FOR EACH ROW EXECUTE FUNCTION expense_document_original_guard();
