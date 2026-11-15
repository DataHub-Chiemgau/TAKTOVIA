-- WP-M4-57 / LH-I.6-KPI-012: Auslastung als Kapazitätszeit- und Fahrgastkilometerkennzahl (Modul analytics). Eine freigegebene Definition ist unveränderlich: die im Seed (V40460) als "nicht ausführbar" angelegten
-- Kennzahlen `seat_time_utilization` und `capacity_utilization_distance_weighted` erhalten eine ausführbare Version 2 (Version 1 und bereits gespeicherte Snapshots bleiben unverändert). Die Kapazität kommt aus
-- vehicle_km_run.capacity_seats (WP-M4-56, Sitzplätze aus vehicle.capacity_config zum Klassifikationszeitpunkt), die Besetzung aus vehicle_km_occupancy. Wiederholbar: nur wenn die jüngste freigegebene Version noch nicht ausführbar ist.
SET search_path = taktovia, public;

CREATE FUNCTION seed_utilization_metric_definitions() RETURNS integer LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE n integer;
BEGIN
  IF taktovia.current_tenant() IS NULL THEN RETURN 0; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended(taktovia.current_tenant()::text || ':metric-seed-utilization', 0));
  INSERT INTO metric_definition_revision (tenant_id, metric_id, version, name, value_kind, unit, numerator_definition, denominator_definition, exclusions, time_basis, period_basis,
                                          filter_dimensions, source, executable, not_executable_reason, state, approved_by)
  SELECT taktovia.current_tenant(), v.metric_id, NULL, v.name, 'RATIO', v.unit, v.numerator, v.denominator, v.exclusions,
         'Zeitpunkt der Kilometerabschnitte (Positionsmessung, Quelle POSITION, Regelversion 1 der Kilometerklassifikation); Betriebstag in der reporting_timezone des Mandanten; Datenstand = jüngster Klassifikationslauf je Fahrzeug und Betriebstag (UTC).',
         'BUSINESS_DAY_LOCAL', ARRAY['vehicle_id'], 'DRIVER_EVENT', true, NULL, 'APPROVED', 'system:seed (Vorschlag des Plans, Offener Punkt 31 offen)'
  FROM (VALUES
    ('seat_time_utilization', 'Sitzplatz-Zeit-Auslastung', 'Anteil',
     'Summe der belegten Sitzplatzminuten: je Abschnitt Zahl der Fahrgäste an Bord (ein Fahrgast je Buchung) mal Dauer des Abschnitts in Minuten.',
     'Summe der verfügbaren Sitzplatzminuten: Sitzplätze des Fahrzeugs (vehicle.capacity_config, Schlüssel seats, zum Klassifikationszeitpunkt des Betriebstags) mal Dauer der Abschnitte Fahrgast und Leerfahrt in Minuten. Wert = Zähler / Nenner.',
     'Erfassungsgrenze: Betriebsdienst (Abschnitte Fahrgast und Leerfahrt); Überführung, Werkstattfahrt und nicht zuordenbare Zeit zählen weder im Zähler noch im Nenner. Rollstuhlplätze werden nicht den Sitzplätzen zugeschlagen (Offener Punkt 31). Ein Kapazitätswechsel (Umrüstung) wirkt je Betriebstag ab dem Lauf, der die neue Kapazität trägt, nie rückwirkend auf einen veröffentlichten Stand. Ohne verfügbare Kapazität im Zeitraum: nicht berechenbar (ZERO_DENOMINATOR), nie 0 oder 100 %; Läufe ohne bekannte Kapazität oder ohne Besetzung senken die Vollständigkeit.'),
    ('capacity_utilization_distance_weighted', 'Kapazitätsauslastung distanzgewichtet', 'Anteil',
     'Summe der besetzten Platz-Kilometer: je Abschnitt Zahl der Fahrgäste an Bord mal Länge des Abschnitts (Basis sind die Fahrgastkilometer der Kennzahl passenger_km).',
     'Summe der angebotenen Platz-Kilometer: Sitzplätze des Fahrzeugs (vehicle.capacity_config, Schlüssel seats, zum Klassifikationszeitpunkt des Betriebstags) mal Länge der Abschnitte Fahrgast und Leerfahrt. Wert = Zähler / Nenner.',
     'Erfassungsgrenze: Betriebsdienst (Abschnitte Fahrgast und Leerfahrt); Überführung, Werkstattfahrt und nicht zuordenbare Strecke zählen weder im Zähler noch im Nenner. Rollstuhlplätze werden nicht den Sitzplätzen zugeschlagen (gesondert zu zählen, Offener Punkt 31). Ein Kapazitätswechsel wirkt je Betriebstag ab dem Lauf, der die neue Kapazität trägt. Ohne verfügbare Kapazität: nicht berechenbar (ZERO_DENOMINATOR), nie 0 oder 100 %.')
  ) AS v(metric_id, name, unit, numerator, denominator, exclusions)
  WHERE (SELECT m.executable FROM metric_definition_revision m WHERE m.tenant_id = taktovia.current_tenant() AND m.metric_id = v.metric_id AND m.state = 'APPROVED' ORDER BY m.version DESC LIMIT 1) = false;
  GET DIAGNOSTICS n = ROW_COUNT;
  RETURN n;
END $$;
