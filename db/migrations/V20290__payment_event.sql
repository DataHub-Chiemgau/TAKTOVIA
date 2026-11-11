-- WP-M2-29: payment_event (Kap. 04 §4.4: "PSP-Ereignisse dedupliziert"). Eingangsregister der signierten PSP-Benachrichtigungen
-- (`POST /v1/payments/provider-events/{provider}`). Eindeutig je (Mandant, Provider, Ereignis-ID); der Payloadhash (SHA-256 der kanonischen Form) erkennt dieselbe
-- ID mit anderem Inhalt. Die Ereignis-ID des PSP ist ein Text des Providers, deshalb eigene Tabelle statt `inbox` (deren Ereignis-ID ist uuid, Hülle der Plattform).
-- Der Mandant folgt aus dem Signaturschlüssel des Providers, nie aus dem Payload. Gespeichert werden nur die für die Abstimmung nötigen Referenzwerte und Beträge
-- (kleinste Einheit, bigint), nie der Rohpayload und nie Zahlungsmitteldaten (SEC-008). Unveränderlich: Anhängen und Lesen, kein UPDATE/DELETE.
SET search_path = taktovia, public;

CREATE TABLE payment_event (
  tenant_id           uuid        NOT NULL REFERENCES tenant(id),
  id                  uuid        NOT NULL,
  provider            text        NOT NULL CHECK (provider ~ '^[a-z0-9][a-z0-9_-]+$' AND char_length(provider) BETWEEN 2 AND 64),
  event_id            text        NOT NULL CHECK (char_length(event_id) BETWEEN 1 AND 128),
  payload_sha256      char(64)    NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
  event_type          text        NOT NULL CHECK (event_type ~ '^payment\.[a-z_.]+$' AND char_length(event_type) <= 64),
  -- Abbildung PSP-Ereignistyp -> interne Art (docs/umsetzungsplan/vertraege/psp-adapter.md); UNMAPPED: bekannter Typ ohne Zahlungswirkung, quittiert und gespeichert
  internal_kind       text        NOT NULL CHECK (internal_kind IN ('REQUIRES_ACTION', 'AUTHORIZED', 'AUTHORIZATION_FAILED', 'CAPTURE_PENDING', 'CAPTURED', 'VOID_PENDING', 'VOIDED',
                                                                   'REFUND_PENDING', 'PARTIALLY_REFUNDED', 'REFUNDED', 'DISPUTE_OPENED', 'DISPUTE_WON', 'DISPUTE_LOST', 'UNMAPPED')),
  provider_sequence   bigint      CHECK (provider_sequence >= 1),
  provider_created_at timestamptz NOT NULL,
  provider_ref        text        NOT NULL CHECK (char_length(provider_ref) BETWEEN 1 AND 128),   -- PSP-Referenz (payment_intent.provider_ref), Zuordnung in WP-M2-30
  reported_state      text        NOT NULL CHECK (reported_state IN ('CREATED', 'REQUIRES_ACTION', 'AUTHORIZED', 'CAPTURE_PENDING', 'CAPTURED', 'AUTHORIZATION_FAILED',
                                                                    'VOID_PENDING', 'VOIDED', 'REFUND_PENDING', 'PARTIALLY_REFUNDED', 'REFUNDED')),
  authorized_minor    bigint      CHECK (authorized_minor >= 0),
  captured_minor      bigint      CHECK (captured_minor >= 0),
  refunded_minor      bigint      CHECK (refunded_minor >= 0),
  currency            char(3)     CHECK (currency ~ '^[A-Z]{3}$'),
  dispute_state       text        CHECK (dispute_state IN ('OPEN', 'WON', 'LOST')),
  received_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  CONSTRAINT payment_event_provider_event UNIQUE (tenant_id, provider, event_id)
);
ALTER TABLE payment_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payment_event USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON payment_event FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE INDEX payment_event_by_ref ON payment_event (tenant_id, provider, provider_ref, received_at);
