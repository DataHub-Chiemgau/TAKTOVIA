-- WP-M1-21 / LH-I.7-CFG-005: Präzedenzkette global -> Mandant -> Region -> Dienst -> Fahrzeug (Produkt folgt mit M2).
-- 1. Fahrzeug als Scope von Konfigurationsrevisionen (V1200 nannte VEHICLE als Folgearbeit von WP-M1-21). Die Migration steht hinter V1250
--    (Integration Profilscope), damit deren Neuanlage der scope_kind-Prüfung die Fahrzeugebene nicht wieder entfernt.
-- 2. "Fahrzeug-/Profiloverrides gelten nur für dafür freigegebene Parameter" (Kap. 01 §1.6): die Freigabeliste `config_vehicle_override_release` ist ein
--    globaler, nur per Migration gepflegter Katalog (wie policy_schema). Freigegeben ist in P1 `wheelchair_boarding_sec` (kataloge.csv, Ebene
--    "Fahrzeugtyp"); weitere Fahrzeugparameter folgen mit M3 und ihrer Aufnahme in den Parameterkatalog.
-- 3. `config_value_violations` kennt die Fahrzeugebene: ein Wert auf Fahrzeugebene ist nur für freigegebene Parameter zulässig (sonst UNKNOWN_FIELD).
-- 4. Ein Fahrzeug-Scope verweist auf ein vorhandenes Fahrzeug desselben Mandanten (polymorpher Verweis: Trigger statt Fremdschlüssel).
SET search_path = taktovia, public;

ALTER TABLE config_revision DROP CONSTRAINT config_revision_scope_kind_valid;
ALTER TABLE config_revision ADD CONSTRAINT config_revision_scope_kind_valid
  CHECK (scope_kind IN ('TENANT', 'REGION', 'DEPOT', 'SERVICE', 'TENANT_PROFILE', 'VEHICLE'));

CREATE TABLE config_vehicle_override_release (
  parameter   text PRIMARY KEY CHECK (parameter ~ '^[a-z][a-z0-9_]{1,62}$'),
  description text NOT NULL CHECK (length(description) BETWEEN 3 AND 500)
);
CREATE TRIGGER config_vehicle_override_release_immutable BEFORE UPDATE OR DELETE ON config_vehicle_override_release FOR EACH ROW EXECUTE FUNCTION reject_mutation();
INSERT INTO config_vehicle_override_release(parameter, description)
VALUES ('wheelchair_boarding_sec', 'Rollstuhl-Einstiegszeit je Fahrzeug (kataloge.csv: Ebene Fahrzeugtyp)');

CREATE OR REPLACE FUNCTION config_value_violations(p_scope text, p_version text, p_values jsonb) RETURNS TABLE(pointer text, code text)
LANGUAGE plpgsql STABLE SET search_path = taktovia, pg_catalog AS $$
DECLARE r record; s policy_schema; num numeric; k text;
BEGIN
  IF jsonb_typeof(p_values) IS DISTINCT FROM 'object' THEN pointer := '/'; code := 'TYPE_MISMATCH'; RETURN NEXT; RETURN; END IF;
  FOR r IN SELECT e.key, e.value FROM jsonb_each(p_values) e LOOP
    pointer := '/' || replace(replace(r.key, '~', '~0'), '/', '~1');
    SELECT * INTO s FROM policy_schema ps WHERE ps.schema_version = p_version AND ps.parameter = r.key;
    -- Fahrzeugebene: nur für freigegebene Parameter (config_vehicle_override_release), unabhängig von x-levels.
    IF NOT FOUND OR NOT (s.allowed_levels ? p_scope OR (p_scope = 'VEHICLE' AND EXISTS (SELECT 1 FROM config_vehicle_override_release rel WHERE rel.parameter = r.key))) THEN
      code := 'UNKNOWN_FIELD'; RETURN NEXT; CONTINUE;
    END IF;
    IF NOT (CASE s.value_type
              WHEN 'integer' THEN jsonb_typeof(r.value) = 'number' AND (r.value #>> '{}')::numeric = trunc((r.value #>> '{}')::numeric)
              WHEN 'number'  THEN jsonb_typeof(r.value) = 'number'
              ELSE jsonb_typeof(r.value) = s.value_type END) THEN
      code := 'TYPE_MISMATCH'; RETURN NEXT; CONTINUE;
    END IF;
    IF s.const_value IS NOT NULL AND r.value <> s.const_value THEN
      code := CASE s.security_class WHEN 'SAFETY_INVARIANT' THEN 'SAFETY_INVARIANT_VIOLATED' ELSE 'NOT_IN_ENUM' END; RETURN NEXT; CONTINUE;
    END IF;
    IF s.value_type IN ('integer', 'number') THEN
      num := (r.value #>> '{}')::numeric;
      IF s.min_value IS NOT NULL AND num < s.min_value THEN code := 'BELOW_MINIMUM'; RETURN NEXT; CONTINUE; END IF;
      IF s.max_value IS NOT NULL AND num > s.max_value THEN code := 'ABOVE_MAXIMUM'; RETURN NEXT; CONTINUE; END IF;
    END IF;
    IF s.value_format = 'iana-timezone' AND NOT EXISTS (SELECT 1 FROM pg_timezone_names z WHERE z.name = (r.value #>> '{}')) THEN
      code := 'ZONE_UNKNOWN'; RETURN NEXT; CONTINUE;
    END IF;
    IF s.value_type = 'object' THEN
      FOR k IN SELECT jsonb_array_elements_text(coalesce(s.json_schema -> 'required', '[]'::jsonb)) LOOP
        IF NOT (r.value ? k) THEN pointer := '/' || r.key || '/' || k; code := 'REQUIRED'; RETURN NEXT; END IF;
      END LOOP;
      IF (s.json_schema -> 'additionalProperties') = 'false'::jsonb THEN
        FOR k IN SELECT o.key FROM jsonb_each(r.value) o WHERE NOT (s.json_schema -> 'properties') ? o.key LOOP
          pointer := '/' || r.key || '/' || k; code := 'UNKNOWN_FIELD'; RETURN NEXT;
        END LOOP;
      END IF;
    END IF;
  END LOOP;
  -- Die Vollständigkeit der Dienstkonfiguration (jede Dienstrevision trägt `service_config`, SYS-002) prüft nur die Anwendung; die Datenbank
  -- prüft vorhandene Bestandteile, damit Testdaten und Altfixtures mit leeren Werten (`{}`) gültig bleiben.
END $$;

CREATE FUNCTION config_revision_vehicle_ref() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.scope_kind = 'VEHICLE' AND NOT EXISTS (SELECT 1 FROM vehicle v WHERE v.tenant_id = NEW.tenant_id AND v.id = NEW.scope_id) THEN
    RAISE EXCEPTION 'config_revision: Fahrzeug des Scopes unbekannt' USING ERRCODE = '23503';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER config_revision_vehicle_ref BEFORE INSERT ON config_revision FOR EACH ROW EXECUTE FUNCTION config_revision_vehicle_ref();
