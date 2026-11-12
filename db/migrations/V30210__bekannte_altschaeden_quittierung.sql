-- WP-M3-21 / LH-6.21-CHK-012, LH-6.22-DMG-012: bekannte Altschäden anzeigen und quittieren.
-- Additiv zu V3020 (known_defect_acknowledgement besteht dort, unveränderlich, UNIQUE je (Mandant, Prüflauf, Schadenakte)):
-- 1. Stand der Schadenakte zum Quittierungszeitpunkt (Revision und Status) wird von der Datenbank selbst eingetragen, nie vom Client.
-- 2. Eine Quittierung gilt nur für einen offenen Prüflauf und eine Akte desselben Fahrzeugs; sie ändert die Akte nicht und legt keine Akte an.
-- 3. Ein Prüflauf der Art PRE_SHIFT / VEHICLE_CHANGE lässt sich erst abschließen, wenn jeder offene Altschaden des Fahrzeugs quittiert ist.
-- Es gibt bewusst keine Spalte für Verursacher, Schuld oder Bewertung des Fahrers (DMG-012, Kap. 04: keine automatische Schuldzuweisung).
SET search_path = taktovia, public;

ALTER TABLE known_defect_acknowledgement
  ADD COLUMN defect_revision bigint NOT NULL DEFAULT 1 CHECK (defect_revision >= 1),
  ADD COLUMN defect_status   text   NOT NULL DEFAULT 'NEW' CHECK (defect_status IN ('NEW', 'REVIEWED', 'IN_PROGRESS', 'REPAIR', 'CLOSED', 'DISMISSED'));
ALTER TABLE known_defect_acknowledgement ALTER COLUMN defect_revision DROP DEFAULT, ALTER COLUMN defect_status DROP DEFAULT;

-- Altschäden eines Prüflaufs (Arbeitsannahme, Detailvertrag DMG-012): alle offenen Akten (Status nicht CLOSED/DISMISSED) desselben Fahrzeugs, die beim Start des Laufs schon bestanden
-- und nicht aus diesem Lauf stammen. Die Funktion läuft mit den Rechten des Aufrufers (RLS gilt); sie ist die einzige Quelle der Regel für Anzeige, Quittierung und Abschlusssperre.
CREATE FUNCTION inspection_known_defects(p_tenant uuid, p_inspection uuid) RETURNS SETOF uuid LANGUAGE sql STABLE SET search_path = taktovia, pg_catalog AS $$
  SELECT d.id FROM inspection_run i
    JOIN defect_case d ON d.tenant_id = i.tenant_id AND d.vehicle_id = i.vehicle_id
   WHERE i.tenant_id = p_tenant AND i.id = p_inspection
     AND d.status NOT IN ('CLOSED', 'DISMISSED')
     AND d.created_at <= i.created_at
     AND d.source_inspection_id IS DISTINCT FROM i.id
$$;

CREATE FUNCTION known_defect_acknowledgement_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE i_vehicle uuid; i_status text; d_vehicle uuid; d_revision bigint; d_status text;
BEGIN
  SELECT vehicle_id, status INTO i_vehicle, i_status FROM inspection_run WHERE tenant_id = NEW.tenant_id AND id = NEW.inspection_id;
  IF i_status IS DISTINCT FROM 'IN_PROGRESS' THEN
    RAISE EXCEPTION 'known_defect_acknowledgement: nur fuer einen offenen Pruefeinlauf' USING ERRCODE = '23514';
  END IF;
  -- FOR SHARE: eine gleichzeitige Statusaenderung der Akte wartet; Revision und Status sind der Stand zum Quittierungszeitpunkt
  SELECT vehicle_id, revision, status INTO d_vehicle, d_revision, d_status FROM defect_case WHERE tenant_id = NEW.tenant_id AND id = NEW.defect_id FOR SHARE;
  IF d_vehicle IS DISTINCT FROM i_vehicle THEN
    RAISE EXCEPTION 'known_defect_acknowledgement: die Akte gehoert nicht zum Fahrzeug des Pruefeinlaufs' USING ERRCODE = '23514';
  END IF;
  NEW.defect_revision := d_revision;
  NEW.defect_status := d_status;
  RETURN NEW;
END $$;
CREATE TRIGGER known_defect_acknowledgement_guard BEFORE INSERT ON known_defect_acknowledgement FOR EACH ROW EXECUTE FUNCTION known_defect_acknowledgement_guard();

CREATE FUNCTION inspection_known_defects_completion_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF NEW.kind IN ('PRE_SHIFT', 'VEHICLE_CHANGE') AND EXISTS (
       SELECT 1 FROM inspection_known_defects(NEW.tenant_id, NEW.id) k
        WHERE NOT EXISTS (SELECT 1 FROM known_defect_acknowledgement a WHERE a.tenant_id = NEW.tenant_id AND a.inspection_id = NEW.id AND a.defect_id = k)) THEN
    RAISE EXCEPTION 'KNOWN_DEFECTS_UNACKNOWLEDGED: offene Altschaeden des Fahrzeugs sind nicht quittiert' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER inspection_known_defects_completion_guard BEFORE UPDATE OF status ON inspection_run FOR EACH ROW
  WHEN (OLD.status = 'IN_PROGRESS' AND NEW.status = 'COMPLETED') EXECUTE FUNCTION inspection_known_defects_completion_guard();
