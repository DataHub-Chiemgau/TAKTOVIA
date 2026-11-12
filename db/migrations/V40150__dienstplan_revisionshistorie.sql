-- WP-M4-15 / LH-6.23-SCH-010: Änderungs- und Veröffentlichungsrevisionen der Schicht. (1) Eine veröffentlichte Revision ist unveränderlich: nach der Publikation ändert sich nur noch ihr Zustand
-- (PUBLISHED -> SUPERSEDED), kein anderes Feld, und sie lässt sich nicht löschen (der Entwurf bleibt bis zur Publikation veränderlich). Die Schichtstände je Revision sind die `staff_shift`-Zeilen der
-- Revision (Snapshot je Revision: der Entwurf kopiert beim Eröffnen, die Zeilen früherer Revisionen bleiben unberührt, Vorschlag des Plans, OP 8).
-- (2) `roster_shift_change`: unveränderliches Änderungsprotokoll je Schicht (stabile `lineage_id`): jede Anlage, Änderung und Veröffentlichung mit Autor, Zeitstempel und Schichtstand (Snapshot, JSON).
-- Anhängen und lesen; UPDATE und DELETE sperrt der Trigger. Sichtbarkeit wie die Revision: Zeilen einer Entwurfsrevision sehen nur Berechtigte (Entwurfsschalter, restriktive Policy über `roster_revision`).
SET search_path = taktovia, public;

CREATE OR REPLACE FUNCTION roster_revision_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE
  prev record;
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.state <> 'DRAFT' THEN RAISE EXCEPTION 'roster_revision: veroeffentlichte Revisionen lassen sich nicht loeschen' USING ERRCODE = '23514'; END IF;
    RETURN OLD;
  END IF;
  IF TG_OP = 'INSERT' THEN
    SELECT max(revision) AS max_rev, min(depot_id::text) AS depot, min(week_start) AS week INTO prev FROM roster_revision WHERE tenant_id = NEW.tenant_id AND roster_id = NEW.roster_id;
    IF prev.max_rev IS NULL THEN
      IF NEW.revision <> 1 THEN RAISE EXCEPTION 'roster_revision: erste Revision hat die Nummer 1' USING ERRCODE = '23514'; END IF;
    ELSE
      IF NEW.revision <> prev.max_rev + 1 THEN RAISE EXCEPTION 'roster_revision: Revisionsnummern zaehlen lueckenlos hoch' USING ERRCODE = '23514'; END IF;
      IF NEW.depot_id::text <> prev.depot OR NEW.week_start <> prev.week THEN RAISE EXCEPTION 'roster_revision: Depot und Woche eines Dienstplans sind fest' USING ERRCODE = '23514'; END IF;
    END IF;
    IF NEW.state <> 'DRAFT' THEN RAISE EXCEPTION 'roster_revision: neue Revisionen beginnen als Entwurf' USING ERRCODE = '23514'; END IF;
    RETURN NEW;
  END IF;
  IF NEW.id IS DISTINCT FROM OLD.id OR NEW.roster_id IS DISTINCT FROM OLD.roster_id OR NEW.depot_id IS DISTINCT FROM OLD.depot_id OR NEW.week_start IS DISTINCT FROM OLD.week_start
     OR NEW.revision IS DISTINCT FROM OLD.revision OR NEW.author IS DISTINCT FROM OLD.author OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'roster_revision: Kennung, Depot, Woche, Revision und Autor sind unveraenderlich' USING ERRCODE = '23514';
  END IF;
  IF NEW.state IS DISTINCT FROM OLD.state THEN
    IF NOT ((OLD.state = 'DRAFT' AND NEW.state = 'PUBLISHED') OR (OLD.state = 'PUBLISHED' AND NEW.state = 'SUPERSEDED')) THEN
      RAISE EXCEPTION 'roster_revision: Zustandsuebergang % -> % nicht erlaubt', OLD.state, NEW.state USING ERRCODE = '23514', CONSTRAINT = 'roster_revision_state_transition';
    END IF;
    IF OLD.state = 'PUBLISHED' AND (to_jsonb(NEW) - ARRAY['state', 'entity_revision']) IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['state', 'entity_revision']) THEN
      RAISE EXCEPTION 'roster_revision: eine veroeffentlichte Revision aendert nur noch ihren Zustand' USING ERRCODE = '23514';
    END IF;
    NEW.entity_revision := OLD.entity_revision + 1;
  ELSIF OLD.state <> 'DRAFT' AND NEW IS DISTINCT FROM OLD THEN
    RAISE EXCEPTION 'roster_revision: veroeffentlichte Revisionen sind unveraenderlich' USING ERRCODE = '23514';
  END IF;
  IF NEW.entity_revision < OLD.entity_revision THEN RAISE EXCEPTION 'roster_revision: entity_revision steigt nur' USING ERRCODE = '23514'; END IF;
  RETURN NEW;
END $$;
DROP TRIGGER roster_revision_guard ON roster_revision;
CREATE TRIGGER roster_revision_guard BEFORE INSERT OR UPDATE OR DELETE ON roster_revision FOR EACH ROW EXECUTE FUNCTION roster_revision_guard();

CREATE TABLE roster_shift_change (
  tenant_id        uuid        NOT NULL REFERENCES tenant(id),
  id               uuid        NOT NULL,
  roster_id        uuid        NOT NULL,
  roster_revision  bigint      NOT NULL CHECK (roster_revision >= 1),
  lineage_id       uuid        NOT NULL,                              -- stabile Schichtkennung über Revisionen
  shift_id         uuid        NOT NULL,                              -- Schichtzeile der Revision, auf die sich die Änderung bezieht
  change_kind      text        NOT NULL CHECK (change_kind IN ('CREATED', 'CHANGED', 'PUBLISHED')),
  shift_revision   bigint      NOT NULL CHECK (shift_revision >= 1),
  actor            text        NOT NULL CHECK (actor ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  occurred_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  snapshot         jsonb       NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),   -- Schichtstand zum Zeitpunkt der Änderung
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, roster_id, roster_revision) REFERENCES roster_revision (tenant_id, roster_id, revision),
  FOREIGN KEY (tenant_id, shift_id) REFERENCES staff_shift (tenant_id, id)
);
CREATE INDEX roster_shift_change_by_lineage ON roster_shift_change (tenant_id, lineage_id, occurred_at, id);
CREATE INDEX roster_shift_change_by_revision ON roster_shift_change (tenant_id, roster_id, roster_revision);

ALTER TABLE roster_shift_change ENABLE ROW LEVEL SECURITY;
ALTER TABLE roster_shift_change FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON roster_shift_change USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
-- Zeilen einer Entwurfsrevision folgen der Entwurfssichtbarkeit der Revision (deren eigene restriktive Policy filtert die Unterabfrage)
CREATE POLICY revision_visibility ON roster_shift_change AS RESTRICTIVE
  USING (EXISTS (SELECT 1 FROM taktovia.roster_revision r WHERE r.tenant_id = roster_shift_change.tenant_id AND r.roster_id = roster_shift_change.roster_id AND r.revision = roster_shift_change.roster_revision));
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON roster_shift_change FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change();
CREATE TRIGGER roster_shift_change_immutable BEFORE UPDATE OR DELETE ON roster_shift_change FOR EACH ROW EXECUTE FUNCTION taktovia.reject_mutation();
