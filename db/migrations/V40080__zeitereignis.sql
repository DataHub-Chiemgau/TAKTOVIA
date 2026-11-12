-- WP-M4-08 / LH-6.24-TIM-001: Ist-Arbeitszeit als Folge unveränderlicher Zeitereignisse (Modul staff-time), unabhängig von Schichten.
-- Intervalle werden aus den Ereignissen abgeleitet, nicht gespeichert. Korrekturen (time_correction, TIM-007) und Zeitkonten (TIM-011) sind P2 (M7).
SET search_path = taktovia, public;

CREATE TABLE time_event (
  tenant_id          uuid        NOT NULL REFERENCES tenant(id),
  id                 uuid        NOT NULL,                    -- stabile Ereignis-ID, vom Erfasser vergeben; dedupliziert dauerhaft
  person_ref         text        NOT NULL CHECK (length(person_ref) BETWEEN 1 AND 200),   -- IdP-Subject der Person, deren Arbeitszeit das Ereignis beschreibt
  kind               text        NOT NULL CHECK (kind IN ('WORK_START', 'WORK_END', 'BREAK_START', 'BREAK_END')),
  occurred_at        timestamptz NOT NULL,                    -- Ereigniszeit (wann die Handlung stattfand)
  device_recorded_at timestamptz,                             -- Gerätezeit; wird nie als Serverzeit übernommen, bei Nacherfassung leer
  received_at        timestamptz NOT NULL DEFAULT clock_timestamp(),   -- Serverempfangszeit, setzt allein die Datenbank
  source             text        NOT NULL CHECK (source IN ('APP', 'WEB', 'BACKFILL')),   -- BACKFILL = Nacherfassung durch eine berechtigte Rolle
  recorded_by        text        NOT NULL CHECK (length(recorded_by) BETWEEN 1 AND 200), -- Urheber/Akteur (Subject)
  shift_id           uuid,                                    -- optionaler Bezug auf die geplante Schicht, keine Pflicht
  backfill_reason    text        CHECK (backfill_reason IS NULL OR length(backfill_reason) BETWEEN 3 AND 500),
  payload_sha256     text        NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),      -- Inhaltsprüfsumme: gleiche ID mit anderem Inhalt ist ein Konflikt
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, shift_id) REFERENCES staff_shift(tenant_id, id),
  CONSTRAINT time_event_backfill_has_reason CHECK ((source = 'BACKFILL') = (backfill_reason IS NOT NULL)),
  CONSTRAINT time_event_self_recorded CHECK (source = 'BACKFILL' OR recorded_by = person_ref),
  CONSTRAINT time_event_device_time CHECK (source = 'BACKFILL' OR device_recorded_at IS NOT NULL)
);
CREATE INDEX time_event_by_person ON time_event (tenant_id, person_ref, occurred_at, id);
CREATE INDEX time_event_by_shift ON time_event (tenant_id, shift_id, occurred_at) WHERE shift_id IS NOT NULL;
CREATE INDEX time_event_by_received ON time_event (tenant_id, received_at, id);

ALTER TABLE time_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE time_event FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON time_event USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON time_event FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change();
CREATE TRIGGER time_event_immutable BEFORE UPDATE OR DELETE ON time_event FOR EACH ROW EXECUTE FUNCTION taktovia.reject_mutation();
