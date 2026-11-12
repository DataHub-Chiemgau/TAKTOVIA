-- WP-M3-07 / LH-6.8-FLT-003: eindeutige Schichtgrenzen. Überlappende Schichten desselben Fahrers oder desselben Fahrzeugs sind ein Konflikt, außer eine
-- dokumentierte Ausnahme (Doppelschicht, Planungsannahme PA-03: Genehmiger, Grund, Zeitpunkt) hebt das Verbot für genau diese Schicht auf.
-- Warum kein reiner Exclusion Constraint: die Ausnahme muss das Verbot gezielt aufheben können (Vertrag LH-6.8-FLT-003). Der Constraint kennt keinen Ausnahmepfad
-- für einzelne Zeilen, ohne dass die Ausnahmezeile auch für spätere Schichten unsichtbar würde. Stattdessen eine gesperrte Intervallprüfung im Trigger:
-- transaktionsbezogene Advisory-Locks je Fahrer und je Fahrzeug (stabile Reihenfolge, kein Deadlock) serialisieren konkurrierende Anlagen, danach liest die Prüfung
-- den Stand nach dem Commit des Gegners (READ COMMITTED: jedes Statement bekommt einen frischen Snapshot). Das gilt für jeden Schreibpfad (auch direktes SQL).
-- Zustände von staff_shift.state (vertraege/zustaende/staff_shift.md): PLANNED, STARTED, ENDED, CANCELLED; PUBLISHED gehört dem Dienstplan (M4, WP-M4-65).
-- Nur CANCELLED gilt nicht mehr und blockiert nichts. roster_revision bleibt vor M4 der Platzhalter 0 (PA-03, OP-07), keine Schemaänderung.
SET search_path = taktovia, public;

ALTER TABLE staff_shift
  ADD COLUMN revision                bigint NOT NULL DEFAULT 1 CHECK (revision >= 1),
  ADD COLUMN overlap_exception_by     text,
  ADD COLUMN overlap_exception_reason text,
  ADD COLUMN overlap_exception_at     timestamptz,
  -- Ausnahme nur vollständig (Genehmiger, Grund, Zeitpunkt) oder gar nicht
  ADD CONSTRAINT staff_shift_exception_complete CHECK (
    (overlap_exception_by IS NULL) = (overlap_exception_reason IS NULL) AND (overlap_exception_by IS NULL) = (overlap_exception_at IS NULL)),
  ADD CONSTRAINT staff_shift_exception_text CHECK (
    overlap_exception_by IS NULL OR (length(btrim(overlap_exception_by)) BETWEEN 1 AND 200 AND length(btrim(overlap_exception_reason)) BETWEEN 3 AND 500));

CREATE INDEX staff_shift_by_vehicle ON staff_shift (tenant_id, vehicle_id, starts_at);

-- Sperre je Fahrer und je Fahrzeug (transaktionsbezogen, Reihenfolge nach Schlüsselgröße: zwei Transaktionen mit {a,b} und {b,a} warten nie kreuzweise).
CREATE FUNCTION staff_shift_lock(p_tenant uuid, p_driver uuid, p_vehicle uuid) RETURNS void LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE k1 bigint := hashtextextended('staff_shift|driver|' || p_tenant::text || '|' || p_driver::text, 0); k2 bigint := hashtextextended('staff_shift|vehicle|' || p_tenant::text || '|' || p_vehicle::text, 0);
BEGIN
  PERFORM pg_advisory_xact_lock(least(k1, k2));
  IF k1 <> k2 THEN PERFORM pg_advisory_xact_lock(greatest(k1, k2)); END IF;
END $$;

-- Erste kollidierende, noch geltende Schicht desselben Fahrers (Ursache DRIVER) bzw. desselben Fahrzeugs (VEHICLE) im halboffenen Intervall [start, ende). Wird vom Trigger
-- und von der Anwendung (Konfliktursache, Ausnahmepfad) benutzt, damit beide dieselbe Regel anwenden. Sinnvoll nur nach staff_shift_lock in derselben Transaktion.
CREATE FUNCTION staff_shift_clash(p_tenant uuid, p_self uuid, p_driver uuid, p_vehicle uuid, p_start timestamptz, p_end timestamptz)
RETURNS TABLE (cause text, shift_id uuid) LANGUAGE sql SET search_path = taktovia, pg_catalog AS $$
  (SELECT 'DRIVER'::text, s.id FROM staff_shift s
    WHERE s.tenant_id = p_tenant AND s.id <> p_self AND s.state <> 'CANCELLED' AND s.driver_ref = p_driver
      AND tstzrange(s.starts_at, s.ends_at, '[)') && tstzrange(p_start, p_end, '[)') ORDER BY s.starts_at, s.id LIMIT 1)
  UNION ALL
  (SELECT 'VEHICLE'::text, s.id FROM staff_shift s
    WHERE s.tenant_id = p_tenant AND s.id <> p_self AND s.state <> 'CANCELLED' AND s.vehicle_id = p_vehicle
      AND tstzrange(s.starts_at, s.ends_at, '[)') && tstzrange(p_start, p_end, '[)') ORDER BY s.starts_at, s.id LIMIT 1)
$$;

CREATE FUNCTION staff_shift_overlap_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE
  c record;
BEGIN
  -- der Intervallbau braucht ends_at > starts_at; der CHECK-Constraint des Kernschemas liefe erst nach dem Trigger
  IF NEW.ends_at <= NEW.starts_at THEN
    RAISE EXCEPTION 'staff shift must end after it starts' USING ERRCODE = '23514', CONSTRAINT = 'staff_shift_check';
  END IF;
  IF NEW.state = 'CANCELLED' THEN RETURN NEW; END IF;
  IF TG_OP = 'UPDATE' AND NEW.driver_ref = OLD.driver_ref AND NEW.vehicle_id = OLD.vehicle_id AND NEW.starts_at = OLD.starts_at AND NEW.ends_at = OLD.ends_at
     AND NEW.state = OLD.state AND NEW.overlap_exception_at IS NOT DISTINCT FROM OLD.overlap_exception_at THEN
    RETURN NEW;      -- nichts, was die Belegung ändert (z. B. Dienstantritt)
  END IF;
  PERFORM staff_shift_lock(NEW.tenant_id, NEW.driver_ref, NEW.vehicle_id);
  -- Die dokumentierte Ausnahme erlaubt dieser Zeile die Überlappung; spätere reguläre Schichten werden dennoch gegen sie geprüft.
  IF NEW.overlap_exception_at IS NOT NULL THEN RETURN NEW; END IF;
  FOR c IN SELECT * FROM staff_shift_clash(NEW.tenant_id, NEW.id, NEW.driver_ref, NEW.vehicle_id, NEW.starts_at, NEW.ends_at) LOOP
    RAISE EXCEPTION 'staff shift overlaps another shift of the same %', lower(c.cause)
      USING ERRCODE = '23P01', CONSTRAINT = 'staff_shift_' || lower(c.cause) || '_no_overlap', DETAIL = c.cause || ':' || c.shift_id::text;
  END LOOP;
  RETURN NEW;
END $$;
CREATE TRIGGER staff_shift_overlap_guard BEFORE INSERT OR UPDATE ON staff_shift FOR EACH ROW EXECUTE FUNCTION staff_shift_overlap_guard();

-- Jede inhaltliche Änderung erhöht die Revision genau um 1 (ETag der Schichtzuweisung); gilt für jeden Schreibpfad.
CREATE FUNCTION staff_shift_revision() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.revision = OLD.revision AND (to_jsonb(NEW) - 'revision') IS DISTINCT FROM (to_jsonb(OLD) - 'revision') THEN NEW.revision := OLD.revision + 1; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER staff_shift_revision BEFORE UPDATE ON staff_shift FOR EACH ROW EXECUTE FUNCTION staff_shift_revision();
