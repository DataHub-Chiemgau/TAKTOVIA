-- WP-M1-35 / LH-I.4-MUL-005: zeitlich begrenzte Fahrerfreigabe (Eigentümer) und minimierte Projektion (Empfänger).
-- Planungsannahme OP-26 (fachlich nicht freigegeben): Tabellennamen `driver_release`, `driver_release_projection`; ohne Kooperationsvertrag und Grants (M6).
-- Die Projektion wird beim Eigentümer erzeugt und unter dem Mandanten des Empfängers gespeichert (Kap. 02 §4.2). Der Empfänger liest NUR die
-- Projektion (feste Feldliste); er hat keinen Zugriff auf Fahrer-, Qualifikations- oder Freigabetabellen des Eigentümers (RLS) und keine Definerfunktion
-- (Kap. 04 §4.6): Der Eigentümer schreibt per Policy `owner_manage` Zeilen mit `tenant_id` = Empfänger, die er nur für Freigaben anlegen darf, die er selbst besitzt.
SET search_path = taktovia, public;

CREATE TABLE driver_release (
  tenant_id           uuid        NOT NULL REFERENCES tenant(id),   -- Eigentümer
  id                  uuid        NOT NULL,
  driver_id           uuid        NOT NULL,
  recipient_tenant_id uuid        NOT NULL REFERENCES tenant(id),
  service_ids         uuid[]      NOT NULL CHECK (cardinality(service_ids) BETWEEN 1 AND 50),   -- Dienste des Empfängers; nur für diese gilt die Freigabe
  valid_from          timestamptz NOT NULL,
  valid_to            timestamptz NOT NULL,
  revoked_at          timestamptz,
  revoked_by          text        CHECK (revoked_by IS NULL OR revoked_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  revision            bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_by          text        NOT NULL CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, driver_id) REFERENCES driver(tenant_id, id),
  CHECK (valid_to > valid_from),
  CHECK (recipient_tenant_id <> tenant_id),
  CHECK ((revoked_at IS NULL) = (revoked_by IS NULL))
);
ALTER TABLE driver_release ENABLE ROW LEVEL SECURITY;
ALTER TABLE driver_release FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON driver_release USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON driver_release FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE INDEX driver_release_by_driver ON driver_release (tenant_id, driver_id, valid_from);
CREATE INDEX driver_release_by_created ON driver_release (tenant_id, created_at, id);

-- Nie löschen; ändern darf man nur den einmaligen Widerruf (Zeitpunkt und Urheber setzen, Revision +1).
CREATE FUNCTION driver_release_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'driver release is append-only' USING ERRCODE = '23514';
  END IF;
  IF OLD.revoked_at IS NOT NULL OR NEW.revoked_at IS NULL
     OR NEW.id IS DISTINCT FROM OLD.id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.driver_id IS DISTINCT FROM OLD.driver_id
     OR NEW.recipient_tenant_id IS DISTINCT FROM OLD.recipient_tenant_id OR NEW.service_ids IS DISTINCT FROM OLD.service_ids
     OR NEW.valid_from IS DISTINCT FROM OLD.valid_from OR NEW.valid_to IS DISTINCT FROM OLD.valid_to
     OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'driver release is immutable; only a single revocation may be recorded' USING ERRCODE = '23514';
  END IF;
  NEW.revision := OLD.revision + 1;
  RETURN NEW;
END $$;
CREATE TRIGGER driver_release_guard BEFORE UPDATE OR DELETE ON driver_release FOR EACH ROW EXECUTE FUNCTION driver_release_guard();

CREATE TABLE driver_release_projection (
  tenant_id           uuid        NOT NULL REFERENCES tenant(id),   -- Empfänger (Speicherkontext der Projektion)
  id                  uuid        NOT NULL,                         -- = driver_release.id; bewusst kein mandantenübergreifender Fremdschlüssel (Kap. 04 §4.2)
  owner_tenant_id     uuid        NOT NULL REFERENCES tenant(id),
  service_ids         uuid[]      NOT NULL CHECK (cardinality(service_ids) BETWEEN 1 AND 50),
  valid_from          timestamptz NOT NULL,
  valid_to            timestamptz NOT NULL,
  revoked_at          timestamptz,
  qualifications      jsonb       NOT NULL CHECK (jsonb_typeof(qualifications) = 'array'),   -- [{code, valid_from, valid_to}]
  contact_channel     text        NOT NULL CHECK (contact_channel IN ('PHONE', 'EMAIL', 'APP')),
  contact_value       text        NOT NULL CHECK (length(contact_value) BETWEEN 1 AND 200),
  revision            bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  CHECK (valid_to > valid_from),
  CHECK (owner_tenant_id <> tenant_id)
);
ALTER TABLE driver_release_projection ENABLE ROW LEVEL SECURITY;
ALTER TABLE driver_release_projection FORCE ROW LEVEL SECURITY;
-- Empfänger: nur lesen, und nur im Zeitraum und vor dem Widerruf. Ablauf und Widerruf werden bei JEDEM Zugriff geprüft (clock_timestamp, kein Cache).
CREATE POLICY recipient_read ON driver_release_projection FOR SELECT
  USING (tenant_id = current_tenant() AND revoked_at IS NULL AND valid_from <= clock_timestamp() AND clock_timestamp() < valid_to);
-- Eigentümer: schreibt und liest die Projektionen seiner Freigaben (Zeilen unter dem Mandanten des Empfängers).
CREATE POLICY owner_manage ON driver_release_projection FOR ALL
  USING (owner_tenant_id = current_tenant()) WITH CHECK (owner_tenant_id = current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON driver_release_projection FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE INDEX driver_release_projection_by_owner ON driver_release_projection (owner_tenant_id, id);

-- Eine Projektion gibt es nur zu einer eigenen, aktiven Freigabe des Eigentümers (gleicher Empfänger, Dienste und Zeitraum) und nur mit der festen Feldliste
-- der Qualifikationen. Der Prüfbaustein läuft im Kontext des Aufrufers: nur der Eigentümer sieht seine Freigabe.
CREATE FUNCTION driver_release_projection_check() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE q jsonb; k text;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM taktovia.driver_release r WHERE r.tenant_id = NEW.owner_tenant_id AND r.id = NEW.id AND r.recipient_tenant_id = NEW.tenant_id
                 AND r.service_ids = NEW.service_ids AND r.valid_from = NEW.valid_from AND r.valid_to = NEW.valid_to AND r.revoked_at IS NULL) THEN
    RAISE EXCEPTION 'projection without matching active driver release' USING ERRCODE = '23514';
  END IF;
  FOR q IN SELECT * FROM jsonb_array_elements(NEW.qualifications) LOOP
    IF jsonb_typeof(q) <> 'object' THEN RAISE EXCEPTION 'qualification entry must be an object' USING ERRCODE = '23514'; END IF;
    FOR k IN SELECT * FROM jsonb_object_keys(q) LOOP
      IF k NOT IN ('code', 'valid_from', 'valid_to') THEN
        RAISE EXCEPTION 'field % is not part of the fixed projection field list', k USING ERRCODE = '23514';
      END IF;
    END LOOP;
  END LOOP;
  RETURN NEW;
END $$;
CREATE TRIGGER driver_release_projection_check BEFORE INSERT ON driver_release_projection FOR EACH ROW EXECUTE FUNCTION driver_release_projection_check();

-- Die Projektion ist unveränderlich bis auf die Sperre durch den Widerruf.
CREATE FUNCTION driver_release_projection_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'driver release projection is append-only' USING ERRCODE = '23514';
  END IF;
  IF OLD.revoked_at IS NOT NULL OR NEW.revoked_at IS NULL
     OR (to_jsonb(NEW) - 'revoked_at' - 'revision') IS DISTINCT FROM (to_jsonb(OLD) - 'revoked_at' - 'revision') THEN
    RAISE EXCEPTION 'driver release projection is immutable; only the revocation lock may be set' USING ERRCODE = '23514';
  END IF;
  NEW.revision := OLD.revision + 1;
  RETURN NEW;
END $$;
CREATE TRIGGER driver_release_projection_guard BEFORE UPDATE OR DELETE ON driver_release_projection FOR EACH ROW EXECUTE FUNCTION driver_release_projection_guard();

-- Der Widerruf sperrt die Projektion in derselben Transaktion (Invariante Kap. 04 §4.5 „Grantwiderruf sofort wirksam“): nach dem Commit sieht der Empfänger nichts mehr.
CREATE FUNCTION driver_release_revoke_projection() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  UPDATE taktovia.driver_release_projection SET revoked_at = NEW.revoked_at WHERE owner_tenant_id = NEW.tenant_id AND id = NEW.id AND revoked_at IS NULL;
  RETURN NULL;
END $$;
CREATE TRIGGER driver_release_revoke_projection AFTER UPDATE OF revoked_at ON driver_release FOR EACH ROW
  WHEN (OLD.revoked_at IS NULL AND NEW.revoked_at IS NOT NULL) EXECUTE FUNCTION driver_release_revoke_projection();
