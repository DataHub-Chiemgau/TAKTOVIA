-- WP-M3-22 / LH-6.21-CHK-015: Fahrzeugwechsel in laufender Schicht (Entscheidung OP-08 als Planungsannahme: der Wechsel ändert `staff_shift.vehicle_id` der Schicht, die Schicht bleibt dieselbe;
-- kein `vehicle_assignment`, das ist die Rollenzuordnung des Fahrzeugs aus WP-M1-34). Jeder Wechsel ist eine unveränderliche Zeile `shift_vehicle_switch`; die Einsatzfreigabe des Ersatzfahrzeugs
-- verlangt einen Prüflauf, der nach dem jüngsten Wechsel der Schicht angelegt wurde (Prüfung des alten Fahrzeugs trägt nie, auch nicht bei Rückwechsel).
SET search_path = taktovia, public;

CREATE TABLE shift_vehicle_switch (
  tenant_id       uuid        NOT NULL REFERENCES tenant(id),
  id              uuid        NOT NULL,
  shift_id        uuid        NOT NULL,
  driver_ref      uuid        NOT NULL,
  from_vehicle_id uuid        NOT NULL,
  to_vehicle_id   uuid        NOT NULL,
  reason_code     text        NOT NULL CHECK (reason_code IN ('DEFECT', 'BREAKDOWN', 'SERVICE_NEED', 'CAPACITY', 'OTHER')),
  reason_note     text        CHECK (reason_note IS NULL OR length(btrim(reason_note)) BETWEEN 1 AND 500),
  actor           text        NOT NULL CHECK (actor ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  actor_role      text        NOT NULL CHECK (actor_role IN ('DRIVER', 'DISPATCH')),
  revoked_releases int        NOT NULL DEFAULT 0 CHECK (revoked_releases >= 0),
  created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, shift_id)        REFERENCES staff_shift(tenant_id, id),
  FOREIGN KEY (tenant_id, from_vehicle_id) REFERENCES vehicle(tenant_id, id),
  FOREIGN KEY (tenant_id, to_vehicle_id)   REFERENCES vehicle(tenant_id, id),
  CONSTRAINT shift_vehicle_switch_distinct CHECK (from_vehicle_id <> to_vehicle_id),
  -- ohne Begründung kein Wechsel: der Code genügt, bei `OTHER` ist der Hinweistext Pflicht
  CONSTRAINT shift_vehicle_switch_other_needs_note CHECK (reason_code <> 'OTHER' OR reason_note IS NOT NULL)
);
CREATE INDEX shift_vehicle_switch_by_shift ON shift_vehicle_switch (tenant_id, shift_id, created_at DESC);
ALTER TABLE shift_vehicle_switch ENABLE ROW LEVEL SECURITY;
ALTER TABLE shift_vehicle_switch FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON shift_vehicle_switch USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON shift_vehicle_switch FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE TRIGGER shift_vehicle_switch_immutable BEFORE UPDATE OR DELETE ON shift_vehicle_switch FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- Qualifikation je Fahrzeugtyp (Planungsannahme, die Anforderungsmatrix ist offen, siehe LH-6.21-CHK-010): wer ein Fahrzeug dieses Typs führen darf, hält eine zum Zeitpunkt gültige,
-- freigegebene Qualifikation dieser Art. Ohne Zeile für den Typ gilt nur die allgemeine Fahrerqualifikation (wie bei der Einsatzfreigabe).
CREATE TABLE vehicle_type_qualification (
  tenant_id          uuid NOT NULL REFERENCES tenant(id),
  vehicle_type       text NOT NULL CHECK (vehicle_type ~ '^[A-Z][A-Z0-9_]{1,39}$'),
  qualification_code text NOT NULL CHECK (qualification_code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
  created_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, vehicle_type, qualification_code)
);
ALTER TABLE vehicle_type_qualification ENABLE ROW LEVEL SECURITY;
ALTER TABLE vehicle_type_qualification FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON vehicle_type_qualification USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON vehicle_type_qualification FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();

-- Invariante CHK-015: eine Einsatzfreigabe trägt nur ein Prüflauf desselben Fahrzeugs und derselben Schicht, abgeschlossen und bestanden. Verweist `inspection_ref` auf einen vorhandenen
-- Prüflauf (der Fremdschlüssel ist wegen der Fixtures noch nicht aktiv, siehe db/scripts/m3_fk_aktivierung.sql), muss er passen; ein Verweis auf die Prüfung eines anderen Fahrzeugs
-- (etwa des Fahrzeugs vor dem Wechsel) ist nicht speicherbar, auch nicht bei direktem SQL.
CREATE FUNCTION resource_release_inspection_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE r record;
BEGIN
  SELECT vehicle_id, shift_id, status, outcome INTO r FROM inspection_run WHERE tenant_id = NEW.tenant_id AND id = NEW.inspection_ref;
  IF FOUND AND (r.vehicle_id <> NEW.vehicle_id OR r.shift_id <> NEW.shift_id OR r.status <> 'COMPLETED' OR r.outcome NOT IN ('PASSED', 'PASSED_RESTRICTED')) THEN
    RAISE EXCEPTION 'resource_release: inspection does not belong to this vehicle and shift or is not confirmed' USING ERRCODE = '23514', CONSTRAINT = 'resource_release_own_inspection';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER resource_release_inspection_guard BEFORE INSERT ON resource_release FOR EACH ROW EXECUTE FUNCTION resource_release_inspection_guard();
