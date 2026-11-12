-- WP-M4-33: Kostenereignis, Journal und einmaliges Posting (Modul documents-finance).
-- economic_event (unveränderlich, Trigger economic_immutable), journal und journal_line (Trigger journal_guard/journal_line_guard) und fiscal_period stammen aus dem Kernschema (V1, V2).
-- Hier: Kontierungsregeln (accounting_mapping), genau ein Ist-Kostenereignis je Beleg, Die Ist-Sicht (nur gebuchte Journale) liegt in der Anwendung (das Schema führt keine Views, test_06_konventionen).
SET search_path = taktovia, public;

-- Kontierung: Kostenart -> Konten je Rechtsträger. Planungsannahme/Test-Kontenrahmen (D-06): Aufwandskonto der Kostenart, Vorsteuerkonto, Verbindlichkeitskonto.
CREATE TABLE accounting_mapping (
  tenant_id          uuid        NOT NULL REFERENCES tenant(id),
  legal_entity_id    uuid        NOT NULL,
  category           text        NOT NULL CHECK (category ~ '^[A-Z][A-Z0-9_]{0,39}$'),
  expense_account    text        NOT NULL CHECK (expense_account ~ '^[0-9A-Za-z._-]{1,20}$'),
  input_tax_account  text        NOT NULL CHECK (input_tax_account ~ '^[0-9A-Za-z._-]{1,20}$'),
  payable_account    text        NOT NULL CHECK (payable_account ~ '^[0-9A-Za-z._-]{1,20}$'),
  revision           bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  updated_by         text        NOT NULL CHECK (length(updated_by) BETWEEN 1 AND 200),
  updated_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, legal_entity_id, category),
  FOREIGN KEY (tenant_id, legal_entity_id) REFERENCES legal_entity(tenant_id, id),
  CONSTRAINT accounting_mapping_distinct_accounts CHECK (expense_account <> payable_account AND input_tax_account <> payable_account)
);
ALTER TABLE accounting_mapping ENABLE ROW LEVEL SECURITY;
ALTER TABLE accounting_mapping FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON accounting_mapping USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON accounting_mapping FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change();

-- Genau ein ursprüngliches Ist-Kostenereignis je Beleg (Gegenereignisse tragen `reverses_id` und sind ausgenommen).
CREATE UNIQUE INDEX economic_event_one_per_document ON economic_event (tenant_id, document_id) WHERE document_id IS NOT NULL AND reverses_id IS NULL AND scenario = 'ACTUAL';

-- Wertebereich der Beträge: sicherer Integerbereich (Kap. 05 §5.1, 2^53-1), ein Gegenereignis darf negativ sein.
ALTER TABLE economic_event ADD CONSTRAINT economic_event_safe_amounts CHECK (
  abs(net_minor) <= 9007199254740991 AND abs(tax_minor) <= 9007199254740991 AND abs(gross_minor) <= 9007199254740991 AND abs(recoverable_tax_minor) <= 9007199254740991);
