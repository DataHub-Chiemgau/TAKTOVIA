-- WP-M2-03: Dienstversion mit Betriebszeiten (Wochenschema, Ausnahmetage), Verkehrsproduktkennung und Wirksamkeit.
-- Der Dienstkopf `service` (V1, V1090) bleibt unverändert; jede Änderung der Betriebszeiten ist eine neue, unveränderliche Zeile (Revision + 1) der Kette
-- je Dienst. Wirksam zum Zeitpunkt t ist die Version mit `valid_from <= t < valid_to` (links geschlossen, rechts offen; `valid_to` leer = unbegrenzt).
-- Invariante „nicht überlappende aktive Dienstversion“ (Kap. 04 §4.5): Exclusion Constraint (btree_gist aus V1340), zusätzlich die Scope-Sperre der Anwendung
-- (Zeilensperre auf `service` vor dem Anlegen). Die Zeitzone wird zum Veröffentlichungszeitpunkt aus `service.timezone` übernommen und mit der Version
-- festgehalten (Betriebszeiten sind Ortszeiten dieser Zone; DST-Regel siehe OperatingSchedule). Eine Version ohne Betriebszeit gibt es nicht: ein Dienst ohne
-- wirksame Version gilt als „außer Betrieb“ (kein stiller 24-h-Betrieb).
SET search_path = taktovia, public;

CREATE TABLE service_revision (
  tenant_id       uuid        NOT NULL REFERENCES tenant(id),
  id              uuid        NOT NULL,
  service_id      uuid        NOT NULL,
  revision        bigint      NOT NULL CHECK (revision >= 1),
  traffic_product text        NOT NULL CHECK (traffic_product ~ '^[A-Z][A-Z0-9_]{1,39}$'),
  timezone        text        NOT NULL CHECK (timezone ~ '^(UTC|[A-Z][A-Za-z_-]*(/[A-Za-z0-9_+-]+)+)$' AND timezone NOT LIKE 'Etc/%' AND (now() AT TIME ZONE timezone) IS NOT NULL),
  weekly_hours    jsonb       NOT NULL CHECK (jsonb_typeof(weekly_hours) = 'array'),
  exceptions      jsonb       NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(exceptions) = 'array'),
  valid_from      timestamptz NOT NULL,
  valid_to        timestamptz,
  reason          text        NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
  created_by      text        NOT NULL CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, service_id, revision),
  FOREIGN KEY (tenant_id, service_id) REFERENCES service(tenant_id, id),
  CHECK (valid_to IS NULL OR valid_to > valid_from),
  CONSTRAINT service_revision_no_overlap EXCLUDE USING gist (tenant_id WITH =, service_id WITH =, tstzrange(valid_from, valid_to) WITH &&)
);
ALTER TABLE service_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE service_revision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON service_revision USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON service_revision FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE INDEX service_revision_by_service ON service_revision (tenant_id, service_id, revision DESC);

-- Unveränderlichkeit: nie löschen; eine offene Version darf nur geschlossen werden (valid_to setzen), eine geschlossene bleibt unverändert.
CREATE FUNCTION service_revision_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'service revision is append-only' USING ERRCODE = '23514';
  END IF;
  IF OLD.valid_to IS NOT NULL OR NEW.valid_to IS NULL
     OR NEW.id IS DISTINCT FROM OLD.id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.service_id IS DISTINCT FROM OLD.service_id
     OR NEW.revision IS DISTINCT FROM OLD.revision OR NEW.traffic_product IS DISTINCT FROM OLD.traffic_product OR NEW.timezone IS DISTINCT FROM OLD.timezone
     OR NEW.weekly_hours IS DISTINCT FROM OLD.weekly_hours OR NEW.exceptions IS DISTINCT FROM OLD.exceptions OR NEW.valid_from IS DISTINCT FROM OLD.valid_from
     OR NEW.reason IS DISTINCT FROM OLD.reason OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'closed service revision is immutable; only an open version may be closed' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER service_revision_guard BEFORE UPDATE OR DELETE ON service_revision FOR EACH ROW EXECUTE FUNCTION service_revision_guard();
