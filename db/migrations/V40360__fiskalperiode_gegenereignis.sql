-- WP-M4-36: Fiskalperiode, Periodenabschluss und Gegenereignis (Modul documents-finance).
-- fiscal_period (V1, Härtung V2: geschlossene Periode unveränderlich) bekommt das Überlappungsverbot je Rechtsträger und den Abschließenden; Gegenereignisse (economic_event.reverses_id) tragen ein Regelwerk
-- in der Datenbank (nur Gegenvorzeichen, nie mehr als der Ursprung, nie ein Gegenereignis eines Gegenereignisses); Journalzeilen kennen ihre Rolle, damit die Gegenbuchung dieselben Konten mit getauschter Seite trifft.
SET search_path = taktovia, public;

-- Überlappungsverbot: je Mandant und Rechtsträger überschneiden sich zwei Perioden nie (halboffenes Intervall, benachbarte Perioden sind erlaubt).
ALTER TABLE fiscal_period ADD CONSTRAINT fiscal_period_no_overlap EXCLUDE USING gist (tenant_id WITH =, legal_entity_id WITH =, daterange(starts_on, ends_on, '[)') WITH &&);
ALTER TABLE fiscal_period ADD COLUMN closed_by text CONSTRAINT fiscal_period_closed_by CHECK (length(closed_by) BETWEEN 1 AND 200);
ALTER TABLE fiscal_period ADD COLUMN created_by text CONSTRAINT fiscal_period_created_by CHECK (length(created_by) BETWEEN 1 AND 200);
CREATE INDEX fiscal_period_by_entity ON fiscal_period (tenant_id, legal_entity_id, starts_on);

-- Geschlossen heißt endgültig: auch der Abschließende und der Anleger sind danach unveränderlich (Härtung V2 schützte nur closed_at und die Grenzen).
CREATE OR REPLACE FUNCTION guard_fiscal_period() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  IF OLD.closed_at IS NOT NULL THEN RAISE EXCEPTION 'closed period immutable'; END IF;
  RETURN OLD;
 END IF;
 IF (NEW.tenant_id,NEW.id,NEW.legal_entity_id) IS DISTINCT FROM (OLD.tenant_id,OLD.id,OLD.legal_entity_id) THEN
  RAISE EXCEPTION 'period identity immutable';
 END IF;
 IF OLD.closed_at IS NOT NULL AND (NEW.closed_at IS DISTINCT FROM OLD.closed_at OR NEW.starts_on IS DISTINCT FROM OLD.starts_on OR NEW.ends_on IS DISTINCT FROM OLD.ends_on
    OR NEW.closed_by IS DISTINCT FROM OLD.closed_by OR NEW.created_by IS DISTINCT FROM OLD.created_by) THEN
  RAISE EXCEPTION 'closed period immutable';
 END IF;
 RETURN NEW;
END $$;

-- Rolle der Journalzeile (Aufwand, abziehbare Vorsteuer, Verbindlichkeit): die Gegenbuchung trifft dieselben Konten mit getauschter Seite (Kern-DDL: amount_minor > 0, keine negative Zeile).
ALTER TABLE journal_line ADD COLUMN account_role text CONSTRAINT journal_line_account_role CHECK (account_role IN ('EXPENSE', 'INPUT_TAX', 'PAYABLE'));

-- Eine Umlage auf ein inzwischen deaktiviertes Kostenobjekt ist nur als Korrektur (Betrag <= 0) zulässig: das Gegenereignis muss die ursprüngliche Verteilung aufheben können.
CREATE OR REPLACE FUNCTION allocation_line_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE s allocation_set%ROWTYPE; o_entity uuid; o_active boolean;
BEGIN
  IF TG_OP <> 'INSERT' THEN RAISE EXCEPTION 'allocation_line: Zeilen werden nur angehaengt' USING ERRCODE = '23514'; END IF;
  SELECT * INTO s FROM allocation_set WHERE tenant_id = NEW.tenant_id AND id = NEW.allocation_id FOR SHARE;
  IF NOT FOUND THEN RETURN NEW; END IF;   -- der Fremdschluessel meldet den fehlenden Satz
  IF s.state <> 'DRAFT' THEN RAISE EXCEPTION 'allocation_line: gebuchte Umlage ist final unveraenderlich' USING ERRCODE = '23514'; END IF;
  SELECT legal_entity_id, active INTO o_entity, o_active FROM cost_object WHERE tenant_id = NEW.tenant_id AND id = NEW.cost_object_id FOR SHARE;
  IF FOUND THEN
    IF o_entity <> s.legal_entity_id THEN RAISE EXCEPTION 'allocation_line: Kostenobjekt gehoert zu einem anderen Rechtstraeger als das Ereignis' USING ERRCODE = '23514'; END IF;
    IF o_active = false AND NEW.amount_minor > 0 THEN RAISE EXCEPTION 'allocation_line: Kostenobjekt ist inaktiv' USING ERRCODE = '23514'; END IF;
  END IF;
  RETURN NEW;
END $$;

-- Gegenereignis (reverses_id): serialisiert je Ursprung (Advisory-Lock), nur Gegenvorzeichen, gleicher Rechtsträger, gleiche Währung und gleiches Szenario, nie ein Gegenereignis eines Gegenereignisses,
-- die Summe aller Gegenereignisse überschreitet den Ursprung nie (weder brutto noch Kostenbasis). Das Ereignis selbst bleibt unveränderlich (Trigger economic_immutable).
CREATE FUNCTION economic_event_reversal_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE o economic_event%ROWTYPE; rev_gross numeric; rev_basis numeric;
BEGIN
  IF NEW.reverses_id IS NULL THEN RETURN NEW; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended('economic_event_reversal:' || NEW.tenant_id::text || ':' || NEW.reverses_id::text, 0));
  SELECT * INTO o FROM economic_event WHERE tenant_id = NEW.tenant_id AND id = NEW.reverses_id;
  IF NOT FOUND THEN RETURN NEW; END IF;   -- der Fremdschluessel meldet den fehlenden Ursprung
  IF o.reverses_id IS NOT NULL THEN RAISE EXCEPTION 'economic_event: ein Gegenereignis wird nicht selbst aufgehoben' USING ERRCODE = '23514'; END IF;
  IF (o.legal_entity_id, o.currency, o.scenario) IS DISTINCT FROM (NEW.legal_entity_id, NEW.currency, NEW.scenario) THEN
    RAISE EXCEPTION 'economic_event: Gegenereignis mit anderem Rechtstraeger, anderer Waehrung oder anderem Szenario als der Ursprung' USING ERRCODE = '23514';
  END IF;
  IF NEW.gross_minor = 0 OR sign(NEW.gross_minor) = sign(o.gross_minor)
     OR (NEW.net_minor <> 0 AND sign(NEW.net_minor) <> sign(NEW.gross_minor)) OR (NEW.tax_minor <> 0 AND sign(NEW.tax_minor) <> sign(NEW.gross_minor)) THEN
    RAISE EXCEPTION 'economic_event: ein Gegenereignis hat das Gegenvorzeichen des Ursprungs' USING ERRCODE = '23514';
  END IF;
  SELECT coalesce(sum(gross_minor), 0), coalesce(sum(cost_basis_minor), 0) INTO rev_gross, rev_basis FROM economic_event WHERE tenant_id = NEW.tenant_id AND reverses_id = o.id;
  IF abs(rev_gross + NEW.gross_minor) > abs(o.gross_minor) OR abs(rev_basis + (NEW.gross_minor - NEW.recoverable_tax_minor)) > abs(o.cost_basis_minor) THEN
    RAISE EXCEPTION 'economic_event: die Gegenereignisse ueberschreiten den Ursprung' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER economic_event_reversal_guard BEFORE INSERT ON economic_event FOR EACH ROW EXECUTE FUNCTION economic_event_reversal_guard();
