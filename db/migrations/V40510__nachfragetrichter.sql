-- WP-M4-51 / LH-I.6-KPI-005, LH-6.14-ANA-001: Nachfragetrichter und getrennte Zaehlung der Buchungsstufen (Modul analytics).
-- Stufen: Anfrage, Angebot angezeigt (OP 27: Ereignis booking.offer_created), Buchung bestaetigt, Buchung abgeschlossen (OP 27: Fahrtabschluss/Ausstieg), dazu die Endzustaende Anfrage abgelehnt,
-- Angebot nicht gebucht, Zahlung fehlgeschlagen, Fahrgaststorno, Betreiberstorno. Alle Zaehlgroessen entstehen aus den bestehenden Fakten (fact_attempt, fact_offer, fact_booking) in EINEM Statement
-- (funnel_counts) und werden gegen das Ereignisprotokoll (analytics_event_inbox) abgeglichen (funnel_reconciliation, Differenz muss 0 sein). RLS gilt (Funktionen laufen als Aufrufer).
SET search_path = taktovia, public;

-- Zusatz an der abgeleiteten Tabelle fact_booking: Bezug zum Angebot (optionales Feld offer_id von booking.confirmed), Stornoinitiator, Zahlungsfehlschlag.
ALTER TABLE fact_booking
  ADD COLUMN offer_id            uuid,
  ADD COLUMN cancel_initiator    text CHECK (cancel_initiator IS NULL OR cancel_initiator IN ('PASSENGER', 'OPERATOR', 'SYSTEM')),
  ADD COLUMN payment_failed_at   timestamptz,
  ADD COLUMN payment_failure_code text CHECK (payment_failure_code IS NULL OR payment_failure_code ~ '^[A-Z][A-Z_]{1,39}$');
CREATE INDEX fact_booking_by_offer ON fact_booking (tenant_id, offer_id) WHERE offer_id IS NOT NULL;
CREATE INDEX fact_booking_by_confirmed ON fact_booking (tenant_id, confirmed_at);
CREATE INDEX fact_offer_by_created ON fact_offer (tenant_id, created_at);

-- Die vier Kennzahlen mit Stufenbezug sind ab diesem Paket ausfuehrbar (Seed fuer neue Mandanten; bereits angelegte Definitionen sind unveraenderlich und aendern sich nur ueber eine neue Version).
-- Seed der P1-Kennzahlen (Vorschlag des Plans, Offener Punkt 24; verbindliche Berichtsdefinitionen sind AG-Freigabe, D-05) für den Mandanten des Kontexts.
-- Wiederholbar: nur Kennzahlen ohne jede Revision werden angelegt. Ausführbar ist in diesem Stand, was die Faktenbasis der Versuche hergibt (executable); alle anderen Definitionen tragen ihren Grund.
CREATE OR REPLACE FUNCTION seed_metric_definitions() RETURNS integer LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE n integer;
BEGIN
  IF taktovia.current_tenant() IS NULL THEN RETURN 0; END IF;
  -- gleichzeitiger erster Zugriff: der zweite Aufruf wartet und sieht danach die Zeilen des ersten (eigenes Statement, eigener Lesestand)
  PERFORM pg_advisory_xact_lock(hashtextextended(taktovia.current_tenant()::text || ':metric-seed', 0));
  INSERT INTO metric_definition_revision (tenant_id, metric_id, version, name, value_kind, unit, numerator_definition, denominator_definition, exclusions, time_basis, period_basis,
                                          filter_dimensions, source, executable, not_executable_reason, state, approved_by)
  SELECT taktovia.current_tenant(), v.metric_id, 1, v.name, v.kind, v.unit, v.numerator, v.denominator, v.exclusions, v.time_basis, 'BUSINESS_DAY_LOCAL',
         v.dims, v.source, v.reason IS NULL, v.reason, 'APPROVED', 'system:seed (Vorschlag des Plans, Freigabe D-05 offen)'
  FROM (VALUES
    ('requests', 'Anfragen', 'COUNT', 'Anfragen',
     'Anzahl gültiger Anfrageversuche: je fachlich neuer Suche genau ein Versuch (attempt_id), unabhängig davon, ob ein Angebot entsteht.',
     'Keiner (Absolutzahl).', 'Testanfragen; technische Wiederholungen derselben Suche (gleicher Idempotenzschlüssel) zählen nicht erneut.',
     'Zeitpunkt der Anfrage (requested_at); Betriebstag in der reporting_timezone des Mandanten.', ARRAY['service_id', 'channel'], 'ATTEMPT', NULL),
    ('technical_request_errors', 'Technische Anfragefehler', 'RATIO', 'Anteil',
     'Anfrageversuche mit Ergebnis TECHNICAL_ERROR (technischer Fehler vor einem Angebot).',
     'Gültige Anfrageversuche (Kennzahl Anfragen).', 'Testanfragen; operative Ablehnungen zählen nicht als technischer Fehler.',
     'Zeitpunkt der Anfrage (requested_at); Betriebstag in der reporting_timezone des Mandanten.', ARRAY['service_id', 'channel'], 'ATTEMPT', NULL),
    ('capacity_rejections', 'Kapazitätsablehnungen', 'COUNT', 'Ablehnungen',
     'Abgelehnte Anfrageversuche, deren Primärgrund der Kennzahl CAPACITY im Ablehnungskatalog zugeordnet ist (keine Kapazität, kein Fahrzeug, keine Verbindung); Sekundärgründe zählen nicht zusätzlich.',
     'Bezugsgröße: Anfragen im selben Zeitraum und Filter (zur Einordnung, geht nicht in den Wert ein).', 'Testanfragen; technische Fehler (keine fachliche Ablehnung).',
     'Zeitpunkt der Anfrage (requested_at); Betriebstag in der reporting_timezone des Mandanten.', ARRAY['service_id', 'channel'], 'ATTEMPT', NULL),
    ('personnel_rejections', 'Personalmangelablehnungen', 'COUNT', 'Ablehnungen',
     'Abgelehnte Anfrageversuche mit Primärgrund der Kennzahl PERSONNEL (kein Fahrer, Schichtende); Sekundärgründe zählen nicht zusätzlich.',
     'Bezugsgröße: Anfragen im selben Zeitraum und Filter (zur Einordnung, geht nicht in den Wert ein).', 'Testanfragen; technische Fehler.',
     'Zeitpunkt der Anfrage (requested_at); Betriebstag in der reporting_timezone des Mandanten.', ARRAY['service_id', 'channel'], 'ATTEMPT', NULL),
    ('equipment_rejections', 'Ausstattungsablehnungen', 'COUNT', 'Ablehnungen',
     'Abgelehnte Anfrageversuche mit Primärgrund der Kennzahl EQUIPMENT (falsche oder fehlende Ausstattung, kein geeigneter Haltepunkt); Sekundärgründe zählen nicht zusätzlich.',
     'Bezugsgröße: Anfragen im selben Zeitraum und Filter (zur Einordnung, geht nicht in den Wert ein).', 'Testanfragen; technische Fehler.',
     'Zeitpunkt der Anfrage (requested_at); Betriebstag in der reporting_timezone des Mandanten.', ARRAY['service_id', 'channel'], 'ATTEMPT', NULL),
    ('area_rejections', 'Gebietsablehnungen', 'COUNT', 'Ablehnungen',
     'Abgelehnte Anfrageversuche mit Primärgrund der Kennzahl AREA (außerhalb des Bediengebiets, kein Haltepunkt in Reichweite); Sekundärgründe zählen nicht zusätzlich.',
     'Bezugsgröße: Anfragen im selben Zeitraum und Filter (zur Einordnung, geht nicht in den Wert ein).', 'Testanfragen; technische Fehler.',
     'Zeitpunkt der Anfrage (requested_at); Betriebstag in der reporting_timezone des Mandanten.', ARRAY['service_id', 'channel'], 'ATTEMPT', NULL),
    ('time_window_rejections', 'Zeitfensterablehnungen', 'COUNT', 'Ablehnungen',
     'Abgelehnte Anfrageversuche mit Primärgrund der Kennzahl TIME_WINDOW; ausgewiesen getrennt nach Betrieb geschlossen (TIME_OPENING) und Frist (TIME_DEADLINE: Mindestvorlauf, Maximalwartezeit, Vorlaufhorizont, kein Abholfenster).',
     'Bezugsgröße: Anfragen im selben Zeitraum und Filter (zur Einordnung, geht nicht in den Wert ein).', 'Testanfragen; technische Fehler.',
     'Zeitpunkt der Anfrage (requested_at); Betriebstag in der reporting_timezone des Mandanten.', ARRAY['service_id', 'channel'], 'ATTEMPT', NULL),
    ('offer_rate', 'Angebotsquote', 'RATIO', 'Anteil',
     'Anfrageversuche, zu denen mindestens ein Angebot entstand.', 'Gültige Anfrageversuche ohne technischen Fehler.', 'Testanfragen; technische Fehler.',
     'Zeitpunkt der Anfrage (requested_at); Betriebstag in der reporting_timezone des Mandanten.', ARRAY['service_id', 'channel'], 'OFFER', NULL),
    ('booking_conversion', 'Buchungskonversion', 'RATIO', 'Anteil',
     'Bestätigte Buchungen.', 'Angebote (je Angebot höchstens eine Buchung).', 'Testanfragen; abgelaufene Angebote zählen im Nenner.',
     'Zeitpunkt des Angebots (created_at); Betriebstag in der reporting_timezone des Mandanten.', ARRAY['service_id'], 'BOOKING', NULL),
    ('cancellation_rate_passenger', 'Buchungsstornoquote Fahrgast', 'RATIO', 'Anteil',
     'Bestätigte Buchungen, die vom Fahrgast storniert wurden.', 'Bestätigte Buchungen.', 'Betreiberstornos; Testbuchungen.',
     'Zeitpunkt der Bestätigung (confirmed_at); Betriebstag in der reporting_timezone des Mandanten.', ARRAY['service_id'], 'BOOKING', NULL),
    ('cancellation_rate_operator', 'Buchungsstornoquote Betreiber', 'RATIO', 'Anteil',
     'Bestätigte Buchungen, die vom Betreiber storniert wurden.', 'Bestätigte Buchungen.', 'Fahrgaststornos; Testbuchungen.',
     'Zeitpunkt der Bestätigung (confirmed_at); Betriebstag in der reporting_timezone des Mandanten.', ARRAY['service_id'], 'BOOKING', NULL),
    ('no_show_rate', 'No-Show-Rate', 'RATIO', 'Anteil',
     'Buchungen mit wirksamem Nicht-Erscheinen nach Ablauf der Wartefrist.', 'Bestätigte, nicht stornierte Buchungen.', 'Stornierte Buchungen; Nicht-Erscheinen vor Ablauf der Wartefrist.',
     'Zeitpunkt der geplanten Abholung (pickup_earliest); Betriebstag in der reporting_timezone des Mandanten.', ARRAY['service_id'], 'DRIVER_EVENT', 'NOT_EXECUTABLE_IN_THIS_RELEASE'),
    ('pickup_on_time', 'Abholung pünktlich', 'RATIO', 'Anteil',
     'Abholungen innerhalb des zugesagten Abholfensters.', 'Durchgeführte Abholungen.', 'Nicht durchgeführte Fahrten; Abholungen ohne Zusagefenster.',
     'Zeitpunkt der tatsächlichen Abholung; Betriebstag in der reporting_timezone des Mandanten.', ARRAY['service_id'], 'DRIVER_EVENT', 'NOT_EXECUTABLE_IN_THIS_RELEASE'),
    ('arrival_on_time', 'Ankunft pünktlich', 'RATIO', 'Anteil',
     'Ankünfte am Ziel innerhalb der zugesagten Ankunftszeit.', 'Durchgeführte Fahrten.', 'Nicht durchgeführte Fahrten.',
     'Zeitpunkt der tatsächlichen Ankunft; Betriebstag in der reporting_timezone des Mandanten.', ARRAY['service_id'], 'DRIVER_EVENT', 'NOT_EXECUTABLE_IN_THIS_RELEASE'),
    ('mean_pickup_delay', 'Mittlere Abholverspätung', 'MEAN', 'Sekunden',
     'Summe der Verspätungen gegenüber dem spätesten Abholzeitpunkt (nur verspätete Abholungen).', 'Anzahl der durchgeführten Abholungen.', 'Nicht durchgeführte Fahrten; vorzeitige Abholungen zählen mit 0.',
     'Zeitpunkt der tatsächlichen Abholung; Betriebstag in der reporting_timezone des Mandanten.', ARRAY['service_id'], 'DRIVER_EVENT', 'NOT_EXECUTABLE_IN_THIS_RELEASE'),
    ('mean_arrival_delay', 'Mittlere Ankunftsverspätung', 'MEAN', 'Sekunden',
     'Summe der Verspätungen gegenüber der zugesagten Ankunftszeit (nur verspätete Ankünfte).', 'Anzahl der durchgeführten Fahrten.', 'Nicht durchgeführte Fahrten; frühe Ankünfte zählen mit 0.',
     'Zeitpunkt der tatsächlichen Ankunft; Betriebstag in der reporting_timezone des Mandanten.', ARRAY['service_id'], 'DRIVER_EVENT', 'NOT_EXECUTABLE_IN_THIS_RELEASE'),
    ('empty_km_rate', 'Leerkilometerquote', 'RATIO', 'Anteil',
     'Kilometer ohne Fahrgast an Bord.', 'Gefahrene Kilometer insgesamt.', 'Fahrten ohne Positionsdaten (Datenlücke wird als Vollständigkeit ausgewiesen).',
     'Zeitpunkt der Positionsmessung; Betriebstag in der reporting_timezone des Mandanten.', ARRAY['service_id'], 'DRIVER_EVENT', 'NOT_EXECUTABLE_IN_THIS_RELEASE'),
    ('productive_km', 'Produktive Kilometer', 'SUM', 'Kilometer',
     'Summe der Kilometer mit mindestens einem Fahrgast an Bord.', 'Keiner (Summe).', 'Fahrten ohne Positionsdaten.',
     'Zeitpunkt der Positionsmessung; Betriebstag in der reporting_timezone des Mandanten.', ARRAY['service_id'], 'DRIVER_EVENT', 'NOT_EXECUTABLE_IN_THIS_RELEASE'),
    ('capacity_utilization_distance_weighted', 'Kapazitätsauslastung distanzgewichtet', 'RATIO', 'Anteil',
     'Summe aus besetzten Plätzen mal gefahrener Strecke.', 'Summe aus angebotenen Plätzen mal gefahrener Strecke.', 'Fahrten ohne Positionsdaten.',
     'Zeitpunkt der Positionsmessung; Betriebstag in der reporting_timezone des Mandanten.', ARRAY['service_id'], 'DRIVER_EVENT', 'NOT_EXECUTABLE_IN_THIS_RELEASE'),
    ('seat_time_utilization', 'Sitzplatz-Zeit-Auslastung', 'RATIO', 'Anteil',
     'Summe aus besetzten Sitzplätzen mal Zeit.', 'Summe aus angebotenen Sitzplätzen mal Betriebszeit.', 'Zeiten außerhalb der Betriebszeit.',
     'Zeitpunkt der Fahrt; Betriebstag in der reporting_timezone des Mandanten.', ARRAY['service_id'], 'DRIVER_EVENT', 'NOT_EXECUTABLE_IN_THIS_RELEASE'),
    ('passenger_km', 'Fahrgastkilometer', 'SUM', 'Personenkilometer',
     'Summe aus Personenzahl mal besetzter Strecke.', 'Keiner (Summe).', 'Fahrten ohne Positionsdaten.',
     'Zeitpunkt der Positionsmessung; Betriebstag in der reporting_timezone des Mandanten.', ARRAY['service_id'], 'DRIVER_EVENT', 'NOT_EXECUTABLE_IN_THIS_RELEASE'),
    ('technical_partner_rejections', 'Technische Partnerablehnungen', 'COUNT', 'Ablehnungen',
     'Abgelehnte Anfrageversuche mit Primärgrund Partnerausfall (PARTNER_UNAVAILABLE).', 'Bezugsgröße: Anfragen im selben Zeitraum und Filter.', 'Testanfragen; technische Fehler des eigenen Systems.',
     'Zeitpunkt der Anfrage (requested_at); Betriebstag in der reporting_timezone des Mandanten.', ARRAY['service_id'], 'ATTEMPT', 'NO_PARTNERS_IN_P1')
  ) AS v(metric_id, name, kind, unit, numerator, denominator, exclusions, time_basis, dims, source, reason)
  WHERE taktovia.current_tenant() IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM metric_definition_revision m WHERE m.tenant_id = taktovia.current_tenant() AND m.metric_id = v.metric_id);
  GET DIAGNOSTICS n = ROW_COUNT;
  RETURN n;
END $$;


-- Alle Zaehlgroessen des Trichters in einem Statement (ein Lesestand). Zeitbezug je Stufe: Anfrage requested_at, Angebot created_at, Bestaetigung confirmed_at, Abschluss alighted_at, Zahlungsfehlschlag
-- payment_failed_at, Storno cancelled_at. Ein Versuch/eine Buchung hat je Stufe genau einen Endzustand: Buchungszustand mit Vorrang NO_SHOW, COMPLETED, CANCELED (Storno nach Abschluss aendert die Stufe
-- "abgeschlossen" nicht und zaehlt nicht als Storno); eine Buchung mit Zahlungsfehlschlag zaehlt nie zusaetzlich als Fahrgast- oder Betreiberstorno. Testanfragen sind ausgeschlossen.
-- Fahrgaststorno: Verursacher FAHRGAST mit Grund PASSENGER_REQUEST; Betreiberstorno: Verursacher BETREIBER; alles andere (Fahrzeugausfall, No-Show, System) getrennt als "sonstige".
CREATE FUNCTION funnel_counts(p_from timestamptz, p_to timestamptz, p_service uuid, p_channel text)
RETURNS TABLE (requests bigint, technical bigint, rejected bigint, attempts_with_offer bigint, attempts_non_technical bigint, requests_all bigint,
               offers bigint, offers_not_booked bigint, offers_all bigint,
               confirmed bigint, confirmed_all bigint, unlinked_bookings bigint, completed bigint, payment_failed bigint,
               cancel_passenger bigint, cancel_operator bigint, cancel_other bigint, conf_cancel_passenger bigint, conf_cancel_operator bigint,
               processed_until timestamptz, incomplete boolean, gap_count integer, event_count bigint, facts_total bigint, has_data boolean)
LANGUAGE sql STABLE SET search_path = taktovia, pg_catalog AS $$
  WITH wm AS (SELECT w.processed_until, w.completeness, w.gap_count, w.event_count FROM dataset_watermark w WHERE w.source IN ('ATTEMPT', 'OFFER', 'BOOKING')),
  att_all AS (SELECT a.attempt_id, a.service_id, a.channel, a.outcome FROM fact_attempt a WHERE NOT a.is_test AND a.requested_at >= p_from AND a.requested_at < p_to),
  att AS (SELECT a.* FROM att_all a WHERE (p_service IS NULL OR a.service_id = p_service) AND (p_channel IS NULL OR a.channel = p_channel)),
  off_all AS (SELECT o.offer_id, o.service_id, a.channel FROM fact_offer o LEFT JOIN fact_attempt a ON a.attempt_id = o.attempt_id
              WHERE a.is_test IS NOT TRUE AND o.created_at >= p_from AND o.created_at < p_to),
  off AS (SELECT o.* FROM off_all o WHERE (p_service IS NULL OR o.service_id = p_service) AND (p_channel IS NULL OR o.channel = p_channel)),
  conf_all AS (SELECT b.* FROM fact_booking b WHERE b.confirmed_at >= p_from AND b.confirmed_at < p_to),
  conf AS (SELECT b.* FROM conf_all b WHERE p_service IS NULL OR b.service_id = p_service),
  svc AS (SELECT b.* FROM fact_booking b WHERE p_service IS NULL OR b.service_id = p_service)
  SELECT
    (SELECT count(*) FROM att), (SELECT count(*) FILTER (WHERE outcome = 'TECHNICAL_ERROR') FROM att), (SELECT count(*) FILTER (WHERE outcome = 'REJECTED') FROM att),
    (SELECT count(*) FROM att WHERE outcome <> 'TECHNICAL_ERROR' AND EXISTS (SELECT 1 FROM fact_offer o WHERE o.attempt_id = att.attempt_id)),
    (SELECT count(*) FROM att WHERE outcome <> 'TECHNICAL_ERROR'), (SELECT count(*) FROM att_all),
    (SELECT count(*) FROM off),
    (SELECT count(*) FROM off WHERE NOT EXISTS (SELECT 1 FROM fact_booking b WHERE b.offer_id = off.offer_id AND b.confirmed_at IS NOT NULL)),
    (SELECT count(*) FROM off_all),
    (SELECT count(*) FROM conf), (SELECT count(*) FROM conf_all), (SELECT count(*) FILTER (WHERE offer_id IS NULL) FROM conf),
    (SELECT count(*) FROM svc WHERE state = 'COMPLETED' AND alighted_at >= p_from AND alighted_at < p_to),
    (SELECT count(*) FROM svc WHERE payment_failed_at >= p_from AND payment_failed_at < p_to),
    (SELECT count(*) FROM svc WHERE state = 'CANCELED' AND payment_failed_at IS NULL AND cancelled_at >= p_from AND cancelled_at < p_to AND cause_class = 'FAHRGAST' AND cancel_reason = 'PASSENGER_REQUEST'),
    (SELECT count(*) FROM svc WHERE state = 'CANCELED' AND payment_failed_at IS NULL AND cancelled_at >= p_from AND cancelled_at < p_to AND cause_class = 'BETREIBER'),
    (SELECT count(*) FROM svc WHERE state = 'CANCELED' AND payment_failed_at IS NULL AND cancelled_at >= p_from AND cancelled_at < p_to
        AND NOT (cause_class = 'FAHRGAST' AND cancel_reason = 'PASSENGER_REQUEST') AND cause_class IS DISTINCT FROM 'BETREIBER'),
    (SELECT count(*) FROM conf WHERE state = 'CANCELED' AND payment_failed_at IS NULL AND cause_class = 'FAHRGAST' AND cancel_reason = 'PASSENGER_REQUEST'),
    (SELECT count(*) FROM conf WHERE state = 'CANCELED' AND payment_failed_at IS NULL AND cause_class = 'BETREIBER'),
    (SELECT min(processed_until) FROM wm), coalesce((SELECT bool_or(completeness = 'INCOMPLETE') FROM wm), false), coalesce((SELECT sum(gap_count) FROM wm), 0)::integer,
    coalesce((SELECT sum(event_count) FROM wm), 0)::bigint,
    (SELECT (SELECT count(*) FROM fact_attempt) + (SELECT count(*) FROM fact_offer) + (SELECT count(*) FROM fact_booking)),
    EXISTS (SELECT 1 FROM wm)
$$;

-- Abgleichbericht Trichter gegen Ereignisprotokoll: je Stufe die Zahl der Fakten und die unabhaengig aus analytics_event_inbox neu gezaehlte Zahl (juengste Revision je Versuch, erstes Ereignis je Buchung);
-- Differenz 0 heisst: die Faktenprojektion stimmt mit dem Protokoll ueberein. Ohne Dienst-/Kanalfilter (Gesamtzeitraum), Testanfragen ausgeschlossen.
CREATE FUNCTION funnel_reconciliation(p_from timestamptz, p_to timestamptz)
RETURNS TABLE (stage text, fact_count bigint, log_count bigint)
LANGUAGE sql STABLE SET search_path = taktovia, pg_catalog AS $$
  WITH f AS (SELECT * FROM funnel_counts(p_from, p_to, NULL, NULL)),
  att AS (SELECT DISTINCT ON (i.aggregate_id) i.payload FROM analytics_event_inbox i WHERE i.event_type = 'booking.attempt_recorded'
          ORDER BY i.aggregate_id, i.aggregate_revision DESC, i.occurred_at DESC, i.event_id DESC),
  off AS (SELECT DISTINCT ON (i.aggregate_id) i.occurred_at, i.payload FROM analytics_event_inbox i WHERE i.event_type = 'booking.offer_created'
          ORDER BY i.aggregate_id, i.aggregate_revision DESC, i.occurred_at DESC, i.event_id DESC),
  conf AS (SELECT DISTINCT ON (i.payload->>'booking_id') i.occurred_at FROM analytics_event_inbox i WHERE i.event_type = 'booking.confirmed'
           ORDER BY i.payload->>'booking_id', i.aggregate_revision, i.occurred_at, i.event_id),
  done AS (SELECT s.booking_id, min(s.effective_at) AS at FROM (
             SELECT i.payload->>'booking_id' AS booking_id, (i.payload->>'effective_at')::timestamptz AS effective_at, i.payload->>'event_type' AS t, i.payload->>'outcome' AS o
             FROM analytics_event_inbox i WHERE i.event_type = 'driver.event_recorded' AND i.payload ? 'booking_id') s
           WHERE s.t = 'CHECK_OUT' AND s.o = 'APPLIED'
             AND NOT EXISTS (SELECT 1 FROM analytics_event_inbox n WHERE n.event_type = 'driver.event_recorded' AND n.payload->>'booking_id' = s.booking_id AND n.payload->>'event_type' = 'NO_SHOW' AND n.payload->>'outcome' = 'APPLIED')
           GROUP BY s.booking_id),
  pay AS (SELECT i.payload->>'booking_id' AS booking_id, min(i.occurred_at) AS at FROM analytics_event_inbox i WHERE i.event_type = 'payment.authorization_failed' GROUP BY 1
          UNION ALL
          SELECT c.payload->>'booking_id', min(c.occurred_at) FROM analytics_event_inbox c WHERE c.event_type = 'booking.cancelled' AND c.payload->>'reason_code' = 'PAYMENT_FAILED'
            AND NOT EXISTS (SELECT 1 FROM analytics_event_inbox p WHERE p.event_type = 'payment.authorization_failed' AND p.payload->>'booking_id' = c.payload->>'booking_id') GROUP BY 1)
  SELECT 'requests', f.requests_all, (SELECT count(*) FROM att WHERE coalesce((att.payload->>'is_test')::boolean, false) = false
                                      AND (att.payload->>'requested_at')::timestamptz >= p_from AND (att.payload->>'requested_at')::timestamptz < p_to) FROM f
  UNION ALL SELECT 'offers', f.offers_all, (SELECT count(*) FROM off WHERE off.occurred_at >= p_from AND off.occurred_at < p_to
                                              AND NOT coalesce((SELECT (a.payload->>'is_test')::boolean FROM att a WHERE a.payload->>'attempt_id' = off.payload->>'attempt_id'), false)) FROM f
  UNION ALL SELECT 'confirmed_bookings', f.confirmed_all, (SELECT count(*) FROM conf WHERE conf.occurred_at >= p_from AND conf.occurred_at < p_to) FROM f
  UNION ALL SELECT 'completed_bookings', f.completed, (SELECT count(*) FROM done WHERE done.at >= p_from AND done.at < p_to) FROM f
  UNION ALL SELECT 'payment_failed', f.payment_failed, (SELECT count(*) FROM pay WHERE pay.at >= p_from AND pay.at < p_to) FROM f
$$;
