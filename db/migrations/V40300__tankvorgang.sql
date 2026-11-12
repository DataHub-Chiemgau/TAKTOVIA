-- WP-M4-30 (LH-I.1-DOC-005): Tankbeleg-Fachdaten (Modul documents-finance).
-- `fuel_transaction`: der Tankvorgang eines Belegs der Art FUEL_RECEIPT (Fahrzeug, Kraftstoffart, Menge mit Einheit, Preis je Einheit, Brutto, Kilometerstand, Tankzeit, Karten-ID).
-- Menge und Preis sind numeric(20,8), nie Gleitkomma; Brutto und Währung sind der Belegstand (`expense_document.current_fields`) und werden hier nur gespiegelt (Trigger), damit es nie zwei
-- Wahrheiten gibt. Fehlende Pflichtwerte (NULL) sind im Entwurf zulässig und werden einzeln angezeigt; die Freigabesperre „Pflichtwert fehlt“ liegt im Freigabe-Workflow (WP-M4-34).
-- `finance_policy`: mandantenbezogene Fachparameter des Moduls (Planungsstand, D-06); `fuel_receipt_required` hier, weitere Parameter mit WP-M4-31, -32, -34.
SET search_path = taktovia, public;

CREATE TABLE fuel_transaction (
  tenant_id         uuid          NOT NULL REFERENCES tenant(id),
  id                uuid          NOT NULL,
  document_id       uuid          NOT NULL,
  vehicle_id        uuid,
  fuel_type         text          CONSTRAINT fuel_transaction_type CHECK (fuel_type IN ('PETROL', 'DIESEL', 'LPG', 'ELECTRIC')),
  quantity          numeric(20,8) CONSTRAINT fuel_transaction_quantity_positive CHECK (quantity > 0),
  unit              text          GENERATED ALWAYS AS (CASE WHEN fuel_type IS NULL THEN NULL WHEN fuel_type = 'ELECTRIC' THEN 'KWH' ELSE 'LITER' END) STORED,   -- Liter und kWh getrennt (Kap. 03 §2.1)
  unit_price        numeric(20,8) CONSTRAINT fuel_transaction_price_positive CHECK (unit_price > 0),
  gross_minor       bigint        CONSTRAINT fuel_transaction_gross_safe CHECK (gross_minor > 0 AND gross_minor <= 9007199254740991),
  currency          char(3)       REFERENCES currency(code),
  odometer_km       bigint        CONSTRAINT fuel_transaction_odometer CHECK (odometer_km >= 0 AND odometer_km <= 10000000),
  fueled_at         timestamptz,
  card_id           text          CONSTRAINT fuel_transaction_card CHECK (card_id ~ '^[A-Za-z0-9._-]{1,64}$'),
  document_revision bigint        NOT NULL CHECK (document_revision >= 1),
  recorded_by       text          NOT NULL CHECK (length(recorded_by) BETWEEN 1 AND 200),
  recorded_at       timestamptz   NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, document_id),
  FOREIGN KEY (tenant_id, document_id) REFERENCES expense_document(tenant_id, id),
  FOREIGN KEY (tenant_id, vehicle_id) REFERENCES vehicle(tenant_id, id)
);
CREATE INDEX fuel_transaction_by_vehicle ON fuel_transaction (tenant_id, vehicle_id, fueled_at);
ALTER TABLE fuel_transaction ENABLE ROW LEVEL SECURITY;
ALTER TABLE fuel_transaction FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fuel_transaction USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON fuel_transaction FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change();

-- Nur für Tankbelege, nur im Entwurfszustand STORED; Brutto und Währung kommen aus dem Belegstand (nie aus dem Aufruf), der Beleg ist unveränderlich verknüpft.
CREATE FUNCTION fuel_transaction_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE d record;
BEGIN
  SELECT state, document_kind, current_fields INTO d FROM expense_document WHERE tenant_id = NEW.tenant_id AND id = NEW.document_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'fuel_transaction: Beleg nicht gefunden' USING ERRCODE = '23503'; END IF;
  IF d.document_kind <> 'FUEL_RECEIPT' THEN RAISE EXCEPTION 'fuel_transaction: nur fuer Belege der Art FUEL_RECEIPT' USING ERRCODE = '23514'; END IF;
  IF TG_OP = 'UPDATE' AND NEW.document_id IS DISTINCT FROM OLD.document_id THEN RAISE EXCEPTION 'fuel_transaction: Belegbezug ist unveraenderlich' USING ERRCODE = '23514'; END IF;
  IF d.state <> 'STORED' THEN RAISE EXCEPTION 'fuel_transaction: Tankdaten nur im Entwurfszustand STORED aenderbar (Zustand %)', d.state USING ERRCODE = '23514'; END IF;
  NEW.gross_minor := (d.current_fields ->> 'amount_minor')::bigint;
  NEW.currency := d.current_fields ->> 'currency';
  RETURN NEW;
END $$;
CREATE TRIGGER fuel_transaction_guard BEFORE INSERT OR UPDATE ON fuel_transaction FOR EACH ROW EXECUTE FUNCTION fuel_transaction_guard();

-- Ändert sich der Belegstand (Betrag, Währung), folgt die Spiegelung im selben Statement (derselbe Beleg ist gesperrt, `FOR UPDATE` der Korrektur).
CREATE FUNCTION fuel_transaction_sync() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.document_kind = 'FUEL_RECEIPT' AND NEW.current_fields IS DISTINCT FROM OLD.current_fields THEN
    UPDATE fuel_transaction SET gross_minor = (NEW.current_fields ->> 'amount_minor')::bigint, currency = NEW.current_fields ->> 'currency', document_revision = NEW.revision
     WHERE tenant_id = NEW.tenant_id AND document_id = NEW.id;
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER fuel_transaction_sync AFTER UPDATE OF current_fields ON expense_document FOR EACH ROW EXECUTE FUNCTION fuel_transaction_sync();

-- Fachparameter des Moduls je Mandant. `fuel_receipt_required` (Katalog LH-J.3, Wirkung nach Detailvertrag, Offener Punkt 39): true (Vorgabe) = ein Tankbeleg ist erst mit vollständigen Tankwerten
-- freigabefähig; false = unvollständige Tankwerte blockieren nicht (gegebene Werte werden weiter geprüft). Ohne Zeile gilt die Vorgabe.
CREATE TABLE finance_policy (
  tenant_id             uuid        NOT NULL REFERENCES tenant(id),
  fuel_receipt_required boolean     NOT NULL DEFAULT true,
  revision              bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  updated_by            text        NOT NULL CHECK (length(updated_by) BETWEEN 1 AND 200),
  updated_at            timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id)
);
ALTER TABLE finance_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE finance_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON finance_policy USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON finance_policy FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change();
