-- WP-M3-02: Tabellen von M3 (Fahrer, Leitstelle, Flotte/Sicherheit) mit RLS und Unveränderlichkeit.
-- Entwurf: docs/umsetzungsplan/vertraege/m3-ddl-entwurf.sql (WP-M3-01), hier ausgeführt und um die in WP-M3-02 aufgezählten Zusatztabellen ergänzt.
-- Konvention (Kap. 04 §4.1): tenant_id NOT NULL, Primärschlüssel (tenant_id,id), lokale Fremdschlüssel zusammengesetzt, RLS ENABLE und FORCE mit Policy
-- tenant_isolation (USING und WITH CHECK), Revision, created_at, Urheber. Unveränderliche Nachweise schützen Trigger (SQLSTATE 23514, auch für die Administration).
-- Bereits vorhanden und hier nicht neu angelegt: driver_device und driver_event (V3240, WP-M3-24), vehicle_assignment (V1340), driver und
-- qualification_revision (V1350). Nicht im Umfang: charging_slot, route_stop, passenger_trip, Partitionierung von position_sample.
SET search_path = taktovia, public;

-- ---------------------------------------------------------------------------------------------------------------------------------------------------
-- 1. Additive Erweiterung der Kerntabellen (expand/contract: bestehende Zeilen und M2-Fixtures bleiben gültig, neue Spalten sind nullbar oder haben einen Default)
-- ---------------------------------------------------------------------------------------------------------------------------------------------------

-- vehicle (WP-M3-03/-04): Kennung, Typ, Ausstattung, Aktivstatus, Stammdaten-Revision (getrennt von safety_revision).
-- vehicle_label und vehicle_type sind in der Datenbank nullbar, weil die M2-Fixtures ohne sie angelegt wurden (contract-Phase später); die API verlangt sie als Pflichtangabe.
-- Der V1-Default active_block = true bleibt: ein neues Fahrzeug ist bis zur Einsatzfreigabe nicht disponierbar (OP-01, WP-M3-03 Kriterium 6).
ALTER TABLE vehicle
  ADD COLUMN vehicle_label          text,
  ADD COLUMN vehicle_type           text,
  ADD COLUMN equipment              text[]      NOT NULL DEFAULT '{}',
  ADD COLUMN active                 boolean     NOT NULL DEFAULT true,
  ADD COLUMN master_revision        bigint      NOT NULL DEFAULT 1 CHECK (master_revision >= 1),
  ADD COLUMN capacity_schema_version text,
  ADD COLUMN created_by             text,
  ADD COLUMN created_at             timestamptz NOT NULL DEFAULT clock_timestamp(),
  ADD COLUMN updated_at             timestamptz NOT NULL DEFAULT clock_timestamp(),
  ADD CONSTRAINT vehicle_label_format CHECK (vehicle_label IS NULL OR length(vehicle_label) BETWEEN 1 AND 64),
  ADD CONSTRAINT vehicle_type_format CHECK (vehicle_type IS NULL OR vehicle_type ~ '^[A-Z][A-Z0-9_]{1,39}$'),
  ADD CONSTRAINT vehicle_created_by_format CHECK (created_by IS NULL OR created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  ADD CONSTRAINT vehicle_label_unique UNIQUE (tenant_id, vehicle_label);

-- Kapazitätswerte (FLT-001 Originalabnahme: ungültige Kapazität ist nicht speicherbar, auch nicht per SQL). capacity_config ist ein JSON-Objekt; wo eine Dimension
-- (seats, wheelchair) vorhanden ist, muss sie ganzzahlig und >= 0 sein. Die Pflichtigkeit der Dimensionen prüft die API; Altbestand (Spike-Daten, Fixtures) darf Dimensionen auslassen.
CREATE FUNCTION capacity_dimension_valid(cfg jsonb, dim text) RETURNS boolean LANGUAGE sql IMMUTABLE SET search_path = taktovia, pg_catalog AS $$
  SELECT CASE WHEN cfg -> dim IS NULL THEN true
              ELSE jsonb_typeof(cfg -> dim) = 'number' AND (cfg ->> dim) ~ '^[0-9]{1,6}$' END
$$;
ALTER TABLE vehicle ADD CONSTRAINT vehicle_capacity_valid CHECK (
  jsonb_typeof(capacity_config) = 'object' AND capacity_dimension_valid(capacity_config, 'seats') AND capacity_dimension_valid(capacity_config, 'wheelchair'));
-- Zusätzlich (neu angelegt über die API): Wird eine Schemaversion gespeichert, sind beide Dimensionen Pflicht.
ALTER TABLE vehicle ADD CONSTRAINT vehicle_capacity_complete CHECK (
  capacity_schema_version IS NULL OR (capacity_config ? 'seats' AND capacity_config ? 'wheelchair'));
-- Nur aktive Fahrzeuge werden neu disponiert; die Arbeitsliste filtert über (tenant_id, active).
CREATE INDEX vehicle_by_active ON vehicle (tenant_id, active, id);

-- staff_shift (WP-M3-07/-24): Zugriff je Fahrer. Der Fremdschlüssel staff_shift.driver_ref -> driver und resource_release.inspection_ref -> inspection_run
-- wird NICHT in dieser Migration erzwungen (siehe db/scripts/m3_fk_aktivierung.sql und db/README.md): die M2-Fixtures und Spike-Daten legen Schichten mit
-- freien Fahrerkennungen und Freigaben mit freien Prüfverweisen an (WP-M3-02 Kriterium 6 verlangt, dass sie gültig bleiben). roster_revision NOT NULL (OP-07):
-- Schichten aus M3 tragen den Sentinel 0 bis M4 den Dienstplan führt; keine Schemaänderung nötig.
CREATE INDEX staff_shift_by_driver ON staff_shift (tenant_id, driver_ref, starts_at);
CREATE INDEX resource_release_by_inspection ON resource_release (tenant_id, inspection_ref);

-- ---------------------------------------------------------------------------------------------------------------------------------------------------
-- 2. Flotte und Sicherheit (Modul fleet-safety)
-- ---------------------------------------------------------------------------------------------------------------------------------------------------

-- Checklistenvorlagen (CHK): veröffentlichte Revisionen sind unveränderlich
CREATE TABLE checklist_template_revision (
  tenant_id     uuid        NOT NULL REFERENCES tenant(id),
  id            uuid        NOT NULL,
  template_id   uuid        NOT NULL,
  version       int         NOT NULL CHECK (version >= 1),
  vehicle_class text        NOT NULL CHECK (vehicle_class ~ '^[A-Z][A-Z0-9_]{1,39}$'),
  service_mode  text        NOT NULL CHECK (service_mode ~ '^[A-Z][A-Z0-9_]{1,39}$'),
  state         text        NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
  published_at  timestamptz,
  revision      bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_by    text        NOT NULL CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, template_id, version),
  CONSTRAINT checklist_published_has_time CHECK ((state = 'DRAFT') OR published_at IS NOT NULL)
);

CREATE TABLE checklist_step (
  tenant_id                uuid    NOT NULL REFERENCES tenant(id),
  id                       uuid    NOT NULL,
  template_revision_id     uuid    NOT NULL,
  step_code                text    NOT NULL CHECK (step_code ~ '^[A-Z][A-Z0-9_]{1,59}$'),
  position                 int     NOT NULL CHECK (position >= 1),
  title                    text    NOT NULL CHECK (length(title) BETWEEN 1 AND 200),
  mandatory                boolean NOT NULL,
  safety_critical          boolean NOT NULL,
  answer_kind              text    NOT NULL CHECK (answer_kind IN ('OK_DEFECT_NA', 'NUMBER', 'TEXT_SHORT')),
  photo_required_on_defect boolean NOT NULL DEFAULT false,
  revision                 bigint  NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_by               text    NOT NULL CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_at               timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, template_revision_id, step_code),
  UNIQUE (tenant_id, template_revision_id, position),
  FOREIGN KEY (tenant_id, template_revision_id) REFERENCES checklist_template_revision(tenant_id, id)
);

CREATE TABLE inspection_run (
  tenant_id            uuid        NOT NULL REFERENCES tenant(id),
  id                   uuid        NOT NULL,
  shift_id             uuid        NOT NULL,
  vehicle_id           uuid        NOT NULL,
  driver_ref           uuid        NOT NULL,
  template_revision_id uuid        NOT NULL,
  kind                 text        NOT NULL CHECK (kind IN ('PRE_SHIFT', 'FOLLOW_UP', 'VEHICLE_CHANGE')),
  status               text        NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'EXPIRED', 'CANCELED')),
  outcome              text        CHECK (outcome IN ('PASSED', 'PASSED_RESTRICTED', 'BLOCKED', 'CLARIFICATION_REQUIRED')),
  started_at           timestamptz NOT NULL,
  completed_at         timestamptz,
  received_at          timestamptz,
  safety_revision      bigint      CHECK (safety_revision IS NULL OR safety_revision >= 0),
  revision             bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_by           text        NOT NULL CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, shift_id)             REFERENCES staff_shift(tenant_id, id),
  FOREIGN KEY (tenant_id, vehicle_id)           REFERENCES vehicle(tenant_id, id),
  FOREIGN KEY (tenant_id, template_revision_id) REFERENCES checklist_template_revision(tenant_id, id),
  CONSTRAINT inspection_completed_consistent CHECK ((status = 'COMPLETED') = (outcome IS NOT NULL AND completed_at IS NOT NULL AND received_at IS NOT NULL))
);
-- Höchstens ein offener Lauf je (Schicht, Fahrzeug, Art).
CREATE UNIQUE INDEX inspection_one_open ON inspection_run (tenant_id, shift_id, vehicle_id, kind) WHERE status = 'IN_PROGRESS';
CREATE INDEX inspection_run_work ON inspection_run (tenant_id, status, created_at);

CREATE TABLE inspection_answer (
  tenant_id     uuid        NOT NULL REFERENCES tenant(id),
  id            uuid        NOT NULL,
  inspection_id uuid        NOT NULL,
  step_code     text        NOT NULL CHECK (step_code ~ '^[A-Z][A-Z0-9_]{1,59}$'),
  answer        text        NOT NULL CHECK (answer IN ('OK', 'DEFECT', 'NOT_APPLICABLE', 'NOT_CHECKABLE')),
  number_value  numeric,
  note          text        CHECK (note IS NULL OR length(note) <= 500),
  answered_at   timestamptz NOT NULL,
  revision      bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_by    text        NOT NULL CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, inspection_id, step_code),
  FOREIGN KEY (tenant_id, inspection_id) REFERENCES inspection_run(tenant_id, id)
);

-- Korrektur-/Ergänzungseinträge zur Prüfung: nach Abschluss ändert nur ein Ergänzungseintrag das Bild, nie die Antwort.
CREATE TABLE inspection_amendment (
  tenant_id     uuid        NOT NULL REFERENCES tenant(id),
  id            uuid        NOT NULL,
  inspection_id uuid        NOT NULL,
  step_code     text        NOT NULL CHECK (step_code ~ '^[A-Z][A-Z0-9_]{1,59}$'),
  amended_by    text        NOT NULL CHECK (amended_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  reason        text        NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
  new_answer    text        NOT NULL CHECK (new_answer IN ('OK', 'DEFECT', 'NOT_APPLICABLE', 'NOT_CHECKABLE')),
  revision      bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, inspection_id) REFERENCES inspection_run(tenant_id, id)
);

-- Schadenakte (DMG)
CREATE TABLE defect_case (
  tenant_id           uuid        NOT NULL REFERENCES tenant(id),
  id                  uuid        NOT NULL,
  case_number         text        NOT NULL CHECK (length(case_number) BETWEEN 1 AND 40),
  vehicle_id          uuid        NOT NULL,
  reported_by         uuid        NOT NULL,
  reported_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
  occurred_at         timestamptz NOT NULL,
  location_kind       text        CHECK (location_kind IN ('COORDINATE', 'ADDRESS_TEXT', 'FREE_TEXT', 'DEPOT')),
  location_value      text        CHECK (location_value IS NULL OR length(location_value) <= 300),
  category_code       text        NOT NULL CHECK (category_code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
  description         text        CHECK (description IS NULL OR length(description) <= 2000),
  severity            text        CHECK (severity IN ('SAFETY_CRITICAL', 'OPERATIONALLY_RELEVANT', 'COSMETIC', 'OTHER')),
  status              text        NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW', 'REVIEWED', 'IN_PROGRESS', 'REPAIR', 'CLOSED', 'DISMISSED')),
  assignee_ref        uuid,
  source_inspection_id uuid,
  source_step_code    text,
  dismissed_reason    text        CHECK (dismissed_reason IS NULL OR length(dismissed_reason) <= 500),
  revision            bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_by          text        NOT NULL CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, case_number),
  FOREIGN KEY (tenant_id, vehicle_id)           REFERENCES vehicle(tenant_id, id),
  FOREIGN KEY (tenant_id, source_inspection_id) REFERENCES inspection_run(tenant_id, id),
  CONSTRAINT defect_dismissed_needs_reason CHECK (status <> 'DISMISSED' OR dismissed_reason IS NOT NULL)
);
CREATE INDEX defect_case_by_vehicle ON defect_case (tenant_id, vehicle_id, status);
CREATE INDEX defect_case_work ON defect_case (tenant_id, status, updated_at);

-- Quittierung bekannter Schäden am Prüflauf (WP-M3-21): der Fahrer bestätigt, dass er einen offenen Fall des Fahrzeugs gesehen hat.
CREATE TABLE known_defect_acknowledgement (
  tenant_id     uuid        NOT NULL REFERENCES tenant(id),
  id            uuid        NOT NULL,
  inspection_id uuid        NOT NULL,
  defect_id     uuid        NOT NULL,
  acknowledged_by text      NOT NULL CHECK (acknowledged_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  revision      bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, inspection_id, defect_id),
  FOREIGN KEY (tenant_id, inspection_id) REFERENCES inspection_run(tenant_id, id),
  FOREIGN KEY (tenant_id, defect_id)     REFERENCES defect_case(tenant_id, id)
);

CREATE TABLE defect_evidence (
  tenant_id   uuid        NOT NULL REFERENCES tenant(id),
  id          uuid        NOT NULL,
  defect_id   uuid        NOT NULL,
  media_ref   text        NOT NULL CHECK (length(media_ref) BETWEEN 1 AND 300),
  sha256      text        NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  captured_at timestamptz NOT NULL,
  received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  revision    bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_by  text        NOT NULL CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, defect_id) REFERENCES defect_case(tenant_id, id)
);

CREATE TABLE defect_status_history (
  tenant_id   uuid        NOT NULL REFERENCES tenant(id),
  id          uuid        NOT NULL,
  defect_id   uuid        NOT NULL,
  from_status text,
  to_status   text        NOT NULL CHECK (to_status IN ('NEW', 'REVIEWED', 'IN_PROGRESS', 'REPAIR', 'CLOSED', 'DISMISSED')),
  actor       text        NOT NULL CHECK (actor ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  reason      text        CHECK (reason IS NULL OR length(reason) <= 500),
  revision    bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, defect_id) REFERENCES defect_case(tenant_id, id)
);

CREATE TABLE repair_order (
  tenant_id      uuid        NOT NULL REFERENCES tenant(id),
  id             uuid        NOT NULL,
  defect_id      uuid        NOT NULL,
  vehicle_id     uuid        NOT NULL,
  status         text        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'IN_PROGRESS', 'PROOF_RECORDED', 'CANCELED')),
  workshop_ref   text        CHECK (workshop_ref IS NULL OR length(workshop_ref) <= 200),
  planned_at     timestamptz,
  cost_reference text        CHECK (cost_reference IS NULL OR length(cost_reference) <= 200),
  revision       bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_by     text        NOT NULL CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, defect_id)  REFERENCES defect_case(tenant_id, id),
  FOREIGN KEY (tenant_id, vehicle_id) REFERENCES vehicle(tenant_id, id)
);

CREATE TABLE vehicle_release_decision (
  tenant_id               uuid        NOT NULL REFERENCES tenant(id),
  id                      uuid        NOT NULL,
  vehicle_id              uuid        NOT NULL,
  repair_order_id         uuid        NOT NULL,
  follow_up_inspection_id uuid        NOT NULL,
  decision                text        NOT NULL CHECK (decision IN ('RELEASED', 'PENDING_SECOND_APPROVAL', 'REJECTED')),
  decided_by              text        NOT NULL CHECK (decided_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  second_approver         text        CHECK (second_approver IS NULL OR second_approver ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  decided_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
  safety_revision         bigint      NOT NULL CHECK (safety_revision >= 0),
  revision                bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, vehicle_id)              REFERENCES vehicle(tenant_id, id),
  FOREIGN KEY (tenant_id, repair_order_id)         REFERENCES repair_order(tenant_id, id),
  FOREIGN KEY (tenant_id, follow_up_inspection_id) REFERENCES inspection_run(tenant_id, id),
  -- Vieraugenpflicht: eine Wiederfreigabe verlangt eine zweite, andere Identität
  CONSTRAINT release_four_eyes CHECK (decision <> 'RELEASED' OR (second_approver IS NOT NULL AND second_approver <> decided_by))
);

-- Sperrgründe je Fahrzeug (OP-01). Zeilen werden nur geschlossen (ACTIVE -> LIFTED), nie wieder geöffnet; eine neue Sperre ist eine neue Zeile.
CREATE TABLE vehicle_block (
  tenant_id           uuid        NOT NULL REFERENCES tenant(id),
  id                  uuid        NOT NULL,
  vehicle_id          uuid        NOT NULL,
  source              text        NOT NULL CHECK (source IN ('OPERATIONS', 'SAFETY')),
  reason_code         text        NOT NULL CHECK (reason_code IN ('OPERATIONAL_DISRUPTION', 'DEFECT_CRITICAL', 'INSPECTION_FAILED', 'MANUAL', 'OTHER')),
  status              text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'LIFTED')),
  set_by              text        NOT NULL CHECK (set_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  set_at              timestamptz NOT NULL DEFAULT clock_timestamp(),
  lifted_by           text        CHECK (lifted_by IS NULL OR lifted_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  lifted_at           timestamptz,
  release_decision_id uuid,
  revision            bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, vehicle_id)          REFERENCES vehicle(tenant_id, id),
  FOREIGN KEY (tenant_id, release_decision_id) REFERENCES vehicle_release_decision(tenant_id, id),
  CONSTRAINT vehicle_block_lift_consistent CHECK ((status = 'LIFTED') = (lifted_at IS NOT NULL AND lifted_by IS NOT NULL))
);
CREATE INDEX vehicle_block_active ON vehicle_block (tenant_id, vehicle_id) WHERE status = 'ACTIVE';

-- Alarme, Incident und Workflow (AUT, OP-02)
CREATE TABLE alert_policy_revision (
  tenant_id   uuid        NOT NULL REFERENCES tenant(id),
  id          uuid        NOT NULL,
  revision_no int         NOT NULL CHECK (revision_no >= 1),
  rules       jsonb       NOT NULL CHECK (jsonb_typeof(rules) IN ('object', 'array')),
  revision    bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_by  text        NOT NULL CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, revision_no)
);

CREATE TABLE business_alert (
  tenant_id  uuid        NOT NULL REFERENCES tenant(id),
  id         uuid        NOT NULL,
  alert_kind text        NOT NULL CHECK (alert_kind ~ '^[A-Z][A-Z0-9_]{1,59}$'),
  priority   text        NOT NULL CHECK (priority IN ('CRITICAL', 'HIGH', 'NORMAL')),
  vehicle_id uuid,
  dedupe_key text        NOT NULL CHECK (length(dedupe_key) BETWEEN 1 AND 200),
  state      text        NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN', 'ACKNOWLEDGED')),
  policy_revision_id uuid,
  revision   bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_by text        NOT NULL CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, vehicle_id)         REFERENCES vehicle(tenant_id, id),
  FOREIGN KEY (tenant_id, policy_revision_id) REFERENCES alert_policy_revision(tenant_id, id)
);
-- Dedupe: höchstens ein offener Alarm je Schlüssel
CREATE UNIQUE INDEX business_alert_dedupe_open ON business_alert (tenant_id, dedupe_key) WHERE state = 'OPEN';
CREATE INDEX business_alert_work ON business_alert (tenant_id, state, updated_at);

CREATE TABLE alert_acknowledgement (
  tenant_id       uuid        NOT NULL REFERENCES tenant(id),
  id              uuid        NOT NULL,
  alert_id        uuid        NOT NULL,
  acknowledged_by text        NOT NULL CHECK (acknowledged_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  note            text        CHECK (note IS NULL OR length(note) <= 500),
  revision        bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_at      timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, alert_id),
  FOREIGN KEY (tenant_id, alert_id) REFERENCES business_alert(tenant_id, id)
);

CREATE TABLE workflow_rule_revision (
  tenant_id  uuid        NOT NULL REFERENCES tenant(id),
  id         uuid        NOT NULL,
  event_kind text        NOT NULL CHECK (event_kind ~ '^[A-Z][A-Z0-9_]{1,59}$'),
  rule       jsonb       NOT NULL CHECK (jsonb_typeof(rule) = 'object'),
  revision   bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_by text        NOT NULL CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id)
);

CREATE TABLE incident (
  tenant_id                uuid        NOT NULL REFERENCES tenant(id),
  id                       uuid        NOT NULL,
  kind                     text        NOT NULL CHECK (kind IN ('VEHICLE_OUTAGE', 'DEFECT', 'REJECTION', 'CRITICAL_DELAY')),
  vehicle_id               uuid,
  status                   text        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'REPLACEMENT_SEARCH', 'RESOLVED', 'CLOSED_NO_REPLACEMENT')),
  dedupe_key               text        NOT NULL CHECK (length(dedupe_key) BETWEEN 1 AND 200),
  rule_revision_id         uuid,
  replacement_operation_id uuid,
  opened_at                timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at               timestamptz NOT NULL DEFAULT clock_timestamp(),
  revision                 bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_by               text        NOT NULL CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_at               timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, vehicle_id)               REFERENCES vehicle(tenant_id, id),
  FOREIGN KEY (tenant_id, rule_revision_id)         REFERENCES workflow_rule_revision(tenant_id, id),
  FOREIGN KEY (tenant_id, replacement_operation_id) REFERENCES operation(tenant_id, id)
);
-- Genau ein offener Vorgang je Schlüssel (einmalige Geschäftswirkung)
CREATE UNIQUE INDEX incident_one_open_per_key ON incident (tenant_id, dedupe_key) WHERE status IN ('OPEN', 'REPLACEMENT_SEARCH');
CREATE INDEX incident_work ON incident (tenant_id, status, updated_at);

-- Verknüpfungen eines Incidents mit betroffenen Objekten (Buchung, Lauf, Schadenakte, Alarm)
CREATE TABLE incident_link (
  tenant_id   uuid        NOT NULL REFERENCES tenant(id),
  id          uuid        NOT NULL,
  incident_id uuid        NOT NULL,
  link_kind   text        NOT NULL CHECK (link_kind IN ('BOOKING', 'VEHICLE_RUN', 'DEFECT', 'ALERT')),
  object_id   uuid        NOT NULL,
  revision    bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_by  text        NOT NULL CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, incident_id, link_kind, object_id),
  FOREIGN KEY (tenant_id, incident_id) REFERENCES incident(tenant_id, id)
);

-- Ablaufprotokoll automatisch ausgeführter Vorgänge; (event_id, action) ist der Dedupe-Schlüssel
CREATE TABLE workflow_run_log (
  tenant_id        uuid        NOT NULL REFERENCES tenant(id),
  id               uuid        NOT NULL,
  incident_id      uuid,
  event_id         uuid        NOT NULL,
  rule_revision_id uuid,
  action           text        NOT NULL CHECK (length(action) BETWEEN 1 AND 100),
  outcome          text        NOT NULL CHECK (length(outcome) BETWEEN 1 AND 100),
  revision         bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_by       text        NOT NULL CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, event_id, action),
  FOREIGN KEY (tenant_id, incident_id)      REFERENCES incident(tenant_id, id),
  FOREIGN KEY (tenant_id, rule_revision_id) REFERENCES workflow_rule_revision(tenant_id, id)
);

-- ---------------------------------------------------------------------------------------------------------------------------------------------------
-- 3. Fahrer und Leitstelle (Modul staff-time) sowie booking_change (Modul booking-dispatch)
-- ---------------------------------------------------------------------------------------------------------------------------------------------------

CREATE TABLE position_sample (
  tenant_id   uuid        NOT NULL REFERENCES tenant(id),
  id          uuid        NOT NULL,
  vehicle_id  uuid        NOT NULL,
  shift_id    uuid        NOT NULL,
  position    geography(Point, 4326) NOT NULL,
  accuracy_m  numeric(8,2) NOT NULL CHECK (accuracy_m >= 0),
  measured_at timestamptz NOT NULL,
  received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  revision    bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_by  text        NOT NULL CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, vehicle_id) REFERENCES vehicle(tenant_id, id),
  FOREIGN KEY (tenant_id, shift_id)   REFERENCES staff_shift(tenant_id, id)
);
-- jüngste Position je Fahrzeug (Kap. 04 §4.7)
CREATE INDEX position_sample_latest ON position_sample (tenant_id, vehicle_id, measured_at DESC);

CREATE TABLE vehicle_status_history (
  tenant_id   uuid        NOT NULL REFERENCES tenant(id),
  id          uuid        NOT NULL,
  vehicle_id  uuid        NOT NULL,
  shift_id    uuid        NOT NULL,
  from_status text        CHECK (from_status IN ('NOT_READY', 'AVAILABLE', 'EN_ROUTE', 'WAITING', 'BREAK', 'DISRUPTED', 'ENDED')),
  to_status   text        NOT NULL CHECK (to_status IN ('NOT_READY', 'AVAILABLE', 'EN_ROUTE', 'WAITING', 'BREAK', 'DISRUPTED', 'ENDED')),
  trigger     text        NOT NULL CHECK (trigger IN ('DRIVER', 'SYSTEM', 'DISPATCH')),
  revision    bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_by  text        NOT NULL CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, vehicle_id) REFERENCES vehicle(tenant_id, id),
  FOREIGN KEY (tenant_id, shift_id)   REFERENCES staff_shift(tenant_id, id)
);
CREATE INDEX vehicle_status_history_by_shift ON vehicle_status_history (tenant_id, shift_id, created_at);

-- Bestätigte Planrevision je Fahrer (DRV-004): append-only, die höchste Revision je Schicht gilt
CREATE TABLE driver_plan_ack (
  tenant_id     uuid        NOT NULL REFERENCES tenant(id),
  id            uuid        NOT NULL,
  shift_id      uuid        NOT NULL,
  run_id        uuid        NOT NULL,
  plan_revision bigint      NOT NULL CHECK (plan_revision >= 1),
  device_id     uuid,   -- Gerät (driver_device, V3240 sortiert hinter dieser Migration: der Verweis wird im Dienst geprüft, kein Datenbank-Fremdschlüssel)
  acknowledged_at timestamptz NOT NULL,
  revision      bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_by    text        NOT NULL CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, shift_id, run_id, plan_revision),
  FOREIGN KEY (tenant_id, shift_id)  REFERENCES staff_shift(tenant_id, id),
  FOREIGN KEY (tenant_id, run_id)    REFERENCES vehicle_run(tenant_id, id)
);

-- Boarding-Code je Buchung: nur der Hash (Argon2id-Kodierung) wird gespeichert, nie der Code (OP-18). Versuchszähler und Verbrauch sind die einzigen Änderungen.
CREATE TABLE boarding_code (
  tenant_id    uuid        NOT NULL REFERENCES tenant(id),
  id           uuid        NOT NULL,
  booking_id   uuid        NOT NULL,
  code_hash    text        NOT NULL CHECK (length(code_hash) BETWEEN 20 AND 300 AND code_hash NOT ILIKE '%plain%'),
  valid_until  timestamptz NOT NULL,
  failed_attempts int      NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 100),
  used_at      timestamptz,
  revision     bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_by   text        NOT NULL CHECK (created_by ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  created_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, booking_id),
  FOREIGN KEY (tenant_id, booking_id) REFERENCES booking(tenant_id, id)
);

-- booking_change (Kap. 04 §4.4, Zeile BKG): M2 liefert sie nicht (M2 Offener Punkt 22/44); Schreibrecht nur für booking-dispatch
CREATE TABLE booking_change (
  tenant_id   uuid        NOT NULL REFERENCES tenant(id),
  id          uuid        NOT NULL,
  booking_id  uuid        NOT NULL,
  change_kind text        NOT NULL CHECK (change_kind IN ('MODIFY', 'CANCEL', 'REPLACE_OFFER')),
  actor       text        NOT NULL CHECK (actor ~ '^(user|service|system|partner):[A-Za-z0-9._:-]{1,120}$'),
  reason      text        CHECK (reason IS NULL OR length(reason) <= 500),
  revision    bigint      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  created_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, booking_id) REFERENCES booking(tenant_id, id)
);
CREATE INDEX booking_change_by_booking ON booking_change (tenant_id, booking_id, created_at);

-- ---------------------------------------------------------------------------------------------------------------------------------------------------
-- 4. Mandantentrennung: RLS erzwungen, Policy tenant_isolation (USING und WITH CHECK), tenant_id unveränderlich
-- ---------------------------------------------------------------------------------------------------------------------------------------------------
DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['checklist_template_revision', 'checklist_step', 'inspection_run', 'inspection_answer', 'inspection_amendment', 'defect_case',
    'known_defect_acknowledgement', 'defect_evidence', 'defect_status_history', 'repair_order', 'vehicle_release_decision', 'vehicle_block', 'alert_policy_revision',
    'business_alert', 'alert_acknowledgement', 'workflow_rule_revision', 'incident', 'incident_link', 'workflow_run_log', 'position_sample', 'vehicle_status_history',
    'driver_plan_ack', 'boarding_code', 'booking_change'] LOOP
    EXECUTE format('ALTER TABLE taktovia.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE taktovia.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY tenant_isolation ON taktovia.%I USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant())', t);
    EXECUTE format('CREATE TRIGGER tenant_id_immutable BEFORE UPDATE ON taktovia.%I FOR EACH ROW EXECUTE FUNCTION taktovia.reject_tenant_change()', t);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------------------------------------------------------------------------------------
-- 5. Unveränderlichkeit (SQLSTATE 23514, auch für die Administration)
-- ---------------------------------------------------------------------------------------------------------------------------------------------------

-- Append-only: Evidenz, Freigabeentscheidung, Historien, Ergänzungen, Quittierungen, Ablaufprotokoll, Regel- und Richtlinienrevisionen, Planbestätigung, Buchungsänderung
DO $$ DECLARE t text; BEGIN
  FOREACH t IN ARRAY ARRAY['defect_evidence', 'vehicle_release_decision', 'defect_status_history', 'vehicle_status_history', 'inspection_amendment', 'known_defect_acknowledgement',
    'alert_acknowledgement', 'workflow_run_log', 'workflow_rule_revision', 'alert_policy_revision', 'driver_plan_ack', 'booking_change', 'incident_link'] LOOP
    EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON taktovia.%I FOR EACH ROW EXECUTE FUNCTION taktovia.reject_mutation()', t || '_immutable', t);
  END LOOP;
END $$;
-- Positionsproben: nie ändern (die Löschung nach Ablauf der Aufbewahrung ist ein Betriebsauftrag mit eigener Rolle, OP-04)
CREATE TRIGGER position_sample_immutable BEFORE UPDATE ON position_sample FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- Prüflauf: ein abgeschlossener (oder verfallener, abgebrochener) Lauf ist unveränderlich und lässt sich nie in einen offenen Zustand zurücksetzen.
-- Offene Läufe dürfen nur abgeschlossen, verfallen oder abgebrochen werden; Identität (Schicht, Fahrzeug, Fahrer, Vorlage, Art) bleibt fest.
CREATE FUNCTION inspection_run_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.status <> 'IN_PROGRESS' THEN RAISE EXCEPTION 'closed inspection run is immutable' USING ERRCODE = '23514'; END IF;
    RETURN OLD;
  END IF;
  IF OLD.status <> 'IN_PROGRESS' THEN
    RAISE EXCEPTION 'closed inspection run is immutable; add an amendment' USING ERRCODE = '23514';
  END IF;
  IF NEW.shift_id IS DISTINCT FROM OLD.shift_id OR NEW.vehicle_id IS DISTINCT FROM OLD.vehicle_id OR NEW.driver_ref IS DISTINCT FROM OLD.driver_ref
     OR NEW.template_revision_id IS DISTINCT FROM OLD.template_revision_id OR NEW.kind IS DISTINCT FROM OLD.kind OR NEW.started_at IS DISTINCT FROM OLD.started_at
     OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'inspection run identity is immutable' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER inspection_run_guard BEFORE UPDATE OR DELETE ON inspection_run FOR EACH ROW EXECUTE FUNCTION inspection_run_guard();

-- Antworten: nur solange der Lauf offen ist (Entwurf); mit dem Abschluss unveränderlich, auch gegen Einfügen und Löschen
CREATE FUNCTION inspection_answer_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE st text; iid uuid; tid uuid;
BEGIN
  IF TG_OP = 'INSERT' THEN iid := NEW.inspection_id; tid := NEW.tenant_id; ELSE iid := OLD.inspection_id; tid := OLD.tenant_id; END IF;
  SELECT status INTO st FROM inspection_run WHERE tenant_id = tid AND id = iid;
  IF st IS DISTINCT FROM 'IN_PROGRESS' THEN
    RAISE EXCEPTION 'inspection answers of a closed run are immutable; add an amendment' USING ERRCODE = '23514';
  END IF;
  IF TG_OP = 'UPDATE' AND (NEW.inspection_id IS DISTINCT FROM OLD.inspection_id OR NEW.step_code IS DISTINCT FROM OLD.step_code
     OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at) THEN
    RAISE EXCEPTION 'inspection answer identity is immutable' USING ERRCODE = '23514';
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER inspection_answer_guard BEFORE INSERT OR UPDATE OR DELETE ON inspection_answer FOR EACH ROW EXECUTE FUNCTION inspection_answer_guard();

-- Vorlagenrevision: nach der Veröffentlichung unveränderlich; erlaubt ist nur der Übergang PUBLISHED -> RETIRED (und DRAFT -> PUBLISHED); keine Löschung veröffentlichter Revisionen
CREATE FUNCTION checklist_template_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.state <> 'DRAFT' THEN RAISE EXCEPTION 'published checklist template revision is immutable' USING ERRCODE = '23514'; END IF;
    RETURN OLD;
  END IF;
  IF OLD.state = 'RETIRED' THEN RAISE EXCEPTION 'retired checklist template revision is immutable' USING ERRCODE = '23514'; END IF;
  IF OLD.state = 'PUBLISHED' AND (NEW.state <> 'RETIRED' OR NEW.template_id IS DISTINCT FROM OLD.template_id OR NEW.version IS DISTINCT FROM OLD.version
     OR NEW.vehicle_class IS DISTINCT FROM OLD.vehicle_class OR NEW.service_mode IS DISTINCT FROM OLD.service_mode OR NEW.published_at IS DISTINCT FROM OLD.published_at) THEN
    RAISE EXCEPTION 'published checklist template revision is immutable; only retirement is allowed' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER checklist_template_guard BEFORE UPDATE OR DELETE ON checklist_template_revision FOR EACH ROW EXECUTE FUNCTION checklist_template_guard();

-- Prüfschritte gehören zu einer Vorlagenrevision und sind nach deren Veröffentlichung unveränderlich
CREATE FUNCTION checklist_step_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE st text; tid uuid; rid uuid;
BEGIN
  IF TG_OP = 'INSERT' THEN tid := NEW.tenant_id; rid := NEW.template_revision_id; ELSE tid := OLD.tenant_id; rid := OLD.template_revision_id; END IF;
  SELECT state INTO st FROM checklist_template_revision WHERE tenant_id = tid AND id = rid;
  IF st IS DISTINCT FROM 'DRAFT' THEN
    RAISE EXCEPTION 'steps of a published checklist template revision are immutable' USING ERRCODE = '23514';
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER checklist_step_guard BEFORE INSERT OR UPDATE OR DELETE ON checklist_step FOR EACH ROW EXECUTE FUNCTION checklist_step_guard();

-- Sperre: Zeilen werden nur geschlossen (ACTIVE -> LIFTED, Revision +1), nie geöffnet, nie gelöscht
CREATE FUNCTION vehicle_block_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'vehicle block is append-only' USING ERRCODE = '23514'; END IF;
  IF OLD.status <> 'ACTIVE' OR NEW.status <> 'LIFTED'
     OR NEW.id IS DISTINCT FROM OLD.id OR NEW.vehicle_id IS DISTINCT FROM OLD.vehicle_id OR NEW.source IS DISTINCT FROM OLD.source
     OR NEW.reason_code IS DISTINCT FROM OLD.reason_code OR NEW.set_by IS DISTINCT FROM OLD.set_by OR NEW.set_at IS DISTINCT FROM OLD.set_at
     OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'vehicle block may only be lifted once' USING ERRCODE = '23514';
  END IF;
  NEW.revision := OLD.revision + 1;
  RETURN NEW;
END $$;
CREATE TRIGGER vehicle_block_guard BEFORE UPDATE OR DELETE ON vehicle_block FOR EACH ROW EXECUTE FUNCTION vehicle_block_guard();

-- Boarding-Code: Hash, Buchung und Gültigkeit unveränderlich; Versuchszähler darf nur steigen, der Verbrauch ist endgültig
CREATE FUNCTION boarding_code_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'boarding code is append-only' USING ERRCODE = '23514'; END IF;
  IF NEW.code_hash IS DISTINCT FROM OLD.code_hash OR NEW.booking_id IS DISTINCT FROM OLD.booking_id OR NEW.valid_until IS DISTINCT FROM OLD.valid_until
     OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at OR NEW.failed_attempts < OLD.failed_attempts
     OR (OLD.used_at IS NOT NULL AND NEW.used_at IS DISTINCT FROM OLD.used_at) THEN
    RAISE EXCEPTION 'boarding code is immutable except attempt counter and single use' USING ERRCODE = '23514';
  END IF;
  IF NEW IS DISTINCT FROM OLD THEN NEW.revision := OLD.revision + 1; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER boarding_code_guard BEFORE UPDATE OR DELETE ON boarding_code FOR EACH ROW EXECUTE FUNCTION boarding_code_guard();
