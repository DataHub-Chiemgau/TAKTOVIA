-- WP-M4-56 / LH-6.14-ANA-002, LH-I.6-KPI-013: Kilometer-, Betriebsstunden- und Fahrgastkilometer-Kennzahlen (Modul analytics).
-- 1. vehicle_km_run: die Fahrzeugkapazität (Sitzplätze, Rollstuhlplätze aus vehicle.capacity_config) zum Zeitpunkt der Klassifikation, damit eine spätere Umrüstung einen veröffentlichten
--    Kennzahlstand nicht rückwirkend ändert (die Kapazität gehört zum Lauf, ein neuer Lauf trägt die neue Kapazität). Beide Spalten NULL = Kapazität unbekannt (Altläufe).
-- 2. vehicle_km_occupancy: Besetzung je Lauf. Ein Abschnitt (Kategorie PASSENGER) wird nach der Zahl der Fahrgäste an Bord unterteilt; Fahrgastkilometer = Summe aus Fahrgäste mal Meter.
--    Nur anhängen, Abschnitte eines Laufs überlappen nicht; die Summe der Meter entspricht je Lauf der Summe der PASSENGER-Abschnitte (aufgeschobene Prüfung, sobald ein Lauf Besetzungsabschnitte hat).
-- 3. aggregate_snapshot.components: die Kilometer je Kategorie (Zerlegung der Fahrzeugkilometer), damit die Summeninvariante am gespeicherten Wert prüfbar bleibt.
-- 4. seed_km_metric_definitions(): ausführbare Version 2 der fünf im Seed (V40460) als "nicht ausführbar" angelegten Kilometerkennzahlen und zwei neue Kennzahlen (Fahrzeugkilometer, Betriebsstunden).
SET search_path = taktovia, public;

ALTER TABLE vehicle_km_run
  ADD COLUMN capacity_seats      integer CONSTRAINT vehicle_km_run_capacity_seats CHECK (capacity_seats IS NULL OR capacity_seats >= 0),
  ADD COLUMN capacity_wheelchair integer CONSTRAINT vehicle_km_run_capacity_wheelchair CHECK (capacity_wheelchair IS NULL OR capacity_wheelchair >= 0);

CREATE TABLE vehicle_km_occupancy (
  tenant_id      uuid        NOT NULL REFERENCES tenant(id),
  id             uuid        NOT NULL,
  run_id         uuid        NOT NULL,
  vehicle_id     uuid        NOT NULL,
  service_day    date        NOT NULL,
  period_from    timestamptz NOT NULL,
  period_to      timestamptz NOT NULL,
  passengers     integer     NOT NULL CONSTRAINT vehicle_km_occupancy_passengers CHECK (passengers BETWEEN 1 AND 99),
  km_thousandths bigint      NOT NULL CONSTRAINT vehicle_km_occupancy_km CHECK (km_thousandths >= 0),
  rule_version   integer     NOT NULL CHECK (rule_version >= 1),
  PRIMARY KEY (tenant_id, id),
  CONSTRAINT vehicle_km_occupancy_period CHECK (period_to > period_from),
  FOREIGN KEY (tenant_id, run_id, vehicle_id, service_day) REFERENCES vehicle_km_run(tenant_id, id, vehicle_id, service_day),
  CONSTRAINT vehicle_km_occupancy_no_overlap EXCLUDE USING gist (tenant_id WITH =, run_id WITH =, tstzrange(period_from, period_to) WITH &&)
);
CREATE INDEX vehicle_km_occupancy_by_run ON vehicle_km_occupancy (tenant_id, run_id, period_from);

ALTER TABLE vehicle_km_occupancy ENABLE ROW LEVEL SECURITY;
ALTER TABLE vehicle_km_occupancy FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON vehicle_km_occupancy USING (tenant_id = current_tenant()) WITH CHECK (tenant_id = current_tenant());
CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON vehicle_km_occupancy FOR EACH ROW EXECUTE FUNCTION reject_tenant_change();
CREATE TRIGGER vehicle_km_occupancy_immutable BEFORE UPDATE OR DELETE ON vehicle_km_occupancy FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- Die Besetzung ist eine Unterteilung der PASSENGER-Abschnitte: Meter der Besetzungsabschnitte = Meter der PASSENGER-Abschnitte je Lauf (am Ende der Transaktion geprüft, sobald ein Lauf Besetzungsabschnitte hat).
CREATE FUNCTION vehicle_km_occupancy_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE rid uuid; tid uuid; occ bigint; pax bigint;
BEGIN
  rid := NEW.run_id;
  tid := NEW.tenant_id;
  SELECT coalesce(sum(km_thousandths), 0) INTO occ FROM vehicle_km_occupancy WHERE tenant_id = tid AND run_id = rid;
  SELECT coalesce(sum(km_thousandths), 0) INTO pax FROM vehicle_km_segment WHERE tenant_id = tid AND run_id = rid AND category = 'PASSENGER';
  -- ohne Besetzungsabschnitte (Altläufe, direkt angelegte Läufe) ist die Besetzung unbekannt: das ist kein Widerspruch, die Kennzahl weist es als fehlende Vollständigkeit aus
  IF occ > 0 AND occ IS DISTINCT FROM pax THEN
    RAISE EXCEPTION 'vehicle_km_occupancy: Besetzungsabschnitte (%) ungleich PASSENGER-Abschnitte (%)', occ, pax USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER vehicle_km_occupancy_sum AFTER INSERT ON vehicle_km_occupancy DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION vehicle_km_occupancy_guard();
CREATE CONSTRAINT TRIGGER vehicle_km_occupancy_sum_seg AFTER INSERT ON vehicle_km_segment DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION vehicle_km_occupancy_guard();

ALTER TABLE aggregate_snapshot ADD COLUMN components jsonb CONSTRAINT aggregate_snapshot_components_object CHECK (components IS NULL OR jsonb_typeof(components) = 'object');

CREATE FUNCTION seed_km_metric_definitions() RETURNS integer LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE n integer; m integer;
BEGIN
  IF taktovia.current_tenant() IS NULL THEN RETURN 0; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended(taktovia.current_tenant()::text || ':metric-seed-km', 0));
  -- Version 2 der im Seed als nicht ausführbar angelegten Kennzahlen (nur wenn die jüngste freigegebene Version noch nicht ausführbar ist) und Version 1 der zwei neuen Kennzahlen.
  INSERT INTO metric_definition_revision (tenant_id, metric_id, version, name, value_kind, unit, numerator_definition, denominator_definition, exclusions, time_basis, period_basis,
                                          filter_dimensions, source, executable, not_executable_reason, state, approved_by)
  SELECT taktovia.current_tenant(), v.metric_id, NULL, v.name, v.kind, v.unit, v.numerator, v.denominator, v.exclusions,
         'Zeitpunkt der Kilometerabschnitte (Positionsmessung, Quelle POSITION, Regelversion 1 der Kilometerklassifikation); Betriebstag in der reporting_timezone des Mandanten; Datenstand = jüngster Klassifikationslauf je Fahrzeug und Betriebstag (UTC).',
         'BUSINESS_DAY_LOCAL', ARRAY['vehicle_id'], 'DRIVER_EVENT', true, NULL, 'APPROVED', 'system:seed (Vorschlag des Plans, Freigabe D-07 und Offener Punkt 22 offen)'
  FROM (VALUES
    ('vehicle_km', 'Fahrzeugkilometer', 'SUM', 'Kilometer',
     'Summe aller Kilometerabschnitte der Kilometerklassifikation (Fahrgast, Leerfahrt, Überführung, Werkstattfahrt, nicht zuordenbar); Distanzquelle: Abstand aufeinanderfolgender Positionen des Fahrzeugs (Regelversion 1). Die Zerlegung nach Kategorie steht in components.',
     'Keiner (Summe).',
     'Erfassungsgrenze: Positionen des Betriebstags; Abschnitte werden nach ihrem Beginn dem Berichtszeitraum zugerechnet. Nichts wird ausgeschlossen: was keiner Kategorie zuordenbar ist, steht als nicht zuordenbar (UNASSIGNED) in components und senkt die Vollständigkeit.'),
    ('operating_hours', 'Betriebsstunden', 'SUM', 'Stunden',
     'Dauer der Kilometerabschnitte der Kategorien Fahrgast, Leerfahrt und Überführung (Betriebsstunde laut Vorschlag des Plans, Offener Punkt 22; entspricht der Definition der Energiekosten je Betriebsstunde); Werkstattfahrt und nicht zugeordnete Zeit zählen nicht.',
     'Keiner (Summe).',
     'Erfassungsgrenze: Beginn und Ende des Betriebsdienstes (veröffentlichte Schicht des Fahrzeugs mit Fahrtenplan oder Überführungsschicht); Zeit außerhalb des Betriebsdienstes und über Positionslücken (mehr als 300 Sekunden) ist nicht zuordenbar und senkt die Vollständigkeit; Werkstattfahrten sind keine Betriebszeit.'),
    ('empty_km_rate', 'Leerkilometerquote', 'RATIO', 'Anteil',
     'Kilometer der Kategorie Leerfahrt: Abschnitte im Betriebsdienst, in denen kein Fahrgast an Bord ist.',
     'Alle Fahrzeugkilometer im Betriebsdienst: Fahrgast + Leerfahrt + nicht zuordenbar (die Kategorien Überführung und Werkstattfahrt stehen getrennt, Offener Punkt 22).',
     'Erfassungsgrenze: Beginn und Ende des Betriebsdienstes. Überführung und Werkstattfahrt zählen weder im Zähler noch im Nenner. Nicht zuordenbare Kilometer stehen im Nenner und werden nie auf Leer oder Fahrgast verteilt; sie senken die Vollständigkeit. Ohne Nenner: nicht berechenbar (ZERO_DENOMINATOR), nie 0 oder 100 %.'),
    ('productive_km', 'Produktive Kilometer', 'SUM', 'Kilometer',
     'Summe der Kilometer der Kategorie Fahrgast: Abschnitte, in denen mindestens ein Fahrgast an Bord ist, unabhängig von der Zahl der Fahrgäste (Einstieg bis Ausstieg laut Fahrerereignissen).',
     'Keiner (Summe); zur Einordnung weist der Nenner die Fahrzeugkilometer im Betriebsdienst aus.',
     'Erfassungsgrenze: Beginn und Ende des Betriebsdienstes. Überführung und Werkstattfahrt zählen nie als produktive Fahrgaststrecke, auch nicht mit Fahrgast an Bord; Abschnitte ohne Fahrtenplan oder über Positionslücken sind nicht zuordenbar und werden nicht als produktiv geraten.'),
    ('passenger_km', 'Fahrgastkilometer', 'SUM', 'Personenkilometer',
     'Summe der individuellen Fahrtstrecken aller Fahrgäste: je Abschnitt Zahl der Fahrgäste an Bord mal Länge des Abschnitts (ein Fahrgast je Buchung, solange die Ereignisse keine Gruppengröße tragen); bei zwei Fahrgästen auf gemeinsamer Teilstrecke größer als die produktiven Kilometer.',
     'Keiner (Summe); nicht mit Fahrzeugkilometern verwechseln.',
     'Erfassungsgrenze: Einstieg bis Ausstieg einer Buchung innerhalb des Betriebsdienstes (Kategorie Fahrgast). Überführung und Werkstattfahrt zählen nie; Läufe mit unvollständigen Fahrerereignissen (Einstieg ohne Ausstieg) senken die Vollständigkeit.')
  ) AS v(metric_id, name, kind, unit, numerator, denominator, exclusions)
  WHERE (SELECT m.executable FROM metric_definition_revision m WHERE m.tenant_id = taktovia.current_tenant() AND m.metric_id = v.metric_id AND m.state = 'APPROVED' ORDER BY m.version DESC LIMIT 1) IS NOT TRUE
    AND NOT EXISTS (SELECT 1 FROM metric_definition_revision m WHERE m.tenant_id = taktovia.current_tenant() AND m.metric_id = v.metric_id AND m.executable);
  GET DIAGNOSTICS n = ROW_COUNT;
  RETURN n;
END $$;
