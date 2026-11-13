-- WP-M4-52 / LH-I.6-KPI-006: Zeitfakten je Fahrgastfahrt (Buchung) und Stopp (Abholung, Ankunft) in fünf getrennten Zeitarten.
-- planned (Planrevision), announced (dem Fahrgast mitgeteilt; Definition OP 28 offen), promised (Zusageversion, Zielzeit = Ende des Zusagefensters), forecast (Prognoseversion), actual (Ist, Geräte- und Serverzeit getrennt).
-- Append-only: neue Werte sind neue Zeilen (UPDATE und DELETE weist ein Trigger ab, die Runtime-Rolle hat nur SELECT und INSERT). Je (Fahrt, Stopp, Zeitart, Versionsschlüssel) genau eine Zeile.
-- Kein Fremdschlüssel auf booking (anderes Modul; die Zusage kommt über Ereignis bzw. Leseport), keine Fahrgast-Klartextdaten.
SET search_path = taktovia, public;

CREATE TABLE time_fact (
  tenant_id       uuid        NOT NULL REFERENCES tenant(id),
  seq             bigint      GENERATED ALWAYS AS IDENTITY,
  booking_id      uuid        NOT NULL,
  stop_event      text        NOT NULL CHECK (stop_event IN ('PICKUP', 'ARRIVAL')),
  time_kind       text        NOT NULL CHECK (time_kind IN ('PLANNED', 'ANNOUNCED', 'PROMISED', 'FORECAST', 'ACTUAL')),
  value_at        timestamptz NOT NULL,
  window_earliest timestamptz,
  source          text        NOT NULL CHECK (source ~ '^[A-Z][A-Z0-9_]*$'),
  source_ref      uuid        NOT NULL,
  route_stop_id   uuid,
  version_key     text        NOT NULL CHECK (length(version_key) BETWEEN 1 AND 200),
  promise_version bigint      CHECK (promise_version >= 1),
  plan_revision   bigint      CHECK (plan_revision >= 1),
  forecast_version bigint     CHECK (forecast_version >= 1),
  device_time     timestamptz,
  server_time     timestamptz,
  offline_recorded boolean    NOT NULL DEFAULT false,
  recorded_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, seq),
  CONSTRAINT time_fact_version_unique UNIQUE (tenant_id, booking_id, stop_event, time_kind, version_key),
  CONSTRAINT time_fact_promised_version CHECK (time_kind <> 'PROMISED' OR promise_version IS NOT NULL),
  CONSTRAINT time_fact_planned_revision CHECK (time_kind <> 'PLANNED' OR plan_revision IS NOT NULL),
  CONSTRAINT time_fact_forecast_version CHECK (time_kind <> 'FORECAST' OR forecast_version IS NOT NULL),
  CONSTRAINT time_fact_version_kind CHECK ((promise_version IS NULL OR time_kind IN ('PROMISED', 'ANNOUNCED')) AND (plan_revision IS NULL OR time_kind = 'PLANNED') AND (forecast_version IS NULL OR time_kind = 'FORECAST')),
  CONSTRAINT time_fact_window CHECK (window_earliest IS NULL OR (time_kind = 'PROMISED' AND window_earliest <= value_at)),
  CONSTRAINT time_fact_actual_times CHECK (
    (time_kind = 'ACTUAL' AND server_time IS NOT NULL AND (NOT offline_recorded OR device_time IS NOT NULL))
    OR (time_kind <> 'ACTUAL' AND device_time IS NULL AND server_time IS NULL AND NOT offline_recorded))
);
CREATE INDEX time_fact_by_booking ON time_fact (tenant_id, booking_id, stop_event, time_kind);

ALTER TABLE time_fact ENABLE ROW LEVEL SECURITY;
ALTER TABLE time_fact FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON time_fact USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER time_fact_append_only BEFORE UPDATE OR DELETE ON time_fact FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- Verspätung = Ist minus Zielzeit der gebundenen Zusageversion (höchste Zusageversion; Planrevision, Prognose und Ankündigung gehen nie ein).
-- Fehlt Zusage oder Ist-Zeit, ist der Wert NULL mit Grund (nie 0). Mehrere Ist-Zeiten: die früheste. SECURITY INVOKER: die RLS des Aufrufers gilt.
CREATE FUNCTION time_fact_delay(p_booking uuid) RETURNS TABLE (stop_event text, promise_version bigint, target_at timestamptz, actual_at timestamptz, delay_seconds bigint, not_assessable_reason text)
LANGUAGE sql STABLE SECURITY INVOKER SET search_path = taktovia, pg_catalog AS $$
  SELECT k.stop_event, p.promise_version, p.value_at, a.value_at,
         CASE WHEN p.seq IS NOT NULL AND a.seq IS NOT NULL THEN round(extract(epoch FROM (a.value_at - p.value_at)))::bigint END,
         CASE WHEN p.seq IS NULL THEN 'NO_PROMISE' WHEN a.seq IS NULL THEN 'NO_ACTUAL' END
  FROM (SELECT DISTINCT f.stop_event FROM time_fact f WHERE f.booking_id = p_booking) k
  LEFT JOIN LATERAL (SELECT f.seq, f.promise_version, f.value_at FROM time_fact f
                     WHERE f.booking_id = p_booking AND f.stop_event = k.stop_event AND f.time_kind = 'PROMISED'
                     ORDER BY f.promise_version DESC, f.seq DESC LIMIT 1) p ON true
  LEFT JOIN LATERAL (SELECT f.seq, f.value_at FROM time_fact f
                     WHERE f.booking_id = p_booking AND f.stop_event = k.stop_event AND f.time_kind = 'ACTUAL'
                     ORDER BY f.value_at, f.seq LIMIT 1) a ON true
  ORDER BY k.stop_event
$$;
