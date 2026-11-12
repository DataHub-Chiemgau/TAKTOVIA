-- WP-M3-43 / LH-6.22-DMG-001, DMG-002: Schadenakte, Meldung, Pflichtdaten, Fallnummer.
-- Additiv zu V3020 (defect_case besteht dort): Fallnummernzähler je Mandant und Jahr, Geräteereignis-ID zur Dublettenerkennung, Plausibilität der Ereigniszeit,
-- Index zur Auffindbarkeit über Fahrzeug und Meldezeitpunkt. Fallnummernformat: SCH-<Jahr>-<laufende Nummer, mindestens sechsstellig>, z. B. SCH-2026-000123.
SET search_path = taktovia, public;

-- Zähler je (Mandant, Jahr). Die Vergabe läuft in der Transaktion der Anlage: die Zeilensperre des Zählers serialisiert parallele Meldungen desselben Mandanten,
-- ein Rollback der Anlage nimmt die Vergabe zurück. Dadurch sind Nummern je Mandant und Jahr lückenlos und doppelfrei. Die Nummer enthält keine Personendaten;
-- gleich lautende Nummern verschiedener Mandanten verraten den Mandanten nicht (Zuordnung nur über die mandantengebundene Akten-ID).
CREATE TABLE defect_case_counter (
  tenant_id   uuid   NOT NULL REFERENCES tenant(id),
  case_year   int    NOT NULL CHECK (case_year BETWEEN 2000 AND 2999),
  last_number bigint NOT NULL CHECK (last_number >= 1),
  PRIMARY KEY (tenant_id, case_year)
);
ALTER TABLE defect_case_counter ENABLE ROW LEVEL SECURITY;
ALTER TABLE defect_case_counter FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON defect_case_counter USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON defect_case_counter FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();

-- Der Zähler steigt nur und genau um 1 (keine Sprünge, kein Zurücksetzen, auch nicht per SQL der Anwendung).
CREATE FUNCTION defect_case_counter_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'defect_case_counter: kein Loeschen' USING ERRCODE = '23514'; END IF;
  IF NEW.case_year IS DISTINCT FROM OLD.case_year OR NEW.last_number <> OLD.last_number + 1 THEN
    RAISE EXCEPTION 'defect_case_counter: der Zaehler steigt nur um 1' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER defect_case_counter_guard BEFORE UPDATE OR DELETE ON defect_case_counter FOR EACH ROW EXECUTE FUNCTION defect_case_counter_guard();

-- Vergibt die nächste Fallnummer des Mandanten im Tenantkontext (RLS wirkt, SECURITY INVOKER).
CREATE FUNCTION next_defect_case_number() RETURNS text LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE y int := extract(year FROM clock_timestamp() AT TIME ZONE 'UTC')::int; n bigint;
BEGIN
  INSERT INTO defect_case_counter AS c (tenant_id, case_year, last_number) VALUES (taktovia.current_tenant(), y, 1)
  ON CONFLICT (tenant_id, case_year) DO UPDATE SET last_number = c.last_number + 1
  RETURNING c.last_number INTO n;
  RETURN 'SCH-' || y || '-' || lpad(n::text, 6, '0');
END $$;

-- Geräteereignis-ID: dieselbe Meldung vom Gerät (Wiederholung nach Zeitüberschreitung, Offline-Synchronisation) erzeugt keine zweite Akte.
ALTER TABLE defect_case
  ADD COLUMN device_event_id uuid,
  ADD COLUMN content_version int NOT NULL DEFAULT 1 CHECK (content_version >= 1),
  -- Auswahlfelder der geführten Erfassung (DMG-004, Kataloge im Detailvertrag m3-schadenakte.md); Katalogprüfung in der Anwendung, hier nur das Codemuster
  ADD COLUMN kind text CHECK (kind IS NULL OR kind ~ '^[A-Z][A-Z0-9_]{1,39}$'),
  ADD COLUMN vehicle_area text CHECK (vehicle_area IS NULL OR vehicle_area ~ '^[A-Z][A-Z0-9_]{1,39}$'),
  ADD COLUMN observed_effect text CHECK (observed_effect IS NULL OR observed_effect ~ '^[A-Z][A-Z0-9_]{1,39}$'),
  ADD CONSTRAINT defect_case_device_event_unique UNIQUE (tenant_id, device_event_id);

-- Auffindbarkeit über Fahrzeug und Datum (DMG-002 Originalabnahme)
CREATE INDEX defect_case_by_vehicle_reported ON defect_case (tenant_id, vehicle_id, reported_at);

-- Plausibilität bei der Anlage: die Ereigniszeit liegt nicht in der Zukunft (60 s Toleranz für Uhrenabweichung des Geräts); die Meldezeit setzt der Server.
CREATE FUNCTION defect_case_plausibility() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.occurred_at > clock_timestamp() + interval '60 seconds' THEN
    RAISE EXCEPTION 'defect_case: Ereigniszeit liegt in der Zukunft' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER defect_case_plausibility BEFORE INSERT ON defect_case FOR EACH ROW EXECUTE FUNCTION defect_case_plausibility();
