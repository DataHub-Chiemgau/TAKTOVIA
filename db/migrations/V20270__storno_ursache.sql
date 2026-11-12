-- WP-M2-27 / LH-6.4-BKG-014: Stornoursache. Der Stornogrund und die Verursacherklasse (FAHRGAST | BETREIBER | FAHRZEUGAUSFALL) stehen als typisierte, nach dem Setzen unveränderliche Spalten an der
-- Buchung (gesetzt beim Übergang nach CANCEL_PENDING bzw. COMPENSATING, genau einmal); die Klasse folgt zwingend dem Grund (CHECK). Die Datenbasis für `Buchungsstornoquote Fahrgast` und
-- `Buchungsstornoquote Betreiber` (Fahrzeugausfall getrennt, kataloge.csv LH-K.1-Zeile-11/-12) sind diese Spalten zusammen mit der Statushistorie (Stornozeitpunkt, bestätigte Buchungen als Nenner);
-- die Konvention verbietet Sichten (V1-Regel), die Abfrage steht im Test `db/tests/test_cancel_reason_basis.py`. Die Berechnung der Quoten ist Sache von WP-M4-46.
-- Verursacherklassen (Vorschlag, D-05): Kundenstorno, No-Show und gescheiterte Zahlung des Fahrgasts = FAHRGAST; Betreiberstorno und Sicherheit = BETREIBER; Fahrzeugausfall = FAHRZEUGAUSFALL.
SET search_path = taktovia, public;

ALTER TABLE booking
  ADD COLUMN cancel_reason_code text REFERENCES booking_status_reason(code),
  ADD COLUMN cancel_cause_class text CHECK (cancel_cause_class IN ('FAHRGAST', 'BETREIBER', 'FAHRZEUGAUSFALL')),
  ADD CONSTRAINT booking_cancel_cause_follows_reason CHECK (
    (cancel_reason_code IS NULL AND cancel_cause_class IS NULL) OR
    (cancel_cause_class = CASE cancel_reason_code
       WHEN 'PASSENGER_REQUEST' THEN 'FAHRGAST' WHEN 'NO_SHOW' THEN 'FAHRGAST' WHEN 'PAYMENT_FAILED' THEN 'FAHRGAST'
       WHEN 'OPERATOR_CANCELLATION' THEN 'BETREIBER' WHEN 'SAFETY' THEN 'BETREIBER' WHEN 'VEHICLE_OUTAGE' THEN 'FAHRZEUGAUSFALL' ELSE '-' END));   -- ein Grund außerhalb der Stornogründe (z. B. HOLD_CONFIRMED) verletzt die Regel

-- Wächter: Zusage und Identität wie bisher unveränderlich; die Revision zählt nur +1 und nur mit einer Zustandsänderung weiter (Spalten ohne Zustandswechsel, z. B. der Stornogrund, lassen sie
-- unverändert); der Stornogrund wird einmal gesetzt, nur in einem Storno-/Kompensationszustand, und ist danach unveränderlich.
CREATE OR REPLACE FUNCTION booking_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.promise_snapshot IS DISTINCT FROM OLD.promise_snapshot THEN
    RAISE EXCEPTION 'booking.promise_snapshot is immutable; a changed promise is a new revision' USING ERRCODE = '23514';
  END IF;
  IF (NEW.hold_id, NEW.service_id, NEW.federation_ref, NEW.created_at) IS DISTINCT FROM (OLD.hold_id, OLD.service_id, OLD.federation_ref, OLD.created_at)
     OR (OLD.passenger_id IS NOT NULL AND NEW.passenger_id IS DISTINCT FROM OLD.passenger_id)
     OR (OLD.journey_id IS NOT NULL AND NEW.journey_id IS DISTINCT FROM OLD.journey_id)
     OR (OLD.booking_no IS NOT NULL AND NEW.booking_no IS DISTINCT FROM OLD.booking_no)
     OR (OLD.created_by IS NOT NULL AND NEW.created_by IS DISTINCT FROM OLD.created_by) THEN
    RAISE EXCEPTION 'booking identity is immutable' USING ERRCODE = '23514';
  END IF;
  IF OLD.booking_no IS NOT NULL AND NEW.revision <> OLD.revision AND NEW.revision <> OLD.revision + 1 THEN
    RAISE EXCEPTION 'booking.revision advances by exactly one per change' USING ERRCODE = '23514';
  END IF;
  IF OLD.booking_no IS NOT NULL AND NEW.state IS DISTINCT FROM OLD.state AND NEW.revision <> OLD.revision + 1 THEN
    RAISE EXCEPTION 'booking.state changes only together with the revision' USING ERRCODE = '23514';
  END IF;
  IF OLD.cancel_reason_code IS NOT NULL AND (NEW.cancel_reason_code IS DISTINCT FROM OLD.cancel_reason_code OR NEW.cancel_cause_class IS DISTINCT FROM OLD.cancel_cause_class) THEN
    RAISE EXCEPTION 'booking cancellation reason is immutable' USING ERRCODE = '23514';
  END IF;
  IF OLD.cancel_reason_code IS NULL AND NEW.cancel_reason_code IS NOT NULL AND NEW.state NOT IN ('CANCEL_PENDING', 'COMPENSATING', 'CANCELED', 'RECONCILING', 'FAILED') THEN
    RAISE EXCEPTION 'booking cancellation reason only in a cancellation state' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
