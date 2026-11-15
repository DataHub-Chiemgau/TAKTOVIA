-- WP-M4-55 / LH-I.6-KPI-009: nicht durchgeführte Fahrten mit Ursache und Verursachungskategorie (Modul analytics).
-- 1. not_performed_cause: Ursachenkatalog je Mandant (Ursache, Verursachungskategorie, gültig von/bis), je Mandant beim ersten Zugriff aus dem Arbeitsvorschlag angelegt (seed_not_performed_causes), danach
--    unveränderlich (Trigger, kein UPDATE/DELETE). Arbeitsvorschlag (OP 30: der verbindliche Katalog ist nicht festgelegt):
--    Kategorien Betreiber, Fahrgast, extern; Fahrzeugausfall (Betreiber) und Fahrgast-No-Show (Fahrgast) sind verschiedene Ursachen mit verschiedener Kategorie. Keine Personenzuordnung: die Kategorie nennt nie
--    eine Einzelperson (keine automatische Schuldzuweisung).
-- 2. not_performed_trip: Fakt "Fahrt nicht durchgeführt", abgeleitet aus fact_booking (bestätigte Buchung, die storniert wurde oder nicht angetreten wurde); genau eine wirksame Ursache je Fahrt oder der
--    Zustand OPEN ("Ursache offen", sichtbar, nie ausgeschlossen) bzw. CONFLICT (Ereignisse widersprechen sich, z. B. Fahrzeugausfall-Storno und No-Show: Konfliktmarkierung statt stiller Wahl, OP 30).
--    Wiederaufbaubar wie die übrigen Fakten; die Korrekturen überleben den Wiederaufbau.
-- 3. not_performed_correction: Korrekturvermerke, append-only (UPDATE/DELETE per Trigger und Recht gesperrt) mit Akteur, Zeit, Grund, altem und neuem Wert; je Fahrt fortlaufend nummeriert.
SET search_path = taktovia, public;

CREATE TABLE not_performed_cause (
  tenant_id   uuid        NOT NULL REFERENCES tenant(id),
  code        text        NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]{1,59}$'),
  category    text        NOT NULL CHECK (category IN ('OPERATOR', 'PASSENGER', 'EXTERNAL')),
  label       text        NOT NULL CHECK (length(label) BETWEEN 1 AND 120),
  derived_from text       NOT NULL CHECK (length(derived_from) BETWEEN 1 AND 200),
  valid_from  timestamptz NOT NULL DEFAULT '-infinity'::timestamptz,
  valid_to    timestamptz,
  PRIMARY KEY (tenant_id, code),
  CONSTRAINT not_performed_cause_range CHECK (valid_to IS NULL OR valid_to > valid_from)
);
CREATE TRIGGER not_performed_cause_immutable BEFORE UPDATE OR DELETE ON not_performed_cause FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- Arbeitsvorschlag des Katalogs für den Mandanten des Kontexts (wiederholbar, legt nur Fehlendes an).
CREATE FUNCTION seed_not_performed_causes() RETURNS integer LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE n integer;
BEGIN
  IF taktovia.current_tenant() IS NULL THEN RETURN 0; END IF;
  INSERT INTO not_performed_cause (tenant_id, code, category, label, derived_from)
  SELECT taktovia.current_tenant(), v.code, v.category, v.label, v.derived_from
  FROM (VALUES
    ('VEHICLE_OUTAGE',         'OPERATOR',  'Fahrzeugausfall',               'Storno mit Grund VEHICLE_OUTAGE (Störungsablauf WP-M3-54, Stornoursache WP-M2-27)'),
    ('PASSENGER_NO_SHOW',      'PASSENGER', 'Fahrgast-No-Show',              'Fahrerereignis NO_SHOW nach Ablauf der Wartefrist (WP-M3-29) oder Storno mit Grund NO_SHOW'),
    ('PASSENGER_CANCELLATION', 'PASSENGER', 'Storno durch den Fahrgast',     'Storno mit Grund PASSENGER_REQUEST'),
    ('OPERATOR_CANCELLATION',  'OPERATOR',  'Betreiberstorno',               'Storno mit Grund OPERATOR_CANCELLATION'),
    ('SAFETY_CANCELLATION',    'OPERATOR',  'Storno aus Sicherheitsgründen', 'Storno mit Grund SAFETY'),
    ('PAYMENT_FAILED',         'PASSENGER', 'Zahlung fehlgeschlagen',        'Storno mit Grund PAYMENT_FAILED'),
    ('EXTERNAL_EVENT',         'EXTERNAL',  'Externes Ereignis',             'nur per Korrektur (z. B. Sperrung, Unwetter)')
  ) AS v(code, category, label, derived_from)
  ON CONFLICT (tenant_id, code) DO NOTHING;
  GET DIAGNOSTICS n = ROW_COUNT;
  RETURN n;
END $$;

CREATE TABLE not_performed_trip (
  tenant_id        uuid        NOT NULL REFERENCES tenant(id),
  booking_id       uuid        NOT NULL,
  service_id       uuid,
  confirmed_at     timestamptz NOT NULL,
  pickup_earliest  timestamptz,
  not_performed_at timestamptz NOT NULL,
  derived_cause    text,
  cause_state      text        NOT NULL CHECK (cause_state IN ('OPEN', 'SET', 'CORRECTED', 'CONFLICT')),
  cause            text,
  category         text        CHECK (category IN ('OPERATOR', 'PASSENGER', 'EXTERNAL')),
  revision         bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  updated_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, booking_id),
  FOREIGN KEY (tenant_id, derived_cause) REFERENCES not_performed_cause (tenant_id, code),
  FOREIGN KEY (tenant_id, cause) REFERENCES not_performed_cause (tenant_id, code),
  -- genau eine wirksame Ursache (gesetzt oder korrigiert) oder keine (offen, Konflikt): nie halb
  CONSTRAINT not_performed_trip_cause_state CHECK ((cause_state IN ('SET', 'CORRECTED')) = (cause IS NOT NULL) AND (cause IS NULL) = (category IS NULL))
);
CREATE INDEX not_performed_trip_by_time ON not_performed_trip (tenant_id, not_performed_at);
CREATE INDEX not_performed_trip_by_pickup ON not_performed_trip (tenant_id, pickup_earliest);

CREATE TABLE not_performed_correction (
  tenant_id      uuid        NOT NULL REFERENCES tenant(id),
  id             uuid        NOT NULL,
  booking_id     uuid        NOT NULL,
  correction_no  integer     NOT NULL CHECK (correction_no >= 1),
  old_cause      text,
  old_category   text        CHECK (old_category IN ('OPERATOR', 'PASSENGER', 'EXTERNAL')),
  new_cause      text        NOT NULL,
  new_category   text        NOT NULL CHECK (new_category IN ('OPERATOR', 'PASSENGER', 'EXTERNAL')),
  reason         text        NOT NULL CHECK (length(trim(reason)) BETWEEN 3 AND 500),
  actor          text        NOT NULL CHECK (actor ~ '^(user|service|system):[A-Za-z0-9._:-]{1,120}$'),
  created_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  CONSTRAINT not_performed_correction_no UNIQUE (tenant_id, booking_id, correction_no),
  CONSTRAINT not_performed_correction_pair CHECK ((old_cause IS NULL) = (old_category IS NULL)),
  FOREIGN KEY (tenant_id, old_cause) REFERENCES not_performed_cause (tenant_id, code),
  FOREIGN KEY (tenant_id, new_cause) REFERENCES not_performed_cause (tenant_id, code)
);
CREATE TRIGGER not_performed_correction_append_only BEFORE UPDATE OR DELETE ON not_performed_correction FOR EACH ROW EXECUTE FUNCTION reject_mutation();

DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['not_performed_cause', 'not_performed_trip', 'not_performed_correction'] LOOP
    EXECUTE format('ALTER TABLE taktovia.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE taktovia.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON taktovia.%I USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant())', t);
    EXECUTE format('CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON taktovia.%I FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change()', t);
  END LOOP;
END $$;

-- Ableitung einer Fahrt aus dem Buchungsfakt (Funktion läuft als Aufrufer, RLS gilt). Wird von der Faktenprojektion nach jeder Neuableitung der Buchung gerufen und nach einer Korrektur.
-- Rangfolge/Konflikt (OP 30 offen): meldet die Buchung zugleich Fahrzeugausfall-Storno und No-Show, ist die Ursache nicht eindeutig: Zustand CONFLICT, keine stille Wahl.
-- Die wirksame Ursache ist die jüngste Korrektur (Vermerke bleiben), sonst die abgeleitete.
CREATE FUNCTION not_performed_refresh(p_booking uuid) RETURNS void LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE b fact_booking%ROWTYPE; derived text; st text; eff text; cat text; corr not_performed_correction%ROWTYPE; no_show boolean; outage boolean; at_ts timestamptz;
BEGIN
  PERFORM seed_not_performed_causes();
  SELECT * INTO b FROM fact_booking WHERE booking_id = p_booking;
  IF NOT FOUND OR b.confirmed_at IS NULL OR b.state NOT IN ('CANCELED', 'NO_SHOW') THEN
    DELETE FROM not_performed_trip WHERE booking_id = p_booking;      -- (noch) nicht oder nicht mehr eine nicht durchgeführte bestätigte Fahrt
    RETURN;
  END IF;
  no_show := b.state = 'NO_SHOW' OR b.cancel_reason = 'NO_SHOW';
  outage := b.cancel_reason = 'VEHICLE_OUTAGE' OR b.cause_class = 'FAHRZEUGAUSFALL';
  derived := CASE
    WHEN no_show AND outage THEN NULL
    WHEN no_show THEN 'PASSENGER_NO_SHOW'
    ELSE CASE b.cancel_reason WHEN 'VEHICLE_OUTAGE' THEN 'VEHICLE_OUTAGE' WHEN 'PASSENGER_REQUEST' THEN 'PASSENGER_CANCELLATION' WHEN 'OPERATOR_CANCELLATION' THEN 'OPERATOR_CANCELLATION'
                              WHEN 'SAFETY' THEN 'SAFETY_CANCELLATION' WHEN 'PAYMENT_FAILED' THEN 'PAYMENT_FAILED' ELSE NULL END
  END;
  at_ts := coalesce(b.no_show_at, b.cancelled_at, b.confirmed_at);
  SELECT * INTO corr FROM not_performed_correction WHERE booking_id = p_booking ORDER BY correction_no DESC LIMIT 1;
  IF FOUND THEN st := 'CORRECTED'; eff := corr.new_cause; cat := corr.new_category;
  ELSIF no_show AND outage THEN st := 'CONFLICT'; eff := NULL; cat := NULL;
  ELSIF derived IS NULL THEN st := 'OPEN'; eff := NULL; cat := NULL;
  ELSE st := 'SET'; eff := derived; SELECT c.category INTO cat FROM not_performed_cause c WHERE c.tenant_id = current_tenant() AND c.code = derived;
  END IF;
  INSERT INTO not_performed_trip (tenant_id, booking_id, service_id, confirmed_at, pickup_earliest, not_performed_at, derived_cause, cause_state, cause, category)
  VALUES (current_tenant(), p_booking, b.service_id, b.confirmed_at, b.pickup_earliest, at_ts, derived, st, eff, cat)
  ON CONFLICT (tenant_id, booking_id) DO UPDATE SET
    service_id = EXCLUDED.service_id, confirmed_at = EXCLUDED.confirmed_at, pickup_earliest = EXCLUDED.pickup_earliest, not_performed_at = EXCLUDED.not_performed_at,
    revision = not_performed_trip.revision + CASE WHEN (not_performed_trip.cause_state, not_performed_trip.cause, not_performed_trip.derived_cause) IS DISTINCT FROM (EXCLUDED.cause_state, EXCLUDED.cause, EXCLUDED.derived_cause) THEN 1 ELSE 0 END,
    derived_cause = EXCLUDED.derived_cause, cause_state = EXCLUDED.cause_state, cause = EXCLUDED.cause, category = EXCLUDED.category,
    updated_at = clock_timestamp();
END $$;
