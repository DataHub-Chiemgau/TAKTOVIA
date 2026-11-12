-- WP-M4-32 (LH-I.1-DOC-009): Dublettenerkennung und Evidenzverknüpfung (Modul documents-finance).
-- `duplicate_candidate`: ein Treffer je Paar (neuerer Beleg, älterer Beleg): sicherer Treffer (identischer Dateihash oder vollständig gleiche Fachdaten) wird sofort als weitere Evidenz desselben Vorgangs
-- verknüpft (GLEICHE_EVIDENZ), unsicherer bleibt OFFEN und blockiert die Freigabe, bis ein Mensch entscheidet (GLEICHE_EVIDENZ oder EIGENER_VORGANG). Nie wird ein Beleg gelöscht oder zusammengeführt.
-- `evidence_link`: Beleg als Evidenz eines wirtschaftlichen Ereignisses (append-only); ein Beleg ist Evidenz höchstens eines Ereignisses, ein Ereignis hat genau einen PRIMARY (Invariante „Genau eine Kostenwirkung“).
-- Hashvergleich nur innerhalb des Mandanten (Zeilen sind nach tenant_id und RLS getrennt; der vorhandene Index `expense_hash(tenant_id, original_sha256)` trägt die Suche).
SET search_path = taktovia, public;

ALTER TABLE finance_policy
  ADD COLUMN receipt_duplicate_threshold integer NOT NULL DEFAULT 60 CONSTRAINT finance_policy_dup_threshold CHECK (receipt_duplicate_threshold BETWEEN 1 AND 100);

CREATE TABLE duplicate_candidate (
  tenant_id         uuid        NOT NULL REFERENCES tenant(id),
  id                uuid        NOT NULL,
  document_id       uuid        NOT NULL,    -- der neuere Beleg (nach Eingangszeit, dann Kennung): er ist es, der blockiert oder Evidenz wird
  other_document_id uuid        NOT NULL,    -- der ältere Beleg
  match_kind        text        NOT NULL CONSTRAINT duplicate_candidate_kind CHECK (match_kind IN ('HASH', 'FACTS')),
  certainty         text        NOT NULL CONSTRAINT duplicate_candidate_certainty CHECK (certainty IN ('SAFE', 'POSSIBLE')),
  score             integer     NOT NULL CHECK (score BETWEEN 0 AND 100),
  basis             jsonb       NOT NULL CONSTRAINT duplicate_candidate_basis CHECK (jsonb_typeof(basis) = 'object'),
  state             text        NOT NULL DEFAULT 'OFFEN' CONSTRAINT duplicate_candidate_state CHECK (state IN ('OFFEN', 'GLEICHE_EVIDENZ', 'EIGENER_VORGANG')),
  detected_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  decided_by        text        CHECK (length(decided_by) BETWEEN 1 AND 200),
  decided_at        timestamptz,
  decision_reason   text        CHECK (length(decision_reason) BETWEEN 3 AND 1000),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, document_id, other_document_id),
  CONSTRAINT duplicate_candidate_distinct CHECK (document_id <> other_document_id),
  CONSTRAINT duplicate_candidate_decided CHECK ((state = 'OFFEN') = (decided_by IS NULL AND decided_at IS NULL)),
  CONSTRAINT duplicate_candidate_safe_is_evidence CHECK (certainty <> 'SAFE' OR state = 'GLEICHE_EVIDENZ'),
  FOREIGN KEY (tenant_id, document_id) REFERENCES expense_document(tenant_id, id),
  FOREIGN KEY (tenant_id, other_document_id) REFERENCES expense_document(tenant_id, id)
);
CREATE INDEX duplicate_candidate_by_other ON duplicate_candidate (tenant_id, other_document_id);
ALTER TABLE duplicate_candidate ENABLE ROW LEVEL SECURITY;
ALTER TABLE duplicate_candidate FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON duplicate_candidate USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON duplicate_candidate FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change();

-- Ein Treffer wird nur einmal entschieden (OFFEN -> GLEICHE_EVIDENZ | EIGENER_VORGANG); solange er offen ist, darf die Neubewertung mit neuen Fachdaten Punkte, Art und Grundlage fortschreiben (auch zum
-- sicheren Treffer). Das Paar und die Entstehungszeit sind unveränderlich, ein entschiedener Treffer bleibt unverändert, gelöscht wird nie.
CREATE FUNCTION duplicate_candidate_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.document_id IS DISTINCT FROM OLD.document_id OR NEW.other_document_id IS DISTINCT FROM OLD.other_document_id OR NEW.detected_at IS DISTINCT FROM OLD.detected_at THEN
    RAISE EXCEPTION 'duplicate_candidate: Paar und Entstehungszeit sind unveraenderlich' USING ERRCODE = '23514';
  END IF;
  IF OLD.state <> 'OFFEN' AND NEW IS DISTINCT FROM OLD THEN
    RAISE EXCEPTION 'duplicate_candidate: ein entschiedener Treffer bleibt unveraendert' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER duplicate_candidate_guard BEFORE UPDATE ON duplicate_candidate FOR EACH ROW EXECUTE FUNCTION duplicate_candidate_guard();

-- Evidenz eines wirtschaftlichen Ereignisses. PRIMARY = der Beleg, aus dem das Ereignis entstand; ADDITIONAL = weitere Evidenz desselben Ereignisses (Dublette, Tankkartentransaktion).
CREATE TABLE evidence_link (
  tenant_id    uuid        NOT NULL REFERENCES tenant(id),
  id           uuid        NOT NULL,
  event_id     uuid        NOT NULL,
  document_id  uuid        NOT NULL,
  role         text        NOT NULL CONSTRAINT evidence_link_role CHECK (role IN ('PRIMARY', 'ADDITIONAL')),
  decision     text        NOT NULL CONSTRAINT evidence_link_decision CHECK (decision IN ('POSTING', 'AUTO_SAFE', 'MANUAL_SAME')),
  decided_by   text        NOT NULL CHECK (length(decided_by) BETWEEN 1 AND 200),
  decided_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  CONSTRAINT evidence_link_primary_by_posting CHECK ((role = 'PRIMARY') = (decision = 'POSTING')),
  FOREIGN KEY (tenant_id, event_id) REFERENCES economic_event(tenant_id, id),
  FOREIGN KEY (tenant_id, document_id) REFERENCES expense_document(tenant_id, id)
);
CREATE UNIQUE INDEX evidence_link_one_event_per_document ON evidence_link (tenant_id, document_id);
CREATE UNIQUE INDEX evidence_link_one_primary_per_event ON evidence_link (tenant_id, event_id) WHERE role = 'PRIMARY';
CREATE INDEX evidence_link_by_event ON evidence_link (tenant_id, event_id);
ALTER TABLE evidence_link ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence_link FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evidence_link USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER evidence_link_immutable BEFORE UPDATE OR DELETE ON evidence_link FOR EACH ROW EXECUTE FUNCTION taktovia.reject_mutation();
