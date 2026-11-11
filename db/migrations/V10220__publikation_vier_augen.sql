-- WP-M1-22 / LH-I.7-CFG-006: Validierung, Vier-Augen-Freigabe, Publikation und Rollback von Konfigurationsrevisionen.
-- Festlegungen für den Bau (OP-19, Detailvertrag docs/umsetzungsplan/vertraege/zustaende/config-revision.md):
--  * Vier-Augen-Regel in der Datenbank: `approved_by <> created_by` (die Freigabe durch den Ersteller ist ausgeschlossen, auch an der App vorbei).
--  * Invariante Kap. 04 §4.5 „Nicht überlappende aktive Dienst-/Policyversion“: Exclusion Constraint über die publizierten Revisionen eines Scopes
--    (halboffene Intervalle [effective_from, effective_to): aneinandergrenzende Intervalle sind zulässig). Das Profil (`TENANT_PROFILE`) hat eigene Revisionsregeln und ist ausgenommen.
--  * Die Publikation schließt `effective_to` der Vorgängerrevision. Das ist die EINZIGE Änderung, die eine publizierte Zeile noch zulässt: das Ende darf nur
--    gesetzt oder vorgezogen werden, und nur auf den Beginn einer freigegebenen bzw. publizierten Nachfolgerevision desselben Scopes. Alle übrigen Spalten bleiben fest.
--  * Zusatzspalten für Nachweis und Rollback: Prüfer und Zeitpunkt der Validierung, Zeitpunkt der Freigabe, Veröffentlichender und Grund, `rolled_back_from`
--    (die fehlerhafte Revision, die diese Revision zurücknimmt).
SET search_path = taktovia, public;

CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

ALTER TABLE config_revision
  ADD COLUMN validated_by       text,
  ADD COLUMN validated_at       timestamptz,
  ADD COLUMN approved_at        timestamptz,
  ADD COLUMN published_by       text,
  ADD COLUMN publication_reason text,
  ADD COLUMN rolled_back_from   uuid;

ALTER TABLE config_revision
  -- Das Unternehmensprofil (TENANT_PROFILE) pflegt der Mandantenadministrator selbst (WP-M1-24, ohne zweite Person): ausgenommen.
  ADD CONSTRAINT config_revision_four_eyes CHECK (scope_kind = 'TENANT_PROFILE' OR approved_by IS NULL OR approved_by <> created_by),
  ADD CONSTRAINT config_revision_rollback_not_self CHECK (rolled_back_from IS NULL OR rolled_back_from <> id),
  ADD CONSTRAINT config_revision_rollback_fk FOREIGN KEY (tenant_id, rolled_back_from) REFERENCES config_revision (tenant_id, id),
  ADD CONSTRAINT config_revision_actor_refs CHECK (
    (validated_by IS NULL OR length(validated_by) BETWEEN 3 AND 200) AND (published_by IS NULL OR length(published_by) BETWEEN 3 AND 200)
    AND (publication_reason IS NULL OR length(btrim(publication_reason)) BETWEEN 3 AND 1000));

ALTER TABLE config_revision
  ADD CONSTRAINT config_revision_published_no_overlap
  EXCLUDE USING gist (tenant_id WITH =, scope_kind WITH =, scope_id WITH =, tstzrange(effective_from, effective_to) WITH &&)
  WHERE (state = 'PUBLISHED' AND scope_kind <> 'TENANT_PROFILE');

CREATE OR REPLACE FUNCTION config_revision_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.state = 'PUBLISHED' THEN RAISE EXCEPTION 'config_revision is published and immutable' USING ERRCODE = '23514'; END IF;
    RETURN OLD;
  END IF;
  IF OLD.state = 'PUBLISHED' THEN
    -- einzige zulässige Änderung: das Ende des Wirksamkeitsintervalls setzen oder vorziehen, und zwar auf den Beginn einer freigegebenen/publizierten Nachfolgerevision
    IF NEW.state = 'PUBLISHED' AND NEW.effective_to IS NOT NULL AND NEW.effective_to > OLD.effective_from
       AND (OLD.effective_to IS NULL OR NEW.effective_to < OLD.effective_to)
       AND (to_jsonb(NEW) - 'effective_to' - 'row_version') = (to_jsonb(OLD) - 'effective_to' - 'row_version')
       AND EXISTS (SELECT 1 FROM config_revision s WHERE s.tenant_id = NEW.tenant_id AND s.scope_kind = NEW.scope_kind AND s.scope_id = NEW.scope_id
                   AND s.id <> NEW.id AND s.state IN ('APPROVED', 'PUBLISHED') AND s.effective_from = NEW.effective_to) THEN
      NEW.row_version := OLD.row_version + 1;
      RETURN NEW;
    END IF;
    RAISE EXCEPTION 'config_revision is published and immutable' USING ERRCODE = '23514';
  END IF;
  IF NEW.scope_kind IS DISTINCT FROM OLD.scope_kind OR NEW.scope_id IS DISTINCT FROM OLD.scope_id OR NEW.revision IS DISTINCT FROM OLD.revision
     OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.rolled_back_from IS DISTINCT FROM OLD.rolled_back_from THEN
    RAISE EXCEPTION 'config_revision identity is immutable' USING ERRCODE = '23514';
  END IF;
  IF NEW IS DISTINCT FROM OLD THEN NEW.row_version := OLD.row_version + 1; END IF;
  RETURN NEW;
END $$;
