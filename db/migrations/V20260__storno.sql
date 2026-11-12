-- WP-M2-26 / LH-6.4-BKG-005: Stornofrist und zulässige Stornogründe als neue Dienstparameter im Konfigurationsregister (Offener Punkt 23: im Katalog kataloge.csv nicht enthalten) sowie die
-- Freigabe der Kapazität einer bestätigten Buchung beim Storno.
-- Vorläufige Festlegung (Planungsannahme, Fachfreigabe und Produktionswerte stehen unter D-05 aus): Frist = Mindestabstand in Minuten zwischen dem Storno durch den Fahrgast und dem Beginn des
-- zugesagten Abholfensters (Standard 60), zulässige Gründe = Teilmenge des Stornokatalogs (Standard alle). Die Parameter werden der Startversion '1' hinzugefügt (nur INSERT; die Unveränderlichkeit
-- sperrt UPDATE/DELETE). Gemeinsame Quelle: vertraege/schemas/cancellation_*.v1.schema.json.
SET search_path = taktovia, public;

INSERT INTO policy_schema(schema_version, parameter, json_schema) VALUES
  ('1', 'cancellation_deadline_min', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/cancellation_deadline_min.v1.schema.json", "title": "cancellation_deadline_min", "description": "Stornofrist des Fahrgasts", "type": "integer", "minimum": 0, "maximum": 43200, "default": 60, "x-unit": "MINUTES", "x-levels": ["TENANT", "REGION", "SERVICE"], "x-security-class": "STANDARD", "x-limit-source": "PLANUNGSANNAHME", "x-catalog": "nicht im Katalog (WP-M2-26, OP-23)", "x-catalog-range": "0..43200; Mindestabstand zwischen Storno durch den Fahrgast und Beginn des zugesagten Abholfensters, Standard 60; Produktionswerte nach D-05"}'::jsonb),
  ('1', 'cancellation_reasons', '{"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "https://taktovia.example/schemas/cancellation_reasons.v1.schema.json", "title": "cancellation_reasons", "description": "Zulässige Stornogründe", "type": "object", "additionalProperties": false, "required": ["reasons"], "properties": {"reasons": {"type": "array", "minItems": 1, "uniqueItems": true, "items": {"enum": ["PASSENGER_REQUEST", "OPERATOR_CANCELLATION", "NO_SHOW", "PAYMENT_FAILED", "SAFETY", "VEHICLE_OUTAGE"]}}}, "default": {"reasons": ["PASSENGER_REQUEST", "OPERATOR_CANCELLATION", "NO_SHOW", "PAYMENT_FAILED", "SAFETY", "VEHICLE_OUTAGE"]}, "x-unit": "REASON_SET", "x-levels": ["TENANT", "REGION", "SERVICE"], "x-security-class": "STANDARD", "x-limit-source": "PLANUNGSANNAHME", "x-catalog": "nicht im Katalog (WP-M2-26, OP-23)", "x-catalog-range": "Teilmenge des Grundkatalogs (booking_status_reason, Art CANCELLATION); Standard alle Gründe; Produktionswerte nach D-05"}'::jsonb);

-- Hold: eine bestätigte (CONSUMED) Reservierung wird beim Storno der Buchung freigegeben (CONSUMED -> RELEASED), damit die Kapazität auf allen Abschnitten und Dimensionen wieder zählt
-- (Belegung = CONSUMED plus gültiges HELD). Der Übergang ist nur zulässig, solange die Buchung zum Hold storniert wird oder ist (Wächter unten): ein Client kann einen verbrauchten Hold nie
-- per SQL oder über den Freigabe-Befehl freigeben, solange die Buchung bestätigt bleibt.
INSERT INTO state_transition_rule(machine, version, from_state, to_state) VALUES ('capacity_hold', 1, 'CONSUMED', 'RELEASED');
CREATE FUNCTION capacity_hold_release_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF OLD.state = 'CONSUMED' AND NEW.state = 'RELEASED' AND NOT EXISTS (
       SELECT 1 FROM booking b WHERE b.tenant_id = OLD.tenant_id AND b.hold_id = OLD.id AND b.state IN ('CANCEL_PENDING', 'COMPENSATING', 'CANCELED', 'FAILED')) THEN
    RAISE EXCEPTION 'a consumed hold is released only together with the cancellation of its booking' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER capacity_hold_release_guard BEFORE UPDATE OF state ON capacity_hold FOR EACH ROW WHEN (OLD.state = 'CONSUMED' AND NEW.state = 'RELEASED') EXECUTE FUNCTION capacity_hold_release_guard();
