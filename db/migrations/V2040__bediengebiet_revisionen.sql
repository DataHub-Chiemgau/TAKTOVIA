-- WP-M2-04 / LH-6.9-GEO-001: Bediengebiete als valide Polygone mit Aktivierungszeitraum und Revisionskette.
-- V1 kennt je Zeile nur `(tenant_id, id)`, keinen stabilen Gebietsschlüssel und keine Eindeutigkeit von `revision`. Diese Migration ergänzt:
--  * `area_key`: stabiler Gebietsschlüssel einer Revisionskette (UUID); die erste Zeile einer Kette trägt ihre eigene `id` als Schlüssel (Trigger, damit auch
--    Seed-Zeilen ohne Angabe deterministisch bleiben). `UNIQUE (tenant_id, area_key, revision)` sichert „Revision + 1 desselben Gebiets“ und die Konkurrenz
--    zweier Revisionen (genau eine gewinnt, die andere 412) in der Datenbank.
--  * Überlappungsverbot: „nicht überlappende aktive Version je Dienst“ (Kap. 04 §4.5) als Exclusion Constraint über `(tenant_id, service_id, [valid_from, valid_to))`.
--    Wirksamkeit gilt links geschlossen, rechts offen. Mehrere gleichzeitig wirksame Gebiete eines Dienstes (Zonen, GEO-003) ersetzt WP-M2-06 durch die
--    Zonenrolle (V2060); bis dahin gilt die Regel je Dienst.
--  * Unveränderlichkeit: eine Revision wird nie überschrieben; nur das Ende einer offenen Revision darf gesetzt werden (Nachfolgerevision schließt sie).
-- Das Runtime-Recht UPDATE aus V1 bleibt tabellenweit (Rollenmatrix 00_kern); die Spaltenbeschränkung leistet der Trigger.
SET search_path = taktovia, public;

ALTER TABLE service_area
  ADD COLUMN area_key   uuid,
  ADD COLUMN created_by text        NOT NULL DEFAULT 'system:unattributed'
    CONSTRAINT service_area_created_by_format CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  ADD CONSTRAINT service_area_revision_positive CHECK (revision >= 1);

UPDATE service_area SET area_key = id WHERE area_key IS NULL;
ALTER TABLE service_area ALTER COLUMN area_key SET NOT NULL;
ALTER TABLE service_area
  ADD CONSTRAINT service_area_key_revision UNIQUE (tenant_id, area_key, revision),
  ADD CONSTRAINT service_area_no_overlap EXCLUDE USING gist (tenant_id WITH =, service_id WITH =, tstzrange(valid_from, valid_to) WITH &&);
CREATE INDEX service_area_by_service ON service_area (tenant_id, service_id, valid_from);

CREATE FUNCTION service_area_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    NEW.area_key := COALESCE(NEW.area_key, NEW.id);
    RETURN NEW;
  END IF;
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'service area revision is append-only' USING ERRCODE = '23514';
  END IF;
  IF OLD.valid_to IS NOT NULL OR NEW.valid_to IS NULL
     OR NEW.id IS DISTINCT FROM OLD.id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.service_id IS DISTINCT FROM OLD.service_id
     OR NEW.area_key IS DISTINCT FROM OLD.area_key OR NEW.revision IS DISTINCT FROM OLD.revision OR NEW.area::bytea IS DISTINCT FROM OLD.area::bytea
     OR NEW.valid_from IS DISTINCT FROM OLD.valid_from OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'service area revision is immutable; only an open revision may be closed' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER service_area_guard BEFORE INSERT OR UPDATE OR DELETE ON service_area FOR EACH ROW EXECUTE FUNCTION service_area_guard();
