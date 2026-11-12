-- WP-M3-44 / LH-6.22-DMG-004: Fotoevidenz der Schadenakte. Uploadticket mit Quarantäne und Medienprüfung (Staging, veränderlich) und die unveränderliche Evidenz
-- (`defect_evidence`, V3020) erst NACH bestandener Prüfung: ein Foto in Prüfung ist an der Akte weder sichtbar noch abrufbar. Objekte liegen unter dem Mandantenpräfix
-- des Objektspeichers (`tenants/<mandant>/…`); Aufbewahrung der Schadensbilder: D-11 (blockiert nur die Produktivschaltung).
SET search_path = taktovia, public;

CREATE TABLE defect_evidence_upload (
  tenant_id            uuid        NOT NULL REFERENCES tenant(id),
  id                   uuid        NOT NULL,
  uploaded_by          text        NOT NULL CHECK (uploaded_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  declared_mime        text        NOT NULL CHECK (declared_mime IN ('image/jpeg', 'image/png')),
  declared_size        bigint      NOT NULL CHECK (declared_size BETWEEN 1 AND 10485760),
  declared_sha256      text        NOT NULL CHECK (declared_sha256 ~ '^[0-9a-f]{64}$'),
  captured_at          timestamptz NOT NULL,
  quarantine_key       text        NOT NULL CHECK (quarantine_key ~ '^[A-Za-z0-9._/-]{1,200}$' AND quarantine_key !~ '(^|/)\.\.?(/|$)' AND quarantine_key !~ '//'),
  state                text        NOT NULL DEFAULT 'ISSUED' CHECK (state IN ('ISSUED', 'IN_REVIEW', 'RELEASED', 'REJECTED')),
  reject_reason        text        CHECK (reject_reason IS NULL OR reject_reason IN ('EXPIRED', 'OBJECT_MISSING', 'SIZE_MISMATCH', 'HASH_MISMATCH', 'MIME_MISMATCH', 'SIZE_LIMIT', 'MALWARE', 'MEDIA_INVALID')),
  object_key           text        CHECK (object_key IS NULL OR object_key ~ '^[A-Za-z0-9._/-]{1,200}$'),
  stored_sha256        text        CHECK (stored_sha256 IS NULL OR stored_sha256 ~ '^[0-9a-f]{64}$'),
  stored_size          bigint      CHECK (stored_size IS NULL OR stored_size > 0),
  review_engine        text        CHECK (review_engine IS NULL OR length(review_engine) <= 60),
  reviewed_at          timestamptz,
  defect_id            uuid,
  expires_at           timestamptz NOT NULL,
  revision             bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, quarantine_key),
  FOREIGN KEY (tenant_id, defect_id) REFERENCES defect_case(tenant_id, id),
  CONSTRAINT defect_upload_expiry CHECK (expires_at > created_at),
  CONSTRAINT defect_upload_rejected_has_reason CHECK ((state = 'REJECTED') = (reject_reason IS NOT NULL)),
  CONSTRAINT defect_upload_released_has_object CHECK ((state = 'RELEASED') = (object_key IS NOT NULL AND stored_sha256 IS NOT NULL AND stored_size IS NOT NULL))
);
CREATE INDEX defect_upload_by_review ON defect_evidence_upload (tenant_id, created_at) WHERE state = 'IN_REVIEW';
CREATE INDEX defect_upload_by_defect ON defect_evidence_upload (tenant_id, defect_id) WHERE defect_id IS NOT NULL;
-- Wiederholte Ticketanforderung derselben Datei (nach Zeitüberschreitung) liefert dasselbe Ticket: eine nicht abgewiesene Datei je Urheber
CREATE UNIQUE INDEX defect_upload_one_per_file ON defect_evidence_upload (tenant_id, uploaded_by, declared_sha256) WHERE state <> 'REJECTED';

ALTER TABLE defect_evidence_upload ENABLE ROW LEVEL SECURITY;
ALTER TABLE defect_evidence_upload FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON defect_evidence_upload USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON defect_evidence_upload FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();

-- Sachfelder unveränderlich; Zustand nur vorwärts ISSUED -> IN_REVIEW | REJECTED, IN_REVIEW -> RELEASED | REJECTED (REJECTED und RELEASED endgültig); die Bindung an eine Akte
-- wird einmal gesetzt und nie geändert; jede Änderung zählt die Revision.
CREATE FUNCTION defect_evidence_upload_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'defect_evidence_upload: kein Loeschen' USING ERRCODE = '23514'; END IF;
  IF NEW.id IS DISTINCT FROM OLD.id OR NEW.uploaded_by IS DISTINCT FROM OLD.uploaded_by OR NEW.declared_mime IS DISTINCT FROM OLD.declared_mime
     OR NEW.declared_size IS DISTINCT FROM OLD.declared_size OR NEW.declared_sha256 IS DISTINCT FROM OLD.declared_sha256 OR NEW.captured_at IS DISTINCT FROM OLD.captured_at
     OR NEW.quarantine_key IS DISTINCT FROM OLD.quarantine_key OR NEW.expires_at IS DISTINCT FROM OLD.expires_at OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'defect_evidence_upload: Sachfelder sind unveraenderlich' USING ERRCODE = '23514';
  END IF;
  IF OLD.defect_id IS NOT NULL AND NEW.defect_id IS DISTINCT FROM OLD.defect_id THEN
    RAISE EXCEPTION 'defect_evidence_upload: die Bindung an die Akte ist endgueltig' USING ERRCODE = '23514';
  END IF;
  IF NEW.state IS DISTINCT FROM OLD.state THEN
    IF NOT ((OLD.state = 'ISSUED' AND NEW.state IN ('IN_REVIEW', 'REJECTED')) OR (OLD.state = 'IN_REVIEW' AND NEW.state IN ('RELEASED', 'REJECTED'))) THEN
      RAISE EXCEPTION 'defect_evidence_upload: Zustandsuebergang % -> % nicht erlaubt', OLD.state, NEW.state USING ERRCODE = '23514';
    END IF;
  ELSIF OLD.state IN ('RELEASED', 'REJECTED') AND (NEW.object_key IS DISTINCT FROM OLD.object_key OR NEW.stored_sha256 IS DISTINCT FROM OLD.stored_sha256 OR NEW.reject_reason IS DISTINCT FROM OLD.reject_reason) THEN
    RAISE EXCEPTION 'defect_evidence_upload: Pruefergebnis ist endgueltig' USING ERRCODE = '23514';
  END IF;
  IF NEW IS DISTINCT FROM OLD THEN NEW.revision := OLD.revision + 1; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER defect_evidence_upload_guard BEFORE UPDATE OR DELETE ON defect_evidence_upload FOR EACH ROW EXECUTE FUNCTION defect_evidence_upload_guard();

-- Evidenz (unveränderlich, V3020): zusätzliche Angaben des geprüften Objekts. Eine Datei ist je Akte höchstens einmal Evidenz (Dublettenschutz nach Wiederholung).
ALTER TABLE defect_evidence
  ADD COLUMN mime            text   CHECK (mime IS NULL OR mime IN ('image/jpeg', 'image/png')),
  ADD COLUMN size_bytes      bigint CHECK (size_bytes IS NULL OR size_bytes > 0),
  ADD COLUMN original_sha256 text   CHECK (original_sha256 IS NULL OR original_sha256 ~ '^[0-9a-f]{64}$'),
  ADD COLUMN upload_id       uuid,
  ADD CONSTRAINT defect_evidence_upload_fk FOREIGN KEY (tenant_id, upload_id) REFERENCES defect_evidence_upload(tenant_id, id);
CREATE UNIQUE INDEX defect_evidence_one_per_file ON defect_evidence (tenant_id, defect_id, sha256);
CREATE UNIQUE INDEX defect_evidence_one_per_upload ON defect_evidence (tenant_id, upload_id) WHERE upload_id IS NOT NULL;
