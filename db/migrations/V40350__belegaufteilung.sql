-- WP-M4-35 (LH-I.1-DOC-011): Belegaufteilung auf mehrere Kostenobjekte, centgenau (Modul documents-finance).
-- allocation_set / allocation_line stammen aus dem Kernschema (V1). Hier: Unveränderlichkeit und Centgenauigkeit per Trigger, genau eine aktive Umlagerevision je Ereignis (allocation_activation)
-- und der Beleg-Split vor dem Posting (expense_split, expense_split_line): die Planung des Belegs auf die ihm zugeordneten Kostenobjekte (Offener Punkt 18, Bau-Vorschlag).
SET search_path = taktovia, public;

-- Ziel der zusammengesetzten Fremdschlüssel der Aktivierung: Satz mit Ereignis und Revision.
ALTER TABLE allocation_set ADD CONSTRAINT allocation_set_activation_target UNIQUE (tenant_id, id, event_id, revision);
ALTER TABLE allocation_set ADD CONSTRAINT allocation_set_revision_positive CHECK (revision >= 1);
ALTER TABLE allocation_set ADD CONSTRAINT allocation_set_safe_basis CHECK (abs(basis_minor) <= 9007199254740991);
ALTER TABLE allocation_line ADD CONSTRAINT allocation_line_safe_amount CHECK (abs(amount_minor) <= 9007199254740991);
CREATE INDEX allocation_set_by_event ON allocation_set (tenant_id, event_id, revision);
CREATE INDEX allocation_line_by_object ON allocation_line (tenant_id, cost_object_id);
-- RLS und Policies der Kernschema-Tabellen stehen in V1; sie bleiben unverändert.

-- Satz: Revision lückenlos je Ereignis, Basis = Kostenbasis des Ereignisses; nur DRAFT -> POSTED; beim Buchen Summe der Zeilen = Basis (centgenau); gebucht ist final, nie löschbar.
CREATE FUNCTION allocation_set_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE basis bigint; last_rev bigint; total numeric; n bigint;
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'allocation_set: ein Umlagesatz wird nie geloescht (Korrektur nur als neue Revision)' USING ERRCODE = '23514'; END IF;
  IF TG_OP = 'INSERT' THEN
    IF NEW.state <> 'DRAFT' THEN RAISE EXCEPTION 'allocation_set: Satz anlegen als DRAFT, Zeilen ergaenzen, dann buchen' USING ERRCODE = '23514'; END IF;
    SELECT cost_basis_minor INTO basis FROM economic_event WHERE tenant_id = NEW.tenant_id AND id = NEW.event_id;
    IF basis IS DISTINCT FROM NEW.basis_minor THEN RAISE EXCEPTION 'allocation_set: Basis % entspricht nicht der Kostenbasis % des Ereignisses', NEW.basis_minor, basis USING ERRCODE = '23514'; END IF;
    SELECT coalesce(max(revision), 0) INTO last_rev FROM allocation_set WHERE tenant_id = NEW.tenant_id AND event_id = NEW.event_id;
    IF NEW.revision <> last_rev + 1 THEN RAISE EXCEPTION 'allocation_set: Revision % statt %', NEW.revision, last_rev + 1 USING ERRCODE = '23514'; END IF;
    RETURN NEW;
  END IF;
  IF OLD.state = 'POSTED' THEN RAISE EXCEPTION 'allocation_set: gebuchte Umlage ist final unveraenderlich' USING ERRCODE = '23514'; END IF;
  IF (NEW.tenant_id, NEW.id, NEW.event_id, NEW.revision, NEW.legal_entity_id, NEW.currency, NEW.basis_minor, NEW.algorithm_version, NEW.driver_snapshot)
     IS DISTINCT FROM (OLD.tenant_id, OLD.id, OLD.event_id, OLD.revision, OLD.legal_entity_id, OLD.currency, OLD.basis_minor, OLD.algorithm_version, OLD.driver_snapshot) THEN
    RAISE EXCEPTION 'allocation_set: nur der Zustand aendert sich' USING ERRCODE = '23514';
  END IF;
  IF NEW.state = 'POSTED' THEN
    SELECT coalesce(sum(amount_minor), 0), count(*) INTO total, n FROM allocation_line WHERE tenant_id = NEW.tenant_id AND allocation_id = NEW.id;
    IF n < 1 OR total <> NEW.basis_minor THEN RAISE EXCEPTION 'allocation_set: Summe der Teilbetraege % entspricht nicht der Basis % (centgenau, mindestens eine Zeile)', total, NEW.basis_minor USING ERRCODE = '23514'; END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER allocation_set_guard BEFORE INSERT OR UPDATE OR DELETE ON allocation_set FOR EACH ROW EXECUTE FUNCTION allocation_set_guard();

-- Zeilen: nur anhängen, nur solange der Satz DRAFT ist; das Kostenobjekt gehört zum Rechtsträger des Satzes (und damit des Ereignisses) und ist aktiv.
CREATE FUNCTION allocation_line_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE s allocation_set%ROWTYPE; o_entity uuid; o_active boolean;
BEGIN
  IF TG_OP <> 'INSERT' THEN RAISE EXCEPTION 'allocation_line: Zeilen werden nur angehaengt' USING ERRCODE = '23514'; END IF;
  SELECT * INTO s FROM allocation_set WHERE tenant_id = NEW.tenant_id AND id = NEW.allocation_id FOR SHARE;
  IF NOT FOUND THEN RETURN NEW; END IF;   -- der Fremdschluessel meldet den fehlenden Satz
  IF s.state <> 'DRAFT' THEN RAISE EXCEPTION 'allocation_line: gebuchte Umlage ist final unveraenderlich' USING ERRCODE = '23514'; END IF;
  SELECT legal_entity_id, active INTO o_entity, o_active FROM cost_object WHERE tenant_id = NEW.tenant_id AND id = NEW.cost_object_id FOR SHARE;
  IF FOUND THEN
    IF o_entity <> s.legal_entity_id THEN RAISE EXCEPTION 'allocation_line: Kostenobjekt gehoert zu einem anderen Rechtstraeger als das Ereignis' USING ERRCODE = '23514'; END IF;
    IF o_active = false THEN RAISE EXCEPTION 'allocation_line: Kostenobjekt ist inaktiv' USING ERRCODE = '23514'; END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER allocation_line_guard BEFORE INSERT OR UPDATE OR DELETE ON allocation_line FOR EACH ROW EXECUTE FUNCTION allocation_line_guard();

-- Genau eine aktive Umlagerevision je Ereignis (Primärschlüssel); der Wechsel auf eine neuere gebuchte Revision ist ein UPDATE, nie ein zweiter Eintrag.
CREATE TABLE allocation_activation (
  tenant_id     uuid        NOT NULL REFERENCES tenant(id),
  event_id      uuid        NOT NULL,
  allocation_id uuid        NOT NULL,
  revision      bigint      NOT NULL CHECK (revision >= 1),
  activated_by  text        NOT NULL CHECK (length(activated_by) BETWEEN 1 AND 200),
  activated_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, event_id),
  FOREIGN KEY (tenant_id, allocation_id, event_id, revision) REFERENCES allocation_set (tenant_id, id, event_id, revision)
);
CREATE INDEX allocation_activation_by_set ON allocation_activation (tenant_id, allocation_id);
ALTER TABLE allocation_activation ENABLE ROW LEVEL SECURITY;
ALTER TABLE allocation_activation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON allocation_activation USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON allocation_activation FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change();

CREATE FUNCTION allocation_activation_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE st text;
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'allocation_activation: die Aktivierung wird nie geloescht' USING ERRCODE = '23514'; END IF;
  SELECT state INTO st FROM allocation_set WHERE tenant_id = NEW.tenant_id AND id = NEW.allocation_id FOR SHARE;
  IF st IS DISTINCT FROM 'POSTED' THEN RAISE EXCEPTION 'allocation_activation: nur eine gebuchte Umlagerevision wird aktiv' USING ERRCODE = '23514'; END IF;
  IF TG_OP = 'UPDATE' AND (NEW.event_id <> OLD.event_id OR NEW.revision <= OLD.revision) THEN
    RAISE EXCEPTION 'allocation_activation: nur der Wechsel auf eine neuere Revision desselben Ereignisses' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER allocation_activation_guard BEFORE INSERT OR UPDATE OR DELETE ON allocation_activation FOR EACH ROW EXECUTE FUNCTION allocation_activation_guard();

-- Beleg-Split vor dem Posting (Aufteilungsplan des Belegs auf die ihm zugeordneten Kostenobjekte): Modus AMOUNT (Beträge), PERCENT (Prozent, Summe 100) oder WEIGHT (Schlüsselmengen).
-- Das Posting wandelt den vollständigen Plan atomar in die erste gebuchte Umlagerevision. Änderungen nur im Belegzustand STORED; ein Wechsel der Kostenzuordnung verwirft den Plan (Kaskade).
CREATE TABLE expense_split (
  tenant_id       uuid        NOT NULL REFERENCES tenant(id),
  document_id     uuid        NOT NULL,
  mode            text        NOT NULL CONSTRAINT expense_split_mode CHECK (mode IN ('AMOUNT', 'PERCENT', 'WEIGHT')),
  policy_revision text        NOT NULL CHECK (length(policy_revision) BETWEEN 1 AND 256),
  reason          text        NOT NULL CHECK (length(reason) BETWEEN 3 AND 1000),
  set_by          text        NOT NULL CHECK (length(set_by) BETWEEN 1 AND 200),
  set_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, document_id),
  FOREIGN KEY (tenant_id, document_id) REFERENCES expense_document(tenant_id, id)
);
CREATE TABLE expense_split_line (
  tenant_id      uuid           NOT NULL REFERENCES tenant(id),
  document_id    uuid           NOT NULL,
  cost_object_id uuid           NOT NULL,
  percent        numeric(20, 8) CHECK (percent >= 0 AND percent <= 100),
  amount_minor   bigint         CHECK (amount_minor >= 0 AND amount_minor <= 9007199254740991),
  quantity       numeric(20, 8) CHECK (quantity > 0),
  unit           text           CHECK (unit IN ('KM', 'MIN', 'KWH', 'L', 'ITEM')),
  PRIMARY KEY (tenant_id, document_id, cost_object_id),
  CONSTRAINT expense_split_line_one_driver CHECK (num_nonnulls(percent, amount_minor, quantity) = 1),
  FOREIGN KEY (tenant_id, document_id) REFERENCES expense_split(tenant_id, document_id) ON DELETE CASCADE,
  -- nur Kostenobjekte, die dem Beleg zugeordnet sind (Mandant und Rechtsträger sichert die Zuordnung); entfällt die Zuordnung, entfällt die Zeile
  FOREIGN KEY (tenant_id, document_id, cost_object_id) REFERENCES expense_cost_assignment(tenant_id, document_id, cost_object_id) ON DELETE CASCADE
);
CREATE INDEX expense_split_line_by_object ON expense_split_line (tenant_id, cost_object_id);
DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['expense_split', 'expense_split_line'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant())', t);
  END LOOP;
END $$;
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON expense_split FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change();

CREATE FUNCTION expense_split_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE doc_state text; tid uuid; did uuid;
BEGIN
  IF TG_OP = 'DELETE' THEN tid := OLD.tenant_id; did := OLD.document_id; ELSE tid := NEW.tenant_id; did := NEW.document_id; END IF;
  SELECT state INTO doc_state FROM expense_document WHERE tenant_id = tid AND id = did FOR SHARE;
  IF FOUND AND doc_state <> 'STORED' THEN
    RAISE EXCEPTION 'expense_split: Aufteilung nur im Entwurfszustand STORED aenderbar (Zustand %)', doc_state USING ERRCODE = '23514';
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER expense_split_guard BEFORE INSERT OR UPDATE OR DELETE ON expense_split FOR EACH ROW EXECUTE FUNCTION expense_split_guard();
CREATE TRIGGER expense_split_line_guard BEFORE INSERT OR UPDATE OR DELETE ON expense_split_line FOR EACH ROW EXECUTE FUNCTION expense_split_guard();
