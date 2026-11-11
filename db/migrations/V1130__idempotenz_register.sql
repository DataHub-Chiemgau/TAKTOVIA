-- WP-M1-13 (LH-6.17-INT-004): Zustand, Lease und gespeichertes HTTP-Ergebnis des Idempotenzregisters.
-- Keine neue Tabelle. PK (tenant_id, principal_ref, command_scope, key) bleibt der Scope des Registers.
-- IN_PROGRESS: Erstverarbeitung läuft; locked_until ist die Lease (abgelaufen = abgebrochene Verarbeitung, Übernahme erlaubt).
-- COMPLETED: das angenommene Ergebnis (http_status, response_json, response_headers) wird bis expires_at wiederholt.
SET search_path = taktovia, public;

ALTER TABLE idempotency_record
  ADD COLUMN state            text        NOT NULL DEFAULT 'IN_PROGRESS' CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
  ADD COLUMN locked_until     timestamptz,
  ADD COLUMN http_status      integer     CHECK (http_status BETWEEN 200 AND 299),
  ADD COLUMN response_headers jsonb,
  ADD COLUMN created_at       timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN completed_at     timestamptz,
  ADD CONSTRAINT idempotency_completed_has_status CHECK (state = 'IN_PROGRESS' OR (http_status IS NOT NULL AND completed_at IS NOT NULL)),
  ADD CONSTRAINT idempotency_key_length CHECK (char_length(key) BETWEEN 1 AND 128),   -- Muster und 16-Zeichen-Minimum erzwingt die API (Vertrag)
  ADD CONSTRAINT idempotency_sha_format CHECK (request_sha256 ~ '^[0-9a-f]{64}$');

-- Bereinigung nach Ablauf (ohne laufende Befehle) arbeitet je Mandant über diesen Index.
CREATE INDEX idempotency_record_expiry ON idempotency_record (tenant_id, expires_at);
