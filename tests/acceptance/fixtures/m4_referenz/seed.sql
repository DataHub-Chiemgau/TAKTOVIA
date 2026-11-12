-- WP-M4-02: Seed der Referenzdaten für M4 (zwei Mandanten A/B, ausschließlich synthetische Daten). KEIN Modulcode: wird unter der Rolle taktovia_migrator geladen
-- (tests/acceptance/taktovia_at/m4_referenz.py bzw. die Backendtests), nie unter Superuser. Je Mandant setzt der Seed transaktionslokal den Tenantkontext (RLS).
-- Idempotent (ON CONFLICT DO NOTHING), deterministisch (alle IDs md5('m4ref/<mandant>/<schlüssel>')::uuid), keine Statements mit freier Uhrzeit.
-- Je Mandant: zwei Rechtsträger (X = Eigentümer, Y = Betreiber), zwei Depots, zwei Kostenstellen, zwei Fahrzeuge mit Rollen, zwei Fahrer mit Qualifikationen und drei Schichten
-- (Tagschicht, Nachtschicht über die Herbstumstellung 25.10.2026, Nachtschicht über die Frühjahrsumstellung 28.03.2027). Nur Mandant A hat die Musterfahrt
-- (Dienst, Haltepunkte, Fahrtenplan mit dokumentierten Kilometern, bestätigte Buchung mit Zusagesnapshot, zwei Positionsproben).
-- Hinweis: das Verfügbarkeitsfenster der Referenzregion für M2 (Seed m2_referenzregion) bleibt unberührt; die Mandanten dieses Seeds sind eigene (…0a4, …0b4).
SET search_path = taktovia, public;

DO $seed$
DECLARE
  m record;
  tid uuid;
  l text;
  -- Hilfsfunktion als Ausdruck: md5('m4ref/<l>/<key>')::uuid
BEGIN
  FOR m IN SELECT * FROM (VALUES ('a', '00000000-0000-4000-8000-0000000000a4'::uuid, 'Mandant A (synthetisch)'),
                                 ('b', '00000000-0000-4000-8000-0000000000b4'::uuid, 'Mandant B (synthetisch)')) v(lbl, id, name)
  LOOP
    l := m.lbl; tid := m.id;
    PERFORM set_config('app.tenant_id', tid::text, true);
    INSERT INTO tenant(id, name, status) VALUES (tid, m.name, 'ACTIVE') ON CONFLICT (id) DO NOTHING;
    INSERT INTO currency(code, exponent) VALUES ('EUR', 2) ON CONFLICT DO NOTHING;

    -- Rechtsträger X (Eigentümer) und Y (Betreiber) mit je einer Organisation
    INSERT INTO legal_entity(tenant_id, id, name, country) VALUES
      (tid, md5('m4ref/'||l||'/le-x')::uuid, 'Rechtsträger X M4-Referenz '||upper(l)||' (synthetisch)', 'DE'),
      (tid, md5('m4ref/'||l||'/le-y')::uuid, 'Rechtsträger Y M4-Referenz '||upper(l)||' (synthetisch)', 'DE') ON CONFLICT DO NOTHING;
    INSERT INTO organization(tenant_id, id, legal_entity_id, name, federation_ref, roles, roles_valid_from) VALUES
      (tid, md5('m4ref/'||l||'/org-x')::uuid, md5('m4ref/'||l||'/le-x')::uuid, 'Eigentümer X '||upper(l)||' (synthetisch)', 'fed-org-m4-'||l||'-x', '{CLIENT}', '2026-01-01T00:00:00Z'),
      (tid, md5('m4ref/'||l||'/org-y')::uuid, md5('m4ref/'||l||'/le-y')::uuid, 'Betreiber Y '||upper(l)||' (synthetisch)', 'fed-org-m4-'||l||'-y', '{TRANSPORT_COMPANY}', '2026-01-01T00:00:00Z') ON CONFLICT DO NOTHING;

    -- Region, zwei Depots, zwei Kostenstellen
    INSERT INTO region(tenant_id, id, name, created_by) VALUES (tid, md5('m4ref/'||l||'/reg-1')::uuid, 'Region Referenz (synthetisch)', 'system:m4-referenz') ON CONFLICT DO NOTHING;
    INSERT INTO depot(tenant_id, id, region_id, name, created_by) VALUES
      (tid, md5('m4ref/'||l||'/dep-1')::uuid, md5('m4ref/'||l||'/reg-1')::uuid, 'Depot Nord (synthetisch)', 'system:m4-referenz'),
      (tid, md5('m4ref/'||l||'/dep-2')::uuid, md5('m4ref/'||l||'/reg-1')::uuid, 'Depot Süd (synthetisch)', 'system:m4-referenz') ON CONFLICT DO NOTHING;
    INSERT INTO cost_object(tenant_id, id, legal_entity_id, kind, business_ref, name, region_id, created_by) VALUES
      (tid, md5('m4ref/'||l||'/cc-1')::uuid, md5('m4ref/'||l||'/le-y')::uuid, 'COST_CENTER', 'KST-100', 'Kostenstelle Betrieb Nord (synthetisch)', md5('m4ref/'||l||'/reg-1')::uuid, 'system:m4-referenz'),
      (tid, md5('m4ref/'||l||'/cc-2')::uuid, md5('m4ref/'||l||'/le-y')::uuid, 'COST_CENTER', 'KST-200', 'Kostenstelle Betrieb Süd (synthetisch)', md5('m4ref/'||l||'/reg-1')::uuid, 'system:m4-referenz') ON CONFLICT DO NOTHING;

    -- Fahrzeuge (Eigentümer X, Betreiber Y; Fahrzeug 2 mit Rollstuhlplatz), Rollen
    INSERT INTO vehicle(tenant_id, id, owner_org_ref, capacity_config, active_block, safety_revision, vehicle_label, vehicle_type, active, created_by) VALUES
      (tid, md5('m4ref/'||l||'/veh-1')::uuid, 'fed-org-m4-'||l||'-x', '{"schema_version": "1.0", "seats": 4, "wheelchair": 0, "equipment": []}', false, 1, 'M4-'||upper(l)||'-1', 'KLEINBUS', true, 'system:m4-referenz'),
      (tid, md5('m4ref/'||l||'/veh-2')::uuid, 'fed-org-m4-'||l||'-x', '{"schema_version": "1.0", "seats": 6, "wheelchair": 1, "equipment": ["RAMP"]}', false, 1, 'M4-'||upper(l)||'-2', 'KLEINBUS', true, 'system:m4-referenz') ON CONFLICT DO NOTHING;
    INSERT INTO vehicle_assignment(tenant_id, id, vehicle_id, organization_id, role, valid_from, created_by)
      SELECT tid, md5('m4ref/'||l||'/va-'||v||'-'||r)::uuid, md5('m4ref/'||l||'/veh-'||v)::uuid, md5('m4ref/'||l||'/org-'||o)::uuid, r, '2026-01-01T00:00:00Z', 'system:m4-referenz'
      FROM (VALUES ('1', 'OWNER', 'x'), ('1', 'OPERATOR', 'y'), ('2', 'OWNER', 'x'), ('2', 'OPERATOR', 'y')) t(v, r, o) ON CONFLICT DO NOTHING;

    -- Fahrer mit Qualifikationen (Fahrer 2: Personenbeförderung nur bis 31.12.2026)
    INSERT INTO driver(tenant_id, id, display_name, contact_channel, contact_value) VALUES
      (tid, md5('m4ref/'||l||'/drv-1')::uuid, 'Fahrer Eins '||upper(l)||' (synthetisch)', 'APP', 'geraet-synthetisch-'||l||'-1'),
      (tid, md5('m4ref/'||l||'/drv-2')::uuid, 'Fahrer Zwei '||upper(l)||' (synthetisch)', 'APP', 'geraet-synthetisch-'||l||'-2') ON CONFLICT DO NOTHING;
    INSERT INTO qualification_revision(tenant_id, id, driver_id, qualification_code, revision, valid_from, valid_to, driver_seq, created_by) VALUES
      (tid, md5('m4ref/'||l||'/q-1-pb')::uuid, md5('m4ref/'||l||'/drv-1')::uuid, 'PERSONENBEFOERDERUNG', 1, '2026-01-01T00:00:00Z', NULL, 1, 'system:m4-referenz'),
      (tid, md5('m4ref/'||l||'/q-1-rs')::uuid, md5('m4ref/'||l||'/drv-1')::uuid, 'ROLLSTUHLBEFOERDERUNG', 1, '2026-01-01T00:00:00Z', NULL, 2, 'system:m4-referenz'),
      (tid, md5('m4ref/'||l||'/q-2-pb')::uuid, md5('m4ref/'||l||'/drv-2')::uuid, 'PERSONENBEFOERDERUNG', 1, '2026-01-01T00:00:00Z', '2026-12-31T23:00:00Z', 1, 'system:m4-referenz') ON CONFLICT DO NOTHING;

    -- Schichten (Europe/Berlin): Tagschicht 01.12.2026 06:00-14:00 (7 h netto Fahrzeit unabhängig), Herbst 24.10. 22:00 bis 25.10.2026 06:00 (9 h real, 25.10. hat 25 Stunden),
    -- Frühjahr 27.03. 22:00 bis 28.03.2027 06:00 (7 h real, 28.03. hat 23 Stunden)
    INSERT INTO staff_shift(tenant_id, id, driver_ref, vehicle_id, starts_at, ends_at, timezone, roster_revision, state) VALUES
      (tid, md5('m4ref/'||l||'/shift-day')::uuid, md5('m4ref/'||l||'/drv-1')::uuid, md5('m4ref/'||l||'/veh-1')::uuid, '2026-12-01T05:00:00Z', '2026-12-01T13:00:00Z', 'Europe/Berlin', 0, 'PUBLISHED'),
      (tid, md5('m4ref/'||l||'/shift-dst-autumn')::uuid, md5('m4ref/'||l||'/drv-1')::uuid, md5('m4ref/'||l||'/veh-1')::uuid, '2026-10-24T20:00:00Z', '2026-10-25T05:00:00Z', 'Europe/Berlin', 0, 'PUBLISHED'),
      (tid, md5('m4ref/'||l||'/shift-dst-spring')::uuid, md5('m4ref/'||l||'/drv-2')::uuid, md5('m4ref/'||l||'/veh-2')::uuid, '2027-03-27T21:00:00Z', '2027-03-28T04:00:00Z', 'Europe/Berlin', 0, 'PUBLISHED') ON CONFLICT DO NOTHING;
  END LOOP;

  -- ===== Musterfahrt (nur Mandant A) =====
  tid := '00000000-0000-4000-8000-0000000000a4'; l := 'a';
  PERFORM set_config('app.tenant_id', tid::text, true);
  INSERT INTO config_revision(tenant_id, id, scope_kind, scope_id, revision, schema_version, values_json, effective_from, created_by, reason)
    VALUES (tid, md5('m4ref/a/cfg-svc')::uuid, 'SERVICE', md5('m4ref/a/svc-1')::uuid, 1, '1', '{}', '2026-01-01T00:00:00Z', 'system:m4-referenz', 'Fixture M4-Referenz') ON CONFLICT DO NOTHING;
  INSERT INTO service(tenant_id, id, operator_id, name, timezone, config_revision_id)
    VALUES (tid, md5('m4ref/a/svc-1')::uuid, md5('m4ref/a/org-y')::uuid, 'Rufbus Referenz (synthetisch)', 'Europe/Berlin', md5('m4ref/a/cfg-svc')::uuid) ON CONFLICT DO NOTHING;
  INSERT INTO stop_revision(tenant_id, id, stop_id, revision, point, name, accessibility, approved_by, valid_from) VALUES
    (tid, md5('m4ref/a/stoprev-depot')::uuid, md5('m4ref/a/stop-depot')::uuid, 1, ST_SetSRID(ST_MakePoint(13.700, 51.040), 4326), 'Depot Nord', '{"schema_version": "1.0", "step_free": "YES", "wheelchair_boarding": "YES"}', 'synthetic', '2026-01-01T00:00:00Z'),
    (tid, md5('m4ref/a/stoprev-pickup')::uuid, md5('m4ref/a/stop-pickup')::uuid, 1, ST_SetSRID(ST_MakePoint(13.740, 51.050), 4326), 'Rathaus', '{"schema_version": "1.0", "step_free": "YES", "wheelchair_boarding": "YES"}', 'synthetic', '2026-01-01T00:00:00Z'),
    (tid, md5('m4ref/a/stoprev-dropoff')::uuid, md5('m4ref/a/stop-dropoff')::uuid, 1, ST_SetSRID(ST_MakePoint(13.800, 51.080), 4326), 'Bahnhof', '{"schema_version": "1.0", "step_free": "YES", "wheelchair_boarding": "YES"}', 'synthetic', '2026-01-01T00:00:00Z') ON CONFLICT DO NOTHING;
  INSERT INTO vehicle_run(tenant_id, id, vehicle_id, shift_id, revision, state)
    VALUES (tid, md5('m4ref/a/run-1')::uuid, md5('m4ref/a/veh-1')::uuid, md5('m4ref/a/shift-day')::uuid, 1, 'ACTIVE') ON CONFLICT DO NOTHING;
  -- Fahrtenplan: Depot 06:00Z -> Abholung 06:15Z (4200 m, 900 s) -> Ausstieg 06:36Z (8200 m, 1260 s); Summe 12400 m
  INSERT INTO capacity_hold(tenant_id, id, run_id, run_revision, federation_ref, capacity_vector, interval_from, interval_to, expires_at, state)
    VALUES (tid, md5('m4ref/a/hold-1')::uuid, md5('m4ref/a/run-1')::uuid, 1, 'fed-hold-m4-a-1', '{"schema_version": "1.0", "seats": 2, "wheelchair": 0, "equipment": []}', 1, 2, '2026-12-01T05:10:00Z', 'CONSUMED') ON CONFLICT DO NOTHING;
  INSERT INTO booking(tenant_id, id, hold_id, service_id, federation_ref, state, promise_snapshot, revision) VALUES
    (tid, md5('m4ref/a/bkg-1')::uuid, md5('m4ref/a/hold-1')::uuid, md5('m4ref/a/svc-1')::uuid, 'fed-bkg-m4-a-1', 'CONFIRMED',
     '{"pickup_earliest": "2026-12-01T06:10:00Z", "pickup_latest": "2026-12-01T06:25:00Z", "arrival_earliest": "2026-12-01T06:30:00Z", "arrival_latest": "2026-12-01T06:45:00Z", "passengers": 2}', 1) ON CONFLICT DO NOTHING;
  INSERT INTO route_stop(tenant_id, id, run_id, run_revision, seq, stop_revision_id, planned_at, boardings, alightings, leg_duration_sec, leg_distance_m) VALUES
    (tid, md5('m4ref/a/rs-0')::uuid, md5('m4ref/a/run-1')::uuid, 1, 0, md5('m4ref/a/stoprev-depot')::uuid, '2026-12-01T06:00:00Z', '[]', '[]', 900, 4200),
    (tid, md5('m4ref/a/rs-1')::uuid, md5('m4ref/a/run-1')::uuid, 1, 1, md5('m4ref/a/stoprev-pickup')::uuid, '2026-12-01T06:15:00Z',
       jsonb_build_array(jsonb_build_object('booking_id', md5('m4ref/a/bkg-1')::uuid, 'vector', jsonb_build_object('schema_version', '1.0', 'seats', 2, 'wheelchair', 0, 'equipment', jsonb_build_array()))), '[]', 1260, 8200),
    (tid, md5('m4ref/a/rs-2')::uuid, md5('m4ref/a/run-1')::uuid, 1, 2, md5('m4ref/a/stoprev-dropoff')::uuid, '2026-12-01T06:36:00Z', '[]',
       jsonb_build_array(jsonb_build_object('booking_id', md5('m4ref/a/bkg-1')::uuid, 'vector', jsonb_build_object('schema_version', '1.0', 'seats', 2, 'wheelchair', 0, 'equipment', jsonb_build_array()))), NULL, NULL) ON CONFLICT DO NOTHING;
  INSERT INTO position_sample(tenant_id, id, vehicle_id, shift_id, position, accuracy_m, measured_at, created_by) VALUES
    (tid, md5('m4ref/a/pos-1')::uuid, md5('m4ref/a/veh-1')::uuid, md5('m4ref/a/shift-day')::uuid, ST_SetSRID(ST_MakePoint(13.741, 51.049), 4326)::geography, 8.0, '2026-12-01T06:16:00Z', 'system:m4-referenz'),
    (tid, md5('m4ref/a/pos-2')::uuid, md5('m4ref/a/veh-1')::uuid, md5('m4ref/a/shift-day')::uuid, ST_SetSRID(ST_MakePoint(13.799, 51.079), 4326)::geography, 9.5, '2026-12-01T06:37:00Z', 'system:m4-referenz') ON CONFLICT DO NOTHING;

  -- travel_attempt für PH-AT-021: zehn Versuche, drei Ablehnungen (zwei mit Nebengründen), Mandant A
  PERFORM 1;
  INSERT INTO travel_attempt(tenant_id, id, created_at, channel, primary_rejection, secondary_rejections, coarse_origin, coarse_destination, service_id, time_kind, booking_kind, desired_at, passengers, wheelchair_spaces, outcome)
    SELECT tid, md5('m4ref/a/att-'||n)::uuid, '2026-12-01T05:00:00Z'::timestamptz + (n || ' minutes')::interval, 'WEB',
           CASE WHEN n = 8 THEN 'OUTSIDE_AREA' WHEN n = 9 THEN 'NO_CAPACITY' WHEN n = 10 THEN 'OUTSIDE_HOURS' END,
           CASE WHEN n = 9 THEN ARRAY['NO_STOP_IN_RANGE'] WHEN n = 10 THEN ARRAY['LEAD_TIME', 'NO_STAFF'] ELSE ARRAY[]::text[] END,
           'zone-nord', 'zone-mitte', md5('m4ref/a/svc-1')::uuid, 'DEPARTURE', 'ADVANCE', '2026-12-02T08:00:00Z'::timestamptz + (n || ' minutes')::interval, 1 + n % 3, 0,
           CASE WHEN n >= 8 THEN 'REJECTED' ELSE 'OFFERED' END
    FROM generate_series(1, 10) n ON CONFLICT DO NOTHING;
END
$seed$;
