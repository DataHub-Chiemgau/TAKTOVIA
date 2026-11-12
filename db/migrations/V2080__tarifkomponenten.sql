-- WP-M2-08 / LH-6.10-PAY-002: Preiskomponenten je Segment eines Angebots (Kap. 04 §4.4 `fare_component`). Die Tarifengine liefert disjunkte additive Komponenten
-- (Netto, Steuer, Gebühren; Kap. 05 §5.2); WP-M2-09 schreibt sie zusammen mit dem unveränderlichen `fare_quote`.
--  * Komponentencodes wie `FareComponent` in `api/federation.yaml`: TRANSPORT, DISCOUNT, SURCHARGE, SUBSIDY, COMMISSION, TAX. Beträge `bigint` in der kleinsten Einheit,
--    Währung aus dem Katalog; Rabatt und Zuschuss (negativ) sind P2 und werden von der Engine nicht erzeugt, die Spalte lässt sie zu.
--  * Die Zeilen gehören zum unveränderlichen Angebot: kein UPDATE, kein DELETE (Trigger reject_mutation wie `quote_immutable`).
--  * Die Regel, aus der eine Komponente stammt, wird nur als Kennung festgehalten (`rule_id`, Fremdschlüssel auf `tariff_rule`).
SET search_path = taktovia, public;

CREATE TABLE fare_component (
  tenant_id    uuid        NOT NULL REFERENCES tenant(id),
  id           uuid        NOT NULL,
  quote_id     uuid        NOT NULL,
  segment_no   integer     NOT NULL DEFAULT 1 CHECK (segment_no >= 1),
  component_no integer     NOT NULL CHECK (component_no >= 1),
  code         text        NOT NULL CONSTRAINT fare_component_code_valid CHECK (code IN ('TRANSPORT', 'DISCOUNT', 'SURCHARGE', 'SUBSIDY', 'COMMISSION', 'TAX')),
  amount_minor bigint      NOT NULL CHECK (amount_minor BETWEEN -9007199254740991 AND 9007199254740991),
  currency     char(3)     NOT NULL REFERENCES currency(code),
  rule_id      uuid,
  created_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, quote_id, segment_no, component_no),
  FOREIGN KEY (tenant_id, quote_id) REFERENCES fare_quote (tenant_id, id),
  FOREIGN KEY (tenant_id, rule_id) REFERENCES tariff_rule (tenant_id, id),
  CONSTRAINT fare_component_sign CHECK (amount_minor >= 0 OR code IN ('DISCOUNT', 'SUBSIDY'))
);
ALTER TABLE fare_component ENABLE ROW LEVEL SECURITY;
ALTER TABLE fare_component FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fare_component USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON fare_component FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE TRIGGER fare_component_immutable BEFORE UPDATE OR DELETE ON fare_component FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE INDEX fare_component_by_quote ON fare_component (tenant_id, quote_id, segment_no, component_no);
