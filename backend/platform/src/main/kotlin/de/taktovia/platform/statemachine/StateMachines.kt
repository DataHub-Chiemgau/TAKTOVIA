package de.taktovia.platform.statemachine

/** Journey-Buchung (Kap. 02 §7.3). Anwendung auf `booking.state` in M2; die Zusage ist ein eigenes Objekt. */
enum class BookingState { HOLDING, HELD, ACTION_REQUIRED, CONFIRMING, RECONCILING, CONFIRMED, PARTIAL, COMPENSATING, FAILED, CANCEL_PENDING, CANCELED, COMPLETED }

/** Zahlungsautomat (Kap. 03 §4.2). `DISPUTED` ist ein eigener Streitstatus und nicht Teil dieses Automaten. */
enum class PaymentState { CREATED, REQUIRES_ACTION, AUTHORIZED, CAPTURE_PENDING, CAPTURED, AUTHORIZATION_FAILED, VOID_PENDING, VOIDED, CAPTURE_UNKNOWN, RECONCILING, REFUND_PENDING, PARTIALLY_REFUNDED, REFUNDED }

/** Operation/Saga (Kap. 02 §7.3, `operation.status`). `RECONCILING` wird nie zu Erfolg oder Fehler verkürzt. */
enum class OperationState { QUEUED, RUNNING, RECONCILING, SUCCEEDED, FAILED, MANUAL_REVIEW }

/** Mandantenstatus (`tenant.status`). */
enum class TenantState { ACTIVE, SUSPENDED, CLOSED }

/**
 * Registrierung aller Automaten (WP-M1-17). Jeder Automat hat eine eigene Definition und eine eigene Zustandsspalte; kein Automat leitet
 * seinen Zustand aus einem anderen ab (Zahlungsstatus ist kein Gleichlauf zum Buchungsstatus, Kap. 02 §7.3): die Definitionen kennen
 * einander nicht, ihre Zustandstypen sind getrennte Enums. Die Übergangstabellen stehen auch in `db/migrations/V1170__zustandshaertung.sql`;
 * `StateMachineTransitionTest` vergleicht beide Seiten.
 */
object StateMachines {
    private fun <S : Enum<S>> t(vararg p: Pair<S, List<S>>) = p.toMap().mapValues { it.value.toSet() }

    val OPERATION = StateMachine("operation", 1, OperationState.entries, "taktovia.operation", "status", t(
        OperationState.QUEUED to listOf(OperationState.RUNNING),
        OperationState.RUNNING to listOf(OperationState.SUCCEEDED, OperationState.FAILED, OperationState.RECONCILING, OperationState.MANUAL_REVIEW, OperationState.QUEUED),
        OperationState.RECONCILING to listOf(OperationState.SUCCEEDED, OperationState.FAILED, OperationState.MANUAL_REVIEW),
        OperationState.MANUAL_REVIEW to listOf(OperationState.SUCCEEDED, OperationState.FAILED, OperationState.QUEUED, OperationState.RECONCILING),
    ))

    /** Der Mandant trägt keine Revisionsspalte (OP-33): Revision aus der Ereignisfolge. Geändert wird nur von der Plattformverwaltung, nicht von der Runtime-Rolle. */
    val TENANT = StateMachine("tenant", 1, TenantState.entries, "taktovia.tenant", "status", t(
        TenantState.ACTIVE to listOf(TenantState.SUSPENDED, TenantState.CLOSED),
        TenantState.SUSPENDED to listOf(TenantState.ACTIVE, TenantState.CLOSED),
    ), revisionColumn = null, tenantColumn = "id")

    val BOOKING = StateMachine("booking", 1, BookingState.entries, "taktovia.booking", "state", t(
        BookingState.HOLDING to listOf(BookingState.HELD, BookingState.COMPENSATING, BookingState.FAILED),
        BookingState.HELD to listOf(BookingState.ACTION_REQUIRED, BookingState.CONFIRMING, BookingState.COMPENSATING, BookingState.FAILED),
        BookingState.ACTION_REQUIRED to listOf(BookingState.CONFIRMING, BookingState.COMPENSATING, BookingState.FAILED),
        BookingState.CONFIRMING to listOf(BookingState.CONFIRMED, BookingState.PARTIAL, BookingState.RECONCILING, BookingState.COMPENSATING, BookingState.FAILED),
        BookingState.RECONCILING to listOf(BookingState.CONFIRMED, BookingState.PARTIAL, BookingState.COMPENSATING, BookingState.FAILED),
        BookingState.PARTIAL to listOf(BookingState.COMPENSATING, BookingState.CANCEL_PENDING, BookingState.COMPLETED),
        BookingState.COMPENSATING to listOf(BookingState.FAILED, BookingState.CANCELED),
        BookingState.CONFIRMED to listOf(BookingState.CANCEL_PENDING, BookingState.COMPLETED),
        BookingState.CANCEL_PENDING to listOf(BookingState.CANCELED, BookingState.RECONCILING),
    ))

    val PAYMENT = StateMachine("payment", 1, PaymentState.entries, "taktovia.payment_intent", "state", t(
        PaymentState.CREATED to listOf(PaymentState.REQUIRES_ACTION, PaymentState.AUTHORIZED, PaymentState.AUTHORIZATION_FAILED),
        PaymentState.REQUIRES_ACTION to listOf(PaymentState.AUTHORIZED, PaymentState.AUTHORIZATION_FAILED),
        PaymentState.AUTHORIZED to listOf(PaymentState.CAPTURE_PENDING, PaymentState.VOID_PENDING),
        PaymentState.CAPTURE_PENDING to listOf(PaymentState.CAPTURED, PaymentState.CAPTURE_UNKNOWN),
        PaymentState.CAPTURE_UNKNOWN to listOf(PaymentState.RECONCILING),
        PaymentState.RECONCILING to listOf(PaymentState.CAPTURED, PaymentState.AUTHORIZED, PaymentState.VOID_PENDING),
        PaymentState.VOID_PENDING to listOf(PaymentState.VOIDED),
        PaymentState.CAPTURED to listOf(PaymentState.REFUND_PENDING),
        PaymentState.REFUND_PENDING to listOf(PaymentState.PARTIALLY_REFUNDED, PaymentState.REFUNDED),
        PaymentState.PARTIALLY_REFUNDED to listOf(PaymentState.REFUND_PENDING),
    ))

    // OP-21: im Pflichtenheft keine Zustands-/Übergangslisten (Fahrt lokal, Fahrzeug, Störung). Nicht erfunden; Bau erst nach Spezifikation.
    val TRIP = UnspecifiedMachine("trip", "taktovia.vehicle_run", "state", "OP-21")
    val VEHICLE = UnspecifiedMachine("vehicle", null, null, "OP-21")
    val DISRUPTION = UnspecifiedMachine("disruption", null, null, "OP-21")

    /** Die fünf fachlichen Automaten (Fahrt, Buchung, Fahrzeug, Zahlung, Störung) plus Operation und Mandant. */
    val all: List<MachineDefinition> = listOf(TRIP, BOOKING, VEHICLE, PAYMENT, DISRUPTION, OPERATION, TENANT)
    val specified: List<StateMachine<*>> get() = all.filterIsInstance<StateMachine<*>>()
}
