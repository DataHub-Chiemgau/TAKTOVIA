-- ENTWURF (WP-M3-01): DDL der M3-Tabellen als Eingabe für WP-M3-02. KEIN Migrationsskript, liegt bewusst außerhalb von db/migrations.
-- Konvention: tenant_id NOT NULL, PK (tenant_id,id), lokale FKs zusammengesetzt, RLS ENABLE+FORCE mit Policy tenant_isolation, revision, created_at.
-- Zustandswerte entsprechen vertraege/zustaende/*.md; unveränderliche Tabellen bekommen in WP-M3-02 den Trigger reject_mutation().
-- Geprüft wird nur die Ausführbarkeit gegen den Stand nach M2 (in einer Transaktion mit ROLLBACK, tests/contract/test_m3_vertraege.py).
SET search_path = taktovia, public;

-- Sperrgründe je Fahrzeug (OP-01)
CREATE TABLE vehicle_block (
  tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, vehicle_id uuid NOT NULL,
  source text NOT NULL CHECK (source IN ('OPERATIONS','SAFETY')),
  reason_code text NOT NULL CHECK (reason_code IN ('OPERATIONAL_DISRUPTION','DEFECT_CRITICAL','INSPECTION_FAILED','MANUAL','OTHER')),
  status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','LIFTED')),
  set_by text NOT NULL, set_at timestamptz NOT NULL DEFAULT clock_timestamp(), lifted_by text, lifted_at timestamptz,
  release_decision_id uuid, revision bigint NOT NULL DEFAULT 1 CHECK (revision >= 1), created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id), FOREIGN KEY (tenant_id, vehicle_id) REFERENCES vehicle(tenant_id, id),
  CONSTRAINT vehicle_block_lift_consistent CHECK ((status = 'LIFTED') = (lifted_at IS NOT NULL AND lifted_by IS NOT NULL))
);
CREATE INDEX vehicle_block_active ON vehicle_block (tenant_id, vehicle_id) WHERE status = 'ACTIVE';

-- Checklistenvorlagen und Prüfläufe (CHK)
CREATE TABLE checklist_template_revision (
  tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, template_id uuid NOT NULL, version int NOT NULL CHECK (version >= 1),
  vehicle_class text NOT NULL, service_mode text NOT NULL, state text NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT','PUBLISHED','RETIRED')),
  published_at timestamptz, created_at timestamptz NOT NULL DEFAULT clock_timestamp(), revision bigint NOT NULL DEFAULT 1,
  PRIMARY KEY (tenant_id, id), UNIQUE (tenant_id, template_id, version)
);
CREATE TABLE checklist_step (
  tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, template_revision_id uuid NOT NULL, step_code text NOT NULL, position int NOT NULL,
  title text NOT NULL, mandatory boolean NOT NULL, safety_critical boolean NOT NULL, answer_kind text NOT NULL CHECK (answer_kind IN ('OK_DEFECT_NA','NUMBER','TEXT_SHORT')),
  photo_required_on_defect boolean NOT NULL DEFAULT false,
  PRIMARY KEY (tenant_id, id), UNIQUE (tenant_id, template_revision_id, step_code),
  FOREIGN KEY (tenant_id, template_revision_id) REFERENCES checklist_template_revision(tenant_id, id)
);
CREATE TABLE inspection_run (
  tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, shift_id uuid NOT NULL, vehicle_id uuid NOT NULL, driver_ref uuid NOT NULL, template_revision_id uuid NOT NULL,
  kind text NOT NULL CHECK (kind IN ('PRE_SHIFT','FOLLOW_UP','VEHICLE_CHANGE')),
  status text NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS','COMPLETED','EXPIRED','CANCELED')),
  outcome text CHECK (outcome IN ('PASSED','PASSED_RESTRICTED','BLOCKED','CLARIFICATION_REQUIRED')),
  started_at timestamptz NOT NULL, completed_at timestamptz, received_at timestamptz, safety_revision bigint,
  revision bigint NOT NULL DEFAULT 1, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id), FOREIGN KEY (tenant_id, shift_id) REFERENCES staff_shift(tenant_id, id), FOREIGN KEY (tenant_id, vehicle_id) REFERENCES vehicle(tenant_id, id),
  FOREIGN KEY (tenant_id, template_revision_id) REFERENCES checklist_template_revision(tenant_id, id),
  CONSTRAINT inspection_completed_consistent CHECK ((status = 'COMPLETED') = (outcome IS NOT NULL AND completed_at IS NOT NULL AND received_at IS NOT NULL))
);
CREATE UNIQUE INDEX inspection_one_open ON inspection_run (tenant_id, shift_id, vehicle_id, kind) WHERE status = 'IN_PROGRESS';
CREATE TABLE inspection_answer (
  tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, inspection_id uuid NOT NULL, step_code text NOT NULL,
  answer text NOT NULL CHECK (answer IN ('OK','DEFECT','NOT_APPLICABLE','NOT_CHECKABLE')), number_value numeric, note text, answered_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY (tenant_id, id), UNIQUE (tenant_id, inspection_id, step_code),
  FOREIGN KEY (tenant_id, inspection_id) REFERENCES inspection_run(tenant_id, id)
);
CREATE TABLE inspection_amendment (
  tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, inspection_id uuid NOT NULL, step_code text NOT NULL, amended_by text NOT NULL, reason text NOT NULL,
  new_answer text NOT NULL CHECK (new_answer IN ('OK','DEFECT','NOT_APPLICABLE','NOT_CHECKABLE')), created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id), FOREIGN KEY (tenant_id, inspection_id) REFERENCES inspection_run(tenant_id, id)
);

-- Schadenakte (DMG)
CREATE TABLE defect_case (
  tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, case_number text NOT NULL, vehicle_id uuid NOT NULL, reported_by uuid NOT NULL,
  reported_at timestamptz NOT NULL DEFAULT clock_timestamp(), occurred_at timestamptz NOT NULL, location_kind text CHECK (location_kind IN ('COORDINATE','ADDRESS_TEXT','FREE_TEXT','DEPOT')),
  location_value text, category_code text NOT NULL, description text, severity text CHECK (severity IN ('SAFETY_CRITICAL','OPERATIONALLY_RELEVANT','COSMETIC','OTHER')),
  status text NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW','REVIEWED','IN_PROGRESS','REPAIR','CLOSED','DISMISSED')), assignee_ref uuid, source_inspection_id uuid, source_step_code text,
  dismissed_reason text, revision bigint NOT NULL DEFAULT 1, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id), UNIQUE (tenant_id, case_number), FOREIGN KEY (tenant_id, vehicle_id) REFERENCES vehicle(tenant_id, id),
  FOREIGN KEY (tenant_id, source_inspection_id) REFERENCES inspection_run(tenant_id, id),
  CONSTRAINT defect_dismissed_needs_reason CHECK (status <> 'DISMISSED' OR dismissed_reason IS NOT NULL)
);
CREATE TABLE defect_evidence (
  tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, defect_id uuid NOT NULL, media_ref text NOT NULL, sha256 text NOT NULL CHECK (length(sha256) = 64),
  captured_at timestamptz NOT NULL, received_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_by text NOT NULL,
  PRIMARY KEY (tenant_id, id), FOREIGN KEY (tenant_id, defect_id) REFERENCES defect_case(tenant_id, id)
);
CREATE TABLE defect_status_history (
  tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, defect_id uuid NOT NULL, from_status text, to_status text NOT NULL, actor text NOT NULL, reason text,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY (tenant_id, id), FOREIGN KEY (tenant_id, defect_id) REFERENCES defect_case(tenant_id, id)
);
CREATE TABLE repair_order (
  tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, defect_id uuid NOT NULL, vehicle_id uuid NOT NULL,
  status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_PROGRESS','PROOF_RECORDED','CANCELED')), workshop_ref text, planned_at timestamptz, cost_reference text,
  revision bigint NOT NULL DEFAULT 1, created_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, defect_id) REFERENCES defect_case(tenant_id, id), FOREIGN KEY (tenant_id, vehicle_id) REFERENCES vehicle(tenant_id, id)
);
CREATE TABLE vehicle_release_decision (
  tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, vehicle_id uuid NOT NULL, repair_order_id uuid NOT NULL, follow_up_inspection_id uuid NOT NULL,
  decision text NOT NULL CHECK (decision IN ('RELEASED','PENDING_SECOND_APPROVAL','REJECTED')), decided_by text NOT NULL, second_approver text,
  decided_at timestamptz NOT NULL DEFAULT clock_timestamp(), safety_revision bigint NOT NULL, PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, vehicle_id) REFERENCES vehicle(tenant_id, id), FOREIGN KEY (tenant_id, repair_order_id) REFERENCES repair_order(tenant_id, id),
  FOREIGN KEY (tenant_id, follow_up_inspection_id) REFERENCES inspection_run(tenant_id, id),
  CONSTRAINT release_four_eyes CHECK (decision <> 'RELEASED' OR (second_approver IS NOT NULL AND second_approver <> decided_by))
);

-- Fahrer: Geräte, Ereigniseingang, Positionen, Dienststatus
CREATE TABLE driver_device (
  tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, session_id uuid NOT NULL, subject text NOT NULL, platform text NOT NULL CHECK (platform IN ('ANDROID','IOS')),
  app_version text NOT NULL, registered_at timestamptz NOT NULL DEFAULT clock_timestamp(), revoked_at timestamptz, revision bigint NOT NULL DEFAULT 1, PRIMARY KEY (tenant_id, id)
);
CREATE TABLE driver_event (
  tenant_id uuid NOT NULL REFERENCES tenant(id), event_id uuid NOT NULL, device_id uuid NOT NULL, shift_id uuid NOT NULL, vehicle_id uuid NOT NULL, event_type text NOT NULL,
  payload_sha256 text NOT NULL CHECK (length(payload_sha256) = 64), outcome text NOT NULL CHECK (outcome IN ('APPLIED','REVIEW_REQUIRED')), reason_code text,
  plan_revision bigint NOT NULL, device_recorded_at timestamptz NOT NULL, occurred_at timestamptz NOT NULL, received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, event_id), FOREIGN KEY (tenant_id, device_id) REFERENCES driver_device(tenant_id, id)
);
CREATE TABLE position_sample (
  tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, vehicle_id uuid NOT NULL, shift_id uuid NOT NULL, position geography(Point, 4326) NOT NULL,
  accuracy_m real NOT NULL, measured_at timestamptz NOT NULL, received_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, vehicle_id) REFERENCES vehicle(tenant_id, id)
);
CREATE INDEX position_sample_latest ON position_sample (tenant_id, vehicle_id, measured_at DESC);
CREATE TABLE vehicle_status_history (
  tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, vehicle_id uuid NOT NULL, shift_id uuid NOT NULL, from_status text, to_status text NOT NULL,
  trigger text NOT NULL CHECK (trigger IN ('DRIVER','SYSTEM','DISPATCH')), created_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY (tenant_id, id)
);

-- Alarme, Incident, Workflow (AUT, OP-02)
CREATE TABLE alert_policy_revision (
  tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, revision_no int NOT NULL, rules jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY (tenant_id, id)
);
CREATE TABLE business_alert (
  tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, alert_kind text NOT NULL, priority text NOT NULL CHECK (priority IN ('CRITICAL','HIGH','NORMAL')),
  vehicle_id uuid, dedupe_key text NOT NULL, state text NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN','ACKNOWLEDGED')), created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  revision bigint NOT NULL DEFAULT 1, PRIMARY KEY (tenant_id, id)
);
CREATE UNIQUE INDEX business_alert_dedupe_open ON business_alert (tenant_id, dedupe_key) WHERE state = 'OPEN';
CREATE TABLE workflow_rule_revision (
  tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, event_kind text NOT NULL, rule jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY (tenant_id, id)
);
CREATE TABLE incident (
  tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, kind text NOT NULL CHECK (kind IN ('VEHICLE_OUTAGE','DEFECT','REJECTION','CRITICAL_DELAY')), vehicle_id uuid,
  status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','REPLACEMENT_SEARCH','RESOLVED','CLOSED_NO_REPLACEMENT')), dedupe_key text NOT NULL, rule_revision_id uuid,
  replacement_operation_id uuid, opened_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  revision bigint NOT NULL DEFAULT 1, PRIMARY KEY (tenant_id, id), FOREIGN KEY (tenant_id, rule_revision_id) REFERENCES workflow_rule_revision(tenant_id, id)
);
CREATE UNIQUE INDEX incident_one_open_per_key ON incident (tenant_id, dedupe_key) WHERE status IN ('OPEN','REPLACEMENT_SEARCH');
CREATE TABLE workflow_run_log (
  tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, incident_id uuid, event_id uuid NOT NULL, rule_revision_id uuid, action text NOT NULL, outcome text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY (tenant_id, id), UNIQUE (tenant_id, event_id, action)
);

-- booking_change (Kap. 04 §4.4): schreibt ausschließlich booking-dispatch
CREATE TABLE booking_change (
  tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, booking_id uuid NOT NULL, change_kind text NOT NULL CHECK (change_kind IN ('MODIFY','CANCEL','REPLACE_OFFER')),
  actor text NOT NULL, reason text, created_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY (tenant_id, id), FOREIGN KEY (tenant_id, booking_id) REFERENCES booking(tenant_id, id)
);

DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['vehicle_block','checklist_template_revision','checklist_step','inspection_run','inspection_answer','inspection_amendment','defect_case','defect_evidence','defect_status_history',
    'repair_order','vehicle_release_decision','driver_device','driver_event','position_sample','vehicle_status_history','alert_policy_revision','business_alert','workflow_rule_revision','incident','workflow_run_log','booking_change'] LOOP
    EXECUTE format('ALTER TABLE taktovia.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE taktovia.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON taktovia.%I USING (tenant_id=taktovia.current_tenant()) WITH CHECK (tenant_id=taktovia.current_tenant())', t);
  END LOOP;
END $$;
