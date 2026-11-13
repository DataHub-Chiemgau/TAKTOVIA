-- WP-M4-42 (LH-I.2-FCO-005): Klassifikation der Fahrzeugkilometer (Modul analytics). Tabellennamen sind Vorschläge des Plans (Überführung und Werkstattfahrt haben im Pflichtenheft kein Datenmodell, OP-22).
-- vehicle_km_run: ein Klassifikationslauf je Fahrzeug, Betriebstag (UTC) und Eingangszustand (`input_sha256` über Positionen, Fahrtenplan und Fahrerereignisse). Ein neuer Eingangszustand (z. B. nachträglich
--   eintreffende Positionsdaten) erzeugt einen NEUEN Lauf mit neuem Datenstand (`data_as_of`); ein bestehender Lauf wird nie überschrieben. Aktuell ist der Lauf mit dem jüngsten `data_as_of`.
-- vehicle_km_segment: Kilometerabschnitte je Lauf und Kategorie. `km_thousandths` sind Tausendstel Kilometer (= Meter, ganzzahlig, keine Gleitkommasummen).
-- Invariante (deferred Prüfung): Summe aller Abschnitte eines Laufs (vier Kategorien + UNASSIGNED) = `total_km_thousandths`; UNASSIGNED ist der Rest zur Gesamtstrecke und wird nie verteilt.
-- Beide Tabellen sind append-only. Abschnitte eines Laufs überlappen nicht (Exclusion Constraint; ein Lauf gehört zu genau einem Fahrzeug).
SET search_path = taktovia, public;

CREATE TABLE vehicle_km_run (
  tenant_id            uuid        NOT NULL REFERENCES tenant(id),
  id                   uuid        NOT NULL,
  vehicle_id           uuid        NOT NULL,
  service_day          date        NOT NULL,
  input_sha256         char(64)    NOT NULL CHECK (input_sha256 ~ '^[0-9a-f]{64}$'),
  data_as_of           timestamptz NOT NULL,
  rule_version         integer     NOT NULL CHECK (rule_version >= 1),
  position_count       integer     NOT NULL CHECK (position_count >= 0),
  events_complete      boolean     NOT NULL,
  total_km_thousandths bigint      NOT NULL CHECK (total_km_thousandths >= 0),
  created_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, id, vehicle_id, service_day),
  UNIQUE (tenant_id, vehicle_id, service_day, input_sha256),   -- derselbe Eingangszustand ergibt keinen zweiten Lauf, auch bei paralleler Klassifikation
  FOREIGN KEY (tenant_id, vehicle_id) REFERENCES vehicle(tenant_id, id)
);
CREATE INDEX vehicle_km_run_by_vehicle_day ON vehicle_km_run (tenant_id, vehicle_id, service_day, data_as_of DESC);

CREATE TABLE vehicle_km_segment (
  tenant_id       uuid        NOT NULL REFERENCES tenant(id),
  id              uuid        NOT NULL,
  run_id          uuid        NOT NULL,
  vehicle_id      uuid        NOT NULL,
  service_day     date        NOT NULL,
  period_from     timestamptz NOT NULL,
  period_to       timestamptz NOT NULL,
  category        text        NOT NULL CONSTRAINT vehicle_km_segment_category CHECK (category IN ('PASSENGER', 'EMPTY', 'TRANSFER', 'WORKSHOP', 'UNASSIGNED')),
  km_thousandths  bigint      NOT NULL CONSTRAINT vehicle_km_segment_km CHECK (km_thousandths >= 0),
  source          text        NOT NULL CONSTRAINT vehicle_km_segment_source CHECK (source IN ('ROUTING', 'ODOMETER', 'POSITION')),
  rule_version    integer     NOT NULL CHECK (rule_version >= 1),
  PRIMARY KEY (tenant_id, id),
  CONSTRAINT vehicle_km_segment_period CHECK (period_to > period_from),
  CONSTRAINT vehicle_km_segment_in_day CHECK (period_from >= (service_day::timestamp AT TIME ZONE 'UTC') AND period_to <= ((service_day + 1)::timestamp AT TIME ZONE 'UTC')),
  FOREIGN KEY (tenant_id, run_id, vehicle_id, service_day) REFERENCES vehicle_km_run(tenant_id, id, vehicle_id, service_day),
  FOREIGN KEY (tenant_id, vehicle_id) REFERENCES vehicle(tenant_id, id),
  CONSTRAINT vehicle_km_segment_no_overlap EXCLUDE USING gist (tenant_id WITH =, run_id WITH =, tstzrange(period_from, period_to) WITH &&)
);
CREATE INDEX vehicle_km_segment_by_run ON vehicle_km_segment (tenant_id, run_id, period_from);

DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['vehicle_km_run', 'vehicle_km_segment'] LOOP
    EXECUTE format('ALTER TABLE taktovia.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE taktovia.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON taktovia.%I USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant())', t);
    EXECUTE format('CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON taktovia.%I FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change()', t);
    EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON taktovia.%I FOR EACH ROW EXECUTE FUNCTION taktovia.reject_mutation()', t || '_immutable', t);
  END LOOP;
END $$;

-- Summeninvariante je Lauf, am Ende der Transaktion geprüft (Lauf und Abschnitte entstehen gemeinsam).
CREATE FUNCTION vehicle_km_sum_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE rid uuid; tid uuid; total bigint; segs bigint;
BEGIN
  IF TG_TABLE_NAME = 'vehicle_km_run' THEN rid := NEW.id; ELSE rid := NEW.run_id; END IF;
  tid := NEW.tenant_id;
  SELECT total_km_thousandths INTO total FROM vehicle_km_run WHERE tenant_id = tid AND id = rid;
  SELECT coalesce(sum(km_thousandths), 0) INTO segs FROM vehicle_km_segment WHERE tenant_id = tid AND run_id = rid;
  IF total IS DISTINCT FROM segs THEN
    RAISE EXCEPTION 'vehicle_km_run: Gesamtkilometer (%) ungleich Summe der Abschnitte (%)', total, segs USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER vehicle_km_run_sum AFTER INSERT ON vehicle_km_run DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION vehicle_km_sum_guard();
CREATE CONSTRAINT TRIGGER vehicle_km_segment_sum AFTER INSERT ON vehicle_km_segment DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION vehicle_km_sum_guard();
