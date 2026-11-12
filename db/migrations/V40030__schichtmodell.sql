-- WP-M4-03 / LH-6.23-SCH-002: Schichtmodell. `staff_shift` (Kernschema V1: driver_ref, vehicle_id, starts_at, ends_at, timezone, roster_revision, state) wird additiv erweitert (expand/contract,
-- Altzeilen aus M2/M3 bleiben gültig und lesbar, `resource_release.shift_id` unverändert): Betrieb (Betreiberorganisation) und Depot, Funktion, erforderliche Qualifikation, Personalbedarf,
-- Pausenplan, der daraus abgeleitete Betriebstag (lokale Zeiten werden aus Zeitpunkt und Zone berechnet, nicht als Zeit ohne Zone gespeichert). `driver_ref` und `vehicle_id` sind nullbar (offene Schicht, OP-08); `ends_at > starts_at` bleibt.
-- Betriebstag einer Mitternachtsschicht = lokales Datum des Beginns in `timezone` (Vorschlag des Plans, OP-08); die Datenbank leitet ihn aus dem absoluten Beginn ab,
-- ein Client kann ihn nicht abweichend setzen. Neue Schichten aus `/v1/rosters` beginnen im Zustand DRAFT (die Disposition nutzt nur PUBLISHED); die Veröffentlichung folgt mit WP-M4-04/-05.
SET search_path = taktovia, public;

ALTER TABLE staff_shift
  ALTER COLUMN driver_ref DROP NOT NULL,
  ALTER COLUMN vehicle_id DROP NOT NULL,
  ADD COLUMN roster_id              uuid,        -- Gruppierung zu einem Dienstplan; Kopf und Revisionen folgen mit WP-M4-04
  ADD COLUMN operator_id            uuid,        -- Betrieb: Organisation des Betreibers
  ADD COLUMN depot_id               uuid,
  ADD COLUMN shift_function         text         CONSTRAINT staff_shift_function CHECK (shift_function IS NULL OR length(shift_function) BETWEEN 1 AND 100),
  ADD COLUMN required_qualification text         CONSTRAINT staff_shift_qualification CHECK (required_qualification IS NULL OR required_qualification ~ '^[A-Z][A-Z0-9_]{1,39}$'),   -- Qualifikationsart wie `qualification_revision.qualification_code`
  ADD COLUMN staffing_need          integer      NOT NULL DEFAULT 1 CONSTRAINT staff_shift_need CHECK (staffing_need BETWEEN 1 AND 50),   -- Personalbedarf: Zahl der benötigten Personen
  ADD COLUMN break_plan             jsonb        NOT NULL DEFAULT '{"schema_version": "1.0", "breaks": []}'
    CONSTRAINT staff_shift_break_plan_shape CHECK (jsonb_typeof(break_plan) = 'object' AND break_plan ->> 'schema_version' = '1.0'
      AND jsonb_typeof(break_plan -> 'breaks') = 'array' AND jsonb_array_length(break_plan -> 'breaks') <= 6),
  ADD COLUMN operating_day          date,        -- abgeleitet: lokales Datum des Beginns in `timezone` (lokale Zeiten ohne Zone werden nicht gespeichert, Zeit-Lint SYS-006)
  ADD COLUMN shift_revision         bigint       NOT NULL DEFAULT 1 CONSTRAINT staff_shift_revision CHECK (shift_revision >= 1),   -- ETag der Schicht (`roster_revision` ist die Dienstplanrevision)
  ADD COLUMN created_by             text         CONSTRAINT staff_shift_created_by CHECK (created_by IS NULL OR created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  ADD COLUMN created_at             timestamptz  NOT NULL DEFAULT clock_timestamp(),
  ADD CONSTRAINT staff_shift_depot_fk    FOREIGN KEY (tenant_id, depot_id)    REFERENCES depot (tenant_id, id),
  ADD CONSTRAINT staff_shift_operator_fk FOREIGN KEY (tenant_id, operator_id) REFERENCES organization (tenant_id, id),
  ADD CONSTRAINT staff_shift_max_24h     CHECK (ends_at - starts_at <= interval '24 hours');

CREATE FUNCTION staff_shift_derive() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
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
    -- Revision +1 je inhaltlicher Änderung (abgeleitete Spalten zählen nicht: der Rückfüllungslauf dieser Migration lässt Altzeilen auf Revision 1)
    IF (to_jsonb(NEW) - ARRAY['shift_revision', 'operating_day']) IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['shift_revision', 'operating_day']) THEN
      NEW.shift_revision := OLD.shift_revision + 1;
    ELSE
      NEW.shift_revision := OLD.shift_revision;
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER staff_shift_derive BEFORE INSERT OR UPDATE ON staff_shift FOR EACH ROW EXECUTE FUNCTION staff_shift_derive();

-- Altzeilen (M2-/M3-Fixtures, Spike): abgeleitete Spalten füllen, ohne Revision oder Inhalt zu ändern
UPDATE staff_shift SET starts_at = starts_at WHERE operating_day IS NULL;

CREATE INDEX staff_shift_by_roster ON staff_shift (tenant_id, roster_id, starts_at, id) WHERE roster_id IS NOT NULL;
CREATE INDEX staff_shift_by_day    ON staff_shift (tenant_id, depot_id, operating_day);
