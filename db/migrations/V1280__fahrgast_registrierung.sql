-- WP-M1-28 / LH-6.2-IAM-001: Fahrgastregistrierung mit Kontaktverifikation (OP-23, Entscheidungen des Plans für den Bau, fachlich nicht freigegeben).
-- Kontaktdaten liegen ausschließlich verschlüsselt in passenger_private.contact_ciphertext (WP-M1-31). Hier entstehen: das Konto-Gerüst
-- (Subject-Bindung, Status, Revision), der Verifikationsstatus je Kontaktkanal und die Verifikationsaufgaben (Codes nur als HMAC).
SET search_path = taktovia, public;

ALTER TABLE passenger_private
  ADD COLUMN idp_issuer  text CHECK (idp_issuer  IS NULL OR length(idp_issuer)  BETWEEN 1 AND 512),
  ADD COLUMN idp_subject text CHECK (idp_subject IS NULL OR length(idp_subject) BETWEEN 1 AND 200),
  ADD COLUMN status      text        NOT NULL DEFAULT 'PENDING_VERIFICATION' CHECK (status IN ('PENDING_VERIFICATION','ACTIVE','BLOCKED')),
  ADD COLUMN locale      text        NOT NULL DEFAULT 'de-DE' CHECK (locale ~ '^[a-z]{2}(-[A-Z]{2})?$'),
  ADD COLUMN revision    bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  ADD COLUMN created_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  ADD COLUMN updated_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  ADD CONSTRAINT passenger_account_ref CHECK ((idp_issuer IS NULL) = (idp_subject IS NULL));
-- Ein Konto des IdP hat im Mandanten höchstens ein Fahrgastkonto.
CREATE UNIQUE INDEX passenger_private_account ON passenger_private (tenant_id, idp_issuer, idp_subject) WHERE idp_subject IS NOT NULL;

-- Jede Änderung des Kontos erhöht die Revision genau um 1 (ETag des Profils, WP-M1-29); Kennung, Mandant und Beginn sind unveränderlich.
CREATE FUNCTION passenger_private_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.id IS DISTINCT FROM OLD.id OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'passenger identity is immutable' USING ERRCODE = '23514';
  END IF;
  IF NEW IS DISTINCT FROM OLD THEN NEW.revision := OLD.revision + 1; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER passenger_private_guard BEFORE UPDATE ON passenger_private FOR EACH ROW EXECUTE FUNCTION passenger_private_guard();

-- Kontaktkanal mit Verifikationsstatus. `contact_token` ist der HMAC des normalisierten Kontakts (Schlüssel aus der Secret-Verwaltung,
-- Vertrag LH-6.2-IAM-001): er macht „ein Kontakt, ein Konto“ und die Suche bei der Wiederherstellung möglich, ohne Klartext zu speichern.
CREATE TABLE passenger_contact (
  tenant_id         uuid        NOT NULL REFERENCES tenant(id),
  id                uuid        NOT NULL,
  passenger_id      uuid        NOT NULL,
  channel           text        NOT NULL CHECK (channel IN ('EMAIL','PHONE')),
  contact_token     text        NOT NULL CHECK (contact_token ~ '^[0-9a-f]{64}$'),
  status            text        NOT NULL DEFAULT 'UNVERIFIED' CHECK (status IN ('UNVERIFIED','VERIFIED')),
  verified_at       timestamptz,
  status_changed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  revision          bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, passenger_id, channel),
  UNIQUE (tenant_id, channel, contact_token),
  FOREIGN KEY (tenant_id, passenger_id) REFERENCES passenger_private (tenant_id, id),
  CONSTRAINT passenger_contact_verified_consistent CHECK ((status = 'VERIFIED') = (verified_at IS NOT NULL))
);

-- Verifikationsaufgabe: höchstens eine offene je Fahrgast und Kanal. Der Code selbst wird nie gespeichert, nur sein HMAC (code_mac).
CREATE TABLE contact_verification (
  tenant_id     uuid        NOT NULL REFERENCES tenant(id),
  id            uuid        NOT NULL,
  passenger_id  uuid        NOT NULL,
  channel       text        NOT NULL CHECK (channel IN ('EMAIL','PHONE')),
  contact_token text        NOT NULL CHECK (contact_token ~ '^[0-9a-f]{64}$'),
  code_mac      text        NOT NULL CHECK (code_mac ~ '^[0-9a-f]{64}$'),
  created_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  expires_at    timestamptz NOT NULL,
  attempts      integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  state         text        NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','CONSUMED','LOCKED','SUPERSEDED')),
  closed_at     timestamptz,
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, passenger_id) REFERENCES passenger_private (tenant_id, id),
  CONSTRAINT contact_verification_closed_consistent CHECK ((state = 'PENDING') = (closed_at IS NULL)),
  CONSTRAINT contact_verification_expiry CHECK (expires_at > created_at)
);
CREATE UNIQUE INDEX contact_verification_one_pending ON contact_verification (tenant_id, passenger_id, channel) WHERE state = 'PENDING';
CREATE INDEX contact_verification_recent ON contact_verification (tenant_id, passenger_id, channel, created_at DESC);

DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['passenger_contact','contact_verification'] LOOP
    EXECUTE format('ALTER TABLE taktovia.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE taktovia.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON taktovia.%I USING (tenant_id=taktovia.current_tenant()) WITH CHECK (tenant_id=taktovia.current_tenant())', t);
    EXECUTE format('CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON taktovia.%I FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change()', t);
  END LOOP;
END $$;

-- Kontakt: Ein geänderter Kontakt (anderer contact_token) ist bis zur erneuten Verifikation UNVERIFIED und nicht für die Wiederherstellung nutzbar
-- (Zustandsautomat UNVERIFIED -> VERIFIED, Rücksprung bei Änderung). Das erzwingt die Datenbank, nicht nur der Dienst.
CREATE FUNCTION passenger_contact_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.id IS DISTINCT FROM OLD.id OR NEW.passenger_id IS DISTINCT FROM OLD.passenger_id OR NEW.channel IS DISTINCT FROM OLD.channel THEN
    RAISE EXCEPTION 'contact identity is immutable' USING ERRCODE = '23514';
  END IF;
  IF NEW.contact_token IS DISTINCT FROM OLD.contact_token THEN
    NEW.status := 'UNVERIFIED'; NEW.verified_at := NULL;
  END IF;
  IF NEW.status IS DISTINCT FROM OLD.status THEN NEW.status_changed_at := clock_timestamp(); END IF;
  IF NEW IS DISTINCT FROM OLD THEN NEW.revision := OLD.revision + 1; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER passenger_contact_guard BEFORE UPDATE ON passenger_contact FOR EACH ROW EXECUTE FUNCTION passenger_contact_guard();

-- Verifikationsaufgabe: Kanal, Kontakt-Token, Code-MAC und Zeiten unveränderlich; ein geschlossener Zustand ist endgültig (Code einmalig).
CREATE FUNCTION contact_verification_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.id IS DISTINCT FROM OLD.id OR NEW.passenger_id IS DISTINCT FROM OLD.passenger_id OR NEW.channel IS DISTINCT FROM OLD.channel
     OR NEW.contact_token IS DISTINCT FROM OLD.contact_token OR NEW.code_mac IS DISTINCT FROM OLD.code_mac
     OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.expires_at IS DISTINCT FROM OLD.expires_at THEN
    RAISE EXCEPTION 'verification is immutable' USING ERRCODE = '23514';
  END IF;
  IF OLD.state <> 'PENDING' AND (NEW.state IS DISTINCT FROM OLD.state OR NEW.closed_at IS DISTINCT FROM OLD.closed_at) THEN
    RAISE EXCEPTION 'verification is closed' USING ERRCODE = '23514';
  END IF;
  IF NEW.attempts < OLD.attempts THEN RAISE EXCEPTION 'attempts never decrease' USING ERRCODE = '23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER contact_verification_guard BEFORE UPDATE ON contact_verification FOR EACH ROW EXECUTE FUNCTION contact_verification_guard();
