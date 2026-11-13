-- WP-M4-48 / LH-I.6-KPI-002: Ablehnungskatalog um Gruppe, Gültigkeit und Texte je Sprache erweitert; Durchsetzung an der Faktenbasis (fact_attempt).
-- Der Katalog bleibt global und nur per Migration pflegbar (M2 WP-M2-13). Deutsch ist `label` (Pflicht), weitere Sprachen stehen in `texts` ({"en": "..."}).
-- Gruppen sind feiner als die Kennzahlen K.1 (`metric`): TIME_OPENING (Betrieb geschlossen) und TIME_DEADLINE (Frist) zählen beide in Zeitfensterablehnungen, werden aber getrennt ausgewiesen.
-- Tarifablehnung (NO_TARIFF) ist ein eigener Code der Gruppe TARIFF ohne eigene Kennzahl in K.1 (OP-25: Abgleich mit M2).
SET search_path = taktovia, public;

ALTER TABLE rejection_reason
  ADD COLUMN reason_group text,
  ADD COLUMN valid_from   timestamptz NOT NULL DEFAULT '-infinity'::timestamptz,
  ADD COLUMN valid_to     timestamptz,
  ADD COLUMN texts        jsonb NOT NULL DEFAULT '{}'::jsonb;

INSERT INTO rejection_reason (code, priority, counts_as_rejection, metric, label, next_action, reason_group) VALUES
  ('NO_TARIFF', 65, true, NULL, 'Für die gewünschte Verbindung ist kein gültiger Tarif hinterlegt.', 'Wählen Sie eine andere Verbindung oder kontaktieren Sie den Betreiber.', 'TARIFF');

UPDATE rejection_reason r SET reason_group = g.grp FROM (VALUES
  ('LEAD_TIME', 'TIME_DEADLINE'), ('HORIZON', 'TIME_DEADLINE'), ('WINDOW_NOT_FEASIBLE', 'TIME_DEADLINE'), ('OUTSIDE_HOURS', 'TIME_OPENING'),
  ('OUTSIDE_AREA', 'AREA'), ('RULE_FORBIDDEN', 'RULE'), ('NO_STOP_IN_RANGE', 'AREA'), ('NO_ACCESSIBLE_STOP', 'EQUIPMENT'), ('NO_ROUTE', 'CAPACITY'),
  ('EQUIPMENT_MISSING', 'EQUIPMENT'), ('NO_STAFF', 'PERSONNEL'), ('NO_CAPACITY', 'CAPACITY'), ('TECHNICAL', 'TECHNICAL')
) AS g(code, grp) WHERE r.code = g.code;

UPDATE rejection_reason r SET texts = jsonb_build_object('en', t.en) FROM (VALUES
  ('LEAD_TIME', 'The requested departure is too short notice.'),
  ('HORIZON', 'The requested time is too far in the future.'),
  ('WINDOW_NOT_FEASIBLE', 'No reachable pickup window can be formed for the requested arrival.'),
  ('OUTSIDE_HOURS', 'The service is not operating at the requested time.'),
  ('OUTSIDE_AREA', 'Origin or destination is outside the service area.'),
  ('RULE_FORBIDDEN', 'This connection is not permitted for the service.'),
  ('NO_STOP_IN_RANGE', 'There is no bookable stop within reach of origin or destination.'),
  ('NO_ACCESSIBLE_STOP', 'There is no wheelchair-suitable stop within reach.'),
  ('NO_ROUTE', 'There is no drivable connection between origin and destination.'),
  ('EQUIPMENT_MISSING', 'No vehicle with the required equipment is available.'),
  ('NO_STAFF', 'No driver is scheduled at the requested time.'),
  ('NO_CAPACITY', 'No seat is left at the requested time.'),
  ('TECHNICAL', 'The request could not be processed for technical reasons.'),
  ('NO_TARIFF', 'No valid fare is defined for the requested connection.')
) AS t(code, en) WHERE r.code = t.code;

ALTER TABLE rejection_reason
  ALTER COLUMN reason_group SET NOT NULL,
  ADD CONSTRAINT rejection_reason_group_known CHECK (reason_group IN ('CAPACITY', 'PERSONNEL', 'EQUIPMENT', 'AREA', 'TIME_OPENING', 'TIME_DEADLINE', 'RULE', 'TARIFF', 'LIMIT', 'PARTNER', 'TECHNICAL')),
  ADD CONSTRAINT rejection_reason_valid_range CHECK (valid_to IS NULL OR valid_to > valid_from),
  ADD CONSTRAINT rejection_reason_texts_object CHECK (jsonb_typeof(texts) = 'object'),
  ADD CONSTRAINT rejection_reason_technical_group CHECK ((reason_group = 'TECHNICAL') = (NOT counts_as_rejection));

-- Durchsetzung an der Faktenbasis: nur Katalogcodes (gültig zum Anfragezeitpunkt), fachlich zählende Gründe, Nebengründe nur mit Primärgrund, ohne Wiederholung und ohne den Primärgrund.
CREATE FUNCTION fact_attempt_reason_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE c text; d timestamptz; n integer;
BEGIN
  d := NEW.requested_at;
  IF NEW.primary_reason IS NOT NULL THEN
    SELECT count(*) INTO n FROM rejection_reason r WHERE r.code = NEW.primary_reason AND r.valid_from <= d AND (r.valid_to IS NULL OR r.valid_to > d);
    IF n = 0 THEN RAISE EXCEPTION 'fact_attempt: unbekannter Ablehnungsgrund %', NEW.primary_reason USING ERRCODE = '23503'; END IF;
    SELECT count(*) INTO n FROM rejection_reason r WHERE r.code = NEW.primary_reason AND r.counts_as_rejection;
    IF n = 0 THEN RAISE EXCEPTION 'fact_attempt: % ist keine fachliche Ablehnung', NEW.primary_reason USING ERRCODE = '23514'; END IF;
  END IF;
  IF cardinality(NEW.secondary_reasons) > 0 AND NEW.primary_reason IS NULL THEN
    RAISE EXCEPTION 'fact_attempt: Nebengruende ohne Primaergrund' USING ERRCODE = '23514';
  END IF;
  IF (SELECT count(DISTINCT s) FROM unnest(NEW.secondary_reasons) s) <> cardinality(NEW.secondary_reasons) THEN
    RAISE EXCEPTION 'fact_attempt: doppelte Nebengruende' USING ERRCODE = '23514';
  END IF;
  FOREACH c IN ARRAY NEW.secondary_reasons LOOP
    SELECT count(*) INTO n FROM rejection_reason r WHERE r.code = c AND r.valid_from <= d AND (r.valid_to IS NULL OR r.valid_to > d);
    IF n = 0 THEN RAISE EXCEPTION 'fact_attempt: unbekannter Ablehnungsgrund %', c USING ERRCODE = '23503'; END IF;
    SELECT count(*) INTO n FROM rejection_reason r WHERE r.code = c AND r.counts_as_rejection;
    IF n = 0 OR c = NEW.primary_reason THEN RAISE EXCEPTION 'fact_attempt: % ist als Nebengrund nicht zulaessig', c USING ERRCODE = '23514'; END IF;
  END LOOP;
  RETURN NEW;
END $$;
CREATE TRIGGER fact_attempt_reason_guard BEFORE INSERT OR UPDATE ON fact_attempt FOR EACH ROW EXECUTE FUNCTION fact_attempt_reason_guard();
