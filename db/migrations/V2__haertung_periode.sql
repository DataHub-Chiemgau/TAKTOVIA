SET search_path = taktovia, public;
-- Befund B-01 (WP-M0-21): Eine geschlossene Buchungsperiode ließ sich per UPDATE wieder öffnen oder verschieben,
-- obwohl guard_journal() das Buchen nur gegen den aktuellen Periodenstand prüft. Gegenmaßnahme: einmal gesetztes
-- closed_at und die Periodengrenzen sind unveränderlich; Korrekturen erfolgen als Gegenbuchung in einer offenen Periode.
CREATE FUNCTION guard_fiscal_period() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  IF OLD.closed_at IS NOT NULL THEN RAISE EXCEPTION 'closed period immutable'; END IF;
  RETURN OLD;
 END IF;
 IF (NEW.tenant_id,NEW.id,NEW.legal_entity_id) IS DISTINCT FROM (OLD.tenant_id,OLD.id,OLD.legal_entity_id) THEN
  RAISE EXCEPTION 'period identity immutable';
 END IF;
 IF OLD.closed_at IS NOT NULL AND (NEW.closed_at IS DISTINCT FROM OLD.closed_at
    OR NEW.starts_on IS DISTINCT FROM OLD.starts_on OR NEW.ends_on IS DISTINCT FROM OLD.ends_on) THEN
  RAISE EXCEPTION 'closed period immutable';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER fiscal_period_guard BEFORE UPDATE OR DELETE ON fiscal_period FOR EACH ROW EXECUTE FUNCTION guard_fiscal_period();
