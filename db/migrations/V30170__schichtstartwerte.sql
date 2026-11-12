-- WP-M3-17 / LH-6.21-CHK-008: Schichtstartwerte mit Plausibilitätsprüfung.
-- 1. Eingabetypen der Prüfelemente: ODOMETER_KM (Kilometerstand, ganze Zahl), ENERGY_PCT (Energie/Tank in Prozent 0 bis 100), VEHICLE_IDENTITY (Fahrzeugidentität als Eingabe, Abgleich
--    mit dem zugewiesenen Fahrzeug der Schicht). Zahlengrenzen (number_min/max) bleiben dem Typ NUMBER vorbehalten (checklist_step_number_only_for_number).
-- 2. vehicle.last_confirmed_odometer_km: letzter bestätigter Kilometerstand; die Fortschreibung geschieht beim Prüfabschluss unter der Fahrzeugsperre als bedingtes UPDATE und
--    fällt nie zurück (Wächter: nur steigend).
-- 3. inspection_answer: typisierte Werte (text_value für die Identität), Plausibilitätsstatus (PLAUSIBEL | UEBERPRUEFUNG_ERFORDERLICH | BEGRUENDET_FREIGEGEBEN; Arbeitsnamen laut
--    Detailvertrag), der Vorwert zum Zeitpunkt der Prüfung sowie Akteur, Zeit und Begründung der begründeten Freigabe. Mit dem Abschluss unveränderlich (inspection_answer_guard).
--    Eine begründete Freigabe trägt immer alle drei Angaben, die Begründung ist nicht leer.
SET search_path = taktovia, public;

ALTER TABLE checklist_step DROP CONSTRAINT checklist_step_answer_kind_check;
ALTER TABLE checklist_step ADD CONSTRAINT checklist_step_answer_kind_check CHECK (answer_kind IN ('OK_DEFECT_NA', 'NUMBER', 'TEXT_SHORT', 'ODOMETER_KM', 'ENERGY_PCT', 'VEHICLE_IDENTITY'));

ALTER TABLE vehicle
  ADD COLUMN last_confirmed_odometer_km bigint CHECK (last_confirmed_odometer_km IS NULL OR last_confirmed_odometer_km >= 0);

CREATE FUNCTION vehicle_odometer_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF OLD.last_confirmed_odometer_km IS NOT NULL AND (NEW.last_confirmed_odometer_km IS NULL OR NEW.last_confirmed_odometer_km < OLD.last_confirmed_odometer_km) THEN
    RAISE EXCEPTION 'last confirmed odometer may only increase' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER vehicle_odometer_guard BEFORE UPDATE OF last_confirmed_odometer_km ON vehicle FOR EACH ROW EXECUTE FUNCTION vehicle_odometer_guard();

ALTER TABLE inspection_answer
  ADD COLUMN text_value           text CHECK (text_value IS NULL OR length(text_value) BETWEEN 1 AND 100),
  ADD COLUMN plausibility         text CHECK (plausibility IS NULL OR plausibility IN ('PLAUSIBEL', 'UEBERPRUEFUNG_ERFORDERLICH', 'BEGRUENDET_FREIGEGEBEN')),
  ADD COLUMN previous_value       numeric,
  ADD COLUMN justification_reason text CHECK (justification_reason IS NULL OR length(btrim(justification_reason)) BETWEEN 1 AND 500),
  ADD COLUMN justified_by         text CHECK (justified_by IS NULL OR justified_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  ADD COLUMN justified_at         timestamptz,
  ADD CONSTRAINT inspection_answer_justification_complete CHECK (
    (plausibility = 'BEGRUENDET_FREIGEGEBEN') = (justification_reason IS NOT NULL AND justified_by IS NOT NULL AND justified_at IS NOT NULL)
    AND (plausibility = 'BEGRUENDET_FREIGEGEBEN' OR (justification_reason IS NULL AND justified_by IS NULL AND justified_at IS NULL)));
