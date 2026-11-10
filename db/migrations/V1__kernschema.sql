-- TAKTOVIA Pflichtenheft, Stand 2026-10-25, PostgreSQL 17 + PostGIS.
-- Entwurfs-DDL fuer ein leeres Schema; keine produktive Migration.
-- Keine Runtime-Rollen/Secrets angelegt. Rollenrechte siehe 04-datenmodell.md.
CREATE SCHEMA taktovia;
SET search_path = taktovia, public;
CREATE FUNCTION current_tenant() RETURNS uuid LANGUAGE sql STABLE
AS $$ SELECT nullif(current_setting('app.tenant_id', true), '')::uuid $$;
CREATE TABLE tenant (
 id uuid PRIMARY KEY, name text NOT NULL, status text NOT NULL CHECK(status IN ('ACTIVE','SUSPENDED','CLOSED'))
);
CREATE TABLE currency (
 code char(3) PRIMARY KEY, exponent smallint NOT NULL CHECK(exponent BETWEEN 0 AND 4)
);
CREATE TABLE legal_entity (
 tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, name text NOT NULL,
 country char(2) NOT NULL, tax_reference_token text, PRIMARY KEY(tenant_id,id)
);
CREATE TABLE organization (
 tenant_id uuid NOT NULL, id uuid NOT NULL, legal_entity_id uuid NOT NULL,
 name text NOT NULL, federation_ref text NOT NULL, roles text[] NOT NULL,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,federation_ref),
 FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES legal_entity(tenant_id,id)
);
CREATE TABLE membership (
 tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, issuer text NOT NULL,
 subject text NOT NULL, role_code text NOT NULL, service_scope uuid[], revoked_at timestamptz,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,issuer,subject,role_code)
);
CREATE TABLE config_revision (
 tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, scope_kind text NOT NULL,
 scope_id uuid NOT NULL, revision bigint NOT NULL CHECK(revision>0),
 schema_version text NOT NULL, values_json jsonb NOT NULL,
 effective_from timestamptz NOT NULL, effective_to timestamptz,
 created_by text NOT NULL, approved_by text, published_at timestamptz,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,scope_kind,scope_id,revision),
 CHECK(effective_to IS NULL OR effective_to>effective_from),
 CHECK(published_at IS NULL OR approved_by IS NOT NULL)
);
CREATE TABLE service (
 tenant_id uuid NOT NULL, id uuid NOT NULL, operator_id uuid NOT NULL, name text NOT NULL,
 timezone text NOT NULL, config_revision_id uuid NOT NULL,
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,operator_id) REFERENCES organization(tenant_id,id),
 FOREIGN KEY(tenant_id,config_revision_id) REFERENCES config_revision(tenant_id,id)
);
CREATE TABLE service_area (
 tenant_id uuid NOT NULL, id uuid NOT NULL, service_id uuid NOT NULL, revision bigint NOT NULL,
 area geometry(MultiPolygon,4326) NOT NULL, valid_from timestamptz NOT NULL, valid_to timestamptz,
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,service_id) REFERENCES service(tenant_id,id),
 CHECK(ST_IsValid(area)), CHECK(valid_to IS NULL OR valid_to>valid_from)
);
CREATE INDEX service_area_geo ON service_area USING gist(area);
CREATE TABLE stop_revision (
 tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, stop_id uuid NOT NULL,
 revision bigint NOT NULL, point geometry(Point,4326) NOT NULL, name text NOT NULL,
 accessibility jsonb NOT NULL, approved_by text NOT NULL,
 valid_from timestamptz NOT NULL, valid_to timestamptz,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,stop_id,revision),
 CHECK(valid_to IS NULL OR valid_to>valid_from)
);
CREATE INDEX stop_geo ON stop_revision USING gist(point);
CREATE TABLE partner_contract_version (
 tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, partner_ref text NOT NULL,
 version bigint NOT NULL, terms_hash char(64) NOT NULL, terms jsonb NOT NULL,
 valid_from timestamptz NOT NULL, valid_to timestamptz NOT NULL,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,partner_ref,version), CHECK(valid_to>valid_from)
);
CREATE TABLE cooperation_grant (
 tenant_id uuid NOT NULL, id uuid NOT NULL, contract_version_id uuid NOT NULL,
 grantee_ref text NOT NULL, purpose text NOT NULL, actions text[] NOT NULL,
 service_refs text[] NOT NULL, allowed_fields text[] NOT NULL,
 valid_from timestamptz NOT NULL, valid_to timestamptz NOT NULL, revoked_at timestamptz,
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,contract_version_id) REFERENCES partner_contract_version(tenant_id,id),
 CHECK(valid_to>valid_from)
);
CREATE TABLE passenger_private (
 tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, subject_token text NOT NULL,
 contact_ciphertext bytea, support_ciphertext bytea, retention_until timestamptz NOT NULL,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,subject_token)
);
CREATE TABLE travel_attempt (
 tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, created_at timestamptz NOT NULL,
 channel text NOT NULL, primary_rejection text, secondary_rejections text[] NOT NULL DEFAULT '{}',
 coarse_origin text, coarse_destination text, PRIMARY KEY(tenant_id,id)
);
CREATE TABLE fare_quote (
 tenant_id uuid NOT NULL, id uuid NOT NULL, attempt_id uuid NOT NULL,
 currency char(3) NOT NULL REFERENCES currency(code), pay_at_platform_minor bigint NOT NULL CHECK(pay_at_platform_minor>=0),
 external_known_minor bigint NOT NULL CHECK(external_known_minor>=0), external_unknown_leg_ids uuid[] NOT NULL,
 expires_at timestamptz NOT NULL, tariff_revision_id uuid NOT NULL, config_revision_id uuid NOT NULL,
 snapshot jsonb NOT NULL, content_hash char(64) NOT NULL, PRIMARY KEY(tenant_id,id),
 FOREIGN KEY(tenant_id,attempt_id) REFERENCES travel_attempt(tenant_id,id),
 FOREIGN KEY(tenant_id,config_revision_id) REFERENCES config_revision(tenant_id,id)
);
CREATE TABLE journey (
 tenant_id uuid NOT NULL, id uuid NOT NULL, passenger_id uuid NOT NULL, quote_id uuid NOT NULL,
 state text NOT NULL, revision bigint NOT NULL DEFAULT 1 CHECK(revision>0),
 created_at timestamptz NOT NULL, currency char(3) NOT NULL REFERENCES currency(code),
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,quote_id), FOREIGN KEY(tenant_id,passenger_id) REFERENCES passenger_private(tenant_id,id),
 FOREIGN KEY(tenant_id,quote_id) REFERENCES fare_quote(tenant_id,id)
);
CREATE TABLE journey_leg (
 tenant_id uuid NOT NULL, id uuid NOT NULL, journey_id uuid NOT NULL, sequence integer NOT NULL CHECK(sequence>0),
 operator_ref text NOT NULL, service_ref text NOT NULL, federation_ref text NOT NULL,
 capability text NOT NULL CHECK(capability IN ('INFORMATION_ONLY','EXTERNAL_BOOKING','PLATFORM_BOOKABLE')),
 state text NOT NULL, planned_departure timestamptz NOT NULL, planned_arrival timestamptz NOT NULL,
 origin_snapshot jsonb NOT NULL, destination_snapshot jsonb NOT NULL, promise_revision bigint,
 pickup_earliest timestamptz, pickup_latest timestamptz, arrival_earliest timestamptz, arrival_latest timestamptz,
 execution_state text NOT NULL DEFAULT 'PLANNED',
 contract_version_id uuid, gross_minor bigint CHECK(gross_minor>=0), currency char(3) REFERENCES currency(code),
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,journey_id,sequence), UNIQUE(tenant_id,federation_ref),
 FOREIGN KEY(tenant_id,journey_id) REFERENCES journey(tenant_id,id),
 FOREIGN KEY(tenant_id,contract_version_id) REFERENCES partner_contract_version(tenant_id,id),
 CHECK(planned_arrival>=planned_departure), CHECK((gross_minor IS NULL)=(currency IS NULL)),
 CHECK((pickup_earliest IS NULL)=(pickup_latest IS NULL)), CHECK(pickup_latest>=pickup_earliest),
 CHECK((arrival_earliest IS NULL)=(arrival_latest IS NULL)), CHECK(arrival_latest>=arrival_earliest),
 CHECK(capability<>'PLATFORM_BOOKABLE' OR state NOT IN ('CONFIRMED','COMPLETED') OR (pickup_earliest IS NOT NULL AND arrival_earliest IS NOT NULL))
);
CREATE TABLE operation (
 tenant_id uuid NOT NULL, id uuid NOT NULL, journey_id uuid NOT NULL,
 kind text NOT NULL, status text NOT NULL, revision bigint NOT NULL DEFAULT 1,
 next_attempt_at timestamptz, lease_until timestamptz, fencing_token bigint NOT NULL DEFAULT 0,
 last_error_code text, deadline_at timestamptz NOT NULL, PRIMARY KEY(tenant_id,id),
 FOREIGN KEY(tenant_id,journey_id) REFERENCES journey(tenant_id,id)
);
CREATE TABLE saga_step (
 tenant_id uuid NOT NULL, id uuid NOT NULL, operation_id uuid NOT NULL, leg_id uuid,
 action text NOT NULL, state text NOT NULL, partner_command_ref text NOT NULL,
 partner_result_ref text, attempts integer NOT NULL DEFAULT 0, updated_at timestamptz NOT NULL,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,partner_command_ref),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES operation(tenant_id,id),
 FOREIGN KEY(tenant_id,leg_id) REFERENCES journey_leg(tenant_id,id)
);
CREATE TABLE vehicle (
 tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, owner_org_ref text NOT NULL,
 capacity_config jsonb NOT NULL, active_block boolean NOT NULL DEFAULT true,
 safety_revision bigint NOT NULL DEFAULT 1, PRIMARY KEY(tenant_id,id)
);
CREATE TABLE staff_shift (
 tenant_id uuid NOT NULL, id uuid NOT NULL, driver_ref uuid NOT NULL, vehicle_id uuid NOT NULL,
 starts_at timestamptz NOT NULL, ends_at timestamptz NOT NULL, timezone text NOT NULL,
 roster_revision bigint NOT NULL, state text NOT NULL,
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,vehicle_id) REFERENCES vehicle(tenant_id,id), CHECK(ends_at>starts_at)
);
CREATE TABLE resource_release (
 tenant_id uuid NOT NULL, id uuid NOT NULL, vehicle_id uuid NOT NULL, shift_id uuid NOT NULL,
 safety_revision bigint NOT NULL, inspection_ref uuid NOT NULL, qualification_revision bigint NOT NULL,
 valid_until timestamptz NOT NULL, revoked_at timestamptz,
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,vehicle_id) REFERENCES vehicle(tenant_id,id),
 FOREIGN KEY(tenant_id,shift_id) REFERENCES staff_shift(tenant_id,id)
);
CREATE TABLE vehicle_run (
 tenant_id uuid NOT NULL, id uuid NOT NULL, vehicle_id uuid NOT NULL, shift_id uuid NOT NULL,
 revision bigint NOT NULL, state text NOT NULL, PRIMARY KEY(tenant_id,id),
 FOREIGN KEY(tenant_id,vehicle_id) REFERENCES vehicle(tenant_id,id), FOREIGN KEY(tenant_id,shift_id) REFERENCES staff_shift(tenant_id,id)
);
CREATE TABLE capacity_hold (
 tenant_id uuid NOT NULL, id uuid NOT NULL, run_id uuid NOT NULL, run_revision bigint NOT NULL,
 federation_ref text NOT NULL, capacity_vector jsonb NOT NULL, interval_from integer NOT NULL, interval_to integer NOT NULL,
 expires_at timestamptz NOT NULL, state text NOT NULL CHECK(state IN ('HELD','CONSUMED','RELEASED','EXPIRED')),
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,federation_ref), FOREIGN KEY(tenant_id,run_id) REFERENCES vehicle_run(tenant_id,id),
 CHECK(interval_to>interval_from)
);
CREATE TABLE booking (
 tenant_id uuid NOT NULL, id uuid NOT NULL, hold_id uuid NOT NULL, service_id uuid NOT NULL,
 federation_ref text NOT NULL, state text NOT NULL, promise_snapshot jsonb NOT NULL, revision bigint NOT NULL,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,hold_id), UNIQUE(tenant_id,federation_ref),
 FOREIGN KEY(tenant_id,hold_id) REFERENCES capacity_hold(tenant_id,id), FOREIGN KEY(tenant_id,service_id) REFERENCES service(tenant_id,id)
);
CREATE TABLE payment_intent (
 tenant_id uuid NOT NULL, id uuid NOT NULL, journey_id uuid NOT NULL, provider text NOT NULL,
 provider_ref text NOT NULL, state text NOT NULL, currency char(3) NOT NULL REFERENCES currency(code),
 authorized_minor bigint NOT NULL CHECK(authorized_minor>=0), captured_minor bigint NOT NULL DEFAULT 0 CHECK(captured_minor>=0),
 refunded_minor bigint NOT NULL DEFAULT 0 CHECK(refunded_minor>=0), revision bigint NOT NULL DEFAULT 1,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,provider,provider_ref), FOREIGN KEY(tenant_id,journey_id) REFERENCES journey(tenant_id,id),
 CHECK(captured_minor<=authorized_minor), CHECK(refunded_minor<=captured_minor)
);
CREATE TABLE expense_document (
 tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, legal_entity_id uuid NOT NULL,
 object_key text NOT NULL, original_sha256 char(64) NOT NULL, mime text NOT NULL,
 state text NOT NULL, revision bigint NOT NULL, created_by text NOT NULL, approved_by text,
 original_received_at timestamptz NOT NULL, PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,object_key),
 FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES legal_entity(tenant_id,id)
);
CREATE INDEX expense_hash ON expense_document(tenant_id,original_sha256);
CREATE TABLE cost_object (
 tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, legal_entity_id uuid NOT NULL,
 kind text NOT NULL CHECK(kind IN ('VEHICLE','SERVICE','RUN','LEG','PROJECT','COST_CENTER','UNALLOCATED')),
 business_ref text NOT NULL, PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,kind,business_ref),
 FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES legal_entity(tenant_id,id)
);
CREATE TABLE economic_event (
 tenant_id uuid NOT NULL, id uuid NOT NULL, legal_entity_id uuid NOT NULL, source_system text NOT NULL, source_ref text NOT NULL,
 event_type text NOT NULL, scenario text NOT NULL CHECK(scenario IN ('ACTUAL','PLAN','FORECAST','SIMULATION')),
 cost_behavior text CHECK(cost_behavior IN ('FIXED','VARIABLE','MIXED')),
 attribution text CHECK(attribution IN ('DIRECT','ALLOCATED')), category text NOT NULL,
 currency char(3) NOT NULL REFERENCES currency(code), net_minor bigint NOT NULL, tax_minor bigint NOT NULL, gross_minor bigint NOT NULL,
 recoverable_tax_minor bigint NOT NULL DEFAULT 0,
 cost_basis_minor bigint GENERATED ALWAYS AS (gross_minor-recoverable_tax_minor) STORED,
 tax_rule_revision text NOT NULL, occurred_at timestamptz NOT NULL, booked_at timestamptz NOT NULL,
 service_from timestamptz, service_to timestamptz, document_id uuid, reverses_id uuid,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,legal_entity_id,source_system,source_ref,event_type,scenario),
 FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES legal_entity(tenant_id,id),
 FOREIGN KEY(tenant_id,document_id) REFERENCES expense_document(tenant_id,id),
 FOREIGN KEY(tenant_id,reverses_id) REFERENCES economic_event(tenant_id,id),
 CHECK(net_minor+tax_minor=gross_minor),
 CHECK(abs(recoverable_tax_minor)<=abs(tax_minor) AND (recoverable_tax_minor=0 OR sign(recoverable_tax_minor)=sign(tax_minor))), CHECK(service_to IS NULL OR service_to>=service_from),
 UNIQUE(tenant_id,id,legal_entity_id,currency)
);
CREATE TABLE allocation_set (
 tenant_id uuid NOT NULL, id uuid NOT NULL, event_id uuid NOT NULL, revision bigint NOT NULL,
 legal_entity_id uuid NOT NULL, currency char(3) NOT NULL, basis_minor bigint NOT NULL,
 algorithm_version text NOT NULL, driver_snapshot jsonb NOT NULL, state text NOT NULL CHECK(state IN ('DRAFT','POSTED')),
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,event_id,revision),
 FOREIGN KEY(tenant_id,event_id,legal_entity_id,currency) REFERENCES economic_event(tenant_id,id,legal_entity_id,currency)
);
CREATE TABLE allocation_line (
 tenant_id uuid NOT NULL, id uuid NOT NULL, allocation_id uuid NOT NULL, cost_object_id uuid NOT NULL,
 amount_minor bigint NOT NULL, weight numeric(20,8) NOT NULL CHECK(weight>=0),
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,allocation_id,cost_object_id),
 FOREIGN KEY(tenant_id,allocation_id) REFERENCES allocation_set(tenant_id,id),
 FOREIGN KEY(tenant_id,cost_object_id) REFERENCES cost_object(tenant_id,id)
);
CREATE TABLE fiscal_period (
 tenant_id uuid NOT NULL, id uuid NOT NULL, legal_entity_id uuid NOT NULL,
 starts_on date NOT NULL, ends_on date NOT NULL, closed_at timestamptz,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,id,legal_entity_id),
 FOREIGN KEY(tenant_id,legal_entity_id) REFERENCES legal_entity(tenant_id,id), CHECK(ends_on>starts_on)
);
CREATE TABLE journal (
 tenant_id uuid NOT NULL, id uuid NOT NULL, event_id uuid NOT NULL, legal_entity_id uuid NOT NULL,
 currency char(3) NOT NULL, period_id uuid NOT NULL, accounting_date date NOT NULL,
 state text NOT NULL CHECK(state IN ('DRAFT','POSTED')), posted_by text,
 PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,event_id),
 FOREIGN KEY(tenant_id,event_id,legal_entity_id,currency) REFERENCES economic_event(tenant_id,id,legal_entity_id,currency),
 FOREIGN KEY(tenant_id,period_id,legal_entity_id) REFERENCES fiscal_period(tenant_id,id,legal_entity_id)
);
CREATE TABLE journal_line (
 tenant_id uuid NOT NULL, id uuid NOT NULL, journal_id uuid NOT NULL, account_code text NOT NULL,
 side char(1) NOT NULL CHECK(side IN ('D','C')), amount_minor bigint NOT NULL CHECK(amount_minor>0),
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,journal_id) REFERENCES journal(tenant_id,id)
);
CREATE TABLE settlement_line (
 tenant_id uuid NOT NULL, id uuid NOT NULL, event_id uuid NOT NULL, leg_id uuid NOT NULL,
 contract_version_id uuid NOT NULL, creditor_ref text NOT NULL, debtor_ref text NOT NULL,
 currency char(3) NOT NULL REFERENCES currency(code), payable_minor bigint NOT NULL, revision bigint NOT NULL,
 reverses_id uuid, PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,event_id,leg_id,creditor_ref,revision),
 FOREIGN KEY(tenant_id,event_id) REFERENCES economic_event(tenant_id,id),
 FOREIGN KEY(tenant_id,leg_id) REFERENCES journey_leg(tenant_id,id),
 FOREIGN KEY(tenant_id,contract_version_id) REFERENCES partner_contract_version(tenant_id,id),
 FOREIGN KEY(tenant_id,reverses_id) REFERENCES settlement_line(tenant_id,id)
);
CREATE TABLE idempotency_record (
 tenant_id uuid NOT NULL REFERENCES tenant(id), principal_ref text NOT NULL, command_scope text NOT NULL,
 key text NOT NULL, request_sha256 char(64) NOT NULL, operation_id uuid, response_json jsonb,
 expires_at timestamptz NOT NULL, PRIMARY KEY(tenant_id,principal_ref,command_scope,key),
 FOREIGN KEY(tenant_id,operation_id) REFERENCES operation(tenant_id,id)
);
CREATE TABLE outbox (
 tenant_id uuid NOT NULL REFERENCES tenant(id), event_id uuid NOT NULL, aggregate_id uuid NOT NULL,
 aggregate_revision bigint NOT NULL, event_type text NOT NULL, schema_version text NOT NULL,
 occurred_at timestamptz NOT NULL, payload jsonb NOT NULL, published_at timestamptz,
 PRIMARY KEY(tenant_id,event_id), UNIQUE(tenant_id,aggregate_id,aggregate_revision,event_type)
);
CREATE INDEX outbox_unpublished ON outbox(occurred_at) WHERE published_at IS NULL;
CREATE TABLE inbox (
 tenant_id uuid NOT NULL REFERENCES tenant(id), consumer text NOT NULL, event_id uuid NOT NULL,
 payload_sha256 char(64) NOT NULL, received_at timestamptz NOT NULL,
 PRIMARY KEY(tenant_id,consumer,event_id)
);
CREATE TABLE audit_event (
 tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, actor_ref text NOT NULL,
 action text NOT NULL, object_ref text NOT NULL, occurred_at timestamptz NOT NULL,
 correlation_id uuid NOT NULL, redacted_changes jsonb NOT NULL, PRIMARY KEY(tenant_id,id)
);
-- Fail-closed Mandantenschutz. API-Runtime darf Kontext nur nach Auth-Pruefung setzen.
-- Keine Verbindung fuer Endkunden; GUC ist kein eigener Authentisierungsmechanismus.
DO $$ DECLARE t record; BEGIN
 FOR t IN SELECT table_name FROM information_schema.columns WHERE table_schema='taktovia' AND column_name='tenant_id' LOOP
  EXECUTE format('ALTER TABLE taktovia.%I ENABLE ROW LEVEL SECURITY',t.table_name);
  EXECUTE format('ALTER TABLE taktovia.%I FORCE ROW LEVEL SECURITY',t.table_name);
  EXECUTE format('CREATE POLICY tenant_isolation ON taktovia.%I USING (tenant_id=taktovia.current_tenant()) WITH CHECK (tenant_id=taktovia.current_tenant())',t.table_name);
 END LOOP;
END $$;
ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;
CREATE POLICY own_tenant ON tenant USING(id=current_tenant()) WITH CHECK(id=current_tenant());
-- Unveraenderliche wirtschaftliche Originalereignisse, Vertragsstaende und Audit.
CREATE FUNCTION reject_mutation() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$ BEGIN
 RAISE EXCEPTION 'immutable record; append a correction' USING ERRCODE='23514'; END $$;
CREATE TRIGGER economic_immutable BEFORE UPDATE OR DELETE ON economic_event FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER audit_immutable BEFORE UPDATE OR DELETE ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER contract_immutable BEFORE UPDATE OR DELETE ON partner_contract_version FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER quote_immutable BEFORE UPDATE OR DELETE ON fare_quote FOR EACH ROW EXECUTE FUNCTION reject_mutation();
-- Zeilen und Kopf gemeinsam sperren, damit ein gebuchtes Journal nicht nachtraeglich veraendert wird.
CREATE FUNCTION protect_journal_line() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE j journal%ROWTYPE; tid uuid; jid uuid;
BEGIN
 IF TG_OP='UPDATE' AND (NEW.tenant_id,NEW.journal_id) IS DISTINCT FROM (OLD.tenant_id,OLD.journal_id) THEN
  RAISE EXCEPTION 'journal reassignment forbidden';
 END IF;
 IF TG_OP='DELETE' THEN tid:=OLD.tenant_id; jid:=OLD.journal_id; ELSE tid:=NEW.tenant_id; jid:=NEW.journal_id; END IF;
 SELECT * INTO j FROM journal WHERE tenant_id=tid AND id=jid FOR UPDATE;
 IF j.state='POSTED' THEN RAISE EXCEPTION 'posted journal immutable'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER journal_line_guard BEFORE INSERT OR UPDATE OR DELETE ON journal_line FOR EACH ROW EXECUTE FUNCTION protect_journal_line();
CREATE FUNCTION guard_journal() RETURNS trigger LANGUAGE plpgsql SET search_path = taktovia, pg_catalog AS $$
DECLARE total numeric; line_count bigint; p fiscal_period%ROWTYPE;
BEGIN
 IF TG_OP<>'INSERT' AND OLD.state='POSTED' THEN RAISE EXCEPTION 'posted journal immutable'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 IF NEW.state='POSTED' THEN
  IF TG_OP='INSERT' THEN RAISE EXCEPTION 'create draft, add lines, then post'; END IF;
  SELECT * INTO p FROM fiscal_period WHERE tenant_id=NEW.tenant_id AND id=NEW.period_id FOR UPDATE;
  IF p.closed_at IS NOT NULL OR NEW.accounting_date<p.starts_on OR NEW.accounting_date>=p.ends_on THEN RAISE EXCEPTION 'period closed or date outside period'; END IF;
  SELECT coalesce(sum(CASE side WHEN 'D' THEN amount_minor ELSE -amount_minor END),0),count(*) INTO total,line_count FROM journal_line WHERE tenant_id=NEW.tenant_id AND journal_id=NEW.id;
  IF total<>0 OR line_count<2 OR NEW.posted_by IS NULL THEN RAISE EXCEPTION 'journal not balanced or missing posting identity'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER journal_guard BEFORE INSERT OR UPDATE OR DELETE ON journal FOR EACH ROW EXECUTE FUNCTION guard_journal();
-- Weitere fachliche Summen-/Statuspruefungen in 04 beschrieben; nicht durch diese DDL behauptet.
