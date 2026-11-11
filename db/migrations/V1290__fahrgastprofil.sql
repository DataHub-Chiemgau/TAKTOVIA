-- WP-M1-29 / LH-6.2-IAM-006: Fahrgastprofil und Präferenzen (OP-30, Entscheidung des Plans für den Bau: Feldliste von passenger_preference).
-- Passenger 1:n Präferenz. Arten: FAVORITE (benannter Ortsfavorit mit Haltepunkt), PREFERRED_STOP (bevorzugte Haltestelle),
-- NOTIFICATION_CHANNEL (bevorzugter Benachrichtigungskanal). Die Sprache liegt am Konto (passenger_private.locale).
-- Die Revision des Profils zählt passenger_private.revision (jede Änderung des Profils berührt die Kontozeile).
SET search_path = taktovia, public;

CREATE TABLE passenger_preference (
  tenant_id    uuid        NOT NULL REFERENCES tenant(id),
  id           uuid        NOT NULL,
  passenger_id uuid        NOT NULL,
  kind         text        NOT NULL CHECK (kind IN ('FAVORITE','PREFERRED_STOP','NOTIFICATION_CHANNEL')),
  position     integer     NOT NULL CHECK (position BETWEEN 0 AND 49),
  label        text        CHECK (label IS NULL OR length(label) BETWEEN 1 AND 80),
  stop_id      uuid,
  channel      text        CHECK (channel IS NULL OR channel IN ('PUSH','EMAIL','SMS')),
  created_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, passenger_id, kind, position),
  FOREIGN KEY (tenant_id, passenger_id) REFERENCES passenger_private (tenant_id, id),
  CONSTRAINT passenger_preference_shape CHECK (
    (kind = 'FAVORITE'             AND label IS NOT NULL AND stop_id IS NOT NULL AND channel IS NULL) OR
    (kind = 'PREFERRED_STOP'       AND label IS NULL     AND stop_id IS NOT NULL AND channel IS NULL) OR
    (kind = 'NOTIFICATION_CHANNEL' AND label IS NULL     AND stop_id IS NULL     AND channel IS NOT NULL))
);
CREATE UNIQUE INDEX passenger_preference_channel_once ON passenger_preference (tenant_id, passenger_id, channel) WHERE kind = 'NOTIFICATION_CHANNEL';
CREATE UNIQUE INDEX passenger_preference_stop_once ON passenger_preference (tenant_id, passenger_id, stop_id) WHERE kind = 'PREFERRED_STOP';

ALTER TABLE passenger_preference ENABLE ROW LEVEL SECURITY;
ALTER TABLE passenger_preference FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON passenger_preference USING (tenant_id=taktovia.current_tenant()) WITH CHECK (tenant_id=taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON passenger_preference FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change();

-- Haltestellenreferenz: stop_revision hat keinen Schlüssel auf stop_id allein (UNIQUE(tenant_id,stop_id,revision)), ein Fremdschlüssel ist deshalb nicht
-- möglich. Die Datenbank erzwingt die Existenz im Mandanten trotzdem beim Anlegen (SQLSTATE 23503). In M1 sind das Fixture-Haltepunkte; der gepflegte
-- Haltepunktstamm folgt mit M2 (WP-M2-05, OP-30).
CREATE FUNCTION passenger_preference_stop_exists() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.stop_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM taktovia.stop_revision s WHERE s.tenant_id = NEW.tenant_id AND s.stop_id = NEW.stop_id) THEN
    RAISE EXCEPTION 'unknown stop' USING ERRCODE = '23503';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER passenger_preference_stop_exists BEFORE INSERT OR UPDATE ON passenger_preference FOR EACH ROW EXECUTE FUNCTION passenger_preference_stop_exists();
