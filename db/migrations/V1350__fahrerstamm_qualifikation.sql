-- WP-M1-35 / LH-I.4-MUL-005: minimaler Fahrerstamm (Kap. 04 §4.4 DRV/FLT, Stammteil) als Grundlage der zeitlich begrenzten Fahrerfreigabe.
-- Nur, was die Freigabe braucht und was in M1 mit Fixture-Fahrern nachweisbar ist; der Fahrerstamm im Vollausbau (Personalakte, Arbeitszeit,
-- Abwesenheit, Gehalt) folgt mit M3 (WP-M3-05) und liegt ausdrücklich NICHT in dieser Tabelle. OP-26: Tabellen- und Feldnamen sind Planungsannahme.
SET search_path = taktovia, public;

CREATE TABLE driver (
  tenant_id       uuid        NOT NULL REFERENCES tenant(id),
  id              uuid        NOT NULL,
  display_name    text        NOT NULL CHECK (length(display_name) BETWEEN 1 AND 200),   -- Klarname: nie Teil einer Projektion
  contact_channel text        NOT NULL CHECK (contact_channel IN ('PHONE', 'EMAIL', 'APP')),
  contact_value   text        NOT NULL CHECK (length(contact_value) BETWEEN 1 AND 200),
  status          text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
  revision        bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id)
);
ALTER TABLE driver ENABLE ROW LEVEL SECURITY;
ALTER TABLE driver FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON driver USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON driver FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();

-- Qualifikation je Fahrer in Revisionen (append-only): je Qualifikation gilt die höchste Revision.
CREATE TABLE qualification_revision (
  tenant_id          uuid        NOT NULL REFERENCES tenant(id),
  id                 uuid        NOT NULL,
  driver_id          uuid        NOT NULL,
  qualification_code text        NOT NULL CHECK (qualification_code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
  revision           bigint      NOT NULL CHECK (revision >= 1),
  valid_from         timestamptz NOT NULL,
  valid_to           timestamptz,
  evidence_ref       text        CHECK (evidence_ref IS NULL OR length(evidence_ref) <= 200),   -- interner Nachweisverweis: nie Teil einer Projektion
  created_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, driver_id, qualification_code, revision),
  FOREIGN KEY (tenant_id, driver_id) REFERENCES driver(tenant_id, id),
  CHECK (valid_to IS NULL OR valid_to > valid_from)
);
ALTER TABLE qualification_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE qualification_revision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qualification_revision USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON qualification_revision FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
