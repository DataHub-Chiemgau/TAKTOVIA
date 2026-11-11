-- WP-M1-19 / LH-I.7-CFG-002, LH-I.7-CFG-004: Parameterkatalog `policy_schema` mit Schema-Version und Systemgrenzen.
-- Entscheidung zu OP-08 für den Bau: Der Katalog ist ein globaler, nicht personenbezogener Plattformkatalog ohne `tenant_id` (wie `currency`
-- und `state_transition_rule`). Er enthält keine Mandantendaten; jeder Mandant liest dieselben Zeilen, die Runtime-Rolle hat nur SELECT
-- (R__runtime_grants_m1_19.sql), geändert wird er ausschließlich per Migration. Ein expliziter Plattformmandant ist dafür nicht nötig,
-- weil `tenant_id IS NULL` nie "für alle sichtbar" bedeutet und hier gar keine Mandantenspalte existiert.
-- Katalogbereiche aus docs/pflichtenheft/kataloge.csv gelten als systemisches Maximum (OP-03); wo der Katalog keinen Bereich nennt
-- ("Positiv", "Vertragsabhängig"), stehen technische Planungsannahmen (`x-limit-source = PLANUNGSANNAHME`), die D-05 ersetzt.
SET search_path = taktovia, public;

CREATE TABLE policy_schema_version (
  schema_version text        PRIMARY KEY CHECK (schema_version ~ '^[1-9][0-9]{0,8}$'),
  valid_from     timestamptz NOT NULL,
  description    text        NOT NULL CHECK (length(description) BETWEEN 3 AND 500)
);

-- Je Version und Parameter genau ein JSON-Schema (Draft 2020-12 mit den Erweiterungen x-unit, x-levels, x-security-class, x-limit-source).
-- Die abfragbaren Spalten sind aus dem JSON-Schema erzeugt: es gibt genau eine Quelle der Wahrheit.
CREATE TABLE policy_schema (
  schema_version  text  NOT NULL REFERENCES policy_schema_version(schema_version),
  parameter       text  NOT NULL CHECK (parameter ~ '^[a-z][a-z0-9_]{1,62}$'),
  json_schema     jsonb NOT NULL CHECK (jsonb_typeof(json_schema) = 'object'),
  value_type      text  GENERATED ALWAYS AS (json_schema ->> 'type') STORED NOT NULL,
  unit            text  GENERATED ALWAYS AS (json_schema ->> 'x-unit') STORED NOT NULL,
  description     text  GENERATED ALWAYS AS (json_schema ->> 'description') STORED NOT NULL,
  min_value       numeric GENERATED ALWAYS AS ((json_schema ->> 'minimum')::numeric) STORED,
  max_value       numeric GENERATED ALWAYS AS ((json_schema ->> 'maximum')::numeric) STORED,
  const_value     jsonb GENERATED ALWAYS AS (json_schema -> 'const') STORED,
  default_value   jsonb GENERATED ALWAYS AS (json_schema -> 'default') STORED,
  value_format    text  GENERATED ALWAYS AS (json_schema ->> 'format') STORED,
  allowed_levels  jsonb GENERATED ALWAYS AS (json_schema -> 'x-levels') STORED NOT NULL,
  security_class  text  GENERATED ALWAYS AS (json_schema ->> 'x-security-class') STORED NOT NULL,
  limit_source    text  GENERATED ALWAYS AS (json_schema ->> 'x-limit-source') STORED NOT NULL,
  PRIMARY KEY (schema_version, parameter),
  CHECK (value_type IN ('integer', 'number', 'boolean', 'string', 'object')),
  CHECK (security_class IN ('STANDARD', 'SAFETY_INVARIANT')),
  CHECK (limit_source IN ('KATALOG', 'PLANUNGSANNAHME')),
  CHECK (jsonb_typeof(allowed_levels) = 'array' AND allowed_levels <@ '["TENANT","REGION","DEPOT","SERVICE"]'::jsonb),
  CHECK (min_value IS NULL OR max_value IS NULL OR min_value <= max_value)
);

-- Eine freigegebene Schema-Version ist unveränderlich (auch für den Migrator): Änderungen sind eine neue Version.
CREATE TRIGGER policy_schema_immutable BEFORE UPDATE OR DELETE ON policy_schema FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER policy_schema_version_immutable BEFORE UPDATE OR DELETE ON policy_schema_version FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- Die zum Zeitpunkt gültige Version: die höchste, deren Gültigkeit begonnen hat (NULL, wenn noch keine gilt).
CREATE FUNCTION current_policy_version(at timestamptz DEFAULT now()) RETURNS text LANGUAGE sql STABLE SET search_path = taktovia, pg_catalog AS $$
  SELECT schema_version FROM policy_schema_version WHERE valid_from <= at ORDER BY schema_version::int DESC LIMIT 1
$$;

-- Prüfung von `config_revision.values_json` gegen den Katalog (Schutz in der Tiefe: auch direkte SQL-Schreibzugriffe und Fehler der Anwendung
-- erzeugen keine Zeile mit ungültigem Wert). Die Anwendung prüft zuerst und liefert die Feldverletzungen (422); dieselben Codes.
-- Geprüft werden: bekannter Parametername auf zulässiger Ebene, Typ, Bereich, feste Werte (Sicherheitsinvarianten), IANA-Zeitzone sowie
-- bei Objekten die Pflicht- und die erlaubten Felder der ersten Ebene (nur für vorhandene Werte, nicht ihre Vollständigkeit). Tiefere Formate (Muster, Aufzählungen verschachtelter Felder) prüft nur die Anwendung.
CREATE FUNCTION config_value_violations(p_scope text, p_version text, p_values jsonb) RETURNS TABLE(pointer text, code text)
LANGUAGE plpgsql STABLE SET search_path = taktovia, pg_catalog AS $$
DECLARE r record; s policy_schema; num numeric; k text;
BEGIN
  IF jsonb_typeof(p_values) IS DISTINCT FROM 'object' THEN pointer := '/'; code := 'TYPE_MISMATCH'; RETURN NEXT; RETURN; END IF;
  FOR r IN SELECT e.key, e.value FROM jsonb_each(p_values) e LOOP
    pointer := '/' || replace(replace(r.key, '~', '~0'), '/', '~1');
    SELECT * INTO s FROM policy_schema ps WHERE ps.schema_version = p_version AND ps.parameter = r.key;
    IF NOT FOUND OR NOT (s.allowed_levels ? p_scope) THEN code := 'UNKNOWN_FIELD'; RETURN NEXT; CONTINUE; END IF;
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

INSERT INTO policy_schema_version(schema_version, valid_from, description)
VALUES ('1', '2026-01-01T00:00:00Z', 'Startmenge P1 (WP-M1-19): 20 Parameter aus kataloge.csv und die Dienstkonfiguration; Planungsstand, Werte vor D-05 nicht freigegeben');

INSERT INTO policy_schema(schema_version, parameter, json_schema) VALUES
  ('1', 'max_wait_min', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/max_wait_min.v1.schema.json", "title": "max_wait_min", "description": "Maximale Wartezeit", "type": "integer", "minimum": 0, "maximum": 240, "x-unit": "MINUTES", "x-levels": ["TENANT", "REGION", "SERVICE"], "x-security-class": "STANDARD", "x-limit-source": "KATALOG", "x-catalog": "LH-J.1-max_wait_min", "x-catalog-range": "0..240; Sicherheits- und Vertragsvorgaben vorgehend"}'::jsonb),
  ('1', 'max_detour_pct', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/max_detour_pct.v1.schema.json", "title": "max_detour_pct", "description": "Maximaler Pooling-Umweg", "type": "integer", "minimum": 0, "maximum": 200, "x-unit": "PERCENT", "x-levels": ["TENANT", "REGION", "SERVICE"], "x-security-class": "STANDARD", "x-limit-source": "KATALOG", "x-catalog": "LH-J.1-max_detour_pct", "x-catalog-range": "0..200; harte Zusagegrenzen vorgehend"}'::jsonb),
  ('1', 'min_booking_lead_min', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/min_booking_lead_min.v1.schema.json", "title": "min_booking_lead_min", "description": "Mindestbuchungsvorlauf", "type": "integer", "minimum": 0, "maximum": 43200, "x-unit": "MINUTES", "x-levels": ["TENANT", "REGION", "SERVICE"], "x-security-class": "STANDARD", "x-limit-source": "KATALOG", "x-catalog": "LH-J.1-min_booking_lead_min", "x-catalog-range": "0..43200"}'::jsonb),
  ('1', 'max_booking_horizon_days', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/max_booking_horizon_days.v1.schema.json", "title": "max_booking_horizon_days", "description": "Vorausbuchungshorizont", "type": "integer", "minimum": 0, "maximum": 365, "x-unit": "DAYS", "x-levels": ["TENANT", "REGION", "SERVICE"], "x-security-class": "STANDARD", "x-limit-source": "KATALOG", "x-catalog": "LH-J.1-max_booking_horizon_days", "x-catalog-range": "0..365"}'::jsonb),
  ('1', 'pickup_window_min', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/pickup_window_min.v1.schema.json", "title": "pickup_window_min", "description": "Abholzeitfenster", "type": "integer", "minimum": 1, "maximum": 240, "x-unit": "MINUTES", "x-levels": ["TENANT", "REGION", "SERVICE"], "x-security-class": "STANDARD", "x-limit-source": "PLANUNGSANNAHME", "x-catalog": "LH-J.1-pickup_window_min", "x-catalog-range": "Positiv; bei Änderung bereits bestätigte Buchungen prüfen"}'::jsonb),
  ('1', 'arrival_grace_min', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/arrival_grace_min.v1.schema.json", "title": "arrival_grace_min", "description": "Ankunftstoleranz", "type": "integer", "minimum": 0, "maximum": 240, "x-unit": "MINUTES", "x-levels": ["TENANT", "REGION", "SERVICE"], "x-security-class": "STANDARD", "x-limit-source": "PLANUNGSANNAHME", "x-catalog": "LH-J.1-arrival_grace_min", "x-catalog-range": "Vertragsabhängig; nicht nachträglich rückwirkend ändern"}'::jsonb),
  ('1', 'no_show_grace_min', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/no_show_grace_min.v1.schema.json", "title": "no_show_grace_min", "description": "Wartefrist No-Show", "type": "integer", "minimum": 1, "maximum": 120, "x-unit": "MINUTES", "x-levels": ["TENANT", "REGION", "SERVICE"], "x-security-class": "STANDARD", "x-limit-source": "PLANUNGSANNAHME", "x-catalog": "LH-J.1-no_show_grace_min", "x-catalog-range": "Positiv und fahrgastgerecht; Ereignisprotokoll"}'::jsonb),
  ('1', 'stop_walk_max_m', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/stop_walk_max_m.v1.schema.json", "title": "stop_walk_max_m", "description": "Maximaler Fußweg", "type": "integer", "minimum": 0, "maximum": 5000, "x-unit": "METERS", "x-levels": ["TENANT", "REGION", "SERVICE"], "x-security-class": "STANDARD", "x-limit-source": "PLANUNGSANNAHME", "x-catalog": "LH-J.1-stop_walk_max_m", "x-catalog-range": "Barrierefreiheit wird vorrangig geprüft"}'::jsonb),
  ('1', 'advance_arrival_buffer_min', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/advance_arrival_buffer_min.v1.schema.json", "title": "advance_arrival_buffer_min", "description": "Anschluss-Zeitpuffer", "type": "integer", "minimum": 0, "maximum": 240, "x-unit": "MINUTES", "x-levels": ["TENANT", "REGION", "SERVICE"], "x-security-class": "STANDARD", "x-limit-source": "PLANUNGSANNAHME", "x-catalog": "LH-J.1-advance_arrival_buffer_min", "x-catalog-range": "Planungssicherheit; nicht über Sicherheitsgrenzen"}'::jsonb),
  ('1', 'pooling_allowed', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/pooling_allowed.v1.schema.json", "title": "pooling_allowed", "description": "Pooling aktiv", "type": "boolean", "x-unit": "BOOLEAN", "x-levels": ["TENANT", "REGION", "SERVICE"], "x-security-class": "STANDARD", "x-limit-source": "KATALOG", "x-catalog": "LH-J.1-pooling_allowed", "x-catalog-range": "Bei AUS keine fremden Buchungen gemeinschaftlich disponieren"}'::jsonb),
  ('1', 'max_reassignments', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/max_reassignments.v1.schema.json", "title": "max_reassignments", "description": "Maximale Umbuchungen", "type": "integer", "minimum": 0, "maximum": 20, "x-unit": "COUNT", "x-levels": ["TENANT", "REGION", "SERVICE"], "x-security-class": "STANDARD", "x-limit-source": "PLANUNGSANNAHME", "x-catalog": "LH-J.1-max_reassignments", "x-catalog-range": "Zugesagte Fahrten schützen"}'::jsonb),
  ('1', 'protected_stop_horizon_min', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/protected_stop_horizon_min.v1.schema.json", "title": "protected_stop_horizon_min", "description": "Nicht umplanbarer Stopphorizont", "type": "integer", "minimum": 0, "maximum": 240, "x-unit": "MINUTES", "x-levels": ["TENANT", "REGION", "SERVICE"], "x-security-class": "STANDARD", "x-limit-source": "PLANUNGSANNAHME", "x-catalog": "LH-J.1-protected_stop_horizon_min", "x-catalog-range": "Nur unter autorisiertem Ausnahmeprozess verletzbar"}'::jsonb),
  ('1', 'min_capacity_reserve', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/min_capacity_reserve.v1.schema.json", "title": "min_capacity_reserve", "description": "Geschützte Kapazitätsreserve", "type": "integer", "minimum": 0, "maximum": 20, "x-unit": "SEATS", "x-levels": ["TENANT", "REGION", "SERVICE"], "x-security-class": "STANDARD", "x-limit-source": "PLANUNGSANNAHME", "x-catalog": "LH-J.1-min_capacity_reserve", "x-catalog-range": "Nicht größer als tatsächlich verfügbare Kapazität"}'::jsonb),
  ('1', 'transfer_min_time_min', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/transfer_min_time_min.v1.schema.json", "title": "transfer_min_time_min", "description": "Mindestumsteigezeit", "type": "integer", "minimum": 0, "maximum": 240, "x-unit": "MINUTES", "x-levels": ["TENANT", "REGION"], "x-security-class": "STANDARD", "x-limit-source": "PLANUNGSANNAHME", "x-catalog": "LH-J.1-transfer_min_time_min", "x-catalog-range": "Fußweg und Zugänglichkeit berücksichtigen"}'::jsonb),
  ('1', 'wheelchair_boarding_sec', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/wheelchair_boarding_sec.v1.schema.json", "title": "wheelchair_boarding_sec", "description": "Rollstuhl-Einstiegszeit", "type": "integer", "minimum": 0, "maximum": 1800, "x-unit": "SECONDS", "x-levels": ["TENANT", "SERVICE"], "x-security-class": "STANDARD", "x-limit-source": "PLANUNGSANNAHME", "x-catalog": "LH-J.1-wheelchair_boarding_sec", "x-catalog-range": "Muss Fahrzeug und individuelle Situation berücksichtigen"}'::jsonb),
  ('1', 'stop_dwell_sec', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/stop_dwell_sec.v1.schema.json", "title": "stop_dwell_sec", "description": "Standard-Haltestellenaufenthalt", "type": "integer", "minimum": 1, "maximum": 3600, "x-unit": "SECONDS", "x-levels": ["TENANT", "REGION", "SERVICE"], "x-security-class": "STANDARD", "x-limit-source": "PLANUNGSANNAHME", "x-catalog": "LH-J.1-stop_dwell_sec", "x-catalog-range": "Nicht unter sichere Haltestellenabfertigung"}'::jsonb),
  ('1', 'vehicle_readiness_require_checks', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/vehicle_readiness_require_checks.v1.schema.json", "title": "vehicle_readiness_require_checks", "description": "Pflichtprüfung vor Dienst", "type": "boolean", "const": true, "x-unit": "BOOLEAN", "x-levels": ["TENANT", "REGION", "SERVICE"], "x-security-class": "SAFETY_INVARIANT", "x-limit-source": "KATALOG", "x-catalog": "LH-J.3-vehicle_readiness_require_checks", "x-catalog-range": "Sicherheitsprüfungen dürfen nicht abgeschaltet werden"}'::jsonb),
  ('1', 'customer_notify_before_pickup_min', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/customer_notify_before_pickup_min.v1.schema.json", "title": "customer_notify_before_pickup_min", "description": "Push vor Abholung", "type": "integer", "minimum": 0, "maximum": 240, "x-unit": "MINUTES", "x-levels": ["TENANT", "REGION", "SERVICE"], "x-security-class": "STANDARD", "x-limit-source": "PLANUNGSANNAHME", "x-catalog": "LH-J.7-customer_notify_before_pickup_min", "x-catalog-range": "Reale ETA-Änderungen priorisiert"}'::jsonb),
  ('1', 'reporting_timezone', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/reporting_timezone.v1.schema.json", "title": "reporting_timezone", "description": "Statistik-Zeitzone", "type": "string", "format": "iana-timezone", "minLength": 1, "maxLength": 64, "x-unit": "IANA_TZ", "x-levels": ["TENANT"], "x-security-class": "STANDARD", "x-limit-source": "KATALOG", "x-catalog": "LH-J.7-reporting_timezone", "x-catalog-range": "DST-sicher"}'::jsonb),
  ('1', 'notification_quiet_hours', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/notification_quiet_hours.v1.schema.json", "title": "notification_quiet_hours", "description": "Ruhezeiten für Nachrichten", "type": "object", "additionalProperties": false, "required": ["from", "to"], "properties": {"from": {"type": "string", "pattern": "^([01][0-9]|2[0-3]):[0-5][0-9]$"}, "to": {"type": "string", "pattern": "^([01][0-9]|2[0-3]):[0-5][0-9]$"}}, "x-unit": "TIME_SPAN", "x-levels": ["TENANT"], "x-security-class": "STANDARD", "x-limit-source": "KATALOG", "x-catalog": "LH-J.7-notification_quiet_hours", "x-catalog-range": "Sicherheitsalarme ausgenommen"}'::jsonb),
  ('1', 'service_config', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/service-config.v1.schema.json", "title": "service_config", "description": "Dienstkonfiguration (LH-6.1-SYS-002): Bediengebietsbezug, Verkehrstyp, Betreiber, Betriebszeiten und Tarifverknüpfung als Bestandteile derselben Revision.", "type": "object", "additionalProperties": false, "required": ["service_area_id", "traffic_type", "operator_id", "operating_hours", "tariff_id"], "properties": {"service_area_id": {"type": "string", "format": "uuid", "description": "Bediengebietsbezug: Bezug auf service_area (Auflösung mit M2)."}, "traffic_type": {"type": "string", "enum": ["AREA_ON_DEMAND", "LINE_ON_DEMAND", "STATION_FEEDER"], "description": "Verkehrstyp (Planungsannahme PA-01)."}, "operator_id": {"type": "string", "format": "uuid", "description": "Betreiber: Bezug auf organization."}, "operating_hours": {"type": "array", "minItems": 1, "maxItems": 50, "description": "Betriebszeiten je Wochentag in Ortszeit des Dienstes; 24:00 nur als Ende.", "items": {"type": "object", "additionalProperties": false, "required": ["weekdays", "from", "to"], "properties": {"weekdays": {"type": "array", "minItems": 1, "maxItems": 7, "uniqueItems": true, "items": {"type": "string", "enum": ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"]}}, "from": {"type": "string", "pattern": "^([01][0-9]|2[0-3]):[0-5][0-9]$"}, "to": {"type": "string", "pattern": "^(([01][0-9]|2[0-3]):[0-5][0-9]|24:00)$"}}}}, "tariff_id": {"type": "string", "format": "uuid", "description": "Tarifverknüpfung: Bezug auf tariff_revision (Auflösung mit M2)."}}, "x-unit": "OBJECT", "x-levels": ["SERVICE"], "x-security-class": "STANDARD", "x-limit-source": "PLANUNGSANNAHME", "x-catalog": "LH-6.1-SYS-002"}'::jsonb);
