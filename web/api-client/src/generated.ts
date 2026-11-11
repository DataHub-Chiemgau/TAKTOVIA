// GENERIERT von api/tools/gen_ts_client.py aus api/*.yaml - nicht von Hand ändern (python api/tools/gen_ts_client.py --write).

export type UUID = string;

export interface SignedMoney {
  amount_minor: number;
  currency: string;
  exponent: number;
}

export type Timestamp = string;

export type TimeZone = string;

export interface ZonedMoment {
  desired_at: Timestamp;
  time_zone: TimeZone;
}

export interface LocalDateTimeInZone {
  local: string;
  time_zone: TimeZone;
  utc_offset?: string | null;
}

export interface ValidityInterval {
  valid_from: Timestamp;
  valid_to?: string | null;
}

export interface Point {
  latitude: number;
  longitude: number;
}

export interface Operation {
  operation_id: UUID;
  kind: 'HOLD' | 'CONFIRM' | 'CANCEL' | 'RECONCILE';
  status: 'QUEUED' | 'RUNNING' | 'RECONCILING' | 'SUCCEEDED' | 'FAILED' | 'MANUAL_REVIEW';
  journey_id: UUID | null;
  created_at: string;
  updated_at: string;
  result_code: string | null;
  retry_after_seconds: number;
}

export interface PublishRequest {
  effective_from: Timestamp;
  reason: string;
  impact_report_id: UUID;
}

export interface PublishedRevision {
  resource_id: UUID;
  revision: number;
  state: 'PUBLISHED';
  effective_from: string;
  audit_event_id: UUID;
}

export type ErrorCode = 'BAD_REQUEST' | 'INVALID_CURSOR' | 'UNAUTHENTICATED' | 'FORBIDDEN' | 'COOPERATION_NOT_ALLOWED' | 'NOT_FOUND' | 'METHOD_NOT_ALLOWED' | 'NOT_ACCEPTABLE' | 'CONFLICT' | 'IDEMPOTENCY_CONFLICT' | 'OPERATION_IN_PROGRESS' | 'OFFER_EXPIRED' | 'HOLD_EXPIRED' | 'REQUOTE_REQUIRED' | 'CAPACITY_UNAVAILABLE' | 'IMMUTABLE_RECORD' | 'INVALID_STATE_TRANSITION' | 'REVISION_MISMATCH' | 'PAYLOAD_TOO_LARGE' | 'UNSUPPORTED_MEDIA_TYPE' | 'VALIDATION_FAILED' | 'PRECONDITION_REQUIRED' | 'RATE_LIMITED' | 'INTERNAL_ERROR' | 'SERVICE_UNAVAILABLE' | 'PROVIDER_UNAVAILABLE';

export type ViolationCode = 'REQUIRED' | 'UNKNOWN_FIELD' | 'TYPE_MISMATCH' | 'TOO_SHORT' | 'TOO_LONG' | 'BELOW_MINIMUM' | 'ABOVE_MAXIMUM' | 'PATTERN_MISMATCH' | 'INVALID_FORMAT' | 'NOT_IN_ENUM' | 'TOO_FEW_ITEMS' | 'TOO_MANY_ITEMS' | 'NOT_UNIQUE' | 'INVALID_VALUE' | 'NESTING_TOO_DEEP' | 'MONEY_NOT_INTEGER' | 'MONEY_OUT_OF_RANGE' | 'MONEY_NEGATIVE' | 'CURRENCY_UNKNOWN' | 'EXPONENT_MISMATCH' | 'OFFSET_REQUIRED' | 'LOCAL_TIME_NONEXISTENT' | 'LOCAL_TIME_AMBIGUOUS' | 'ZONE_UNKNOWN' | 'ZONE_OFFSET_MISMATCH' | 'INTERVAL_INVALID' | 'TARGET_NOT_ALLOWED';

export interface Money {
  amount_minor: number;
  currency: string;
  exponent: number;
}

export interface Problem {
  type: string;
  title: string;
  status: number;
  code: string;
  detail?: string;
  correlation_id: UUID;
  violations?: ({
    pointer: string;
    code: string;
    message: string;
  })[];
}

export interface Place {
  label: string;
  point: Point;
  stop_id?: UUID;
}

export interface Party {
  persons: number;
  wheelchair_places: number;
  child_seats: number;
  assistance_codes: ('BOARDING_HELP' | 'AUDIO_GUIDANCE' | 'LOW_FLOOR')[];
}

export interface PlanRequest {
  origin: Place;
  destination: Place;
  time_mode: 'DEPART_AFTER' | 'ARRIVE_BY';
  desired_at: string;
  time_zone: string;
  party: Party;
  max_transfers: number;
  booking_subject: BookingSubject;
  entitlement_refs: UUID[];
}

export interface PartnerAvailability {
  operator_id: UUID;
  state: 'AVAILABLE' | 'UNAVAILABLE' | 'TIMEOUT' | 'STALE';
  reason_code: 'CAPACITY' | 'STAFF_UNAVAILABLE' | 'NO_SERVICE' | 'PARTNER_TIMEOUT' | 'CONTRACT_INACTIVE' | 'STALE_DATA' | null;
}

export interface PlanResponse {
  request_id: UUID;
  evaluated_at: string;
  offers: JourneyOffer[];
  partners: PartnerAvailability[];
  reject_reason: 'NO_ROUTE' | 'CAPACITY' | 'STAFF_UNAVAILABLE' | 'NO_SERVICE' | 'NO_ACCESSIBLE_TRANSFER' | 'PROVIDER_UNAVAILABLE' | null;
}

export interface Transfer {
  from_leg_id: UUID;
  to_leg_id: UUID;
  hub_id: UUID;
  walking_seconds: number;
  minimum_transfer_seconds: number;
  available_seconds: number;
  accessible: boolean;
  assurance: 'NOT_GUARANTEED' | 'CONTRACTUAL_PROTECTION';
  policy_revision: string;
}

export interface FareComponent {
  code: 'TRANSPORT' | 'DISCOUNT' | 'SURCHARGE' | 'SUBSIDY' | 'COMMISSION' | 'TAX';
  description: string;
  amount: SignedMoney;
  tax_code?: string;
  tariff_revision: string;
}

export interface CancellationTerms {
  policy_revision: string;
  terms_uri: string;
  terms_sha256: string;
  free_until: string | null;
  refundable: boolean;
}

export interface OfferLeg {
  leg_id: UUID;
  sequence: number;
  operator_id: UUID;
  seller_legal_entity_id: UUID | null;
  mode: 'ON_DEMAND' | 'BUS' | 'RAIL' | 'WALK';
  origin: Place;
  destination: Place;
  departure_at: string;
  arrival_at: string;
  booking_capability: 'PLATFORM_BOOKABLE' | 'EXTERNAL_BOOKING' | 'INFORMATION_ONLY';
  fare_status: 'KNOWN' | 'UNKNOWN' | 'NOT_APPLICABLE';
  quoted_fare: Money | null;
  fare_components: FareComponent[];
  tariff_revision: string | null;
  partner_offer_ref: string | null;
  expires_at: string | null;
  external_booking_uri: string | null;
  cancellation_terms: CancellationTerms | null;
  accessibility_confirmed: boolean;
  pickup_window: TimeWindow | null;
  arrival_window: TimeWindow | null;
}

export interface PriceSummary {
  pay_at_platform: Money;
  external_known: Money;
  external_unknown_leg_ids: UUID[];
  total_known_amount: Money;
  is_complete_total: boolean;
}

export interface JourneyOffer {
  offer_id: UUID;
  revision: number;
  created_at: string;
  expires_at: string;
  legs: OfferLeg[];
  transfers: Transfer[];
  price: PriceSummary;
  terms_bundle_sha256: string;
  booking_policy: 'COMPENSATE_ON_FAILURE' | 'ALLOW_PARTIAL_WITH_EXPLICIT_CONSENT';
  fully_platform_bookable: boolean;
}

export interface HoldRequest {
  offer_revision: number;
  accepted_terms_sha256: string;
  acknowledged_external_leg_ids: UUID[];
  partial_booking_consent: boolean;
}

export interface PaymentAction {
  type: 'PSP_AUTHENTICATION';
  redirect_uri: string;
  expires_at: string;
}

export interface ConfirmRequest {
  payment_method_token?: string;
  max_authorized_amount: Money;
  accepted_terms_sha256: string;
  return_uri?: string;
}

export interface JourneyLeg {
  leg_id: UUID;
  sequence: number;
  operator_id: UUID;
  booking_capability: 'PLATFORM_BOOKABLE' | 'EXTERNAL_BOOKING' | 'INFORMATION_ONLY';
  booking_reference: string | null;
  state: 'OFFERED' | 'HOLDING' | 'HELD' | 'CONFIRMING' | 'CONFIRMED' | 'CANCEL_PENDING' | 'CANCELED' | 'COMPLETED' | 'FAILED' | 'UNKNOWN' | 'INFORMATION_ONLY' | 'EXTERNAL_REQUIRED';
  promise_revision: number;
  origin: Place;
  destination: Place;
  estimated_arrival_at: string | null;
  fare: Money | null;
  hold_expires_at: string | null;
  pickup_window: TimeWindow | null;
  arrival_window: TimeWindow | null;
  planned_departure_at: string;
  planned_arrival_at: string;
  execution_state: 'PLANNED' | 'APPROACHING' | 'BOARDED' | 'COMPLETED' | 'NO_SHOW' | 'DISRUPTED';
}

export interface Journey {
  journey_id: UUID;
  revision: number;
  offer_id: UUID;
  state: 'HOLDING' | 'HELD' | 'CONFIRMING' | 'ACTION_REQUIRED' | 'CONFIRMED' | 'PARTIAL' | 'COMPENSATING' | 'CANCEL_PENDING' | 'CANCELED' | 'COMPLETED' | 'FAILED' | 'RECONCILING';
  coverage: 'ALL_TRANSPORT_LEGS' | 'PLATFORM_SEGMENTS_ONLY';
  legs: JourneyLeg[];
  price: PriceSummary;
  payment_state: 'NOT_REQUIRED' | 'PENDING' | 'AUTHORIZING' | 'REQUIRES_ACTION' | 'AUTHORIZED' | 'CAPTURING' | 'CAPTURED' | 'VOIDED' | 'PARTIALLY_REFUNDED' | 'REFUNDED' | 'FAILED' | 'UNKNOWN';
  payment_action: PaymentAction | null;
  operation_id: UUID | null;
  updated_at: string;
  execution_state: 'NOT_STARTED' | 'IN_PROGRESS' | 'TRANSFER_AT_RISK' | 'DISRUPTED' | 'COMPLETED' | 'PARTIALLY_COMPLETED' | 'ABANDONED';
  transfer_risks: TransferRisk[];
}

export interface CancellationQuoteRequest {
  leg_ids: UUID[];
  reason_code: 'CUSTOMER_REQUEST' | 'TRANSFER_MISSED' | 'OPERATOR_FAILURE' | 'SAFETY';
}

export interface CancellationLine {
  leg_id: UUID;
  cancellable: boolean;
  fee: Money;
  refund: Money;
  policy_revision: string;
}

export interface CancellationQuote {
  cancellation_quote_id: UUID;
  journey_id: UUID;
  journey_revision: number;
  expires_at: string;
  lines: CancellationLine[];
  total_refund: Money;
  total_fee: Money;
  remaining_journey_viable: boolean;
}

export interface CancelRequest {
  cancellation_quote_id: UUID;
  accept_remaining_journey_risk: boolean;
}

export interface PartnerEvent {
  event_id: UUID;
  event_type: 'partner.offer_held' | 'partner.booking_confirmed' | 'partner.booking_canceled' | 'partner.booking_failed' | 'partner.leg_delayed';
  tenant_id: UUID;
  schema_version: '1.0';
  occurred_at: string;
  correlation_id: UUID;
  aggregate_id: UUID;
  aggregate_version: number;
  data: PartnerEventData;
}

export interface PartnerEventData {
  partner_booking_ref: string;
  command_id: UUID;
  leg_id: UUID;
  state: 'HELD' | 'CONFIRMED' | 'CANCELED' | 'FAILED' | 'DELAYED';
  hold_expires_at: string | null;
  estimated_arrival_at: string | null;
  failure_code: string | null;
}

export interface EventReceipt {
  event_id: UUID;
  status: 'ACCEPTED' | 'ALREADY_RECEIVED';
}

export interface TimeWindow {
  earliest_at: string;
  latest_at: string;
}

export interface BookingSubject {
  mode: 'SELF' | 'DELEGATED';
  passenger_ref: UUID | null;
  delegation_ref: UUID | null;
}

export interface TransferRisk {
  from_leg_id: UUID;
  to_leg_id: UUID;
  state: 'UNKNOWN' | 'ON_TRACK' | 'AT_RISK' | 'MISSED';
  predicted_margin_seconds: number | null;
  observed_at: string;
  reason_code: string;
}

export type DecimalQuantity = string;

export interface CostAllocationLine {
  cost_center_id: UUID;
  cost_object_type: 'VEHICLE' | 'SERVICE' | 'RUN' | 'LEG' | 'PROJECT' | 'COST_CENTER' | 'UNALLOCATED';
  cost_object_id: UUID;
  amount: SignedMoney;
  quantity: DecimalQuantity | null;
  unit: 'KM' | 'MIN' | 'KWH' | 'L' | 'ITEM' | null;
}

export interface AllocationRequest {
  allocation_policy_revision: string;
  reason: string;
  lines: CostAllocationLine[];
}

export interface AllocationResult {
  allocation_id: UUID;
  cost_event_id: UUID;
  revision: number;
  state: 'POSTED';
  basis: SignedMoney;
  lines: CostAllocationLine[];
}

export interface ApprovalRequest {
  reason: string;
  validated_document_revision: number;
}

export interface ExpenseApproval {
  expense_id: UUID;
  revision: number;
  state: 'WAITING_SECOND_APPROVAL' | 'POSTED';
  posting_id: UUID | null;
}

export interface DriverEvent {
  event_id: UUID;
  shift_id: UUID;
  vehicle_id: UUID;
  plan_revision: number;
  event_type: 'CHECK_IN' | 'CHECK_OUT' | 'NO_SHOW';
  booking_id: UUID;
  route_stop_id: UUID;
  occurred_at: string;
  device_recorded_at: string;
  position?: Point;
  accuracy_meters?: number;
  offline_recorded: boolean;
}

export interface DriverEventResult {
  event_id: UUID;
  state: 'APPLIED' | 'DUPLICATE' | 'REVIEW_REQUIRED';
  received_at: string;
  current_plan_revision: number;
  reason_code: 'STALE_PLAN' | 'OUTSIDE_STOP' | 'CLOCK_SKEW' | 'INVALID_ORDER' | null;
}

export interface AuditEvent {
  id: UUID;
  actor_ref: string;
  action: string;
  object_ref: string;
  occurred_at: string;
  correlation_id: UUID;
  redacted_changes?: Record<string, unknown>;
}

export interface AuditEventPage {
  items: AuditEvent[];
  next_cursor: string | null;
  as_of: string;
}

export interface CompanyProfile {
  legal_name: string;
  address: string;
  contact_email: string;
  revision: number;
}

export interface CompanyProfileUpdate {
  legal_name: string;
  address: string;
  contact_email: string;
}

export interface RetentionRule {
  id: UUID;
  data_category: string;
  retention_days: number;
  action: 'ARCHIVE' | 'DELETE' | 'ANONYMIZE';
  revision: number;
}

export interface RetentionRuleUpdate {
  retention_days: number;
  action: 'ARCHIVE' | 'DELETE' | 'ANONYMIZE';
}

export interface RetentionRulePage {
  items: RetentionRule[];
  next_cursor: string | null;
  as_of: string;
}

export interface VehicleRoleAssignment {
  id: UUID;
  vehicle_id: UUID;
  role_code: string;
  valid_from: string;
  valid_to: string | null;
  revision: number;
}

export interface VehicleRoleAssignmentCreate {
  vehicle_id: UUID;
  role_code: string;
  valid_from: Timestamp;
  valid_to?: string | null;
}

export interface VehicleRoleAssignmentPage {
  items: VehicleRoleAssignment[];
  next_cursor: string | null;
  as_of: string;
}

export interface DriverClearance {
  id: UUID;
  driver_ref: UUID;
  vehicle_id: UUID;
  valid_from: string;
  valid_to: string;
  revision: number;
}

export interface DriverClearanceCreate {
  driver_ref: UUID;
  vehicle_id: UUID;
  valid_from: Timestamp;
  valid_to: string;
}

export interface DriverClearancePage {
  items: DriverClearance[];
  next_cursor: string | null;
  as_of: string;
}

export interface Me {
  subject: string;
  issuer: string;
  tenant_id: UUID;
  tenant_name: string;
  roles: string[];
  service_scope: UUID[] | null;
  session_id: UUID;
  mfa: boolean;
  tenants: ({
    tenant_id: UUID;
    name: string;
  })[];
  scopes?: string[];
  context_token?: string;
  context_expires_at?: string;
}

export interface TenantSwitchRequest {
  tenant_id: UUID;
  service_id?: UUID;
}

export interface Membership {
  id: UUID;
  issuer: string;
  subject: string;
  role_code: string;
  service_scope: UUID[] | null;
  valid_from?: string;
  valid_to?: string | null;
  revoked_at: string | null;
  revision: number;
}

export interface MembershipCreate {
  issuer: string;
  subject: string;
  role_code: 'passenger' | 'driver' | 'callcenter' | 'support' | 'dispatcher' | 'planner' | 'tenant_admin' | 'finance' | 'staff_planning' | 'workshop';
  service_scope?: UUID[] | null;
  valid_from?: string;
  valid_to?: string | null;
}

export interface LegalEntity {
  id: UUID;
  name: string;
  country: string;
  revision: number;
}

export interface LegalEntityCreate {
  name: string;
  country: string;
  tax_reference_token?: string;
}

export interface LegalEntityUpdate {
  name: string;
  country: string;
}

export interface Region {
  id: UUID;
  name: string;
  revision: number;
}

export interface RegionCreate {
  name: string;
}

export interface Depot {
  id: UUID;
  region_id: UUID;
  name: string;
  revision: number;
}

export interface DepotCreate {
  region_id: UUID;
  name: string;
}

export interface CostCenter {
  id: UUID;
  code: string;
  name: string;
  legal_entity_id: UUID;
  revision: number;
}

export interface CostCenterCreate {
  code: string;
  name: string;
  legal_entity_id: UUID;
}

export interface Session {
  id: UUID;
  subject: string;
  created_at: string;
  last_seen_at: string;
  revoked_at: string | null;
  revision: number;
}

export interface MembershipPage {
  items: Membership[];
  next_cursor: string | null;
  as_of: string;
}

export interface LegalEntityPage {
  items: LegalEntity[];
  next_cursor: string | null;
  as_of: string;
}

export interface RegionPage {
  items: Region[];
  next_cursor: string | null;
  as_of: string;
}

export interface DepotPage {
  items: Depot[];
  next_cursor: string | null;
  as_of: string;
}

export interface CostCenterPage {
  items: CostCenter[];
  next_cursor: string | null;
  as_of: string;
}

export interface SessionPage {
  items: Session[];
  next_cursor: string | null;
  as_of: string;
}

export interface Passenger {
  id: UUID;
  status: 'PENDING_VERIFICATION' | 'ACTIVE' | 'BLOCKED';
  locale: string;
  created_at: string;
  revision: number;
}

export interface PassengerRegistration {
  contact: {
    channel: 'EMAIL' | 'PHONE';
    value: string;
  };
  locale: string;
}

export interface PassengerPage {
  items: Passenger[];
  next_cursor: string | null;
  as_of: string;
}

export interface EffectiveConfig {
  as_of: string;
  values: Record<string, unknown>;
  revision_ids: UUID[];
}

export interface ConfigRevision {
  id: UUID;
  scope_kind: 'TENANT' | 'REGION' | 'DEPOT' | 'SERVICE';
  scope_id: UUID;
  revision: number;
  state: 'DRAFT' | 'VALIDATED' | 'APPROVED' | 'PUBLISHED' | 'SUPERSEDED';
  schema_version: string;
  values: Record<string, unknown>;
  effective_from: string;
  effective_to: string | null;
  created_by: string;
  approved_by: string | null;
}

export interface ConfigRevisionCreate {
  scope_kind: 'TENANT' | 'REGION' | 'DEPOT' | 'SERVICE';
  scope_id: UUID;
  schema_version: string;
  values: Record<string, unknown>;
  effective_from: Timestamp;
}

export interface ConfigValidationReport {
  revision_id: UUID;
  valid: boolean;
  violations: ({
    pointer: string;
    code: string;
    message: string;
  })[];
}

export interface ConfigImpactReport {
  id: UUID;
  revision_id: UUID;
  generated_at: string;
  affected: ({
    kind: string;
    count: number;
  })[];
}

export interface ConfigRevisionPage {
  items: ConfigRevision[];
  next_cursor: string | null;
  as_of: string;
}

export interface Service {
  id: UUID;
  operator_id: UUID;
  name: string;
  timezone: TimeZone;
  revision: number;
}

export interface ServiceCreate {
  operator_id: UUID;
  name: string;
  timezone: TimeZone;
}

export interface ServicePage {
  items: Service[];
  next_cursor: string | null;
  as_of: string;
}

export interface WebhookSubscription {
  id: UUID;
  url: string;
  event_types: string[];
  active: boolean;
  revision: number;
}

export interface WebhookSubscriptionCreate {
  url: string;
  event_types: string[];
}

export interface WebhookSubscriptionPage {
  items: WebhookSubscription[];
  next_cursor: string | null;
  as_of: string;
}

export interface Operations {
  planJourney: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: PlanRequest;
    response: PlanResponse;
  };
  getOffer: {
    path: { offer_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: JourneyOffer;
  };
  holdOffer: {
    path: { offer_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: HoldRequest;
    response: Operation;
  };
  getJourney: {
    path: { journey_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: Journey;
  };
  confirmJourney: {
    path: { journey_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: ConfirmRequest;
    response: Operation;
  };
  quoteCancellation: {
    path: { journey_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: CancellationQuoteRequest;
    response: CancellationQuote;
  };
  cancelJourney: {
    path: { journey_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: CancelRequest;
    response: Operation;
  };
  receivePartnerEvent: {
    path: { partner_id: UUID };
    query: Record<string, never>;
    headers: { 'X-Partner-Key-Id': string; 'X-Partner-Timestamp': string };
    body: PartnerEvent;
    response: EventReceipt;
  };
  getOperation: {
    path: { operation_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: Operation;
  };
  allocateCost: {
    path: { cost_event_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: AllocationRequest;
    response: AllocationResult;
  };
  approveExpense: {
    path: { expense_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: ApprovalRequest;
    response: ExpenseApproval;
  };
  publishConfig: {
    path: { revision_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: PublishRequest;
    response: PublishedRevision;
  };
  publishRoster: {
    path: { roster_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: PublishRequest;
    response: PublishedRevision;
  };
  recordDriverEvent: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: DriverEvent;
    response: DriverEventResult;
  };
  listAuditEvents: {
    path: Record<string, never>;
    query: { limit?: number; cursor?: string; object_ref?: string; actor_ref?: string; action?: string; from?: string; to?: string; purpose: 'security_review' | 'incident_response' | 'support_case' | 'compliance_check' };
    headers: { traceparent?: string };
    body: undefined;
    response: AuditEventPage;
  };
  getCompanyProfile: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: CompanyProfile;
  };
  updateCompanyProfile: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: CompanyProfileUpdate;
    response: CompanyProfile;
  };
  listRetentionRules: {
    path: Record<string, never>;
    query: { limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: RetentionRulePage;
  };
  updateRetentionRule: {
    path: { rule_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: RetentionRuleUpdate;
    response: RetentionRule;
  };
  listVehicleRoles: {
    path: Record<string, never>;
    query: { limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: VehicleRoleAssignmentPage;
  };
  createVehicleRole: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: VehicleRoleAssignmentCreate;
    response: VehicleRoleAssignment;
  };
  listDriverClearances: {
    path: Record<string, never>;
    query: { limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: DriverClearancePage;
  };
  createDriverClearance: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: DriverClearanceCreate;
    response: DriverClearance;
  };
  getMe: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: Me;
  };
  switchTenant: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: TenantSwitchRequest;
    response: Me;
  };
  listMemberships: {
    path: Record<string, never>;
    query: { limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: MembershipPage;
  };
  createMembership: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: MembershipCreate;
    response: Membership;
  };
  revokeMembership: {
    path: { membership_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: undefined;
    response: undefined;
  };
  listLegalEntities: {
    path: Record<string, never>;
    query: { limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: LegalEntityPage;
  };
  createLegalEntity: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: LegalEntityCreate;
    response: LegalEntity;
  };
  updateLegalEntity: {
    path: { legal_entity_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: LegalEntityUpdate;
    response: LegalEntity;
  };
  listRegions: {
    path: Record<string, never>;
    query: { limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: RegionPage;
  };
  createRegion: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: RegionCreate;
    response: Region;
  };
  listDepots: {
    path: Record<string, never>;
    query: { limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: DepotPage;
  };
  createDepot: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: DepotCreate;
    response: Depot;
  };
  listCostCenters: {
    path: Record<string, never>;
    query: { limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: CostCenterPage;
  };
  createCostCenter: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: CostCenterCreate;
    response: CostCenter;
  };
  listSessions: {
    path: Record<string, never>;
    query: { limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: SessionPage;
  };
  revokeSession: {
    path: { session_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: undefined;
    response: undefined;
  };
  listPassengers: {
    path: Record<string, never>;
    query: { limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: PassengerPage;
  };
  registerPassenger: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: PassengerRegistration;
    response: Passenger;
  };
  getEffectiveConfig: {
    path: Record<string, never>;
    query: { at?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: EffectiveConfig;
  };
  listConfigRevisions: {
    path: Record<string, never>;
    query: { limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: ConfigRevisionPage;
  };
  createConfigRevision: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: ConfigRevisionCreate;
    response: ConfigRevision;
  };
  getConfigRevision: {
    path: { revision_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: ConfigRevision;
  };
  validateConfigRevision: {
    path: { revision_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: undefined;
    response: ConfigValidationReport;
  };
  requestConfigImpact: {
    path: { revision_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: undefined;
    response: ConfigImpactReport;
  };
  listServices: {
    path: Record<string, never>;
    query: { limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: ServicePage;
  };
  createService: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: ServiceCreate;
    response: Service;
  };
  listWebhookSubscriptions: {
    path: Record<string, never>;
    query: { limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: WebhookSubscriptionPage;
  };
  createWebhookSubscription: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: WebhookSubscriptionCreate;
    response: WebhookSubscription;
  };
}

export type OperationId = keyof Operations;

export interface OperationInfo {
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  path: string;
  pathParams: readonly string[];
  query: readonly string[];
  headers: readonly string[];
  hasBody: boolean;
  scopes: readonly string[];
  statuses: readonly number[];
}

export const operationTable: Record<OperationId, OperationInfo> = {
  planJourney: { method: 'POST', path: '/v1/federation/journeys/plan', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['journeys:plan'], statuses: [200, 400, 401, 403, 404, 409, 422, 429, 503] },
  getOffer: { method: 'GET', path: '/v1/federation/offers/{offer_id}', pathParams: ['offer_id'], query: [], headers: ['traceparent'], hasBody: false, scopes: ['journeys:read'], statuses: [200, 400, 401, 403, 404, 409, 422, 429, 503] },
  holdOffer: { method: 'POST', path: '/v1/federation/offers/{offer_id}/hold', pathParams: ['offer_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['journeys:book'], statuses: [202, 400, 401, 403, 404, 409, 422, 429, 503, 412, 428] },
  getJourney: { method: 'GET', path: '/v1/federation/journeys/{journey_id}', pathParams: ['journey_id'], query: [], headers: ['traceparent'], hasBody: false, scopes: ['journeys:read'], statuses: [200, 400, 401, 403, 404, 409, 422, 429, 503] },
  confirmJourney: { method: 'POST', path: '/v1/federation/journeys/{journey_id}/confirm', pathParams: ['journey_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['journeys:book'], statuses: [202, 400, 401, 403, 404, 409, 422, 429, 503, 412, 428] },
  quoteCancellation: { method: 'POST', path: '/v1/federation/journeys/{journey_id}/cancellation-quotes', pathParams: ['journey_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['journeys:cancel'], statuses: [201, 400, 401, 403, 404, 409, 422, 429, 503, 412, 428] },
  cancelJourney: { method: 'POST', path: '/v1/federation/journeys/{journey_id}/cancel', pathParams: ['journey_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['journeys:cancel'], statuses: [202, 400, 401, 403, 404, 409, 422, 429, 503, 412, 428] },
  receivePartnerEvent: { method: 'POST', path: '/v1/federation/partners/{partner_id}/events', pathParams: ['partner_id'], query: [], headers: ['X-Partner-Key-Id', 'X-Partner-Timestamp'], hasBody: true, scopes: [], statuses: [202, 200, 400, 401, 403, 409, 422, 429, 503] },
  getOperation: { method: 'GET', path: '/v1/operations/{operation_id}', pathParams: ['operation_id'], query: [], headers: ['traceparent'], hasBody: false, scopes: ['operations:read'], statuses: [200, 400, 401, 403, 404, 409, 422, 429, 503] },
  allocateCost: { method: 'POST', path: '/v1/cost-events/{cost_event_id}/allocations', pathParams: ['cost_event_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['costs:allocate'], statuses: [201, 400, 401, 403, 404, 409, 422, 429, 503, 412, 428] },
  approveExpense: { method: 'POST', path: '/v1/expenses/{expense_id}/approve', pathParams: ['expense_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['expenses:approve'], statuses: [200, 400, 401, 403, 404, 409, 422, 429, 503, 412, 428] },
  publishConfig: { method: 'POST', path: '/v1/config/revisions/{revision_id}/publish', pathParams: ['revision_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['config:publish'], statuses: [200, 400, 401, 403, 404, 409, 422, 429, 503, 412, 428] },
  publishRoster: { method: 'POST', path: '/v1/rosters/{roster_id}/publish', pathParams: ['roster_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['rosters:publish'], statuses: [200, 400, 401, 403, 404, 409, 422, 429, 503, 412, 428] },
  recordDriverEvent: { method: 'POST', path: '/v1/driver/events', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['driver:events'], statuses: [200, 400, 401, 403, 404, 409, 422, 429, 503] },
  listAuditEvents: { method: 'GET', path: '/v1/audit-events', pathParams: [], query: ['limit', 'cursor', 'object_ref', 'actor_ref', 'action', 'from', 'to', 'purpose'], headers: ['traceparent'], hasBody: false, scopes: ['audit:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  getCompanyProfile: { method: 'GET', path: '/v1/company-profile', pathParams: [], query: [], headers: ['traceparent'], hasBody: false, scopes: ['company:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  updateCompanyProfile: { method: 'PUT', path: '/v1/company-profile', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['company:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  listRetentionRules: { method: 'GET', path: '/v1/retention-rules', pathParams: [], query: ['limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['retention:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  updateRetentionRule: { method: 'PUT', path: '/v1/retention-rules/{rule_id}', pathParams: ['rule_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['retention:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  listVehicleRoles: { method: 'GET', path: '/v1/vehicle-roles', pathParams: [], query: ['limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['fleet:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  createVehicleRole: { method: 'POST', path: '/v1/vehicle-roles', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['fleet:write'], statuses: [201, 400, 401, 403, 409, 422, 429, 503] },
  listDriverClearances: { method: 'GET', path: '/v1/driver-clearances', pathParams: [], query: ['limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['fleet:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  createDriverClearance: { method: 'POST', path: '/v1/driver-clearances', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['fleet:write'], statuses: [201, 400, 401, 403, 409, 422, 429, 503] },
  getMe: { method: 'GET', path: '/v1/me', pathParams: [], query: [], headers: ['traceparent'], hasBody: false, scopes: ['me:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  switchTenant: { method: 'POST', path: '/v1/me/tenant-switch', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['tenant:switch'], statuses: [200, 400, 401, 403, 404, 409, 422, 429, 503] },
  listMemberships: { method: 'GET', path: '/v1/memberships', pathParams: [], query: ['limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['memberships:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  createMembership: { method: 'POST', path: '/v1/memberships', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['memberships:write'], statuses: [201, 400, 401, 403, 409, 422, 429, 503] },
  revokeMembership: { method: 'DELETE', path: '/v1/memberships/{membership_id}', pathParams: ['membership_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: false, scopes: ['memberships:write'], statuses: [204, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  listLegalEntities: { method: 'GET', path: '/v1/legal-entities', pathParams: [], query: ['limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['organization:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  createLegalEntity: { method: 'POST', path: '/v1/legal-entities', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['organization:write'], statuses: [201, 400, 401, 403, 409, 422, 429, 503] },
  updateLegalEntity: { method: 'PUT', path: '/v1/legal-entities/{legal_entity_id}', pathParams: ['legal_entity_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['organization:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  listRegions: { method: 'GET', path: '/v1/regions', pathParams: [], query: ['limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['organization:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  createRegion: { method: 'POST', path: '/v1/regions', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['organization:write'], statuses: [201, 400, 401, 403, 409, 422, 429, 503] },
  listDepots: { method: 'GET', path: '/v1/depots', pathParams: [], query: ['limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['organization:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  createDepot: { method: 'POST', path: '/v1/depots', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['organization:write'], statuses: [201, 400, 401, 403, 409, 422, 429, 503] },
  listCostCenters: { method: 'GET', path: '/v1/cost-centers', pathParams: [], query: ['limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['organization:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  createCostCenter: { method: 'POST', path: '/v1/cost-centers', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['organization:write'], statuses: [201, 400, 401, 403, 409, 422, 429, 503] },
  listSessions: { method: 'GET', path: '/v1/sessions', pathParams: [], query: ['limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['sessions:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  revokeSession: { method: 'DELETE', path: '/v1/sessions/{session_id}', pathParams: ['session_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: false, scopes: ['sessions:write'], statuses: [204, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  listPassengers: { method: 'GET', path: '/v1/passengers', pathParams: [], query: ['limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['passengers:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  registerPassenger: { method: 'POST', path: '/v1/passengers', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['passengers:write'], statuses: [201, 400, 401, 403, 409, 422, 429, 503] },
  getEffectiveConfig: { method: 'GET', path: '/v1/config/effective', pathParams: [], query: ['at'], headers: ['traceparent'], hasBody: false, scopes: ['config:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  listConfigRevisions: { method: 'GET', path: '/v1/config/revisions', pathParams: [], query: ['limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['config:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  createConfigRevision: { method: 'POST', path: '/v1/config/revisions', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['config:write'], statuses: [201, 400, 401, 403, 409, 422, 429, 503] },
  getConfigRevision: { method: 'GET', path: '/v1/config/revisions/{revision_id}', pathParams: ['revision_id'], query: [], headers: ['traceparent'], hasBody: false, scopes: ['config:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  validateConfigRevision: { method: 'POST', path: '/v1/config/revisions/{revision_id}/validate', pathParams: ['revision_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: false, scopes: ['config:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  requestConfigImpact: { method: 'POST', path: '/v1/config/revisions/{revision_id}/impact', pathParams: ['revision_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: false, scopes: ['config:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  listServices: { method: 'GET', path: '/v1/services', pathParams: [], query: ['limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['services:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  createService: { method: 'POST', path: '/v1/services', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['services:write'], statuses: [201, 400, 401, 403, 409, 422, 429, 503] },
  listWebhookSubscriptions: { method: 'GET', path: '/v1/webhook-subscriptions', pathParams: [], query: ['limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['webhooks:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  createWebhookSubscription: { method: 'POST', path: '/v1/webhook-subscriptions', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['webhooks:write'], statuses: [201, 400, 401, 403, 409, 422, 429, 503] },
};
