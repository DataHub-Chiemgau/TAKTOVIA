-- WP-M4-65: Übernahme der Schichtzuweisungen aus M3 in den Dienstplan. `staff_shift` hat fachlich genau einen führenden Besitzer (Modul `staff-time`); die in M3 über die Leitstelle
-- angelegten Zuweisungen (`roster_id` NULL, `roster_revision` 0, Zustand PLANNED/STARTED/ENDED/CANCELLED) gehören danach je Kalenderwoche zu genau einer veröffentlichten Dienstplanrevision.
-- Abbildung Altzeile -> Revision (Detailvertrag m4-65-schichtuebernahme.md): Depot = das je Mandant einmalig angelegte Übernahmedepot „Altbestand (Übernahme M3)“ (M3 kannte kein Depot;
-- eine fachliche Zuordnung zu einem echten Depot ist Sache der Planer), Woche = Montag der Kalenderwoche des lokalen Schichtbeginns (`timezone`), Revisionsnummer 1, Zustand PUBLISHED, Autor und
-- Veröffentlicher `system:migration-wp-m4-65`. PLANNED wird PUBLISHED (verbindlich, wie bisher überlappungsgeschützt); STARTED, ENDED und CANCELLED behalten ihren Zustand. `id` und
-- damit `resource_release.shift_id`, `vehicle_run.shift_id` und Prüfläufe bleiben unverändert gültig. Das Überlappungsverbot (V3070/V40040) und der Zustandswächter (V3250) sind während der Übernahme
-- ausgesetzt, weil sich die Belegung nicht ändert (auch dokumentierte Doppelschichten bleiben bestehen); danach gelten sie wieder.
-- Die Funktion ist wiederholbar: übernommen werden nur Wochen ohne Dienstplan des Übernahmedepots; Zeilen, die nicht übernommen werden konnten, bleiben unverändert und werden gezählt (Rückgabe).
-- [p_tenant] begrenzt auf einen Mandanten (NULL: alle). Sie gehört dem Eigentümer (nur der Migrator oder die Betriebsrolle, kein EXECUTE für die Runtime-Rolle). Neue Zuweisungen entstehen nach dieser Migration über Dienstplanrevisionen.
SET search_path = taktovia, public;

CREATE FUNCTION adopt_legacy_staff_shifts(p_tenant uuid DEFAULT NULL) RETURNS integer LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE
  t record; g record; v_depot uuid; v_region uuid; v_roster uuid; v_actor constant text := 'system:migration-wp-m4-65'; v_adopted integer := 0; v_n integer;
BEGIN
  PERFORM set_config('app.roster_draft_access', 'on', true);
  ALTER TABLE staff_shift DISABLE TRIGGER staff_shift_overlap_guard;
  ALTER TABLE staff_shift DISABLE TRIGGER staff_shift_state_guard;
  FOR t IN SELECT DISTINCT tenant_id FROM staff_shift WHERE roster_id IS NULL AND state IN ('PLANNED', 'STARTED', 'ENDED', 'CANCELLED') AND (p_tenant IS NULL OR tenant_id = p_tenant) ORDER BY tenant_id LOOP
    v_region := substr(encode(sha256(convert_to('wp-m4-65|region|' || t.tenant_id::text, 'UTF8')), 'hex'), 1, 32)::uuid;
    v_depot  := substr(encode(sha256(convert_to('wp-m4-65|depot|' || t.tenant_id::text, 'UTF8')), 'hex'), 1, 32)::uuid;
    INSERT INTO region (tenant_id, id, name, created_by) VALUES (t.tenant_id, v_region, 'Altbestand (Übernahme M3)', v_actor) ON CONFLICT DO NOTHING;
    INSERT INTO depot (tenant_id, id, region_id, name, created_by) VALUES (t.tenant_id, v_depot, v_region, 'Altbestand (Übernahme M3)', v_actor) ON CONFLICT DO NOTHING;
    FOR g IN SELECT date_trunc('week', (s.starts_at AT TIME ZONE s.timezone)::timestamp)::date AS week_start, min(s.starts_at) AS first_start
               FROM staff_shift s WHERE s.tenant_id = t.tenant_id AND s.roster_id IS NULL AND s.state IN ('PLANNED', 'STARTED', 'ENDED', 'CANCELLED') GROUP BY 1 ORDER BY 1 LOOP
      v_roster := substr(encode(sha256(convert_to('wp-m4-65|roster|' || t.tenant_id::text || '|' || g.week_start::text, 'UTF8')), 'hex'), 1, 32)::uuid;
      IF EXISTS (SELECT 1 FROM roster_revision r WHERE r.tenant_id = t.tenant_id AND r.depot_id = v_depot AND r.week_start = g.week_start) THEN CONTINUE; END IF;
      INSERT INTO roster_revision (tenant_id, id, roster_id, depot_id, week_start, revision, state, author) VALUES (t.tenant_id, v_roster, v_roster, v_depot, g.week_start, 1, 'DRAFT', v_actor);
      UPDATE staff_shift s SET roster_id = v_roster, roster_revision = 1, depot_id = v_depot, lineage_id = COALESCE(s.lineage_id, s.id), state = CASE s.state WHEN 'PLANNED' THEN 'PUBLISHED' ELSE s.state END
        WHERE s.tenant_id = t.tenant_id AND s.roster_id IS NULL AND s.state IN ('PLANNED', 'STARTED', 'ENDED', 'CANCELLED') AND date_trunc('week', (s.starts_at AT TIME ZONE s.timezone)::timestamp)::date = g.week_start;
      GET DIAGNOSTICS v_n = ROW_COUNT;
      v_adopted := v_adopted + v_n;
      UPDATE roster_revision SET state = 'PUBLISHED', published_at = clock_timestamp(), published_by = v_actor, effective_from = g.first_start,
        publish_reason = 'Übernahme der Schichtzuweisungen aus M3 (WP-M4-65)' WHERE tenant_id = t.tenant_id AND id = v_roster;
      INSERT INTO roster_shift_change (tenant_id, id, roster_id, roster_revision, lineage_id, shift_id, change_kind, shift_revision, actor, snapshot)
        SELECT s.tenant_id, gen_random_uuid(), s.roster_id, s.roster_revision, COALESCE(s.lineage_id, s.id), s.id, 'PUBLISHED', s.shift_revision, v_actor,
               jsonb_build_object('shift_id', s.id, 'lineage_id', COALESCE(s.lineage_id, s.id), 'state', s.state, 'driver_ref', s.driver_ref, 'vehicle_id', s.vehicle_id, 'starts_at', s.starts_at,
                 'ends_at', s.ends_at, 'timezone', s.timezone, 'function', s.shift_function, 'required_qualification', s.required_qualification, 'staffing_need', s.staffing_need,
                 'break_plan', s.break_plan, 'depot_id', s.depot_id, 'operator_id', s.operator_id, 'operating_day', s.operating_day)
          FROM staff_shift s WHERE s.tenant_id = t.tenant_id AND s.roster_id = v_roster AND s.roster_revision = 1;
    END LOOP;
  END LOOP;
  ALTER TABLE staff_shift ENABLE TRIGGER staff_shift_overlap_guard;
  ALTER TABLE staff_shift ENABLE TRIGGER staff_shift_state_guard;
  RETURN v_adopted;
END $$;
REVOKE ALL ON FUNCTION adopt_legacy_staff_shifts(uuid) FROM PUBLIC;

-- Bestand der laufenden Datenbank übernehmen. Die Tabellen erzwingen RLS auch für den Eigentümer (FORCE); für die einmalige Übernahme über alle Mandanten wird das je Tabelle kurz
-- aufgehoben und im selben Schritt wiederhergestellt (eine Transaktion, andere Sitzungen sehen nie einen Zwischenstand).
ALTER TABLE tenant NO FORCE ROW LEVEL SECURITY;
ALTER TABLE region NO FORCE ROW LEVEL SECURITY;
ALTER TABLE depot NO FORCE ROW LEVEL SECURITY;
ALTER TABLE roster_revision NO FORCE ROW LEVEL SECURITY;
ALTER TABLE roster_shift_change NO FORCE ROW LEVEL SECURITY;
ALTER TABLE staff_shift NO FORCE ROW LEVEL SECURITY;
SELECT adopt_legacy_staff_shifts(NULL);
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;
ALTER TABLE region FORCE ROW LEVEL SECURITY;
ALTER TABLE depot FORCE ROW LEVEL SECURITY;
ALTER TABLE roster_revision FORCE ROW LEVEL SECURITY;
ALTER TABLE roster_shift_change FORCE ROW LEVEL SECURITY;
ALTER TABLE staff_shift FORCE ROW LEVEL SECURITY;
