-- WP-M1-06 / LH-I.4-MUL-006, LH-6.17-INT-002: Mitgliedschaft mit Gültigkeitszeitraum und Revision; kontrollierter Lookup
-- „Mandanten eines Subjects“ vor gesetztem Tenantkontext (OP-04, OP-06).
SET search_path = taktovia, public;

-- OP-04 (Entscheidung des Plans für den Bau, fachliche Freigabe offen): zeitlich begrenzte Mitgliedschaft über valid_from/valid_to,
-- Widerruf weiterhin über revoked_at. Aktiv = revoked_at IS NULL AND valid_from <= now() AND (valid_to IS NULL OR valid_to > now()).
-- service_scope NULL = keine Dienstfreigabe (fail-closed); eine Dienstfreigabe ist immer eine ausdrückliche Liste.
ALTER TABLE membership
  ADD COLUMN valid_from timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN valid_to   timestamptz,
  ADD COLUMN revision   bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  ADD CONSTRAINT membership_valid_interval CHECK (valid_to IS NULL OR valid_to > valid_from),
  ADD CONSTRAINT membership_subject_format CHECK (length(issuer) BETWEEN 1 AND 512 AND length(subject) BETWEEN 1 AND 200);
CREATE INDEX membership_by_subject ON membership (tenant_id, issuer, subject) WHERE revoked_at IS NULL;
CREATE INDEX membership_by_created ON membership (tenant_id, created_at, id);

-- Kernfelder einer Mitgliedschaft sind nach dem Erteilen unveränderlich (Kennung, Subject, Rolle); ein Widerruf ist endgültig
-- (kein Zurücknehmen von revoked_at); jede Änderung erhöht die Revision genau um 1. Gilt auch für Administration.
CREATE FUNCTION membership_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.issuer IS DISTINCT FROM OLD.issuer OR NEW.subject IS DISTINCT FROM OLD.subject OR NEW.role_code IS DISTINCT FROM OLD.role_code
     OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'membership identity is immutable' USING ERRCODE = '23514';
  END IF;
  IF OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at THEN
    RAISE EXCEPTION 'membership revocation is final' USING ERRCODE = '23514';
  END IF;
  IF NEW IS DISTINCT FROM OLD THEN NEW.revision := OLD.revision + 1; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER membership_guard BEFORE UPDATE ON membership FOR EACH ROW EXECUTE FUNCTION membership_guard();

-- OP-06 (Entscheidung des Plans für den Bau): Vor dem Tenantkontext kennt der Server nur das geprüfte Subject (issuer, subject aus dem
-- verifizierten Token). Ein schmaler Verzeichnisindex je Subject ersetzt Allzweck-SECURITY-DEFINER und globale Leserechte: Die Zeilen sind
-- nur sichtbar, wenn der transaktionslokale Schlüssel `app.subject_key` genau dem Subject der Zeile entspricht (Policy subject_lookup);
-- geschrieben wird nur im Tenantkontext des eigenen Mandanten (Policy tenant_write). Der Index nennt nur „Subject ↔ Mandant“ und ist
-- ein Kandidatenverzeichnis: Gültigkeit und Rolle prüft der Server danach in diesem Mandanten aus `membership` (Wahrheit). Der Index
-- wird durch den Trigger am Erteilen gepflegt und nie gelöscht; ein veralteter Eintrag öffnet nichts. Keine View, keine Definer-Funktion.
CREATE TABLE membership_subject_index (
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  issuer    text NOT NULL CHECK (length(issuer) BETWEEN 1 AND 512),
  subject   text NOT NULL CHECK (length(subject) BETWEEN 1 AND 200),
  PRIMARY KEY (tenant_id, issuer, subject)
);
CREATE INDEX membership_subject_index_lookup ON membership_subject_index (issuer, subject);
ALTER TABLE membership_subject_index ENABLE ROW LEVEL SECURITY;
ALTER TABLE membership_subject_index FORCE ROW LEVEL SECURITY;
CREATE POLICY subject_lookup ON membership_subject_index FOR SELECT
  USING (nullif(current_setting('app.subject_key', true), '') = issuer || E'\n' || subject);
CREATE POLICY tenant_write ON membership_subject_index FOR INSERT WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON membership_subject_index FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();

CREATE FUNCTION membership_index_subject() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  INSERT INTO membership_subject_index (tenant_id, issuer, subject) VALUES (NEW.tenant_id, NEW.issuer, NEW.subject) ON CONFLICT DO NOTHING;
  RETURN NEW;
END $$;
CREATE TRIGGER membership_index_subject AFTER INSERT ON membership FOR EACH ROW EXECUTE FUNCTION membership_index_subject();
