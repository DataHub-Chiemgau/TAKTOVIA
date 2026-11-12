package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.AttemptOutcome
import de.taktovia.bookingdispatch.api.CandidateGate
import de.taktovia.bookingdispatch.api.CandidateGateResult
import de.taktovia.bookingdispatch.api.CandidateSearchRequest
import de.taktovia.bookingdispatch.api.DirectDistance
import de.taktovia.bookingdispatch.api.IssuedOffer
import de.taktovia.bookingdispatch.api.OfferAssignment
import de.taktovia.bookingdispatch.api.OfferPolicy
import de.taktovia.bookingdispatch.api.OfferStop
import de.taktovia.bookingdispatch.api.DirectTravelTime
import de.taktovia.bookingdispatch.api.RejectionCode
import de.taktovia.bookingdispatch.api.RideBookingKind
import de.taktovia.bookingdispatch.api.RideOfferCommand
import de.taktovia.bookingdispatch.api.RideOfferParameters
import de.taktovia.bookingdispatch.api.RideOfferProposal
import de.taktovia.bookingdispatch.api.RideOfferRequests
import de.taktovia.bookingdispatch.api.RideOfferResult
import de.taktovia.bookingdispatch.api.RideRejection
import de.taktovia.bookingdispatch.api.RideTimeKind
import de.taktovia.bookingdispatch.api.RideTimeWindow
import de.taktovia.config.api.EffectiveConfig
import de.taktovia.config.api.EffectiveRequest
import de.taktovia.config.api.EffectiveStatus
import de.taktovia.farepayment.api.FareRequest
import de.taktovia.farepayment.api.FareQuotes
import de.taktovia.farepayment.api.QuoteOutcome
import de.taktovia.farepayment.api.QuoteRequest
import de.taktovia.platform.approval.FourEyes
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.time.ZonedMoments
import de.taktovia.servicegeo.api.ServicegeoApi
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Anfrageerfassung (WP-M2-11/-12/-13). Die Prüfungen lesen über die Ports von `service-geo` und die wirksame Konfiguration (je eine eigene, lesende Transaktion); geschrieben wird
 * genau einmal: `travel_attempt` und Ereignis in **einer** Transaktion. Der Wiederholungsschutz ist ein Eindeutigkeitsschlüssel in der Datenbank (`request_scope` = Hash aus
 * Akteur und `Idempotency-Key`): auch 100 gleichzeitige Wiederholungen erzeugen genau einen Versuch und liefern dasselbe gespeicherte Ergebnis.
 */
internal class JdbcRideOfferRequests(
    private val ctxTx: ContextTransactions, private val outbox: Outbox, private val geo: ServicegeoApi, private val config: EffectiveConfig, private val clock: Clock,
    private val travelTime: DirectTravelTime, private val gate: CandidateGate, private val eligibility: EligibilityCheck,
    private val quotes: FareQuotes? = null, private val policy: OfferPolicy = OfferPolicy(), private val distance: DirectDistance = de.taktovia.bookingdispatch.api.HaversineDistance(),
) : RideOfferRequests {
    private val log = LoggerFactory.getLogger("taktovia.booking.rideoffer")

    override fun request(ctx: RequestContext, cmd: RideOfferCommand): RideOfferResult {
        val desired = validate(cmd)
        val scope = sha256("${ctx.actor.subject}\u0000${cmd.idempotencyKey}")
        val fingerprint = fingerprint(cmd, desired)
        existing(ctx, scope)?.let { stored -> stored.replayIfFinal(fingerprint)?.let { return withIssuedOffer(ctx, it) } }

        val now = clock.instant()
        val availability = geo.availability.at(ctx, cmd.serviceId, now)   // unbekannter oder fremder Dienst: 404
        val zone = ZoneId.of(availability.timezone)
        val params = parameters(ctx, cmd.serviceId, now)
        val direct = travelTime.seconds(cmd.origin.point(), cmd.destination.point())

        val time = RideTimeRules.evaluate(now, zone, cmd.timeKind, desired, params, direct)
        val causes = mutableListOf<RejectionCode>()
        val nominal = nominalWindows(cmd.timeKind, desired, params, direct)
        val (pickup, arrival) = when (time) {
            is TimeDecision.Ok -> time.pickup to time.arrival
            is TimeDecision.Reject -> { causes += time.code; nominal }
        }
        val check = eligibility.check(ctx, EligibilityRequest(cmd.serviceId, cmd.origin, cmd.destination, pickup, arrival, cmd.needsWheelchair(), params))
        causes += check.causes

        var decision = RejectionCode.decide(causes)
        var outcome = if (decision == null) AttemptOutcome.OFFERED else AttemptOutcome.REJECTED
        var assignment: OfferAssignment? = null
        if (decision == null) {
            val found = try {
                gate.check(ctx, CandidateSearchRequest(cmd.serviceId, cmd.demand(), check.pickupStopId, pickup, arrival))
            } catch (e: ProblemException) { throw e } catch (e: Exception) {
                // technische Störung (z. B. Routing-Timeout): Versuch erfassen, nie als fachliche Ablehnung zählen
                log.warn("Kandidatensuche technisch gescheitert ({})", e.javaClass.simpleName)
                store(ctx, cmd, scope, fingerprint, desired, now, time.bookingKindOrNull(), AttemptOutcome.TECHNICAL_ERROR, null, emptyList(), check)
                throw ProblemException(ErrorCode.SERVICE_UNAVAILABLE, "Die Anfrage konnte technisch nicht bearbeitet werden; sie kann mit demselben Idempotency-Key wiederholt werden.")
            }
            if (found is CandidateGateResult.Assigned) assignment = found.assignment
            if (found is CandidateGateResult.NoCandidate) {
                decision = RejectionCode.decide(found.causes)
                if (decision == null) {
                    store(ctx, cmd, scope, fingerprint, desired, now, time.bookingKindOrNull(), AttemptOutcome.TECHNICAL_ERROR, null, emptyList(), check)
                    throw ProblemException(ErrorCode.SERVICE_UNAVAILABLE, "Die Anfrage konnte technisch nicht bearbeitet werden.")
                }
                outcome = AttemptOutcome.REJECTED
            }
        }
        val kind = (time as? TimeDecision.Ok)?.bookingKind ?: RideTimeRules.bookingKind(now, cmd.timeKind, desired, params, direct)
        val offers = if (outcome == AttemptOutcome.OFFERED)
            listOf(RideOfferProposal(cmd.serviceId, kind, cmd.timeKind, pickup, arrival, params.arrivalGraceMin, direct)) else emptyList()
        val result = store(ctx, cmd, scope, fingerprint, desired, now, kind, outcome, decision, offers, check, assignment, params)
        if (result.outcome == AttemptOutcome.TECHNICAL_ERROR && outcome == AttemptOutcome.OFFERED) {
            // Preisbildung nicht möglich (kein wirksamer Tarif, Engine-Ablehnung): nie ein Angebot ohne Preis, nie als fachliche Ablehnung gezählt
            throw ProblemException(ErrorCode.SERVICE_UNAVAILABLE, "Für die Anfrage konnte kein Preis gebildet werden (kein wirksamer Tarif); sie kann später mit demselben Idempotency-Key wiederholt werden.")
        }
        return result
    }

    // ---- Eingabeprüfung ------------------------------------------------------------------------------------------------------------

    private fun validate(cmd: RideOfferCommand): Instant {
        val v = mutableListOf<Violation>()
        if (cmd.passengers < 1) v += Violation("/passengers", ViolationCode.BELOW_MINIMUM) else if (cmd.passengers > 99) v += Violation("/passengers", ViolationCode.ABOVE_MAXIMUM)
        if (cmd.wheelchairSpaces < 0) v += Violation("/wheelchair_spaces", ViolationCode.BELOW_MINIMUM) else if (cmd.wheelchairSpaces > 20) v += Violation("/wheelchair_spaces", ViolationCode.ABOVE_MAXIMUM)
        if (cmd.wheelchairSpaces > cmd.passengers && cmd.passengers >= 1) v += Violation("/wheelchair_spaces", ViolationCode.INVALID_VALUE, "Mehr Rollstuhlplätze als Personen")
        if (!IDEMPOTENCY_KEY.matches(cmd.idempotencyKey)) v += Violation("/\$header/Idempotency-Key", ViolationCode.INVALID_FORMAT)
        if (cmd.channel !in CHANNELS) v += Violation("/channel", ViolationCode.NOT_IN_ENUM)
        for ((name, p) in listOf("origin" to cmd.origin, "destination" to cmd.destination)) {
            if (p.latitude !in -90.0..90.0 || p.longitude !in -180.0..180.0) v += Violation("/$name/point", ViolationCode.INVALID_VALUE)
        }
        val moment = ZonedMoments.parse(cmd.desiredAt, cmd.timeZone, "/desired/desired_at", "/desired/time_zone")
        v += moment.violations
        if (v.isNotEmpty()) throw ProblemException.invalid(*v.toTypedArray())
        return moment.value!!.instant
    }

    // ---- Parameter -----------------------------------------------------------------------------------------------------------------

    private fun parameters(ctx: RequestContext, serviceId: UUID, now: Instant): RideOfferParameters = ctxTx.run(ctx, readOnly = true) { j ->
        val view = config.resolveInTransaction(j, ctx, de.taktovia.config.api.EffectiveRequest(parameters = RideOfferParameters.NAMES.toSet(), serviceId = serviceId, at = now))
        val byName = view.items.associateBy { it.parameter }
        val defaulted = mutableSetOf<String>()
        fun value(name: String, default: Int): Int {
            val item = byName[name]
            val v = (item?.value as? Number)?.toInt()
            if (item == null || item.status != EffectiveStatus.EFFECTIVE || v == null) { defaulted += name; return default }
            return v
        }
        val d = RideOfferParameters.DEFAULTS
        // gebundene Konfigurationsrevision (Preisbindung): die Revision, aus der `pickup_window_min` stammt, sonst die erste gelieferte (kein erfundener Wert: sonst `null`)
        val revisionId = (byName["pickup_window_min"]?.revisionId) ?: RideOfferParameters.NAMES.firstNotNullOfOrNull { byName[it]?.revisionId }
        RideOfferParameters(
            value("min_booking_lead_min", d.minBookingLeadMin), value("max_booking_horizon_days", d.maxBookingHorizonDays), value("pickup_window_min", d.pickupWindowMin),
            value("arrival_grace_min", d.arrivalGraceMin), value("stop_walk_max_m", d.stopWalkMaxM), defaulted, revisionId,
        )
    }

    // ---- Fenster ohne Fristprüfung (nur für die Zulässigkeit abgelehnter Anfragen) ---------------------------------------------------

    private fun nominalWindows(kind: RideTimeKind, desired: Instant, p: RideOfferParameters, directSeconds: Int): Pair<RideTimeWindow, RideTimeWindow> {
        val w = Duration.ofMinutes(p.pickupWindowMin.toLong()); val d = Duration.ofSeconds(directSeconds.toLong())
        return if (kind == RideTimeKind.DEPARTURE) RideTimeWindow(desired, desired.plus(w)) to RideTimeWindow(desired.plus(d), desired.plus(w).plus(d))
        else RideTimeWindow(desired.minus(w).minus(d), desired.minus(d)) to RideTimeWindow(desired.minus(w), desired)
    }

    private fun TimeDecision.bookingKindOrNull(): RideBookingKind? = (this as? TimeDecision.Ok)?.bookingKind

    // ---- Wiederholung und Speicherung ----------------------------------------------------------------------------------------------

    private class Stored(val attemptId: UUID, val outcome: String, val fingerprint: String, val result: String?) {
        /** Ergebnis der Wiederholung; `null` = technischer Fehler beim ersten Mal, der Retry führt neu aus (derselbe Versuch). Anderer Inhalt: 409. */
        fun replayIfFinal(fp: String): RideOfferResult? {
            if (fingerprint != fp) throw ProblemException(ErrorCode.IDEMPOTENCY_CONFLICT, "Der Idempotency-Key wurde bereits mit einer anderen Anfrage verwendet.")
            if (outcome == "TECHNICAL_ERROR" || result == null) return null
            return decode(attemptId, result)
        }
    }

    private fun existing(ctx: RequestContext, scope: String): Stored? = ctxTx.run(ctx, readOnly = true) { j -> load(j, scope) }

    private fun load(j: JdbcOperations, scope: String): Stored? =
        j.query("SELECT t.id, t.outcome, t.request_fingerprint, t.result::text FROM taktovia.travel_attempt t WHERE t.request_scope = ?",
            { rs, _ -> Stored(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3).trim(), rs.getString(4)) }, scope).singleOrNull()

    private fun store(
        ctx: RequestContext, cmd: RideOfferCommand, scope: String, fingerprint: String, desired: Instant, now: Instant, kind: RideBookingKind?, outcome: AttemptOutcome,
        decision: de.taktovia.bookingdispatch.api.RejectionDecision?, offers: List<RideOfferProposal>, check: EligibilityResult,
        assignment: OfferAssignment? = null, params: RideOfferParameters? = null,
    ): RideOfferResult = ctxTx.run(ctx) { j ->
        val id = UUID.randomUUID()
        val rejection = decision?.let { RideRejection(it.primary, it.secondary) }
        val resultJson = encode(outcome, kind, offers, rejection)
        // Mit Preisbildung entsteht der Versuch zunächst als `TECHNICAL_ERROR` (ohne Ergebnis) und wird erst nach Quote und Angebot zu `OFFERED` (der Wächter am Versuch erlaubt genau diesen
        // einen Übergang): scheitert die Preisbildung, bleibt es bei dem technischen Fehler (Kennzahl: keine fachliche Ablehnung, kein Angebot ohne Preis).
        val priced = quotes != null && outcome == AttemptOutcome.OFFERED && offers.isNotEmpty() && params != null
        val inserted = j.query(
            "INSERT INTO taktovia.travel_attempt (tenant_id, id, created_at, channel, primary_rejection, secondary_rejections, coarse_origin, coarse_destination, service_id, time_kind, booking_kind, " +
                "desired_at, passengers, wheelchair_spaces, outcome, request_scope, request_fingerprint, result) VALUES (taktovia.current_tenant(),?,?,?,?,CAST(? AS text[]),?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb)) " +
                "ON CONFLICT (tenant_id, request_scope) WHERE request_scope IS NOT NULL DO NOTHING RETURNING id",
            { rs, _ -> rs.getObject(1, UUID::class.java) }, id, Timestamp.from(now), cmd.channel, rejection?.primary?.name, pgArray(rejection?.secondary?.map { it.name } ?: emptyList()),
            check.coarseOrigin, check.coarseDestination, cmd.serviceId, cmd.timeKind.name, kind?.name, Timestamp.from(desired), cmd.passengers, cmd.wheelchairSpaces,
            if (priced) AttemptOutcome.TECHNICAL_ERROR.name else outcome.name, scope, fingerprint, if (priced) null else resultJson,
        ).firstOrNull()
        if (inserted != null) {
            val (finalOutcome, finalOffers) = if (priced) issueOffers(j, ctx, inserted, cmd, offers, check, assignment, params!!, now, resultJson) else outcome to offers
            emit(j, ctx, inserted, 1, cmd, finalOutcome, rejection, now)
            return@run RideOfferResult(inserted, false, finalOutcome, finalOffers, rejection, kind)
        }
        // Eindeutigkeitsschlüssel getroffen: ein anderer Aufruf (oder ein früherer Fehlversuch) besitzt den Versuch
        val cur = j.query("SELECT t.id, t.outcome, t.request_fingerprint, t.result::text FROM taktovia.travel_attempt t WHERE t.request_scope = ? FOR UPDATE",
            { rs, _ -> Stored(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3).trim(), rs.getString(4)) }, scope).single()
        if (cur.fingerprint != fingerprint) throw ProblemException(ErrorCode.IDEMPOTENCY_CONFLICT, "Der Idempotency-Key wurde bereits mit einer anderen Anfrage verwendet.")
        if (cur.outcome != "TECHNICAL_ERROR" || outcome == AttemptOutcome.TECHNICAL_ERROR) {
            return@run if (cur.result != null && cur.outcome != "TECHNICAL_ERROR") withIssuedOffer(j, decode(cur.attemptId, cur.result)) else RideOfferResult(cur.attemptId, true, AttemptOutcome.TECHNICAL_ERROR, emptyList(), null, null)
        }
        // früherer technischer Fehler, jetzt ein fachliches Ergebnis: derselbe Versuch wird fortgeschrieben (Revision 2), kein zweiter
        if (priced) {
            val (pricedOutcome, pricedOffers) = issueOffers(j, ctx, cur.attemptId, cmd, offers, check, assignment, params!!, now, resultJson)
            if (pricedOutcome == AttemptOutcome.TECHNICAL_ERROR) return@run RideOfferResult(cur.attemptId, true, AttemptOutcome.TECHNICAL_ERROR, emptyList(), null, null)
            emit(j, ctx, cur.attemptId, 2, cmd, pricedOutcome, rejection, now)
            return@run RideOfferResult(cur.attemptId, true, pricedOutcome, pricedOffers, rejection, kind)
        }
        j.update("UPDATE taktovia.travel_attempt SET outcome = ?, primary_rejection = ?, secondary_rejections = CAST(? AS text[]), coarse_origin = ?, coarse_destination = ?, booking_kind = ?, result = CAST(? AS jsonb) " +
            "WHERE id = ? AND outcome = 'TECHNICAL_ERROR'", outcome.name, rejection?.primary?.name, pgArray(rejection?.secondary?.map { it.name } ?: emptyList()), check.coarseOrigin, check.coarseDestination, kind?.name, resultJson, cur.attemptId)
        emit(j, ctx, cur.attemptId, 2, cmd, outcome, rejection, now)
        RideOfferResult(cur.attemptId, true, outcome, offers, rejection, kind)
    }

    /** Wiederholung: das gespeicherte Ergebnis trägt das ursprünglich ausgestellte Angebot (gleiche ID, gleicher Preisstand), nie ein neues. */
    private fun withIssuedOffer(ctx: RequestContext, r: RideOfferResult): RideOfferResult =
        if (quotes == null || r.outcome != AttemptOutcome.OFFERED || r.offers.isEmpty()) r else ctxTx.run(ctx, readOnly = true) { j -> withIssuedOffer(j, r) }

    private fun withIssuedOffer(j: JdbcOperations, r: RideOfferResult): RideOfferResult {
        val q = quotes
        if (q == null || r.outcome != AttemptOutcome.OFFERED || r.offers.isEmpty()) return r
        val row = j.query(
            "SELECT id, quote_id, expires_at, cancellation_policy_ref, pickup_stop_id, pickup_stop_revision_id, dropoff_stop_id, dropoff_stop_revision_id, plan_revision, revision FROM taktovia.ride_offer WHERE attempt_id = ?",
            { rs, _ ->
                val quote = q.getIn(j, rs.getObject(2, UUID::class.java))!!
                fun stop(a: Int, b: Int) = rs.getObject(a, UUID::class.java)?.let { OfferStop(it, rs.getObject(b, UUID::class.java)) }
                IssuedOffer(rs.getObject(1, UUID::class.java), rs.getTimestamp(3).toInstant(), quote, rs.getString(4), stop(5, 6), stop(7, 8), rs.getObject(9) as Long?, rs.getLong(10))
            }, r.attemptId,
        ).firstOrNull() ?: return r
        return r.copy(offers = r.offers.map { it.copy(offer = row) })
    }

    /**
     * Angebotsbildung (WP-M2-09/-21) in der Transaktion des Versuchs: Quote mit Komponenten, Angebotsobjekt und Ereignis `booking.offer_created` gehören mit dem Versuch zusammen oder gar nicht.
     * Ohne Preisbildung (kein [quotes]) bleibt es bei dem Zeitfenster. Ohne wirksamen Tarif wird der Versuch als `TECHNICAL_ERROR` festgehalten (kein Angebot ohne Preis).
     */
    private fun issueOffers(
        j: JdbcOperations, ctx: RequestContext, attemptId: UUID, cmd: RideOfferCommand, offers: List<RideOfferProposal>, check: EligibilityResult,
        assignment: OfferAssignment?, params: RideOfferParameters, now: Instant, resultJson: String?,
    ): Pair<AttemptOutcome, List<RideOfferProposal>> {
        val q = quotes!!
        val proposal = offers.single()
        val expires = now.plus(policy.validity).truncatedTo(java.time.temporal.ChronoUnit.MICROS)   // Datenbankgenauigkeit: das ausgestellte Angebot und jeder spätere Abruf zeigen denselben Zeitpunkt
        val configRevision = params.configRevisionId ?: j.queryForObject("SELECT config_revision_id FROM taktovia.service WHERE id = ?", UUID::class.java, cmd.serviceId)!!
        val fare = FareRequest(cmd.passengers, check.originAreaId, check.destinationAreaId, distance.meters(cmd.origin.point(), cmd.destination.point()))
        val quote = when (val r = q.createIn(j, ctx, QuoteRequest(attemptId, cmd.serviceId, proposal.pickupWindow.earliestAt, fare, expires, configRevision, policy.tariffClass, policy.cancellationPolicyRef))) {
            is QuoteOutcome.Created -> r.quote
            is QuoteOutcome.NoTariff, is QuoteOutcome.Rejected -> return AttemptOutcome.TECHNICAL_ERROR to emptyList()
        }
        val offerId = UUID.randomUUID()
        val pickup = check.pickupStop?.let { OfferStop(it.stopId, it.revisionId) }; val dropoff = check.dropoffStop?.let { OfferStop(it.stopId, it.revisionId) }
        j.update(
            "INSERT INTO taktovia.ride_offer(tenant_id, id, attempt_id, quote_id, service_id, actor_ref, booking_kind, time_kind, pickup_earliest, pickup_latest, arrival_earliest, arrival_latest, arrival_grace_min, " +
                "direct_seconds, expires_at, cancellation_policy_ref, demand, pickup_stop_id, pickup_stop_revision_id, dropoff_stop_id, dropoff_stop_revision_id, run_id, plan_revision, interval_from, interval_to, created_at, updated_at) " +
                "VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            offerId, attemptId, quote.id, cmd.serviceId, FourEyes.refOf(ctx).value, proposal.bookingKind.name, proposal.timeKind.name,
            Timestamp.from(proposal.pickupWindow.earliestAt), Timestamp.from(proposal.pickupWindow.latestAt), Timestamp.from(proposal.arrivalWindow.earliestAt), Timestamp.from(proposal.arrivalWindow.latestAt),
            proposal.arrivalGraceMin, proposal.directSeconds, Timestamp.from(expires), policy.cancellationPolicyRef, cmd.demand().toJson(), pickup?.stopId, pickup?.revisionId, dropoff?.stopId, dropoff?.revisionId,
            assignment?.runId, assignment?.runRevision, assignment?.intervalFrom, assignment?.intervalTo, Timestamp.from(now), Timestamp.from(now),
        )
        outbox.append(j, OutboxEvent(offerId, 1, "booking.offer_created", linkedMapOf<String, Any?>(
            "offer_id" to offerId.toString(), "attempt_id" to attemptId.toString(), "service_id" to cmd.serviceId.toString(), "run_revision" to assignment?.runRevision, "expires_at" to expires.toString(),
            "pickup_earliest" to proposal.pickupWindow.earliestAt.toString(), "pickup_latest" to proposal.pickupWindow.latestAt.toString(), "arrival_latest" to proposal.arrivalWindow.latestAt.toString(),
            "gross_minor" to quote.payAtPlatformMinor, "currency" to quote.currency,
        ).filterValues { it != null }, now, correlationId = ctx.correlationId))
        j.update("UPDATE taktovia.travel_attempt SET outcome = 'OFFERED', result = CAST(? AS jsonb) WHERE id = ? AND outcome = 'TECHNICAL_ERROR'", resultJson, attemptId)
        return AttemptOutcome.OFFERED to listOf(proposal.copy(offer = IssuedOffer(offerId, expires, quote, policy.cancellationPolicyRef, pickup, dropoff, assignment?.runRevision, 1)))
    }

    private fun emit(j: JdbcOperations, ctx: RequestContext, attemptId: UUID, revision: Long, cmd: RideOfferCommand, outcome: AttemptOutcome, rejection: RideRejection?, now: Instant) {
        val payload = linkedMapOf<String, Any?>(
            "attempt_id" to attemptId.toString(), "service_id" to cmd.serviceId.toString(),
            "outcome" to when (outcome) { AttemptOutcome.OFFERED -> "RECORDED"; AttemptOutcome.REJECTED -> "REJECTED"; AttemptOutcome.TECHNICAL_ERROR -> "TECHNICAL_ERROR" },
            "time_mode" to cmd.timeKind.name, "requested_at" to now.toString(), "passengers" to cmd.passengers, "wheelchair" to cmd.wheelchairSpaces, "channel" to cmd.channel,
        )
        rejection?.let { payload["primary_reason"] = it.primary.name; payload["secondary_reasons"] = it.secondary.map { s -> s.name } }
        outbox.append(j, OutboxEvent(attemptId, revision, "booking.attempt_recorded", payload, now, correlationId = ctx.correlationId))
    }

    // ---- Kodierung -----------------------------------------------------------------------------------------------------------------

    private companion object {
        val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9._:-]{16,128}$")
        val CHANNELS = setOf("WEB", "APP", "API")
        val MAPPER: JsonMapper = JsonMapper.builder().build()

        fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        fun pgArray(values: List<String>): String = values.joinToString(",", "{", "}")

        fun fingerprint(c: RideOfferCommand, desired: Instant): String = sha256(listOf(
            c.serviceId, "%.7f,%.7f,%s".format(java.util.Locale.ROOT, c.origin.longitude, c.origin.latitude, c.origin.stopId), "%.7f,%.7f,%s".format(java.util.Locale.ROOT, c.destination.longitude, c.destination.latitude, c.destination.stopId),
            c.timeKind, desired, c.passengers, c.wheelchairSpaces, c.equipment.map { it.name }.sorted(), c.assistanceCodes.map { it.name }.sorted(), c.channel,
        ).joinToString("|"))

        fun window(w: RideTimeWindow) = linkedMapOf("earliest_at" to w.earliestAt.toString(), "latest_at" to w.latestAt.toString())

        fun encode(outcome: AttemptOutcome, kind: RideBookingKind?, offers: List<RideOfferProposal>, rejection: RideRejection?): String? {
            if (outcome == AttemptOutcome.TECHNICAL_ERROR) return null
            return MAPPER.writeValueAsString(linkedMapOf(
                "outcome" to outcome.name, "booking_kind" to kind?.name,
                "offers" to offers.map { linkedMapOf("service_id" to it.serviceId.toString(), "booking_kind" to it.bookingKind.name, "time_kind" to it.timeKind.name, "pickup_window" to window(it.pickupWindow),
                    "arrival_window" to window(it.arrivalWindow), "arrival_grace_min" to it.arrivalGraceMin, "direct_seconds" to it.directSeconds) },
                "rejection" to rejection?.let { linkedMapOf("primary" to it.primary.name, "secondary" to it.secondary.map { s -> s.name }) },
            ))
        }

        fun decode(attemptId: UUID, json: String): RideOfferResult {
            val n = MAPPER.readTree(json)
            fun win(x: JsonNode) = RideTimeWindow(Instant.parse(x.get("earliest_at").asString()), Instant.parse(x.get("latest_at").asString()))
            val offers = n.get("offers").values().map { o ->
                RideOfferProposal(UUID.fromString(o.get("service_id").asString()), RideBookingKind.valueOf(o.get("booking_kind").asString()), RideTimeKind.valueOf(o.get("time_kind").asString()),
                    win(o.get("pickup_window")), win(o.get("arrival_window")), o.get("arrival_grace_min").asInt(), o.get("direct_seconds").asInt())
            }
            val rej = n.get("rejection")?.takeIf { !it.isNull }?.let { r -> RideRejection(RejectionCode.valueOf(r.get("primary").asString()), r.get("secondary").values().map { RejectionCode.valueOf(it.asString()) }) }
            val kind = n.get("booking_kind")?.takeIf { !it.isNull }?.let { RideBookingKind.valueOf(it.asString()) }
            return RideOfferResult(attemptId, true, AttemptOutcome.valueOf(n.get("outcome").asString()), offers, rej, kind)
        }
    }
}
