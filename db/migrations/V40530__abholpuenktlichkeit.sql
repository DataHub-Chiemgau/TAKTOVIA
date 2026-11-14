-- WP-M4-53 / LH-I.6-KPI-007: Abholpünktlichkeit gegen das Zusagefenster (Modul analytics).
-- 1. Parameterkatalog (Version '1', nur INSERT): on_time_pickup_tolerance_min (Katalog J.7, Ebene Dienst; die Anforderung verlangt gebietsspezifisch: zulässig sind Mandant, Region und Dienst,
--    die Auflösung nimmt die spezifischste Ebene, Offener Punkt 29). Grenzen 0 bis 240 und Standardwert 0 sind Planungsannahme (SLA-Werte des Piloten D-05 offen).
-- 2. time_fact: Zusagezeilen tragen den Dienst (service_id) und den Zusagezeitpunkt (promised_at = Vergleichsstichtag, Arbeitsannahme OP 29); beides nur bei PROMISED, nur angehängt (Spalten NULL bei bestehenden Zeilen).
-- 3. pickup_tolerance_binding: der je Buchung und Zusageversion zum Vergleichsstichtag wirksame Grenzwert mit Konfigurationsrevision (nur anhängen). Eine spätere Änderung des Grenzwerts
--    legt eine neue Konfigurationsrevision an und verändert diese Zeilen nie: bereits klassifizierte Abholungen und gespeicherte Berichtstände bleiben.
-- 4. aggregate_snapshot.parameter_versions: die im Wert verwendeten Konfigurationsrevisionen (Version des Grenzwerts am Ergebnis).
SET search_path = taktovia, public;

INSERT INTO policy_schema(schema_version, parameter, json_schema) VALUES
  ('1', 'on_time_pickup_tolerance_min', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/on_time_pickup_tolerance_min.v1.schema.json", "title": "on_time_pickup_tolerance_min", "description": "Abholpünktlichkeit Grenzwert: Abholung gilt als pünktlich, wenn die Verspätung gegenüber dem Ende des zugesagten Abholfensters höchstens so viele Minuten beträgt", "type": "integer", "minimum": 0, "maximum": 240, "default": 0, "x-unit": "MINUTES", "x-levels": ["TENANT", "REGION", "SERVICE"], "x-security-class": "STANDARD", "x-limit-source": "PLANUNGSANNAHME", "x-catalog": "LH-J.7-on_time_pickup_tolerance_min", "x-catalog-range": "An historische SLA-Version gebunden; Grenzen und Standardwert Planungsannahme WP-M4-53 (SLA-Werte des Piloten D-05 offen, Testwerte)"}'::jsonb);

ALTER TABLE time_fact ADD COLUMN service_id uuid, ADD COLUMN promised_at timestamptz;
ALTER TABLE time_fact ADD CONSTRAINT time_fact_promise_context CHECK (time_kind = 'PROMISED' OR (service_id IS NULL AND promised_at IS NULL));
CREATE INDEX time_fact_pickup_actual ON time_fact (tenant_id, value_at) WHERE stop_event = 'PICKUP' AND time_kind = 'ACTUAL';

CREATE TABLE pickup_tolerance_binding (
  tenant_id          uuid        NOT NULL REFERENCES tenant(id),
  booking_id         uuid        NOT NULL,
  promise_version    bigint      NOT NULL CHECK (promise_version >= 1),
  service_id         uuid,
  bound_at           timestamptz NOT NULL,
  tolerance_min      integer     NOT NULL CHECK (tolerance_min BETWEEN 0 AND 240),
  config_level       text        NOT NULL CHECK (config_level IN ('GLOBAL', 'TENANT', 'REGION', 'SERVICE')),
  config_revision_id uuid,
  config_revision    bigint      CHECK (config_revision >= 1),
  recorded_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, booking_id, promise_version),
  CONSTRAINT pickup_tolerance_binding_revision CHECK ((config_level = 'GLOBAL') = (config_revision_id IS NULL))
);
ALTER TABLE pickup_tolerance_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE pickup_tolerance_binding FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pickup_tolerance_binding USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER pickup_tolerance_binding_append_only BEFORE UPDATE OR DELETE ON pickup_tolerance_binding FOR EACH ROW EXECUTE FUNCTION reject_mutation();

ALTER TABLE aggregate_snapshot ADD COLUMN parameter_versions jsonb CHECK (parameter_versions IS NULL OR jsonb_typeof(parameter_versions) = 'array');
