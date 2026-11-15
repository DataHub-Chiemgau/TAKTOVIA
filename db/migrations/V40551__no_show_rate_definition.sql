-- WP-M4-55 / LH-I.6-KPI-009: die Kennzahl `no_show_rate` wird ausführbar. Eine freigegebene Definition ist unveränderlich (Trigger): die Version 1 des Seeds (V40460) ist als "in diesem Stand nicht ausführbar"
-- angelegt, also kommt eine neue Version 2 (Zähler und Nenner laut Detailvertrag). Bereits gespeicherte Snapshots zeigen weiter auf Version 1. Wiederholbar: nur wenn die jüngste freigegebene Version noch nicht
-- ausführbar ist; die Funktion wird vom Kennzahlregister beim Zugriff nach dem Grundseed aufgerufen. `Buchungsstornoquote Betreiber` (Fahrzeugausfall getrennt) ist seit WP-M4-51 ausführbar: sie zählt
-- ausschließlich die Verursacherklasse BETREIBER; Fahrzeugausfall (FAHRZEUGAUSFALL) steht getrennt in der Auswertung nicht durchgeführter Fahrten.
SET search_path = taktovia, public;

CREATE FUNCTION seed_not_performed_definitions() RETURNS integer LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE n integer;
BEGIN
  IF taktovia.current_tenant() IS NULL THEN RETURN 0; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended(taktovia.current_tenant()::text || ':metric-seed-not-performed', 0));
  INSERT INTO metric_definition_revision (tenant_id, metric_id, version, name, value_kind, unit, numerator_definition, denominator_definition, exclusions, time_basis, period_basis,
                                          filter_dimensions, source, executable, not_executable_reason, state, approved_by)
  SELECT taktovia.current_tenant(), 'no_show_rate', NULL, 'No-Show-Rate', 'RATIO', 'Anteil',
         'Nicht durchgeführte bestätigte Fahrten mit wirksamer Ursache PASSENGER_NO_SHOW (Fahrgast-No-Show nach Ablauf der Wartefrist; eine Korrektur der Ursache wirkt).',
         'Bestätigte Abholungen mit geplanter Leistung: bestätigte, nicht stornierte Buchungen mit geplanter Abholung im Zeitraum.',
         'Stornierte Buchungen (auch Fahrzeugausfall); Nicht-Erscheinen vor Ablauf der Wartefrist; Testbuchungen.',
         'Zeitpunkt der geplanten Abholung (pickup_earliest); Betriebstag in der reporting_timezone des Mandanten.', 'BUSINESS_DAY_LOCAL',
         ARRAY['service_id'], 'DRIVER_EVENT', true, NULL, 'APPROVED', 'system:seed (Vorschlag des Plans, Freigabe D-05 offen)'
  WHERE (SELECT m.executable FROM metric_definition_revision m WHERE m.tenant_id = taktovia.current_tenant() AND m.metric_id = 'no_show_rate' AND m.state = 'APPROVED' ORDER BY m.version DESC LIMIT 1) = false;
  GET DIAGNOSTICS n = ROW_COUNT;
  RETURN n;
END $$;
