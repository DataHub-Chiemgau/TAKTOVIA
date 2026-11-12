-- WP-M4-62 / LH-I.3-ETA-002: Puffer- und Quantilparameter der Modellrevision, Parameter der regelkonformen Prognose und Systemgrenze fuer stop_dwell_sec.
-- 1. eta_model_revision: Sicherheitsreserve je Quantil als Prozent der Referenzfahrzeit (Planungsannahme p50 5, p75 10, p85 15, p90 20; Herleitung und Freigabe offen, OP-34) und
--    Mindestreserve in Sekunden. Die Tabelle wird nur um Spalten erweitert; der Unveraenderlichkeits-Trigger (V40610) gilt fuer alle Spalten einer freigegebenen Revision.
-- 2. Parameterkatalog (Version '1', nur INSERT): eta_model_quantile, eta_safe_speed_tolerance, eta_max_data_age_sec.
-- 3. Systemgrenze "nicht unter sichere Haltestellenabfertigung" (kataloge.csv, stop_dwell_sec): policy_schema ist je Version unveraenderlich und stop_dwell_sec steht mit
--    minimum 1 in Version '1'; die Untergrenze liegt deshalb in `policy_system_floor()` (eine Quelle fuer Datenbank und Anwendung; Planungsannahme 10 s, Freigabe D-05) und gilt
--    zusaetzlich zum Schema-Minimum. config_value_violations meldet sie als BELOW_MINIMUM, die Anwendung (ParameterValidator) ebenso.
SET search_path = taktovia, public;

CREATE FUNCTION eta_buffer_table_valid(t jsonb) RETURNS boolean LANGUAGE sql IMMUTABLE SET search_path = taktovia, pg_catalog AS $$
  SELECT jsonb_typeof(t) = 'object'
     AND (SELECT array_agg(k ORDER BY k) FROM jsonb_object_keys(t) k) = ARRAY['p50', 'p75', 'p85', 'p90']
     AND jsonb_typeof(t -> 'p50') = 'number' AND jsonb_typeof(t -> 'p75') = 'number' AND jsonb_typeof(t -> 'p85') = 'number' AND jsonb_typeof(t -> 'p90') = 'number'
     AND (t ->> 'p50')::numeric >= 0 AND (t ->> 'p90')::numeric <= 100
     AND (t ->> 'p50')::numeric <= (t ->> 'p75')::numeric AND (t ->> 'p75')::numeric <= (t ->> 'p85')::numeric AND (t ->> 'p85')::numeric <= (t ->> 'p90')::numeric
$$;

ALTER TABLE eta_model_revision
  ADD COLUMN buffer_pct_by_quantile jsonb   NOT NULL DEFAULT '{"p50": 5, "p75": 10, "p85": 15, "p90": 20}'::jsonb,
  ADD COLUMN min_buffer_sec         integer NOT NULL DEFAULT 10 CHECK (min_buffer_sec >= 0 AND min_buffer_sec <= 3600),
  ADD CONSTRAINT eta_model_revision_buffer_table CHECK (eta_buffer_table_valid(buffer_pct_by_quantile));

INSERT INTO policy_schema(schema_version, parameter, json_schema) VALUES
  ('1', 'eta_model_quantile', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/eta_model_quantile.v1.schema.json", "title": "eta_model_quantile", "description": "Quantil der Sicherheitsreserve für die Disposition", "type": "string", "enum": ["p50", "p75", "p85", "p90"], "default": "p85", "x-unit": "ENUM", "x-levels": ["TENANT", "REGION", "SERVICE"], "x-security-class": "STANDARD", "x-limit-source": "PLANUNGSANNAHME", "x-catalog": "LH-J.2-eta_model_quantile", "x-catalog-range": "p50/p75/p85/p90; Standardwert im Pflichtenheft nicht beziffert (Planungsdefault p85, Freigabe vor Produktivschaltung, OP-34)"}'::jsonb),
  ('1', 'eta_safe_speed_tolerance', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/eta_safe_speed_tolerance.v1.schema.json", "title": "eta_safe_speed_tolerance", "description": "GPS-Toleranz beim Tempovergleich", "type": "integer", "minimum": 0, "maximum": 10, "default": 0, "x-unit": "KMH", "x-levels": ["TENANT"], "x-security-class": "STANDARD", "x-limit-source": "PLANUNGSANNAHME", "x-catalog": "LH-J.2-eta_safe_speed_tolerance", "x-catalog-range": "Kein Freibrief für Überschreitungen; wirkt nicht auf die zulässige Geschwindigkeit der Prognose (WP-M4-62)"}'::jsonb),
  ('1', 'eta_max_data_age_sec', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/eta_max_data_age_sec.v1.schema.json", "title": "eta_max_data_age_sec", "description": "GPS-Datenhöchstalter der laufenden ETA", "type": "integer", "minimum": 10, "maximum": 3600, "default": 120, "x-unit": "SECONDS", "x-levels": ["TENANT", "SERVICE"], "x-security-class": "STANDARD", "x-limit-source": "PLANUNGSANNAHME", "x-catalog": "LH-J.2-eta_max_data_age_sec", "x-catalog-range": "Bei Überschreitung Fallback laut eta_fallback_policy; Planungsdefault 120 s (WP-M4-62)"}'::jsonb);

CREATE FUNCTION policy_system_floor() RETURNS TABLE(parameter text, minimum numeric, source text) LANGUAGE sql IMMUTABLE SET search_path = taktovia, pg_catalog AS $$
  VALUES ('stop_dwell_sec'::text, 10::numeric, 'PLANUNGSANNAHME: sichere Haltestellenabfertigung (WP-M4-62, Freigabe D-05)'::text)
$$;

-- config_value_violations wie V10210, zusaetzlich mit der Systemgrenze aus policy_system_floor().
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
      IF EXISTS (SELECT 1 FROM policy_system_floor() f WHERE f.parameter = r.key AND num < f.minimum) THEN code := 'BELOW_MINIMUM'; RETURN NEXT; CONTINUE; END IF;   -- Systemgrenze (WP-M4-62)
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
