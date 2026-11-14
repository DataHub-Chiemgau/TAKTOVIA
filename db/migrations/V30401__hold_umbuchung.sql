-- WP-M3-40: Umbuchung einer bestätigten Buchung auf einen anderen Umlauf. Der Hold der Buchung wandert mit (Umlauf, Planrevision, Abschnittsintervall), damit Storno,
-- Kapazitätsbelegung und Fahrzeugidentifikation dem Plan folgen; Kapazitätsvektor, Referenz und Ablauf bleiben unveränderlich. Der Wechsel des Umlaufs ist nur zulässig, wenn in
-- derselben Transaktion eine Umbuchung der zugehörigen Buchung (`booking_change.from_run_id/to_run_id`, V30400) protokolliert wurde; innerhalb desselben Umlaufs bleibt alles unveränderlich.
SET search_path = taktovia, public;

CREATE OR REPLACE FUNCTION capacity_hold_core_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF (NEW.federation_ref, NEW.capacity_vector, NEW.expires_at) IS DISTINCT FROM (OLD.federation_ref, OLD.capacity_vector, OLD.expires_at) THEN
    RAISE EXCEPTION 'capacity_hold capacity and expiry are immutable' USING ERRCODE = '23514';
  END IF;
  IF (NEW.run_id, NEW.run_revision, NEW.interval_from, NEW.interval_to) IS DISTINCT FROM (OLD.run_id, OLD.run_revision, OLD.interval_from, OLD.interval_to) THEN
    IF NEW.run_id IS NOT DISTINCT FROM OLD.run_id OR NOT EXISTS (
         SELECT 1 FROM booking b JOIN booking_change c ON c.tenant_id = b.tenant_id AND c.booking_id = b.id
          WHERE b.tenant_id = OLD.tenant_id AND b.hold_id = OLD.id AND c.from_run_id = OLD.run_id AND c.to_run_id = NEW.run_id AND c.created_at >= transaction_timestamp()) THEN
      RAISE EXCEPTION 'capacity_hold capacity and expiry are immutable' USING ERRCODE = '23514';
    END IF;
  END IF;
  RETURN NEW;
END $$;
