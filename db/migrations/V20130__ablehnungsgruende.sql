-- WP-M2-13 / LH-6.5-OPT-011: Katalog der standardisierten Ablehnungsgründe (`rejection_reason`, Kap. 04 §4.4, ANA/KPI) und seine Durchsetzung an `travel_attempt`.
-- Globale Tabelle ohne tenant_id; die Runtime-Rolle liest nur, Pflege ausschließlich per Migration. Der Katalog ist ein Vorschlag des Plans (M2 OP-14); die Prioritäten
-- bestimmen den Primärgrund deterministisch (kleinste Zahl gewinnt) und müssen mit `RejectionCode` im Modul `booking-dispatch` übereinstimmen (ein Test vergleicht beide).
SET search_path = taktovia, public;

CREATE TABLE rejection_reason (
  code                text    PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
  priority            integer NOT NULL CHECK (priority > 0),
  counts_as_rejection boolean NOT NULL,                                                       -- FALSE: technischer Fehler, nie eine fachliche Ablehnung
  metric              text    CHECK (metric IN ('CAPACITY', 'PERSONNEL', 'EQUIPMENT', 'AREA', 'TIME_WINDOW')),   -- Kennzahl K.1; NULL: keine eigene Kennzahl
  label               text    NOT NULL CHECK (length(label) BETWEEN 1 AND 300),
  next_action         text    NOT NULL CHECK (length(next_action) BETWEEN 1 AND 300),
  CHECK (counts_as_rejection OR metric IS NULL)
);

-- Eindeutige Priorität als Index (globaler Katalog ohne tenant_id: die Konvention „UNIQUE beginnt mit tenant_id“ gilt für mandantenlokale Tabellen)
CREATE UNIQUE INDEX rejection_reason_priority_uq ON rejection_reason (priority);

INSERT INTO rejection_reason (code, priority, counts_as_rejection, metric, label, next_action) VALUES
  ('LEAD_TIME',           10,   true,  'TIME_WINDOW', 'Die gewünschte Abfahrt liegt zu kurzfristig.',                                           'Wählen Sie eine spätere Abfahrtszeit.'),
  ('HORIZON',             20,   true,  'TIME_WINDOW', 'Die gewünschte Zeit liegt zu weit in der Zukunft.',                                      'Wählen Sie einen näheren Termin.'),
  ('WINDOW_NOT_FEASIBLE', 30,   true,  'TIME_WINDOW', 'Für die gewünschte Ankunft lässt sich kein erreichbares Abholfenster bilden.',            'Wählen Sie eine spätere Ankunftszeit.'),
  ('OUTSIDE_HOURS',       40,   true,  'TIME_WINDOW', 'Der Dienst ist zur gewünschten Zeit nicht in Betrieb.',                                  'Wählen Sie eine Zeit innerhalb der Betriebszeiten.'),
  ('OUTSIDE_AREA',        50,   true,  'AREA',        'Start oder Ziel liegt außerhalb des Bediengebiets.',                                     'Wählen Sie Start und Ziel innerhalb des Bediengebiets.'),
  ('RULE_FORBIDDEN',      60,   true,  NULL,          'Diese Verbindung ist für den Dienst nicht zugelassen.',                                  'Wählen Sie ein anderes Ziel oder einen anderen Start.'),
  ('NO_STOP_IN_RANGE',    70,   true,  'AREA',        'In Reichweite von Start oder Ziel gibt es keinen buchbaren Haltepunkt.',                 'Wählen Sie einen Start oder ein Ziel näher an einem Haltepunkt.'),
  ('NO_ACCESSIBLE_STOP',  75,   true,  'EQUIPMENT',   'In Reichweite gibt es keinen für Rollstuhlnutzung geeigneten Haltepunkt.',               'Wählen Sie einen geeigneten Haltepunkt oder kontaktieren Sie den Betreiber.'),
  ('NO_ROUTE',            80,   true,  'CAPACITY',    'Zwischen Start und Ziel besteht keine befahrbare Verbindung.',                           'Wählen Sie ein anderes Ziel.'),
  ('EQUIPMENT_MISSING',   90,   true,  'EQUIPMENT',   'Kein Fahrzeug mit der benötigten Ausstattung verfügbar.',                                'Wählen Sie eine andere Zeit oder verzichten Sie auf die Zusatzausstattung.'),
  ('NO_STAFF',            100,  true,  'PERSONNEL',   'Zur gewünschten Zeit ist kein Fahrpersonal eingeplant.',                                 'Wählen Sie eine andere Zeit.'),
  ('NO_CAPACITY',         110,  true,  'CAPACITY',    'Zur gewünschten Zeit ist kein Platz mehr frei.',                                         'Wählen Sie eine andere Zeit oder verringern Sie die Personenzahl.'),
  ('TECHNICAL',           1000, false, NULL,          'Die Anfrage konnte technisch nicht bearbeitet werden.',                                  'Versuchen Sie es in einigen Minuten erneut.');

-- Durchsetzung am Versuch: bekannte, fachlich zählende Codes; genau ein Primärgrund; Nebengründe nur zusätzlich, ohne Wiederholung und ohne den Primärgrund.
-- Ein technisch gescheiterter Versuch (outcome TECHNICAL_ERROR) trägt keinen Grund und darf genau einmal in ein fachliches Ergebnis übergehen (derselbe Versuch beim Retry);
-- ein fachliches Ergebnis (OFFERED/REJECTED) ist endgültig.
CREATE FUNCTION travel_attempt_rejection_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE c text; n integer;
BEGIN
  IF NEW.primary_rejection IS NOT NULL THEN
    SELECT count(*) INTO n FROM rejection_reason r WHERE r.code = NEW.primary_rejection;
    IF n = 0 THEN RAISE EXCEPTION 'travel_attempt: unbekannter Ablehnungsgrund %', NEW.primary_rejection USING ERRCODE = '23503'; END IF;
    SELECT count(*) INTO n FROM rejection_reason r WHERE r.code = NEW.primary_rejection AND r.counts_as_rejection;
    IF n = 0 THEN RAISE EXCEPTION 'travel_attempt: % ist keine fachliche Ablehnung', NEW.primary_rejection USING ERRCODE = '23514'; END IF;
  END IF;
  IF cardinality(NEW.secondary_rejections) > 0 AND NEW.primary_rejection IS NULL THEN
    RAISE EXCEPTION 'travel_attempt: Nebengruende ohne Primaergrund' USING ERRCODE = '23514';
  END IF;
  IF (SELECT count(DISTINCT s) FROM unnest(NEW.secondary_rejections) s) <> cardinality(NEW.secondary_rejections) THEN
    RAISE EXCEPTION 'travel_attempt: doppelte Nebengruende' USING ERRCODE = '23514';
  END IF;
  FOREACH c IN ARRAY NEW.secondary_rejections LOOP
    SELECT count(*) INTO n FROM rejection_reason r WHERE r.code = c;
    IF n = 0 THEN RAISE EXCEPTION 'travel_attempt: unbekannter Ablehnungsgrund %', c USING ERRCODE = '23503'; END IF;
    SELECT count(*) INTO n FROM rejection_reason r WHERE r.code = c AND r.counts_as_rejection;
    IF n = 0 OR c = NEW.primary_rejection THEN RAISE EXCEPTION 'travel_attempt: % ist als Nebengrund nicht zulaessig', c USING ERRCODE = '23514'; END IF;
  END LOOP;
  IF TG_OP = 'UPDATE' AND OLD.outcome IN ('OFFERED', 'REJECTED')
     AND (NEW.outcome IS DISTINCT FROM OLD.outcome OR NEW.primary_rejection IS DISTINCT FROM OLD.primary_rejection OR NEW.secondary_rejections IS DISTINCT FROM OLD.secondary_rejections) THEN
    RAISE EXCEPTION 'travel_attempt: das Ergebnis eines Versuchs ist endgueltig' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER travel_attempt_rejection_guard BEFORE INSERT OR UPDATE ON travel_attempt FOR EACH ROW EXECUTE FUNCTION travel_attempt_rejection_guard();
