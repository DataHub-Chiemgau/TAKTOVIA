-- WP-M4-31 (LH-I.1-DOC-012): Tankplausibilität mit kontrollierbaren Hinweisen (Modul documents-finance).
-- Schwellen und Vergleichsbasen als Fachparameter (Planungsstand, D-06; die Ebenen Fahrzeug und Fahrzeugtyp des Konfigurationsregisters sind im Parameterkatalog nicht vorhanden, Abweichung: siehe
-- vertraege/m4-cluster-wave10-c5.md). Hinweise (`expense_hint`) und Quittierungen (`expense_hint_ack`) sind append-only: ein Hinweis ändert weder erfasste Werte noch sich selbst.
SET search_path = taktovia, public;

ALTER TABLE finance_policy
  ADD COLUMN fuel_max_liters       numeric(12,3) CONSTRAINT finance_policy_max_liters CHECK (fuel_max_liters > 0 AND fuel_max_liters <= 100000),
  ADD COLUMN fuel_price_alert_pct  numeric(7,3)  CONSTRAINT finance_policy_price_pct CHECK (fuel_price_alert_pct >= 0 AND fuel_price_alert_pct <= 1000),
  ADD COLUMN fuel_use_alert_pct    numeric(7,3)  CONSTRAINT finance_policy_use_pct CHECK (fuel_use_alert_pct >= 0 AND fuel_use_alert_pct <= 1000),
  ADD COLUMN fuel_price_reference  jsonb         NOT NULL DEFAULT '{}'::jsonb CONSTRAINT finance_policy_price_ref CHECK (jsonb_typeof(fuel_price_reference) = 'object'),
  ADD COLUMN fuel_card_mapping     jsonb         NOT NULL DEFAULT '[]'::jsonb CONSTRAINT finance_policy_card_map CHECK (jsonb_typeof(fuel_card_mapping) = 'array');

-- Überschreibung je Fahrzeug (Tankgröße bzw. Fahrzeugtyp); NULL = Wert des Mandanten gilt.
CREATE TABLE fuel_vehicle_policy (
  tenant_id           uuid          NOT NULL REFERENCES tenant(id),
  vehicle_id          uuid          NOT NULL,
  fuel_max_liters     numeric(12,3) CONSTRAINT fuel_vehicle_policy_liters CHECK (fuel_max_liters > 0 AND fuel_max_liters <= 100000),
  fuel_use_alert_pct  numeric(7,3)  CONSTRAINT fuel_vehicle_policy_use CHECK (fuel_use_alert_pct >= 0 AND fuel_use_alert_pct <= 1000),
  revision            bigint        NOT NULL DEFAULT 1 CHECK (revision >= 1),
  updated_by          text          NOT NULL CHECK (length(updated_by) BETWEEN 1 AND 200),
  updated_at          timestamptz   NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, vehicle_id),
  FOREIGN KEY (tenant_id, vehicle_id) REFERENCES vehicle(tenant_id, id)
);
ALTER TABLE fuel_vehicle_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE fuel_vehicle_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fuel_vehicle_policy USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON fuel_vehicle_policy FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change();

-- Hinweis: ein Hinweis je Regel und Dokumentrevision (auch bei wiederholtem Prüflauf). `threshold` und `actual_value` sind Dezimalstrings bzw. Texte, `basis` nennt Vergleichswert, Schwelle und Quelle.
CREATE TABLE expense_hint (
  tenant_id         uuid        NOT NULL REFERENCES tenant(id),
  id                uuid        NOT NULL,
  document_id       uuid        NOT NULL,
  document_revision bigint      NOT NULL CHECK (document_revision >= 1),
  rule              text        NOT NULL CONSTRAINT expense_hint_rule CHECK (rule IN ('FUEL_VOLUME', 'FUEL_PRICE', 'FUEL_ODOMETER_GAP', 'FUEL_CARD_MISMATCH')),
  threshold         text        NOT NULL CHECK (length(threshold) BETWEEN 1 AND 200),
  actual_value      text        NOT NULL CHECK (length(actual_value) BETWEEN 1 AND 200),
  basis             jsonb       NOT NULL CONSTRAINT expense_hint_basis CHECK (jsonb_typeof(basis) = 'object'),
  created_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, document_id, document_revision, rule),
  FOREIGN KEY (tenant_id, document_id) REFERENCES expense_document(tenant_id, id)
);
ALTER TABLE expense_hint ENABLE ROW LEVEL SECURITY;
ALTER TABLE expense_hint FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON expense_hint USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER expense_hint_immutable BEFORE UPDATE OR DELETE ON expense_hint FOR EACH ROW EXECUTE FUNCTION taktovia.reject_mutation();

-- Quittierung: eigener append-only Eintrag (Akteur, Begründung, Zeit); `OPEN`/`ACKNOWLEDGED` ergibt sich aus seinem Vorhandensein. Höchstens eine Quittierung je Hinweis.
CREATE TABLE expense_hint_ack (
  tenant_id    uuid        NOT NULL REFERENCES tenant(id),
  hint_id      uuid        NOT NULL,
  acked_by     text        NOT NULL CHECK (length(acked_by) BETWEEN 1 AND 200),
  reason       text        NOT NULL CHECK (length(reason) BETWEEN 3 AND 1000),
  acked_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, hint_id),
  FOREIGN KEY (tenant_id, hint_id) REFERENCES expense_hint(tenant_id, id)
);
ALTER TABLE expense_hint_ack ENABLE ROW LEVEL SECURITY;
ALTER TABLE expense_hint_ack FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON expense_hint_ack USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER expense_hint_ack_immutable BEFORE UPDATE OR DELETE ON expense_hint_ack FOR EACH ROW EXECUTE FUNCTION taktovia.reject_mutation();
