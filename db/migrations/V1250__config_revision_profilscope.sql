-- Integration WP-M1-20 (Revisionslebenszyklus) und WP-M1-24 (Unternehmensprofil als Konfiguration, PA-11): Das Profil liegt als eigener Scope
-- `TENANT_PROFILE` in `config_revision`. Es hat ein eigenes Schema (`tenant-profile.v1`, geprüft vom Dienst gegen
-- docs/umsetzungsplan/vertraege/schemas/tenant-profile.v1.schema.json) und gehört nicht zum Parameterkatalog `policy_schema`.
-- Darum: scope_kind um TENANT_PROFILE erweitern, den Fremdschlüssel auf policy_schema_version durch eine bedingte Prüfung im Trigger ersetzen
-- (für alle anderen Scopes unverändert: unbekannte Schema-Version -> Prüfungsverletzung 23514) und die Katalogprüfung der Werte für das Profil auslassen.
SET search_path = taktovia, public;

ALTER TABLE config_revision DROP CONSTRAINT config_revision_scope_kind_valid;
ALTER TABLE config_revision ADD CONSTRAINT config_revision_scope_kind_valid
  CHECK (scope_kind IN ('TENANT', 'REGION', 'DEPOT', 'SERVICE', 'TENANT_PROFILE'));
ALTER TABLE config_revision DROP CONSTRAINT config_revision_schema_version_fk;

CREATE OR REPLACE FUNCTION config_revision_validate() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE bad record;
BEGIN
  IF NEW.scope_kind = 'TENANT_PROFILE' THEN
    IF NEW.schema_version IS DISTINCT FROM 'tenant-profile.v1' THEN
      RAISE EXCEPTION 'config_revision: Profil verlangt Schema tenant-profile.v1' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM policy_schema_version v WHERE v.schema_version = NEW.schema_version) THEN
    RAISE EXCEPTION 'config_revision: unbekannte Schema-Version %', NEW.schema_version USING ERRCODE = '23514';
  END IF;
  SELECT v.pointer, v.code INTO bad FROM config_value_violations(NEW.scope_kind, NEW.schema_version, NEW.values_json) v LIMIT 1;
  IF FOUND THEN RAISE EXCEPTION 'policy_schema violation at % (%)', bad.pointer, bad.code USING ERRCODE = '23514'; END IF;
  RETURN NEW;
END $$;
