package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.BookingReason
import de.taktovia.bookingdispatch.api.ChangeBooking
import de.taktovia.bookingdispatch.api.ChangedBooking
import de.taktovia.bookingdispatch.api.ConfirmBooking
import de.taktovia.bookingdispatch.api.ConfirmedBooking
import de.taktovia.bookingdispatch.api.DispatchReason
import de.taktovia.bookingdispatch.api.OperatorBookings
import de.taktovia.bookingdispatch.api.RideOfferCommand
import de.taktovia.bookingdispatch.api.RideOfferRequests
import de.taktovia.bookingdispatch.api.RideOfferResult
import de.taktovia.bookingdispatch.api.RideOffers
import de.taktovia.farepayment.api.FareQuotes
import de.taktovia.platform.approval.FourEyes
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.authz.ObjectAuthorizer
import de.taktovia.platform.authz.ScopedObject
import de.taktovia.platform.authz.ServiceScopeAuthorizer
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.statemachine.BookingState
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Leitstellenbuchung (WP-M3-39) auf dem Kern der Fahrgastbuchung: Angebot und Hold laufen über dieselben Dienste, Bestätigung und Storno über [JdbcBookings] (gleiche Tarif-, Kapazitäts- und
 * Sicherheitsprüfung, keine eigene Kapazitätslogik). Dieses Objekt ergänzt Rolle und Dienstscope, die Bindung an den Fahrgast, Grund, Änderungsprotokoll, Audit und den atomaren Tausch.
 *
 * **Sperrreihenfolge des Tauschs:** beide Umläufe aufsteigend nach Kennung, dann Angebot und Hold des neuen Wunsches, Hold der bisherigen Buchung, die Buchung selbst. Das passt zu Hold
 * (Umlauf, Angebot), Bestätigung (Umlauf, Angebot, Hold) und Storno (Umlauf, Hold, Buchung): ein gleichzeitiger Storno durch den Fahrgast oder ein zweiter Disponent wartet am Umlauf, danach
 * gewinnt genau einer (die Revision der bisherigen Buchung entscheidet).
 */
internal class JdbcOperatorBookings(
    private val ctxTx: ContextTransactions, private val bookings: JdbcBookings, private val holds: JdbcCapacityHolds, private val quotes: FareQuotes, private val requests: RideOfferRequests,
    private val offers: RideOffers, private val clock: java.time.Clock, private val authorizer: ObjectAuthorizer = ServiceScopeAuthorizer,
) : OperatorBookings {
    private val json = JsonMapper.builder().build()
    private val changes get() = bookings.changes

    // ---- Berechtigung ---------------------------------------------------------------------------------------------------------------

    private fun requireRole(ctx: RequestContext) {
        if (ctx.actor.type != ActorType.HUMAN || ctx.roles.none { it in DISPATCH_ROLES }) throw ProblemException(ErrorCode.FORBIDDEN, "Die Leitstellenbuchung setzt die Rolle Disposition oder Callcenter voraus.")
    }

    private fun requireService(j: JdbcOperations, ctx: RequestContext, kind: String, id: UUID, serviceId: UUID) = authorizer.requireWrite(j, ctx, ScopedObject(kind, id, ctx.tenant, serviceId))

    /** Der Fahrgast hat im Mandanten ein aktives Konto (Arbeitsannahme OP-30: Legitimation = verifiziertes, aktives Konto; Buchung für nicht registrierte Fahrgäste ist offen). */
    private fun requirePassenger(j: JdbcOperations, passengerId: UUID, pointer: String = "/booking_subject/passenger_id") {
        val status = j.query("SELECT status FROM taktovia.passenger_private WHERE id = ?", { rs, _ -> rs.getString(1) }, passengerId).firstOrNull()
        if (status != "ACTIVE") throw ProblemException.invalid(Violation(pointer, ViolationCode.INVALID_VALUE, "Kein aktives Fahrgastkonto"))
    }

    private class Binding(val offerId: UUID, val serviceId: UUID, val actorRef: String, val passengerId: UUID)

    private fun bindingOfOffer(j: JdbcOperations, offerId: UUID): Binding? = j.query(
        "SELECT o.id, o.service_id, o.actor_ref, s.passenger_id FROM taktovia.ride_offer o JOIN taktovia.operator_offer_subject s ON s.tenant_id = o.tenant_id AND s.offer_id = o.id WHERE o.id = ?",
        { rs, _ -> Binding(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getObject(4, UUID::class.java)) }, offerId,
    ).firstOrNull()

    private fun bindingOfHold(j: JdbcOperations, holdId: UUID): Binding? = j.query(
        "SELECT o.id, o.service_id, o.actor_ref, s.passenger_id FROM taktovia.ride_offer o JOIN taktovia.operator_offer_subject s ON s.tenant_id = o.tenant_id AND s.offer_id = o.id WHERE o.hold_id = ?",
        { rs, _ -> Binding(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getObject(4, UUID::class.java)) }, holdId,
    ).firstOrNull()

    // ---- Angebot und Hold -----------------------------------------------------------------------------------------------------------

    override fun issueOffer(ctx: RequestContext, passengerId: UUID, cmd: RideOfferCommand): RideOfferResult {
        requireRole(ctx)
        ctxTx.run(ctx) { j ->
            requireService(j, ctx, "SERVICE", cmd.serviceId, cmd.serviceId)
            requirePassenger(j, passengerId)
        }
        val result = requests.request(ctx, cmd)
        val ids = result.offers.mapNotNull { it.offer?.id }
        if (ids.isNotEmpty()) ctxTx.run(ctx) { j ->
            for (id in ids) {
                j.update("INSERT INTO taktovia.operator_offer_subject(tenant_id, offer_id, passenger_id, actor_ref) VALUES (taktovia.current_tenant(), ?, ?, ?) ON CONFLICT (tenant_id, offer_id) DO NOTHING",
                    id, passengerId, FourEyes.refOf(ctx).value)
                // ein Subjektwechsel verlangt ein neues Angebot: ein bereits an einen anderen Fahrgast gebundenes Angebot wird nie umgebunden
                if (bindingOfOffer(j, id)?.passengerId != passengerId) throw ProblemException(ErrorCode.CONFLICT, "Das Angebot ist bereits an einen anderen Fahrgast gebunden; ein Subjektwechsel verlangt ein neues Angebot.")
            }
        }
        return result
    }

    override fun assertHoldAllowed(ctx: RequestContext, offerId: UUID) {
        ctxTx.run(ctx) { j ->
            val b = bindingOfOffer(j, offerId) ?: return@run
            if (b.actorRef != FourEyes.refOf(ctx).value) throw ProblemException.notFound()
            requireRole(ctx); requireService(j, ctx, "OFFER", b.offerId, b.serviceId); requirePassenger(j, b.passengerId)
        }
    }

    // ---- Anlegen --------------------------------------------------------------------------------------------------------------------

    override fun create(ctx: RequestContext, cmd: ConfirmBooking, reason: DispatchReason, reasonText: String?): ConfirmedBooking {
        requireRole(ctx)
        val text = reasonText(reason, reasonText)
        return ctxTx.run(ctx) { j ->
            val b = bindingOfHold(j, cmd.holdId) ?: throw ProblemException.notFound()   // ohne Bindung ist es kein Leitstellenangebot: wie nicht vorhanden
            if (b.actorRef != FourEyes.refOf(ctx).value) throw ProblemException.notFound()
            requireService(j, ctx, "OFFER", b.offerId, b.serviceId); requirePassenger(j, b.passengerId)
            val r = bookings.confirmIn(j, ctx, cmd, b.passengerId)
            if (!r.replayed) {
                changes.record(j, ctx, r.booking.id, "CREATE", reason.name, text, null, snapshotOf(j, r.booking.id), null, null)
                changes.audit(j, ctx, AuditActions.DISPATCH_BOOKING_CREATED, r.booking.id, mapOf("reason_code" to reason.name, "passenger_ref" to b.passengerId.toString()))
            }
            r
        }
    }

    // ---- Ändern (atomarer Tausch) ---------------------------------------------------------------------------------------------------

    override fun change(ctx: RequestContext, bookingId: UUID, cmd: ChangeBooking): ChangedBooking {
        requireRole(ctx)
        val text = reasonText(cmd.reason, cmd.reasonText)
        try {
            return ctxTx.run(ctx) { j -> changeIn(j, ctx, bookingId, cmd, text) }
        } catch (e: ProblemException) {
            // Scheitert der Tausch (Konflikt, veraltete Revision, nicht bedienbar), bleibt die bisherige Buchung unverändert, und die für den Wunsch gehaltene Kapazität wird freigegeben (nichts verwaist).
            // Nicht freigegeben wird, wenn nur eine Eingabe fehlt (422: z. B. Zustimmung) oder der Aufrufer nichts darf (403/404): der Disponent wiederholt mit demselben Hold.
            if (e.code !in KEEP_HOLD) releaseQuietly(ctx, cmd.newHoldId)
            throw e
        }
    }

    private fun changeIn(j: JdbcOperations, ctx: RequestContext, bookingId: UUID, cmd: ChangeBooking, reasonText: String?): ChangedBooking {
        val pre = bookings.row(j, bookingId) ?: throw ProblemException.notFound()
        requireService(j, ctx, "BOOKING", pre.id, pre.serviceId)
        val newBinding = bindingOfHold(j, cmd.newHoldId) ?: throw ProblemException.notFound()
        if (newBinding.actorRef != FourEyes.refOf(ctx).value) throw ProblemException.notFound()
        // Subjektwechsel verlangt ein neues Angebot; Dienstwechsel ist keine Änderung dieser Buchung
        if (pre.passengerId == null || newBinding.passengerId != pre.passengerId) throw ProblemException.invalid(Violation("/new_hold_id", ViolationCode.INVALID_VALUE, "Das Angebot gehört zu einem anderen Fahrgast; ein Subjektwechsel verlangt ein neues Angebot"))
        if (newBinding.serviceId != pre.serviceId) throw ProblemException.invalid(Violation("/new_hold_id", ViolationCode.INVALID_VALUE, "Das Angebot gehört zu einem anderen Dienst"))
        requirePassenger(j, newBinding.passengerId)
        // Wiederholung desselben Tauschs: die Folgebuchung des Holds besteht bereits und gehört zu einem Änderungseintrag dieser Buchung
        j.query("SELECT bc.id, bc.new_booking_id FROM taktovia.booking_change bc JOIN taktovia.booking nb ON nb.tenant_id = bc.tenant_id AND nb.id = bc.new_booking_id WHERE bc.booking_id = ? AND nb.hold_id = ?",
            { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) }, bookingId, cmd.newHoldId).firstOrNull()?.let { (changeId, newId) ->
            val newPrice = snapshotNode(j, newId)["gross_minor"].asLong() != snapshotNodeOf(pre.snapshot)["gross_minor"].asLong()
            return ChangedBooking(bookings.detail(j, ctx, newId, null), bookings.detail(j, ctx, bookingId, null), changeId, replayed = true, priceChanged = newPrice, timeChanged = false)
        }
        // Wunsch gegen Preis und Zeit der bisherigen Zusage (Vorprüfung ohne Sperre; maßgeblich ist der Stand unter den Sperren)
        val offer = bookings.offerOfHold(j, cmd.newHoldId, lock = false) ?: throw ProblemException.notFound()
        val newQuote = quotes.getIn(j, offer.quoteId) ?: throw ProblemException.notFound()
        val old = snapshotNodeOf(pre.snapshot)
        val oldPickup = OffsetDateTime.parse(old["pickup_earliest"].asString()).toInstant()
        val priceChanged = newQuote.payAtPlatformMinor != old["gross_minor"].asLong()
        val timeChanged = Duration.between(oldPickup, offer.pickupEarliest).abs() >= Duration.ofMinutes(OperatorBookings.CONSENT_TIME_SHIFT_MIN)
        if ((priceChanged || timeChanged) && cmd.consent == null)
            throw ProblemException.invalid(Violation("/passenger_consent", ViolationCode.REQUIRED, "Preis oder Abholzeit ändern sich: die Zustimmung des Fahrgasts ist nachzuweisen"))
        // Sperren: beide Umläufe aufsteigend, dann Angebot und Hold des neuen Wunsches, Hold und Buchung der bisherigen Zusage
        val oldRun = j.query("SELECT run_id FROM taktovia.capacity_hold WHERE id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, pre.holdId).first()
        val newRun = j.query("SELECT run_id FROM taktovia.capacity_hold WHERE id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, cmd.newHoldId).first()
        for (run in listOf(oldRun, newRun).distinct().sortedBy { it.toString() }) j.query("SELECT 1 FROM taktovia.vehicle_run WHERE id = ? FOR UPDATE", { rs, _ -> rs.getInt(1) }, run)
        bookings.offerOfHold(j, cmd.newHoldId, lock = true)
        j.query("SELECT 1 FROM taktovia.capacity_hold WHERE id = ? FOR UPDATE", { rs, _ -> rs.getInt(1) }, cmd.newHoldId)
        j.query("SELECT 1 FROM taktovia.capacity_hold WHERE id = ? FOR UPDATE", { rs, _ -> rs.getInt(1) }, pre.holdId)
        val cur = j.query("SELECT state, revision FROM taktovia.booking WHERE id = ? FOR UPDATE", { rs, _ -> rs.getString(1) to rs.getLong(2) }, bookingId).first()
        if (cur.second != cmd.ifMatch) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Die Buchung wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        if (cur.first != BookingState.CONFIRMED.name && cur.first != BookingState.CONFIRMING.name)
            throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Eine Buchung im Zustand ${cur.first} kann nicht geändert werden.")
        if ((bookings.legState(j, bookingId) ?: "PLANNED") != "PLANNED")
            throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Die Fahrt dieser Buchung hat bereits begonnen und kann nicht mehr geändert werden.")
        // 1. Folgebuchung (Betrag, Revision des Angebots, Ablauf des Holds, Kapazität: dieselben Prüfungen wie bei der Fahrgastbuchung)
        val created = bookings.confirmIn(j, ctx, ConfirmBooking(cmd.newHoldId, cmd.newOfferRevision, cmd.maxGrossMinor, cmd.currency), newBinding.passengerId)
        // 2. bisherige Buchung stornieren (Hold frei, Plan ohne die Buchung); der Grund ist der Betriebsstorno, der Fahrgast wird über die Folgebuchung informiert
        bookings.cancelLocked(j, Caller.of(ctx), "OPERATOR", bookingId, pre.holdId, oldRun, cur.first == BookingState.CONFIRMED.name, BookingReason.OPERATOR_CANCELLATION, cmd.ifMatch)
        // 3. Änderungseintrag (Akteur, Grund, alte und neue Zusage) und Audit
        val consent = cmd.consent?.let { """{"channel":"${it.channel}","obtained_at":"${it.obtainedAt}"}""" }
        val changeId = changes.record(j, ctx, bookingId, "REPLACE_OFFER", cmd.reason.name, reasonText, pre.snapshot, snapshotOf(j, created.booking.id), created.booking.id, consent)
        changes.audit(j, ctx, AuditActions.DISPATCH_BOOKING_CHANGED, bookingId, mapOf(
            "reason_code" to cmd.reason.name, "new_booking_id" to created.booking.id.toString(), "price_changed" to priceChanged, "time_changed" to timeChanged, "consent_channel" to cmd.consent?.channel,
        ))
        return ChangedBooking(created.booking, bookings.detail(j, ctx, bookingId, null), changeId, replayed = false, priceChanged = priceChanged, timeChanged = timeChanged)
    }

    /** Gibt den Hold des gescheiterten Wunsches über den Angebotsdienst frei (eigenes Angebot, aktuelle Revision); Fehler dabei ändern den ursprünglichen Fehler nicht. */
    private fun releaseQuietly(ctx: RequestContext, holdId: UUID) {
        runCatching {
            val target = ctxTx.run(ctx, readOnly = true) { j ->
                val b = bindingOfHold(j, holdId) ?: return@run null
                if (b.actorRef != FourEyes.refOf(ctx).value) return@run null
                b.offerId to j.queryForObject("SELECT revision FROM taktovia.ride_offer WHERE id = ?", Long::class.java, b.offerId)!!
            } ?: return
            offers.release(ctx, target.first, target.second)
        }
    }

    // ---- Hilfen ---------------------------------------------------------------------------------------------------------------------

    private fun reasonText(reason: DispatchReason, raw: String?): String? {
        val t = changes.text(raw, "/reason_text")
        if (reason == DispatchReason.OTHER && (t == null || t.length < 3)) throw ProblemException.invalid(Violation("/reason_text", ViolationCode.REQUIRED, "Bei „Sonstiges“ ist ein Freitext Pflicht"))
        return t
    }

    private fun snapshotOf(j: JdbcOperations, bookingId: UUID): String = j.queryForObject("SELECT promise_snapshot::text FROM taktovia.booking WHERE id = ?", String::class.java, bookingId)!!
    private fun snapshotNode(j: JdbcOperations, bookingId: UUID) = json.readTree(snapshotOf(j, bookingId))
    private fun snapshotNodeOf(text: String) = json.readTree(text)

    private companion object {
        val DISPATCH_ROLES = setOf("dispatcher", "callcenter")
        /** Fehler, bei denen der neue Hold bestehen bleibt: fehlende Eingabe (422) und fehlende Berechtigung (403, 404). */
        val KEEP_HOLD = setOf(ErrorCode.VALIDATION_FAILED, ErrorCode.FORBIDDEN, ErrorCode.NOT_FOUND)
    }
}
