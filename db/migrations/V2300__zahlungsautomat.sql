-- WP-M2-30 / LH-6.10-PAY-004: Zahlungsautomat unabhängig vom Buchungsstatus. `payment_intent` (V1) bekommt die Spalten des Automaten, die Härtung der Zustandsspalte
-- (CHECK der zulässigen Werte plus Übergangsregeln des Automaten `payment`, V1170) und Wächter für Geld und Identität. Zahlstatus liegt ausschließlich in `payment_intent.state`,
-- Buchungsstatus in `booking.state`; `booking_id` ist ein Verweis, kein gemeinsames Zustandsfeld. `payment_operation` verknüpft die Zahlung mit den `operation`-Zeilen
-- (Lease, Fencing, Abgleich) und hält die stabile PSP-Kommandoreferenz je Aufruf (`PspCommandRef`), damit jeder Wiederholungsversuch denselben Idempotenzschlüssel benutzt.
-- Entscheidung zu OP-5 (Planungsannahme, D-03 offen): Zahlung `AUTHORIZED` ist Voraussetzung für `CONFIRMED`; der Einzug (Capture) wird nach der Bestätigung angestoßen.
-- Zeitpunkt der Autorisierung gegenüber der Journey (OP-3): `payment_intent.journey_id` ist Pflicht; die Zahlung beginnt für eine Buchung im Zustand `CONFIRMING`, deren Journey-Hülle
-- bei der Bestätigung des Holds entstanden ist.
SET search_path = taktovia, public;

ALTER TABLE payment_intent
  ALTER COLUMN provider_ref DROP NOT NULL,                  -- erst nach der PSP-Antwort bekannt; bei unbestimmter Autorisierung bleibt sie leer, der Abgleich läuft über `merchant_ref`
  ADD COLUMN booking_id         uuid,
  ADD COLUMN service_id         uuid,                                         -- Dienst der Buchung (Objektberechtigung und Kopplung ohne Lesen fremder Tabellen)
  ADD COLUMN coupled_at         timestamptz,                                  -- Zeitpunkt, zu dem die Kopplung an die Buchung (Bestätigung bzw. Storno) vollzogen wurde
  ADD COLUMN quote_id           uuid,
  ADD COLUMN requested_minor    bigint      CHECK (requested_minor >= 1),   -- Soll: exakt `fare_quote.pay_at_platform_minor`
  ADD COLUMN merchant_ref       text        CHECK (char_length(merchant_ref) BETWEEN 8 AND 128),
  ADD COLUMN psp_payment_id     text        CHECK (char_length(psp_payment_id) BETWEEN 1 AND 128),
  ADD COLUMN payment_method_ref text        CHECK (char_length(payment_method_ref) BETWEEN 1 AND 128),   -- vom PSP gelieferter Verweis, nie Kartendaten (SEC-008)
  ADD COLUMN authorize_by       timestamptz,                                  -- Frist (Hold-Ablauf): später eintreffende Autorisierung wird aufgehoben
  ADD COLUMN failure_code       text        CHECK (failure_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
  ADD COLUMN created_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
  ADD COLUMN updated_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
  ADD CONSTRAINT payment_intent_booking_fk FOREIGN KEY (tenant_id, booking_id) REFERENCES booking (tenant_id, id),
  ADD CONSTRAINT payment_intent_quote_fk   FOREIGN KEY (tenant_id, quote_id)   REFERENCES fare_quote (tenant_id, id),
  ADD CONSTRAINT payment_intent_service_fk FOREIGN KEY (tenant_id, service_id) REFERENCES service (tenant_id, id),
  ADD CONSTRAINT payment_intent_ref_present CHECK (provider_ref IS NOT NULL OR state IN ('CREATED', 'AUTHORIZATION_FAILED')),
  ADD CONSTRAINT payment_intent_merchant_ref_unique UNIQUE (tenant_id, provider, merchant_ref);

-- Höchstens eine wirksame Zahlung je Buchung (kein zweiter Einzug); nach `AUTHORIZATION_FAILED` bzw. `VOIDED` ist ein anderer Zahlungsweg (neue Zahlung) möglich.
CREATE UNIQUE INDEX payment_intent_one_active_per_booking ON payment_intent (tenant_id, booking_id) WHERE booking_id IS NOT NULL AND state NOT IN ('AUTHORIZATION_FAILED', 'VOIDED');
CREATE INDEX payment_intent_by_booking ON payment_intent (tenant_id, booking_id) WHERE booking_id IS NOT NULL;
CREATE INDEX payment_intent_by_provider_ref ON payment_intent (tenant_id, provider, provider_ref) WHERE provider_ref IS NOT NULL;

-- CHECK der zulässigen Zustandswerte und Übergangstrigger (Automat `payment`, V1170).
SELECT harden_state_column('taktovia.payment_intent', 'state', 'payment');

-- Wächter: Identität unveränderlich, Geld nur in den dafür vorgesehenen Zuständen und nie rückwärts, Zustandsübergänge nur mit passendem Betrag.
CREATE FUNCTION payment_intent_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.journey_id IS DISTINCT FROM OLD.journey_id OR NEW.provider IS DISTINCT FROM OLD.provider OR NEW.currency IS DISTINCT FROM OLD.currency
     OR (OLD.booking_id IS NOT NULL AND NEW.booking_id IS DISTINCT FROM OLD.booking_id)
     OR (OLD.quote_id IS NOT NULL AND NEW.quote_id IS DISTINCT FROM OLD.quote_id)
     OR (OLD.service_id IS NOT NULL AND NEW.service_id IS DISTINCT FROM OLD.service_id)
     OR (OLD.coupled_at IS NOT NULL AND NEW.coupled_at IS DISTINCT FROM OLD.coupled_at)
     OR (OLD.requested_minor IS NOT NULL AND NEW.requested_minor IS DISTINCT FROM OLD.requested_minor)
     OR (OLD.merchant_ref IS NOT NULL AND NEW.merchant_ref IS DISTINCT FROM OLD.merchant_ref)
     OR (OLD.payment_method_ref IS NOT NULL AND NEW.payment_method_ref IS DISTINCT FROM OLD.payment_method_ref)
     OR (OLD.provider_ref IS NOT NULL AND NEW.provider_ref IS DISTINCT FROM OLD.provider_ref)
     OR (OLD.psp_payment_id IS NOT NULL AND NEW.psp_payment_id IS DISTINCT FROM OLD.psp_payment_id) THEN
    RAISE EXCEPTION 'payment_intent identity is immutable' USING ERRCODE = '23514';
  END IF;
  IF NEW.authorized_minor IS DISTINCT FROM OLD.authorized_minor AND OLD.state NOT IN ('CREATED', 'REQUIRES_ACTION') THEN
    RAISE EXCEPTION 'payment_intent.authorized_minor is fixed after the authorization' USING ERRCODE = '23514';
  END IF;
  IF NEW.captured_minor < OLD.captured_minor OR NEW.refunded_minor < OLD.refunded_minor THEN
    RAISE EXCEPTION 'payment_intent amounts never decrease (correction only by a counter event)' USING ERRCODE = '23514';
  END IF;
  IF NEW.captured_minor > OLD.captured_minor AND OLD.state NOT IN ('CAPTURE_PENDING', 'CAPTURE_UNKNOWN', 'RECONCILING') THEN
    RAISE EXCEPTION 'payment_intent.captured_minor changes only while a capture is open' USING ERRCODE = '23514';
  END IF;
  IF NEW.refunded_minor > OLD.refunded_minor AND OLD.state <> 'REFUND_PENDING' THEN
    RAISE EXCEPTION 'payment_intent.refunded_minor changes only while a refund is pending' USING ERRCODE = '23514';
  END IF;
  IF NEW.state IS DISTINCT FROM OLD.state THEN
    IF NEW.state = 'AUTHORIZED' AND OLD.state IN ('CREATED', 'REQUIRES_ACTION') AND NEW.authorized_minor < 1 THEN
      RAISE EXCEPTION 'AUTHORIZED requires an authorized amount' USING ERRCODE = '23514';
    END IF;
    IF NEW.state = 'CAPTURED' AND NEW.captured_minor < 1 THEN
      RAISE EXCEPTION 'CAPTURED requires a captured amount' USING ERRCODE = '23514';
    END IF;
    IF NEW.state = 'REFUNDED' AND NEW.refunded_minor <> NEW.captured_minor THEN
      RAISE EXCEPTION 'REFUNDED requires refunded = captured' USING ERRCODE = '23514';
    END IF;
    IF NEW.state = 'PARTIALLY_REFUNDED' AND NOT (NEW.refunded_minor >= 1 AND NEW.refunded_minor < NEW.captured_minor) THEN
      RAISE EXCEPTION 'PARTIALLY_REFUNDED requires 0 < refunded < captured' USING ERRCODE = '23514';
    END IF;
  END IF;
  NEW.updated_at := clock_timestamp();
  RETURN NEW;
END $$;
CREATE TRIGGER payment_intent_guard BEFORE UPDATE ON payment_intent FOR EACH ROW EXECUTE FUNCTION payment_intent_guard();

-- Zahlungsoperation: Verknüpfung Zahlung <-> `operation` (Lease/Fencing/Abgleich liegen dort) mit stabiler Kommandoreferenz je PSP-Aufruf.
CREATE TABLE payment_operation (
  tenant_id     uuid        NOT NULL REFERENCES tenant(id),
  operation_id  uuid        NOT NULL,
  payment_id    uuid        NOT NULL,
  action        text        NOT NULL CHECK (action IN ('AUTHORIZE', 'CAPTURE', 'VOID', 'REFUND', 'RECONCILE')),
  seq           integer     NOT NULL CHECK (seq >= 1),
  amount_minor  bigint      CHECK (amount_minor >= 1),
  command_ref   text        NOT NULL CHECK (command_ref ~ '^[A-Za-z0-9._:-]{8,128}$'),
  created_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, operation_id),
  FOREIGN KEY (tenant_id, operation_id) REFERENCES operation (tenant_id, id),
  FOREIGN KEY (tenant_id, payment_id)   REFERENCES payment_intent (tenant_id, id),
  CONSTRAINT payment_operation_seq UNIQUE (tenant_id, payment_id, action, seq),
  CONSTRAINT payment_operation_command_ref UNIQUE (tenant_id, command_ref)
);
ALTER TABLE payment_operation ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment_operation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payment_operation USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON payment_operation FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE INDEX payment_operation_by_payment ON payment_operation (tenant_id, payment_id, action, seq);

-- Zahlungsoperationen sind nach dem Anlegen unveränderlich (die Ausführung ändert nur `operation`).
CREATE TRIGGER payment_operation_immutable BEFORE UPDATE OR DELETE ON payment_operation FOR EACH ROW EXECUTE FUNCTION reject_mutation();
