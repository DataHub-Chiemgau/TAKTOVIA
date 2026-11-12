package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.BookingDemand
import de.taktovia.bookingdispatch.api.BookingDetail
import de.taktovia.bookingdispatch.api.BookingHistoryEntry
import de.taktovia.bookingdispatch.api.BookingReason
import de.taktovia.bookingdispatch.api.CancelBooking
import de.taktovia.bookingdispatch.api.CancelResult
import de.taktovia.bookingdispatch.api.CauseClass
import de.taktovia.bookingdispatch.api.CancellationPolicy
import de.taktovia.bookingdispatch.api.PlanStop
import de.taktovia.bookingdispatch.api.RevisionCause
import de.taktovia.bookingdispatch.api.RunState
import de.taktovia.config.api.EffectiveConfig
import de.taktovia.config.api.EffectiveRequest
import de.taktovia.config.api.EffectiveStatus
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.bookingdispatch.api.HistoryLinks
import de.taktovia.bookingdispatch.api.HistoryView
import de.taktovia.bookingdispatch.api.BookingPromise
import de.taktovia.bookingdispatch.api.Bookings
import de.taktovia.bookingdispatch.api.ConfirmBooking
import de.taktovia.bookingdispatch.api.ConfirmedBooking
import de.taktovia.bookingdispatch.api.PromiseCore
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.farepayment.api.FareQuotes
import de.taktovia.platform.approval.FourEyes
import de.taktovia.platform.authz.ObjectAuthorizer
import de.taktovia.platform.authz.ScopedObject
import de.taktovia.platform.authz.ServiceScopeAuthorizer
import de.taktovia.platform.authz.ServiceScoped
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.statemachine.BookingState
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.RowMapper
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Buchungsbestätigung und Lesesichten (WP-M2-23). **Sperrreihenfolge** wie bei Hold, Freigabe und Ablauf: zuerst die Zeile `vehicle_run` des Holds, dann das Angebot, dann der Hold. Alle
 * fachlichen Ablehnungen sind Ausnahmen und rollen die ganze Transaktion zurück: Hold-Verbrauch, Buchung, Journey-Hülle, Buchungsnummer und Ereignis entstehen gemeinsam oder gar nicht.
 */
@ServiceScoped
internal class JdbcBookings(
    private val ctxTx: ContextTransactions, private val quotes: FareQuotes, private val holds: JdbcCapacityHolds, private val transitions: BookingTransitions, private val plans: JdbcRunPlans,
    private val outbox: Outbox, private val config: EffectiveConfig?, private val clock: Clock,
    private val authorizer: ObjectAuthorizer = ServiceScopeAuthorizer,
) : Bookings {
    private val json = JsonMapper.builder().build()

    private class OfferRow(
        val actorRef: String, val serviceId: UUID, val quoteId: UUID, val revision: Long, val pickupEarliest: Instant, val pickupLatest: Instant, val arrivalEarliest: Instant, val arrivalLatest: Instant,
        val cancellationPolicyRef: String, val demand: String, val pickupStopRevisionId: UUID?, val dropoffStopRevisionId: UUID?,
    )

    private fun offerOfHold(j: JdbcOperations, holdId: UUID, lock: Boolean): OfferRow? =
        if (lock) j.query("SELECT $OFFER_COLS FROM taktovia.ride_offer WHERE hold_id = ? FOR UPDATE", OFFER, holdId).firstOrNull()  // sql-lint: ok Spaltenliste aus Konstante, Wert gebunden
        else j.query("SELECT $OFFER_COLS FROM taktovia.ride_offer WHERE hold_id = ?", OFFER, holdId).firstOrNull()  // sql-lint: ok Spaltenliste aus Konstante, Wert gebunden

    /** Das Fahrgastkonto des Akteurs im Mandanten (`passenger_private`, Bindung über Aussteller und Subject des Tokens); `null` = kein Konto. */
    private fun passengerOf(j: JdbcOperations, ctx: RequestContext): UUID? = j.query(
        "SELECT id FROM taktovia.passenger_private WHERE idp_issuer = ? AND idp_subject = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, ctx.actor.issuer, ctx.actor.subject,
    ).firstOrNull()

    override fun confirm(ctx: RequestContext, cmd: ConfirmBooking): ConfirmedBooking = ctxTx.run(ctx) { j ->
        val passengerId = passengerOf(j, ctx) ?: throw ProblemException(ErrorCode.FORBIDDEN, "Eine Buchung setzt ein Fahrgastkonto voraus.")
        // Hold und Angebot zuerst ohne Sperre lesen (Umlauf, Besitzer und Bezüge sind unveränderlich), dann Umlauf, dann Angebot, dann Hold sperren
        val runId = j.query("SELECT run_id FROM taktovia.capacity_hold WHERE id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, cmd.holdId).firstOrNull() ?: throw ProblemException.notFound()
        val pre = offerOfHold(j, cmd.holdId, lock = false) ?: throw ProblemException.notFound()
        if (pre.actorRef != FourEyes.refOf(ctx).value) throw ProblemException.notFound()   // fremder Hold: wie nicht vorhanden
        j.query("SELECT 1 FROM taktovia.vehicle_run WHERE id = ? FOR UPDATE", { rs, _ -> rs.getInt(1) }, runId)
        val offer = offerOfHold(j, cmd.holdId, lock = true)!!
        val hold = j.query("SELECT state, expires_at <= clock_timestamp() FROM taktovia.capacity_hold WHERE id = ? FOR UPDATE", { rs, _ -> rs.getString(1) to rs.getBoolean(2) }, cmd.holdId).first()
        val quote = quotes.getIn(j, offer.quoteId) ?: throw ProblemException.notFound()
        // Wiederholung: dieselbe Buchung (vor Ablauf-, ETag- und Preisprüfung); der Betrag muss dem bestätigten entsprechen
        j.query("SELECT id FROM taktovia.booking WHERE hold_id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, cmd.holdId).firstOrNull()?.let { existing ->
            if (quote.payAtPlatformMinor != cmd.maxGrossMinor || quote.currency != cmd.currency) throw ProblemException(ErrorCode.REQUOTE_REQUIRED, "Der Betrag weicht vom bestätigten Preis ab.")
            return@run ConfirmedBooking(detail(j, ctx, existing, passengerOf = passengerId), replayed = true)
        }
        when {
            hold.first == "HELD" && hold.second -> throw ProblemException(ErrorCode.HOLD_EXPIRED, "Die Reservierung ist abgelaufen.")
            hold.first == "EXPIRED" -> throw ProblemException(ErrorCode.HOLD_EXPIRED, "Die Reservierung ist abgelaufen.")
            hold.first != "HELD" -> throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Die Reservierung wurde freigegeben oder bereits verbraucht.")
        }
        if (offer.revision != cmd.ifMatch) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Das Angebot wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        if (quote.payAtPlatformMinor != cmd.maxGrossMinor || quote.currency != cmd.currency) throw ProblemException(ErrorCode.REQUOTE_REQUIRED, "Der akzeptierte Betrag weicht vom Preisstand des Angebots ab.")
        val vector = ResourceVector.parse(offer.demand)
        val promise = PromiseCore(
            offer.pickupEarliest.toString(), offer.pickupLatest.toString(), offer.arrivalEarliest.toString(), offer.arrivalLatest.toString(), quote.payAtPlatformMinor, quote.currency,
            offer.cancellationPolicyRef, if (vector.wheelchair > 0) "ASSISTENZ" else "STANDARD",
        )
        val operatorRef = j.queryForObject(
            "SELECT o.federation_ref FROM taktovia.service s JOIN taktovia.organization o ON o.tenant_id = s.tenant_id AND o.id = s.operator_id WHERE s.id = ?", String::class.java, offer.serviceId,
        )!!
        val identity = JdbcCapacityHolds.ConfirmIdentity(passengerId, FourEyes.refOf(ctx).value, offer.quoteId, operatorRef, offer.pickupStopRevisionId?.toString() ?: "unbound", offer.dropoffStopRevisionId?.toString() ?: "unbound")
        val created = holds.confirmIn(j, ctx, cmd.holdId, offer.serviceId, promise, BookingDemand(vector.seats + vector.wheelchair, vector, emptySet()), identity)
        // Zahlungsvoraussetzung (OP-5): tariflich kostenfrei = sofort bestätigt, sonst CONFIRMING bis zum verifizierten PSP-Status AUTHORIZED (WP-M2-30)
        transitions.recordCreated(j, ctx, created.id, BookingState.CONFIRMING)
        if (quote.payAtPlatformMinor == 0L) transitions.apply(j, ctx, created.id, BookingState.CONFIRMED, BookingReason.FREE_OF_CHARGE, 1)
        ConfirmedBooking(detail(j, ctx, created.id, passengerOf = passengerId), replayed = false)
    }

    // ---- Lesen ------------------------------------------------------------------------------------------------------------------------

    private class Row(
        val id: UUID, val bookingNo: String, val serviceId: UUID, val holdId: UUID, val state: String, val revision: Long, val createdAt: Instant, val snapshot: String, val passengerId: UUID?,
        val cancelReason: String?, val cancelCause: String?,
    )

    private fun isPassengerView(ctx: RequestContext) = "passenger" in ctx.roles

    private fun row(j: JdbcOperations, id: UUID): Row? = j.query("SELECT $COLS FROM taktovia.booking WHERE id = ? AND booking_no IS NOT NULL", ROW, id).firstOrNull()

    /** Berechtigung des Aufrufers auf die Buchung: Fahrgast nur eigene, Betrieb nur Dienste des Scopes; alles andere ist `404`. */
    private fun authorize(j: JdbcOperations, ctx: RequestContext, r: Row, passengerOf: UUID?) {
        if (isPassengerView(ctx)) { if (passengerOf == null || r.passengerId != passengerOf) throw ProblemException.notFound() }
        else authorizer.requireRead(j, ctx, ScopedObject("BOOKING", r.id, ctx.tenant, r.serviceId))
    }

    private fun detail(j: JdbcOperations, ctx: RequestContext, id: UUID, passengerOf: UUID?): BookingDetail {
        val r = row(j, id) ?: throw ProblemException.notFound()
        authorize(j, ctx, r, passengerOf)
        return view(j, ctx, r)
    }

    private fun view(j: JdbcOperations, ctx: RequestContext, r: Row): BookingDetail {
        val node = json.readTree(r.snapshot)
        fun at(f: String) = OffsetDateTime.parse(node[f].asString()).toInstant()
        val vector = node["resource_vector"]?.let { ResourceVector.parse(it.toString()) }
        val promise = BookingPromise(at("pickup_earliest"), at("pickup_latest"), at("arrival_earliest"), at("arrival_latest"), node["gross_minor"].asLong(), node["currency"].asString(),
            j.queryForObject("SELECT exponent FROM taktovia.currency WHERE code = ?", Int::class.java, node["currency"].asString())!!, node["cancellation_policy_ref"].asString(), node["vehicle_class"]?.asString(), vector)
        val codes = if (isPassengerView(ctx) || ctx.roles.any { it in ASSISTANCE_ROLES })
            (j.query("SELECT array_to_string(assistance_codes, ',') FROM taktovia.passenger_trip WHERE booking_id = ? ORDER BY seq LIMIT 1", { rs, _ -> rs.getString(1) }, r.id).firstOrNull() ?: "")
                .split(',').filter { it.isNotBlank() } else null
        return BookingDetail(r.id, r.bookingNo, r.serviceId, r.holdId, r.state, r.revision, r.createdAt, (vector?.let { it.seats + it.wheelchair } ?: 0), vector?.wheelchair ?: 0, codes, promise,
            r.cancelReason?.let { BookingReason.parse(it) }, r.cancelCause?.let { CauseClass.valueOf(it) })
    }

    override fun get(ctx: RequestContext, bookingId: UUID): BookingDetail = ctxTx.run(ctx, readOnly = true) { j -> detail(j, ctx, bookingId, passengerOf(j, ctx)) }

    override fun list(ctx: RequestContext, limit: Int, after: Pair<Instant, UUID>?): List<BookingDetail> = ctxTx.run(ctx, readOnly = true) { j ->
        val cursor = if (after == null) "" else " AND (created_at, id) > (?, ?)"
        val cursorArgs = if (after == null) emptyList() else listOf(Timestamp.from(after.first), after.second)
        val rows = if (isPassengerView(ctx)) {
            val own = passengerOf(j, ctx) ?: return@run emptyList()
            j.query("SELECT $COLS FROM taktovia.booking WHERE booking_no IS NOT NULL AND passenger_id = ?$cursor ORDER BY created_at, id LIMIT ?", ROW, *(listOf<Any>(own) + cursorArgs + (limit + 1)).toTypedArray())  // sql-lint: ok Fragment aus Konstanten, Werte gebunden
        } else {
            val services = authorizer.effectiveServices(ctx)?.toList().orEmpty()
            if (services.isEmpty()) return@run emptyList()
            val ids = services.joinToString(",", "{", "}")   // nur UUIDs aus dem Scope des Kontexts
            j.query("SELECT $COLS FROM taktovia.booking WHERE booking_no IS NOT NULL AND service_id = ANY(CAST(? AS uuid[]))$cursor ORDER BY created_at, id LIMIT ?", ROW, *(listOf<Any>(ids) + cursorArgs + (limit + 1)).toTypedArray())  // sql-lint: ok Fragment aus Konstanten, Werte gebunden
        }
        rows.map { view(j, ctx, it) }
    }

    // ---- Storno (WP-M2-26) -------------------------------------------------------------------------------------------------------------

    /** Stornoregeln des Dienstes aus der wirksamen Konfiguration; ohne Wert (oder ohne Konfigurationszugriff) gelten die Planungsdefaults, nie ein erfundener Wert. */
    private fun policy(j: JdbcOperations, ctx: RequestContext, serviceId: UUID): CancellationPolicy {
        val defaulted = mutableSetOf<String>()
        fun resolve(name: String): Any? {
            val item = config?.resolveInTransaction(j, ctx, EffectiveRequest(parameter = name, serviceId = serviceId, at = clock.instant()))?.items?.firstOrNull()
            return if (item != null && item.status == EffectiveStatus.EFFECTIVE) item.value else null.also { defaulted += name }
        }
        val deadline = (resolve("cancellation_deadline_min") as? Number)?.toInt() ?: CancellationPolicy.DEFAULT_DEADLINE_MIN
        val reasons = ((resolve("cancellation_reasons") as? Map<*, *>)?.get("reasons") as? List<*>)?.mapNotNull { BookingReason.parse(it.toString()) }?.toSet() ?: CancellationPolicy.DEFAULT_REASONS
        return CancellationPolicy(deadline, reasons, defaulted)
    }

    override fun cancel(ctx: RequestContext, bookingId: UUID, cmd: CancelBooking): CancelResult = ctxTx.run(ctx) { j ->
        val passenger = isPassengerView(ctx)
        val own = if (passenger) passengerOf(j, ctx) else null
        val pre = row(j, bookingId) ?: throw ProblemException.notFound()
        if (passenger) { if (own == null || pre.passengerId != own) throw ProblemException.notFound() }
        else authorizer.requireWrite(j, ctx, ScopedObject("BOOKING", pre.id, ctx.tenant, pre.serviceId))
        // Berechtigung je Grund: Fahrgäste setzen nur den Kundenstorno, alle Gründe müssen aus dem Stornokatalog stammen und für den Dienst zulässig sein
        if (cmd.reason.kind != BookingReason.Kind.CANCELLATION || cmd.reason == BookingReason.CANCEL_COMPLETED || cmd.reason == BookingReason.CANCEL_UNDETERMINED)
            throw ProblemException.invalid(Violation("/reason", ViolationCode.NOT_IN_ENUM))
        val actor = if (passenger) BookingReason.ReasonActor.PASSENGER else if (ctx.actor.type == ActorType.SERVICE) BookingReason.ReasonActor.SYSTEM else BookingReason.ReasonActor.OPERATOR
        if (actor !in cmd.reason.settableBy) throw ProblemException(ErrorCode.FORBIDDEN, "Dieser Stornogrund darf von dieser Rolle nicht gesetzt werden.")
        val policy = policy(j, ctx, pre.serviceId)
        if (cmd.reason !in policy.reasons) throw ProblemException.invalid(Violation("/reason", ViolationCode.NOT_IN_ENUM))
        // Sperren in stabiler Reihenfolge: Umlauf (Kapazitätslock), Hold, Buchung
        val holdId = pre.holdId
        val runId = j.query("SELECT run_id FROM taktovia.capacity_hold WHERE id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, holdId).first()
        j.query("SELECT 1 FROM taktovia.vehicle_run WHERE id = ? FOR UPDATE", { rs, _ -> rs.getInt(1) }, runId)
        j.query("SELECT 1 FROM taktovia.capacity_hold WHERE id = ? FOR UPDATE", { rs, _ -> rs.getInt(1) }, holdId)
        val cur = j.query("SELECT state, revision FROM taktovia.booking WHERE id = ? FOR UPDATE", { rs, _ -> rs.getString(1) to rs.getLong(2) }, bookingId).first()
        if (cur.second != cmd.ifMatch) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Buchung wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        if (cur.first != BookingState.CONFIRMED.name && cur.first != BookingState.CONFIRMING.name)
            throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Eine Buchung im Zustand ${cur.first} kann nicht storniert werden.")
        // Frist (nur Fahrgäste): Mindestabstand zum Beginn des zugesagten Abholfensters, Auswertung mit Serverzeit; die Fristgrenze selbst gilt noch als innerhalb
        if (passenger) {
            val pickup = OffsetDateTime.parse(json.readTree(pre.snapshot)["pickup_earliest"].asString()).toInstant()
            if (clock.instant().isAfter(pickup.minus(java.time.Duration.ofMinutes(policy.deadlineMin.toLong()))))
                throw ProblemException(ErrorCode.CANCEL_DEADLINE_EXPIRED, "Die Stornofrist von ${policy.deadlineMin} Minuten vor der Abholung ist abgelaufen.")
        }
        val confirmed = cur.first == BookingState.CONFIRMED.name
        transitions.apply(j, ctx, bookingId, if (confirmed) BookingState.CANCEL_PENDING else BookingState.COMPENSATING, cmd.reason, cmd.ifMatch)
        // Reservierung freigeben: die Kapazität zählt auf allen Abschnitten und Dimensionen wieder (Belegung = CONSUMED plus gültiges HELD)
        check(j.update("UPDATE taktovia.capacity_hold SET state = 'RELEASED' WHERE id = ? AND state = 'CONSUMED'", holdId) == 1) { "Reservierung der Buchung nicht freigebbar" }
        val planRevision = removeFromPlan(j, ctx, runId, bookingId)
        transitions.apply(j, ctx, bookingId, BookingState.CANCELED, if (confirmed) BookingReason.CANCEL_COMPLETED else BookingReason.COMPENSATION_DONE, null)
        val revision = cur.second + 2
        val initiator = actor.name
        outbox.append(j, OutboxEvent(bookingId, revision, "booking.cancelled", linkedMapOf(
            "booking_id" to bookingId.toString(), "reason" to cmd.reason.name, "reason_code" to cmd.reason.name, "cause_class" to cmd.reason.cause!!.name, "initiator" to initiator, "released_hold_id" to holdId.toString(),
        ), clock.instant(), correlationId = ctx.correlationId))
        CancelResult(detail(j, ctx, bookingId, passengerOf = own), holdId, planRevision)
    }

    /**
     * Neue Planrevision ohne die Stopps der Buchung (Fahrerauftrag): nur wenn der aktuelle Plan sie enthält und der Umlauf änderbar ist (Zustand `PLANNED`/`ACTIVE`). Stopps, die nur für diese Buchung
     * da waren, entfallen; die Planzeiten der übrigen Stopps bleiben unverändert (zugesagte Fenster bleiben eingehalten), die Fahrzeit zum Folgestopp wird um die entfallene Teilstrecke erweitert
     * (Obergrenze; eine Neuoptimierung gehört nicht zum Umfang). Liefert die neue Revision oder `null`.
     */
    private fun removeFromPlan(j: JdbcOperations, ctx: RequestContext, runId: UUID, bookingId: UUID): Long? {
        val run = j.query("SELECT revision, state FROM taktovia.vehicle_run WHERE id = ?", { rs, _ -> rs.getLong(1) to RunState.valueOf(rs.getString(2)) }, runId).first()
        if (run.second !in RunState.PLAN_CHANGEABLE) return null
        val plan = plans.planIn(j, runId, run.first)
        fun has(s: PlanStop) = s.boardings.any { it.bookingId == bookingId } || s.alightings.any { it.bookingId == bookingId }
        if (plan.stops.none { has(it) }) return null
        val kept = mutableListOf<PlanStop>()
        for (s in plan.stops) {
            val stripped = s.copy(boardings = s.boardings.filter { it.bookingId != bookingId }, alightings = s.alightings.filter { it.bookingId != bookingId })
            if (has(s) && stripped.boardings.isEmpty() && stripped.alightings.isEmpty()) {          // der Stopp bestand nur für diese Buchung: entfällt, seine Teilstrecke wandert zum Vorgänger
                if (kept.isNotEmpty() && s.legDurationSec != null) {
                    val prev = kept.removeAt(kept.lastIndex)
                    kept += prev.copy(legDurationSec = (prev.legDurationSec ?: 0) + s.legDurationSec, legDistanceM = (prev.legDistanceM ?: 0) + (s.legDistanceM ?: 0))
                } else if (kept.isNotEmpty()) kept += kept.removeAt(kept.lastIndex).copy(legDurationSec = null, legDistanceM = null)   // entfallener letzter Stopp
                // entfallener erster Stopp: keine Teilstrecke zu übertragen, die Folgestopps rücken nach
            } else kept += stripped
        }
        val renumbered = kept.mapIndexed { i, s -> s.copy(seq = i, legDurationSec = if (i == kept.lastIndex) null else s.legDurationSec, legDistanceM = if (i == kept.lastIndex) null else s.legDistanceM) }
        return plans.appendIn(j, ctx, runId, run.first, renumbered, RevisionCause.BOOKING_REMOVED, listOf(bookingId)).revision
    }

    // ---- Zustandsübergang und Historie (WP-M2-25) --------------------------------------------------------------------------------------

    override fun transition(ctx: RequestContext, bookingId: UUID, to: BookingState, reason: BookingReason, expectedRevision: Long?): BookingDetail = ctxTx.run(ctx) { j ->
        if (isPassengerView(ctx)) throw ProblemException(ErrorCode.FORBIDDEN, "Fahrgäste ändern Buchungen nur über den Storno.")
        val r = row(j, bookingId) ?: throw ProblemException.notFound()
        authorizer.requireWrite(j, ctx, ScopedObject("BOOKING", r.id, ctx.tenant, r.serviceId))
        transitions.apply(j, ctx, bookingId, to, reason, expectedRevision)
        detail(j, ctx, bookingId, passengerOf = null)
    }

    private class HistRow(val revision: Long, val from: String?, val to: String, val reason: String, val text: String, val at: Instant, val kind: String, val actor: String, val correlation: UUID?, val links: HistoryLinks)

    override fun history(ctx: RequestContext, bookingId: UUID, view: HistoryView): List<BookingHistoryEntry> = ctxTx.run(ctx, readOnly = true) { j ->
        val passenger = isPassengerView(ctx)
        val r = row(j, bookingId) ?: throw ProblemException.notFound()
        authorize(j, ctx, r, if (passenger) passengerOf(j, ctx) else null)
        if (passenger && view == HistoryView.AUDIT) throw ProblemException(ErrorCode.FORBIDDEN, "Die Prüfersicht steht Fahrgästen nicht zur Verfügung.")
        j.query(
            "SELECT h.booking_revision, h.from_state, h.to_state, h.reason_code, c.passenger_text, h.occurred_at, h.actor_kind, h.actor_ref, h.correlation_id, h.attempt_id, h.offer_id, h.hold_id, h.quote_id, h.payment_intent_id " +
                "FROM taktovia.booking_status_history h JOIN taktovia.booking_status_reason c ON c.code = h.reason_code WHERE h.booking_id = ? ORDER BY h.booking_revision",
            { rs, _ -> HistRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant(), rs.getString(7), rs.getString(8), rs.getObject(9, UUID::class.java),
                HistoryLinks(rs.getObject(10, UUID::class.java), rs.getObject(11, UUID::class.java), rs.getObject(12, UUID::class.java), rs.getObject(13, UUID::class.java), rs.getObject(14, UUID::class.java))) }, bookingId,
        ).map { h ->
            val audit = view == HistoryView.AUDIT
            BookingHistoryEntry(h.revision, h.from, h.to, BookingReason.parse(h.reason)!!, h.text, h.at, h.kind, if (audit) h.actor else null, if (audit) h.correlation else null, if (audit) h.links else null)
        }
    }

    private companion object {
        const val OFFER_COLS = "actor_ref, service_id, quote_id, revision, pickup_earliest, pickup_latest, arrival_earliest, arrival_latest, cancellation_policy_ref, demand::text, pickup_stop_revision_id, dropoff_stop_revision_id"
        val OFFER = RowMapper { rs, _ ->
            OfferRow(rs.getString(1), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getLong(4), rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant(),
                rs.getTimestamp(7).toInstant(), rs.getTimestamp(8).toInstant(), rs.getString(9), rs.getString(10), rs.getObject(11, UUID::class.java), rs.getObject(12, UUID::class.java))
        }
        const val COLS = "id, booking_no, service_id, hold_id, state, revision, created_at, promise_snapshot::text, passenger_id, cancel_reason_code, cancel_cause_class"
        val ASSISTANCE_ROLES = setOf("dispatcher", "callcenter")
        val ROW = RowMapper { rs, _ ->
            Row(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getObject(3, UUID::class.java), rs.getObject(4, UUID::class.java), rs.getString(5), rs.getLong(6), rs.getTimestamp(7).toInstant(), rs.getString(8), rs.getObject(9, UUID::class.java), rs.getString(10), rs.getString(11))
        }
    }
}
