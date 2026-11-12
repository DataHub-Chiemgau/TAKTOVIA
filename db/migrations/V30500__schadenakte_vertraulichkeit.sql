-- WP-M3-50 / LH-6.22-DMG-011: Vertraulichkeit der Schadenakte.
-- 1. Vertraulichkeitsstufe je Dokument (Evidenz): STANDARD (alle mit Aktenzugriff) oder CONFIDENTIAL (nur berechtigte Rollen und der Urheber). Die Stufe wird mit dem
--    Uploadticket festgelegt, ist danach unveränderlich und geht mit der Freigabe in die unveränderliche Evidenz über (additiv, expand/contract: Altbestand = STANDARD).
-- 2. Getrennter Bereich für personenbezogene Unfallangaben (`defect_personal_detail`, in M3 abgegrenzt und standardmäßig leer; Datenmodell im Detail offen, OP-23). Die Runtime-Rolle
--    hat darauf KEIN Recht, nur der Modulzugang `taktovia_fields` (siehe R__runtime_grants_m3_50.sql). `defect_case` bleibt ohne Gesundheitsfelder (Schematest).
SET search_path = taktovia, public;

ALTER TABLE defect_evidence_upload
  ADD COLUMN confidentiality text NOT NULL DEFAULT 'STANDARD' CHECK (confidentiality IN ('STANDARD', 'CONFIDENTIAL'));
ALTER TABLE defect_evidence
  ADD COLUMN confidentiality text NOT NULL DEFAULT 'STANDARD' CHECK (confidentiality IN ('STANDARD', 'CONFIDENTIAL'));
CREATE INDEX defect_evidence_confidential ON defect_evidence (tenant_id, defect_id) WHERE confidentiality = 'CONFIDENTIAL';

-- Wächter des Uploadtickets (V30440) um die Vertraulichkeitsstufe ergänzt: Sachfeld, nach dem Ausstellen unveränderlich.
CREATE OR REPLACE FUNCTION defect_evidence_upload_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'defect_evidence_upload: kein Loeschen' USING ERRCODE = '23514'; END IF;
  IF NEW.id IS DISTINCT FROM OLD.id OR NEW.uploaded_by IS DISTINCT FROM OLD.uploaded_by OR NEW.declared_mime IS DISTINCT FROM OLD.declared_mime
     OR NEW.declared_size IS DISTINCT FROM OLD.declared_size OR NEW.declared_sha256 IS DISTINCT FROM OLD.declared_sha256 OR NEW.captured_at IS DISTINCT FROM OLD.captured_at
     OR NEW.quarantine_key IS DISTINCT FROM OLD.quarantine_key OR NEW.expires_at IS DISTINCT FROM OLD.expires_at OR NEW.created_at IS DISTINCT FROM OLD.created_at
     OR NEW.confidentiality IS DISTINCT FROM OLD.confidentiality THEN
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

-- Getrennter Bereich: personenbezogene Unfallangaben (Beteiligte, Zeugen, Aussagen). Nur anhängen (Korrektur = neue Zeile); Löschung nach Aufbewahrungsfrist ist ein Betriebsauftrag (D-11).
CREATE TABLE defect_personal_detail (
  tenant_id   uuid        NOT NULL REFERENCES tenant(id),
  id          uuid        NOT NULL,
  defect_id   uuid        NOT NULL,
  detail_kind text        NOT NULL CHECK (detail_kind IN ('INVOLVED_PARTY', 'WITNESS', 'STATEMENT')),
  content     text        NOT NULL CHECK (length(content) BETWEEN 1 AND 4000),
  recorded_by text        NOT NULL CHECK (recorded_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  revision    bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, defect_id) REFERENCES defect_case(tenant_id, id)
);
CREATE INDEX defect_personal_detail_by_defect ON defect_personal_detail (tenant_id, defect_id);
ALTER TABLE defect_personal_detail ENABLE ROW LEVEL SECURITY;
ALTER TABLE defect_personal_detail FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON defect_personal_detail USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON defect_personal_detail FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE TRIGGER defect_personal_detail_immutable BEFORE UPDATE OR DELETE ON defect_personal_detail FOR EACH ROW EXECUTE FUNCTION reject_mutation();
