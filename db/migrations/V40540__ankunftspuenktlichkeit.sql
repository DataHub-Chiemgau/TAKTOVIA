-- WP-M4-54 / LH-I.6-KPI-008: Ankunftspünktlichkeit gegen die zugesagte späteste Ankunft, getrennt von der Abholung (Modul analytics).
-- 1. Parameterkatalog (Version '1', nur INSERT): on_time_arrival_tolerance_min (Ebenen Mandant, Region, Dienst; Grenzen 0 bis 240 und Standardwert 0 sind Planungsannahme, SLA-Werte des Piloten D-05 offen).
--    arrival_grace_min (Ankunftstoleranz der Zusage) existiert bereits seit V1190.
-- 2. arrival_tolerance_binding: je Buchung und Zusageversion die zum Vergleichsstichtag (Zusagezeitpunkt, Arbeitsannahme OP 29) wirksamen Werte beider Parameter mit Konfigurationsrevision (nur anhängen).
--    Arbeitsannahme zum Verhältnis der Parameter: wirksame Toleranz = größerer der beiden Werte (applied_min); eine spätere Änderung verändert diese Zeilen nie, bereits klassifizierte Ankünfte
--    und gespeicherte Berichtstände bleiben (Katalogvalidierung "keine rückwirkende Statistikänderung").
-- 3. Definitionen Version 2 von arrival_on_time und mean_arrival_delay (ausführbar); die freigegebene Version 1 ist unveränderlich.
-- Abhol- und Ankunftswerte liegen in getrennten Zeitfakten-Zeilen (stop_event) und getrennten Bindungen: es gibt nirgends ein gemeinsames Pünktlichkeitsflag.
SET search_path = taktovia, public;

INSERT INTO policy_schema(schema_version, parameter, json_schema) VALUES
  ('1', 'on_time_arrival_tolerance_min', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/on_time_arrival_tolerance_min.v1.schema.json", "title": "on_time_arrival_tolerance_min", "description": "Ankunftspünktlichkeit Grenzwert: Ankunft gilt als pünktlich, wenn die Verspätung gegenüber der zugesagten spätesten Ankunft höchstens so viele Minuten beträgt", "type": "integer", "minimum": 0, "maximum": 240, "default": 0, "x-unit": "MINUTES", "x-levels": ["TENANT", "REGION", "SERVICE"], "x-security-class": "STANDARD", "x-limit-source": "PLANUNGSANNAHME", "x-catalog": "LH-J.7-on_time_arrival_tolerance_min", "x-catalog-range": "An historische SLA-Version gebunden; Grenzen und Standardwert Planungsannahme WP-M4-54 (SLA-Werte des Piloten D-05 offen, Testwerte)"}'::jsonb);

CREATE INDEX time_fact_arrival_actual ON time_fact (tenant_id, value_at) WHERE stop_event = 'ARRIVAL' AND time_kind = 'ACTUAL';

CREATE TABLE arrival_tolerance_binding (
  tenant_id                 uuid        NOT NULL REFERENCES tenant(id),
  booking_id                uuid        NOT NULL,
  promise_version           bigint      NOT NULL CHECK (promise_version >= 1),
  service_id                uuid,
  bound_at                  timestamptz NOT NULL,
  tolerance_min             integer     NOT NULL CHECK (tolerance_min BETWEEN 0 AND 240),
  tolerance_level           text        NOT NULL CHECK (tolerance_level IN ('GLOBAL', 'TENANT', 'REGION', 'SERVICE')),
  tolerance_revision_id     uuid,
  tolerance_revision        bigint      CHECK (tolerance_revision >= 1),
  grace_min                 integer     NOT NULL CHECK (grace_min BETWEEN 0 AND 240),
  grace_level               text        NOT NULL CHECK (grace_level IN ('GLOBAL', 'TENANT', 'REGION', 'SERVICE')),
  grace_revision_id         uuid,
  grace_revision            bigint      CHECK (grace_revision >= 1),
  applied_min             integer     GENERATED ALWAYS AS (greatest(tolerance_min, grace_min)) STORED,
  recorded_at               timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, booking_id, promise_version),
  CONSTRAINT arrival_tolerance_binding_tol_revision CHECK ((tolerance_level = 'GLOBAL') = (tolerance_revision_id IS NULL)),
  CONSTRAINT arrival_tolerance_binding_grace_revision CHECK ((grace_level = 'GLOBAL') = (grace_revision_id IS NULL))
);
ALTER TABLE arrival_tolerance_binding ENABLE ROW LEVEL SECURITY;
ALTER TABLE arrival_tolerance_binding FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON arrival_tolerance_binding USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER arrival_tolerance_binding_append_only BEFORE UPDATE OR DELETE ON arrival_tolerance_binding FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE FUNCTION seed_arrival_punctuality_definitions() RETURNS integer LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE n integer;
BEGIN
  IF taktovia.current_tenant() IS NULL THEN RETURN 0; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended(taktovia.current_tenant()::text || ':metric-seed-arrival', 0));
  INSERT INTO metric_definition_revision (tenant_id, metric_id, version, name, value_kind, unit, numerator_definition, denominator_definition, exclusions, time_basis, period_basis,
                                          filter_dimensions, source, executable, not_executable_reason, state, approved_by)
  SELECT taktovia.current_tenant(), v.metric_id, NULL, v.name, v.kind, v.unit, v.numerator, v.denominator, v.exclusions,
         'Zeitpunkt der tatsächlichen Ankunft (Ausstieg, Ist-Zeit); Betriebstag in der reporting_timezone des Mandanten.', 'BUSINESS_DAY_LOCAL',
         ARRAY['service_id'], 'DRIVER_EVENT', true, NULL, 'APPROVED', 'system:seed (Vorschlag des Plans, Freigabe D-05 offen)'
  FROM (VALUES
    ('arrival_on_time', 'Ankunft pünktlich', 'RATIO', 'Anteil',
     'Tatsächliche Ankünfte, deren Verspätung (Ist-Ankunft minus zugesagte späteste Ankunft der höchsten Zusageversion; bei gewähltem Servicefenster dessen Ende) höchstens die zum Vergleichsstichtag (Zeitpunkt der Zusage) wirksame Toleranz beträgt: der größere Wert aus on_time_arrival_tolerance_min und arrival_grace_min des Dienstes (Gebiets); frühe Ankünfte sind pünktlich. Unabhängig vom Abholergebnis.',
     'Tatsächliche qualifizierte Ankünfte: Ankünfte mit Ist-Zeit, zugesagter spätester Ankunft und gebundener Toleranz.',
     'Fahrten ohne Ankunftsereignis (nicht im Nenner); Ankünfte ohne Zusage der Ankunftszeit (gesondert ausgewiesen, nicht im Nenner); Ankünfte ohne auflösbare Toleranz; nicht durchgeführte Fahrten.'),
    ('mean_arrival_delay', 'Mittlere Ankunftsverspätung', 'MEAN', 'Minuten',
     'Summe der positiven Verspätungsminuten (Ist-Ankunft minus zugesagte späteste Ankunft der höchsten Zusageversion, ohne Toleranz); eine frühe Ankunft geht mit 0 ein und wird nie negativ verrechnet. Unabhängig vom Abholergebnis.',
     'Alle bewertbaren Ankünfte: Ankünfte mit Ist-Zeit und zugesagter spätester Ankunft.',
     'Fahrten ohne Ankunftsereignis (nicht im Nenner); Ankünfte ohne Zusage der Ankunftszeit (gesondert ausgewiesen); nicht durchgeführte Fahrten.')
  ) AS v(metric_id, name, kind, unit, numerator, denominator, exclusions)
  WHERE taktovia.current_tenant() IS NOT NULL
    AND (SELECT m.executable FROM metric_definition_revision m WHERE m.tenant_id = taktovia.current_tenant() AND m.metric_id = v.metric_id AND m.state = 'APPROVED' ORDER BY m.version DESC LIMIT 1) = false;
  GET DIAGNOSTICS n = ROW_COUNT;
  RETURN n;
END $$;
