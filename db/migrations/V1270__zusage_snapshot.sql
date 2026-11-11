-- WP-M1-27 / LH-6.1-SYS-005: Baustein „gebundener Snapshot“ für verbindliche Zusagen, nachgewiesen an einem Testaggregat.
-- Muster für booking.promise_snapshot / booking.revision / journey_leg.promise_revision (Anwendung in M2): der Snapshot ist je Revision ein
-- eigener, unveränderlicher Datensatz (Trigger analog quote_immutable auf fare_quote); eine Änderung legt eine neue Revision an, die alte bleibt
-- lesbar (Kap. 04 §4.5 „Gegenereignis statt Änderung“). Der Kopf trägt nur die aktuelle Revision und darf nur um genau 1 wachsen; er zeigt per
-- aufgeschobenem Fremdschlüssel immer auf einen vorhandenen Snapshot. Die Hashprüfung (SHA-256 über kanonisches JSON) macht der Server (platform.promise);
-- die Datenbank erzwingt das Format.
SET search_path = taktovia, public;

CREATE TABLE promise_test_aggregate (
  tenant_id  uuid NOT NULL REFERENCES tenant(id),
  id         uuid NOT NULL,
  revision   bigint NOT NULL CHECK (revision >= 1),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, id)
);
CREATE TABLE promise_test_snapshot (
  tenant_id          uuid NOT NULL,
  promise_id         uuid NOT NULL,
  revision           bigint NOT NULL CHECK (revision >= 1),
  schema_version     text   NOT NULL CHECK (schema_version ~ '^[0-9]+\.[0-9]+$'),
  content            jsonb  NOT NULL CHECK (jsonb_typeof(content) = 'object'),
  content_hash       char(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
  config_revision_id uuid   NOT NULL,
  created_at         timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, promise_id, revision),
  FOREIGN KEY (tenant_id, promise_id) REFERENCES promise_test_aggregate(tenant_id, id),
  FOREIGN KEY (tenant_id, config_revision_id) REFERENCES config_revision(tenant_id, id)
);
ALTER TABLE promise_test_aggregate ADD CONSTRAINT promise_head_points_to_snapshot
  FOREIGN KEY (tenant_id, id, revision) REFERENCES promise_test_snapshot(tenant_id, promise_id, revision) DEFERRABLE INITIALLY DEFERRED;

CREATE TRIGGER promise_snapshot_immutable BEFORE UPDATE OR DELETE ON promise_test_snapshot FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE FUNCTION guard_promise_head() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'immutable record; append a correction' USING ERRCODE = '23514'; END IF;
  IF NEW.revision <> OLD.revision + 1 OR NEW.id <> OLD.id OR NEW.created_at <> OLD.created_at THEN
    RAISE EXCEPTION 'promise revision may only advance by one' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER promise_head_guard BEFORE UPDATE OR DELETE ON promise_test_aggregate FOR EACH ROW EXECUTE FUNCTION guard_promise_head();
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON promise_test_aggregate FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON promise_test_snapshot FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();

DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['promise_test_aggregate', 'promise_test_snapshot'] LOOP
    EXECUTE format('ALTER TABLE taktovia.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE taktovia.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON taktovia.%I USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant())', t);
  END LOOP;
END $$;
