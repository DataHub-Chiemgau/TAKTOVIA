-- WP-M3-14 / LH-6.21-CHK-013: Prüfabschluss und unveränderlicher Prüfnachweis.
-- Additiv zu V3020 (inspection_run, inspection_answer, Wächter inspection_run_guard und inspection_answer_guard):
-- 1. inspection_run.completed_by: Prüfer-ID aus der Sitzung (Serverwert, nie vom Client); mit dem Abschluss unveränderlich (der Wächter lässt nach COMPLETED gar keine Änderung mehr zu).
--    Zeitstempel Gerät (completed_at) und Server (received_at) sowie Ergebnis (outcome) bestehen bereits und werden vom Server im selben Update gesetzt.
-- 2. inspection_answer.step_id: Verweis auf die Prüfelement-Zeile der festen Vorlagenrevision des Laufs. Freigegebene Revisionen und ihre Prüfelemente sind unveränderlich
--    (checklist_step_guard), der Fremdschlüssel verhindert das Löschen; spätere Vorlagenänderungen erzeugen neue Revisionen und lassen die Historie unberührt (AT-32).
--    Nullbar, weil Altbestand und Fixtures Antworten ohne Verweis anlegen; die Anwendung füllt ihn immer, ein gesetzter Verweis muss zur Vorlage des Laufs passen (Wächter).
SET search_path = taktovia, public;

ALTER TABLE inspection_run
  ADD COLUMN completed_by text CHECK (completed_by IS NULL OR completed_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$');

ALTER TABLE inspection_answer
  ADD COLUMN step_id uuid,
  ADD CONSTRAINT inspection_answer_step_fk FOREIGN KEY (tenant_id, step_id) REFERENCES checklist_step(tenant_id, id);
CREATE INDEX inspection_answer_by_step ON inspection_answer (tenant_id, step_id);

CREATE FUNCTION inspection_answer_step_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM inspection_run r JOIN checklist_step s ON s.tenant_id = r.tenant_id AND s.template_revision_id = r.template_revision_id
                  WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.inspection_id AND s.id = NEW.step_id AND s.step_code = NEW.step_code) THEN
    RAISE EXCEPTION 'inspection answer does not belong to a step of the template revision of its run' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER inspection_answer_step_guard BEFORE INSERT OR UPDATE ON inspection_answer FOR EACH ROW WHEN (NEW.step_id IS NOT NULL) EXECUTE FUNCTION inspection_answer_step_guard();
