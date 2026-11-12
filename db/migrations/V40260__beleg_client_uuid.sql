-- WP-M4-26 (LH-I.1-DOC-013): Offline-Belegerfassung mit einmaliger Synchronisation (Modul documents-finance).
-- Die Fahrer-App erzeugt die stabile Client-UUID des Entwurfs VOR dem Upload; die Anlage ist darüber idempotent: `UNIQUE (tenant_id, client_uuid)` am Beleg. Die Erfassungszeit am Gerät
-- (`captured_at`) steht getrennt von der Eingangszeit am Server (`original_received_at`, V1). Beides ist nach der Anlage unveränderlich (zusätzlich zum Original-Wächter aus V40210).
-- expand: bestehende Belege erhalten `client_uuid = id` (deterministisch, ohne Zufall); neue Belege ohne Angabe (Web-Upload, E-Mail, Import) erhalten ebenfalls `client_uuid = id`.
SET search_path = taktovia, public;

ALTER TABLE expense_document
  ADD COLUMN client_uuid uuid,
  ADD COLUMN captured_at timestamptz;
UPDATE expense_document SET client_uuid = id WHERE client_uuid IS NULL;
ALTER TABLE expense_document ALTER COLUMN client_uuid SET NOT NULL;
ALTER TABLE expense_document ADD CONSTRAINT expense_document_client_uuid_unique UNIQUE (tenant_id, client_uuid);

CREATE FUNCTION expense_document_client_uuid_default() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.client_uuid IS NULL THEN NEW.client_uuid := NEW.id; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER expense_document_client_uuid_default BEFORE INSERT ON expense_document FOR EACH ROW EXECUTE FUNCTION expense_document_client_uuid_default();

CREATE FUNCTION expense_document_capture_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.client_uuid IS DISTINCT FROM OLD.client_uuid OR NEW.captured_at IS DISTINCT FROM OLD.captured_at THEN
    RAISE EXCEPTION 'expense_document: Client-UUID und Erfassungszeit sind unveraenderlich' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER expense_document_capture_guard BEFORE UPDATE ON expense_document FOR EACH ROW EXECUTE FUNCTION expense_document_capture_guard();

-- Ticket: dieselbe Client-UUID erlaubt die Wiederaufnahme eines abgebrochenen Uploads (gleiches Ticket, solange gültig) und die Erkennung eines bereits angelegten Belegs.
ALTER TABLE upload_ticket
  ADD COLUMN client_uuid uuid,
  ADD COLUMN captured_at timestamptz;
CREATE INDEX upload_ticket_by_client_uuid ON upload_ticket (tenant_id, client_uuid, issued_at) WHERE client_uuid IS NOT NULL;

-- Ein Ticket kann als Dublette enden: zwei Tickets derselben Client-UUID (z. B. nach Ablauf des ersten), das zweite findet beim Abschluss den Beleg schon vor.
ALTER TABLE upload_ticket DROP CONSTRAINT upload_ticket_reject_reason_check;
ALTER TABLE upload_ticket ADD CONSTRAINT upload_ticket_reject_reason_check
  CHECK (reject_reason IS NULL OR reject_reason IN ('OBJECT_MISSING', 'SIZE_MISMATCH', 'HASH_MISMATCH', 'MIME_MISMATCH', 'SIZE_LIMIT', 'DUPLICATE'));

CREATE FUNCTION upload_ticket_capture_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.client_uuid IS DISTINCT FROM OLD.client_uuid OR NEW.captured_at IS DISTINCT FROM OLD.captured_at THEN
    RAISE EXCEPTION 'upload_ticket: Client-UUID und Erfassungszeit sind unveraenderlich' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER upload_ticket_capture_guard BEFORE UPDATE ON upload_ticket FOR EACH ROW EXECUTE FUNCTION upload_ticket_capture_guard();
