-- WP-M2-28 / LH-6.5-OPT-010: Planrevisionen verteilen und Clients synchronisieren.
-- 1. plan_change: je Planrevision eines Umlaufs genau eine Zeile (append-only), in der Transaktion der Planänderung geschrieben. `seq` ist der Cursor des
--    Änderungsstroms (monoton, aber nicht lückenlos in Commit-Reihenfolge: Clients erkennen Lücken an den Revisionen je Aggregat und lesen dann den Snapshot nach).
-- 2. plan_change_booking: je Planänderung und betroffener Buchung eine Zeile (Art, Abholzeit und Ausstiegszeit vorher/nachher) mit `booking_seq`, der fortlaufenden Nummer
--    der Änderungen dieser Buchung (1, 2, 3 ...). Fahrgäste folgen diesem Strom je Buchung: andere Buchungen desselben Umlaufs erreichen sie nie. Kein Fremdschlüssel auf
--    `booking`: die Planänderung des Holds entsteht, bevor die Buchung bestätigt ist.
SET search_path = taktovia, public;

CREATE TABLE plan_change (
  tenant_id         uuid        NOT NULL REFERENCES tenant(id),
  id                uuid        NOT NULL,
  seq               bigint      GENERATED ALWAYS AS IDENTITY,
  run_id            uuid        NOT NULL,
  run_revision      bigint      NOT NULL CHECK (run_revision >= 2),
  previous_revision bigint      NOT NULL,
  cause             text        NOT NULL CHECK (cause IN ('BOOKING_INSERTED', 'BOOKING_REMOVED', 'MANUAL_CHANGE', 'REPLAN')),
  recorded_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, run_id, run_revision),
  UNIQUE (tenant_id, seq),
  CHECK (previous_revision = run_revision - 1),
  FOREIGN KEY (tenant_id, run_id) REFERENCES vehicle_run(tenant_id, id)
);

CREATE TABLE plan_change_booking (
  tenant_id       uuid        NOT NULL REFERENCES tenant(id),
  plan_change_id  uuid        NOT NULL,
  booking_id      uuid        NOT NULL,
  kind            text        NOT NULL CHECK (kind IN ('ADDED', 'REMOVED', 'TIME_CHANGED', 'STOP_CHANGED', 'TOUCHED')),
  booking_seq     bigint      NOT NULL CHECK (booking_seq >= 1),
  pickup_before   timestamptz,
  pickup_after    timestamptz,
  dropoff_before  timestamptz,
  dropoff_after   timestamptz,
  PRIMARY KEY (tenant_id, plan_change_id, booking_id),
  UNIQUE (tenant_id, booking_id, booking_seq),
  FOREIGN KEY (tenant_id, plan_change_id) REFERENCES plan_change(tenant_id, id)
);
CREATE INDEX plan_change_by_run ON plan_change (tenant_id, run_id, run_revision);
CREATE INDEX plan_change_booking_by_booking ON plan_change_booking (tenant_id, booking_id, booking_seq DESC);

ALTER TABLE plan_change ENABLE ROW LEVEL SECURITY;
ALTER TABLE plan_change FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON plan_change USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON plan_change FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE TRIGGER plan_change_immutable BEFORE UPDATE OR DELETE ON plan_change FOR EACH ROW EXECUTE FUNCTION reject_mutation();

ALTER TABLE plan_change_booking ENABLE ROW LEVEL SECURITY;
ALTER TABLE plan_change_booking FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON plan_change_booking USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON plan_change_booking FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE TRIGGER plan_change_booking_immutable BEFORE UPDATE OR DELETE ON plan_change_booking FOR EACH ROW EXECUTE FUNCTION reject_mutation();
