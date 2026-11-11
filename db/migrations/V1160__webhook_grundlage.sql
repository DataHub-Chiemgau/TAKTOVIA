-- WP-M1-16: Webhook-Grundlage. `webhook_subscription` (Abonnement mit Secret-REFERENZ, nie Secretwert), `webhook_delivery` (Zustellung je Abonnement und Ereignis
-- mit Retry-Zustand und Dead-Letter), `webhook_quarantine` (Eingangsereignis mit gleicher ID und anderem Payload; nur Hashes, kein Payload).
-- Der Eingang nutzt die bestehende `inbox` (Dedupe `(consumer, event_id)`, Consumer `webhook.<quelle>`).
SET search_path = taktovia, public;

CREATE TABLE webhook_subscription (
  tenant_id      uuid        NOT NULL REFERENCES tenant(id),
  id             uuid        NOT NULL,
  url            text        NOT NULL CHECK (url ~ '^https://' AND char_length(url) <= 2000),
  event_types    text[]      NOT NULL CHECK (cardinality(event_types) BETWEEN 1 AND 50),
  secret_ref     text        NOT NULL CHECK (secret_ref ~ '^secret://[a-z][a-z0-9-]{2,62}/[a-z][a-z0-9_]{1,62}$'),
  active         boolean     NOT NULL DEFAULT true,
  revision       bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_by     text        NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  deactivated_at timestamptz,
  PRIMARY KEY (tenant_id, id),
  CHECK (active = (deactivated_at IS NULL))
);

CREATE TABLE webhook_delivery (
  tenant_id        uuid        NOT NULL REFERENCES tenant(id),
  id               uuid        NOT NULL,
  subscription_id  uuid        NOT NULL,
  event_id         uuid        NOT NULL,
  event_type       text        NOT NULL,
  body             jsonb       NOT NULL,                       -- empfängergebundener, minimierter Payload (Hülle ohne Nutzdaten)
  state            text        NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'DELIVERED', 'DEAD', 'CANCELLED')),   -- CANCELLED: Abonnement wurde vor der Zustellung deaktiviert
  attempts         integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  next_attempt_at  timestamptz,                                -- NULL = sofort fällig
  claimed_until    timestamptz,                                -- Lease des Zustellworkers; abgelaufen = wieder fällig
  last_status      integer     CHECK (last_status BETWEEN 100 AND 599),
  last_error       text        CHECK (last_error ~ '^[a-z0-9_]{1,64}$'),   -- nur Fehlercode, nie Meldungstext oder Antwortinhalt
  created_at       timestamptz NOT NULL DEFAULT now(),
  delivered_at     timestamptz,
  dead_lettered_at timestamptz,
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, subscription_id, event_id),
  FOREIGN KEY (tenant_id, subscription_id) REFERENCES webhook_subscription (tenant_id, id),
  CHECK ((state = 'DELIVERED') = (delivered_at IS NOT NULL)),
  CHECK ((state = 'DEAD') = (dead_lettered_at IS NOT NULL))
);
CREATE INDEX webhook_delivery_due ON webhook_delivery (next_attempt_at NULLS FIRST, created_at) WHERE state = 'PENDING';

CREATE TABLE webhook_quarantine (
  tenant_id       uuid        NOT NULL REFERENCES tenant(id),
  id              uuid        NOT NULL,
  source          text        NOT NULL CHECK (source ~ '^[a-z][a-z0-9_-]{1,62}$'),
  event_id        uuid        NOT NULL,
  payload_sha256  char(64)    NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),   -- Hash des abweichenden Payloads
  original_sha256 char(64)    NOT NULL CHECK (original_sha256 ~ '^[0-9a-f]{64}$'),  -- Hash des zuerst angenommenen Payloads
  reason          text        NOT NULL CHECK (reason ~ '^[a-z0-9_]{1,64}$'),
  received_at     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, id)
);

CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON webhook_subscription FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON webhook_delivery FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON webhook_quarantine FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();

DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['webhook_subscription', 'webhook_delivery', 'webhook_quarantine'] LOOP
    EXECUTE format('ALTER TABLE taktovia.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE taktovia.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON taktovia.%I USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant())', t);
  END LOOP;
END $$;
