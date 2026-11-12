-- WP-M4-04 / LH-6.23-SCH-004: Dienstplan-Entwurf getrennt vom veröffentlichten Plan. Ein Dienstplan ist der Plan eines Depots für eine Kalenderwoche (Schnitt: Vorschlag des Plans, WP-M4-01);
-- er besteht aus Revisionen (`roster_revision`, je Dienstplan höchstens ein Entwurf und höchstens eine veröffentlichte Revision). Veröffentlichung (WP-M4-14) ist eine neue Revision,
-- kein Überschreiben; weitere Änderungen laufen in einem neuen Entwurf (Kopie der veröffentlichten Schichten, `openDraft`). Zustandsautomat: vertraege/zustaende/dienstplan.md.
-- Entwurfszeilen (`roster_revision` und `staff_shift` im Zustand DRAFT) sind zusätzlich zur Mandantentrennung nur sichtbar, wenn die Anwendung nach der Rollenprüfung (Funktionsscope und
-- Depotrecht) transaktionslokal `app.roster_draft_access = 'on'` setzt (restriktive Policy, wie `app.archive_access`); ohne Planerrecht liefert auch eine Direktabfrage nichts.
-- Zusätzlich (OP-38, damit Konflikte im Entwurf speicherbar bleiben): das Überlappungsverbot der Schichtzuweisung (V3070) gilt nur für verbindliche Schichten, nicht für DRAFT/SUPERSEDED.
SET search_path = taktovia, public;

CREATE TABLE roster_revision (
  tenant_id        uuid        NOT NULL REFERENCES tenant(id),
  id               uuid        NOT NULL,
  roster_id        uuid        NOT NULL,                                  -- stabile Kennung des Dienstplans (Depot und Woche), gleich über alle Revisionen
  depot_id         uuid        NOT NULL,
  week_start       date        NOT NULL CHECK (extract(isodow FROM week_start) = 1),   -- Montag der Kalenderwoche (lokales Datum)
  revision         bigint      NOT NULL CHECK (revision >= 1),
  state            text        NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED')),
  author           text        NOT NULL CHECK (author ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  published_at     timestamptz,
  published_by     text,
  entity_revision  bigint      NOT NULL DEFAULT 1 CHECK (entity_revision >= 1),   -- ETag des Dienstplanstands; steigt mit jeder Änderung der Schichten dieser Revision
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, roster_id, revision),
  UNIQUE (tenant_id, depot_id, week_start, revision),
  FOREIGN KEY (tenant_id, depot_id) REFERENCES depot (tenant_id, id),
  CONSTRAINT roster_revision_published_consistent CHECK ((state = 'DRAFT') = (published_at IS NULL) AND (published_at IS NULL) = (published_by IS NULL))
);
CREATE UNIQUE INDEX roster_revision_one_draft     ON roster_revision (tenant_id, roster_id) WHERE state = 'DRAFT';
CREATE UNIQUE INDEX roster_revision_one_published ON roster_revision (tenant_id, roster_id) WHERE state = 'PUBLISHED';
CREATE INDEX roster_revision_by_depot ON roster_revision (tenant_id, depot_id, week_start);

ALTER TABLE roster_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE roster_revision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON roster_revision USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE POLICY draft_visibility ON roster_revision AS RESTRICTIVE USING (state <> 'DRAFT' OR current_setting('app.roster_draft_access', true) = 'on');
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON roster_revision FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change();

-- Lebenslauf und Unveränderlichkeit: Revisionen eines Dienstplans gehören zu demselben Depot und derselben Woche und zählen lückenlos hoch; Übergänge nur DRAFT -> PUBLISHED -> SUPERSEDED;
-- Kennung, Depot, Woche, Revisionsnummer, Autor und Entstehungszeit sind unveränderlich; eine veröffentlichte oder ersetzte Revision ändert nur noch ihren Zustand (PUBLISHED -> SUPERSEDED).
CREATE FUNCTION roster_revision_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE
  prev record;
BEGIN
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
    NEW.entity_revision := OLD.entity_revision + 1;
  ELSIF OLD.state <> 'DRAFT' AND NEW IS DISTINCT FROM OLD THEN
    RAISE EXCEPTION 'roster_revision: veroeffentlichte Revisionen sind unveraenderlich' USING ERRCODE = '23514';
  END IF;
  IF NEW.entity_revision < OLD.entity_revision THEN RAISE EXCEPTION 'roster_revision: entity_revision steigt nur' USING ERRCODE = '23514'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER roster_revision_guard BEFORE INSERT OR UPDATE ON roster_revision FOR EACH ROW EXECUTE FUNCTION roster_revision_guard();

-- staff_shift: Verweis auf die Dienstplanrevision (NOT VALID: Zeilen ohne Dienstplan, M2/M3-Zuweisungen mit roster_id NULL, bleiben gültig), Entwurfssichtbarkeit,
-- Änderungszeit (Index für offene Schichten, WP-M4-05) und „offene Schicht trägt kein Fahrzeug“ (SCH-008).
ALTER TABLE staff_shift
  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  ADD CONSTRAINT staff_shift_roster_revision_fk FOREIGN KEY (tenant_id, roster_id, roster_revision) REFERENCES roster_revision (tenant_id, roster_id, revision) NOT VALID,
  ADD CONSTRAINT staff_shift_open_without_vehicle CHECK (driver_ref IS NOT NULL OR vehicle_id IS NULL);
CREATE POLICY draft_visibility ON staff_shift AS RESTRICTIVE USING (state <> 'DRAFT' OR current_setting('app.roster_draft_access', true) = 'on');
CREATE INDEX staff_shift_by_state ON staff_shift (tenant_id, state, updated_at);

-- Ableitungstrigger (V40030) um updated_at erweitert: die Änderungszeit steigt nur bei inhaltlicher Änderung (ein unveränderter Inhalt bleibt unverändert, auch die Revision).
CREATE OR REPLACE FUNCTION staff_shift_derive() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  NEW.operating_day := (NEW.starts_at AT TIME ZONE NEW.timezone)::date;
  IF TG_OP = 'INSERT' THEN
    NEW.shift_revision := 1;
  ELSE
    IF OLD.roster_id IS NOT NULL AND NEW.roster_id IS DISTINCT FROM OLD.roster_id THEN
      RAISE EXCEPTION 'staff_shift: Dienstplanzuordnung ist nach dem Setzen endgueltig' USING ERRCODE = '23514';
    END IF;
    IF NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
      RAISE EXCEPTION 'staff_shift: Urheber und Entstehungszeit sind unveraenderlich' USING ERRCODE = '23514';
    END IF;
    IF (to_jsonb(NEW) - ARRAY['shift_revision', 'operating_day', 'updated_at']) IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['shift_revision', 'operating_day', 'updated_at']) THEN
      NEW.shift_revision := OLD.shift_revision + 1;
      NEW.updated_at := clock_timestamp();
    ELSE
      NEW.shift_revision := OLD.shift_revision;
      NEW.updated_at := OLD.updated_at;
    END IF;
  END IF;
  RETURN NEW;
END $$;

-- Überlappungsverbot nur für verbindliche Schichten (OP-38): Entwürfe und ersetzte Revisionen blockieren nichts und werden nicht geprüft; die Konfliktprüfung des Entwurfs
-- (WP-M4-07) meldet Überlappungen als Hinweis, die Veröffentlichung sperrt sie. Beim Übergang DRAFT -> PUBLISHED prüft der Trigger wie bei einer Neuanlage.
CREATE OR REPLACE FUNCTION staff_shift_clash(p_tenant uuid, p_self uuid, p_driver uuid, p_vehicle uuid, p_start timestamptz, p_end timestamptz)
RETURNS TABLE (cause text, shift_id uuid) LANGUAGE sql SET search_path = taktovia, pg_catalog AS $$
  (SELECT 'DRIVER'::text, s.id FROM staff_shift s
    WHERE s.tenant_id = p_tenant AND s.id <> p_self AND s.state NOT IN ('CANCELLED', 'DRAFT', 'SUPERSEDED') AND s.driver_ref = p_driver
      AND tstzrange(s.starts_at, s.ends_at, '[)') && tstzrange(p_start, p_end, '[)') ORDER BY s.starts_at, s.id LIMIT 1)
  UNION ALL
  (SELECT 'VEHICLE'::text, s.id FROM staff_shift s
    WHERE s.tenant_id = p_tenant AND s.id <> p_self AND s.state NOT IN ('CANCELLED', 'DRAFT', 'SUPERSEDED') AND s.vehicle_id = p_vehicle
      AND tstzrange(s.starts_at, s.ends_at, '[)') && tstzrange(p_start, p_end, '[)') ORDER BY s.starts_at, s.id LIMIT 1)
$$;

CREATE OR REPLACE FUNCTION staff_shift_overlap_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE
  c record;
BEGIN
  IF NEW.ends_at <= NEW.starts_at THEN
    RAISE EXCEPTION 'staff shift must end after it starts' USING ERRCODE = '23514', CONSTRAINT = 'staff_shift_check';
  END IF;
  IF NEW.state IN ('CANCELLED', 'DRAFT', 'SUPERSEDED') THEN RETURN NEW; END IF;
  IF TG_OP = 'UPDATE' AND NEW.driver_ref IS NOT DISTINCT FROM OLD.driver_ref AND NEW.vehicle_id IS NOT DISTINCT FROM OLD.vehicle_id AND NEW.starts_at = OLD.starts_at AND NEW.ends_at = OLD.ends_at
     AND NEW.state = OLD.state AND NEW.overlap_exception_at IS NOT DISTINCT FROM OLD.overlap_exception_at THEN
    RETURN NEW;      -- nichts, was die Belegung ändert (z. B. Dienstantritt)
  END IF;
  IF NEW.driver_ref IS NULL AND NEW.vehicle_id IS NULL THEN RETURN NEW; END IF;   -- offene Schicht: keine Belegung
  PERFORM staff_shift_lock(NEW.tenant_id, coalesce(NEW.driver_ref, NEW.id), coalesce(NEW.vehicle_id, NEW.id));
  IF NEW.overlap_exception_at IS NOT NULL THEN RETURN NEW; END IF;
  FOR c IN SELECT * FROM staff_shift_clash(NEW.tenant_id, NEW.id, NEW.driver_ref, NEW.vehicle_id, NEW.starts_at, NEW.ends_at) LOOP
    RAISE EXCEPTION 'staff shift overlaps another shift of the same %', lower(c.cause)
      USING ERRCODE = '23P01', CONSTRAINT = 'staff_shift_' || lower(c.cause) || '_no_overlap', DETAIL = c.cause || ':' || c.shift_id::text;
  END LOOP;
  RETURN NEW;
END $$;
