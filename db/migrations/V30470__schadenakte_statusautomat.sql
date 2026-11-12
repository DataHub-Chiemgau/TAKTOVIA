-- WP-M3-47 / LH-6.22-DMG-007: Statusautomat der Schadenakte. Die Datenbank erzwingt die Übergangstabelle aus docs/umsetzungsplan/vertraege/zustaende/defect_case.md auch bei direktem
-- SQL (Hauptfolge NEW -> REVIEWED -> IN_PROGRESS -> REPAIR -> CLOSED, Abkürzungen, Endzustände CLOSED und DISMISSED, dokumentierte Wiedereröffnung), jede Statusänderung zählt die
-- Revision, und ein sicherheitskritischer Fall wird nur mit Freigabeentscheidung abgeschlossen. Rollen, Begründungspflicht und Vorbedingungen prüft die Anwendung (Detailvertrag m3-schadenakte.md).
SET search_path = taktovia, public;

ALTER TABLE defect_case ADD COLUMN reopen_count int NOT NULL DEFAULT 0 CHECK (reopen_count >= 0);
-- Statushistorie (append-only, V3020): Kennzeichen der Wiedereröffnung
ALTER TABLE defect_status_history ADD COLUMN is_reopening boolean NOT NULL DEFAULT false;
CREATE INDEX defect_status_history_by_defect ON defect_status_history (tenant_id, defect_id, created_at);

CREATE FUNCTION defect_case_status_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE ok boolean;
BEGIN
  IF NEW.status IS NOT DISTINCT FROM OLD.status THEN
    -- ohne Statuswechsel ändert sich die Wiedereröffnungszahl nicht
    IF NEW.reopen_count IS DISTINCT FROM OLD.reopen_count THEN RAISE EXCEPTION 'defect_case: Wiedereroeffnungszaehler nur mit Statuswechsel' USING ERRCODE = '23514'; END IF;
    RETURN NEW;
  END IF;
  ok := (OLD.status, NEW.status) IN (
    ('NEW', 'REVIEWED'), ('NEW', 'DISMISSED'),
    ('REVIEWED', 'IN_PROGRESS'), ('REVIEWED', 'DISMISSED'), ('REVIEWED', 'CLOSED'),
    ('IN_PROGRESS', 'REPAIR'), ('IN_PROGRESS', 'CLOSED'), ('IN_PROGRESS', 'DISMISSED'),
    ('REPAIR', 'CLOSED'), ('REPAIR', 'IN_PROGRESS'));
  IF OLD.status IN ('CLOSED', 'DISMISSED') THEN
    -- aus einem Endzustand nur die dokumentierte Wiedereröffnung nach REVIEWED (Zähler +1)
    IF NEW.status <> 'REVIEWED' OR NEW.reopen_count <> OLD.reopen_count + 1 THEN
      RAISE EXCEPTION 'defect_case: aus dem Endzustand % nur dokumentierte Wiedereroeffnung nach REVIEWED', OLD.status USING ERRCODE = '23514';
    END IF;
  ELSIF NOT ok THEN
    RAISE EXCEPTION 'defect_case: Uebergang % -> % nicht erlaubt', OLD.status, NEW.status USING ERRCODE = '23514';
  ELSIF NEW.reopen_count IS DISTINCT FROM OLD.reopen_count THEN
    RAISE EXCEPTION 'defect_case: Wiedereroeffnungszaehler nur bei Wiedereroeffnung' USING ERRCODE = '23514';
  END IF;
  IF NEW.revision <> OLD.revision + 1 THEN RAISE EXCEPTION 'defect_case: Statuswechsel zaehlt die Revision' USING ERRCODE = '23514'; END IF;
  IF NEW.status = 'CLOSED' AND NEW.severity = 'SAFETY_CRITICAL' AND NOT EXISTS (
       SELECT 1 FROM vehicle_release_decision d JOIN repair_order r ON r.tenant_id = d.tenant_id AND r.id = d.repair_order_id
       WHERE r.tenant_id = NEW.tenant_id AND r.defect_id = NEW.id AND d.decision = 'RELEASED') THEN
    RAISE EXCEPTION 'defect_case: sicherheitskritischer Fall nur mit Freigabeentscheidung abschliessbar' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER defect_case_status_guard BEFORE UPDATE OF status, reopen_count ON defect_case FOR EACH ROW EXECUTE FUNCTION defect_case_status_guard();
