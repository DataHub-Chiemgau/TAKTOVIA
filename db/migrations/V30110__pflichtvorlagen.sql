-- WP-M3-11 / LH-6.21-CHK-005, LH-6.21-CHK-006: Pflichtvorlagen Fahrzeugtechnik und Fahrgastraum.
-- 1. checklist_template_revision.template_type: Vorlagentyp (GENERAL ohne Pflichtpunkte, VEHICLE_TECHNICAL = technische Fahrzeugprüfung mit acht Pflichtpunkten, PASSENGER_COMPARTMENT =
--    Prüfung vor Fahrgastaufnahme mit sechs Pflichtpunkten). Der Typ gehört zur Revision und ist nach der Freigabe unveränderlich (Wächter, ersetzt V3100). Die Vollständigkeit der Pflichtpunkte
--    prüft die Anwendung bei der Freigabe (422 mit Liste der fehlenden Schlüssel); die Datenbank hält zusätzlich, dass eine veröffentlichte Revision eines Pflichttyps alle Pflichtschlüssel
--    als Pflichtschritte trägt (Wächter beim Veröffentlichen, auch bei direktem SQL).
-- 2. inspection_run.kind: neue Art PRE_BOARDING (Prüfung vor Fahrgastaufnahme); sie erteilt keine Einsatzfreigabe, ihre Vorlage hat den Typ PASSENGER_COMPARTMENT.
SET search_path = taktovia, public;

ALTER TABLE checklist_template_revision
  ADD COLUMN template_type text NOT NULL DEFAULT 'GENERAL' CHECK (template_type IN ('GENERAL', 'VEHICLE_TECHNICAL', 'PASSENGER_COMPARTMENT'));

-- Pflichtschlüssel je Typ (stabile Schlüssel, Großschreibung laut Schlüsselformat der Prüfelemente)
CREATE FUNCTION checklist_mandatory_keys(p_type text) RETURNS text[] LANGUAGE sql IMMUTABLE SET search_path = taktovia, pg_catalog AS $$
  SELECT CASE p_type
    WHEN 'VEHICLE_TECHNICAL' THEN ARRAY['BELEUCHTUNG', 'REIFEN_SICHTPRUEFUNG', 'SCHEIBEN', 'SPIEGEL', 'TUEREN', 'SICHERHEITSGURTE', 'SICHTBARE_LECKAGEN', 'WARNANZEIGEN']
    WHEN 'PASSENGER_COMPARTMENT' THEN ARRAY['SAUBERKEIT', 'FAHRGASTRAUM', 'SITZPLAETZE', 'EINSTIEGSBEREICH', 'RUECKHALTESYSTEME', 'SICHERHEITSAUSRUESTUNG']
    ELSE ARRAY[]::text[] END
$$;

CREATE OR REPLACE FUNCTION checklist_template_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE missing text[];
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.state <> 'DRAFT' THEN RAISE EXCEPTION 'published checklist template revision is immutable' USING ERRCODE = '23514'; END IF;
    RETURN OLD;
  END IF;
  IF OLD.state = 'RETIRED' THEN RAISE EXCEPTION 'retired checklist template revision is immutable' USING ERRCODE = '23514'; END IF;
  IF OLD.state = 'PUBLISHED' AND (NEW.state <> 'RETIRED' OR NEW.template_id IS DISTINCT FROM OLD.template_id OR NEW.version IS DISTINCT FROM OLD.version
     OR NEW.vehicle_class IS DISTINCT FROM OLD.vehicle_class OR NEW.service_mode IS DISTINCT FROM OLD.service_mode OR NEW.published_at IS DISTINCT FROM OLD.published_at
     OR NEW.name IS DISTINCT FROM OLD.name OR NEW.approved_by IS DISTINCT FROM OLD.approved_by OR NEW.created_by IS DISTINCT FROM OLD.created_by
     OR NEW.template_type IS DISTINCT FROM OLD.template_type) THEN
    RAISE EXCEPTION 'published checklist template revision is immutable; only retirement is allowed' USING ERRCODE = '23514';
  END IF;
  IF OLD.state = 'DRAFT' AND NEW.state = 'PUBLISHED' AND NOT EXISTS (SELECT 1 FROM checklist_step s WHERE s.tenant_id = NEW.tenant_id AND s.template_revision_id = NEW.id) THEN
    RAISE EXCEPTION 'checklist template revision without steps cannot be published' USING ERRCODE = '23514';
  END IF;
  IF OLD.state = 'DRAFT' AND NEW.state = 'PUBLISHED' AND NEW.template_type <> 'GENERAL' THEN
    SELECT array_agg(k ORDER BY k) INTO missing FROM unnest(checklist_mandatory_keys(NEW.template_type)) AS k
     WHERE NOT EXISTS (SELECT 1 FROM checklist_step s WHERE s.tenant_id = NEW.tenant_id AND s.template_revision_id = NEW.id AND s.step_code = k AND s.mandatory);
    IF missing IS NOT NULL THEN
      RAISE EXCEPTION 'MANDATORY_POINTS_MISSING: %', array_to_string(missing, ',') USING ERRCODE = '23514';
    END IF;
  END IF;
  IF OLD.state = 'DRAFT' AND NEW.state = 'RETIRED' THEN
    RAISE EXCEPTION 'draft checklist template revision cannot be retired' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

ALTER TABLE inspection_run DROP CONSTRAINT inspection_run_kind_check;
ALTER TABLE inspection_run ADD CONSTRAINT inspection_run_kind_check CHECK (kind IN ('PRE_SHIFT', 'FOLLOW_UP', 'VEHICLE_CHANGE', 'PRE_BOARDING'));
