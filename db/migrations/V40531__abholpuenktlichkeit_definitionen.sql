-- WP-M4-53 / LH-I.6-KPI-007: die Kennzahlen `pickup_on_time` und `mean_pickup_delay` werden ausführbar. Eine freigegebene Definition ist unveränderlich (Trigger): der Seed der Version 1 (V40460) hat sie als
-- "in diesem Stand nicht ausführbar" angelegt, also kommt eine neue Version 2 mit den Definitionen dieses Pakets (Zähler, Nenner, Ausschlüsse, Zeitbezug laut Detailvertrag). Bereits gespeicherte Snapshots
-- zeigen weiter auf Version 1. Wiederholbar: nur wenn die jüngste freigegebene Version noch nicht ausführbar ist; die Funktion wird vom Kennzahlregister beim Zugriff nach dem Grundseed aufgerufen.
SET search_path = taktovia, public;

CREATE FUNCTION seed_pickup_punctuality_definitions() RETURNS integer LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE n integer;
BEGIN
  IF taktovia.current_tenant() IS NULL THEN RETURN 0; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended(taktovia.current_tenant()::text || ':metric-seed-pickup', 0));
  INSERT INTO metric_definition_revision (tenant_id, metric_id, version, name, value_kind, unit, numerator_definition, denominator_definition, exclusions, time_basis, period_basis,
                                          filter_dimensions, source, executable, not_executable_reason, state, approved_by)
  SELECT taktovia.current_tenant(), v.metric_id, NULL, v.name, v.kind, v.unit, v.numerator, v.denominator, v.exclusions,
         'Zeitpunkt der tatsächlichen Abholung (Einstieg, Ist-Zeit); Betriebstag in der reporting_timezone des Mandanten.', 'BUSINESS_DAY_LOCAL',
         ARRAY['service_id'], 'DRIVER_EVENT', true, NULL, 'APPROVED', 'system:seed (Vorschlag des Plans, Freigabe D-05 offen)'
  FROM (VALUES
    ('pickup_on_time', 'Abholung pünktlich', 'RATIO', 'Anteil',
     'Tatsächliche Abholungen, deren Verspätung (Ist-Abholung minus Ende des zugesagten Abholfensters der höchsten Zusageversion) höchstens den zum Vergleichsstichtag (Zeitpunkt der Zusage) wirksamen Grenzwert on_time_pickup_tolerance_min des Dienstes (Gebiet) beträgt; frühe Abholungen sind pünktlich.',
     'Tatsächliche qualifizierte Abholungen: Abholungen mit Ist-Zeit, Zusagefenster und gebundenem Grenzwert.',
     'Abholungen ohne Ist-Zeit (nicht bewertbar), ohne Zusagefenster, ohne auflösbaren Grenzwert; nicht durchgeführte Fahrten.'),
    ('mean_pickup_delay', 'Mittlere Abholverspätung', 'MEAN', 'Minuten',
     'Summe der positiven Verspätungsminuten (Ist-Abholung minus Ende des zugesagten Abholfensters der höchsten Zusageversion); eine frühe Abholung geht mit 0 ein und wird nie negativ verrechnet.',
     'Alle bewertbaren Abholungen: Abholungen mit Ist-Zeit und Zusagefenster.',
     'Abholungen ohne Ist-Zeit (nicht bewertbar) oder ohne Zusagefenster; nicht durchgeführte Fahrten.')
  ) AS v(metric_id, name, kind, unit, numerator, denominator, exclusions)
  WHERE taktovia.current_tenant() IS NOT NULL
    AND (SELECT m.executable FROM metric_definition_revision m WHERE m.tenant_id = taktovia.current_tenant() AND m.metric_id = v.metric_id AND m.state = 'APPROVED' ORDER BY m.version DESC LIMIT 1) = false;
  GET DIAGNOSTICS n = ROW_COUNT;
  RETURN n;
END $$;
