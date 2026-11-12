-- WP-M3-10 (LH-6.21-CHK-003): Checklistenvorlagen und Prüfelemente.
-- Ergänzt die Tabellen aus V3020 um Vorlagenname, Vier-Augen-Freigabe, Hilfetext, Beispielbild, Begründungspflicht bei "Nicht anwendbar" und Zahlengrenzen;
-- je Vorlage höchstens ein Entwurf und genau eine wirksame (veröffentlichte) Revision; die Unveränderlichkeit der freigegebenen Revision schließt jetzt auch
-- Name und Freigebenden ein. Antwortwerte der Tabelle inspection_answer bleiben wie in V3020 (OK, DEFECT, NOT_APPLICABLE; NOT_CHECKABLE ist durch OP-16 nur
-- vorgehalten und wird von der Antwortvalidierung bis zur Entscheidung abgewiesen).

ALTER TABLE checklist_template_revision
  ADD COLUMN name        text CHECK (name IS NULL OR length(btrim(name)) BETWEEN 1 AND 120),
  ADD COLUMN approved_by text CHECK (approved_by IS NULL OR approved_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$');

-- Vier-Augen-Prinzip auch an der Anwendung vorbei (WP-M1-22): eine veröffentlichte Revision hat einen Freigebenden, der nicht der Ersteller ist.
ALTER TABLE checklist_template_revision
  ADD CONSTRAINT checklist_published_four_eyes CHECK (state = 'DRAFT' OR (approved_by IS NOT NULL AND approved_by <> created_by));

-- Je Vorlage (template_id) höchstens ein Entwurf und genau eine wirksame Revision.
CREATE UNIQUE INDEX checklist_one_draft_per_template ON checklist_template_revision (tenant_id, template_id) WHERE state = 'DRAFT';
CREATE UNIQUE INDEX checklist_one_published_per_template ON checklist_template_revision (tenant_id, template_id) WHERE state = 'PUBLISHED';

ALTER TABLE checklist_step
  ADD COLUMN help_text          text CHECK (help_text IS NULL OR length(help_text) <= 1000),
  ADD COLUMN example_image_name text CHECK (example_image_name IS NULL OR (example_image_name ~ '^[A-Za-z0-9._/-]{1,200}$' AND example_image_name !~ '(^/|//|(^|/)\.\.?(/|$))')),
  ADD COLUMN na_requires_reason boolean NOT NULL DEFAULT false,
  ADD COLUMN number_min         numeric,
  ADD COLUMN number_max         numeric,
  ADD COLUMN number_unit        text CHECK (number_unit IS NULL OR length(number_unit) BETWEEN 1 AND 20);

ALTER TABLE checklist_step
  ADD CONSTRAINT checklist_step_title_not_blank CHECK (length(btrim(title)) >= 1),
  ADD CONSTRAINT checklist_step_number_only_for_number CHECK (answer_kind = 'NUMBER' OR (number_min IS NULL AND number_max IS NULL AND number_unit IS NULL)),
  ADD CONSTRAINT checklist_step_number_range CHECK (number_min IS NULL OR number_max IS NULL OR number_min <= number_max);

-- Wächter der Vorlagenrevision (ersetzt V3020): Veröffentlichung nur mit mindestens einem Prüfelement; danach nur noch PUBLISHED -> RETIRED.
CREATE OR REPLACE FUNCTION checklist_template_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.state <> 'DRAFT' THEN RAISE EXCEPTION 'published checklist template revision is immutable' USING ERRCODE = '23514'; END IF;
    RETURN OLD;
  END IF;
  IF OLD.state = 'RETIRED' THEN RAISE EXCEPTION 'retired checklist template revision is immutable' USING ERRCODE = '23514'; END IF;
  IF OLD.state = 'PUBLISHED' AND (NEW.state <> 'RETIRED' OR NEW.template_id IS DISTINCT FROM OLD.template_id OR NEW.version IS DISTINCT FROM OLD.version
     OR NEW.vehicle_class IS DISTINCT FROM OLD.vehicle_class OR NEW.service_mode IS DISTINCT FROM OLD.service_mode OR NEW.published_at IS DISTINCT FROM OLD.published_at
     OR NEW.name IS DISTINCT FROM OLD.name OR NEW.approved_by IS DISTINCT FROM OLD.approved_by OR NEW.created_by IS DISTINCT FROM OLD.created_by) THEN
    RAISE EXCEPTION 'published checklist template revision is immutable; only retirement is allowed' USING ERRCODE = '23514';
  END IF;
  IF OLD.state = 'DRAFT' AND NEW.state = 'PUBLISHED' AND NOT EXISTS (SELECT 1 FROM checklist_step s WHERE s.tenant_id = NEW.tenant_id AND s.template_revision_id = NEW.id) THEN
    RAISE EXCEPTION 'checklist template revision without steps cannot be published' USING ERRCODE = '23514';
  END IF;
  IF OLD.state = 'DRAFT' AND NEW.state = 'RETIRED' THEN
    RAISE EXCEPTION 'draft checklist template revision cannot be retired' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
