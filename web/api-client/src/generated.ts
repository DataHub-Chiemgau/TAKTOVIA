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
  kind: 'HOLD' | 'CONFIRM' | 'CANCEL' | 'RECONCILE' | 'PUBLISH_CONFIG' | 'IMPACT_ANALYSIS' | 'RETENTION_RUN' | 'TEST_COMMAND';
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

export type ViolationCode = 'REQUIRED' | 'UNKNOWN_FIELD' | 'TYPE_MISMATCH' | 'TOO_SHORT' | 'TOO_LONG' | 'BELOW_MINIMUM' | 'ABOVE_MAXIMUM' | 'PATTERN_MISMATCH' | 'INVALID_FORMAT' | 'NOT_IN_ENUM' | 'TOO_FEW_ITEMS' | 'TOO_MANY_ITEMS' | 'NOT_UNIQUE' | 'INVALID_VALUE' | 'NESTING_TOO_DEEP' | 'MONEY_NOT_INTEGER' | 'MONEY_OUT_OF_RANGE' | 'MONEY_NEGATIVE' | 'CURRENCY_UNKNOWN' | 'EXPONENT_MISMATCH' | 'OFFSET_REQUIRED' | 'LOCAL_TIME_NONEXISTENT' | 'LOCAL_TIME_AMBIGUOUS' | 'ZONE_UNKNOWN' | 'ZONE_OFFSET_MISMATCH' | 'INTERVAL_INVALID' | 'TARGET_NOT_ALLOWED' | 'UNIT_MISMATCH' | 'SAFETY_INVARIANT_VIOLATED';

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
  event_type: 'ARRIVAL' | 'CHECK_IN' | 'CHECK_OUT' | 'NO_SHOW' | 'BREAK_START' | 'BREAK_END' | 'DISRUPTION' | 'SHIFT_END';
  booking_id?: UUID | null;
  route_stop_id?: UUID | null;
  occurred_at: string;
  device_recorded_at: string;
  position?: Point;
  accuracy_meters?: number;
  offline_recorded: boolean;
  device_id?: UUID;
  no_show_wait_seconds?: number;
  no_show_reason?: 'NOT_PRESENT' | 'REFUSED_TO_BOARD' | 'OTHER';
}

export interface DriverEventResult {
  event_id: UUID;
  state: 'APPLIED' | 'DUPLICATE' | 'REVIEW_REQUIRED';
  received_at: string;
  current_plan_revision: number;
  reason_code: 'STALE_PLAN' | 'OUTSIDE_STOP' | 'CLOCK_SKEW' | 'INVALID_ORDER' | 'RELEASE_REQUIRED' | 'SHIFT_ENDED' | null;
  original_state?: 'APPLIED' | 'REVIEW_REQUIRED' | null;
}

export interface DriverPositionSample {
  position: Point;
  accuracy_meters: number;
  measured_at: string;
  speed_mps?: number | null;
  heading_deg?: number | null;
}

export interface DriverPositionBatch {
  shift_id: UUID;
  vehicle_id: UUID;
  device_id: UUID;
  samples: DriverPositionSample[];
}

export interface DriverPositionResult {
  accepted: number;
  rejected: number;
  received_at: string;
  rejections?: ({
    index: number;
    reason: 'FUTURE_TIME' | 'OUTSIDE_SERVICE';
  })[];
}

export interface DriverRunStop {
  route_stop_id: UUID;
  sequence: number;
  kind: 'PICKUP' | 'DROPOFF' | 'WAYPOINT';
  planned_at: string;
  forecast_at: string | null;
  stop_name: string;
  position: Point;
  passenger_count: number;
  booking_ids: UUID[];
  change: 'NEW' | 'REMOVED' | 'MOVED' | 'TIME_CHANGED' | null;
  support_hints: string[];
}

export interface DriverRun {
  shift_id: UUID;
  vehicle_id: UUID;
  shift_state: string;
  shift_valid: boolean;
  release_state: 'NOT_RELEASED' | 'RELEASED' | 'BLOCKED' | 'RESTRICTED';
  missing_requirements: MissingRequirement[];
  plan_revision: number | null;
  plan_as_of: string;
  stops: DriverRunStop[];
  acknowledged_revision: number | null;
}

export interface DriverRunAck {
  plan_revision: number;
  acknowledged_at: string;
}

export interface DriverDeviceRegister {
  device_id: UUID;
  platform: 'ANDROID' | 'IOS';
  app_version: string;
  push_token_ref?: string | null;
}

export interface DriverDevice {
  device_id: UUID;
  session_id: UUID;
  platform: 'ANDROID' | 'IOS';
  app_version: string;
  registered_at: string;
  revoked_at: string | null;
  revision: number;
}

export interface DriverShift {
  shift_id: UUID;
  vehicle_id: UUID;
  vehicle_label: string | null;
  starts_at: string;
  ends_at: string;
  timezone: string;
  state: string;
  startable_from: string;
  started_at: string | null;
  ended_at: string | null;
  valid: boolean;
}

export interface DriverShiftList {
  items: DriverShift[];
  as_of: string;
}

export interface DriverShiftStart {
  shift_id: UUID;
  vehicle_id: UUID;
  device_id: UUID;
}

export interface DriverShiftEnd {
  shift_id: UUID;
  device_id: UUID;
}

export interface DriverShiftStartResult {
  shift: DriverShift;
  already_started: boolean;
}

export interface AbsenceCreate {
  person_ref: string;
  from: string;
  to: string;
  kind: 'LEAVE' | 'TRAINING' | 'OTHER';
}

export interface Absence {
  id: UUID;
  person_ref: string;
  from: string;
  to: string;
  kind: 'LEAVE' | 'TRAINING' | 'OTHER';
  state: 'APPROVED' | 'WITHDRAWN';
  created_by: string;
  created_at: string;
  revision: number;
}

export interface AvailabilityRestriction {
  id: UUID;
  person_ref: string;
  from: string;
  to: string;
  availability: 'UNAVAILABLE';
}

export interface AvailabilityRestrictionPage {
  items: AvailabilityRestriction[];
  next_cursor: string | null;
  as_of: string;
}

export interface RideResourceVector {
  schema_version?: '1.0';
  seats: number;
  wheelchair: number;
  equipment?: ('RAMP' | 'LIFT' | 'CHILD_SEAT' | 'BOOSTER_SEAT' | 'LUGGAGE_SPACE' | 'ASSISTANCE_DOG_SPACE' | 'STRETCHER_FIXING')[];
}

export interface RidePlace {
  stop_id?: UUID;
  point: Point;
  label?: string;
}

export type RideAssistanceCodes = ('BOARDING_HELP' | 'AUDIO_GUIDANCE' | 'LOW_FLOOR' | 'WHEELCHAIR_SPACE' | 'ASSISTANCE_ANIMAL' | 'ACCOMPANYING_PERSON')[];

export interface RideOfferRequest {
  service_id: UUID;
  origin: RidePlace;
  destination: RidePlace;
  time_kind: 'DEPARTURE' | 'ARRIVAL';
  desired: ZonedMoment;
  passengers: number;
  wheelchair_spaces?: number;
  equipment?: ('RAMP' | 'LIFT' | 'CHILD_SEAT' | 'BOOSTER_SEAT' | 'LUGGAGE_SPACE' | 'ASSISTANCE_DOG_SPACE' | 'STRETCHER_FIXING')[];
  assistance_codes?: RideAssistanceCodes;
}

export interface RideWindow {
  earliest_at: Timestamp;
  latest_at: Timestamp;
}

export interface RideOffer {
  id: UUID;
  attempt_id: UUID;
  service_id: UUID;
  state: 'OFFERED' | 'HELD' | 'EXPIRED' | 'DECLINED';
  pickup_window: RideWindow;
  arrival_window: RideWindow;
  expires_at: Timestamp;
  run_revision?: number;
  gross: Money;
  tariff_revision?: number;
  cancellation_policy_ref?: string;
  demand: RideResourceVector;
  hold: RideHold | null;
  revision: number;
}

export interface RideHold {
  id: UUID;
  state: 'HELD' | 'CONSUMED' | 'RELEASED' | 'EXPIRED';
  expires_at: Timestamp;
  capacity_vector: RideResourceVector;
}

export interface RideHoldRequest {
  max_gross: Money;
}

export interface RideConfirmRequest {
  hold_id: UUID;
  max_gross: Money;
  payment_reference?: string;
}

export interface RideBooking {
  id: UUID;
  service_id: UUID;
  hold_id: UUID;
  state: 'CONFIRMING' | 'CONFIRMED' | 'RECONCILING' | 'COMPENSATING' | 'CANCEL_PENDING' | 'CANCELED' | 'COMPLETED' | 'FAILED';
  passengers: number;
  wheelchair_spaces: number;
  assistance_codes?: RideAssistanceCodes;
  promise: RidePromise;
  revision: number;
}

export interface RidePromise {
  pickup_earliest: Timestamp;
  pickup_latest: Timestamp;
  arrival_earliest: Timestamp;
  arrival_latest: Timestamp;
  gross: Money;
  cancellation_policy_ref: string;
  vehicle_class?: string;
  resource_vector: RideResourceVector;
}

export interface RideCancelRequest {
  reason: 'PASSENGER_REQUEST' | 'OPERATOR_CANCELLATION' | 'NO_SHOW' | 'PAYMENT_FAILED' | 'SAFETY';
}

export interface RideBookingPage {
  items: RideBooking[];
  next_cursor: string | null;
  as_of: string;
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
  contact_email: string;
  contact_phone?: string;
  address?: string;
  billing_address?: string;
  billing_email?: string;
  operating_mode?: string;
  legal_notice?: string;
  default_currency: string;
  revision: number;
}

export interface CompanyProfileUpdate {
  legal_name: string;
  contact_email: string;
  contact_phone?: string;
  address?: string;
  billing_address?: string;
  billing_email?: string;
  operating_mode?: string;
  legal_notice?: string;
  default_currency: string;
}

export interface RetentionRule {
  id: UUID;
  data_category: string;
  retention_days: number;
  action: 'ARCHIVE' | 'DELETE' | 'ANONYMIZE';
  archive_days?: number | null;
  status?: 'DRAFT' | 'APPROVED';
  revision: number;
}

export interface RetentionRuleUpdate {
  retention_days: number;
  action: 'ARCHIVE' | 'DELETE' | 'ANONYMIZE';
  archive_days?: number | null;
}

export interface RetentionRuleCreate {
  data_category: string;
  retention_days: number;
  action: 'ARCHIVE' | 'DELETE' | 'ANONYMIZE';
  archive_days?: number | null;
}

export type RetentionRuleApprovalRequest = Record<string, never>;

export interface ErasureJobStart {
  rule_id: UUID;
}

export interface ErasureJob {
  id: UUID;
  rule_id: UUID;
  data_category: string;
  action: 'ARCHIVE' | 'DELETE' | 'ANONYMIZE';
  status: 'RUNNING' | 'RESUMABLE' | 'COMPLETED' | 'ABORTED';
  processed_count: number;
  skipped_count: number;
  result_code?: string | null;
  operation_id?: UUID | null;
  created_at: string;
  finished_at?: string | null;
  revision: number;
}

export interface ErasureJobPage {
  items: ErasureJob[];
  next_cursor: string | null;
  as_of: string;
}

export interface ErasureJournalEntry {
  id: UUID;
  job_id: UUID;
  data_category: string;
  object_ref: string;
  action: 'ARCHIVE' | 'DELETE' | 'ANONYMIZE';
  applied_at: string;
}

export interface ErasureJournalPage {
  items: ErasureJournalEntry[];
  next_cursor: string | null;
  as_of: string;
}

export interface RetentionRulePage {
  items: RetentionRule[];
  next_cursor: string | null;
  as_of: string;
}

export interface RunStopChange {
  route_stop_id: UUID;
  change: 'MOVE' | 'REMOVE' | 'INSERT';
  new_sequence?: number | null;
  booking_id?: UUID | null;
}

export interface RunRevisionCreate {
  changes: RunStopChange[];
  reason: string;
  base_plan_revision: number;
  compare_only?: boolean;
}

export interface RunRevision {
  run_id: UUID;
  plan_revision: number;
  previous_revision: number | null;
  cause: 'BOOKING_INSERTED' | 'BOOKING_REMOVED' | 'MANUAL_CHANGE' | 'REPLAN';
  reason: string | null;
  created_at: string;
  created_by: string;
  diff: ({
    route_stop_id: UUID;
    kind: 'ADDED' | 'REMOVED' | 'MOVED' | 'TIME_CHANGED';
    old_sequence: number | null;
    new_sequence: number | null;
  })[];
  committed: boolean;
}

export interface RunRevisionPage {
  items: RunRevision[];
  next_cursor: string | null;
  as_of: string;
}

export interface DriverReleaseCreate {
  driver_id: UUID;
  recipient_tenant_id: UUID;
  service_ids: UUID[];
  valid_from: Timestamp;
  valid_to: Timestamp;
}

export interface DriverRelease {
  id: UUID;
  driver_id: UUID;
  recipient_tenant_id: UUID;
  service_ids: UUID[];
  valid_from: string;
  valid_to: string;
  revoked_at: string | null;
  revision: number;
}

export interface DriverReleaseProjection {
  id: UUID;
  service_ids: UUID[];
  valid_from: string;
  valid_to: string;
  qualifications: ({
    code: string;
    valid_from: string;
    valid_to: string | null;
  })[];
  contact_channel: {
    kind: 'PHONE' | 'EMAIL' | 'APP';
    value: string;
  };
}

export type DriverStatus = 'ACTIVE' | 'INACTIVE';

export type ContactChannel = 'PHONE' | 'EMAIL' | 'APP';

export type QualificationStatus = 'PENDING' | 'APPROVED' | 'REVOKED';

export interface DriverCreate {
  membership_id: UUID;
  display_name: string;
  contact_channel: ContactChannel;
  contact_value: string;
}

export interface DriverUpdate {
  display_name: string;
  contact_channel: ContactChannel;
  contact_value: string;
  active: boolean;
}

export interface Driver {
  id: UUID;
  membership_id: UUID | null;
  status: DriverStatus;
  qualification_seq: number;
  revision: number;
  hr_fields_visible: boolean;
  display_name: string | null;
  contact_channel: ContactChannel | null;
  contact_value: string | null;
  created_at: string;
  updated_at: string;
}

export interface DriverPage {
  items: Driver[];
  next_cursor: string | null;
  as_of: string;
}

export interface QualificationInput {
  qualification_code: string;
  valid_from: string;
  valid_to?: string | null;
  evidence_ref?: string | null;
  decision: QualificationStatus;
}

export interface QualificationRevision {
  id: UUID;
  qualification_code: string;
  revision: number;
  status: QualificationStatus;
  valid_from: string;
  valid_to: string | null;
  approved_by: string | null;
  approved_at: string | null;
  driver_seq: number | null;
  evidence_ref: string | null;
  effective: boolean;
  valid_at: boolean;
  created_at: string;
  driver_revision?: number;
}

export interface QualificationPage {
  items: QualificationRevision[];
  next_cursor: string | null;
  as_of: string;
  at: string;
}

export interface VehicleRoleAssignment {
  id: UUID;
  vehicle_id: UUID;
  role_code: 'OWNER' | 'OPERATOR' | 'DISPATCH_PROVIDER' | 'BILLING_PARTNER';
  organization_id: UUID;
  valid_from: string;
  valid_to: string | null;
  revision: number;
}

export interface VehicleRoleAssignmentCreate {
  vehicle_id: UUID;
  role_code: 'OWNER' | 'OPERATOR' | 'DISPATCH_PROVIDER' | 'BILLING_PARTNER';
  organization_id: UUID;
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

export interface CommittedRide {
  booking_id: UUID;
  state: string;
  pickup_earliest: string;
  pickup_latest: string;
}

export interface VehicleServiceAssignmentCreate {
  service_id: UUID;
  service_area_id?: UUID | null;
  valid_from: string;
  valid_to?: string | null;
}

export interface VehicleServiceAssignmentEnd {
  valid_to: string;
}

export interface VehicleServiceAssignment {
  id: UUID;
  vehicle_id: UUID;
  service_id: UUID;
  service_area_id: UUID | null;
  valid_from: string;
  valid_to: string | null;
  revision: number;
  created_at: string;
  committed_rides_after_end: CommittedRide[];
}

export interface VehicleServiceAssignmentPage {
  items: VehicleServiceAssignment[];
  next_cursor: string | null;
  as_of: string;
}

export type VehicleOperationalState = 'NOT_READY' | 'RELEASE_PENDING' | 'AVAILABLE' | 'EN_ROUTE' | 'WAITING' | 'BREAK' | 'DISRUPTED' | 'BLOCKED' | 'RESTRICTED' | 'ENDED';

export interface MissingRequirement {
  code: 'NO_INSPECTION' | 'INSPECTION_OPEN' | 'INSPECTION_EXPIRED' | 'DEFECT_BLOCK' | 'OPERATIONAL_BLOCK' | 'SAFETY_REVISION_CHANGED' | 'QUALIFICATION_MISSING' | 'VEHICLE_CHANGED' | 'RELEASE_REVOKED';
  inspection_id?: UUID | null;
  step_codes?: string[];
}

export interface VehicleBlock {
  id: UUID;
  vehicle_id: UUID;
  source: 'OPERATIONS' | 'SAFETY';
  reason_code: 'OPERATIONAL_DISRUPTION' | 'DEFECT_CRITICAL' | 'INSPECTION_FAILED' | 'MANUAL' | 'OTHER';
  set_at: string;
  set_by: string;
  lifted_at: string | null;
  lifted_by: string | null;
  revision: number;
}

export interface OperatorVehicle {
  vehicle_id: UUID;
  vehicle_label: string;
  operational_state: VehicleOperationalState;
  safety_revision: number;
  release_valid_until: string | null;
  missing_requirements: MissingRequirement[];
  active_blocks: VehicleBlock[];
  position: Point | null;
  position_measured_at: string | null;
  position_received_at?: string | null;
  position_age_seconds: number | null;
  position_stale: boolean;
  open_defect_count: number;
  current_run_id: UUID | null;
  revision: number;
}

export interface OperatorVehiclePage {
  items: OperatorVehicle[];
  next_cursor: string | null;
  as_of: string;
}

export interface VehicleBlockCreate {
  reason_code: 'OPERATIONAL_DISRUPTION' | 'MANUAL' | 'OTHER';
  comment?: string;
}

export interface VehicleBlockLift {
  comment?: string;
}

export interface ChecklistTemplate {
  id: UUID;
  template_id: UUID;
  version: number;
  vehicle_class: string;
  service_mode: string;
  published_at: string;
  steps: ({
    step_code: string;
    title: string;
    mandatory: boolean;
    safety_critical: boolean;
    answer_kind: 'OK_DEFECT_NA' | 'NUMBER' | 'TEXT_SHORT';
    photo_required_on_defect: boolean;
  })[];
}

export interface ChecklistTemplatePage {
  items: ChecklistTemplate[];
  next_cursor: string | null;
  as_of: string;
}

export interface InspectionAnswer {
  step_code: string;
  answer: 'OK' | 'DEFECT' | 'NOT_APPLICABLE' | 'NOT_CHECKABLE';
  number_value?: number | null;
  note?: string | null;
  evidence_ids?: UUID[];
  answered_at: string;
}

export interface InspectionCreate {
  shift_id: UUID;
  vehicle_id: UUID;
  template_revision_id: UUID;
  kind: 'PRE_SHIFT' | 'FOLLOW_UP' | 'VEHICLE_CHANGE';
  odometer_km?: number | null;
  battery_soc_pct?: number | null;
  client_started_at: string;
}

export interface InspectionDraft {
  answers: InspectionAnswer[];
}

export interface InspectionSubmit {
  answers: InspectionAnswer[];
  device_event_id?: UUID;
  client_submitted_at: string;
}

export interface Inspection {
  id: UUID;
  shift_id: UUID;
  vehicle_id: UUID;
  driver_ref: UUID;
  template_revision_id: UUID;
  kind: 'PRE_SHIFT' | 'FOLLOW_UP' | 'VEHICLE_CHANGE';
  status: 'IN_PROGRESS' | 'COMPLETED' | 'EXPIRED' | 'CANCELED';
  outcome: 'PASSED' | 'PASSED_RESTRICTED' | 'BLOCKED' | 'CLARIFICATION_REQUIRED' | null;
  missing_requirements: MissingRequirement[];
  answers: InspectionAnswer[];
  release: {
    release_id: UUID;
    valid_until: string;
    safety_revision: number;
  } | null;
  started_at: string;
  completed_at: string | null;
  received_at: string | null;
  revision: number;
}

export type DefectSeverity = 'SAFETY_CRITICAL' | 'OPERATIONALLY_RELEVANT' | 'COSMETIC' | 'OTHER';

export interface DefectCreate {
  vehicle_id: UUID;
  occurred_at: string;
  location_kind?: 'COORDINATE' | 'ADDRESS_TEXT' | 'FREE_TEXT' | 'DEPOT';
  location_value?: string;
  category_code: string;
  description: string;
  source_inspection_id?: UUID | null;
  source_step_code?: string | null;
  evidence_ids?: UUID[];
  device_event_id?: UUID;
  severity_hint?: DefectSeverity;
}

export interface DefectTriage {
  severity: DefectSeverity;
  decision: 'REVIEWED' | 'IN_PROGRESS' | 'DISMISSED';
  assignee_ref?: UUID | null;
  reason?: string | null;
}

export interface Defect {
  id: UUID;
  case_number: string;
  vehicle_id: UUID;
  reported_by: UUID;
  reported_at: string;
  occurred_at: string;
  category_code: string;
  severity: DefectSeverity | null;
  status: 'NEW' | 'REVIEWED' | 'IN_PROGRESS' | 'REPAIR' | 'CLOSED' | 'DISMISSED';
  assignee_ref: UUID | null;
  blocks_vehicle: boolean;
  evidence_ids: UUID[];
  description: string | null;
  revision: number;
}

export interface DefectPage {
  items: Defect[];
  next_cursor: string | null;
  as_of: string;
}

export interface RepairOrderCreate {
  defect_id: UUID;
  workshop_ref?: string | null;
  planned_at?: string | null;
  cost_reference?: string | null;
}

export interface RepairOrder {
  id: UUID;
  defect_id: UUID;
  vehicle_id: UUID;
  status: 'OPEN' | 'IN_PROGRESS' | 'PROOF_RECORDED' | 'CANCELED';
  workshop_ref: string | null;
  planned_at: string | null;
  cost_reference: string | null;
  created_at: string;
  revision: number;
}

export interface RepairOrderPage {
  items: RepairOrder[];
  next_cursor: string | null;
  as_of: string;
}

export interface VehicleReleaseCreate {
  repair_order_id: UUID;
  follow_up_inspection_id: UUID;
  reason: string;
  second_approver_ref?: UUID | null;
}

export interface VehicleRelease {
  id: UUID;
  vehicle_id: UUID;
  decision: 'RELEASED' | 'PENDING_SECOND_APPROVAL' | 'REJECTED';
  decided_by: string;
  decided_at: string;
  safety_revision: number;
  lifted_block_ids: UUID[];
  revision: number;
}

export interface Incident {
  id: UUID;
  kind: 'VEHICLE_OUTAGE' | 'DEFECT' | 'REJECTION' | 'CRITICAL_DELAY';
  vehicle_id: UUID | null;
  status: 'OPEN' | 'REPLACEMENT_SEARCH' | 'RESOLVED' | 'CLOSED_NO_REPLACEMENT';
  dedupe_key: string;
  opened_at: string;
  updated_at: string;
  rule_revision_id: UUID | null;
  affected_run_ids: UUID[];
  replacement_operation_id: UUID | null;
  revision: number;
}

export interface IncidentPage {
  items: Incident[];
  next_cursor: string | null;
  as_of: string;
}

export interface IncidentTransition {
  event: 'START_REPLACEMENT_SEARCH' | 'RESOLVE' | 'CLOSE_WITHOUT_REPLACEMENT';
  comment?: string | null;
}

export type VehicleEquipment = 'RAMP' | 'LIFT' | 'CHILD_SEAT' | 'BOOSTER_SEAT' | 'LUGGAGE_SPACE' | 'ASSISTANCE_DOG_SPACE' | 'STRETCHER_FIXING';

export interface VehicleCapacityBasic {
  seats: number;
  wheelchair: number;
}

export interface VehicleMasterWrite {
  vehicle_label: string;
  vehicle_type: string;
  operator_organization_id: UUID;
  capacity: VehicleCapacityBasic;
  equipment: VehicleEquipment[];
  active: boolean;
}

export interface VehicleMaster {
  id: UUID;
  vehicle_label: string | null;
  vehicle_type: string | null;
  operator_organization_id: UUID | null;
  capacity: VehicleCapacityBasic;
  equipment: VehicleEquipment[];
  active: boolean;
  dispatch_blocked: boolean;
  dispatchable: boolean;
  safety_revision: number;
  has_capacity_layout: boolean;
  revision: number;
  created_at: string;
  updated_at: string;
}

export interface VehicleMasterPage {
  items: VehicleMaster[];
  next_cursor: string | null;
  as_of: string;
}

export interface CapacitySpaces {
  seat_places: number;
  wheelchair_spaces: number;
  seats_replaced_per_wheelchair: number;
}

export interface CapacityCombination {
  code: string;
  wheelchair_places: number;
}

export interface CapacityRestriction {
  code: 'MAX_TOTAL_PERSONS' | 'MAX_WHEELCHAIR_SPACES';
  value: number;
}

export interface CapacityLayout {
  schema_version: '1.0';
  spaces: CapacitySpaces;
  equipment: VehicleEquipment[];
  combinations: CapacityCombination[];
  active_combination: string;
  restrictions: CapacityRestriction[];
}

export interface CapacityPatch {
  spaces?: CapacitySpaces;
  equipment?: VehicleEquipment[];
  combinations?: CapacityCombination[];
  active_combination?: string;
  restrictions?: CapacityRestriction[];
}

export interface ConfiguredResourceVector {
  code: string;
  seats: number;
  wheelchair: number;
  equipment: VehicleEquipment[];
}

export interface CapacityConfiguration {
  vehicle_id: UUID;
  layout: CapacityLayout | null;
  effective: ConfiguredResourceVector;
  vectors: ConfiguredResourceVector[];
  revision: number;
}

export interface GeoMultiPolygon {
  type: 'MultiPolygon';
  coordinates: number[][][][];
}

export interface GeoServiceArea {
  id: UUID;
  revision_id: UUID;
  zone_role: 'ORIGIN' | 'DESTINATION' | 'BOTH';
  service_id: UUID;
  revision: number;
  area: GeoMultiPolygon;
  valid_from: Timestamp;
  valid_to: Timestamp | null;
}

export interface GeoServiceAreaUpdate {
  area: GeoMultiPolygon;
  zone_role?: 'ORIGIN' | 'DESTINATION' | 'BOTH';
  valid_from: Timestamp;
  valid_to?: Timestamp | null;
}

export interface GeoServiceAreaCreate {
  service_id: UUID;
  area: GeoMultiPolygon;
  zone_role?: 'ORIGIN' | 'DESTINATION' | 'BOTH';
  valid_from: Timestamp;
  valid_to?: Timestamp | null;
}

export interface GeoServiceAreaPage {
  items: GeoServiceArea[];
  next_cursor: string | null;
  as_of: string;
}

export interface GeoRelationRuleInput {
  destination_stop_ids: UUID[];
  valid_from: Timestamp;
  valid_to?: Timestamp | null;
  reason: string;
}

export interface GeoRelationRule {
  service_id: UUID;
  revision: number;
  destination_stop_ids: UUID[];
  valid_from: Timestamp | null;
  valid_to: Timestamp | null;
  reason?: string | null;
  created_by?: string | null;
  created_at?: Timestamp | null;
}

export interface GeoRelationCheck {
  service_id: UUID;
  at: string;
  admissible: boolean;
  reason: 'OUTSIDE_AREA' | 'RULE_FORBIDDEN' | null;
  detail: string | null;
}

export interface GeoStopAccessibility {
  schema_version: '1.0';
  step_free: 'YES' | 'NO' | 'UNVERIFIED';
  wheelchair_boarding: 'YES' | 'NO' | 'UNVERIFIED';
  tactile_guidance?: 'YES' | 'NO' | 'UNVERIFIED';
  shelter?: 'YES' | 'NO' | 'UNVERIFIED';
  verified_at?: string;
  note?: string;
}

export interface GeoStop {
  id: UUID;
  stop_id: UUID;
  revision: number;
  name: string;
  point: Point;
  accessibility: GeoStopAccessibility;
  traffic_products: GeoTrafficProducts;
  active: boolean;
  approved_by: string;
  valid_from: Timestamp;
  valid_to: Timestamp | null;
}

export type GeoTrafficProducts = string[];

export interface GeoStopUpdate {
  name: string;
  point: Point;
  accessibility: GeoStopAccessibility;
  traffic_products: GeoTrafficProducts;
  active: boolean;
  valid_from: Timestamp;
  valid_to?: Timestamp | null;
}

export interface GeoStopCreate {
  stop_id?: UUID | null;
  name: string;
  point: Point;
  accessibility: GeoStopAccessibility;
  traffic_products: GeoTrafficProducts;
  active?: boolean;
  valid_from: Timestamp;
  valid_to?: Timestamp | null;
}

export interface GeoStopPage {
  items: GeoStop[];
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

export interface RegionUpdate {
  name: string;
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

export interface DepotUpdate {
  region_id: UUID;
  name: string;
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
  region_id?: UUID | null;
  service_id?: UUID | null;
  revision: number;
}

export interface CostCenterUpdate {
  name: string;
  region_id?: UUID | null;
  service_id?: UUID | null;
}

export interface CostCenterCreate {
  code: string;
  name: string;
  legal_entity_id: UUID;
  region_id?: UUID | null;
  service_id?: UUID | null;
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

export type OrganizationRole = 'PLATFORM_OPERATOR' | 'TRANSPORT_COMPANY' | 'TASK_AUTHORITY' | 'CLIENT' | 'BROKER' | 'ACTUAL_CARRIER';

export interface Organization {
  id: UUID;
  legal_entity_id: UUID;
  name: string;
  federation_ref: string;
  roles: OrganizationRole[];
  roles_valid_from: string;
  roles_valid_to: string | null;
  revision: number;
}

export interface OrganizationCreate {
  legal_entity_id: UUID;
  name: string;
  federation_ref: string;
  roles: OrganizationRole[];
  roles_valid_from?: string;
  roles_valid_to?: string | null;
}

export interface OrganizationUpdate {
  legal_entity_id: UUID;
  name: string;
  roles: OrganizationRole[];
  roles_valid_from?: string;
  roles_valid_to?: string | null;
}

export interface OrganizationPage {
  items: Organization[];
  next_cursor: string | null;
  as_of: string;
}

export interface TenantCreate {
  name: string;
  legal_entity: {
    name: string;
    country: string;
    tax_reference_token?: string;
  };
  first_admin: {
    issuer: string;
    subject: string;
  };
  contact_email: string;
  default_currency?: string;
}

export interface TenantCreated {
  id: UUID;
  name: string;
  status: TenantStatus;
  legal_entity_id: UUID;
  admin_membership_id: UUID;
}

export type TenantStatus = 'ACTIVE' | 'SUSPENDED' | 'CLOSED';

export interface TenantStatusChange {
  managed_tenant_id: UUID;
  status: TenantStatus;
}

export interface TenantStatusChanged {
  managed_tenant_id: UUID;
  from: TenantStatus;
  status: TenantStatus;
}

export interface Notification {
  id: UUID;
  event_id: UUID;
  channel: 'PUSH' | 'SMS' | 'EMAIL';
  template: string;
  state: 'QUEUED' | 'SENDING' | 'RETRY_WAIT' | 'UNKNOWN' | 'DELIVERED' | 'FAILED' | 'SUPPRESSED';
  attempts: number;
  last_failure: 'INVALID_CONTACT' | 'PROVIDER_ERROR' | 'RETRIES_EXHAUSTED' | 'TEMPLATE_UNAVAILABLE' | 'NO_VERIFIED_CONTACT' | null;
  created_at: string;
  revision: number;
}

export interface NotificationPage {
  items: Notification[];
  next_cursor: string | null;
  as_of: string;
}

export type ContactChannelCode = 'EMAIL' | 'PHONE';

export type ChannelStatus = 'UNVERIFIED' | 'VERIFIED';

export interface ContactChannelView {
  value: string | null;
  status: ChannelStatus;
  verified_at: string | null;
}

export interface Profile {
  id: UUID;
  status: 'PENDING_VERIFICATION' | 'ACTIVE' | 'BLOCKED';
  locale: 'de-DE' | 'en-GB';
  created_at: string;
  revision: number;
  contact: {
    email: ContactChannelView | null;
    phone: ContactChannelView | null;
  };
  favorites: Favorite[];
  preferred_stops: UUID[];
  notification_channels: NotificationChannel[];
}

export interface Favorite {
  label: string;
  stop_id: UUID;
}

export type NotificationChannel = 'PUSH' | 'EMAIL' | 'SMS';

export interface ProfileUpdate {
  locale?: string;
  contact?: {
    email?: string;
    phone?: string;
  };
  favorites?: Favorite[];
  preferred_stops?: UUID[];
  notification_channels?: NotificationChannel[];
}

export interface ContactVerificationStart {
  channel: ContactChannelCode;
}

export interface ContactVerification {
  channel: ContactChannelCode;
  expires_at: string;
}

export interface ContactVerificationConfirm {
  channel: ContactChannelCode;
  code: string;
}

export interface ContactChannelStatus {
  channel: ContactChannelCode;
  status: ChannelStatus;
  verified_at: string;
}

export type ConsentPurpose = 'OPERATIONAL_SAFETY' | 'MARKETING';

export interface ConsentState {
  purpose: ConsentPurpose;
  granted: boolean;
  changeable: boolean;
  recorded_at: string | null;
  text_version: string | null;
}

export interface ConsentRevision {
  id: UUID;
  purpose: ConsentPurpose;
  sequence: number;
  granted: boolean;
  recorded_at: string;
  text_version: string;
}

export interface PrivacySettings {
  current_text_version: string;
  consents: ConsentState[];
  history: ConsentRevision[];
}

export interface ConsentChange {
  purpose: ConsentPurpose;
  granted: boolean;
  text_version: string;
}

export interface MyTransportSupport {
  codes: ('BOARDING_HELP' | 'AUDIO_GUIDANCE' | 'LOW_FLOOR' | 'WHEELCHAIR_SPACE' | 'ASSISTANCE_ANIMAL' | 'ACCOMPANYING_PERSON')[];
}

export interface PassengerSupportNeeds {
  passenger_id: UUID;
  codes: ('BOARDING_HELP' | 'AUDIO_GUIDANCE' | 'LOW_FLOOR' | 'WHEELCHAIR_SPACE' | 'ASSISTANCE_ANIMAL' | 'ACCOMPANYING_PERSON')[];
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

export interface ProviderEvent {
  event_id: string;
  provider?: string;
  sequence?: number;
  type: string;
  created_at: string;
  data: Record<string, unknown>;
}

export interface ProviderEventReceipt {
  event_id: string;
  status: 'ACCEPTED' | 'ALREADY_RECEIVED';
}

export interface ReceiptUploadRequest {
  legal_entity_id: UUID;
  purpose: UploadPurpose;
  mime: 'image/jpeg' | 'image/png' | 'application/pdf';
  size_bytes: number;
  sha256: string;
}

export type UploadPurpose = 'RECEIPT' | 'DAMAGE_PHOTO';

export interface ReceiptUploadTicket {
  ticket_id: UUID;
  purpose: UploadPurpose;
  mime: string;
  size_bytes: number;
  sha256: string;
  max_bytes: number;
  expires_at: string;
  state: 'ISSUED';
  revision: number;
  upload_key: string;
  upload: null | {
    method: 'PUT';
    url: string;
    headers: Record<string, unknown>;
  };
}

export interface ReceiptDocument {
  document_id: UUID;
  ticket_id: UUID;
  purpose: UploadPurpose;
  state: 'QUARANTINED' | 'STORED' | 'QUARANTINE_REJECTED';
  mime: string;
  size_bytes: number;
  sha256: string;
  received_at: string;
  scanned_at: string | null;
  revision: number;
}

export interface EffectiveConfig {
  as_of: string;
  values: Record<string, unknown>;
  revision_ids: UUID[];
  schema_version?: string;
  items?: EffectiveParameter[];
}

export type ConfigLevel = 'GLOBAL' | 'TENANT' | 'REGION' | 'SERVICE' | 'VEHICLE';

export interface EffectiveParameter {
  parameter: string;
  unit: string;
  status: 'EFFECTIVE' | 'NO_VALUE';
  value: unknown;
  level: ConfigLevel | null;
  revision_id: UUID | null;
  revision: number | null;
  scope_id: UUID | null;
  effective_from: string | null;
  effective_to: string | null;
  schema_version: string;
  rule: PrecedenceRule;
  precedence: PrecedenceEntry[];
}

export interface PrecedenceRule {
  code: 'MOST_SPECIFIC_LEVEL_WINS' | 'SINGLE_LEVEL' | 'SAFETY_LIMIT_FALLBACK' | 'GLOBAL_DEFAULT' | 'NO_VALUE';
  description: string;
}

export interface PrecedenceEntry {
  level: ConfigLevel;
  outcome: 'APPLIED' | 'OVERRIDDEN' | 'SKIPPED_LIMIT' | 'SKIPPED_NOT_ALLOWED' | 'NOT_SET' | 'NOT_IN_CONTEXT';
  scope_id: UUID | null;
  value: unknown;
  revision_id: UUID | null;
  revision: number | null;
  violation: string | null;
  proposal: EffectiveProposal | null;
}

export interface EffectiveProposal {
  revision_id: UUID;
  state: ConfigRevisionState;
  value: unknown;
}

export type ConfigScopeKind = 'TENANT' | 'REGION' | 'DEPOT' | 'SERVICE' | 'VEHICLE';

export type ConfigRevisionState = 'DRAFT' | 'VALIDATED' | 'APPROVED' | 'PUBLISHED' | 'SUPERSEDED';

export interface ConfigChange {
  pointer: string;
  kind: 'ADDED' | 'CHANGED' | 'REMOVED';
  old_value?: unknown;
  new_value?: unknown;
}

export interface ConfigChangeLog {
  changed_by: string;
  changed_at: string;
  reason: string;
  effective_from: string;
  effective_to: string | null;
  predecessor_id: UUID | null;
  predecessor_revision: number | null;
  changes: ConfigChange[];
}

export interface ConfigParameter {
  name: string;
  type: 'integer' | 'number' | 'boolean' | 'string' | 'object';
  unit: string;
  minimum: number | null;
  maximum: number | null;
  default?: unknown;
  allowed_levels: ConfigScopeKind[];
  security_class: 'STANDARD' | 'SAFETY_INVARIANT';
  limit_source: 'KATALOG' | 'PLANUNGSANNAHME';
  description: string;
  schema: Record<string, unknown>;
  impact_required?: boolean;
  impact_hint?: string | null;
}

export interface ConfigParameterCatalog {
  schema_version: string;
  valid_from: string;
  items: ConfigParameter[];
}

export interface ConfigRevisionUpdate {
  values?: Record<string, unknown>;
  effective_from?: Timestamp;
  effective_to?: Timestamp | null;
  reason?: string;
}

export interface ConfigRevision {
  row_version?: number;
  id: UUID;
  scope_kind: 'TENANT' | 'REGION' | 'DEPOT' | 'SERVICE' | 'VEHICLE';
  scope_id: UUID;
  revision: number;
  state: 'DRAFT' | 'VALIDATED' | 'APPROVED' | 'PUBLISHED' | 'SUPERSEDED';
  schema_version: string;
  values: Record<string, unknown>;
  effective_from: string;
  effective_to: string | null;
  created_by: string;
  approved_by: string | null;
  reason?: string;
  created_at?: string;
  published_at?: string | null;
  validated_by?: string | null;
  validated_at?: string | null;
  approved_at?: string | null;
  published_by?: string | null;
  rolled_back_from?: UUID | null;
  change_log?: ConfigChangeLog;
}

export interface ConfigRevisionCreate {
  scope_kind: 'TENANT' | 'REGION' | 'DEPOT' | 'SERVICE' | 'VEHICLE';
  scope_id: UUID;
  schema_version: string;
  values: Record<string, unknown>;
  effective_from: Timestamp;
  effective_to?: Timestamp | null;
  reason?: string;
}

export interface ConfigValidationReport {
  revision_id: UUID;
  valid: boolean;
  state?: ConfigRevisionState;
  row_version?: number;
  audit_event_id?: UUID;
  violations: ({
    pointer: string;
    code: string;
    message: string;
  })[];
}

export interface ConfigRollbackRequest {
  effective_from: Timestamp;
  reason: string;
  target_revision_id?: UUID;
}

export interface ConfigImpactReport {
  id: UUID;
  revision_id: UUID;
  revision_row_version: number;
  values_hash: string;
  generated_at: string;
  created_by: string;
  highest_severity: ImpactSeverity;
  conflict_count: number;
  affected: ({
    kind: string;
    severity: ImpactSeverity;
    count: number;
    object_refs: ({
      kind: string;
      id: UUID;
    })[];
  })[];
}

export type ImpactSeverity = 'NONE' | 'INFO' | 'WARNING' | 'BLOCKING';

export interface ConfigRevisionPage {
  items: ConfigRevision[];
  next_cursor: string | null;
  as_of: string;
}

export type OfferModel = 'AREA_ON_DEMAND' | 'LINE_ON_DEMAND' | 'STATION_FEEDER';

export interface ServiceOfferModelRules {
  model: OfferModel;
  label: string;
  required: string[];
  forbidden: string[];
  optional: string[];
}

export interface ServiceOfferModelList {
  items: ServiceOfferModelRules[];
}

export interface ServiceConfiguration {
  service_id: UUID;
  as_of: string;
  offer_model: OfferModel | null;
  model_rules: ServiceOfferModelRules | null;
  parameters: Record<string, unknown>;
  revision_id: UUID | null;
  effective_from: string | null;
}

export interface Service {
  id: UUID;
  operator_id: UUID;
  name: string;
  timezone: TimeZone;
  region_id?: UUID | null;
  config_revision_id: UUID;
  revision: number;
}

export interface ServiceUpdate {
  name: string;
  timezone: TimeZone;
  region_id?: UUID | null;
}

export interface ServiceCreate {
  operator_id: UUID;
  name: string;
  timezone: TimeZone;
  region_id?: UUID | null;
}

export interface ServicePage {
  items: Service[];
  next_cursor: string | null;
  as_of: string;
}

export interface HoursWindow {
  from: string;
  to: string;
}

export interface WeeklyHours {
  weekdays: ('MON' | 'TUE' | 'WED' | 'THU' | 'FRI' | 'SAT' | 'SUN')[];
  from: string;
  to: string;
}

export interface ExceptionDay {
  date: string;
  windows: HoursWindow[];
  note?: string;
}

export interface ServiceOperatingHoursInput {
  traffic_product: string;
  weekly_hours: WeeklyHours[];
  exceptions?: ExceptionDay[];
  valid_from: Timestamp;
  valid_to?: Timestamp | null;
  reason: string;
}

export interface ServiceOperatingHours {
  service_id: UUID;
  revision: number;
  traffic_product: string | null;
  timezone: string | null;
  weekly_hours: WeeklyHours[] | null;
  exceptions: ExceptionDay[] | null;
  valid_from: Timestamp | null;
  valid_to: Timestamp | null;
  reason?: string | null;
  created_by?: string | null;
  created_at?: Timestamp | null;
}

export interface ServiceOperatingHoursRevisionPage {
  items: ServiceOperatingHours[];
  next_before_revision: number | null;
  as_of: string;
}

export interface ServiceAvailability {
  service_id: UUID;
  at: string;
  in_operation: boolean;
  next_opening: string | null;
  timezone: TimeZone;
  traffic_product: string | null;
}

export interface AccountLockCreate {
  subject: UUID;
  reason: string;
}

export interface AccountAccess {
  subject: UUID;
  status: 'ACTIVE' | 'LOCKED';
  status_changed_at: string;
  revision: number;
  idp_synced?: boolean;
}

export interface AccountRecoveryCreate {
  subject: UUID;
}

export interface AccountRecovery {
  subject: UUID;
  started_at: string;
}

export type StaffShiftState = 'PLANNED' | 'STARTED' | 'ENDED' | 'CANCELLED';

export interface ShiftOverlapExceptionInput {
  approved_by?: string;
  reason?: string;
}

export interface ShiftOverlapException {
  approved_by: string;
  reason: string;
  approved_at: string;
}

export interface StaffShiftInput {
  driver_id: UUID;
  vehicle_id: UUID;
  starts_at: string;
  ends_at: string;
  timezone: string;
  withdrawn?: boolean;
  overlap_exception?: ShiftOverlapExceptionInput;
}

export interface StaffShift {
  id: UUID;
  driver_id: UUID;
  vehicle_id: UUID;
  starts_at: string;
  ends_at: string;
  duration_minutes: number;
  timezone: string;
  state: string;
  roster_revision: number;
  revision: number;
  overlap_exception: ShiftOverlapException | null;
  started_at: string | null;
  ended_at: string | null;
}

export interface StaffShiftPage {
  items: StaffShift[];
  next_cursor: string | null;
  as_of: string;
}

export interface TimeEventCreate {
  event_id: UUID;
  kind: TimeEventKind;
  occurred_at: string;
  device_recorded_at: string;
  channel: 'APP' | 'WEB';
  shift_id?: UUID | null;
}

export interface TimeEventBackfillCreate {
  event_id: UUID;
  person_ref: string;
  kind: TimeEventKind;
  occurred_at: string;
  shift_id?: UUID | null;
  reason: string;
}

export type TimeEventKind = 'WORK_START' | 'WORK_END' | 'BREAK_START' | 'BREAK_END';

export interface TimeEvent {
  event_id: UUID;
  person_ref: string;
  kind: TimeEventKind;
  occurred_at: string;
  device_recorded_at: string | null;
  received_at: string;
  source: 'APP' | 'WEB' | 'BACKFILL';
  recorded_by: string;
  shift_id: UUID | null;
  backfill_reason: string | null;
}

export interface TimeEventPage {
  items: TimeEvent[];
  next_cursor: string | null;
  as_of: string;
}

export interface TimeInterval {
  start: string | null;
  end: string | null;
  breaks: ({
    start: string;
    end: string | null;
  })[];
  net_seconds: number | null;
  flags: ('MISSING_END' | 'END_WITHOUT_START' | 'BREAK_NOT_ENDED')[];
  event_ids: UUID[];
}

export interface TimeIntervalList {
  person_ref: string;
  from: string;
  to: string;
  intervals: TimeInterval[];
  anomalies: ({
    event_id: UUID;
    code: 'BREAK_OUTSIDE_WORK' | 'BREAK_END_WITHOUT_START' | 'BREAK_ALREADY_STARTED';
  })[];
}

export interface WebhookSubscription {
  id: UUID;
  url: string;
  event_types: string[];
  active: boolean;
  secret_ref: string;
  revision: number;
}

export interface WebhookSubscriptionCreate {
  url: string;
  event_types: string[];
  secret_ref?: string;
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
  recordDriverPosition: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; traceparent?: string };
    body: DriverPositionBatch;
    response: DriverPositionResult;
  };
  getDriverRun: {
    path: { shift_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: DriverRun;
  };
  acknowledgeDriverRunRevision: {
    path: { shift_id: UUID };
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; 'If-Match': string; traceparent?: string };
    body: DriverRunAck;
    response: DriverRun;
  };
  registerDriverDevice: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; traceparent?: string };
    body: DriverDeviceRegister;
    response: DriverDevice;
  };
  revokeDriverDevice: {
    path: { device_id: UUID };
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; 'If-Match': string; traceparent?: string };
    body: undefined;
    response: undefined;
  };
  listDriverShifts: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: DriverShiftList;
  };
  startDriverShift: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; traceparent?: string };
    body: DriverShiftStart;
    response: DriverShiftStartResult;
  };
  endDriverShift: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; traceparent?: string };
    body: DriverShiftEnd;
    response: DriverShift;
  };
  listAbsences: {
    path: Record<string, never>;
    query: { person_ref?: string; from?: string; to?: string; limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: AvailabilityRestrictionPage;
  };
  createAbsence: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: AbsenceCreate;
    response: Absence;
  };
  getAbsence: {
    path: { absence_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: Absence;
  };
  withdrawAbsence: {
    path: { absence_id: UUID };
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; 'If-Match': string; traceparent?: string };
    body: undefined;
    response: Absence;
  };
  createRideOffer: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: RideOfferRequest;
    response: RideOffer;
  };
  getRideOffer: {
    path: { ride_offer_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: RideOffer;
  };
  holdRideOffer: {
    path: { ride_offer_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: RideHoldRequest;
    response: RideHold;
  };
  releaseHold: {
    path: { ride_offer_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: undefined;
    response: RideHold;
  };
  listBookings: {
    path: Record<string, never>;
    query: { limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: RideBookingPage;
  };
  confirmBooking: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: RideConfirmRequest;
    response: RideBooking;
  };
  getBooking: {
    path: { booking_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: RideBooking;
  };
  cancelBooking: {
    path: { booking_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: RideCancelRequest;
    response: RideBooking;
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
  createRetentionRule: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: RetentionRuleCreate;
    response: RetentionRule;
  };
  updateRetentionRule: {
    path: { rule_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: RetentionRuleUpdate;
    response: RetentionRule;
  };
  approveRetentionRule: {
    path: { rule_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: RetentionRuleApprovalRequest;
    response: RetentionRule;
  };
  listErasureJobs: {
    path: Record<string, never>;
    query: { limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: ErasureJobPage;
  };
  startErasureJob: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: ErasureJobStart;
    response: ErasureJob;
  };
  listErasureJournal: {
    path: Record<string, never>;
    query: { job_id?: UUID; limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: ErasureJournalPage;
  };
  listRunRevisions: {
    path: { run_id: UUID };
    query: { limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: RunRevisionPage;
  };
  createRunRevision: {
    path: { run_id: UUID };
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; 'If-Match': string; traceparent?: string };
    body: RunRevisionCreate;
    response: RunRevision;
  };
  createDriverRelease: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: DriverReleaseCreate;
    response: DriverRelease;
  };
  revokeDriverRelease: {
    path: { release_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: undefined;
    response: undefined;
  };
  getDriverReleaseProjection: {
    path: { release_id: UUID };
    query: { service_id: UUID };
    headers: { traceparent?: string };
    body: undefined;
    response: DriverReleaseProjection;
  };
  listDrivers: {
    path: Record<string, never>;
    query: { status?: DriverStatus; limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: DriverPage;
  };
  createDriver: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; traceparent?: string };
    body: DriverCreate;
    response: Driver;
  };
  getDriver: {
    path: { driver_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: Driver;
  };
  updateDriver: {
    path: { driver_id: UUID };
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; 'If-Match': string; traceparent?: string };
    body: DriverUpdate;
    response: Driver;
  };
  listDriverQualifications: {
    path: { driver_id: UUID };
    query: { at?: string; only_valid?: boolean };
    headers: { traceparent?: string };
    body: undefined;
    response: QualificationPage;
  };
  createDriverQualification: {
    path: { driver_id: UUID };
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; 'If-Match': string; traceparent?: string };
    body: QualificationInput;
    response: QualificationRevision;
  };
  listVehicleRoles: {
    path: Record<string, never>;
    query: { vehicle_id?: UUID; as_of?: string; limit?: number; cursor?: string };
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
  listOperatorVehicles: {
    path: Record<string, never>;
    query: { state?: VehicleOperationalState; depot_id?: UUID; limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: OperatorVehiclePage;
  };
  createVehicleBlock: {
    path: { vehicle_id: UUID };
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; 'If-Match': string; traceparent?: string };
    body: VehicleBlockCreate;
    response: VehicleBlock;
  };
  liftVehicleBlock: {
    path: { vehicle_id: UUID; block_id: UUID };
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; 'If-Match': string; traceparent?: string };
    body: VehicleBlockLift;
    response: VehicleBlock;
  };
  listChecklistTemplates: {
    path: Record<string, never>;
    query: { vehicle_class?: string; service_mode?: string; limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: ChecklistTemplatePage;
  };
  createInspection: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; traceparent?: string };
    body: InspectionCreate;
    response: Inspection;
  };
  getInspection: {
    path: { inspection_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: Inspection;
  };
  saveInspectionDraft: {
    path: { inspection_id: UUID };
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; 'If-Match': string; traceparent?: string };
    body: InspectionDraft;
    response: Inspection;
  };
  submitInspection: {
    path: { inspection_id: UUID };
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; 'If-Match': string; traceparent?: string };
    body: InspectionSubmit;
    response: Inspection;
  };
  listDefects: {
    path: Record<string, never>;
    query: { vehicle_id?: UUID; status?: 'NEW' | 'REVIEWED' | 'IN_PROGRESS' | 'REPAIR' | 'CLOSED' | 'DISMISSED'; severity?: DefectSeverity; limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: DefectPage;
  };
  createDefect: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; traceparent?: string };
    body: DefectCreate;
    response: Defect;
  };
  getDefect: {
    path: { defect_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: Defect;
  };
  triageDefect: {
    path: { defect_id: UUID };
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; 'If-Match': string; traceparent?: string };
    body: DefectTriage;
    response: Defect;
  };
  listRepairOrders: {
    path: Record<string, never>;
    query: { defect_id?: UUID; vehicle_id?: UUID; limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: RepairOrderPage;
  };
  createRepairOrder: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; traceparent?: string };
    body: RepairOrderCreate;
    response: RepairOrder;
  };
  createVehicleRelease: {
    path: { vehicle_id: UUID };
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; 'If-Match': string; traceparent?: string };
    body: VehicleReleaseCreate;
    response: VehicleRelease;
  };
  listOperatorIncidents: {
    path: Record<string, never>;
    query: { status?: 'OPEN' | 'REPLACEMENT_SEARCH' | 'RESOLVED' | 'CLOSED_NO_REPLACEMENT'; vehicle_id?: UUID; limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: IncidentPage;
  };
  getOperatorIncident: {
    path: { incident_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: Incident;
  };
  transitionOperatorIncident: {
    path: { incident_id: UUID };
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; 'If-Match': string; traceparent?: string };
    body: IncidentTransition;
    response: Incident;
  };
  listVehicleMasters: {
    path: Record<string, never>;
    query: { active?: boolean; limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: VehicleMasterPage;
  };
  createVehicleMaster: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; traceparent?: string };
    body: VehicleMasterWrite;
    response: VehicleMaster;
  };
  getVehicleMaster: {
    path: { vehicle_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: VehicleMaster;
  };
  updateVehicleMaster: {
    path: { vehicle_id: UUID };
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; 'If-Match': string; traceparent?: string };
    body: VehicleMasterWrite;
    response: VehicleMaster;
  };
  getVehicleCapacity: {
    path: { vehicle_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: CapacityConfiguration;
  };
  updateVehicleCapacity: {
    path: { vehicle_id: UUID };
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; 'If-Match': string; traceparent?: string };
    body: CapacityPatch;
    response: CapacityConfiguration;
  };
  listVehicleServiceAssignments: {
    path: { vehicle_id: UUID };
    query: { as_of?: string; limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: VehicleServiceAssignmentPage;
  };
  createVehicleServiceAssignment: {
    path: { vehicle_id: UUID };
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; traceparent?: string };
    body: VehicleServiceAssignmentCreate;
    response: VehicleServiceAssignment;
  };
  endVehicleServiceAssignment: {
    path: { vehicle_id: UUID; assignment_id: UUID };
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; 'If-Match': string; traceparent?: string };
    body: VehicleServiceAssignmentEnd;
    response: VehicleServiceAssignment;
  };
  listServiceAreas: {
    path: Record<string, never>;
    query: { limit?: number; cursor?: string; service_id?: UUID };
    headers: { traceparent?: string };
    body: undefined;
    response: GeoServiceAreaPage;
  };
  createServiceArea: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: GeoServiceAreaCreate;
    response: GeoServiceArea;
  };
  getServiceArea: {
    path: { area_id: UUID };
    query: { revision?: number };
    headers: { traceparent?: string };
    body: undefined;
    response: GeoServiceArea;
  };
  updateServiceArea: {
    path: { area_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: GeoServiceAreaUpdate;
    response: GeoServiceArea;
  };
  listStops: {
    path: Record<string, never>;
    query: { limit?: number; cursor?: string; service_id?: UUID; at?: Timestamp; near_latitude?: number; near_longitude?: number; near_radius_m?: number; active?: boolean };
    headers: { traceparent?: string };
    body: undefined;
    response: GeoStopPage;
  };
  createStop: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: GeoStopCreate;
    response: GeoStop;
  };
  getStop: {
    path: { stop_id: UUID };
    query: { revision?: number };
    headers: { traceparent?: string };
    body: undefined;
    response: GeoStop;
  };
  updateStop: {
    path: { stop_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: GeoStopUpdate;
    response: GeoStop;
  };
  getServiceRelationRule: {
    path: { service_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: GeoRelationRule;
  };
  putServiceRelationRule: {
    path: { service_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: GeoRelationRuleInput;
    response: GeoRelationRule;
  };
  checkServiceRelation: {
    path: { service_id: UUID };
    query: { origin_latitude: number; origin_longitude: number; destination_latitude: number; destination_longitude: number; destination_stop_id?: UUID; at?: Timestamp };
    headers: { traceparent?: string };
    body: undefined;
    response: GeoRelationCheck;
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
  updateRegion: {
    path: { region_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: RegionUpdate;
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
  updateDepot: {
    path: { depot_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: DepotUpdate;
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
  updateCostCenter: {
    path: { cost_center_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: CostCenterUpdate;
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
  listOrganizations: {
    path: Record<string, never>;
    query: { limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: OrganizationPage;
  };
  createOrganization: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: OrganizationCreate;
    response: Organization;
  };
  getOrganization: {
    path: { organization_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: Organization;
  };
  updateOrganization: {
    path: { organization_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: OrganizationUpdate;
    response: Organization;
  };
  createTenant: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: TenantCreate;
    response: TenantCreated;
  };
  changeTenantStatus: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: TenantStatusChange;
    response: TenantStatusChanged;
  };
  listNotifications: {
    path: Record<string, never>;
    query: { limit?: number; cursor?: string; state?: 'QUEUED' | 'SENDING' | 'RETRY_WAIT' | 'UNKNOWN' | 'DELIVERED' | 'FAILED' | 'SUPPRESSED' };
    headers: { traceparent?: string };
    body: undefined;
    response: NotificationPage;
  };
  getNotification: {
    path: { notification_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: Notification;
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
  getPassengerSupportNeeds: {
    path: { passenger_id: UUID };
    query: Record<string, never>;
    headers: { 'X-Access-Purpose': 'BOOKING_ASSISTANCE' | 'SUPPORT_CASE' | 'TRANSPORT_PLANNING'; traceparent?: string };
    body: undefined;
    response: PassengerSupportNeeds;
  };
  getPassenger: {
    path: { passenger_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: Passenger;
  };
  getMyProfile: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: Profile;
  };
  updateMyProfile: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: ProfileUpdate;
    response: Profile;
  };
  startContactVerification: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: ContactVerificationStart;
    response: ContactVerification;
  };
  confirmContactVerification: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: ContactVerificationConfirm;
    response: ContactChannelStatus;
  };
  getMyPrivacySettings: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: PrivacySettings;
  };
  recordMyConsent: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: ConsentChange;
    response: ConsentRevision;
  };
  getMyTransportSupport: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: MyTransportSupport;
  };
  replaceMyTransportSupport: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: MyTransportSupport;
    response: undefined;
  };
  receiveProviderEvent: {
    path: { provider: string };
    query: Record<string, never>;
    headers: { 'X-Provider-Timestamp': string; traceparent?: string };
    body: ProviderEvent;
    response: ProviderEventReceipt;
  };
  requestReceiptUpload: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: ReceiptUploadRequest;
    response: ReceiptUploadTicket;
  };
  completeReceiptUpload: {
    path: { ticket_id: UUID };
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; 'If-Match': string; traceparent?: string };
    body: undefined;
    response: ReceiptDocument;
  };
  getReceiptDocument: {
    path: { document_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: ReceiptDocument;
  };
  getEffectiveConfig: {
    path: Record<string, never>;
    query: { at?: string; parameter?: string; region_id?: UUID; service_id?: UUID; vehicle_id?: UUID };
    headers: { traceparent?: string };
    body: undefined;
    response: EffectiveConfig;
  };
  listConfigParameters: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: ConfigParameterCatalog;
  };
  listConfigRevisions: {
    path: Record<string, never>;
    query: { limit?: number; cursor?: string; scope_kind?: ConfigScopeKind; scope_id?: UUID; state?: ConfigRevisionState; as_of?: string };
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
  updateConfigRevision: {
    path: { revision_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: ConfigRevisionUpdate;
    response: ConfigRevision;
  };
  validateConfigRevision: {
    path: { revision_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: undefined;
    response: ConfigValidationReport;
  };
  approveConfigRevision: {
    path: { revision_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: undefined;
    response: ConfigRevision;
  };
  rollbackConfigRevision: {
    path: { revision_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: ConfigRollbackRequest;
    response: ConfigRevision;
  };
  getConfigImpactReport: {
    path: { impact_report_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: ConfigImpactReport;
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
  updateService: {
    path: { service_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: ServiceUpdate;
    response: Service;
  };
  getServiceConfiguration: {
    path: { service_id: UUID };
    query: { at?: Timestamp };
    headers: { traceparent?: string };
    body: undefined;
    response: ServiceConfiguration;
  };
  listServiceOfferModels: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: ServiceOfferModelList;
  };
  getServiceOperatingHours: {
    path: { service_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: ServiceOperatingHours;
  };
  putServiceOperatingHours: {
    path: { service_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: ServiceOperatingHoursInput;
    response: ServiceOperatingHours;
  };
  listServiceOperatingHoursRevisions: {
    path: { service_id: UUID };
    query: { limit?: number; before_revision?: number };
    headers: { traceparent?: string };
    body: undefined;
    response: ServiceOperatingHoursRevisionPage;
  };
  getServiceAvailability: {
    path: { service_id: UUID };
    query: { at?: Timestamp };
    headers: { traceparent?: string };
    body: undefined;
    response: ServiceAvailability;
  };
  listMySessions: {
    path: Record<string, never>;
    query: { limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: SessionPage;
  };
  revokeAllMySessions: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: undefined;
    response: undefined;
  };
  revokeMySession: {
    path: { session_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: undefined;
    response: undefined;
  };
  lockAccount: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: AccountLockCreate;
    response: AccountAccess;
  };
  unlockAccount: {
    path: { subject: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: undefined;
    response: undefined;
  };
  startAccountRecovery: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: AccountRecoveryCreate;
    response: AccountRecovery;
  };
  listStaffShifts: {
    path: Record<string, never>;
    query: { driver_id?: UUID; vehicle_id?: UUID; from?: string; to?: string; limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: StaffShiftPage;
  };
  createStaffShift: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; traceparent?: string };
    body: StaffShiftInput;
    response: StaffShift;
  };
  getStaffShift: {
    path: { shift_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: StaffShift;
  };
  updateStaffShift: {
    path: { shift_id: UUID };
    query: Record<string, never>;
    headers: { 'Idempotency-Key': string; 'If-Match': string; traceparent?: string };
    body: StaffShiftInput;
    response: StaffShift;
  };
  listTimeEvents: {
    path: Record<string, never>;
    query: { person_ref?: string; from?: string; to?: string; shift_id?: string; limit?: number; cursor?: string };
    headers: { traceparent?: string };
    body: undefined;
    response: TimeEventPage;
  };
  recordTimeEvent: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: TimeEventCreate;
    response: TimeEvent;
  };
  createTimeEventBackfill: {
    path: Record<string, never>;
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string };
    body: TimeEventBackfillCreate;
    response: TimeEvent;
  };
  listTimeIntervals: {
    path: Record<string, never>;
    query: { person_ref?: string; from: string; to: string };
    headers: { traceparent?: string };
    body: undefined;
    response: TimeIntervalList;
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
  getWebhookSubscription: {
    path: { subscription_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string };
    body: undefined;
    response: WebhookSubscription;
  };
  deactivateWebhookSubscription: {
    path: { subscription_id: UUID };
    query: Record<string, never>;
    headers: { traceparent?: string; 'Idempotency-Key': string; 'If-Match': string };
    body: undefined;
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
  recordDriverPosition: { method: 'POST', path: '/v1/driver/positions', pathParams: [], query: [], headers: ['Idempotency-Key', 'traceparent'], hasBody: true, scopes: ['driver:positions'], statuses: [200, 400, 401, 403, 404, 409, 422, 429, 503] },
  getDriverRun: { method: 'GET', path: '/v1/driver/shifts/{shift_id}/run', pathParams: ['shift_id'], query: [], headers: ['traceparent'], hasBody: false, scopes: ['driver:run'], statuses: [200, 400, 401, 403, 404, 422, 429, 503, 304] },
  acknowledgeDriverRunRevision: { method: 'POST', path: '/v1/driver/shifts/{shift_id}/run/acknowledgements', pathParams: ['shift_id'], query: [], headers: ['Idempotency-Key', 'If-Match', 'traceparent'], hasBody: true, scopes: ['driver:run'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  registerDriverDevice: { method: 'POST', path: '/v1/driver/devices', pathParams: [], query: [], headers: ['Idempotency-Key', 'traceparent'], hasBody: true, scopes: ['driver:devices'], statuses: [201, 400, 401, 403, 409, 422, 429, 503] },
  revokeDriverDevice: { method: 'DELETE', path: '/v1/driver/devices/{device_id}', pathParams: ['device_id'], query: [], headers: ['Idempotency-Key', 'If-Match', 'traceparent'], hasBody: false, scopes: ['driver:devices'], statuses: [204, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  listDriverShifts: { method: 'GET', path: '/v1/driver/shifts', pathParams: [], query: [], headers: ['traceparent'], hasBody: false, scopes: ['driver:run'], statuses: [200, 400, 401, 403, 429, 503] },
  startDriverShift: { method: 'POST', path: '/v1/driver/shift-starts', pathParams: [], query: [], headers: ['Idempotency-Key', 'traceparent'], hasBody: true, scopes: ['driver:run'], statuses: [200, 400, 401, 403, 404, 409, 422, 429, 503] },
  endDriverShift: { method: 'POST', path: '/v1/driver/shift-ends', pathParams: [], query: [], headers: ['Idempotency-Key', 'traceparent'], hasBody: true, scopes: ['driver:run'], statuses: [200, 400, 401, 403, 404, 409, 422, 429, 503] },
  listAbsences: { method: 'GET', path: '/v1/absences', pathParams: [], query: ['person_ref', 'from', 'to', 'limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['absences:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  createAbsence: { method: 'POST', path: '/v1/absences', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['absences:write'], statuses: [201, 400, 401, 403, 409, 422, 429, 503] },
  getAbsence: { method: 'GET', path: '/v1/absences/{absence_id}', pathParams: ['absence_id'], query: [], headers: ['traceparent'], hasBody: false, scopes: ['absences:detail'], statuses: [200, 400, 401, 403, 404, 429, 503] },
  withdrawAbsence: { method: 'POST', path: '/v1/absences/{absence_id}/withdraw', pathParams: ['absence_id'], query: [], headers: ['Idempotency-Key', 'If-Match', 'traceparent'], hasBody: false, scopes: ['absences:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 428, 429, 503] },
  createRideOffer: { method: 'POST', path: '/v1/ride-offers', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['rides:write'], statuses: [201, 400, 401, 403, 409, 422, 429, 503] },
  getRideOffer: { method: 'GET', path: '/v1/ride-offers/{ride_offer_id}', pathParams: ['ride_offer_id'], query: [], headers: ['traceparent'], hasBody: false, scopes: ['rides:read'], statuses: [200, 400, 401, 403, 404, 429, 503] },
  holdRideOffer: { method: 'POST', path: '/v1/ride-offers/{ride_offer_id}/hold', pathParams: ['ride_offer_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['rides:write'], statuses: [201, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  releaseHold: { method: 'POST', path: '/v1/ride-offers/{ride_offer_id}/hold/release', pathParams: ['ride_offer_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: false, scopes: ['rides:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 428, 429, 503] },
  listBookings: { method: 'GET', path: '/v1/bookings', pathParams: [], query: ['limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['rides:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  confirmBooking: { method: 'POST', path: '/v1/bookings', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['rides:write'], statuses: [201, 400, 401, 403, 404, 409, 422, 429, 503] },
  getBooking: { method: 'GET', path: '/v1/bookings/{booking_id}', pathParams: ['booking_id'], query: [], headers: ['traceparent'], hasBody: false, scopes: ['rides:read'], statuses: [200, 400, 401, 403, 404, 429, 503] },
  cancelBooking: { method: 'POST', path: '/v1/bookings/{booking_id}/cancel', pathParams: ['booking_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['rides:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  listAuditEvents: { method: 'GET', path: '/v1/audit-events', pathParams: [], query: ['limit', 'cursor', 'object_ref', 'actor_ref', 'action', 'from', 'to', 'purpose'], headers: ['traceparent'], hasBody: false, scopes: ['audit:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  getCompanyProfile: { method: 'GET', path: '/v1/company-profile', pathParams: [], query: [], headers: ['traceparent'], hasBody: false, scopes: ['company:read'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
  updateCompanyProfile: { method: 'PUT', path: '/v1/company-profile', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['company:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  listRetentionRules: { method: 'GET', path: '/v1/retention-rules', pathParams: [], query: ['limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['retention:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  createRetentionRule: { method: 'POST', path: '/v1/retention-rules', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['retention:write'], statuses: [201, 400, 401, 403, 409, 422, 429, 503] },
  updateRetentionRule: { method: 'PUT', path: '/v1/retention-rules/{rule_id}', pathParams: ['rule_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['retention:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  approveRetentionRule: { method: 'POST', path: '/v1/retention-rules/{rule_id}/approvals', pathParams: ['rule_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['retention:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  listErasureJobs: { method: 'GET', path: '/v1/erasure-jobs', pathParams: [], query: ['limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['retention:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  startErasureJob: { method: 'POST', path: '/v1/erasure-jobs', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['retention:write'], statuses: [201, 400, 401, 403, 404, 409, 422, 429, 503] },
  listErasureJournal: { method: 'GET', path: '/v1/erasure-journal', pathParams: [], query: ['job_id', 'limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['retention:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  listRunRevisions: { method: 'GET', path: '/v1/runs/{run_id}/revisions', pathParams: ['run_id'], query: ['limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['operator:read'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
  createRunRevision: { method: 'POST', path: '/v1/runs/{run_id}/revisions', pathParams: ['run_id'], query: [], headers: ['Idempotency-Key', 'If-Match', 'traceparent'], hasBody: true, scopes: ['dispatch:write'], statuses: [201, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  createDriverRelease: { method: 'POST', path: '/v1/driver-releases', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['driver-releases:write'], statuses: [201, 400, 401, 403, 404, 409, 422, 429, 503] },
  revokeDriverRelease: { method: 'DELETE', path: '/v1/driver-releases/{release_id}', pathParams: ['release_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: false, scopes: ['driver-releases:write'], statuses: [204, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  getDriverReleaseProjection: { method: 'GET', path: '/v1/driver-releases/{release_id}/projection', pathParams: ['release_id'], query: ['service_id'], headers: ['traceparent'], hasBody: false, scopes: ['driver-releases:read'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
  listDrivers: { method: 'GET', path: '/v1/drivers', pathParams: [], query: ['status', 'limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['drivers:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  createDriver: { method: 'POST', path: '/v1/drivers', pathParams: [], query: [], headers: ['Idempotency-Key', 'traceparent'], hasBody: true, scopes: ['drivers:write'], statuses: [201, 400, 401, 403, 409, 422, 429, 503] },
  getDriver: { method: 'GET', path: '/v1/drivers/{driver_id}', pathParams: ['driver_id'], query: [], headers: ['traceparent'], hasBody: false, scopes: ['drivers:read'], statuses: [200, 400, 401, 403, 404, 429, 503] },
  updateDriver: { method: 'PUT', path: '/v1/drivers/{driver_id}', pathParams: ['driver_id'], query: [], headers: ['Idempotency-Key', 'If-Match', 'traceparent'], hasBody: true, scopes: ['drivers:write'], statuses: [200, 400, 401, 403, 404, 412, 422, 428, 429, 503] },
  listDriverQualifications: { method: 'GET', path: '/v1/drivers/{driver_id}/qualifications', pathParams: ['driver_id'], query: ['at', 'only_valid'], headers: ['traceparent'], hasBody: false, scopes: ['drivers:read'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
  createDriverQualification: { method: 'POST', path: '/v1/drivers/{driver_id}/qualifications', pathParams: ['driver_id'], query: [], headers: ['Idempotency-Key', 'If-Match', 'traceparent'], hasBody: true, scopes: ['drivers:write'], statuses: [201, 400, 401, 403, 404, 412, 422, 428, 429, 503] },
  listVehicleRoles: { method: 'GET', path: '/v1/vehicle-roles', pathParams: [], query: ['vehicle_id', 'as_of', 'limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['fleet:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  createVehicleRole: { method: 'POST', path: '/v1/vehicle-roles', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['fleet:write'], statuses: [201, 400, 401, 403, 404, 409, 422, 429, 503] },
  listDriverClearances: { method: 'GET', path: '/v1/driver-clearances', pathParams: [], query: ['limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['fleet:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  createDriverClearance: { method: 'POST', path: '/v1/driver-clearances', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['fleet:write'], statuses: [201, 400, 401, 403, 409, 422, 429, 503] },
  listOperatorVehicles: { method: 'GET', path: '/v1/operator/vehicles', pathParams: [], query: ['state', 'depot_id', 'limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['operator:read'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
  createVehicleBlock: { method: 'POST', path: '/v1/operator/vehicles/{vehicle_id}/blocks', pathParams: ['vehicle_id'], query: [], headers: ['Idempotency-Key', 'If-Match', 'traceparent'], hasBody: true, scopes: ['operator:write'], statuses: [201, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  liftVehicleBlock: { method: 'POST', path: '/v1/operator/vehicles/{vehicle_id}/blocks/{block_id}/lift', pathParams: ['vehicle_id', 'block_id'], query: [], headers: ['Idempotency-Key', 'If-Match', 'traceparent'], hasBody: true, scopes: ['operator:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  listChecklistTemplates: { method: 'GET', path: '/v1/checklist-templates', pathParams: [], query: ['vehicle_class', 'service_mode', 'limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['inspections:read'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
  createInspection: { method: 'POST', path: '/v1/inspections', pathParams: [], query: [], headers: ['Idempotency-Key', 'traceparent'], hasBody: true, scopes: ['inspections:write'], statuses: [201, 400, 401, 403, 404, 409, 422, 429, 503] },
  getInspection: { method: 'GET', path: '/v1/inspections/{inspection_id}', pathParams: ['inspection_id'], query: [], headers: ['traceparent'], hasBody: false, scopes: ['inspections:read'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
  saveInspectionDraft: { method: 'PUT', path: '/v1/inspections/{inspection_id}', pathParams: ['inspection_id'], query: [], headers: ['Idempotency-Key', 'If-Match', 'traceparent'], hasBody: true, scopes: ['inspections:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  submitInspection: { method: 'POST', path: '/v1/inspections/{inspection_id}/submit', pathParams: ['inspection_id'], query: [], headers: ['Idempotency-Key', 'If-Match', 'traceparent'], hasBody: true, scopes: ['inspections:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  listDefects: { method: 'GET', path: '/v1/defects', pathParams: [], query: ['vehicle_id', 'status', 'severity', 'limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['defects:read'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
  createDefect: { method: 'POST', path: '/v1/defects', pathParams: [], query: [], headers: ['Idempotency-Key', 'traceparent'], hasBody: true, scopes: ['defects:write'], statuses: [201, 400, 401, 403, 404, 409, 422, 429, 503] },
  getDefect: { method: 'GET', path: '/v1/defects/{defect_id}', pathParams: ['defect_id'], query: [], headers: ['traceparent'], hasBody: false, scopes: ['defects:read'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
  triageDefect: { method: 'POST', path: '/v1/defects/{defect_id}/triage', pathParams: ['defect_id'], query: [], headers: ['Idempotency-Key', 'If-Match', 'traceparent'], hasBody: true, scopes: ['defects:triage'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  listRepairOrders: { method: 'GET', path: '/v1/repair-orders', pathParams: [], query: ['defect_id', 'vehicle_id', 'limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['repair:read'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
  createRepairOrder: { method: 'POST', path: '/v1/repair-orders', pathParams: [], query: [], headers: ['Idempotency-Key', 'traceparent'], hasBody: true, scopes: ['repair:write'], statuses: [201, 400, 401, 403, 404, 409, 422, 429, 503] },
  createVehicleRelease: { method: 'POST', path: '/v1/vehicles/{vehicle_id}/release', pathParams: ['vehicle_id'], query: [], headers: ['Idempotency-Key', 'If-Match', 'traceparent'], hasBody: true, scopes: ['fleet:release'], statuses: [201, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  listOperatorIncidents: { method: 'GET', path: '/v1/operator/incidents', pathParams: [], query: ['status', 'vehicle_id', 'limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['operator:read'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
  getOperatorIncident: { method: 'GET', path: '/v1/operator/incidents/{incident_id}', pathParams: ['incident_id'], query: [], headers: ['traceparent'], hasBody: false, scopes: ['operator:read'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
  transitionOperatorIncident: { method: 'POST', path: '/v1/operator/incidents/{incident_id}/transitions', pathParams: ['incident_id'], query: [], headers: ['Idempotency-Key', 'If-Match', 'traceparent'], hasBody: true, scopes: ['operator:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  listVehicleMasters: { method: 'GET', path: '/v1/operator/vehicle-master', pathParams: [], query: ['active', 'limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['fleet:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  createVehicleMaster: { method: 'POST', path: '/v1/operator/vehicle-master', pathParams: [], query: [], headers: ['Idempotency-Key', 'traceparent'], hasBody: true, scopes: ['fleet:write'], statuses: [201, 400, 401, 403, 409, 422, 429, 503] },
  getVehicleMaster: { method: 'GET', path: '/v1/operator/vehicle-master/{vehicle_id}', pathParams: ['vehicle_id'], query: [], headers: ['traceparent'], hasBody: false, scopes: ['fleet:read'], statuses: [200, 400, 401, 403, 404, 429, 503] },
  updateVehicleMaster: { method: 'PUT', path: '/v1/operator/vehicle-master/{vehicle_id}', pathParams: ['vehicle_id'], query: [], headers: ['Idempotency-Key', 'If-Match', 'traceparent'], hasBody: true, scopes: ['fleet:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  getVehicleCapacity: { method: 'GET', path: '/v1/operator/vehicle-master/{vehicle_id}/capacity', pathParams: ['vehicle_id'], query: [], headers: ['traceparent'], hasBody: false, scopes: ['fleet:read'], statuses: [200, 400, 401, 403, 404, 429, 503] },
  updateVehicleCapacity: { method: 'PATCH', path: '/v1/operator/vehicle-master/{vehicle_id}/capacity', pathParams: ['vehicle_id'], query: [], headers: ['Idempotency-Key', 'If-Match', 'traceparent'], hasBody: true, scopes: ['fleet:write'], statuses: [200, 400, 401, 403, 404, 412, 422, 428, 429, 503] },
  listVehicleServiceAssignments: { method: 'GET', path: '/v1/operator/vehicles/{vehicle_id}/service-assignments', pathParams: ['vehicle_id'], query: ['as_of', 'limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['fleet:read'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
  createVehicleServiceAssignment: { method: 'POST', path: '/v1/operator/vehicles/{vehicle_id}/service-assignments', pathParams: ['vehicle_id'], query: [], headers: ['Idempotency-Key', 'traceparent'], hasBody: true, scopes: ['fleet:write'], statuses: [201, 400, 401, 403, 404, 409, 422, 429, 503] },
  endVehicleServiceAssignment: { method: 'POST', path: '/v1/operator/vehicles/{vehicle_id}/service-assignments/{assignment_id}/end', pathParams: ['vehicle_id', 'assignment_id'], query: [], headers: ['Idempotency-Key', 'If-Match', 'traceparent'], hasBody: true, scopes: ['fleet:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 428, 422, 429, 503] },
  listServiceAreas: { method: 'GET', path: '/v1/service-areas', pathParams: [], query: ['limit', 'cursor', 'service_id'], headers: ['traceparent'], hasBody: false, scopes: ['geo:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  createServiceArea: { method: 'POST', path: '/v1/service-areas', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['geo:write'], statuses: [201, 400, 401, 403, 404, 409, 422, 429, 503] },
  getServiceArea: { method: 'GET', path: '/v1/service-areas/{area_id}', pathParams: ['area_id'], query: ['revision'], headers: ['traceparent'], hasBody: false, scopes: ['geo:read'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
  updateServiceArea: { method: 'PUT', path: '/v1/service-areas/{area_id}', pathParams: ['area_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['geo:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  listStops: { method: 'GET', path: '/v1/stops', pathParams: [], query: ['limit', 'cursor', 'service_id', 'at', 'near_latitude', 'near_longitude', 'near_radius_m', 'active'], headers: ['traceparent'], hasBody: false, scopes: ['geo:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  createStop: { method: 'POST', path: '/v1/stops', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['geo:write'], statuses: [201, 400, 401, 403, 409, 422, 429, 503] },
  getStop: { method: 'GET', path: '/v1/stops/{stop_id}', pathParams: ['stop_id'], query: ['revision'], headers: ['traceparent'], hasBody: false, scopes: ['geo:read'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
  updateStop: { method: 'PUT', path: '/v1/stops/{stop_id}', pathParams: ['stop_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['geo:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  getServiceRelationRule: { method: 'GET', path: '/v1/services/{service_id}/relation-rules', pathParams: ['service_id'], query: [], headers: ['traceparent'], hasBody: false, scopes: ['geo:read'], statuses: [200, 400, 401, 403, 404, 429, 503] },
  putServiceRelationRule: { method: 'PUT', path: '/v1/services/{service_id}/relation-rules', pathParams: ['service_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['geo:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  checkServiceRelation: { method: 'GET', path: '/v1/services/{service_id}/relation-check', pathParams: ['service_id'], query: ['origin_latitude', 'origin_longitude', 'destination_latitude', 'destination_longitude', 'destination_stop_id', 'at'], headers: ['traceparent'], hasBody: false, scopes: ['geo:read'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
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
  updateRegion: { method: 'PUT', path: '/v1/regions/{region_id}', pathParams: ['region_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['organization:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  listDepots: { method: 'GET', path: '/v1/depots', pathParams: [], query: ['limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['organization:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  createDepot: { method: 'POST', path: '/v1/depots', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['organization:write'], statuses: [201, 400, 401, 403, 404, 409, 422, 429, 503] },
  updateDepot: { method: 'PUT', path: '/v1/depots/{depot_id}', pathParams: ['depot_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['organization:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  listCostCenters: { method: 'GET', path: '/v1/cost-centers', pathParams: [], query: ['limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['organization:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  createCostCenter: { method: 'POST', path: '/v1/cost-centers', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['organization:write'], statuses: [201, 400, 401, 403, 404, 409, 422, 429, 503] },
  updateCostCenter: { method: 'PUT', path: '/v1/cost-centers/{cost_center_id}', pathParams: ['cost_center_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['organization:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  listSessions: { method: 'GET', path: '/v1/sessions', pathParams: [], query: ['limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['sessions:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  revokeSession: { method: 'DELETE', path: '/v1/sessions/{session_id}', pathParams: ['session_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: false, scopes: ['sessions:write'], statuses: [204, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  listOrganizations: { method: 'GET', path: '/v1/organizations', pathParams: [], query: ['limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['organization:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  createOrganization: { method: 'POST', path: '/v1/organizations', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['organization:write'], statuses: [201, 400, 401, 403, 404, 409, 422, 429, 503] },
  getOrganization: { method: 'GET', path: '/v1/organizations/{organization_id}', pathParams: ['organization_id'], query: [], headers: ['traceparent'], hasBody: false, scopes: ['organization:read'], statuses: [200, 400, 401, 403, 404, 429, 503] },
  updateOrganization: { method: 'PUT', path: '/v1/organizations/{organization_id}', pathParams: ['organization_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['organization:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  createTenant: { method: 'POST', path: '/v1/platform/tenants', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['platform:tenants:write'], statuses: [201, 400, 401, 403, 409, 422, 429, 503] },
  changeTenantStatus: { method: 'POST', path: '/v1/platform/tenant-status-changes', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['platform:tenants:write'], statuses: [200, 400, 401, 403, 404, 409, 422, 429, 503] },
  listNotifications: { method: 'GET', path: '/v1/notifications', pathParams: [], query: ['limit', 'cursor', 'state'], headers: ['traceparent'], hasBody: false, scopes: ['notifications:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  getNotification: { method: 'GET', path: '/v1/notifications/{notification_id}', pathParams: ['notification_id'], query: [], headers: ['traceparent'], hasBody: false, scopes: ['notifications:read'], statuses: [200, 400, 401, 403, 404, 429, 503] },
  listPassengers: { method: 'GET', path: '/v1/passengers', pathParams: [], query: ['limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['passengers:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  registerPassenger: { method: 'POST', path: '/v1/passengers', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['passengers:write'], statuses: [201, 400, 401, 403, 409, 422, 429, 503] },
  getPassengerSupportNeeds: { method: 'GET', path: '/v1/passengers/{passenger_id}/support-needs', pathParams: ['passenger_id'], query: [], headers: ['X-Access-Purpose', 'traceparent'], hasBody: false, scopes: ['passengers:support:read'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
  getPassenger: { method: 'GET', path: '/v1/passengers/{passenger_id}', pathParams: ['passenger_id'], query: [], headers: ['traceparent'], hasBody: false, scopes: ['me:read'], statuses: [200, 400, 401, 403, 404, 429, 503] },
  getMyProfile: { method: 'GET', path: '/v1/me/profile', pathParams: [], query: [], headers: ['traceparent'], hasBody: false, scopes: ['me:read'], statuses: [200, 400, 401, 403, 404, 429, 503] },
  updateMyProfile: { method: 'PATCH', path: '/v1/me/profile', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['me:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  startContactVerification: { method: 'POST', path: '/v1/me/contact-verifications', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['me:write'], statuses: [200, 400, 401, 403, 404, 409, 422, 429, 503] },
  confirmContactVerification: { method: 'POST', path: '/v1/me/contact-verifications/confirmations', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['me:write'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
  getMyPrivacySettings: { method: 'GET', path: '/v1/me/privacy', pathParams: [], query: [], headers: ['traceparent'], hasBody: false, scopes: ['me:read'], statuses: [200, 400, 401, 403, 404, 429, 503] },
  recordMyConsent: { method: 'POST', path: '/v1/me/privacy/consents', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['me:write'], statuses: [201, 400, 401, 403, 404, 409, 422, 429, 503] },
  getMyTransportSupport: { method: 'GET', path: '/v1/me/transport-support', pathParams: [], query: [], headers: ['traceparent'], hasBody: false, scopes: ['me:read'], statuses: [200, 400, 401, 403, 404, 429, 503] },
  replaceMyTransportSupport: { method: 'PUT', path: '/v1/me/transport-support', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['me:write'], statuses: [204, 400, 401, 403, 404, 422, 429, 503] },
  receiveProviderEvent: { method: 'POST', path: '/v1/payments/provider-events/{provider}', pathParams: ['provider'], query: [], headers: ['X-Provider-Timestamp', 'traceparent'], hasBody: true, scopes: [], statuses: [202, 200, 400, 401, 403, 409, 413, 422, 429, 503] },
  requestReceiptUpload: { method: 'POST', path: '/v1/expenses/receipts', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['receipts:write'], statuses: [201, 400, 401, 403, 404, 409, 422, 429, 503] },
  completeReceiptUpload: { method: 'POST', path: '/v1/expenses/receipts/{ticket_id}/complete', pathParams: ['ticket_id'], query: [], headers: ['Idempotency-Key', 'If-Match', 'traceparent'], hasBody: false, scopes: ['receipts:write'], statuses: [201, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  getReceiptDocument: { method: 'GET', path: '/v1/expenses/receipts/{document_id}', pathParams: ['document_id'], query: [], headers: ['traceparent'], hasBody: false, scopes: ['receipts:read'], statuses: [200, 400, 401, 403, 404, 429, 503] },
  getEffectiveConfig: { method: 'GET', path: '/v1/config/effective', pathParams: [], query: ['at', 'parameter', 'region_id', 'service_id', 'vehicle_id'], headers: ['traceparent'], hasBody: false, scopes: ['config:read'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
  listConfigParameters: { method: 'GET', path: '/v1/config/parameters', pathParams: [], query: [], headers: ['traceparent'], hasBody: false, scopes: ['config:read'], statuses: [200, 400, 401, 403, 429, 503] },
  listConfigRevisions: { method: 'GET', path: '/v1/config/revisions', pathParams: [], query: ['limit', 'cursor', 'scope_kind', 'scope_id', 'state', 'as_of'], headers: ['traceparent'], hasBody: false, scopes: ['config:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  createConfigRevision: { method: 'POST', path: '/v1/config/revisions', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['config:write'], statuses: [201, 400, 401, 403, 409, 422, 429, 503] },
  getConfigRevision: { method: 'GET', path: '/v1/config/revisions/{revision_id}', pathParams: ['revision_id'], query: [], headers: ['traceparent'], hasBody: false, scopes: ['config:read'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
  updateConfigRevision: { method: 'PATCH', path: '/v1/config/revisions/{revision_id}', pathParams: ['revision_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['config:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  validateConfigRevision: { method: 'POST', path: '/v1/config/revisions/{revision_id}/validate', pathParams: ['revision_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: false, scopes: ['config:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  approveConfigRevision: { method: 'POST', path: '/v1/config/revisions/{revision_id}/approve', pathParams: ['revision_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: false, scopes: ['config:publish'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  rollbackConfigRevision: { method: 'POST', path: '/v1/config/revisions/{revision_id}/rollback', pathParams: ['revision_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['config:publish'], statuses: [201, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  getConfigImpactReport: { method: 'GET', path: '/v1/config/impact-reports/{impact_report_id}', pathParams: ['impact_report_id'], query: [], headers: ['traceparent'], hasBody: false, scopes: ['config:read'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
  requestConfigImpact: { method: 'POST', path: '/v1/config/revisions/{revision_id}/impact', pathParams: ['revision_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: false, scopes: ['config:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  listServices: { method: 'GET', path: '/v1/services', pathParams: [], query: ['limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['services:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  createService: { method: 'POST', path: '/v1/services', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['services:write'], statuses: [201, 400, 401, 403, 404, 409, 422, 429, 503] },
  updateService: { method: 'PUT', path: '/v1/services/{service_id}', pathParams: ['service_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['services:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  getServiceConfiguration: { method: 'GET', path: '/v1/services/{service_id}/configuration', pathParams: ['service_id'], query: ['at'], headers: ['traceparent'], hasBody: false, scopes: ['config:read'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
  listServiceOfferModels: { method: 'GET', path: '/v1/service-offer-models', pathParams: [], query: [], headers: ['traceparent'], hasBody: false, scopes: ['services:read'], statuses: [200, 400, 401, 403, 429, 503] },
  getServiceOperatingHours: { method: 'GET', path: '/v1/services/{service_id}/operating-hours', pathParams: ['service_id'], query: [], headers: ['traceparent'], hasBody: false, scopes: ['services:read'], statuses: [200, 400, 401, 403, 404, 429, 503] },
  putServiceOperatingHours: { method: 'PUT', path: '/v1/services/{service_id}/operating-hours', pathParams: ['service_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: true, scopes: ['services:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  listServiceOperatingHoursRevisions: { method: 'GET', path: '/v1/services/{service_id}/operating-hours/revisions', pathParams: ['service_id'], query: ['limit', 'before_revision'], headers: ['traceparent'], hasBody: false, scopes: ['services:read'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
  getServiceAvailability: { method: 'GET', path: '/v1/services/{service_id}/availability', pathParams: ['service_id'], query: ['at'], headers: ['traceparent'], hasBody: false, scopes: ['geo:read'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
  listMySessions: { method: 'GET', path: '/v1/me/sessions', pathParams: [], query: ['limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['me:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  revokeAllMySessions: { method: 'POST', path: '/v1/me/sessions/revoke-all', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: false, scopes: ['me:write'], statuses: [204, 400, 401, 403, 422, 429, 503] },
  revokeMySession: { method: 'DELETE', path: '/v1/me/sessions/{session_id}', pathParams: ['session_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: false, scopes: ['me:write'], statuses: [204, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  lockAccount: { method: 'POST', path: '/v1/account-locks', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['accounts:write'], statuses: [201, 400, 401, 403, 404, 409, 422, 429, 503] },
  unlockAccount: { method: 'DELETE', path: '/v1/account-locks/{subject}', pathParams: ['subject'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: false, scopes: ['accounts:write'], statuses: [204, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  startAccountRecovery: { method: 'POST', path: '/v1/account-recoveries', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['accounts:write'], statuses: [201, 400, 401, 403, 404, 409, 422, 429, 503] },
  listStaffShifts: { method: 'GET', path: '/v1/shifts', pathParams: [], query: ['driver_id', 'vehicle_id', 'from', 'to', 'limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['shifts:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  createStaffShift: { method: 'POST', path: '/v1/shifts', pathParams: [], query: [], headers: ['Idempotency-Key', 'traceparent'], hasBody: true, scopes: ['shifts:write'], statuses: [201, 400, 401, 403, 404, 409, 422, 429, 503] },
  getStaffShift: { method: 'GET', path: '/v1/shifts/{shift_id}', pathParams: ['shift_id'], query: [], headers: ['traceparent'], hasBody: false, scopes: ['shifts:read'], statuses: [200, 400, 401, 403, 404, 429, 503] },
  updateStaffShift: { method: 'PUT', path: '/v1/shifts/{shift_id}', pathParams: ['shift_id'], query: [], headers: ['Idempotency-Key', 'If-Match', 'traceparent'], hasBody: true, scopes: ['shifts:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
  listTimeEvents: { method: 'GET', path: '/v1/time-events', pathParams: [], query: ['person_ref', 'from', 'to', 'shift_id', 'limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['time-events:read'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
  recordTimeEvent: { method: 'POST', path: '/v1/time-events', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['time-events:write'], statuses: [201, 200, 400, 401, 403, 404, 409, 422, 429, 503] },
  createTimeEventBackfill: { method: 'POST', path: '/v1/time-events/backfills', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['time-events:manage'], statuses: [201, 200, 400, 401, 403, 404, 409, 422, 429, 503] },
  listTimeIntervals: { method: 'GET', path: '/v1/time-events/intervals', pathParams: [], query: ['person_ref', 'from', 'to'], headers: ['traceparent'], hasBody: false, scopes: ['time-events:read'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
  listWebhookSubscriptions: { method: 'GET', path: '/v1/webhook-subscriptions', pathParams: [], query: ['limit', 'cursor'], headers: ['traceparent'], hasBody: false, scopes: ['webhooks:read'], statuses: [200, 400, 401, 403, 422, 429, 503] },
  createWebhookSubscription: { method: 'POST', path: '/v1/webhook-subscriptions', pathParams: [], query: [], headers: ['traceparent', 'Idempotency-Key'], hasBody: true, scopes: ['webhooks:write'], statuses: [201, 400, 401, 403, 409, 422, 429, 503] },
  getWebhookSubscription: { method: 'GET', path: '/v1/webhook-subscriptions/{subscription_id}', pathParams: ['subscription_id'], query: [], headers: ['traceparent'], hasBody: false, scopes: ['webhooks:read'], statuses: [200, 400, 401, 403, 404, 422, 429, 503] },
  deactivateWebhookSubscription: { method: 'POST', path: '/v1/webhook-subscriptions/{subscription_id}/deactivate', pathParams: ['subscription_id'], query: [], headers: ['traceparent', 'Idempotency-Key', 'If-Match'], hasBody: false, scopes: ['webhooks:write'], statuses: [200, 400, 401, 403, 404, 409, 412, 422, 428, 429, 503] },
};
