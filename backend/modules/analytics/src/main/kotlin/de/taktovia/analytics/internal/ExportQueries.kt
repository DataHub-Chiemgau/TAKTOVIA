package de.taktovia.analytics.internal

/**
 * Das Ergebnis eines Exports entsteht in **einem** SQL-Statement (ein Snapshot, READ COMMITTED gilt je Statement): ein paralleler Löschjob oder eine parallele Veröffentlichung kann das Ergebnis nie
 * halb verändern. Alle Werte sind gebunden; welche Kategorien enthalten sind, bestimmen Rechte-Flags (`f_*`), nie eine Zeichenkettenkonstruktion. Zeilen mit späterem Entstehungszeitpunkt als `as_of` fehlen.
 * Gelesen werden die Tabellen der führenden Module nur lesend (`inspection_run`, `inspection_answer`, `defect_case`, `defect_case_revision`, `defect_evidence`, `time_event`, `roster_revision`,
 * `staff_shift`, `vehicle`); die Rechte der Runtime-Rolle auf diese Tabellen bleiben unverändert, geschrieben wird nur `export_job`. Freitext der Prüfantworten (`note`) und Klarnamen gehören nicht dazu.
 */
internal object ExportQueries {
    private const val INSPECTION = "(SELECT coalesce(jsonb_agg(x.doc || jsonb_build_object('proof_sha256', encode(sha256(convert_to(x.doc::text, 'UTF8')), 'hex')) ORDER BY x.sort_at, x.id), '[]'::jsonb) " +
        "FROM (SELECT r.id, r.started_at AS sort_at, jsonb_build_object('inspection_id', r.id, 'vehicle_id', r.vehicle_id, 'shift_id', r.shift_id, 'driver_ref', r.driver_ref, 'kind', r.kind, 'status', r.status, " +
        "'outcome', r.outcome, 'started_at', r.started_at, 'completed_at', r.completed_at, 'received_at', r.received_at, 'safety_revision', r.safety_revision, 'revision', r.revision, " +
        "'template_revision_id', r.template_revision_id, 'answers', (SELECT coalesce(jsonb_agg(jsonb_build_object('step_code', a.step_code, 'answer', a.answer, 'number_value', a.number_value, " +
        "'answered_at', a.answered_at, 'revision', a.revision) ORDER BY a.step_code, a.id), '[]'::jsonb) FROM taktovia.inspection_answer a WHERE a.tenant_id = r.tenant_id AND a.inspection_id = r.id " +
        "AND a.created_at <= p.as_of)) AS doc FROM taktovia.inspection_run r WHERE r.received_at <= p.as_of AND (p.vehicle IS NULL OR r.vehicle_id = p.vehicle) " +
        "AND (p.t_from IS NULL OR r.started_at >= p.t_from) AND (p.t_to IS NULL OR r.started_at < p.t_to)) x)"

    private const val DEFECT = "(SELECT coalesce(jsonb_agg(jsonb_build_object('defect_id', d.id, 'case_number', d.case_number, 'vehicle_id', d.vehicle_id, 'reported_at', d.reported_at, " +
        "'occurred_at', d.occurred_at, 'category_code', d.category_code, 'severity', d.severity, 'status', d.status, 'description', d.description, 'revision', d.revision, 'kind', d.kind, " +
        "'vehicle_area', d.vehicle_area, 'reopen_count', d.reopen_count, 'content_version', d.content_version, " +
        "'evidence', (SELECT coalesce(jsonb_agg(jsonb_build_object('evidence_id', e.id, 'sha256', e.sha256, 'original_sha256', e.original_sha256, 'mime', e.mime, 'size_bytes', e.size_bytes, " +
        "'captured_at', e.captured_at, 'received_at', e.received_at, 'revision', e.revision, 'confidentiality', e.confidentiality) ORDER BY e.received_at, e.id), '[]'::jsonb) " +
        "FROM taktovia.defect_evidence e WHERE e.tenant_id = d.tenant_id AND e.defect_id = d.id AND e.received_at <= p.as_of AND (e.confidentiality = 'STANDARD' OR p.f_conf)), " +
        "'confidential_evidence_withheld', (SELECT count(*) FROM taktovia.defect_evidence e WHERE e.tenant_id = d.tenant_id AND e.defect_id = d.id AND e.received_at <= p.as_of " +
        "AND e.confidentiality = 'CONFIDENTIAL' AND NOT p.f_conf), 'history_authorized', p.f_hist, " +
        "'history', CASE WHEN p.f_hist THEN (SELECT coalesce(jsonb_agg(jsonb_build_object('version', h.version, 'author', h.author, 'change_reason', h.change_reason, 'created_at', h.created_at, " +
        "'snapshot', h.snapshot) ORDER BY h.version), '[]'::jsonb) FROM taktovia.defect_case_revision h WHERE h.tenant_id = d.tenant_id AND h.defect_id = d.id AND h.created_at <= p.as_of) END) " +
        "ORDER BY d.created_at, d.id), '[]'::jsonb) FROM taktovia.defect_case d WHERE d.created_at <= p.as_of AND (p.vehicle IS NULL OR d.vehicle_id = p.vehicle) " +
        "AND (p.t_from IS NULL OR d.reported_at >= p.t_from) AND (p.t_to IS NULL OR d.reported_at < p.t_to))"

    private const val TIME = "(SELECT coalesce(jsonb_agg(jsonb_build_object('event_id', t.id, 'person_ref', t.person_ref, 'kind', t.kind, 'occurred_at', t.occurred_at, " +
        "'device_recorded_at', t.device_recorded_at, 'received_at', t.received_at, 'source', t.source, 'recorded_by', t.recorded_by, 'shift_id', t.shift_id, 'backfill_reason', t.backfill_reason, " +
        "'payload_sha256', t.payload_sha256, 'timezone', t.timezone) ORDER BY t.occurred_at, t.id), '[]'::jsonb) FROM taktovia.time_event t WHERE t.received_at <= p.as_of " +
        "AND t.occurred_at >= coalesce(p.t_from, p.as_of - interval '31 days') AND t.occurred_at < coalesce(p.t_to, p.as_of) AND (p.person IS NULL OR t.person_ref = p.person))"

    private const val ROSTER = "(SELECT coalesce(jsonb_agg(jsonb_build_object('roster_id', r.roster_id, 'revision', r.revision, 'state', r.state, 'depot_id', r.depot_id, 'week_start', r.week_start, " +
        "'published_at', r.published_at, 'published_by', r.published_by, 'effective_from', r.effective_from, 'publish_reason', r.publish_reason, " +
        "'shifts', (SELECT coalesce(jsonb_agg(jsonb_build_object('lineage_id', coalesce(s.lineage_id, s.id), 'shift_id', s.id, 'state', s.state, 'starts_at', s.starts_at, 'ends_at', s.ends_at, " +
        "'timezone', s.timezone, 'driver_ref', s.driver_ref, 'vehicle_id', s.vehicle_id, 'function', s.shift_function) ORDER BY s.starts_at, s.id), '[]'::jsonb) FROM taktovia.staff_shift s " +
        "WHERE s.tenant_id = r.tenant_id AND s.roster_id = r.roster_id AND s.roster_revision = r.revision)) ORDER BY r.week_start, r.roster_id, r.revision), '[]'::jsonb) " +
        "FROM taktovia.roster_revision r WHERE r.state IN ('PUBLISHED', 'SUPERSEDED') AND r.published_at <= p.as_of AND (p.depot IS NULL OR r.depot_id = p.depot) " +
        "AND (p.t_from IS NULL OR r.week_start >= (p.t_from::date - 6)) AND (p.t_to IS NULL OR r.week_start < p.t_to::date))"

    private const val VEHICLES = "(SELECT coalesce(jsonb_agg(jsonb_build_object('vehicle_id', v.id, 'label', v.vehicle_label, 'type', v.vehicle_type, 'active', v.active, 'master_revision', v.master_revision, " +
        "'safety_revision', v.safety_revision, 'created_at', v.created_at) ORDER BY v.id), '[]'::jsonb) FROM taktovia.vehicle v WHERE v.created_at <= p.as_of AND (p.vehicle IS NULL OR v.id = p.vehicle))"

    /**
     * Parameter (in dieser Reihenfolge): as_of, vehicle, t_from, t_to, person, depot, f_insp, f_def, f_hist, f_time, f_roster, f_veh, f_conf. Ergebnis: Dokument als Text und die Zeilenzahl je Kategorie
     * (Fahrzeugregister, Prüfnachweise, Schadenakten, Zeitnachweis, Dienstpläne; `NULL` = Kategorie nicht enthalten).
     */
    const val COLLECT = "WITH p AS (SELECT CAST(? AS timestamptz) AS as_of, CAST(? AS uuid) AS vehicle, CAST(? AS timestamptz) AS t_from, CAST(? AS timestamptz) AS t_to, CAST(? AS text) AS person, " +
        "CAST(? AS uuid) AS depot, CAST(? AS boolean) AS f_insp, CAST(? AS boolean) AS f_def, CAST(? AS boolean) AS f_hist, CAST(? AS boolean) AS f_time, CAST(? AS boolean) AS f_roster, " +
        "CAST(? AS boolean) AS f_veh, CAST(? AS boolean) AS f_conf), " +
        "d AS (SELECT '{}'::jsonb " +
        "|| CASE WHEN p.f_veh THEN jsonb_build_object('vehicle_register', $VEHICLES) ELSE '{}'::jsonb END " +
        "|| CASE WHEN p.f_insp THEN jsonb_build_object('inspection_proof', $INSPECTION) ELSE '{}'::jsonb END " +
        "|| CASE WHEN p.f_def THEN jsonb_build_object('defect_case', $DEFECT) ELSE '{}'::jsonb END " +
        "|| CASE WHEN p.f_time THEN jsonb_build_object('time_proof', $TIME) ELSE '{}'::jsonb END " +
        "|| CASE WHEN p.f_roster THEN jsonb_build_object('roster_archive', $ROSTER) ELSE '{}'::jsonb END AS doc FROM p) " +
        "SELECT d.doc::text, jsonb_array_length(d.doc -> 'vehicle_register'), jsonb_array_length(d.doc -> 'inspection_proof'), jsonb_array_length(d.doc -> 'defect_case'), " +
        "jsonb_array_length(d.doc -> 'time_proof'), jsonb_array_length(d.doc -> 'roster_archive') FROM d"
}
