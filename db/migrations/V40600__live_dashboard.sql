-- WP-M4-60 / LH-I.6-KPI-014: Live-Dashboard fuer den laufenden Betrieb (Modul analytics). Lesefunktion ueber die Faktenbasis (Projektion aus den Ereignissen) und die Dienst-/Schichtstammdaten;
-- keine neue Tabelle: die Projektion ist `fact_*` (RLS, tenant_id, nur aus Ereignissen abgeleitet), der Datenstand je Quelle `dataset_watermark`. Die Funktion laeuft als Aufrufer (RLS gilt).
-- Planungsannahmen (OP 32, D-05; im Vertrag LH-I.6-KPI-014 dokumentiert, per D-05 freizugeben):
--   offene Anfrage       = Angebot (fact_offer) mit expires_at > Auswertungszeit, nicht Testanfrage, ohne bestaetigte Buchung auf dieses Angebot;
--   verfuegbare Kapazitaet = Sitzplaetze und Rollstuhlplaetze der Fahrzeuge mit einer laufenden Schicht (state STARTED, starts_at <= jetzt < ends_at), die nicht gesperrt sind;
--   kritische Verspaetung = bestaetigte, noch nicht eingestiegene Buchung, deren zugesagter spaetester Abholzeitpunkt plus on_time_pickup_tolerance_min vor jetzt liegt (hoechstens 12 Stunden zurueck);
--   Fahrzeugausfall      = Fahrzeug mit mindestens einer offenen Sperre (BLOCKED ohne BLOCK_LIFTED derselben ref_id); `long_outages` davon mindestens vehicle_unavailability_alert_min Minuten.
-- Dienstberechtigung: p_allowed (uuid[]; NULL = ganzer Mandant). Fahrzeuge zaehlen, wenn sie zum Zeitpunkt einem berechtigten Dienst zugeordnet sind (vehicle_pool_assignment).
-- Jeder Wert hat seinen eigenen Datenstand (Wasserzeichen der speisenden Quellen, Minimum); ohne Ereignis der Quelle ist der Datenstand NULL und der Wert NULL (nie 0).
SET search_path = taktovia, public;

CREATE FUNCTION live_dashboard(p_now timestamptz, p_allowed uuid[], p_tolerance_min integer, p_alert_min integer)
RETURNS TABLE (open_requests bigint, open_as_of timestamptz,
               capacity_vehicles bigint, capacity_seats bigint, capacity_wheelchair bigint, capacity_as_of timestamptz,
               critical_delays bigint, delays_as_of timestamptz,
               outage_vehicles bigint, long_outages bigint, outage_as_of timestamptz)
LANGUAGE sql STABLE SET search_path = taktovia, pg_catalog AS $$
  WITH wm AS (SELECT w.source, w.processed_until FROM dataset_watermark w),
  open_src AS (SELECT CASE WHEN count(*) = 2 THEN min(processed_until) END AS at FROM wm WHERE source IN ('ATTEMPT', 'OFFER') AND processed_until IS NOT NULL),
  delay_src AS (SELECT CASE WHEN count(*) = 2 THEN min(processed_until) END AS at FROM wm WHERE source IN ('BOOKING', 'DRIVER_EVENT') AND processed_until IS NOT NULL),
  veh_src AS (SELECT max(processed_until) AS at FROM wm WHERE source = 'VEHICLE_STATE'),
  blocks AS (SELECT s.vehicle_id, s.ref_id, min(s.effective_at) AS since FROM fact_vehicle_state s
             WHERE s.kind = 'BLOCKED' AND NOT EXISTS (SELECT 1 FROM fact_vehicle_state l WHERE l.kind = 'BLOCK_LIFTED' AND l.ref_id = s.ref_id AND l.vehicle_id = s.vehicle_id)
             GROUP BY s.vehicle_id, s.ref_id),
  vis AS (SELECT v.id AS vehicle_id, v.capacity_config FROM vehicle v
          WHERE p_allowed IS NULL OR EXISTS (SELECT 1 FROM vehicle_pool_assignment a WHERE a.vehicle_id = v.id AND a.service_id = ANY (p_allowed) AND a.valid_from <= p_now AND (a.valid_to IS NULL OR a.valid_to > p_now))),
  blocked AS (SELECT b.vehicle_id, min(b.since) AS since FROM blocks b JOIN vis ON vis.vehicle_id = b.vehicle_id GROUP BY b.vehicle_id),
  running AS (SELECT DISTINCT s.vehicle_id FROM staff_shift s WHERE s.state = 'STARTED' AND s.vehicle_id IS NOT NULL AND s.starts_at <= p_now AND s.ends_at > p_now),
  cap AS (SELECT vis.capacity_config FROM vis JOIN running r ON r.vehicle_id = vis.vehicle_id WHERE NOT EXISTS (SELECT 1 FROM blocked b WHERE b.vehicle_id = vis.vehicle_id))
  SELECT
    CASE WHEN (SELECT at FROM open_src) IS NULL THEN NULL ELSE
      (SELECT count(*) FROM fact_offer o LEFT JOIN fact_attempt a ON a.attempt_id = o.attempt_id
        WHERE a.is_test IS NOT TRUE AND o.expires_at > p_now AND (p_allowed IS NULL OR o.service_id = ANY (p_allowed))
          AND NOT EXISTS (SELECT 1 FROM fact_booking b WHERE b.offer_id = o.offer_id AND b.confirmed_at IS NOT NULL)) END,
    (SELECT at FROM open_src),
    CASE WHEN (SELECT at FROM veh_src) IS NULL THEN NULL ELSE (SELECT count(*) FROM cap) END,
    CASE WHEN (SELECT at FROM veh_src) IS NULL THEN NULL ELSE (SELECT coalesce(sum((capacity_config ->> 'seats')::bigint), 0) FROM cap) END,
    CASE WHEN (SELECT at FROM veh_src) IS NULL THEN NULL ELSE (SELECT coalesce(sum((capacity_config ->> 'wheelchair')::bigint), 0) FROM cap) END,
    (SELECT at FROM veh_src),
    CASE WHEN (SELECT at FROM delay_src) IS NULL THEN NULL ELSE
      (SELECT count(*) FROM fact_booking b
        WHERE b.state = 'CONFIRMED' AND b.confirmed_at IS NOT NULL AND b.boarded_at IS NULL AND b.pickup_latest IS NOT NULL
          AND (p_allowed IS NULL OR b.service_id = ANY (p_allowed))
          AND b.pickup_latest + make_interval(mins => p_tolerance_min) < p_now AND b.pickup_latest >= p_now - interval '12 hours') END,
    (SELECT at FROM delay_src),
    CASE WHEN (SELECT at FROM veh_src) IS NULL THEN NULL ELSE (SELECT count(*) FROM blocked) END,
    CASE WHEN (SELECT at FROM veh_src) IS NULL THEN NULL ELSE (SELECT count(*) FROM blocked WHERE since + make_interval(mins => p_alert_min) <= p_now) END,
    (SELECT at FROM veh_src)
$$;
