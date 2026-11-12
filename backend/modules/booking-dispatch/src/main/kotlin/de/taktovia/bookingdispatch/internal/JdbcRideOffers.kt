package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.HoldOutcome
import de.taktovia.bookingdispatch.api.HoldRideOffer
import de.taktovia.bookingdispatch.api.HoldSummary
import de.taktovia.bookingdispatch.api.OfferState
import de.taktovia.bookingdispatch.api.OfferStop
import de.taktovia.bookingdispatch.api.PlaceHold
import de.taktovia.bookingdispatch.api.RejectionReason
import de.taktovia.bookingdispatch.api.ReserveRule
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.RideBookingKind
import de.taktovia.bookingdispatch.api.RideOfferHold
import de.taktovia.bookingdispatch.api.RideOfferView
import de.taktovia.bookingdispatch.api.RideOffers
import de.taktovia.bookingdispatch.api.RideTimeKind
import de.taktovia.bookingdispatch.api.RideTimeWindow
import de.taktovia.bookingdispatch.api.TimeWindow
import de.taktovia.config.api.EffectiveConfig
import de.taktovia.config.api.EffectiveRequest
import de.taktovia.config.api.EffectiveStatus
import de.taktovia.farepayment.api.FareQuotes
import de.taktovia.platform.approval.FourEyes
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.geo.GeoPoint
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.springframework.jdbc.core.JdbcOperations
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Angebotsobjekt, Hold und Freigabe (WP-M2-21/-22). **Sperrreihenfolge** überall gleich: zuerst die Zeile `vehicle_run` des gebundenen Umlaufs, dann die Zeile des Angebots, dann die
 * Hold-Zeile (Hold, Freigabe, Ablauf und Bestätigung benutzen dieselbe Reihenfolge, daher kein Deadlock). Fachliche Ablehnungen sind Ausnahmen ([ProblemException]) und rollen die ganze
 * Transaktion zurück: Hold, Angebotsrevision und Outbox-Ereignis entstehen gemeinsam oder gar nicht.
 */
internal class JdbcRideOffers(
    private val ctxTx: ContextTransactions, private val outbox: Outbox, private val quotes: FareQuotes, private val holds: JdbcCapacityHolds, private val config: EffectiveConfig?,
    private val clock: Clock, private val holdTtl: Duration,
) : RideOffers {

    private class Row(
        val id: UUID, val attemptId: UUID, val quoteId: UUID, val serviceId: UUID, val actorRef: String, val state: String, val bookingKind: String, val timeKind: String,
        val pickupEarliest: Instant, val pickupLatest: Instant, val arrivalEarliest: Instant, val arrivalLatest: Instant, val arrivalGraceMin: Int, val directSeconds: Int,
        val expiresAt: Instant, val cancellationPolicyRef: String, val demand: String, val pickupStopId: UUID?, val pickupStopRevisionId: UUID?, val dropoffStopId: UUID?,
        val dropoffStopRevisionId: UUID?, val runId: UUID?, val planRevision: Long?, val intervalFrom: Int?, val intervalTo: Int?, val holdId: UUID?, val holdScope: String?, val revision: Long,
    )

    private fun read(j: JdbcOperations, offerId: UUID, lock: Boolean): Row? =
        if (lock) j.query("SELECT $COLS FROM taktovia.ride_offer WHERE id = ? FOR UPDATE", ROW, offerId).firstOrNull() else j.query("SELECT $COLS FROM taktovia.ride_offer WHERE id = ?", ROW, offerId).firstOrNull()

    /** Das Angebot gehört dem Akteur, der es angefragt hat; ein fremdes (anderer Fahrgast, anderer Mandant per RLS) und ein unbekanntes sind gleichermaßen `404`. */
    private fun owned(j: JdbcOperations, ctx: RequestContext, offerId: UUID, lock: Boolean): Row {
        val row = read(j, offerId, lock) ?: throw ProblemException.notFound()
        if (row.actorRef != FourEyes.refOf(ctx).value) throw ProblemException.notFound()
        return row
    }

    private class HoldRow(val id: UUID, val state: String, val expiresAt: Instant, val vector: ResourceVector, val expired: Boolean)

    private fun holdRow(j: JdbcOperations, holdId: UUID): HoldRow? = j.query(
        "SELECT id, state, expires_at, capacity_vector::text, expires_at <= clock_timestamp() FROM taktovia.capacity_hold WHERE id = ?",
        { rs, _ -> HoldRow(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getTimestamp(3).toInstant(), ResourceVector.parse(rs.getString(4)), rs.getBoolean(5)) }, holdId,
    ).firstOrNull()

    /** Zustand eines Holds bei jedem Zugriff mit Serverzeit: ein `HELD` Hold ab `now >= expires_at` gilt als `EXPIRED`, auch bevor der Expiry-Job lief. */
    private fun summary(h: HoldRow) = HoldSummary(h.id, if (h.state == "HELD" && h.expired) "EXPIRED" else h.state, h.expiresAt, h.vector)

    override fun get(ctx: RequestContext, offerId: UUID): RideOfferView = ctxTx.run(ctx, readOnly = true) { j ->
        val row = owned(j, ctx, offerId, lock = false)
        val quote = quotes.getIn(j, row.quoteId) ?: throw ProblemException.notFound()
        val hold = row.holdId?.let { holdRow(j, it) }?.let { summary(it) }
        val now = clock.instant()
        val state = when {
            row.state == "OFFERED" && !now.isBefore(row.expiresAt) -> OfferState.EXPIRED
            row.state == "HELD" && hold?.state == "EXPIRED" -> OfferState.EXPIRED
            else -> OfferState.valueOf(row.state)
        }
        RideOfferView(
            row.id, row.attemptId, row.serviceId, state, RideBookingKind.valueOf(row.bookingKind), RideTimeKind.valueOf(row.timeKind),
            RideTimeWindow(row.pickupEarliest, row.pickupLatest), RideTimeWindow(row.arrivalEarliest, row.arrivalLatest), row.arrivalGraceMin, row.directSeconds, row.expiresAt,
            row.planRevision, quote, row.cancellationPolicyRef, ResourceVector.parse(row.demand), stop(row.pickupStopId, row.pickupStopRevisionId), stop(row.dropoffStopId, row.dropoffStopRevisionId), hold, row.revision,
        )
    }

    private fun stop(id: UUID?, revisionId: UUID?) = if (id != null && revisionId != null) OfferStop(id, revisionId) else null

    override fun hold(ctx: RequestContext, offerId: UUID, request: HoldRideOffer): RideOfferHold = ctxTx.run(ctx) { j ->
        // 1. Zeile des Angebots ohne Sperre lesen (Umlauf und Besitzer sind unveränderlich), dann Umlauf, dann Angebot sperren
        val pre = owned(j, ctx, offerId, lock = false)
        pre.runId?.let { runId -> j.query("SELECT 1 FROM taktovia.vehicle_run WHERE id = ? FOR UPDATE", { rs, _ -> rs.getInt(1) }, runId) }
        val offer = owned(j, ctx, offerId, lock = true)
        val scope = sha256(FourEyes.refOf(ctx).value + "\u0000" + request.idempotencyKey)
        // 2. Wiederholung desselben Befehls: der ursprüngliche Hold, ohne erneute Ablauf-, ETag- oder Preisprüfung (ein angenommener Hold erscheint nie nachträglich als abgelaufen)
        if (offer.holdId != null && offer.holdScope == scope) {
            val hold = holdRow(j, offer.holdId)!!
            return@run RideOfferHold(summary(hold), offer.revision, replayed = true)
        }
        // 3. abgelaufen: `now >= expires_at` (Serverzeit), vor ETag und Preis
        val now = clock.instant()
        if (offer.state == "EXPIRED" || (offer.state == "OFFERED" && !now.isBefore(offer.expiresAt))) throw ProblemException(ErrorCode.OFFER_EXPIRED, "Das Angebot ist abgelaufen; bitte neu suchen.")
        // 4. veraltete Angebotsrevision
        if (offer.revision != request.ifMatch) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Das Angebot wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        // 5. Preisstand: der akzeptierte Betrag muss dem eingefrorenen Preis entsprechen
        quotes.requireAcceptedAmount(j, offer.quoteId, request.maxGrossMinor, request.currency, now)
        // 6. Zustand
        if (offer.state != "OFFERED") throw ProblemException(ErrorCode.INVALID_STATE_TRANSITION, "Für dieses Angebot wurde bereits eine Kapazität gehalten oder es wurde abgelehnt.")
        val runId = offer.runId ?: throw ProblemException(ErrorCode.CAPACITY_UNAVAILABLE, "Dem Angebot ist keine Fahrt zugeordnet; bitte neu suchen.")
        // 7. Kapazität halten (Revisionsabgleich, Vektor je Abschnitt und Dimension, Einsatzfähigkeit über den Leseport, alles unter dem Lock des Umlaufs)
        val cmd = PlaceHold(
            runId = runId, expectedRunRevision = offer.planRevision!!, serviceId = offer.serviceId, window = TimeWindow(offer.pickupEarliest, offer.arrivalLatest),
            intervalFrom = offer.intervalFrom!!, intervalTo = offer.intervalTo!!, demand = ResourceVector.parse(offer.demand), reserve = reserve(ctx, j, offer.serviceId, now),
            reference = "offer:${offer.id}", ttl = holdTtl, locations = locations(j, offer),
        )
        val placed = when (val o = holds.placeIn(j, ctx, cmd)) {
            is HoldOutcome.Placed -> o
            is HoldOutcome.Rejected -> throw when (o.reason) {
                // die Planrevision hat sich seit dem Angebot geändert: das Angebot ist veraltet, eine neue Suche plant neu (kein stilles Übernehmen)
                RejectionReason.REVISION_CONFLICT -> ProblemException(ErrorCode.REQUOTE_REQUIRED, "Der Fahrtenplan hat sich seit dem Angebot geändert; bitte neu suchen.")
                else -> ProblemException(ErrorCode.CAPACITY_UNAVAILABLE, "Für dieses Angebot ist keine Kapazität mehr verfügbar.")
            }
        }
        // 8. Angebot fortschreiben: genau eine Revision weiter, Hold-Bezug einmalig gesetzt (bedingt auf die gelesene Revision)
        val updated = j.update(
            "UPDATE taktovia.ride_offer SET state = 'HELD', hold_id = ?, hold_scope = ?, revision = revision + 1, updated_at = clock_timestamp() WHERE id = ? AND revision = ? AND state = 'OFFERED'",
            placed.hold.id, scope, offer.id, offer.revision,
        )
        check(updated == 1) { "Angebot nach dem Hold nicht fortschreibbar" }
        RideOfferHold(HoldSummary(placed.hold.id, "HELD", placed.hold.expiresAt, placed.hold.vector), offer.revision + 1, replayed = placed.replayed)
    }

    override fun release(ctx: RequestContext, offerId: UUID, ifMatch: Long): RideOfferHold = ctxTx.run(ctx) { j ->
        val pre = owned(j, ctx, offerId, lock = false)
        val holdId = pre.holdId ?: throw ProblemException.notFound()   // ohne Hold gibt es nichts freizugeben
        // Umlauf zuerst (über den Hold), dann Angebot; `releaseIn` sperrt Umlauf und Hold in dieser Reihenfolge, daher hier erst der Umlauf des Holds
        val runId = j.query("SELECT run_id FROM taktovia.capacity_hold WHERE id = ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, holdId).first()
        j.query("SELECT 1 FROM taktovia.vehicle_run WHERE id = ? FOR UPDATE", { rs, _ -> rs.getInt(1) }, runId)
        val offer = owned(j, ctx, offerId, lock = true)
        val current = holdRow(j, holdId)!!
        // bereits freigegebener oder abgelaufener Hold: idempotent, unabhängig vom ETag; ein verbrauchter Hold bleibt unverändert (409)
        if (current.state == "RELEASED" || current.state == "EXPIRED") return@run RideOfferHold(summary(current), offer.revision, replayed = true)
        if (current.state == "HELD" && offer.revision != ifMatch) throw ProblemException(ErrorCode.REVISION_MISMATCH, "Das Angebot wurde zwischenzeitlich geändert; aktuelle Revision lesen.")
        val view = holds.releaseIn(j, ctx, holdId, "CLIENT_RELEASE")
        if (view.state == "RELEASED") {
            j.update("UPDATE taktovia.ride_offer SET state = 'DECLINED', revision = revision + 1, updated_at = clock_timestamp() WHERE id = ? AND state = 'HELD'", offer.id)
            return@run RideOfferHold(HoldSummary(view.id, "RELEASED", view.expiresAt, view.vector), offer.revision + 1, replayed = false)
        }
        // am Zeitpunkt der Freigabe war der Hold bereits abgelaufen: `releaseIn` hat ihn (und das Angebot) verfallen lassen
        RideOfferHold(HoldSummary(view.id, view.state, view.expiresAt, view.vector), offer.revision + 1, replayed = false)
    }

    /** `min_capacity_reserve` (Plätze der Dimension Sitz) aus der wirksamen Konfiguration; ohne Wert oder ohne Konfigurationszugriff keine Reserve (nie ein erfundener Wert). */
    private fun reserve(ctx: RequestContext, j: JdbcOperations, serviceId: UUID, now: Instant): ReserveRule {
        val cfg = config ?: return ReserveRule.NONE
        val item = cfg.resolveInTransaction(j, ctx, EffectiveRequest(parameter = "min_capacity_reserve", serviceId = serviceId, at = now)).items.firstOrNull()
        val seats = (item?.value as? Number)?.toInt()
        return if (item == null || item.status != EffectiveStatus.EFFECTIVE || seats == null) ReserveRule.NONE else ReserveRule(seats = seats)
    }

    /** Orte der Fahrt für die Gebietsprüfung einer gebietsbezogenen Dienstzuordnung: die Punkte der gebundenen Haltepunktrevisionen (Koordinaten des Fahrgasts werden nie gespeichert). */
    private fun locations(j: JdbcOperations, offer: Row): List<GeoPoint> = listOfNotNull(offer.pickupStopRevisionId, offer.dropoffStopRevisionId).mapNotNull { rev ->
        j.query("SELECT ST_X(point), ST_Y(point) FROM taktovia.stop_revision WHERE id = ?", { rs, _ -> GeoPoint(rs.getDouble(1), rs.getDouble(2)) }, rev).firstOrNull()
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val COLS = "id, attempt_id, quote_id, service_id, actor_ref, state, booking_kind, time_kind, pickup_earliest, pickup_latest, arrival_earliest, arrival_latest, arrival_grace_min, direct_seconds, " +
            "expires_at, cancellation_policy_ref, demand::text, pickup_stop_id, pickup_stop_revision_id, dropoff_stop_id, dropoff_stop_revision_id, run_id, plan_revision, interval_from, interval_to, " +
            "hold_id, hold_scope, revision"
        val ROW = org.springframework.jdbc.core.RowMapper { rs, _ ->
            Row(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java), rs.getObject(4, UUID::class.java), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getTimestamp(9).toInstant(), rs.getTimestamp(10).toInstant(), rs.getTimestamp(11).toInstant(), rs.getTimestamp(12).toInstant(), rs.getInt(13), rs.getInt(14),
                rs.getTimestamp(15).toInstant(), rs.getString(16), rs.getString(17), rs.getObject(18, UUID::class.java), rs.getObject(19, UUID::class.java), rs.getObject(20, UUID::class.java),
                rs.getObject(21, UUID::class.java), rs.getObject(22, UUID::class.java), rs.getObject(23) as Long?, rs.getObject(24) as Int?, rs.getObject(25) as Int?,
                rs.getObject(26, UUID::class.java), rs.getString(27)?.trim(), rs.getLong(28))
        }
    }
}
