-- WP-M4-61 / LH-I.3-ETA-001: ETA-Grundmodell, Revision des fahrerneutralen Referenzmodells (Modul booking-dispatch, Aufrufer ist die Disposition; Modulzuordnung OP-34).
-- `eta_model_revision` ist je Mandant eine Folge von Modellrevisionen mit Parametern und (optional) festgelegter Strassendatenversion. Es gibt bewusst KEINE Fahrerspalte und keinen
-- Fahrerbezug (D-10: P1 ohne individuelles Fahrerprofil). Eine freigegebene Revision (state APPROVED) ist unveraenderlich und nicht loeschbar (auch fuer den Migrator); Aenderungen
-- sind eine neue Revision. Entwurfszeilen duerfen bis zur Freigabe geaendert werden. Der Schreibweg (Anlage/Freigabe) ist in P1 dem Betrieb vorbehalten (keine Route, keine
-- Runtime-Schreibrechte, siehe R__runtime_grants_m4_61.sql); die Revision selbst ist der Nachweis (created_by/approved_by, Zeitstempel).
-- `parameters` traegt die Planungswerte des Modells (z. B. default_stop_dwell_sec, default_wheelchair_boarding_sec, WP-M4-62); die Personalisierung ist in P1 nicht aktivierbar:
-- ein Schluessel eta_personalization_enabled mit Wert ungleich false wird abgewiesen.
SET search_path = taktovia, public;

CREATE TABLE eta_model_revision (
  tenant_id         uuid        NOT NULL REFERENCES tenant(id),
  id                uuid        NOT NULL,
  version           integer     NOT NULL CHECK (version >= 1),
  parameters        jsonb       NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(parameters) = 'object'),
  -- festgelegte Strassendatenversion der Freigabe; NULL = jede vom Routingadapter gemeldete Version ist zulaessig (die Prognose traegt die tatsaechlich verwendete)
  road_data_version text        CHECK (road_data_version IS NULL OR length(road_data_version) BETWEEN 1 AND 100),
  state             text        NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT', 'APPROVED')),
  approved_at       timestamptz,
  approved_by       text        CHECK (approved_by IS NULL OR approved_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_by        text        NOT NULL CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, version),
  CONSTRAINT eta_model_revision_approval_pair CHECK ((state = 'APPROVED') = (approved_at IS NOT NULL AND approved_by IS NOT NULL)),
  CONSTRAINT eta_model_revision_no_personalization CHECK (NOT (parameters ? 'eta_personalization_enabled') OR parameters -> 'eta_personalization_enabled' = 'false'::jsonb)
);
ALTER TABLE eta_model_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE eta_model_revision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON eta_model_revision USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE INDEX eta_model_revision_current ON eta_model_revision (tenant_id, version DESC) WHERE state = 'APPROVED';

CREATE FUNCTION eta_model_revision_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.state = 'APPROVED' THEN RAISE EXCEPTION 'eta_model_revision: freigegebene Revision ist nicht loeschbar' USING ERRCODE = '23514'; END IF;
    RETURN OLD;
  END IF;
  IF OLD.state = 'APPROVED' THEN RAISE EXCEPTION 'eta_model_revision: freigegebene Revision ist unveraenderlich (neue Revision anlegen)' USING ERRCODE = '23514'; END IF;
  IF NEW.id <> OLD.id OR NEW.version <> OLD.version OR NEW.created_by <> OLD.created_by OR NEW.created_at <> OLD.created_at THEN
    RAISE EXCEPTION 'eta_model_revision: Kennung, Version und Urheber sind unveraenderlich' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER eta_model_revision_guard BEFORE UPDATE OR DELETE ON eta_model_revision FOR EACH ROW EXECUTE FUNCTION eta_model_revision_guard();
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON eta_model_revision FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
