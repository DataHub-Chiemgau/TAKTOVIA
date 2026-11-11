-- WP-M1-23 / LH-I.7-CFG-007: Wirkungsbericht vor Publikation (Tabellenname `impact_report` bestätigt, OP-19).
-- Ein Bericht ist ein unveränderlicher Datensatz und an genau einen Revisionsstand gebunden: `config_revision_id`, die Zeilenversion der
-- Revision zum Zeitpunkt der Analyse und der SHA-256 über die geprüften Werte (kanonisches JSON: Scope, Schema-Version, Werte, Gültigkeit).
-- Ändert sich der Entwurf danach, passt der Hash nicht mehr: der Bericht ist damit ungültig (Server, `ImpactGate`). `affected_digest` ist der
-- SHA-256 über das Ergebnis der Provider und erlaubt, bei der Publikation neu entstandene betroffene Objekte zu erkennen.
-- Der Bericht enthält nur Objektverweise (Art und ID) und Anzahlen, keine Personendaten.
SET search_path = taktovia, public;

CREATE TABLE impact_report (
  tenant_id           uuid        NOT NULL REFERENCES tenant(id),
  id                  uuid        NOT NULL,
  config_revision_id  uuid        NOT NULL,
  revision_row_version bigint     NOT NULL CHECK (revision_row_version >= 1),
  values_hash         char(64)    NOT NULL CHECK (values_hash ~ '^[0-9a-f]{64}$'),
  schema_version      text        NOT NULL CHECK (schema_version ~ '^[0-9]+$'),
  affected_digest     char(64)    NOT NULL CHECK (affected_digest ~ '^[0-9a-f]{64}$'),
  highest_severity    text        NOT NULL CHECK (highest_severity IN ('NONE', 'INFO', 'WARNING', 'BLOCKING')),
  conflict_count      bigint      NOT NULL CHECK (conflict_count >= 0),
  affected            jsonb       NOT NULL CHECK (jsonb_typeof(affected) = 'array'),
  created_by          text        NOT NULL CHECK (length(created_by) BETWEEN 3 AND 200),
  created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, config_revision_id) REFERENCES config_revision(tenant_id, id)
);
CREATE INDEX impact_report_by_revision ON impact_report (tenant_id, config_revision_id, created_at DESC);

-- Berichte sind unveränderlich (Nachweis der Publikation); eine neue Analyse legt einen neuen Bericht an.
CREATE TRIGGER impact_report_immutable BEFORE UPDATE OR DELETE ON impact_report FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON impact_report FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();

ALTER TABLE impact_report ENABLE ROW LEVEL SECURITY;
ALTER TABLE impact_report FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON impact_report USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
