package de.taktovia.app.driver

import de.taktovia.app.booking.BookingHttpSupport
import de.taktovia.app.identity.IdentityEnv
import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.PlanStop
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.RevisionCause
import de.taktovia.bookingdispatch.api.RunPlans
import de.taktovia.bookingdispatch.api.RunState
import de.taktovia.bookingdispatch.api.StopMovement
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import java.time.Instant
import java.util.UUID

/**
 * Testaufbau für Stoppliste und Unterstützungshinweise über HTTP (WP-M3-26/-32, Cluster wave11-c1): echte Buchungen ([BookingHttpSupport]), echter Fahrtenplan ([RunPlans]), Fahrer mit Konto,
 * angetretener und freigegebener Schicht. Ein Umlauf je [Driver]; Buchungen aus anderen Umläufen können per Planrevision hineingelegt werden (der Plan referenziert Buchungen nur per ID).
 */
internal class DriverRunHttpWorld(val s: BookingHttpSupport = BookingHttpSupport()) {
    val env = s.env; val tenant = s.tenant
    val runs: RunPlans = BookingdispatchApi.create(env.server.ctx.getBean(ContextTransactions::class.java), outbox = env.server.ctx.getBean(Outbox::class.java)).runs
    private val dispatcher: RequestContext

    init {
        val op = UUID.randomUUID(); val membership = env.grant(tenant, op, "dispatcher")
        dispatcher = RequestContext(Actor(ActorType.HUMAN, IdentityEnv.idp.issuer, op.toString(), "sid-op", true), tenant, setOf("dispatcher"), setOf("rides:read", "rides:write"), emptySet(), UUID.randomUUID(), membershipIds = setOf(membership))
    }

    /** Eine bestätigte Buchung mit Haltepunktrevisionen für Ein- und Ausstieg; [assistanceCodes] hängt eine zweite Person mit Rollstuhlplatz und diesen Codes an. */
    class Booking(val id: UUID, val held: BookingHttpSupport.Held, val from: UUID, val to: UUID)

    fun booking(assistanceCodes: List<String>? = null, wheelchair: Boolean = false): Booking {
        val h = s.held(passengers = 1); val id = UUID.fromString(s.confirm(h).json["id"].asString())
        val stops = env.string("SELECT pickup_stop_revision_id::text || ',' || dropoff_stop_revision_id::text FROM taktovia.ride_offer WHERE id = ?::uuid", h.offerId).single().split(",")
        if (assistanceCodes != null) {
            val codes = assistanceCodes.joinToString(",", "{", "}")
            env.exec("INSERT INTO taktovia.passenger_trip(tenant_id, id, booking_id, seq, place_kind, assistance_codes) VALUES (?, ?, ?, 2, ?, CAST(? AS text[]))", tenant.value, UUID.randomUUID(), id,
                if (wheelchair) "WHEELCHAIR" else "SEAT", codes)
        }
        return Booking(id, h, UUID.fromString(stops[0]), UUID.fromString(stops[1]))
    }

    /** Fahrer mit Konto (Rolle `driver`) und angetretener, freigegebener Schicht auf dem Umlauf von [run]; die Schicht läuft von vor fünf Minuten bis in vier Stunden. */
    inner class Driver(val run: UUID) {
        val subject: UUID = UUID.randomUUID()
        val shift: UUID = UUID.fromString(env.string("SELECT shift_id::text FROM taktovia.vehicle_run WHERE id = ?", run).single())
        val token: String
        init {
            env.grant(tenant, subject, "driver"); token = env.token(subject)
            env.exec("UPDATE taktovia.staff_shift SET driver_ref = ?::uuid, starts_at = now() - interval '5 minutes', ends_at = now() + interval '4 hours' WHERE id = ?", subject.toString(), shift)
            // Der Freigabewächter (WP-M3-08) erlaubt nur den Widerruf als Änderung; die Testfreigabe wird deshalb ersetzt, nicht aktualisiert
            env.exec("""WITH old AS (DELETE FROM taktovia.resource_release WHERE shift_id = ? RETURNING *)
                INSERT INTO taktovia.resource_release(tenant_id,id,vehicle_id,shift_id,safety_revision,inspection_ref,qualification_revision,valid_until)
                SELECT tenant_id,id,vehicle_id,shift_id,safety_revision,inspection_ref,qualification_revision,now() + interval '6 hours' FROM old""", shift)
            // Dienstantritt über die echte Schnittstelle (Gerät registrieren, Schicht antreten)
            val device = UUID.randomUUID()
            check(env.post("/v1/driver/devices", """{"device_id":"$device","platform":"ANDROID","app_version":"1.0.0"}""", token, extra = mapOf("Idempotency-Key" to "d-" + UUID.randomUUID())).status == 201)
            val vehicle = env.string("SELECT vehicle_id::text FROM taktovia.staff_shift WHERE id = ?", shift).single()
            val start = env.post("/v1/driver/shift-starts", """{"shift_id":"$shift","vehicle_id":"$vehicle","device_id":"$device"}""", token, extra = mapOf("Idempotency-Key" to "st-" + UUID.randomUUID()))
            check(start.status == 200) { "Antritt: ${start.status} ${start.text}" }
        }
        fun get(ifNoneMatch: String? = null, t: String = token) = env.get("/v1/driver/shifts/$shift/run", t, extra = if (ifNoneMatch != null) mapOf("If-None-Match" to ifNoneMatch) else emptyMap())
        fun endShift() = env.exec("UPDATE taktovia.staff_shift SET state = 'ENDED', ended_at = now() WHERE id = ?", shift)
        fun revision(): Long = runs.run(dispatcher, run).revision
    }

    fun driver(): Driver = Driver(s.offers.bind(s.offers.issue(), 4))

    val t0: Instant = Instant.parse("2026-11-05T09:00:00Z")
    private val one = ResourceVector(1, 0)

    /**
     * Plan (neue Revision) aus [board]-Buchungen: je Buchung ein Einstieg `i` und der Ausstieg danach (Einstiege zuerst, dann Ausstiege in gleicher Reihenfolge). [legs] steuert, für
     * welche Stopps die Fahrzeit zum Folgestopp aus dem Routing stammt (sonst `null` = keine Prognose für den Folgestopp). Planzeit des Stopps `i` = [base] plus 5 Minuten je Stopp.
     */
    fun plan(run: UUID, bookings: List<Booking>, base: Instant = t0, legs: Set<Int>? = null, expected: Long = runs.run(dispatcher, run).revision): Long {
        val n = bookings.size
        val stops = (0 until 2 * n).map { i ->
            val last = i == 2 * n - 1
            val booking = bookings[i % n]
            PlanStop(i, if (i < n) booking.from else booking.to, base.plusSeconds(300L * i),
                boardings = if (i < n) listOf(StopMovement(booking.id, one)) else emptyList(), alightings = if (i >= n) listOf(StopMovement(booking.id, one)) else emptyList(),
                legDurationSec = if (last || (legs != null && i !in legs)) null else 240, legDistanceM = if (last || (legs != null && i !in legs)) null else 1800)
        }
        return runs.appendRevision(dispatcher, run, expected, stops, RevisionCause.MANUAL_CHANGE, bookings.map { it.id }).revision
    }

    fun complete(run: UUID) { runs.transition(dispatcher, run, RunState.IN_PROGRESS); runs.transition(dispatcher, run, RunState.COMPLETED) }
}
