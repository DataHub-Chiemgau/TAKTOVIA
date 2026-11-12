-- WP-M2-05 / LH-6.9-GEO-002: Haltepunktrevisionen mit Aktivstatus und zulässigen Verkehrsprodukten.
-- V1 kennt je Zeile Koordinate, `stop_id`, Revision, Barrierefreiheit und Gültigkeit, aber weder Aktivstatus noch Verkehrsprodukte (Offene Punkte OP-6, OP-7).
-- Entscheidung für den Bau (Annahme, vom Auftraggeber nicht freigegeben):
--  * `active`: Aktivstatus **dieser** Revision. Eine Deaktivierung ist eine neue Revision (active = false), nie die Änderung einer vorhandenen Zeile.
--  * `traffic_products`: zulässige Verkehrsprodukte als Array von Kennungen (dieselbe Form wie `service_revision.traffic_product`, WP-M2-03), 1 bis 10 Einträge. Ein Array an
--    der Revision statt einer Zuordnungstabelle, damit „mindestens ein Verkehrsprodukt je Haltestelle“ per CHECK in der Datenbank gilt und die Zuordnung mit der Revision
--    unveränderlich ist. Altbestand und Seed-Zeilen ohne Angabe gelten für alle drei bekannten Verkehrsprodukte (Standardwert).
--  * Wirksam: `valid_from <= t < valid_to` (links geschlossen, rechts offen); höchstens eine wirksame Revision je `stop_id` und Zeitpunkt (Exclusion Constraint).
--  * Koordinate im Wertebereich WGS84 (`geometry(Point,4326)` erzwingt ihn nicht): CHECK. `accessibility` ist ein JSON-Objekt; das Schema `stop-accessibility.v1` prüft der Dienst vor
--    der Persistenz (die Spalte nimmt wegen Altbestand auch `{}` an).
--  * Unveränderlichkeit: eine Revision wird nie überschrieben oder gelöscht; nur das Ende einer offenen Revision darf gesetzt werden (Nachfolger oder Deaktivierung schließt sie).
-- Das Runtime-Recht UPDATE aus V1 bleibt tabellenweit (Rollenmatrix 00_kern); die Spaltenbeschränkung leistet der Trigger.
SET search_path = taktovia, public;

ALTER TABLE stop_revision
  ADD COLUMN active           boolean     NOT NULL DEFAULT true,
  ADD COLUMN traffic_products text[]      NOT NULL DEFAULT ARRAY['AREA_ON_DEMAND', 'LINE_ON_DEMAND', 'STATION_FEEDER'],
  ADD COLUMN created_by       text        NOT NULL DEFAULT 'system:unattributed'
    CONSTRAINT stop_revision_created_by_format CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  ADD COLUMN created_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  ADD CONSTRAINT stop_revision_revision_positive CHECK (revision >= 1),
  ADD CONSTRAINT stop_revision_point_range CHECK (ST_X(point) BETWEEN -180 AND 180 AND ST_Y(point) BETWEEN -90 AND 90),
  ADD CONSTRAINT stop_revision_products CHECK (cardinality(traffic_products) BETWEEN 1 AND 10 AND array_position(traffic_products, NULL) IS NULL
    AND array_to_string(traffic_products, ',') ~ '^[A-Z][A-Z0-9_]{1,39}(,[A-Z][A-Z0-9_]{1,39})*$'),
  ADD CONSTRAINT stop_revision_accessibility_object CHECK (jsonb_typeof(accessibility) = 'object'),
  ADD CONSTRAINT stop_revision_name_length CHECK (length(name) BETWEEN 1 AND 200),
  ADD CONSTRAINT stop_revision_approved_by_present CHECK (length(approved_by) BETWEEN 1 AND 200),
  ADD CONSTRAINT stop_revision_no_overlap EXCLUDE USING gist (tenant_id WITH =, stop_id WITH =, tstzrange(valid_from, valid_to) WITH &&);
CREATE INDEX stop_revision_by_stop ON stop_revision (tenant_id, stop_id, revision DESC);
CREATE INDEX stop_revision_by_created ON stop_revision (tenant_id, created_at, id);

CREATE FUNCTION stop_revision_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'stop revision is append-only' USING ERRCODE = '23514';
  END IF;
  IF OLD.valid_to IS NOT NULL OR NEW.valid_to IS NULL
     OR NEW.id IS DISTINCT FROM OLD.id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.stop_id IS DISTINCT FROM OLD.stop_id
     OR NEW.revision IS DISTINCT FROM OLD.revision OR NEW.point::bytea IS DISTINCT FROM OLD.point::bytea OR NEW.name IS DISTINCT FROM OLD.name
     OR NEW.accessibility IS DISTINCT FROM OLD.accessibility OR NEW.approved_by IS DISTINCT FROM OLD.approved_by OR NEW.valid_from IS DISTINCT FROM OLD.valid_from
     OR NEW.active IS DISTINCT FROM OLD.active OR NEW.traffic_products IS DISTINCT FROM OLD.traffic_products
     OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'stop revision is immutable; only an open revision may be closed' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER stop_revision_guard BEFORE UPDATE OR DELETE ON stop_revision FOR EACH ROW EXECUTE FUNCTION stop_revision_guard();
