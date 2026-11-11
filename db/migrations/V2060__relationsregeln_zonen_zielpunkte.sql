-- WP-M2-06 / LH-6.9-GEO-003: Start-/Zielzulässigkeit nach getrennten Zonen und erlaubten Zielpunkten.
-- Entscheidung für den Bau (Planungsannahme PA-04, Offener Punkt OP-10; vom Auftraggeber nicht freigegeben):
--  * Zonen sind `service_area`-Revisionen mit Zonenrolle `ORIGIN` (nur Start), `DESTINATION` (nur Ziel) oder `BOTH` (Standard, wie GEO-001 ohne Rolle).
--    Je Dienst und Zeitpunkt gibt es höchstens eine wirksame Startzone und höchstens eine wirksame Zielzone: zwei Exclusion Constraints über `[valid_from, valid_to)`,
--    jeweils nur für Zeilen, die die Rolle bedienen (`BOTH` zählt für beide). Sie lösen das Überlappungsverbot „je Dienst“ aus V2040 ab.
--  * Erlaubte Zielpunkte sind je Dienst eine versionierte Menge freigegebener Haltepunkte (`stop_revision.stop_id`) in `relation_rule`: eine Zeile = eine Version der ganzen
--    Menge (`destination_stop_ids`), damit eine Prüfung immer vollständig die alte oder die neue Menge sieht. Leere Menge oder keine wirksame Version = keine Einschränkung
--    durch Zielpunkte. Ein Fremdschlüssel auf `stop_id` ist nicht möglich (nicht eindeutig); ein Trigger prüft beim Anlegen, dass jede Kennung ein Haltepunkt des Mandanten ist.
--  * Wirksamkeit überall links geschlossen, rechts offen; höchstens eine wirksame Mengenversion je Dienst (Exclusion Constraint). Versionen sind unveränderlich, nur das Ende
--    einer offenen Version darf gesetzt werden.
SET search_path = taktovia, public;

ALTER TABLE service_area
  ADD COLUMN zone_role text NOT NULL DEFAULT 'BOTH' CONSTRAINT service_area_zone_role CHECK (zone_role IN ('ORIGIN', 'DESTINATION', 'BOTH'));
ALTER TABLE service_area DROP CONSTRAINT service_area_no_overlap;
ALTER TABLE service_area
  ADD CONSTRAINT service_area_origin_no_overlap EXCLUDE USING gist (tenant_id WITH =, service_id WITH =, tstzrange(valid_from, valid_to) WITH &&) WHERE (zone_role IN ('ORIGIN', 'BOTH')),
  ADD CONSTRAINT service_area_destination_no_overlap EXCLUDE USING gist (tenant_id WITH =, service_id WITH =, tstzrange(valid_from, valid_to) WITH &&) WHERE (zone_role IN ('DESTINATION', 'BOTH'));

-- Die Zonenrolle gehört zur Revision und ist wie die Geometrie unveränderlich.
CREATE OR REPLACE FUNCTION service_area_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'service area revision is append-only' USING ERRCODE = '23514';
  END IF;
  IF OLD.valid_to IS NOT NULL OR NEW.valid_to IS NULL
     OR NEW.id IS DISTINCT FROM OLD.id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.service_id IS DISTINCT FROM OLD.service_id
     OR COALESCE(NEW.area_key, NEW.id) IS DISTINCT FROM COALESCE(OLD.area_key, OLD.id) OR NEW.revision IS DISTINCT FROM OLD.revision OR NEW.area::bytea IS DISTINCT FROM OLD.area::bytea
     OR NEW.zone_role IS DISTINCT FROM OLD.zone_role
     OR NEW.valid_from IS DISTINCT FROM OLD.valid_from OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'service area revision is immutable; only an open revision may be closed' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TABLE relation_rule (
  tenant_id            uuid        NOT NULL REFERENCES tenant(id),
  id                   uuid        NOT NULL,
  service_id           uuid        NOT NULL,
  revision             bigint      NOT NULL CHECK (revision >= 1),
  destination_stop_ids uuid[]      NOT NULL DEFAULT '{}' CHECK (cardinality(destination_stop_ids) <= 1000 AND array_position(destination_stop_ids, NULL) IS NULL),
  valid_from           timestamptz NOT NULL,
  valid_to             timestamptz,
  reason               text        NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
  created_by           text        NOT NULL CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, service_id, revision),
  FOREIGN KEY (tenant_id, service_id) REFERENCES service(tenant_id, id),
  CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT relation_rule_no_overlap EXCLUDE USING gist (tenant_id WITH =, service_id WITH =, tstzrange(valid_from, valid_to) WITH &&)
);
ALTER TABLE relation_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE relation_rule FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON relation_rule USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON relation_rule FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE INDEX relation_rule_by_service ON relation_rule (tenant_id, service_id, revision DESC);

-- Jeder Zielpunkt ist ein Haltepunkt des eigenen Mandanten (stop_id ist in stop_revision nicht eindeutig, daher kein Fremdschlüssel); fremde Kennungen sind wie unbekannte.
CREATE FUNCTION relation_rule_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE missing uuid;
BEGIN
  IF TG_OP = 'INSERT' THEN
    SELECT x INTO missing FROM unnest(NEW.destination_stop_ids) AS x
     WHERE NOT EXISTS (SELECT 1 FROM stop_revision s WHERE s.tenant_id = NEW.tenant_id AND s.stop_id = x) LIMIT 1;
    IF missing IS NOT NULL THEN
      RAISE EXCEPTION 'relation rule references an unknown stop' USING ERRCODE = '23503';
    END IF;
    RETURN NEW;
  END IF;
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'relation rule is append-only' USING ERRCODE = '23514';
  END IF;
  IF OLD.valid_to IS NOT NULL OR NEW.valid_to IS NULL
     OR NEW.id IS DISTINCT FROM OLD.id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.service_id IS DISTINCT FROM OLD.service_id
     OR NEW.revision IS DISTINCT FROM OLD.revision OR NEW.destination_stop_ids IS DISTINCT FROM OLD.destination_stop_ids OR NEW.valid_from IS DISTINCT FROM OLD.valid_from
     OR NEW.reason IS DISTINCT FROM OLD.reason OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'closed relation rule is immutable; only an open version may be closed' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER relation_rule_guard BEFORE INSERT OR UPDATE OR DELETE ON relation_rule FOR EACH ROW EXECUTE FUNCTION relation_rule_guard();
