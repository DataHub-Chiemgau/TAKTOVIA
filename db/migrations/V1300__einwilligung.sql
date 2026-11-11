-- WP-M1-30 / LH-6.2-IAM-011: Einwilligungsrevisionen je Zweck, getrennt für betriebliche Mitteilungen und Marketing. Append-only (Kap. 04 §4.4).
-- Aktueller Stand je Fahrgast und Zweck = Revision mit der höchsten `sequence`; Marketing ohne Revision gilt als nicht eingewilligt (Standard).
SET search_path = taktovia, public;

CREATE TABLE consent_revision (
  tenant_id    uuid        NOT NULL REFERENCES tenant(id),
  id           uuid        NOT NULL,
  passenger_id uuid        NOT NULL,
  purpose      text        NOT NULL CHECK (purpose IN ('OPERATIONAL_SAFETY','MARKETING')),
  sequence     bigint      NOT NULL CHECK (sequence >= 1),
  granted      boolean     NOT NULL,
  recorded_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  text_version text        NOT NULL CHECK (length(text_version) BETWEEN 1 AND 64),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, passenger_id, purpose, sequence),
  FOREIGN KEY (tenant_id, passenger_id) REFERENCES passenger_private (tenant_id, id),
  -- Betriebliche/sicherheitsrelevante Mitteilungen sind nicht abwählbar (Originalabnahme IAM-011).
  CONSTRAINT consent_operational_always_granted CHECK (purpose <> 'OPERATIONAL_SAFETY' OR granted)
);
ALTER TABLE consent_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE consent_revision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON consent_revision USING (tenant_id=taktovia.current_tenant()) WITH CHECK (tenant_id=taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON consent_revision FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change();
-- Append-only: Änderung und Löschung werden verweigert (SQLSTATE 23514), auch für Administration.
CREATE TRIGGER consent_revision_immutable BEFORE UPDATE OR DELETE ON consent_revision FOR EACH ROW EXECUTE FUNCTION reject_mutation();
