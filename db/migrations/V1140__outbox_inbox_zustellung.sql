-- WP-M1-14 (OP-16): Empfängeradressierung, Zustellzustand und Dead-Letter für outbox; Aggregatbezug für inbox.
-- Keine neuen Tabellen. Die Tabellen sind in M1 noch leer (keine Fachereignisse vor M1-WPs), daher NOT NULL ohne Datenmigration.
SET search_path = taktovia, public;

ALTER TABLE outbox
  ADD COLUMN recipient_tenant_id uuid NOT NULL DEFAULT current_tenant(),   -- Empfängeradressierung; Standard: der Eigentümer selbst
  ADD COLUMN recorded_at         timestamptz NOT NULL DEFAULT now(),         -- Aufzeichnungszeit (Ereigniszeit ist occurred_at)
  ADD COLUMN correlation_id      uuid,
  ADD COLUMN delivery_attempts   integer NOT NULL DEFAULT 0 CHECK (delivery_attempts >= 0),
  ADD COLUMN next_attempt_at     timestamptz,                                -- NULL = sofort fällig
  ADD COLUMN claimed_until       timestamptz,                                -- Lease des Relays; abgelaufen = wieder fällig
  ADD COLUMN last_error          text CHECK (last_error ~ '^[a-z0-9_]{1,64}$'),   -- nur Fehlercode, nie Meldungstext
  ADD COLUMN dead_lettered_at    timestamptz,
  ADD CONSTRAINT outbox_dead_letter_unpublished CHECK (dead_lettered_at IS NULL OR published_at IS NULL),
  ADD CONSTRAINT outbox_schema_version_format CHECK (schema_version ~ '^[0-9]+\.[0-9]+$');
-- Teilindex für fällige Zustellung (ersetzt outbox_unpublished nicht; der bleibt für Lückenerkennung und Zählung)
CREATE INDEX outbox_due ON outbox (next_attempt_at NULLS FIRST, occurred_at) WHERE published_at IS NULL AND dead_lettered_at IS NULL;

ALTER TABLE inbox
  ADD COLUMN aggregate_id       uuid        NOT NULL,
  ADD COLUMN aggregate_revision bigint      NOT NULL CHECK (aggregate_revision >= 1),
  ADD COLUMN event_type         text        NOT NULL,
  ADD COLUMN occurred_at        timestamptz NOT NULL,                          -- geschäftliche Ereigniszeit; Empfangszeit ist received_at
  ADD COLUMN outcome            text        NOT NULL DEFAULT 'PROCESSED' CHECK (outcome IN ('PROCESSED', 'STALE', 'GAP_SNAPSHOT'));
CREATE INDEX inbox_by_aggregate ON inbox (tenant_id, consumer, aggregate_id, aggregate_revision DESC);
