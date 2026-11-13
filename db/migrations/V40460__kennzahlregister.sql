-- WP-M4-46 / LH-I.6-KPI-010: Kennzahlregister mit Definitionsversion, Nenner und Datenstand (Modul analytics).
-- metric_definition_revision: je Mandant und stabiler technischer Kennzahl-ID eine lückenlose Versionsfolge; nach der Freigabe unveränderlich, eine Änderung ist eine neue Version.
-- aggregate_snapshot: gespeicherter Kennzahlwert mit Definitionsversion, Filter, Zähler, Nenner, Datenstand und Vollständigkeit (nur anhängen; behält seine Version).
-- Nullnenner ergibt "nicht berechenbar" (value NULL), nie 0 oder 100 %; unvollständige Quellen ergeben PROVISIONAL mit Abdeckungsgrad. dataset_watermark (Datenstand) kommt aus V40450.
SET search_path = taktovia, public;

CREATE TABLE metric_definition_revision (
  tenant_id              uuid        NOT NULL REFERENCES tenant(id),
  metric_id              text        NOT NULL CHECK (metric_id ~ '^[a-z][a-z0-9_]{1,63}$'),
  version                integer     NOT NULL CHECK (version >= 1),
  name                   text        NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
  value_kind             text        NOT NULL CHECK (value_kind IN ('COUNT', 'RATIO', 'MEAN', 'SUM')),
  unit                   text        NOT NULL CHECK (length(unit) BETWEEN 1 AND 40),
  numerator_definition   text        CHECK (numerator_definition IS NULL OR length(numerator_definition) <= 1000),
  denominator_definition text        CHECK (denominator_definition IS NULL OR length(denominator_definition) <= 1000),
  exclusions             text        CHECK (exclusions IS NULL OR length(exclusions) <= 1000),
  time_basis             text        CHECK (time_basis IS NULL OR length(time_basis) <= 500),
  period_basis           text        NOT NULL DEFAULT 'BUSINESS_DAY_LOCAL' CHECK (period_basis IN ('BUSINESS_DAY_LOCAL', 'INSTANT')),
  filter_dimensions      text[],
  source                 text        CHECK (source IS NULL OR source IN ('ATTEMPT', 'OFFER', 'BOOKING', 'DRIVER_EVENT', 'VEHICLE_STATE')),
  executable             boolean     NOT NULL DEFAULT false,
  not_executable_reason  text        CHECK (not_executable_reason IS NULL OR not_executable_reason IN ('NOT_EXECUTABLE_IN_THIS_RELEASE', 'NO_PARTNERS_IN_P1')),
  state                  text        NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT', 'APPROVED')),
  approved_at            timestamptz,
  approved_by            text        CHECK (approved_by IS NULL OR length(approved_by) <= 200),
  created_at             timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, metric_id, version),
  CONSTRAINT metric_definition_executable_consistent CHECK (executable = (not_executable_reason IS NULL)),
  CONSTRAINT metric_definition_filter_dimensions_known CHECK (filter_dimensions IS NULL OR filter_dimensions <@ ARRAY['service_id', 'channel']::text[]),
  -- Freigabe nur mit allen Pflichtfeldern: Zähler, Nenner, Ausschlüsse, Zeitbezug, Filterdimensionen (die Liste darf leer sein, fehlen darf sie nicht)
  CONSTRAINT metric_definition_approved_complete CHECK (state <> 'APPROVED' OR (
    length(trim(coalesce(numerator_definition, ''))) > 0 AND length(trim(coalesce(denominator_definition, ''))) > 0 AND length(trim(coalesce(exclusions, ''))) > 0
    AND length(trim(coalesce(time_basis, ''))) > 0 AND filter_dimensions IS NOT NULL AND approved_at IS NOT NULL))
);

CREATE FUNCTION metric_definition_revision_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE nxt integer; newer integer;
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.state = 'APPROVED' THEN RAISE EXCEPTION 'metric_definition_revision: freigegebene Definition ist unveraenderlich' USING ERRCODE = '23514'; END IF;
    RETURN OLD;
  END IF;
  -- Eine Sperre je Mandant und Kennzahl serialisiert Versionsvergabe und Freigabe: gleichzeitige Freigaben ergeben eine eindeutige Folge
  PERFORM pg_advisory_xact_lock(hashtextextended(NEW.tenant_id::text || ':metric:' || NEW.metric_id, 0));
  IF TG_OP = 'INSERT' THEN
    SELECT coalesce(max(m.version), 0) + 1 INTO nxt FROM metric_definition_revision m WHERE m.tenant_id = NEW.tenant_id AND m.metric_id = NEW.metric_id;
    IF NEW.version IS NULL THEN NEW.version := nxt;
    ELSIF NEW.version <> nxt THEN RAISE EXCEPTION 'metric_definition_revision: Version % ist nicht die naechste (%)', NEW.version, nxt USING ERRCODE = '23514';
    END IF;
  ELSE
    IF OLD.state = 'APPROVED' THEN RAISE EXCEPTION 'metric_definition_revision: freigegebene Definition ist unveraenderlich' USING ERRCODE = '23514'; END IF;
    IF NEW.metric_id <> OLD.metric_id OR NEW.version <> OLD.version THEN RAISE EXCEPTION 'metric_definition_revision: Kennung und Version sind unveraenderlich' USING ERRCODE = '23514'; END IF;
  END IF;
  IF NEW.state = 'APPROVED' THEN
    -- Freigaben folgen der Versionsfolge: eine ältere Version wird nach der Freigabe einer neueren nicht mehr freigegeben
    SELECT max(m.version) INTO newer FROM metric_definition_revision m WHERE m.tenant_id = NEW.tenant_id AND m.metric_id = NEW.metric_id AND m.state = 'APPROVED' AND m.version > NEW.version;
    IF newer IS NOT NULL THEN RAISE EXCEPTION 'metric_definition_revision: Version % ist bereits freigegeben', newer USING ERRCODE = '23514'; END IF;
    NEW.approved_at := coalesce(NEW.approved_at, clock_timestamp());
  ELSIF NEW.approved_at IS NOT NULL OR NEW.approved_by IS NOT NULL THEN
    RAISE EXCEPTION 'metric_definition_revision: Freigabedaten nur bei freigegebener Definition' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER metric_definition_revision_guard BEFORE INSERT OR UPDATE OR DELETE ON metric_definition_revision FOR EACH ROW EXECUTE FUNCTION metric_definition_revision_guard();

CREATE TABLE aggregate_snapshot (
  tenant_id          uuid        NOT NULL REFERENCES tenant(id),
  id                 uuid        NOT NULL,
  metric_id          text        NOT NULL,
  definition_version integer     NOT NULL,
  value_kind         text        NOT NULL CHECK (value_kind IN ('COUNT', 'RATIO', 'MEAN', 'SUM')),
  filter             jsonb       NOT NULL CHECK (jsonb_typeof(filter) = 'object'),
  filter_hash        char(64)    NOT NULL CHECK (filter_hash ~ '^[0-9a-f]{64}$'),
  period_from        timestamptz NOT NULL,
  period_to          timestamptz NOT NULL CHECK (period_to > period_from),
  reporting_timezone text        NOT NULL CHECK (length(reporting_timezone) BETWEEN 1 AND 64),
  state              text        NOT NULL CHECK (state IN ('OK', 'PROVISIONAL', 'NOT_COMPUTABLE', 'SUPPRESSED')),
  reason             text        CHECK (reason IS NULL OR reason IN ('ZERO_DENOMINATOR', 'MISSING_DENOMINATOR', 'NO_DATA', 'NOT_EXECUTABLE_IN_THIS_RELEASE', 'NO_PARTNERS_IN_P1', 'SMALL_GROUP')),
  value              numeric,
  numerator          numeric,
  denominator        numeric,
  coverage           numeric(5, 4) CHECK (coverage IS NULL OR (coverage >= 0 AND coverage <= 1)),
  data_as_of         timestamptz,
  source_events      bigint      NOT NULL DEFAULT 0 CHECK (source_events >= 0),
  computed_at        timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, metric_id, definition_version) REFERENCES metric_definition_revision (tenant_id, metric_id, version),
  -- nicht berechenbar oder unterdrückt: kein Wert, immer mit Grund; berechenbar: Wert und Zähler vorhanden, nie ohne Grund ein Wert 0 bei fehlendem Nenner
  CONSTRAINT aggregate_snapshot_value_state CHECK (
    (state IN ('NOT_COMPUTABLE', 'SUPPRESSED') AND value IS NULL AND reason IS NOT NULL)
    OR (state IN ('OK', 'PROVISIONAL') AND value IS NOT NULL AND numerator IS NOT NULL AND reason IS NULL)),
  CONSTRAINT aggregate_snapshot_suppressed_reason CHECK ((state = 'SUPPRESSED') = (reason = 'SMALL_GROUP')),
  CONSTRAINT aggregate_snapshot_ratio_denominator CHECK (value_kind <> 'RATIO' OR state NOT IN ('OK', 'PROVISIONAL') OR denominator > 0),
  CONSTRAINT aggregate_snapshot_provisional_coverage CHECK ((state = 'PROVISIONAL') = (state IN ('OK', 'PROVISIONAL') AND coverage IS NOT NULL AND coverage < 1))
);
-- dieselbe Auswertung auf demselben Datenstand ist genau ein Snapshot (parallele Abfragen schreiben nicht doppelt)
CREATE UNIQUE INDEX aggregate_snapshot_evaluation_uq ON aggregate_snapshot
  (tenant_id, metric_id, definition_version, filter_hash, period_from, period_to, reporting_timezone, coalesce(data_as_of, '-infinity'::timestamptz), source_events);
CREATE INDEX aggregate_snapshot_by_metric ON aggregate_snapshot (tenant_id, metric_id, computed_at);

DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['metric_definition_revision', 'aggregate_snapshot'] LOOP
    EXECUTE format('ALTER TABLE taktovia.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE taktovia.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON taktovia.%I USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant())', t);
    EXECUTE format('CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON taktovia.%I FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change()', t);
  END LOOP;
END $$;
CREATE TRIGGER aggregate_snapshot_immutable BEFORE UPDATE OR DELETE ON aggregate_snapshot FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- Seed der P1-Kennzahlen (Vorschlag des Plans, Offener Punkt 24; verbindliche Berichtsdefinitionen sind AG-Freigabe, D-05) für den Mandanten des Kontexts.
-- Wiederholbar: nur Kennzahlen ohne jede Revision werden angelegt. Ausführbar ist in diesem Stand, was die Faktenbasis der Versuche hergibt (executable); alle anderen Definitionen tragen ihren Grund.
CREATE FUNCTION seed_metric_definitions() RETURNS integer LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
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
     'Zeitpunkt der Anfrage (requested_at); Betriebstag in der reporting_timezone des Mandanten.', ARRAY['service_id', 'channel'], 'OFFER', 'NOT_EXECUTABLE_IN_THIS_RELEASE'),
    ('booking_conversion', 'Buchungskonversion', 'RATIO', 'Anteil',
     'Bestätigte Buchungen.', 'Angebote (je Angebot höchstens eine Buchung).', 'Testanfragen; abgelaufene Angebote zählen im Nenner.',
     'Zeitpunkt des Angebots (created_at); Betriebstag in der reporting_timezone des Mandanten.', ARRAY['service_id'], 'BOOKING', 'NOT_EXECUTABLE_IN_THIS_RELEASE'),
    ('cancellation_rate_passenger', 'Buchungsstornoquote Fahrgast', 'RATIO', 'Anteil',
     'Bestätigte Buchungen, die vom Fahrgast storniert wurden.', 'Bestätigte Buchungen.', 'Betreiberstornos; Testbuchungen.',
     'Zeitpunkt der Bestätigung (confirmed_at); Betriebstag in der reporting_timezone des Mandanten.', ARRAY['service_id'], 'BOOKING', 'NOT_EXECUTABLE_IN_THIS_RELEASE'),
    ('cancellation_rate_operator', 'Buchungsstornoquote Betreiber', 'RATIO', 'Anteil',
     'Bestätigte Buchungen, die vom Betreiber storniert wurden.', 'Bestätigte Buchungen.', 'Fahrgaststornos; Testbuchungen.',
     'Zeitpunkt der Bestätigung (confirmed_at); Betriebstag in der reporting_timezone des Mandanten.', ARRAY['service_id'], 'BOOKING', 'NOT_EXECUTABLE_IN_THIS_RELEASE'),
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
