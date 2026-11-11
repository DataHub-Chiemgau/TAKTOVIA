-- WP-M2-32 / LH-6.12-COM-003: asynchroner Nachrichtenversand. `notification` (ein fachlicher Auftrag, Kap. 04 §4.4) und `notification_attempt`
-- (n Zustellversuche, nur anfügen). Die Nachrichten-ID ist die Auftrags-ID und geht unverändert an jeden Versuch und an den Provider.
-- Kontaktdaten liegen nie hier: `recipient_ref` verweist auf passenger_private.id, der Kontakt wird erst beim Versand aufgelöst und nie gespeichert.
-- Zustandsautomat: docs/umsetzungsplan/vertraege/zustaende/nachricht.md. Die Vorlagenrevision (template_revision_id) ist hier noch ohne Fremdschlüssel,
-- V2330 (WP-M2-33) legt `channel_template_revision` an und ergänzt ihn.
SET search_path = taktovia, public;

CREATE TABLE notification (
  tenant_id            uuid        NOT NULL REFERENCES tenant(id),
  id                   uuid        NOT NULL,                       -- Nachrichten-ID (eindeutig, Idempotenzreferenz beim Provider)
  event_id             uuid        NOT NULL,                       -- auslösendes Ereignis (outbox.event_id) oder Aufrufer-Schlüssel der Betriebsnachricht
  recipient_ref        uuid        NOT NULL,                       -- Referenz auf passenger_private.id, keine Klartextkontakte
  channel              text        NOT NULL CHECK (channel IN ('PUSH', 'SMS', 'EMAIL')),
  event_type           text        NOT NULL CHECK (event_type ~ '^[a-z][a-z_]*(\.[a-z][a-z_]*)+$'),   -- Ereignistyp der Nachricht (Vorlagenschlüssel)
  service_id           uuid,                                       -- Träger des Verkehrsprodukts (OP 6: bis zur Klärung der Dienst)
  params               jsonb       NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(params) = 'object' AND pg_column_size(params) <= 4096),   -- typisierte Platzhalterwerte
  template_revision_id uuid,                                       -- beim ersten Versuch gebunden, danach unverändert (WP-M2-33)
  language             text        CHECK (language IS NULL OR language ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
  state                text        NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED', 'SENDING', 'RETRY_WAIT', 'UNKNOWN', 'DELIVERED', 'FAILED', 'SUPPRESSED')),
  attempt_count        integer     NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 100),
  reconcile_count      integer     NOT NULL DEFAULT 0 CHECK (reconcile_count >= 0),
  failure_reason       text        CHECK (failure_reason IN ('INVALID_CONTACT', 'PROVIDER_ERROR', 'RETRIES_EXHAUSTED', 'TEMPLATE_UNAVAILABLE', 'NO_VERIFIED_CONTACT')),
  next_attempt_at      timestamptz,                                -- NULL = sofort fällig
  claimed_until        timestamptz,                                -- Lease des Versand-Workers; abgelaufen = Ergebnis unbestimmt
  revision             bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
  delivered_at         timestamptz,
  dead_lettered_at     timestamptz,
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, event_id, recipient_ref, channel),            -- fachlicher Schlüssel: höchstens eine Nachricht je Ereignis, Empfänger, Kanal
  FOREIGN KEY (tenant_id, recipient_ref) REFERENCES passenger_private (tenant_id, id),
  FOREIGN KEY (tenant_id, service_id) REFERENCES service (tenant_id, id),
  CONSTRAINT notification_failed_has_reason CHECK ((state IN ('FAILED', 'SUPPRESSED')) = (failure_reason IS NOT NULL)),
  CONSTRAINT notification_delivered_consistent CHECK ((state = 'DELIVERED') = (delivered_at IS NOT NULL)),
  CONSTRAINT notification_dead_letter_consistent CHECK ((state = 'FAILED') = (dead_lettered_at IS NOT NULL))
);
CREATE INDEX notification_due ON notification (next_attempt_at NULLS FIRST, created_at) WHERE state IN ('QUEUED', 'RETRY_WAIT', 'UNKNOWN');
CREATE INDEX notification_sending ON notification (claimed_until) WHERE state = 'SENDING';
CREATE INDEX notification_listing ON notification (tenant_id, created_at, id);

-- Zustandsautomat serverseitig (Leitlinie 6: `UNKNOWN` wird nie stillschweigend zu Erfolg oder Fehler). Endzustände sind endgültig; Revision zählt jede Änderung.
CREATE FUNCTION notification_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.id IS DISTINCT FROM OLD.id OR NEW.event_id IS DISTINCT FROM OLD.event_id OR NEW.recipient_ref IS DISTINCT FROM OLD.recipient_ref
     OR NEW.channel IS DISTINCT FROM OLD.channel OR NEW.event_type IS DISTINCT FROM OLD.event_type OR NEW.service_id IS DISTINCT FROM OLD.service_id
     OR NEW.params IS DISTINCT FROM OLD.params OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'notification identity is immutable' USING ERRCODE = '23514';
  END IF;
  -- die einmal gebundene Vorlagenrevision und Sprache bleiben (WP-M2-33, Abnahme 5)
  IF OLD.template_revision_id IS NOT NULL AND NEW.template_revision_id IS DISTINCT FROM OLD.template_revision_id THEN
    RAISE EXCEPTION 'template revision is immutable once bound' USING ERRCODE = '23514';
  END IF;
  IF OLD.language IS NOT NULL AND NEW.language IS DISTINCT FROM OLD.language THEN
    RAISE EXCEPTION 'language is immutable once bound' USING ERRCODE = '23514';
  END IF;
  IF NEW.attempt_count < OLD.attempt_count THEN
    RAISE EXCEPTION 'attempt_count never decreases' USING ERRCODE = '23514';
  END IF;
  IF NEW.state IS DISTINCT FROM OLD.state AND NOT (
       (OLD.state = 'QUEUED'     AND NEW.state IN ('SENDING', 'SUPPRESSED'))
    OR (OLD.state = 'SENDING'    AND NEW.state IN ('DELIVERED', 'RETRY_WAIT', 'UNKNOWN', 'FAILED', 'SUPPRESSED'))
    OR (OLD.state = 'UNKNOWN'    AND NEW.state IN ('DELIVERED', 'RETRY_WAIT', 'FAILED'))
    OR (OLD.state = 'RETRY_WAIT' AND NEW.state IN ('SENDING', 'FAILED'))) THEN
    RAISE EXCEPTION 'invalid notification state transition % -> %', OLD.state, NEW.state USING ERRCODE = '23514';
  END IF;
  IF NEW IS DISTINCT FROM OLD THEN
    NEW.revision := OLD.revision + 1;
    NEW.updated_at := clock_timestamp();
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER notification_guard BEFORE UPDATE ON notification FOR EACH ROW EXECUTE FUNCTION notification_guard();
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON notification FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();

-- Zustellversuch: nur anfügen. Ein Versuch wird mit seinem Ergebnis geschrieben (der laufende Versuch ist der Zustand SENDING des Auftrags);
-- `provider_ref` ist die Referenz des Providers, `error_code` ein technischer Code, nie Meldungstext oder Inhalt.
CREATE TABLE notification_attempt (
  tenant_id       uuid        NOT NULL REFERENCES tenant(id),
  notification_id uuid        NOT NULL,
  attempt_no      integer     NOT NULL CHECK (attempt_no BETWEEN 1 AND 100),
  started_at      timestamptz NOT NULL,
  finished_at     timestamptz NOT NULL,
  outcome         text        NOT NULL CHECK (outcome IN ('ACCEPTED', 'REJECTED', 'TIMED_OUT')),
  provider_ref    text        CHECK (provider_ref IS NULL OR char_length(provider_ref) <= 200),
  error_code      text        CHECK (error_code ~ '^[a-z0-9_]{1,64}$'),
  PRIMARY KEY (tenant_id, notification_id, attempt_no),
  FOREIGN KEY (tenant_id, notification_id) REFERENCES notification (tenant_id, id),
  CHECK (finished_at >= started_at)
);

CREATE FUNCTION notification_attempt_append_only() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  RAISE EXCEPTION 'append-only table %', TG_TABLE_NAME USING ERRCODE = '23514';
END $$;
CREATE TRIGGER append_only BEFORE UPDATE OR DELETE ON notification_attempt FOR EACH ROW EXECUTE FUNCTION notification_attempt_append_only();

DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['notification', 'notification_attempt'] LOOP
    EXECUTE format('ALTER TABLE taktovia.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE taktovia.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON taktovia.%I USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant())', t);
  END LOOP;
END $$;
