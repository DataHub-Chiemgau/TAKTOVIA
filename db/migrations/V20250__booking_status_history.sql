-- WP-M2-25 / LH-6.4-BKG-013: unveränderliche Statushistorie der Buchung (Tabellenname Vorschlag `booking_status_history`, OP-22; `booking_change` bleibt der Änderungsannahme BKG-006 vorbehalten).
-- Jeder Zustandsübergang von `booking` schreibt in derselben Transaktion einen Eintrag mit Grund aus dem Katalog `booking_status_reason` (gemeinsam mit den Stornogründen aus WP-M2-26/-27).
-- Invariante, von der Datenbank beim Commit geprüft: Anzahl der Einträge = `booking.revision` und der Zustand der Buchung ist der des letzten Eintrags (gilt für Buchungen mit Buchungsnummer, also
-- alle über den Confirm-Befehl angelegten; Fixtures ohne Nummer sind ausgenommen).
SET search_path = taktovia, public;

CREATE TABLE booking_status_reason (
  code           text PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]{2,39}$'),
  kind           text NOT NULL CHECK (kind IN ('CONFIRMATION', 'PAYMENT', 'RECONCILIATION', 'COMPENSATION', 'CANCELLATION', 'EXECUTION')),
  passenger_text text NOT NULL CHECK (length(passenger_text) BETWEEN 3 AND 200)
);
INSERT INTO booking_status_reason(code, kind, passenger_text) VALUES
  ('HOLD_CONFIRMED',                  'CONFIRMATION',   'Ihre Buchung wurde angelegt.'),
  ('PAYMENT_AUTHORIZED',              'PAYMENT',        'Die Zahlung wurde bestätigt.'),
  ('FREE_OF_CHARGE',                  'PAYMENT',        'Die Fahrt ist kostenfrei.'),
  ('PAYMENT_UNDETERMINED',            'PAYMENT',        'Das Ergebnis der Zahlung wird geprüft.'),
  ('PAYMENT_DECLINED',                'PAYMENT',        'Die Zahlung wurde abgelehnt.'),
  ('HOLD_EXPIRED',                    'CONFIRMATION',   'Die Reservierung ist abgelaufen.'),
  ('HOLD_UNUSABLE',                   'CONFIRMATION',   'Die Reservierung konnte nicht gehalten werden.'),
  ('CONFIRMATION_FAILED',             'CONFIRMATION',   'Die Buchung konnte nicht bestätigt werden.'),
  ('RECONCILED_AUTHORIZED',           'RECONCILIATION', 'Die Zahlung wurde nach Prüfung bestätigt.'),
  ('RECONCILED_FAILED',               'RECONCILIATION', 'Die Zahlung ist nach Prüfung gescheitert.'),
  ('RECONCILED_NOTHING_TO_COMPENSATE','RECONCILIATION', 'Die Buchung wurde nach Prüfung beendet.'),
  ('COMPENSATION_DONE',               'COMPENSATION',   'Die Buchung wurde rückgängig gemacht.'),
  ('COMPENSATION_FAILED',             'COMPENSATION',   'Die Buchung konnte nicht vollständig rückgängig gemacht werden.'),
  ('PASSENGER_REQUEST',               'CANCELLATION',   'Sie haben die Buchung storniert.'),
  ('OPERATOR_CANCELLATION',           'CANCELLATION',   'Der Betreiber hat die Buchung storniert.'),
  ('NO_SHOW',                         'CANCELLATION',   'Die Fahrt wurde nicht angetreten.'),
  ('PAYMENT_FAILED',                  'CANCELLATION',   'Die Buchung wurde wegen einer fehlgeschlagenen Zahlung storniert.'),
  ('SAFETY',                          'CANCELLATION',   'Die Buchung wurde aus Sicherheitsgründen storniert.'),
  ('VEHICLE_OUTAGE',                  'CANCELLATION',   'Die Fahrt entfällt wegen eines Fahrzeugausfalls.'),
  ('CANCEL_COMPLETED',                'CANCELLATION',   'Die Stornierung ist abgeschlossen.'),
  ('CANCEL_UNDETERMINED',             'CANCELLATION',   'Die Wirkung der Stornierung wird geprüft.'),
  ('TRIP_COMPLETED',                  'EXECUTION',      'Die Fahrt wurde durchgeführt.');
-- Katalog: global, nicht personenbezogen, nur per Migration gepflegt (wie `state_transition_rule`); die Runtime-Rolle hat nur SELECT.
CREATE TRIGGER booking_status_reason_immutable BEFORE UPDATE OR DELETE ON booking_status_reason FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TABLE booking_status_history (
  tenant_id          uuid        NOT NULL REFERENCES tenant(id),
  id                 uuid        NOT NULL,
  booking_id         uuid        NOT NULL,
  from_state         text,
  to_state           text        NOT NULL,
  reason_code        text        NOT NULL REFERENCES booking_status_reason(code),
  actor_ref          text        NOT NULL CHECK (length(actor_ref) BETWEEN 1 AND 200),
  actor_kind         text        NOT NULL CHECK (actor_kind IN ('PASSENGER', 'OPERATOR', 'SYSTEM')),
  occurred_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  booking_revision   bigint      NOT NULL CHECK (booking_revision >= 1),
  correlation_id     uuid,
  attempt_id         uuid,
  offer_id           uuid,
  hold_id            uuid,
  quote_id           uuid,
  payment_intent_id  uuid,
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, booking_id, booking_revision),
  FOREIGN KEY (tenant_id, booking_id)        REFERENCES booking(tenant_id, id),
  FOREIGN KEY (tenant_id, attempt_id)        REFERENCES travel_attempt(tenant_id, id),
  FOREIGN KEY (tenant_id, offer_id)          REFERENCES ride_offer(tenant_id, id),
  FOREIGN KEY (tenant_id, hold_id)           REFERENCES capacity_hold(tenant_id, id),
  FOREIGN KEY (tenant_id, quote_id)          REFERENCES fare_quote(tenant_id, id),
  FOREIGN KEY (tenant_id, payment_intent_id) REFERENCES payment_intent(tenant_id, id),
  CHECK ((from_state IS NULL) = (booking_revision = 1))
);
CREATE INDEX booking_status_history_by_booking ON booking_status_history (tenant_id, booking_id, booking_revision);
ALTER TABLE booking_status_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE booking_status_history FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON booking_status_history USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON booking_status_history FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE TRIGGER booking_status_history_immutable BEFORE UPDATE OR DELETE ON booking_status_history FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- Ein Eintrag beschreibt einen laut Automat `booking` erlaubten Übergang (der erste Eintrag hat keinen Vorzustand).
CREATE FUNCTION booking_status_history_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.from_state IS NOT NULL AND NOT EXISTS (SELECT 1 FROM state_transition_rule WHERE machine = 'booking' AND from_state = NEW.from_state AND to_state = NEW.to_state) THEN
    RAISE EXCEPTION 'booking_status_history: transition not allowed' USING ERRCODE = '23514';
  END IF;
  IF NEW.from_state IS NULL AND NOT EXISTS (SELECT 1 FROM state_transition_rule WHERE machine = 'booking' AND (from_state = NEW.to_state OR to_state = NEW.to_state)) THEN
    RAISE EXCEPTION 'booking_status_history: unknown state' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER booking_status_history_guard BEFORE INSERT ON booking_status_history FOR EACH ROW EXECUTE FUNCTION booking_status_history_guard();

-- Beim Commit: Anzahl der Einträge = Revision und Zustand = Zustand des letzten Eintrags (kein Übergang ohne Historie, keine Historie ohne Übergang).
CREATE FUNCTION booking_history_complete() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE cur_revision bigint; cur_state text; n bigint; last_state text;
BEGIN
  -- Der aufgeschobene Trigger läuft je Änderung der Transaktion: geprüft wird der Endstand der Zeile beim Commit, nicht der Stand zum Zeitpunkt der einzelnen Änderung.
  SELECT revision, state INTO cur_revision, cur_state FROM booking WHERE tenant_id = NEW.tenant_id AND id = NEW.id;
  SELECT count(*) INTO n FROM booking_status_history WHERE tenant_id = NEW.tenant_id AND booking_id = NEW.id;
  SELECT to_state INTO last_state FROM booking_status_history WHERE tenant_id = NEW.tenant_id AND booking_id = NEW.id AND booking_revision = cur_revision;
  IF n <> cur_revision OR last_state IS DISTINCT FROM cur_state THEN
    RAISE EXCEPTION 'booking history incomplete: % entries for revision %, last state %', n, cur_revision, last_state USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER booking_history_complete AFTER INSERT OR UPDATE ON booking DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW WHEN (NEW.booking_no IS NOT NULL) EXECUTE FUNCTION booking_history_complete();
