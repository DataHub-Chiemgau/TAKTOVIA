package de.taktovia.bookingdispatch.operator

import de.taktovia.booking.ConfirmFixture
import de.taktovia.bookingdispatch.OfferObjectsFixture
import de.taktovia.bookingdispatch.RideOfferFixture
import de.taktovia.bookingdispatch.api.BookingDetail
import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.PlanStop
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.RevisionCause
import de.taktovia.bookingdispatch.api.StopMovement
import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.util.UUID

/**
 * Testaufbau für Fahrzeugplan (WP-M3-38) und manuelle Umplanung (WP-M3-40), eigene Klasse des Clusters wave14-c6: ein Umlauf mit bestätigten Buchungen, Ausgangsplan als Revision 2
 * (A steigt bei 0 ein und bei 2 aus, B bei 1 ein und bei 3 aus; Planzeiten 09:00, 09:05, 09:15, 09:20) und Hilfen zum Schreiben von Fahrerereignissen.
 */
class VehiclePlanScene(val cf: ConfirmFixture, val world: RideOfferFixture.World = cf.world(), capacity: String = OfferObjectsFixture.cap(4, 1), passengersA: Int = 1, wheelchairA: Int = 0) {
    val api: BookingdispatchApi = BookingdispatchApi.create(cf.fx.base.ctxTx, cf.fx.clock, cf.fx.base.outbox)
    val tenant = world.tenant
    val run = cf.fx.run(world, capacity)
    val op: RequestContext = cf.operator(tenant, setOf(world.serviceId))
    val ha = cf.booked(world, run, cf.passenger(world), passengers = passengersA, wheelchair = wheelchairA)
    val hb = cf.booked(world, run, cf.passenger(world), passengers = 1)
    val a: BookingDetail = ha.second
    val b: BookingDetail = hb.second
    val vecA = ResourceVector(passengersA - wheelchairA, wheelchairA)
    val vecB = ResourceVector(1, 0)
    val from: UUID = ha.first.offer.pickupStop!!.revisionId
    val to: UUID = ha.first.offer.dropoffStop!!.revisionId
    val t0: Instant = Instant.parse("2026-11-02T09:00:00Z")

    init {
        api.runs.appendRevision(op, run.runId, 1, stops(0), RevisionCause.BOOKING_INSERTED, listOf(a.id, b.id))
    }

    /** Ausgangsplan, alle Planzeiten um [shiftSec] verschoben. */
    fun stops(shiftSec: Long, lastLegKnown: Boolean = true): List<PlanStop> = listOf(
        PlanStop(0, from, t0.plusSeconds(shiftSec), boardings = listOf(StopMovement(a.id, vecA)), legDurationSec = 300, legDistanceM = 2000),
        PlanStop(1, from, t0.plusSeconds(300 + shiftSec), boardings = listOf(StopMovement(b.id, vecB)), legDurationSec = if (lastLegKnown) 600 else null, legDistanceM = if (lastLegKnown) 4000 else null),
        PlanStop(2, to, t0.plusSeconds(900 + shiftSec), alightings = listOf(StopMovement(a.id, vecA)), legDurationSec = 300, legDistanceM = 2000),
        PlanStop(3, to, t0.plusSeconds(1200 + shiftSec), alightings = listOf(StopMovement(b.id, vecB))),
    )

    fun revision(): Long = api.runs.run(op, run.runId).revision

    private val deviceId: UUID by lazy {
        UUID.randomUUID().also { id ->
            cf.fx.adminUpdate("INSERT INTO taktovia.driver_device(tenant_id,id,issuer,subject,idp_session_id,platform,app_version) VALUES (?,?,?,?,?,'ANDROID','1.0')", tenant.value, id, "https://idp.test", "drv-$id", "session-$id")
        }
    }

    /** Fahrerereignis (Eingang bereits entschieden: [applied] = wirksam, sonst `REVIEW_REQUIRED` mit [reason]). */
    fun event(type: String, booking: UUID?, applied: Boolean = true, reason: String = "OUTSIDE_STOP", shiftId: UUID = run.shiftId, vehicleId: UUID = run.vehicleId) {
        val now = Instant.parse("2026-11-02T09:01:00Z")
        cf.fx.adminUpdate(
            "INSERT INTO taktovia.driver_event(tenant_id,event_id,device_id,shift_id,vehicle_id,event_type,booking_id,payload_sha256,outcome,reason_code,plan_revision,device_recorded_at,occurred_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            tenant.value, UUID.randomUUID(), deviceId, shiftId, vehicleId, type, booking, "0".repeat(64), if (applied) "APPLIED" else "REVIEW_REQUIRED", if (applied) null else reason, 2, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
        )
    }
}
