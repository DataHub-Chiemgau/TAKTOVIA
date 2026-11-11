-- WP-M1-34 / LH-I.4-MUL-004: Fahrzeugrollen (Eigentümer, Betreiber, Einsatzleister, Abrechnungspartner) als unabhängige, zeitlich gültige
-- Zuordnungen Fahrzeug <-> Organisation mit halboffenem Gültigkeitsintervall [valid_from, valid_to).
SET search_path = taktovia, public;

-- Exclusion Constraint über uuid/text braucht btree_gist (vertrauenswürdige Erweiterung, der Migrator ist Datenbankeigentümer).
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

-- OP-26 (Planungsannahme für den Bau, fachlich nicht freigegeben): Abbildung der vier Lastenheftrollen auf die Begriffe aus Kap. 02 §2:
-- OWNER = Eigentümer (Eigentum), OPERATOR = Betreiber (Einsatzbetreiber), DISPATCH_PROVIDER = Einsatzleister, BILLING_PARTNER = Abrechnungspartner.
-- Jede Rolle hat je Fahrzeug höchstens eine Organisation zu einem Zeitpunkt; die vier Rollen sind voneinander unabhängig.
-- `vehicle.owner_org_ref` bleibt Referenz des Stammteils (M3) und ist keine Rollenquelle. Die Tabelle `vehicle_lease` (Nutzungsüberlassung) wird nicht
-- angelegt: Spalten und Zweck sind nicht festgelegt (OP-26), die Überlassung bildet die Betreiber-/Einsatzleisterrolle als Intervall ab.
CREATE TABLE vehicle_assignment (
  tenant_id       uuid        NOT NULL REFERENCES tenant(id),
  id              uuid        NOT NULL,
  vehicle_id      uuid        NOT NULL,
  organization_id uuid        NOT NULL,
  role            text        NOT NULL CHECK (role IN ('OWNER', 'OPERATOR', 'DISPATCH_PROVIDER', 'BILLING_PARTNER')),
  valid_from      timestamptz NOT NULL,
  valid_to        timestamptz,
  revision        bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_by      text        NOT NULL CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  -- zusammengesetzte Schlüssel: Fahrzeug und Organisation müssen demselben Mandanten gehören (keine Organisation eines fremden Mandanten)
  FOREIGN KEY (tenant_id, vehicle_id)      REFERENCES vehicle(tenant_id, id),
  FOREIGN KEY (tenant_id, organization_id) REFERENCES organization(tenant_id, id),
  CHECK (valid_to IS NULL OR valid_to > valid_from),
  -- Überlappungsverbot je Fahrzeug und Rolle (halboffene Intervalle: aneinandergrenzende Intervalle sind zulässig)
  CONSTRAINT vehicle_assignment_no_overlap EXCLUDE USING gist (tenant_id WITH =, vehicle_id WITH =, role WITH =, tstzrange(valid_from, valid_to) WITH &&)
);
-- Mandantentrennung wie alle mandantenlokalen Tabellen (die RLS-Schleife aus V1 erfasst nur die Tabellen des Kernschemas).
ALTER TABLE vehicle_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE vehicle_assignment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON vehicle_assignment USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON vehicle_assignment FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE INDEX vehicle_assignment_by_vehicle ON vehicle_assignment (tenant_id, vehicle_id, role, valid_from);
CREATE INDEX vehicle_assignment_by_created ON vehicle_assignment (tenant_id, created_at, id);

-- Unveränderlichkeit (auch für die Administration): nie löschen; ein laufendes Intervall darf nur geschlossen werden (valid_to setzen, Revision +1),
-- ein abgeschlossenes bleibt unverändert. Ein Wechsel schließt das laufende Intervall zum Stichtag und legt ein neues an.
CREATE FUNCTION vehicle_assignment_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'vehicle assignment is append-only' USING ERRCODE = '23514';
  END IF;
  IF OLD.valid_to IS NOT NULL OR NEW.valid_to IS NULL
     OR NEW.id IS DISTINCT FROM OLD.id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.vehicle_id IS DISTINCT FROM OLD.vehicle_id
     OR NEW.organization_id IS DISTINCT FROM OLD.organization_id OR NEW.role IS DISTINCT FROM OLD.role OR NEW.valid_from IS DISTINCT FROM OLD.valid_from
     OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'closed vehicle assignment is immutable; only an open interval may be closed' USING ERRCODE = '23514';
  END IF;
  NEW.revision := OLD.revision + 1;
  RETURN NEW;
END $$;
CREATE TRIGGER vehicle_assignment_guard BEFORE UPDATE OR DELETE ON vehicle_assignment FOR EACH ROW EXECUTE FUNCTION vehicle_assignment_guard();
