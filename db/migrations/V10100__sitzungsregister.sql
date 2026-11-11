-- WP-M1-10 / LH-6.2-IAM-009: serverseitiges Sitzungsregister mit Sperrstatus und Konto-Zugangsstatus (OP-13, Vorschlag des Detailvertrags).
-- PostgreSQL führt; Redis ist nur Cache der Sperrliste. Keine Passwort- oder Tokenspalte (IAM-002): gespeichert werden nur Kennungen.
SET search_path = taktovia, public;

-- Eine Zeile je (Mandant, IdP-Sitzung): entsteht beim ersten Zugriff der Sitzung im Mandanten und wird dort gesperrt (ACTIVE -> REVOKED).
-- Die IdP-Sitzung selbst gehört dem IdP; „alle Geräte abmelden“ wirkt zusätzlich über account_access.sessions_revoked_before auf Sitzungen,
-- die im Mandanten noch nicht registriert sind.
CREATE TABLE session_register (
  tenant_id       uuid        NOT NULL REFERENCES tenant(id),
  id              uuid        NOT NULL,
  issuer          text        NOT NULL CHECK (length(issuer) BETWEEN 1 AND 512),
  subject         text        NOT NULL CHECK (length(subject) BETWEEN 1 AND 200),
  idp_session_id  text        NOT NULL CHECK (length(idp_session_id) BETWEEN 8 AND 128),
  created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  last_seen_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  expires_at      timestamptz NOT NULL,
  revoked_at      timestamptz,
  revoked_by      text        CHECK (revoked_by IS NULL OR length(revoked_by) BETWEEN 1 AND 200),
  revoke_reason   text        CHECK (revoke_reason IN ('LOGOUT','LOGOUT_ALL','ADMIN','ACCOUNT_LOCK','RECOVERY')),
  revision        bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, issuer, idp_session_id),
  CONSTRAINT session_revoked_consistent CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL))
);
CREATE INDEX session_register_by_subject ON session_register (tenant_id, issuer, subject, created_at DESC, id);

-- Zugangsstatus je Konto (Subject) im Mandanten: Kontosperre und Zeitmarke „alle Geräte abmelden / Wiederherstellung“.
-- Eine Zeile je (Mandant, Issuer, Subject), entsteht lazy; Sperren/Entsperren serialisiert die Zeile (definierter Endzustand).
CREATE TABLE account_access (
  tenant_id               uuid        NOT NULL REFERENCES tenant(id),
  issuer                  text        NOT NULL CHECK (length(issuer) BETWEEN 1 AND 512),
  subject                 text        NOT NULL CHECK (length(subject) BETWEEN 1 AND 200),
  status                  text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','LOCKED')),
  status_changed_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  status_changed_by       text        CHECK (status_changed_by IS NULL OR length(status_changed_by) BETWEEN 1 AND 200),
  lock_reason             text        CHECK (lock_reason IS NULL OR length(lock_reason) BETWEEN 1 AND 500),
  sessions_revoked_before timestamptz,
  revision                bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, issuer, subject)
);

DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['session_register','account_access'] LOOP
    EXECUTE format('ALTER TABLE taktovia.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE taktovia.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON taktovia.%I USING (tenant_id=taktovia.current_tenant()) WITH CHECK (tenant_id=taktovia.current_tenant())', t);
    EXECUTE format('CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON taktovia.%I FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change()', t);
  END LOOP;
END $$;

-- Sitzung: Kennung, Subject, IdP-Sitzung, Beginn unveränderlich; eine Sperre ist endgültig; die Revision zählt nur Sperrwechsel
-- (last_seen_at darf sich ändern, ohne If-Match-Vergleiche zu stören).
CREATE FUNCTION session_register_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.id IS DISTINCT FROM OLD.id OR NEW.issuer IS DISTINCT FROM OLD.issuer OR NEW.subject IS DISTINCT FROM OLD.subject
     OR NEW.idp_session_id IS DISTINCT FROM OLD.idp_session_id OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.expires_at IS DISTINCT FROM OLD.expires_at THEN
    RAISE EXCEPTION 'session identity is immutable' USING ERRCODE = '23514';
  END IF;
  IF OLD.revoked_at IS NOT NULL AND (NEW.revoked_at IS DISTINCT FROM OLD.revoked_at OR NEW.revoke_reason IS DISTINCT FROM OLD.revoke_reason
     OR NEW.revoked_by IS DISTINCT FROM OLD.revoked_by) THEN
    RAISE EXCEPTION 'session revocation is final' USING ERRCODE = '23514';
  END IF;
  IF NEW.revoked_at IS DISTINCT FROM OLD.revoked_at THEN NEW.revision := OLD.revision + 1; ELSE NEW.revision := OLD.revision; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER session_register_guard BEFORE UPDATE ON session_register FOR EACH ROW EXECUTE FUNCTION session_register_guard();

-- Konto: Subject unveränderlich, die Revision wächst mit jeder Änderung genau um 1, die Abmeldezeitmarke geht nie zurück.
CREATE FUNCTION account_access_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.issuer IS DISTINCT FROM OLD.issuer OR NEW.subject IS DISTINCT FROM OLD.subject OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'account identity is immutable' USING ERRCODE = '23514';
  END IF;
  IF OLD.sessions_revoked_before IS NOT NULL AND (NEW.sessions_revoked_before IS NULL OR NEW.sessions_revoked_before < OLD.sessions_revoked_before) THEN
    RAISE EXCEPTION 'sessions_revoked_before never moves back' USING ERRCODE = '23514';
  END IF;
  IF NEW IS DISTINCT FROM OLD THEN NEW.revision := OLD.revision + 1; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER account_access_guard BEFORE UPDATE ON account_access FOR EACH ROW EXECUTE FUNCTION account_access_guard();
