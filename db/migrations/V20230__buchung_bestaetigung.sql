-- WP-M2-23 / LH-6.4-BKG-003: Bestätigung als Buchung (Kap. 01 §1.4 Schritt 6, Kap. 04 §4.1).
-- Ergänzt `booking` additiv (expand): Fahrgastbezug, Journey-Hülle (OP-3, vertraege/m2-detailvertraege.md §5), Buchungsnummer (OP-42: je Mandant eindeutig, Format `BK-<Jahr>-<laufende Nummer, 6 Stellen>`),
-- Anlagezeit und Urheber. Die Spalten sind nullbar, weil Fixtures und Spike-Daten Buchungen ohne diese Angaben anlegen; der Confirm-Befehl setzt sie immer. `promise_snapshot` ist nach dem Anlegen
-- unveränderlich (Baustein WP-M1-27: eine geänderte Zusage wäre eine neue Revision, nicht ein UPDATE), der Zustand ist durch den Automaten `booking` gehärtet (WP-M1-17).
SET search_path = taktovia, public;

ALTER TABLE booking
  ADD COLUMN passenger_id uuid,
  ADD COLUMN journey_id   uuid,
  ADD COLUMN booking_no   text,
  ADD COLUMN created_at   timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN created_by   text,
  ADD CONSTRAINT booking_passenger_fk FOREIGN KEY (tenant_id, passenger_id) REFERENCES passenger_private(tenant_id, id),
  ADD CONSTRAINT booking_journey_fk   FOREIGN KEY (tenant_id, journey_id)   REFERENCES journey(tenant_id, id),
  ADD CONSTRAINT booking_no_unique    UNIQUE (tenant_id, booking_no),
  ADD CONSTRAINT booking_no_format    CHECK (booking_no IS NULL OR booking_no ~ '^BK-[0-9]{4}-[0-9]{6,}$'),
  ADD CONSTRAINT booking_snapshot_object CHECK (jsonb_typeof(promise_snapshot) = 'object');
CREATE INDEX booking_by_passenger ON booking (tenant_id, passenger_id, created_at, id);
CREATE INDEX booking_by_service   ON booking (tenant_id, service_id, created_at, id);

SELECT harden_state_column('taktovia.booking', 'state', 'booking');

-- Zusage und Bezüge sind nach dem Anlegen unveränderlich; die Revision zählt nur um genau 1 weiter und nur zusammen mit dem Zustand (Historie: WP-M2-25).
CREATE FUNCTION booking_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.promise_snapshot IS DISTINCT FROM OLD.promise_snapshot THEN
    RAISE EXCEPTION 'booking.promise_snapshot is immutable; a changed promise is a new revision' USING ERRCODE = '23514';
  END IF;
  IF (NEW.hold_id, NEW.service_id, NEW.federation_ref, NEW.created_at) IS DISTINCT FROM (OLD.hold_id, OLD.service_id, OLD.federation_ref, OLD.created_at)
     OR (OLD.passenger_id IS NOT NULL AND NEW.passenger_id IS DISTINCT FROM OLD.passenger_id)
     OR (OLD.journey_id IS NOT NULL AND NEW.journey_id IS DISTINCT FROM OLD.journey_id)
     OR (OLD.booking_no IS NOT NULL AND NEW.booking_no IS DISTINCT FROM OLD.booking_no)
     OR (OLD.created_by IS NOT NULL AND NEW.created_by IS DISTINCT FROM OLD.created_by) THEN
    RAISE EXCEPTION 'booking identity is immutable' USING ERRCODE = '23514';
  END IF;
  IF OLD.booking_no IS NOT NULL AND NEW.revision <> OLD.revision + 1 THEN
    RAISE EXCEPTION 'booking.revision advances by exactly one per change' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER booking_guard BEFORE UPDATE ON booking FOR EACH ROW EXECUTE FUNCTION booking_guard();

-- Buchungsnummer: Zähler je (Mandant, Jahr), lückenlos und doppelfrei in der Transaktion der Bestätigung (Zeilensperre serialisiert; Rollback nimmt die Vergabe zurück). Die Nummer
-- enthält keine Personendaten; die Zuordnung zum Mandanten gelingt nur über die mandantengebundene Buchungs-ID. Sie ist nicht geheim: der Zugriff wird über die Objektberechtigung geschützt.
CREATE TABLE booking_number_counter (
  tenant_id   uuid   NOT NULL REFERENCES tenant(id),
  number_year int    NOT NULL CHECK (number_year BETWEEN 2000 AND 2999),
  last_number bigint NOT NULL CHECK (last_number >= 1),
  PRIMARY KEY (tenant_id, number_year)
);
ALTER TABLE booking_number_counter ENABLE ROW LEVEL SECURITY;
ALTER TABLE booking_number_counter FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON booking_number_counter USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON booking_number_counter FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE FUNCTION booking_number_counter_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'booking_number_counter: kein Loeschen' USING ERRCODE = '23514'; END IF;
  IF NEW.number_year IS DISTINCT FROM OLD.number_year OR NEW.last_number <> OLD.last_number + 1 THEN
    RAISE EXCEPTION 'booking_number_counter: der Zaehler steigt nur um 1' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER booking_number_counter_guard BEFORE UPDATE OR DELETE ON booking_number_counter FOR EACH ROW EXECUTE FUNCTION booking_number_counter_guard();

CREATE FUNCTION next_booking_number() RETURNS text LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE y int := extract(year FROM clock_timestamp() AT TIME ZONE 'UTC')::int; n bigint;
BEGIN
  INSERT INTO booking_number_counter AS c (tenant_id, number_year, last_number) VALUES (taktovia.current_tenant(), y, 1)
  ON CONFLICT (tenant_id, number_year) DO UPDATE SET last_number = c.last_number + 1
  RETURNING c.last_number INTO n;
  RETURN 'BK-' || y || '-' || lpad(n::text, 6, '0');
END $$;
