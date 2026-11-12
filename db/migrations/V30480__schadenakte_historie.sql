-- WP-M3-48 / LH-6.22-DMG-008: Unveränderliche Historie von Meldungen und Anhängen. Jede Fassung des Meldungsinhalts steht als Zeile in `defect_case_revision` (append-only, Version fortlaufend,
-- Autor, Zeit, Änderungsgrund Pflicht); `defect_case` trägt die aktuelle Fassung und lässt eine Inhaltsänderung nur zu, wenn die neue Version bereits als Revisionszeile vorliegt. Identität
-- (Fallnummer, Fahrzeug, Meldender, Meldezeit) ist unveränderlich, die Akte nicht löschbar. Ein ersetzter Anhang bleibt als frühere Version erhalten (die Folgeversion verweist auf den Vorgänger).
-- Löschen nur über den kontrollierten Lifecycleprozess (WP-M1-33, D-11), nie per DELETE.
SET search_path = taktovia, public;

CREATE TABLE defect_case_revision (
  tenant_id     uuid        NOT NULL REFERENCES tenant(id),
  id            uuid        NOT NULL,
  defect_id     uuid        NOT NULL,
  version       int         NOT NULL CHECK (version >= 1),
  snapshot      jsonb       NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
  author        text        NOT NULL CHECK (author ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  change_reason text        NOT NULL CHECK (length(btrim(change_reason)) BETWEEN 1 AND 500),
  created_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, defect_id, version),
  FOREIGN KEY (tenant_id, defect_id) REFERENCES defect_case(tenant_id, id)
);
ALTER TABLE defect_case_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE defect_case_revision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON defect_case_revision USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON defect_case_revision FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE TRIGGER defect_case_revision_immutable BEFORE UPDATE OR DELETE ON defect_case_revision FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- Anhänge: Folgeversion verweist auf den Vorgänger; Version der Akte, mit der der Anhang hinzukam. Ein Anhang wird höchstens einmal ersetzt.
ALTER TABLE defect_evidence
  ADD COLUMN replaces_evidence_id uuid,
  ADD COLUMN defect_version int NOT NULL DEFAULT 1 CHECK (defect_version >= 1),
  ADD CONSTRAINT defect_evidence_replaces_fk FOREIGN KEY (tenant_id, replaces_evidence_id) REFERENCES defect_evidence(tenant_id, id),
  ADD CONSTRAINT defect_evidence_not_self_replacing CHECK (replaces_evidence_id IS NULL OR replaces_evidence_id <> id);
CREATE UNIQUE INDEX defect_evidence_replaced_once ON defect_evidence (tenant_id, replaces_evidence_id) WHERE replaces_evidence_id IS NOT NULL;

-- Das Uploadticket merkt sich Version und Vorgänger zum Zeitpunkt der Bindung (die Freigabe kann später folgen); beides wird zusammen mit `defect_id` einmal gesetzt.
ALTER TABLE defect_evidence_upload
  ADD COLUMN attached_in_version int CHECK (attached_in_version IS NULL OR attached_in_version >= 1),
  ADD COLUMN replaces_evidence_id uuid,
  ADD CONSTRAINT defect_upload_replaces_fk FOREIGN KEY (tenant_id, replaces_evidence_id) REFERENCES defect_evidence(tenant_id, id);
CREATE OR REPLACE FUNCTION defect_evidence_upload_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'defect_evidence_upload: kein Loeschen' USING ERRCODE = '23514'; END IF;
  IF NEW.id IS DISTINCT FROM OLD.id OR NEW.uploaded_by IS DISTINCT FROM OLD.uploaded_by OR NEW.declared_mime IS DISTINCT FROM OLD.declared_mime
     OR NEW.declared_size IS DISTINCT FROM OLD.declared_size OR NEW.declared_sha256 IS DISTINCT FROM OLD.declared_sha256 OR NEW.captured_at IS DISTINCT FROM OLD.captured_at
     OR NEW.quarantine_key IS DISTINCT FROM OLD.quarantine_key OR NEW.expires_at IS DISTINCT FROM OLD.expires_at OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'defect_evidence_upload: Sachfelder sind unveraenderlich' USING ERRCODE = '23514';
  END IF;
  IF OLD.defect_id IS NOT NULL AND (NEW.defect_id IS DISTINCT FROM OLD.defect_id OR NEW.attached_in_version IS DISTINCT FROM OLD.attached_in_version
     OR NEW.replaces_evidence_id IS DISTINCT FROM OLD.replaces_evidence_id) THEN
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

-- Meldung unveränderlich historisiert: Identität nie ändern; Inhalt (und die Inhaltsversion) nur mit der zugehörigen Revisionszeile; kein Löschen.
CREATE FUNCTION defect_case_content_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE content_changed boolean;
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'defect_case: Meldung nicht loeschbar (Historie, Aufbewahrung nur ueber den Lifecycleprozess)' USING ERRCODE = '23514'; END IF;
  IF NEW.case_number IS DISTINCT FROM OLD.case_number OR NEW.vehicle_id IS DISTINCT FROM OLD.vehicle_id OR NEW.reported_by IS DISTINCT FROM OLD.reported_by
     OR NEW.reported_at IS DISTINCT FROM OLD.reported_at OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at
     OR NEW.device_event_id IS DISTINCT FROM OLD.device_event_id THEN
    RAISE EXCEPTION 'defect_case: Identitaet der Meldung ist unveraenderlich' USING ERRCODE = '23514';
  END IF;
  content_changed := NEW.occurred_at IS DISTINCT FROM OLD.occurred_at OR NEW.location_kind IS DISTINCT FROM OLD.location_kind OR NEW.location_value IS DISTINCT FROM OLD.location_value
     OR NEW.category_code IS DISTINCT FROM OLD.category_code OR NEW.kind IS DISTINCT FROM OLD.kind OR NEW.vehicle_area IS DISTINCT FROM OLD.vehicle_area
     OR NEW.observed_effect IS DISTINCT FROM OLD.observed_effect OR NEW.description IS DISTINCT FROM OLD.description OR NEW.source_inspection_id IS DISTINCT FROM OLD.source_inspection_id
     OR NEW.source_step_code IS DISTINCT FROM OLD.source_step_code;
  IF NEW.content_version IS DISTINCT FROM OLD.content_version THEN
    IF NEW.content_version <> OLD.content_version + 1 OR NOT EXISTS (
         SELECT 1 FROM defect_case_revision r WHERE r.tenant_id = NEW.tenant_id AND r.defect_id = NEW.id AND r.version = NEW.content_version) THEN
      RAISE EXCEPTION 'defect_case: neue Inhaltsversion nur mit Revisionszeile (Autor, Zeit, Grund)' USING ERRCODE = '23514';
    END IF;
  ELSIF content_changed THEN
    RAISE EXCEPTION 'defect_case: Inhalt der Meldung nur per Nachtrag (neue Version) aenderbar' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER defect_case_content_guard BEFORE UPDATE OR DELETE ON defect_case FOR EACH ROW EXECUTE FUNCTION defect_case_content_guard();
