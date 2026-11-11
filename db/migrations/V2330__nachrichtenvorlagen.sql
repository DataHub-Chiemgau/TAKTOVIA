-- WP-M2-33 / LH-6.12-COM-002: Nachrichtenvorlagen je Sprache, Verkehrsprodukt (Dienst, OP 6), Ereignistyp und Kanal als unveränderliche Revisionen.
-- Änderung = neue Revision; die wirksame Revision ist die höchste, die zum Zeitpunkt gilt (valid_from <= t < valid_to). `notification.template_revision_id`
-- verweist auf die beim Versand verwendete Revision und bleibt unverändert (Trigger notification_guard, V2320).
SET search_path = taktovia, public;

CREATE TABLE channel_template_revision (
  tenant_id        uuid        NOT NULL REFERENCES tenant(id),
  id               uuid        NOT NULL,
  event_type       text        NOT NULL CHECK (event_type ~ '^[a-z][a-z_]*(\.[a-z][a-z_]*)+$'),
  language         text        NOT NULL CHECK (language ~ '^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),   -- BCP-47
  service_id       uuid,                                           -- NULL = Rückfallvorlage ohne Verkehrsprodukt
  channel          text        NOT NULL CHECK (channel IN ('PUSH', 'SMS', 'EMAIL')),
  revision         bigint      NOT NULL CHECK (revision >= 1),
  subject_template text        CHECK (subject_template IS NULL OR char_length(subject_template) BETWEEN 1 AND 200),
  body_template    text        NOT NULL CHECK (char_length(body_template) BETWEEN 1 AND 4000),
  valid_from       timestamptz NOT NULL,
  valid_to         timestamptz,
  created_by       text        NOT NULL,
  created_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  -- service_id ist nullbar: NULL zählt als gleich, sonst wären Dubletten ohne Verkehrsprodukt möglich
  CONSTRAINT channel_template_revision_key UNIQUE NULLS NOT DISTINCT (tenant_id, event_type, language, service_id, channel, revision),
  FOREIGN KEY (tenant_id, service_id) REFERENCES service (tenant_id, id),
  CONSTRAINT channel_template_valid_interval CHECK (valid_to IS NULL OR valid_to > valid_from),
  -- Aktivierungsregel (Abnahme 3), zweite Verteidigungslinie neben der Dienstprüfung (422): buchungsbezogene Ereignistypen brauchen den Pflichtplatzhalter
  -- „Buchungsreferenz“. Die Liste entspricht dem Platzhalterkatalog des Moduls (PlaceholderCatalog); ein Test hält beide synchron.
  CONSTRAINT channel_template_booking_reference CHECK (
    event_type NOT IN ('booking.confirmed', 'eta.changed', 'vehicle.arrived', 'booking.cancelled_by_passenger', 'booking.cancelled_by_operator')
    OR body_template ~ '\{\{\s*booking_reference\s*\}\}')
);
CREATE INDEX channel_template_lookup ON channel_template_revision (tenant_id, event_type, channel, language, revision DESC);

CREATE FUNCTION channel_template_revision_immutable() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  RAISE EXCEPTION 'channel template revisions are immutable (new revision instead)' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER immutable BEFORE UPDATE OR DELETE ON channel_template_revision FOR EACH ROW EXECUTE FUNCTION channel_template_revision_immutable();

ALTER TABLE channel_template_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE channel_template_revision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON channel_template_revision USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());

ALTER TABLE notification ADD CONSTRAINT notification_template_revision_fk
  FOREIGN KEY (tenant_id, template_revision_id) REFERENCES channel_template_revision (tenant_id, id);
