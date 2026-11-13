-- WP-M3-12 / LH-6.21-CHK-007: Barrierefreiheits-Prüfpunkte und eingeschränkte Freigabe.
-- 1. checklist_step.equipment_condition: Ausstattungsbedingung eines Prüfelements (RAMP_OR_LIFT = nur für Fahrzeuge, deren Ausstattung `capacity_config.equipment` RAMP oder LIFT enthält).
--    Ein Element mit Bedingung gehört nur zu Prüfläufen passender Fahrzeuge; die Anwendung leitet die Prüfpunkte je Fahrzeug aus der Ausstattung ab (nie aus einem Clientwert).
-- 2. checklist_step.restricts_dimension: Ressourcendimension, die bei Mangel oder „Nicht prüfbar“ entfällt (wheelchair). Nur mit Ausstattungsbedingung. Diese Elemente (und nur diese) nehmen
--    die Antwort NOT_CHECKABLE an (OP-16: „Nicht prüfbar“ gilt nie als Ja und gibt das Fahrzeug nie für passende Fahrten frei). Ob ein Mangel das Fahrzeug sperrt oder nur einschränkt,
--    bestimmt `safety_critical` (WP-M3-16): kritisch = gesperrt, sonst eingeschränkt.
-- 3. inspection_run.restriction: Einschränkung des Prüfergebnisses PASSED_RESTRICTED (Objekt mit `unavailable_dimensions` und `step_codes`); genau dann gesetzt, wenn das Ergebnis eingeschränkt ist.
SET search_path = taktovia, public;

ALTER TABLE checklist_step
  ADD COLUMN equipment_condition text CHECK (equipment_condition IS NULL OR equipment_condition IN ('RAMP_OR_LIFT')),
  ADD COLUMN restricts_dimension text CHECK (restricts_dimension IS NULL OR restricts_dimension IN ('wheelchair')),
  ADD CONSTRAINT checklist_step_restricts_needs_equipment CHECK (restricts_dimension IS NULL OR equipment_condition IS NOT NULL),
  ADD CONSTRAINT checklist_step_restricts_ok_defect CHECK (restricts_dimension IS NULL OR answer_kind = 'OK_DEFECT_NA');

ALTER TABLE inspection_run
  ADD COLUMN restriction jsonb CHECK (restriction IS NULL OR (jsonb_typeof(restriction) = 'object' AND jsonb_typeof(restriction -> 'unavailable_dimensions') = 'array'
                                                              AND jsonb_array_length(restriction -> 'unavailable_dimensions') >= 1)),
  ADD CONSTRAINT inspection_restriction_consistent CHECK ((outcome = 'PASSED_RESTRICTED') = (restriction IS NOT NULL));

-- NOT_CHECKABLE nur für Prüfelemente, die eine Ressourcendimension einschränken können (Ausstattungsprüfpunkte); sonst bleibt der vierte Statuswert bis zur Klärung von OP-16 abgewiesen.
CREATE FUNCTION inspection_answer_not_checkable_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM inspection_run r JOIN checklist_step s ON s.tenant_id = r.tenant_id AND s.template_revision_id = r.template_revision_id AND s.step_code = NEW.step_code
                  WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.inspection_id AND s.restricts_dimension IS NOT NULL) THEN
    RAISE EXCEPTION 'answer NOT_CHECKABLE is only valid for equipment steps that restrict a resource dimension' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER inspection_answer_not_checkable_guard BEFORE INSERT OR UPDATE ON inspection_answer FOR EACH ROW WHEN (NEW.answer = 'NOT_CHECKABLE') EXECUTE FUNCTION inspection_answer_not_checkable_guard();

-- 4. Nur die jüngste Prüfung einer Schicht bestimmt Freigabe und Einschränkung: eine ältere gültige Freigabe derselben Schicht wird bei der Erteilung der neuen widerrufen (Grund SUPERSEDED).
ALTER TABLE resource_release DROP CONSTRAINT resource_release_revoke_reason_check;
ALTER TABLE resource_release ADD CONSTRAINT resource_release_revoke_reason_check CHECK (revoke_reason IS NULL OR revoke_reason IN ('BLOCK_SET', 'VEHICLE_CHANGED', 'QUALIFICATION_REVOKED', 'MANUAL', 'SUPERSEDED'));
