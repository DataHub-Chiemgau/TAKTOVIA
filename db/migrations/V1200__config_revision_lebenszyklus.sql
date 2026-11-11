-- WP-M1-20 / LH-6.1-SYS-002, LH-I.7-CFG-003: Konfigurationsrevisionen mit Lebenszyklus, Pflichtangaben und Unveränderlichkeit.
-- Ergänzt die Kern-DDL (OP-18, Entscheidungen für den Bau): Status, Änderungsgrund, Zeitpunkt der Änderung, Zeilenversion für `If-Match`,
-- zulässige scope_kind-Werte (so wie das Schema ConfigRevision der API sie nennt: TENANT, REGION, DEPOT, SERVICE; VEHICLE folgt mit WP-M1-21),
-- Unveränderlichkeit publizierter Zeilen, Prüfung der Werte gegen policy_schema, Referenz auf die Schema-Version.
-- Altwert/Neuwert werden nicht gespeichert, sondern als Diff gegen die Vorgängerrevision berechnet. `revision` ist die Nummer je Scope und
-- bleibt von der Zeilenversion `row_version` (ETag) getrennt.
SET search_path = taktovia, public;

ALTER TABLE config_revision
  ADD COLUMN state       text        NOT NULL DEFAULT 'DRAFT',
  ADD COLUMN reason      text        NOT NULL DEFAULT 'Übernahme aus Altbestand',
  ADD COLUMN created_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  ADD COLUMN row_version bigint      NOT NULL DEFAULT 1 CHECK (row_version >= 1);
ALTER TABLE config_revision ALTER COLUMN reason DROP DEFAULT;

ALTER TABLE config_revision
  ADD CONSTRAINT config_revision_scope_kind_valid CHECK (scope_kind IN ('TENANT', 'REGION', 'DEPOT', 'SERVICE')),
  ADD CONSTRAINT config_revision_reason_valid CHECK (length(btrim(reason)) BETWEEN 3 AND 1000),
  ADD CONSTRAINT config_revision_created_by_valid CHECK (length(created_by) BETWEEN 3 AND 200),
  ADD CONSTRAINT config_revision_values_object CHECK (jsonb_typeof(values_json) = 'object'),
  -- Publikation und Freigabe gehören zusammen: nur eine publizierte Revision hat einen Veröffentlichungszeitpunkt, Freigabe verlangt einen Freigeber.
  ADD CONSTRAINT config_revision_publication_consistent CHECK ((state = 'PUBLISHED') = (published_at IS NOT NULL)),
  ADD CONSTRAINT config_revision_approval_consistent CHECK (state NOT IN ('APPROVED', 'PUBLISHED') OR approved_by IS NOT NULL),
  ADD CONSTRAINT config_revision_schema_version_fk FOREIGN KEY (schema_version) REFERENCES policy_schema_version(schema_version);

-- Lebenszyklus (Kap. 04 §4.5, API-Schema ConfigRevision): Entwurf -> geprüft -> freigegeben -> publiziert; ein geprüfter oder freigegebener
-- Entwurf kann zur Überarbeitung zurück in den Entwurf. Die Übergänge löst WP-M1-22 aus; die Datenbank erzwingt schon jetzt die Regeln.
INSERT INTO state_transition_rule(machine, version, from_state, to_state) VALUES
  ('config_revision', 1, 'DRAFT', 'VALIDATED'), ('config_revision', 1, 'VALIDATED', 'DRAFT'), ('config_revision', 1, 'VALIDATED', 'APPROVED'),
  ('config_revision', 1, 'APPROVED', 'DRAFT'), ('config_revision', 1, 'APPROVED', 'PUBLISHED');
SELECT harden_state_column('taktovia.config_revision', 'state', 'config_revision');

-- Wächter: identitätsstiftende Felder und der Zeitpunkt der Änderung sind fest, publizierte Revisionen sind vollständig unveränderlich
-- (auch für Administration), jede Änderung eines Entwurfs erhöht die Zeilenversion genau um 1.
CREATE FUNCTION config_revision_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.state = 'PUBLISHED' THEN RAISE EXCEPTION 'config_revision is published and immutable' USING ERRCODE = '23514'; END IF;
    RETURN OLD;
  END IF;
  IF OLD.state = 'PUBLISHED' THEN RAISE EXCEPTION 'config_revision is published and immutable' USING ERRCODE = '23514'; END IF;
  IF NEW.scope_kind IS DISTINCT FROM OLD.scope_kind OR NEW.scope_id IS DISTINCT FROM OLD.scope_id OR NEW.revision IS DISTINCT FROM OLD.revision
     OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'config_revision identity is immutable' USING ERRCODE = '23514';
  END IF;
  IF NEW IS DISTINCT FROM OLD THEN NEW.row_version := OLD.row_version + 1; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER config_revision_guard BEFORE UPDATE OR DELETE ON config_revision FOR EACH ROW EXECUTE FUNCTION config_revision_guard();

-- Wertprüfung gegen den Katalog (V1190): beim Anlegen und bei jeder Änderung von Werten, Version oder Scope. Ein Verstoß erzeugt keine Zeile.
CREATE FUNCTION config_revision_validate() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE bad record;
BEGIN
  SELECT v.pointer, v.code INTO bad FROM config_value_violations(NEW.scope_kind, NEW.schema_version, NEW.values_json) v LIMIT 1;
  IF FOUND THEN RAISE EXCEPTION 'policy_schema violation at % (%)', bad.pointer, bad.code USING ERRCODE = '23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER config_revision_validate_ins BEFORE INSERT ON config_revision FOR EACH ROW EXECUTE FUNCTION config_revision_validate();
CREATE TRIGGER config_revision_validate_upd BEFORE UPDATE ON config_revision FOR EACH ROW
  WHEN (NEW.values_json IS DISTINCT FROM OLD.values_json OR NEW.schema_version IS DISTINCT FROM OLD.schema_version)
  EXECUTE FUNCTION config_revision_validate();

-- Verlauf je Scope: Liste und As-of-Abfrage (Index passt zur Präzedenzauflösung von WP-M1-21: tenant, scope, effective_from).
CREATE INDEX config_revision_by_scope ON config_revision (tenant_id, scope_kind, scope_id, effective_from DESC, revision DESC);
CREATE INDEX config_revision_by_created ON config_revision (tenant_id, created_at, id);
