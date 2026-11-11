-- WP-M1-05 / LH-I.4-MUL-003: Rechtsträger und Organisationen mit Revision und Urheber; die sechs vertraglichen Rollen als getrennte
-- Werte mit CHECK; Mandantenanlage und Statuswechsel durch die Plattformverwaltung ohne Allzweck-SECURITY-DEFINER.
SET search_path = taktovia, public;

-- Urheber im Format von audit_event.actor_ref (nie Name oder Mailadresse); Altzeilen und Fixtures ohne Akteur tragen den Platzhalter.
-- Revision: Kap. 04 §4.1; jede Änderung erhöht sie genau um 1 (Trigger), Grundlage für If-Match / 412.
ALTER TABLE legal_entity
  ADD COLUMN revision   bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  ADD COLUMN created_by text        NOT NULL DEFAULT 'system:unattributed'
    CONSTRAINT legal_entity_created_by_format CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$');

-- OP-05 (Planungsannahme für den Bau, fachlich nicht freigegeben): Die sechs Lastenheftrollen sind getrennte Enum-Werte. Abbildung auf die
-- Begriffe aus Kap. 02 §2: PLATFORM_OPERATOR = Plattformanbieter, TRANSPORT_COMPANY = Verkehrsunternehmen (Beförderer im Sinne des Vertrags),
-- TASK_AUTHORITY = Aufgabenträger, CLIENT = Auftraggeber (Kostenträger), BROKER = Vermittler, ACTUAL_CARRIER = tatsächlicher Beförderer.
-- Keine Rolle wird aus einer anderen abgeleitet; eine Organisation trägt eine oder mehrere. Gültigkeit der Rollenmenge als Ganzes
-- (roles_valid_from/to, halboffen); eine Gültigkeit je einzelner Rolle wäre eine Folgemigration (OP-05).
CREATE FUNCTION organization_roles_valid(r text[]) RETURNS boolean LANGUAGE sql IMMUTABLE SET search_path = taktovia, pg_catalog AS $$
  SELECT r IS NOT NULL
     AND cardinality(r) BETWEEN 1 AND 6
     AND r <@ ARRAY['PLATFORM_OPERATOR','TRANSPORT_COMPANY','TASK_AUTHORITY','CLIENT','BROKER','ACTUAL_CARRIER']::text[]
     AND cardinality(r) = (SELECT count(DISTINCT x) FROM unnest(r) AS x)
$$;
ALTER TABLE organization
  ADD COLUMN revision         bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  ADD COLUMN created_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  ADD COLUMN created_by       text        NOT NULL DEFAULT 'system:unattributed'
    CONSTRAINT organization_created_by_format CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  ADD COLUMN roles_valid_from timestamptz NOT NULL DEFAULT clock_timestamp(),
  ADD COLUMN roles_valid_to   timestamptz,
  ADD CONSTRAINT organization_roles_valid CHECK (organization_roles_valid(roles)),
  ADD CONSTRAINT organization_roles_interval CHECK (roles_valid_to IS NULL OR roles_valid_to > roles_valid_from),
  ADD CONSTRAINT organization_name_format CHECK (length(name) BETWEEN 1 AND 200 AND length(federation_ref) BETWEEN 1 AND 200);
CREATE INDEX organization_by_created ON organization (tenant_id, created_at, id);
CREATE INDEX legal_entity_by_created ON legal_entity (tenant_id, created_at, id);

-- Revision automatisch um 1 erhöhen; Entstehungsangaben sind unveränderlich (auch für die Administration).
CREATE FUNCTION master_data_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.id IS DISTINCT FROM OLD.id OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
    RAISE EXCEPTION 'master data identity is immutable' USING ERRCODE = '23514';
  END IF;
  IF NEW IS DISTINCT FROM OLD THEN NEW.revision := OLD.revision + 1; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER legal_entity_guard BEFORE UPDATE ON legal_entity FOR EACH ROW EXECUTE FUNCTION master_data_guard();
CREATE TRIGGER organization_guard BEFORE UPDATE ON organization FOR EACH ROW EXECUTE FUNCTION master_data_guard();

-- Mandantenanlage (OP-36, Planungsannahme): Die Plattformverwaltung legt den Mandanten in einer Transaktion an, deren Tenantkontext die
-- neue Mandanten-ID ist; die bestehende Policy own_tenant (id = current_tenant()) erlaubt dann genau diese eine Zeile. Es gibt keine
-- Definer-Funktion und kein Leserecht auf fremde Mandanten. Das Recht dazu (INSERT id/name/status, UPDATE status) vergibt
-- R__runtime_grants_m1_05.sql spaltenweise; wer die Anlage anstoßen darf, entscheidet allein der Server (platform_operator_account).
-- Konten der Plattformbetreiber stehen in einer globalen Tabelle ohne tenant_id: Sie sind keine Mitgliedschaften, ihre Rolle verleiht
-- keinerlei Fachdatensicht (Kap. 02 §4.3). Gepflegt nur durch die Administration; die Runtime-Rolle liest sie.
CREATE TABLE platform_operator_account (
  issuer     text        NOT NULL CHECK (length(issuer) BETWEEN 1 AND 512),
  subject    text        NOT NULL CHECK (length(subject) BETWEEN 1 AND 200),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  revoked_at timestamptz,
  PRIMARY KEY (issuer, subject)
);
-- Lesen nur mit dem transaktionslokalen Schlüssel des geprüften Subjects (wie membership_subject_index): kein Aufzählen der Betreiberkonten.
ALTER TABLE platform_operator_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_operator_account FORCE ROW LEVEL SECURITY;
CREATE POLICY subject_lookup ON platform_operator_account FOR SELECT
  USING (nullif(current_setting('app.subject_key', true), '') = issuer || E'\n' || subject);
