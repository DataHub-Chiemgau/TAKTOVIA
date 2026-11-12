-- WP-M4-40 (LH-I.2-FCO-002): strukturierte Fahrzeugkosten. Ein manuelles Kostenereignis (z. B. Versicherung, Kfz-Steuer, Leasingrate ohne Beleg) ist freigabepflichtig und durchläuft dieselben Stufen
-- wie ein Beleg (WP-M4-34): fachliche Prüfung, Budgetfreigabe, bei Überschreiten der Vier-Augen-Schwelle zweite Freigabe durch eine dritte Person, Posting in einer Transaktion. Der fachliche
-- Ursprungsschlüssel (`origin_key`) macht das Anlegen und das Buchen einmalig (Unique hier und am Kostenereignis `(tenant_id, legal_entity_id, source_system, source_ref, event_type, scenario)`).
SET search_path = taktovia, public;

CREATE TABLE manual_cost_event (
  tenant_id       uuid        NOT NULL REFERENCES tenant(id),
  id              uuid        NOT NULL,
  legal_entity_id uuid        NOT NULL,
  vehicle_id      uuid        NOT NULL,
  cost_object_id  uuid        NOT NULL,
  category        text        NOT NULL,
  currency        char(3)     NOT NULL REFERENCES currency(code),
  net_minor       bigint      NOT NULL,
  tax_minor       bigint      NOT NULL DEFAULT 0,
  gross_minor     bigint      NOT NULL,
  tax_treatment   text        NOT NULL CONSTRAINT manual_cost_event_tax CHECK (tax_treatment IN ('FULLY_DEDUCTIBLE', 'NON_DEDUCTIBLE')),
  accounting_date date        NOT NULL,
  service_from    date,
  service_to      date,
  description     text        CONSTRAINT manual_cost_event_description CHECK (length(description) BETWEEN 1 AND 500),
  origin_key      text        NOT NULL CONSTRAINT manual_cost_event_origin CHECK (length(origin_key) BETWEEN 1 AND 200),
  state           text        NOT NULL CONSTRAINT manual_cost_event_state CHECK (state IN ('IN_REVIEW', 'REVIEWED', 'WAITING_SECOND_APPROVAL', 'POSTED', 'REJECTED')),
  revision        bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_by      text        NOT NULL CHECK (length(created_by) BETWEEN 1 AND 200),
  approved_by     text        CHECK (length(approved_by) BETWEEN 1 AND 200),
  event_id        uuid,
  created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, legal_entity_id, origin_key),
  FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES legal_entity (tenant_id, id),
  FOREIGN KEY (tenant_id, vehicle_id) REFERENCES vehicle (tenant_id, id),
  FOREIGN KEY (tenant_id, cost_object_id) REFERENCES cost_object (tenant_id, id),
  FOREIGN KEY (tenant_id, category) REFERENCES cost_category (tenant_id, category),
  FOREIGN KEY (tenant_id, event_id) REFERENCES economic_event (tenant_id, id),
  CONSTRAINT manual_cost_event_amounts CHECK (net_minor + tax_minor = gross_minor AND gross_minor > 0 AND net_minor >= 0 AND tax_minor >= 0 AND gross_minor <= 9007199254740991),
  CONSTRAINT manual_cost_event_service CHECK (service_to IS NULL OR (service_from IS NOT NULL AND service_to >= service_from)),
  CONSTRAINT manual_cost_event_posted_pair CHECK ((state = 'POSTED') = (event_id IS NOT NULL AND approved_by IS NOT NULL))
);
CREATE INDEX manual_cost_event_by_vehicle ON manual_cost_event (tenant_id, vehicle_id, accounting_date);
ALTER TABLE manual_cost_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE manual_cost_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON manual_cost_event USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON manual_cost_event FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change();

-- Inhalt unveränderlich; Zustandsautomat IN_REVIEW -> REVIEWED | REJECTED, REVIEWED -> WAITING_SECOND_APPROVAL | POSTED | REJECTED, WAITING_SECOND_APPROVAL -> POSTED | REJECTED; POSTED und REJECTED sind endgültig.
CREATE FUNCTION manual_cost_event_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'manual_cost_event: ein manuelles Kostenereignis wird nie geloescht' USING ERRCODE = '23514'; END IF;
  IF TG_OP = 'INSERT' THEN
    IF NEW.state <> 'IN_REVIEW' THEN RAISE EXCEPTION 'manual_cost_event: anlegen im Zustand IN_REVIEW' USING ERRCODE = '23514'; END IF;
    RETURN NEW;
  END IF;
  IF (NEW.tenant_id, NEW.id, NEW.legal_entity_id, NEW.vehicle_id, NEW.cost_object_id, NEW.category, NEW.currency, NEW.net_minor, NEW.tax_minor, NEW.gross_minor, NEW.tax_treatment, NEW.accounting_date,
      NEW.service_from, NEW.service_to, NEW.description, NEW.origin_key, NEW.created_by, NEW.created_at)
     IS DISTINCT FROM (OLD.tenant_id, OLD.id, OLD.legal_entity_id, OLD.vehicle_id, OLD.cost_object_id, OLD.category, OLD.currency, OLD.net_minor, OLD.tax_minor, OLD.gross_minor, OLD.tax_treatment, OLD.accounting_date,
      OLD.service_from, OLD.service_to, OLD.description, OLD.origin_key, OLD.created_by, OLD.created_at) THEN
    RAISE EXCEPTION 'manual_cost_event: der Inhalt ist unveraenderlich (nur Zustand, Revision, Freigabe und Ereignis aendern sich)' USING ERRCODE = '23514';
  END IF;
  IF NEW.state IS DISTINCT FROM OLD.state AND NOT (
       (OLD.state = 'IN_REVIEW' AND NEW.state IN ('REVIEWED', 'REJECTED'))
    OR (OLD.state = 'REVIEWED' AND NEW.state IN ('WAITING_SECOND_APPROVAL', 'POSTED', 'REJECTED'))
    OR (OLD.state = 'WAITING_SECOND_APPROVAL' AND NEW.state IN ('POSTED', 'REJECTED'))) THEN
    RAISE EXCEPTION 'manual_cost_event: Zustandswechsel % -> % ist nicht erlaubt', OLD.state, NEW.state USING ERRCODE = '23514';
  END IF;
  IF OLD.state IN ('POSTED', 'REJECTED') AND (NEW.state IS DISTINCT FROM OLD.state OR NEW.event_id IS DISTINCT FROM OLD.event_id OR NEW.approved_by IS DISTINCT FROM OLD.approved_by) THEN
    RAISE EXCEPTION 'manual_cost_event: gebucht bzw. abgelehnt ist endgueltig' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER manual_cost_event_guard BEFORE INSERT OR UPDATE OR DELETE ON manual_cost_event FOR EACH ROW EXECUTE FUNCTION manual_cost_event_guard();

-- Freigabeschritte (wie `approval_step` des Belegs, append-only); Rollentrennung auch an der Anwendung vorbei: der Erfasser gibt nie selbst frei, die zweite Freigabe erteilt nie der Erstfreigeber.
CREATE TABLE manual_cost_event_step (
  tenant_id            uuid        NOT NULL REFERENCES tenant(id),
  id                   uuid        NOT NULL,
  manual_cost_event_id uuid        NOT NULL,
  revision             bigint      NOT NULL CHECK (revision >= 1),
  step                 text        NOT NULL CONSTRAINT manual_cost_event_step_step CHECK (step IN ('CAPTURE', 'REVIEW', 'BUDGET', 'SECOND_APPROVAL')),
  decision             text        NOT NULL CONSTRAINT manual_cost_event_step_decision CHECK (decision IN ('APPROVED', 'REJECTED')),
  decided_by           text        NOT NULL CHECK (length(decided_by) BETWEEN 1 AND 200),
  reason               text        NOT NULL CHECK (length(reason) BETWEEN 3 AND 1000),
  decided_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  CONSTRAINT manual_cost_event_step_capture_approved CHECK (step <> 'CAPTURE' OR decision = 'APPROVED'),
  FOREIGN KEY (tenant_id, manual_cost_event_id) REFERENCES manual_cost_event (tenant_id, id)
);
CREATE INDEX manual_cost_event_step_by_event ON manual_cost_event_step (tenant_id, manual_cost_event_id, decided_at);
ALTER TABLE manual_cost_event_step ENABLE ROW LEVEL SECURITY;
ALTER TABLE manual_cost_event_step FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON manual_cost_event_step USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER manual_cost_event_step_immutable BEFORE UPDATE OR DELETE ON manual_cost_event_step FOR EACH ROW EXECUTE FUNCTION taktovia.reject_mutation();

CREATE FUNCTION manual_cost_event_step_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE creator text; first_by text;
BEGIN
  IF NEW.decision = 'APPROVED' AND NEW.step IN ('BUDGET', 'SECOND_APPROVAL') THEN
    SELECT created_by INTO creator FROM manual_cost_event WHERE tenant_id = NEW.tenant_id AND id = NEW.manual_cost_event_id;
    IF creator IS NOT NULL AND creator = NEW.decided_by THEN
      RAISE EXCEPTION 'manual_cost_event_step: der Erfasser gibt sein Kostenereignis nicht selbst frei' USING ERRCODE = '23514';
    END IF;
  END IF;
  IF NEW.decision = 'APPROVED' AND NEW.step = 'SECOND_APPROVAL' THEN
    SELECT decided_by INTO first_by FROM manual_cost_event_step WHERE tenant_id = NEW.tenant_id AND manual_cost_event_id = NEW.manual_cost_event_id AND revision = NEW.revision
       AND step = 'BUDGET' AND decision = 'APPROVED' ORDER BY decided_at DESC, id DESC LIMIT 1;
    IF first_by IS NULL THEN RAISE EXCEPTION 'manual_cost_event_step: zweite Freigabe ohne Erstfreigabe dieser Revision' USING ERRCODE = '23514'; END IF;
    IF first_by = NEW.decided_by THEN RAISE EXCEPTION 'manual_cost_event_step: die zweite Freigabe erteilt eine andere Person als die Erstfreigabe' USING ERRCODE = '23514'; END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER manual_cost_event_step_guard BEFORE INSERT ON manual_cost_event_step FOR EACH ROW EXECUTE FUNCTION manual_cost_event_step_guard();
