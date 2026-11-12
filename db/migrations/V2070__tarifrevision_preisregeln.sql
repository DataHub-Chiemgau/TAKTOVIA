-- WP-M2-07 / LH-6.10-PAY-003: Tarifrevision und Preisregeln mit Gültigkeit (Kap. 04 §4.4 „Tarif-/Steuerstand“). Planungsannahme PA-05 (Preisstichtag = Fahrtzeitpunkt,
-- Tarifklasse in Phase 1 genau `STANDARD`, die Spalte ist vorhanden); der reale Pilottarif (D-05) fehlt, Preise und Sätze sind Testannahmen.
--  * `tariff_revision`: Kopf einer Tarifversion je Dienst und Tarifklasse mit Gültigkeit `[valid_from, valid_to)` (links geschlossen, rechts offen; `valid_to` NULL = offen),
--    Währung aus dem Katalog und dem Lebenslauf ENTWURF -> GEPRUEFT -> VEROEFFENTLICHT. Ersteller, Prüfer und Veröffentlichender sind festgehalten; Prüfer und
--    Veröffentlichender dürfen nicht der Ersteller sein (Vier-Augen, auch an der Anwendung vorbei, WP-M1-22).
--  * Invariante „nicht überlappende aktive Tarifversion“ (Kap. 04 §4.5): Exclusion Constraint über die VEROEFFENTLICHTEN Revisionen je Dienst und Tarifklasse.
--    Zwei parallele Veröffentlichungen mit überlappendem Zeitraum: genau eine gewinnt, die andere scheitert mit 23P01 (HTTP 409).
--  * Eine veröffentlichte Revision ist vollständig unveränderlich (auch deren Regeln); Korrektur nur als neue Revision (`revision_no` + 1). Kein DELETE.
--  * `tariff_rule`: Preisregeln einer Revision (Gültigkeit, Dienst und Tarifklasse erben sie von der Revision). Genau EIN Regeltyp je Revision: FESTPREIS (eine Regel),
--    ZONENPREIS (je gerichtetem Zonenpaar eine Regel) oder GRUNDPREIS_STRECKE (eine Regel). Sätze als numeric(20,8), Beträge als bigint in der kleinsten Einheit,
--    Steuerregelrevision je Regel (`tax_rule_revision`). Die Berechnung selbst liefert WP-M2-08 (Tarifengine).
--  * `fare_quote.tariff_revision_id` erhält den Fremdschlüssel auf `tariff_revision` (NOT VALID: Altzeilen werden nicht geprüft, jede neue Zeile schon).
SET search_path = taktovia, public;

CREATE TABLE tariff_revision (
  tenant_id    uuid        NOT NULL REFERENCES tenant(id),
  id           uuid        NOT NULL,
  service_id   uuid        NOT NULL,
  tariff_class text        NOT NULL CONSTRAINT tariff_revision_class_format CHECK (tariff_class ~ '^[A-Z][A-Z0-9_]{0,39}$'),
  revision_no  integer     NOT NULL CHECK (revision_no >= 1),
  state        text        NOT NULL DEFAULT 'ENTWURF' CONSTRAINT tariff_revision_state_valid CHECK (state IN ('ENTWURF', 'GEPRUEFT', 'VEROEFFENTLICHT')),
  valid_from   timestamptz NOT NULL,
  valid_to     timestamptz,
  currency     char(3)     NOT NULL REFERENCES currency(code),
  reason       text        NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
  row_version  bigint      NOT NULL DEFAULT 1 CHECK (row_version >= 1),
  created_by   text        NOT NULL CONSTRAINT tariff_revision_created_by_format CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
  reviewed_by  text        CONSTRAINT tariff_revision_reviewed_by_format CHECK (reviewed_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  reviewed_at  timestamptz,
  published_by text        CONSTRAINT tariff_revision_published_by_format CHECK (published_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  published_at timestamptz,
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, service_id, tariff_class, revision_no),
  FOREIGN KEY (tenant_id, service_id) REFERENCES service (tenant_id, id),
  CONSTRAINT tariff_revision_period CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT tariff_revision_four_eyes_review CHECK (reviewed_by IS NULL OR reviewed_by <> created_by),
  CONSTRAINT tariff_revision_four_eyes_publish CHECK (published_by IS NULL OR published_by <> created_by),
  CONSTRAINT tariff_revision_state_shape CHECK (
    (state = 'ENTWURF' AND reviewed_by IS NULL AND reviewed_at IS NULL AND published_by IS NULL AND published_at IS NULL)
    OR (state = 'GEPRUEFT' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND published_by IS NULL AND published_at IS NULL)
    OR (state = 'VEROEFFENTLICHT' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND published_by IS NOT NULL AND published_at IS NOT NULL)),
  CONSTRAINT tariff_revision_published_no_overlap
    EXCLUDE USING gist (tenant_id WITH =, service_id WITH =, tariff_class WITH =, tstzrange(valid_from, valid_to) WITH &&) WHERE (state = 'VEROEFFENTLICHT')
);
ALTER TABLE tariff_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE tariff_revision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tariff_revision USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON tariff_revision FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE INDEX tariff_revision_by_service ON tariff_revision (tenant_id, service_id, tariff_class, valid_from);

CREATE TABLE tariff_rule (
  tenant_id         uuid          NOT NULL REFERENCES tenant(id),
  id                uuid          NOT NULL,
  revision_id       uuid          NOT NULL,
  rule_no           integer       NOT NULL CHECK (rule_no BETWEEN 1 AND 10000),
  rule_type         text          NOT NULL CONSTRAINT tariff_rule_type_valid CHECK (rule_type IN ('FESTPREIS', 'ZONENPREIS', 'GRUNDPREIS_STRECKE')),
  person_mode       text          NOT NULL DEFAULT 'PER_PERSON' CHECK (person_mode IN ('PER_PERSON', 'PER_BOOKING')),
  amount_minor      bigint        NOT NULL CHECK (amount_minor BETWEEN 0 AND 9007199254740991),
  zone_from         uuid,
  zone_to           uuid,
  rate_per_km       numeric(20,8) CHECK (rate_per_km >= 0),
  booking_fee_minor bigint        NOT NULL DEFAULT 0 CHECK (booking_fee_minor BETWEEN 0 AND 9007199254740991),
  tax_rate          numeric(20,8) NOT NULL DEFAULT 0 CHECK (tax_rate >= 0 AND tax_rate < 1),
  tax_mode          text          NOT NULL DEFAULT 'GROSS' CHECK (tax_mode IN ('GROSS', 'NET')),
  tax_rule_revision text          NOT NULL CHECK (length(btrim(tax_rule_revision)) BETWEEN 1 AND 40),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, revision_id, rule_no),
  UNIQUE (tenant_id, revision_id, zone_from, zone_to),
  FOREIGN KEY (tenant_id, revision_id) REFERENCES tariff_revision (tenant_id, id),
  CONSTRAINT tariff_rule_shape CHECK (
    (rule_type = 'FESTPREIS' AND zone_from IS NULL AND zone_to IS NULL AND rate_per_km IS NULL)
    OR (rule_type = 'ZONENPREIS' AND zone_from IS NOT NULL AND zone_to IS NOT NULL AND rate_per_km IS NULL)
    OR (rule_type = 'GRUNDPREIS_STRECKE' AND zone_from IS NULL AND zone_to IS NULL AND rate_per_km IS NOT NULL))
);
ALTER TABLE tariff_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE tariff_rule FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tariff_rule USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON tariff_rule FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE INDEX tariff_rule_by_revision ON tariff_rule (tenant_id, revision_id, rule_no);

-- Lebenslauf und Unveränderlichkeit der Revision. Veröffentlichte Zeilen sind vollständig fest; davor ist nur der Vorwärtsweg ENTWURF -> GEPRUEFT -> VEROEFFENTLICHT möglich,
-- die Identität (Dienst, Klasse, Nummer, Ersteller) ändert sich nie, jede Änderung zählt `row_version`. Prüfung und Veröffentlichung verlangen mindestens eine Regel und
-- genau einen Regeltyp (eine gemischte Revision wäre nicht eindeutig bepreisbar).
CREATE FUNCTION tariff_revision_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE n integer; kinds integer;
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'tariff revision is append-only' USING ERRCODE = '23514';
  END IF;
  IF OLD.state = 'VEROEFFENTLICHT' THEN
    RAISE EXCEPTION 'tariff revision is published and immutable' USING ERRCODE = '23514';
  END IF;
  IF NEW.id IS DISTINCT FROM OLD.id OR NEW.service_id IS DISTINCT FROM OLD.service_id OR NEW.tariff_class IS DISTINCT FROM OLD.tariff_class
     OR NEW.revision_no IS DISTINCT FROM OLD.revision_no OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'tariff revision identity is immutable' USING ERRCODE = '23514';
  END IF;
  IF NEW.state IS DISTINCT FROM OLD.state AND NOT ((OLD.state = 'ENTWURF' AND NEW.state = 'GEPRUEFT') OR (OLD.state = 'GEPRUEFT' AND NEW.state = 'VEROEFFENTLICHT')) THEN
    RAISE EXCEPTION 'tariff revision state transition not allowed' USING ERRCODE = '23514';
  END IF;
  IF OLD.state = 'GEPRUEFT' AND NEW.state = 'GEPRUEFT' THEN
    RAISE EXCEPTION 'tariff revision is reviewed and cannot be edited; create a new revision' USING ERRCODE = '23514';
  END IF;
  IF NEW.state IS DISTINCT FROM OLD.state THEN
    SELECT count(*), count(DISTINCT rule_type) INTO n, kinds FROM tariff_rule WHERE tenant_id = NEW.tenant_id AND revision_id = NEW.id;
    IF n = 0 OR kinds <> 1 THEN
      RAISE EXCEPTION 'tariff revision needs at least one rule of exactly one rule type' USING ERRCODE = '23514';
    END IF;
  END IF;
  NEW.row_version := OLD.row_version + 1;
  RETURN NEW;
END $$;
CREATE TRIGGER tariff_revision_guard BEFORE UPDATE OR DELETE ON tariff_revision FOR EACH ROW EXECUTE FUNCTION tariff_revision_guard();

-- Regeln ändert nur, wer die Revision im Zustand ENTWURF hält (auch Anlegen und Löschen); sonst scheitert die Änderung in der Datenbank.
CREATE FUNCTION tariff_rule_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE st text; rid uuid; tid uuid;
BEGIN
  IF TG_OP = 'DELETE' THEN rid := OLD.revision_id; tid := OLD.tenant_id; ELSE rid := NEW.revision_id; tid := NEW.tenant_id; END IF;
  SELECT state INTO st FROM tariff_revision WHERE tenant_id = tid AND id = rid;
  IF st IS DISTINCT FROM 'ENTWURF' THEN
    RAISE EXCEPTION 'tariff rules are immutable unless the revision is a draft' USING ERRCODE = '23514';
  END IF;
  IF TG_OP = 'UPDATE' AND (NEW.revision_id IS DISTINCT FROM OLD.revision_id OR NEW.id IS DISTINCT FROM OLD.id) THEN
    RAISE EXCEPTION 'tariff rule identity is immutable' USING ERRCODE = '23514';
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER tariff_rule_guard BEFORE INSERT OR UPDATE OR DELETE ON tariff_rule FOR EACH ROW EXECUTE FUNCTION tariff_rule_guard();

-- Preisbindung: jede Angebotszeile verweist auf eine vorhandene Tarifrevision desselben Mandanten (Altzeilen vor dieser Migration werden nicht geprüft).
ALTER TABLE fare_quote ADD CONSTRAINT fare_quote_tariff_revision_fk FOREIGN KEY (tenant_id, tariff_revision_id) REFERENCES tariff_revision (tenant_id, id) NOT VALID;
