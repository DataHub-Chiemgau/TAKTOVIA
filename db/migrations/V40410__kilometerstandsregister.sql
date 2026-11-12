-- WP-M4-41 (LH-I.2-FCO-003): Kilometerstandsregister (Modul documents-finance). Kilometerstände aus Fahrercheck, Tankbeleg, Werkstatt und Telematik werden mit Herkunft gespeichert,
-- append-only (kein UPDATE/DELETE, auch nicht für die Runtime-Rolle: nur SELECT und INSERT sind vergeben, zusätzlich `reject_mutation()`). Widersprüche werden als Konflikt erfasst und nie
-- durch Überschreiben aufgelöst: die Auflösung ist ein eigener append-only Eintrag mit Akteur, Grund und Zeit; der Status offen/aufgelöst ergibt sich aus dessen Vorhandensein.
-- Tabellennamen sind Vorschläge des Plans (Kap. 04 §4.4 benennt sie nicht, Offener Punkt 21).
SET search_path = taktovia, public;

CREATE TABLE odometer_reading (
  tenant_id   uuid        NOT NULL REFERENCES tenant(id),
  id          uuid        NOT NULL,
  vehicle_id  uuid        NOT NULL,
  reading_km  bigint      NOT NULL CONSTRAINT odometer_reading_range CHECK (reading_km >= 0 AND reading_km <= 10000000),
  source      text        NOT NULL CONSTRAINT odometer_reading_source CHECK (source IN ('DRIVER_CHECK', 'FUEL_RECEIPT', 'WORKSHOP', 'TELEMATICS')),
  source_ref  text        NOT NULL CONSTRAINT odometer_reading_ref CHECK (length(source_ref) BETWEEN 1 AND 200),   -- Bezug auf das Quellobjekt (z. B. Prüflauf, Beleg, Position)
  measured_at timestamptz NOT NULL,
  recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  recorded_by text        NOT NULL CHECK (length(recorded_by) BETWEEN 1 AND 200),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, source, source_ref),   -- Einmaligkeit bei wiederholter Zustellung desselben Quellereignisses
  FOREIGN KEY (tenant_id, vehicle_id) REFERENCES vehicle(tenant_id, id)
);
CREATE INDEX odometer_reading_by_vehicle ON odometer_reading (tenant_id, vehicle_id, measured_at, id);

CREATE TABLE odometer_conflict (
  tenant_id          uuid        NOT NULL REFERENCES tenant(id),
  id                 uuid        NOT NULL,
  vehicle_id         uuid        NOT NULL,
  kind               text        NOT NULL CHECK (kind IN ('DECREASING', 'SOURCE_MISMATCH')),
  earlier_reading_id uuid        NOT NULL,   -- der früher gemessene Stand
  later_reading_id   uuid        NOT NULL,   -- der später gemessene Stand (bei SOURCE_MISMATCH: der zuletzt erfasste der Gegenüberstellung)
  tolerance_km       integer     NOT NULL CHECK (tolerance_km >= 0),
  detected_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, earlier_reading_id, later_reading_id, kind),
  FOREIGN KEY (tenant_id, vehicle_id) REFERENCES vehicle(tenant_id, id),
  FOREIGN KEY (tenant_id, earlier_reading_id) REFERENCES odometer_reading(tenant_id, id),
  FOREIGN KEY (tenant_id, later_reading_id) REFERENCES odometer_reading(tenant_id, id),
  CHECK (earlier_reading_id <> later_reading_id)
);
CREATE INDEX odometer_conflict_by_vehicle ON odometer_conflict (tenant_id, vehicle_id, detected_at, id);

CREATE TABLE odometer_conflict_resolution (
  tenant_id         uuid        NOT NULL REFERENCES tenant(id),
  id                uuid        NOT NULL,
  conflict_id       uuid        NOT NULL,
  decision          text        NOT NULL CHECK (decision IN ('CHOSEN', 'DISCARDED')),
  chosen_reading_id uuid,
  reason            text        NOT NULL CONSTRAINT odometer_resolution_reason CHECK (length(btrim(reason)) BETWEEN 3 AND 1000),
  resolved_by       text        NOT NULL CHECK (length(resolved_by) BETWEEN 1 AND 200),
  resolved_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, conflict_id),   -- höchstens eine Auflösung je Konflikt, auch bei paralleler Auflösung
  FOREIGN KEY (tenant_id, conflict_id) REFERENCES odometer_conflict(tenant_id, id),
  FOREIGN KEY (tenant_id, chosen_reading_id) REFERENCES odometer_reading(tenant_id, id),
  CONSTRAINT odometer_resolution_choice CHECK ((decision = 'CHOSEN') = (chosen_reading_id IS NOT NULL))
);

-- Konfliktparameter je Mandant (Planungsannahme, Offener Punkt 21; nicht im Parameterkatalog): Toleranz in km und Zeitfenster für „selber Zeitraum“. Ohne Zeile: 5 km und 60 Minuten.
CREATE TABLE odometer_policy (
  tenant_id      uuid        NOT NULL REFERENCES tenant(id),
  tolerance_km   integer     NOT NULL DEFAULT 5 CHECK (tolerance_km BETWEEN 0 AND 1000),
  window_minutes integer     NOT NULL DEFAULT 60 CHECK (window_minutes BETWEEN 1 AND 10080),
  revision       bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  updated_by     text        NOT NULL CHECK (length(updated_by) BETWEEN 1 AND 200),
  updated_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id)
);

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['odometer_reading', 'odometer_conflict', 'odometer_conflict_resolution', 'odometer_policy'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant())', t);
    EXECUTE format('CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change()', t);
  END LOOP;
  FOREACH t IN ARRAY ARRAY['odometer_reading', 'odometer_conflict', 'odometer_conflict_resolution'] LOOP
    EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION taktovia.reject_mutation()', t || '_immutable', t);
  END LOOP;
END $$;

-- Eine Auflösung wählt nur einen der beiden Stände des Konflikts (nie einen beliebigen anderen Stand).
CREATE FUNCTION odometer_resolution_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.chosen_reading_id IS NOT NULL AND NOT EXISTS (
      SELECT 1 FROM odometer_conflict c WHERE c.tenant_id = NEW.tenant_id AND c.id = NEW.conflict_id AND NEW.chosen_reading_id IN (c.earlier_reading_id, c.later_reading_id)) THEN
    RAISE EXCEPTION 'odometer_conflict_resolution: gewaehlter Stand gehoert nicht zum Konflikt' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER odometer_resolution_guard BEFORE INSERT ON odometer_conflict_resolution FOR EACH ROW EXECUTE FUNCTION odometer_resolution_guard();
