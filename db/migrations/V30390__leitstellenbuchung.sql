-- WP-M3-39 / LH-6.7-OPS-004: Buchung durch die Disposition anlegen, ändern, stornieren.
--   * operator_offer_subject: Bindung eines von der Leitstelle ausgestellten Angebots an den Fahrgast, für den gebucht wird (`PlanRequest.booking_subject = DELEGATED`, Kap. 05 §5.9, Mindestumfang
--     weil M2 DELEGATED nicht liefert, OP-30). Append-only; ein Subjektwechsel ist ein neues Angebot, nie ein UPDATE.
--   * booking_change erhält additiv Pflichtgrund-Code, alte und neue Zusage, die Folgebuchung des atomaren Tauschs und den Nachweis der Fahrgastzustimmung; neue Art `CREATE` (Anlage durch die
--     Disposition). Die Tabelle bleibt append-only (Trigger aus V3020).
SET search_path = taktovia, public;

CREATE TABLE operator_offer_subject (
  tenant_id    uuid        NOT NULL REFERENCES tenant(id),
  offer_id     uuid        NOT NULL,
  passenger_id uuid        NOT NULL,
  actor_ref    text        NOT NULL CHECK (actor_ref ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  basis        text        NOT NULL DEFAULT 'DISPATCH' CHECK (basis IN ('DISPATCH')),
  created_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, offer_id),
  FOREIGN KEY (tenant_id, offer_id)     REFERENCES ride_offer(tenant_id, id),
  FOREIGN KEY (tenant_id, passenger_id) REFERENCES passenger_private(tenant_id, id)
);
ALTER TABLE operator_offer_subject ENABLE ROW LEVEL SECURITY;
ALTER TABLE operator_offer_subject FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON operator_offer_subject USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON operator_offer_subject FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE TRIGGER operator_offer_subject_immutable BEFORE UPDATE OR DELETE ON operator_offer_subject FOR EACH ROW EXECUTE FUNCTION reject_mutation();

ALTER TABLE booking_change DROP CONSTRAINT booking_change_change_kind_check;
ALTER TABLE booking_change
  ADD CONSTRAINT booking_change_kind_check CHECK (change_kind IN ('CREATE', 'MODIFY', 'CANCEL', 'REPLACE_OFFER')),
  ADD COLUMN reason_code    text  CHECK (reason_code IS NULL OR reason_code ~ '^[A-Z][A-Z0-9_]{1,59}$'),
  ADD COLUMN old_promise    jsonb CHECK (old_promise IS NULL OR jsonb_typeof(old_promise) = 'object'),
  ADD COLUMN new_promise    jsonb CHECK (new_promise IS NULL OR jsonb_typeof(new_promise) = 'object'),
  ADD COLUMN new_booking_id uuid,
  ADD COLUMN consent        jsonb CHECK (consent IS NULL OR jsonb_typeof(consent) = 'object'),
  ADD CONSTRAINT booking_change_new_booking_fk FOREIGN KEY (tenant_id, new_booking_id) REFERENCES booking(tenant_id, id),
  -- Änderungen der Disposition haben immer einen Grundcode (Anlage, Änderung, Storno); Altzeilen ohne Code bleiben gültig
  ADD CONSTRAINT booking_change_kind_new_booking CHECK ((change_kind = 'REPLACE_OFFER') = (new_booking_id IS NOT NULL) OR change_kind <> 'REPLACE_OFFER');
-- eine Folgebuchung gehört zu genau einer Änderung (Wiederholung desselben Tauschs erzeugt keine zweite Zeile)
CREATE UNIQUE INDEX booking_change_by_new_booking ON booking_change (tenant_id, new_booking_id) WHERE new_booking_id IS NOT NULL;
