-- WP-M2-19 / LH-6.5-OPT-006: Schutz zugesagter Zeitfenster. Der planbasierte Hold speichert das Zusagefenster der Buchung samt den harten Grenzen ihrer
-- Konfigurationsrevision (WP-M2-18) in `capacity_hold.promise_window`. Hold und Bestätigung prüfen alle so gespeicherten Fenster gegen den dann aktuellen Plan.
-- Das Fenster ist nach dem Anlegen unveränderlich: eine spätere Änderung von `pickup_window_min`, `arrival_grace_min` oder der Grenzen berührt gespeicherte Zusagen nie
-- (neue Fenster entstehen nur mit neuen Holds). Holds ohne Fenster (Plan ohne Einfügung, M2-14) bleiben unverändert gültig.
-- Inhalt (siehe bookingdispatch.api.PromiseWindow): booking_id, pickup_earliest/-latest, arrival_earliest/-latest (RFC 3339 UTC), wait_reference, max_wait_min,
-- max_detour_pct, arrival_grace_min, config_revision_id, direct_sec, pickup_dwell_sec.
SET search_path = taktovia, public;

ALTER TABLE capacity_hold ADD COLUMN promise_window jsonb;
ALTER TABLE capacity_hold ADD CONSTRAINT capacity_hold_promise_window_shape CHECK (
  promise_window IS NULL OR (
    jsonb_typeof(promise_window) = 'object'
    AND promise_window ?& ARRAY['booking_id','pickup_earliest','pickup_latest','arrival_earliest','arrival_latest','wait_reference',
                                'max_wait_min','max_detour_pct','arrival_grace_min','direct_sec','pickup_dwell_sec']
    AND (promise_window->>'max_wait_min')::int BETWEEN 0 AND 240
    AND (promise_window->>'max_detour_pct')::int BETWEEN 0 AND 200
    AND (promise_window->>'arrival_grace_min')::int BETWEEN 0 AND 240
    AND (promise_window->>'direct_sec')::int > 0
  )
);

CREATE FUNCTION capacity_hold_window_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.promise_window IS DISTINCT FROM OLD.promise_window THEN
    RAISE EXCEPTION 'capacity_hold promise_window is immutable' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER capacity_hold_window_immutable BEFORE UPDATE ON capacity_hold FOR EACH ROW EXECUTE FUNCTION capacity_hold_window_guard();
