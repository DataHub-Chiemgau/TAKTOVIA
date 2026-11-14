-- WP-M4-43 / LH-I.2-FCO-004: Energiekosten je 100 km und je Betriebsstunde (Modul analytics).
-- Versionsnummer V40461 statt V40430: die Migration setzt aggregate_snapshot und metric_definition_revision aus V40460 (WP-M4-46) voraus und muss danach laufen.
-- Zwei neue, im Katalog K nicht enthaltene Kennzahldefinitionen (Offener Punkt 40: Name, Formel, Aufnahme in den Katalog sind zu bestätigen; Vorschlag dieses Plans: Kostenbasis, nach Energieart getrennt)
-- und zwei zusätzliche Spalten des Kennzahl-Snapshots: die Ausschlussliste (nicht eingegangene Vorgänge mit Grund) und die Kennzeichnung der P1-Erwerbsnäherung (Kap. 03 §1.1).
-- Gelesen wird nur lesend: economic_event, fuel_transaction, expense_document, expense_hint(_ack), vehicle_km_run/-segment. Geschrieben wird nur aggregate_snapshot (Rechte unverändert: SELECT, INSERT).
SET search_path = taktovia, public;

ALTER TABLE aggregate_snapshot
  ADD COLUMN exclusions    jsonb NOT NULL DEFAULT '[]'::jsonb CONSTRAINT aggregate_snapshot_exclusions_array CHECK (jsonb_typeof(exclusions) = 'array'),
  ADD COLUMN approximation text  CONSTRAINT aggregate_snapshot_approximation CHECK (approximation IS NULL OR approximation IN ('ACQUISITION_BASIS_P1'));

-- Zulässige Filterdimensionen der Definitionen: zusätzlich Fahrzeug, Energieart und Währung der Energiekosten-Kennzahlen.
ALTER TABLE metric_definition_revision DROP CONSTRAINT metric_definition_filter_dimensions_known;
ALTER TABLE metric_definition_revision ADD CONSTRAINT metric_definition_filter_dimensions_known
  CHECK (filter_dimensions IS NULL OR filter_dimensions <@ ARRAY['service_id', 'channel', 'vehicle_id', 'energy', 'currency']::text[]);

-- Seed der beiden Definitionen für den Mandanten des Kontexts (wiederholbar: nur Kennzahlen ohne jede Revision werden angelegt). Getrennt von seed_metric_definitions() (WP-M4-46), dessen Zahl der P1-Kennzahlen unverändert bleibt.
CREATE FUNCTION seed_energy_metric_definitions() RETURNS integer LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE n integer;
BEGIN
  IF taktovia.current_tenant() IS NULL THEN RETURN 0; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended(taktovia.current_tenant()::text || ':metric-seed-energy', 0));
  INSERT INTO metric_definition_revision (tenant_id, metric_id, version, name, value_kind, unit, numerator_definition, denominator_definition, exclusions, time_basis, period_basis,
                                          filter_dimensions, source, executable, not_executable_reason, state, approved_by)
  SELECT taktovia.current_tenant(), v.metric_id, 1, v.name, 'RATIO', v.unit, v.numerator, v.denominator, v.exclusions, v.time_basis, 'BUSINESS_DAY_LOCAL',
         ARRAY['vehicle_id', 'energy', 'currency'], NULL, true, NULL, 'APPROVED', 'system:seed (Vorschlag des Plans, Freigabe D-05 und Offener Punkt 40 offen)'
  FROM (VALUES
    ('energy_cost_per_100km', 'Energiekosten je 100 km', 'Minor Units je 100 km',
     'Summe der Kostenbasis (brutto abzüglich abziehbarer Vorsteuer, Minor Units) der freigegebenen, gebuchten Tankvorgänge der Energieart im Zeitraum; Währungen werden nie addiert, die Ausgabe ist je Energieart (Kraftstoff in Liter, Strom in kWh) und Währung getrennt.',
     'Gefahrene Kilometer (alle Kategorien der Kilometerklassifikation, jüngster Lauf je Fahrzeug und Tag) der Fahrzeuge mit mindestens einem eingegangenen Tankvorgang; Wert = Zähler / Kilometer x 100, auf ganze Minor Units kaufmännisch gerundet (ROUND_HALF_UP).',
     'Nicht freigegebene oder nicht gebuchte Belege (Grund NOT_RELEASED), Belege mit offenem Plausibilitätshinweis (OPEN_HINT), Belege ohne Pflichtwert (MISSING_*), stornierte Vorgänge (REVERSED), Fahrzeuge mit Tankvorgängen beider Energiearten (MIXED_ENERGY_VEHICLE), Vorgänge einer anderen Währung (OTHER_CURRENCY); jeder Ausschluss wird mit Beleg und Grund ausgewiesen.',
     'Tankzeit des Vorgangs (fueled_at, sonst Eingang des Belegs) und Zeitpunkt der Kilometerabschnitte; Betriebstag in der reporting_timezone des Mandanten. P1 ist eine Erwerbsnäherung ohne Bestandsführung und Ladeverluste (Kap. 03 §1.1): getankte, nicht verbrauchte Energie.'),
    ('energy_cost_per_operating_hour', 'Energiekosten je Betriebsstunde', 'Minor Units je Betriebsstunde',
     'Summe der Kostenbasis (brutto abzüglich abziehbarer Vorsteuer, Minor Units) der freigegebenen, gebuchten Tankvorgänge der Energieart im Zeitraum; Währungen werden nie addiert, die Ausgabe ist je Energieart und Währung getrennt.',
     'Betriebsstunden der Fahrzeuge mit mindestens einem eingegangenen Tankvorgang. Definition „Betriebsstunde“ (Vorschlag dieses Plans, Offener Punkt 22): Dauer der Kilometerabschnitte der Kategorien Fahrgast, Leerfahrt und Überführung (ohne Werkstattfahrt und ohne nicht zugeordnete Zeit); Wert = Zähler / Betriebsstunden, auf ganze Minor Units kaufmännisch gerundet (ROUND_HALF_UP).',
     'Wie bei Energiekosten je 100 km; zusätzlich gilt ein Fahrzeug mit nicht zugeordneter Abschnittszeit als unvollständig (die Betriebsstunden wären zu niedrig).',
     'Tankzeit des Vorgangs (fueled_at, sonst Eingang des Belegs) und Zeitpunkt der Kilometerabschnitte; Betriebstag in der reporting_timezone des Mandanten. P1 ist eine Erwerbsnäherung ohne Bestandsführung und Ladeverluste (Kap. 03 §1.1).')
  ) AS v(metric_id, name, unit, numerator, denominator, exclusions, time_basis)
  WHERE NOT EXISTS (SELECT 1 FROM metric_definition_revision m WHERE m.tenant_id = taktovia.current_tenant() AND m.metric_id = v.metric_id);
  GET DIAGNOSTICS n = ROW_COUNT;
  RETURN n;
END $$;
