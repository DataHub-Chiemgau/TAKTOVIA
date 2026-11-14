package de.taktovia.app.fleet

import de.taktovia.app.booking.BookingHttpSupport
import de.taktovia.app.identity.IdentityEnv
import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.PlanStop
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.RevisionCause
import de.taktovia.bookingdispatch.api.StopMovement
import de.taktovia.fleetsafety.api.FleetsafetyApi
import de.taktovia.fleetsafety.api.WorkflowEvent
import de.taktovia.fleetsafety.api.WorkflowEventKinds
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.operation.OperationHandler
import de.taktovia.platform.operation.OperationRunner
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantTransactions
import org.junit.jupiter.api.Assertions.assertNotNull
import java.time.Instant
import java.util.UUID

/**
 * Testaufbau des Störungsablaufs über die echte Anwendung (WP-M3-54, eigene Klasse des Clusters wave15-c1): eine bestätigte Buchung auf Umlauf A (Fahrzeug fällt aus), Umlauf B als Ersatz, Incident aus dem
 * Ereignis „Fahrzeug gesperrt“ und die Ersatzsuche als Betriebsauftrag. Geteilt von [OutageHttpApiTest] und den Vertragsfällen.
 */
internal class OutageHttpScene(val s: BookingHttpSupport = BookingHttpSupport()) {
    val env = s.env
    val ctx get() = env.server.ctx
    val fleet: FleetsafetyApi get() = ctx.getBean(FleetsafetyApi::class.java)
    val a = s.held(passengers = 1); val b = s.held(passengers = 1)          // Umlauf A (ausfallendes Fahrzeug) und Umlauf B (Ersatz)
    val booking = s.confirm(a).json
    val bookingId = booking["id"].asString()
    val service = UUID.fromString(s.world.service)
    val dispatcher = s.operator(listOf(service))
    val vehicleA = UUID.fromString(env.string("SELECT vehicle_id::text FROM taktovia.vehicle_run WHERE id = ?", a.run).single())
    val vehicleB = UUID.fromString(env.string("SELECT vehicle_id::text FROM taktovia.vehicle_run WHERE id = ?", b.run).single())
    val incident: UUID

    init {
        // Plan von A mit der Buchung, Zeiten im Zusagefenster der Buchung
        val pickup = Instant.parse(booking["promise"]["pickup_earliest"].asString())
        val dropoff = Instant.parse(booking["promise"]["arrival_earliest"].asString()).let { if (it.isAfter(pickup.plusSeconds(600))) it else pickup.plusSeconds(600) }
        val op = UUID.randomUUID(); val membership = env.grant(s.tenant, op, "dispatcher")
        val dctx = RequestContext(Actor(ActorType.HUMAN, IdentityEnv.idp.issuer, op.toString(), "sid-op", true), s.tenant, setOf("dispatcher"), setOf("rides:read", "rides:write"), emptySet(), UUID.randomUUID(), membershipIds = setOf(membership))
        val rev = env.string("SELECT pickup_stop_revision_id::text || ',' || dropoff_stop_revision_id::text FROM taktovia.ride_offer WHERE id = ?::uuid", a.offerId).single().split(",")
        val v = ResourceVector(1, 0); val id = UUID.fromString(bookingId)
        BookingdispatchApi.create(ctx.getBean(ContextTransactions::class.java), outbox = ctx.getBean(Outbox::class.java)).runs.appendRevision(dctx, a.run, 1, listOf(
            PlanStop(0, UUID.fromString(rev[0]), pickup, boardings = listOf(StopMovement(id, v)), legDurationSec = 600, legDistanceM = 4000),
            PlanStop(1, UUID.fromString(rev[1]), dropoff, alightings = listOf(StopMovement(id, v))),
        ), RevisionCause.BOOKING_INSERTED, listOf(id))
        // das Fahrzeug fällt aus: Sperre, Ereignis „Fahrzeug gesperrt“ im Workflow, Ersatzsuche als Betriebsauftrag
        env.exec("UPDATE taktovia.vehicle SET active_block = true WHERE id = ?", vehicleA)
        incident = env.tx.run(s.tenant) { j -> fleet.incidentWorkflow.handle(j, WorkflowEvent(UUID.randomUUID(), WorkflowEventKinds.VEHICLE_BLOCKED, vehicleA, Instant.now(), correlationId = UUID.randomUUID())).incidentId }!!
        val handler = ctx.getBean("replacementSearchHandler", OperationHandler::class.java)
        val run = OperationRunner(ctx.getBean(TenantTransactions::class.java), listOf(handler)).runNext(s.tenant)
        assertNotNull(run, "Ersatzsuche lief als Betriebsauftrag")
    }

    fun detail(token: String = dispatcher) = env.get("/v1/operator/incidents/$incident", token)
    fun outage() = detail().also { check(it.status == 200) { it.text } }.json["outage"]
    fun post(path: String, body: String?, ifMatch: String?, token: String = dispatcher, key: String = "outage-" + UUID.randomUUID(), t: String = "/v1/operator/incidents/$incident") =
        env.server.send("POST", "$t$path", body, env.headers(token, extra = mapOf("Idempotency-Key" to key) + (if (ifMatch != null) mapOf("If-Match" to "\"$ifMatch\"") else emptyMap())))
    val path get() = "/outage/bookings/$bookingId"
}
