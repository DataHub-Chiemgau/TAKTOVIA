-- WP-M3-19 / LH-6.21-CHK-010: Einsatzbereitschaft als reine Ja/Nein-Bestätigung ohne Freitext und Qualifikationssperre der Einsatzfreigabe.
-- 1. Neuer Eingabetyp READINESS_YES_NO der Prüfelemente (Selbstauskunft „Einsatzbereitschaft“): ohne Zahlengrenzen, ohne Text.
-- 2. inspection_answer.answer kennt zusätzlich JA und NEIN. Diese beiden Werte sind ausschließlich dem Eingabetyp READINESS_YES_NO vorbehalten und umgekehrt (Wächter), und eine
--    Antwort JA/NEIN trägt weder Notiz noch Zahl noch Text noch Plausibilitätsangaben (CHECK, auch bei direktem SQL): es gibt KEINE Textspalte für dieses Element, die einen Wert aufnehmen könnte.
-- 3. Ein Nachtrag (inspection_amendment) zu einer Selbstauskunft ist ausgeschlossen: die Antwort bleibt Ja oder Nein, eine Änderung geschieht nur durch eine neue Prüfung. So kann über
--    die Nachtragsspalte (new_note) kein Gesundheitstext an der Selbstauskunft entstehen.
-- 4. Wächter an resource_release (INSERT): hat die Vorlage des Prüflaufs ein Selbstauskunftselement, setzt die Freigabe die Antwort JA voraus; bei NEIN oder fehlender Antwort wird
--    keine Freigabe geschrieben. Freigaben zu Prüfläufen ohne Selbstauskunftselement (Altbestand, Fixtures, freie Prüfverweise) bleiben gültig.
-- Der Qualifikationsabgleich (freigegebene, gültige Revision; Nummer in resource_release.qualification_revision) besteht seit WP-M3-08 und wird beim Abschluss und bei der Freigabe angewendet.
SET search_path = taktovia, public;

ALTER TABLE checklist_step DROP CONSTRAINT checklist_step_answer_kind_check;
ALTER TABLE checklist_step ADD CONSTRAINT checklist_step_answer_kind_check CHECK (answer_kind IN ('OK_DEFECT_NA', 'NUMBER', 'TEXT_SHORT', 'ODOMETER_KM', 'ENERGY_PCT', 'VEHICLE_IDENTITY', 'READINESS_YES_NO'));

ALTER TABLE inspection_answer DROP CONSTRAINT inspection_answer_answer_check;
ALTER TABLE inspection_answer ADD CONSTRAINT inspection_answer_answer_check CHECK (answer IN ('OK', 'DEFECT', 'NOT_APPLICABLE', 'NOT_CHECKABLE', 'JA', 'NEIN'));
ALTER TABLE inspection_answer ADD CONSTRAINT inspection_answer_selfdeclaration_no_text CHECK (
  answer NOT IN ('JA', 'NEIN') OR (note IS NULL AND number_value IS NULL AND text_value IS NULL AND plausibility IS NULL AND previous_value IS NULL
                                    AND justification_reason IS NULL AND justified_by IS NULL AND justified_at IS NULL));

CREATE FUNCTION inspection_answer_readiness_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE k text;
BEGIN
  SELECT s.answer_kind INTO k FROM inspection_run r
    JOIN checklist_step s ON s.tenant_id = r.tenant_id AND s.template_revision_id = r.template_revision_id AND s.step_code = NEW.step_code
   WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.inspection_id;
  IF NEW.answer IN ('JA', 'NEIN') AND k IS DISTINCT FROM 'READINESS_YES_NO' THEN
    RAISE EXCEPTION 'answers JA and NEIN belong to the readiness self-declaration step only' USING ERRCODE = '23514';
  END IF;
  IF k = 'READINESS_YES_NO' AND NEW.answer NOT IN ('JA', 'NEIN') THEN
    RAISE EXCEPTION 'the readiness self-declaration step accepts JA or NEIN only' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER inspection_answer_readiness_guard BEFORE INSERT OR UPDATE ON inspection_answer FOR EACH ROW EXECUTE FUNCTION inspection_answer_readiness_guard();

CREATE FUNCTION inspection_amendment_readiness_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM inspection_answer a WHERE a.tenant_id = NEW.tenant_id AND a.id = NEW.answer_id AND a.answer IN ('JA', 'NEIN')) THEN
    RAISE EXCEPTION 'the readiness self-declaration cannot be amended; a new inspection replaces it' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER inspection_amendment_readiness_guard BEFORE INSERT ON inspection_amendment FOR EACH ROW EXECUTE FUNCTION inspection_amendment_readiness_guard();

CREATE FUNCTION resource_release_readiness_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM inspection_run r JOIN checklist_step s ON s.tenant_id = r.tenant_id AND s.template_revision_id = r.template_revision_id AND s.answer_kind = 'READINESS_YES_NO'
              WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.inspection_ref)
     AND NOT EXISTS (SELECT 1 FROM inspection_run r JOIN checklist_step s ON s.tenant_id = r.tenant_id AND s.template_revision_id = r.template_revision_id AND s.answer_kind = 'READINESS_YES_NO'
                       JOIN inspection_answer a ON a.tenant_id = r.tenant_id AND a.inspection_id = r.id AND a.step_code = s.step_code AND a.answer = 'JA'
                      WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.inspection_ref) THEN
    RAISE EXCEPTION 'no resource release without the confirmation JA of the readiness self-declaration' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER resource_release_readiness_guard BEFORE INSERT ON resource_release FOR EACH ROW EXECUTE FUNCTION resource_release_readiness_guard();
