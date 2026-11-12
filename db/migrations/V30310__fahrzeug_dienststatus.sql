-- WP-M3-31 / LH-6.6-DRV-007: Fahrzeug- und Dienststatus je Schicht (Verfügbarkeit, Anfahrt, Wartezeit, Pause, Störung, Ende; Anfangszustand NOT_READY).
-- Automat: docs/umsetzungsplan/vertraege/zustaende/fahrzeug_dienststatus.md. Der Server entscheidet; der Zustandswächter hier stellt die Kantentabelle auch bei direktem SQL sicher.
-- Die Statushistorie (`vehicle_status_history`, V3020, unveränderlich) wird additiv um Ereignis-ID (Dedupe), Gerätezeit und Offline-Kennzeichen erweitert; `created_at` ist die Serverzeit.
SET search_path = taktovia, public;

ALTER TABLE vehicle_status_history
  ADD COLUMN event_id         uuid,
  ADD COLUMN occurred_at      timestamptz,
  ADD COLUMN offline_recorded boolean NOT NULL DEFAULT false;
-- gleiche Statusmeldung (Ereignis-ID des Geräts) wirkt höchstens einmal (Offline-Sync, Wiederholung); Zeilen des Systems ohne Meldung tragen keine Ereignis-ID
CREATE UNIQUE INDEX vehicle_status_history_event ON vehicle_status_history (tenant_id, event_id) WHERE event_id IS NOT NULL;

-- Aktueller Status je Schicht (eine Zeile je Schicht, entsteht mit der ersten Meldung als NOT_READY). Änderungen sperren die Fahrzeugzeile (Sperrreihenfolge wie Sperre/Freigabe, WP-M3-08).
CREATE TABLE vehicle_service_status (
  tenant_id  uuid        NOT NULL REFERENCES tenant(id),
  shift_id   uuid        NOT NULL,
  vehicle_id uuid        NOT NULL,
  status     text        NOT NULL DEFAULT 'NOT_READY' CHECK (status IN ('NOT_READY', 'AVAILABLE', 'EN_ROUTE', 'WAITING', 'BREAK', 'DISRUPTED', 'ENDED')),
  revision   bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  changed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, shift_id),
  FOREIGN KEY (tenant_id, shift_id)   REFERENCES staff_shift(tenant_id, id),
  FOREIGN KEY (tenant_id, vehicle_id) REFERENCES vehicle(tenant_id, id)
);
CREATE INDEX vehicle_service_status_by_vehicle ON vehicle_service_status (tenant_id, vehicle_id, changed_at DESC);

ALTER TABLE vehicle_service_status ENABLE ROW LEVEL SECURITY;
ALTER TABLE vehicle_service_status FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON vehicle_service_status USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON vehicle_service_status FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change();

-- Kantentabelle des Automaten; Schicht und Fahrzeug sind unveränderlich, ENDED ist endgültig, die Revision zählt jeden Wechsel.
CREATE FUNCTION vehicle_service_status_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.shift_id IS DISTINCT FROM OLD.shift_id OR NEW.vehicle_id IS DISTINCT FROM OLD.vehicle_id THEN
    RAISE EXCEPTION 'vehicle service status identity is immutable' USING ERRCODE = '23514';
  END IF;
  IF NEW.status IS NOT DISTINCT FROM OLD.status THEN RETURN NEW; END IF;
  IF NOT ((OLD.status = 'NOT_READY' AND NEW.status IN ('AVAILABLE', 'DISRUPTED', 'ENDED'))
       OR (OLD.status = 'AVAILABLE' AND NEW.status IN ('EN_ROUTE', 'BREAK', 'DISRUPTED', 'ENDED'))
       OR (OLD.status = 'EN_ROUTE' AND NEW.status IN ('WAITING', 'AVAILABLE', 'DISRUPTED'))
       OR (OLD.status = 'WAITING' AND NEW.status IN ('EN_ROUTE', 'AVAILABLE', 'BREAK', 'DISRUPTED'))
       OR (OLD.status = 'BREAK' AND NEW.status IN ('AVAILABLE', 'DISRUPTED', 'ENDED'))
       OR (OLD.status = 'DISRUPTED' AND NEW.status IN ('NOT_READY', 'ENDED'))) THEN
    RAISE EXCEPTION 'vehicle service status transition % -> % not allowed', OLD.status, NEW.status USING ERRCODE = '23514', CONSTRAINT = 'vehicle_service_status_transition';
  END IF;
  NEW.revision := OLD.revision + 1;
  RETURN NEW;
END $$;
CREATE TRIGGER vehicle_service_status_guard BEFORE UPDATE ON vehicle_service_status FOR EACH ROW EXECUTE FUNCTION vehicle_service_status_guard();
CREATE TRIGGER vehicle_service_status_no_delete BEFORE DELETE ON vehicle_service_status FOR EACH ROW EXECUTE FUNCTION reject_mutation();
