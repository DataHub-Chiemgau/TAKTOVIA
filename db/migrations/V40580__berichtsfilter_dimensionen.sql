-- WP-M4-58 / LH-6.14-ANA-003, LH-I.6-KPI-011: Filterdimensionen der Berichte ohne Mandantenüberschreitung (Modul analytics).
-- 1. Dimensionsspalten in den Faktentabellen: Betreiber (operator_id), Region (region_id), Bediengebiet (service_area_id, stabiler Gebietsschlüssel), je Buchung das abholende Fahrzeug (vehicle_id) und sein
--    Fahrzeugtyp (vehicle_type, Quelle vehicle.vehicle_type, Entscheidung zu KPI-011 „Fahrzeugtyp“). Betreiber und Region stehen zum Ereigniszeitpunkt fest (Dienst zum Zeitpunkt der Projektion), das Gebiet kommt aus dem
--    Ereignis (`service_area_id`) oder, wenn der Dienst zum Ereigniszeitpunkt genau ein Gebiet hatte, daraus. Die Spalten sind abgeleitet und werden beim Wiederaufbau der Fakten (FactBase.rebuild) neu gefüllt;
--    bestehende Zeilen bleiben gültig (NULL = unbekannt, ein Filter auf die Dimension schließt sie aus). Der Mandant ist keine Dimension: tenant_id steht in jedem Index und jeder RLS-Policy an erster Stelle.
-- 2. Ortsstunde `report_hour` (DST-sicher über die IANA-Zone) und `funnel_counts_filtered`: der Nachfragetrichter mit allen Dimensionen und der Dienstscope-Grenze in einem Statement (ein Lesestand).
SET search_path = taktovia, public;

ALTER TABLE fact_attempt ADD COLUMN operator_id uuid, ADD COLUMN region_id uuid, ADD COLUMN service_area_id uuid;
ALTER TABLE fact_offer   ADD COLUMN operator_id uuid, ADD COLUMN region_id uuid, ADD COLUMN service_area_id uuid;
ALTER TABLE fact_booking ADD COLUMN operator_id uuid, ADD COLUMN region_id uuid, ADD COLUMN service_area_id uuid, ADD COLUMN vehicle_id uuid,
  ADD COLUMN vehicle_type text CHECK (vehicle_type IS NULL OR vehicle_type ~ '^[A-Z][A-Z0-9_]{1,39}$');

CREATE INDEX fact_attempt_by_operator ON fact_attempt (tenant_id, operator_id, requested_at);
CREATE INDEX fact_attempt_by_region   ON fact_attempt (tenant_id, region_id, requested_at);
CREATE INDEX fact_attempt_by_area     ON fact_attempt (tenant_id, service_area_id, requested_at);
CREATE INDEX fact_offer_by_region     ON fact_offer   (tenant_id, region_id, created_at);
CREATE INDEX fact_offer_by_operator   ON fact_offer   (tenant_id, operator_id, created_at);
CREATE INDEX fact_booking_by_region   ON fact_booking (tenant_id, region_id, confirmed_at);
CREATE INDEX fact_booking_by_operator ON fact_booking (tenant_id, operator_id, confirmed_at);
CREATE INDEX fact_booking_by_vehicle  ON fact_booking (tenant_id, vehicle_id, confirmed_at);

-- Ortsstunde (0..23) eines Zeitpunkts in der Berichtszone. Am Herbstumstellungstag durchläuft die Ortsstunde 2 zwei Mal (CEST und CET), beide Durchläufe gehören zur Stunde 2; am Frühjahrstag fehlt die Stunde 2.
CREATE FUNCTION report_hour(p_at timestamptz, p_zone text) RETURNS integer LANGUAGE sql STABLE SET search_path = taktovia, pg_catalog AS $$
  SELECT extract(hour FROM p_at AT TIME ZONE p_zone)::integer
$$;

-- Nachfragetrichter mit Berichtsdimensionen (Erweiterung von funnel_counts, V40510). Zusätzlich zu Dienst und Kanal: Betreiber, Region, Bediengebiet, Ortsstunde (je Stufe in der Zeit der Stufe: Anfrage requested_at,
-- Angebot created_at, Bestätigung confirmed_at, Abschluss alighted_at, Zahlungsfehlschlag payment_failed_at, Storno cancelled_at) und die Berechtigungsgrenze p_allowed (Dienstscope; NULL = alle Dienste).
-- Die Spalten *_all gelten ohne Dimensionsfilter, aber innerhalb der Berechtigungsgrenze: eine Gegenprobe gegen Differenzbildung rechnet der Aufrufer über dieselbe Funktion ohne die jeweilige Dimension.
CREATE FUNCTION funnel_counts_filtered(p_from timestamptz, p_to timestamptz, p_service uuid, p_channel text, p_operator uuid, p_region uuid, p_area uuid, p_hour integer, p_zone text, p_allowed uuid[])
RETURNS TABLE (requests bigint, technical bigint, rejected bigint, attempts_with_offer bigint, attempts_non_technical bigint, requests_all bigint,
               offers bigint, offers_not_booked bigint, offers_all bigint,
               confirmed bigint, confirmed_all bigint, unlinked_bookings bigint, completed bigint, payment_failed bigint,
               cancel_passenger bigint, cancel_operator bigint, cancel_other bigint, conf_cancel_passenger bigint, conf_cancel_operator bigint,
               processed_until timestamptz, incomplete boolean, gap_count integer, event_count bigint, facts_total bigint, has_data boolean)
LANGUAGE sql STABLE SET search_path = taktovia, pg_catalog AS $$
  WITH wm AS (SELECT w.processed_until, w.completeness, w.gap_count, w.event_count FROM dataset_watermark w WHERE w.source IN ('ATTEMPT', 'OFFER', 'BOOKING')),
  att_all AS (SELECT a.attempt_id, a.service_id, a.channel, a.outcome, a.operator_id, a.region_id, a.service_area_id, a.requested_at FROM fact_attempt a
              WHERE NOT a.is_test AND a.requested_at >= p_from AND a.requested_at < p_to AND (p_allowed IS NULL OR a.service_id = ANY (p_allowed))),
  att AS (SELECT a.* FROM att_all a WHERE (p_service IS NULL OR a.service_id = p_service) AND (p_channel IS NULL OR a.channel = p_channel) AND (p_operator IS NULL OR a.operator_id = p_operator)
            AND (p_region IS NULL OR a.region_id = p_region) AND (p_area IS NULL OR a.service_area_id = p_area) AND (p_hour IS NULL OR report_hour(a.requested_at, p_zone) = p_hour)),
  off_all AS (SELECT o.offer_id, o.service_id, a.channel, o.operator_id, o.region_id, o.service_area_id, o.created_at FROM fact_offer o LEFT JOIN fact_attempt a ON a.attempt_id = o.attempt_id
              WHERE a.is_test IS NOT TRUE AND o.created_at >= p_from AND o.created_at < p_to AND (p_allowed IS NULL OR o.service_id = ANY (p_allowed))),
  off AS (SELECT o.* FROM off_all o WHERE (p_service IS NULL OR o.service_id = p_service) AND (p_channel IS NULL OR o.channel = p_channel) AND (p_operator IS NULL OR o.operator_id = p_operator)
            AND (p_region IS NULL OR o.region_id = p_region) AND (p_area IS NULL OR o.service_area_id = p_area) AND (p_hour IS NULL OR report_hour(o.created_at, p_zone) = p_hour)),
  bk AS (SELECT b.* FROM fact_booking b WHERE (p_allowed IS NULL OR b.service_id = ANY (p_allowed))),
  conf_all AS (SELECT b.* FROM bk b WHERE b.confirmed_at >= p_from AND b.confirmed_at < p_to),
  bkf AS (SELECT b.* FROM bk b WHERE (p_service IS NULL OR b.service_id = p_service) AND (p_operator IS NULL OR b.operator_id = p_operator)
            AND (p_region IS NULL OR b.region_id = p_region) AND (p_area IS NULL OR b.service_area_id = p_area)),
  conf AS (SELECT b.* FROM bkf b WHERE b.confirmed_at >= p_from AND b.confirmed_at < p_to AND (p_hour IS NULL OR report_hour(b.confirmed_at, p_zone) = p_hour))
  SELECT
    (SELECT count(*) FROM att), (SELECT count(*) FILTER (WHERE outcome = 'TECHNICAL_ERROR') FROM att), (SELECT count(*) FILTER (WHERE outcome = 'REJECTED') FROM att),
    (SELECT count(*) FROM att WHERE outcome <> 'TECHNICAL_ERROR' AND EXISTS (SELECT 1 FROM fact_offer o WHERE o.attempt_id = att.attempt_id)),
    (SELECT count(*) FROM att WHERE outcome <> 'TECHNICAL_ERROR'), (SELECT count(*) FROM att_all),
    (SELECT count(*) FROM off),
    (SELECT count(*) FROM off WHERE NOT EXISTS (SELECT 1 FROM fact_booking b WHERE b.offer_id = off.offer_id AND b.confirmed_at IS NOT NULL)),
    (SELECT count(*) FROM off_all),
    (SELECT count(*) FROM conf), (SELECT count(*) FROM conf_all), (SELECT count(*) FILTER (WHERE offer_id IS NULL) FROM conf),
    (SELECT count(*) FROM bkf WHERE state = 'COMPLETED' AND alighted_at >= p_from AND alighted_at < p_to AND (p_hour IS NULL OR report_hour(alighted_at, p_zone) = p_hour)),
    (SELECT count(*) FROM bkf WHERE payment_failed_at >= p_from AND payment_failed_at < p_to AND (p_hour IS NULL OR report_hour(payment_failed_at, p_zone) = p_hour)),
    (SELECT count(*) FROM bkf WHERE state = 'CANCELED' AND payment_failed_at IS NULL AND cancelled_at >= p_from AND cancelled_at < p_to AND (p_hour IS NULL OR report_hour(cancelled_at, p_zone) = p_hour)
        AND cause_class = 'FAHRGAST' AND cancel_reason = 'PASSENGER_REQUEST'),
    (SELECT count(*) FROM bkf WHERE state = 'CANCELED' AND payment_failed_at IS NULL AND cancelled_at >= p_from AND cancelled_at < p_to AND (p_hour IS NULL OR report_hour(cancelled_at, p_zone) = p_hour)
        AND cause_class = 'BETREIBER'),
    (SELECT count(*) FROM bkf WHERE state = 'CANCELED' AND payment_failed_at IS NULL AND cancelled_at >= p_from AND cancelled_at < p_to AND (p_hour IS NULL OR report_hour(cancelled_at, p_zone) = p_hour)
        AND NOT (cause_class = 'FAHRGAST' AND cancel_reason = 'PASSENGER_REQUEST') AND cause_class IS DISTINCT FROM 'BETREIBER'),
    (SELECT count(*) FROM conf WHERE state = 'CANCELED' AND payment_failed_at IS NULL AND cause_class = 'FAHRGAST' AND cancel_reason = 'PASSENGER_REQUEST'),
    (SELECT count(*) FROM conf WHERE state = 'CANCELED' AND payment_failed_at IS NULL AND cause_class = 'BETREIBER'),
    (SELECT min(processed_until) FROM wm), coalesce((SELECT bool_or(completeness = 'INCOMPLETE') FROM wm), false), coalesce((SELECT sum(gap_count) FROM wm), 0)::integer,
    coalesce((SELECT sum(event_count) FROM wm), 0)::bigint,
    (SELECT (SELECT count(*) FROM fact_attempt) + (SELECT count(*) FROM fact_offer) + (SELECT count(*) FROM fact_booking)),
    EXISTS (SELECT 1 FROM wm)
$$;
