package de.taktovia.bookingdispatch

import de.taktovia.booking.ConfirmFixture
import de.taktovia.bookingdispatch.api.Bookings
import de.taktovia.bookingdispatch.api.CancellationEvent
import de.taktovia.bookingdispatch.api.CancellationListener
import de.taktovia.bookingdispatch.api.IncidentGateway
import de.taktovia.bookingdispatch.api.IncidentRef
import de.taktovia.bookingdispatch.api.OutageReplacement
import de.taktovia.bookingdispatch.api.RefundHint
import de.taktovia.bookingdispatch.operator.ReplanScene
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.springframework.jdbc.core.JdbcOperations
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Testaufbau des Störungsablaufs (WP-M3-54, eigene Klasse des Clusters wave15-c1): zwei Umläufe ([ReplanScene]: A mit zwei bestätigten Buchungen, B leer), ein Incident zum Fahrzeug von A und der Störungsablauf
 * mit einem **Testdouble** des Incident-Ports (die echte Verdrahtung mit `fleet-safety` weist der Anwendungstest `OutageHttpApiTest` nach). Das Double prüft Sichtbarkeit und Rolle wie der echte Port (fremder
 * Mandant und fremder Dienst 404, Schreiben nur mit Rolle Disposition 403) und hält Entscheidungen und Abschlüsse im Speicher.
 */
class OutageScene(val cf: ConfirmFixture, params: Map<String, Any?> = emptyMap(), capacityB: String = OfferObjectsFixture.cap(4, 1), wheelchairA: Int = 0) {
    val s = ReplanScene(cf, params, capacityB = capacityB, wheelchairA = wheelchairA)
    val op: RequestContext get() = s.op
    val incidentId: UUID = UUID.randomUUID()
    val refunds = CopyOnWriteArrayList<CancellationEvent>()
    val decisions = CopyOnWriteArrayList<Pair<String, String>>()
    val concluded = CopyOnWriteArrayList<Boolean>()

    val gateway = object : IncidentGateway {
        override fun access(j: JdbcOperations, ctx: RequestContext, incidentId: UUID, write: Boolean): IncidentRef {
            if (write && "dispatcher" !in ctx.roles) throw ProblemException(ErrorCode.FORBIDDEN)
            val ref = ref(j, incidentId) ?: throw ProblemException.notFound()
            if (ref.serviceId != null && ref.serviceId !in ctx.services) throw ProblemException.notFound()
            return ref
        }
        override fun accessSystem(j: JdbcOperations, incidentId: UUID): IncidentRef? = ref(j, incidentId)
        override fun recordDecision(j: JdbcOperations, ctx: RequestContext, incidentId: UUID, decision: String, reasonCode: String) { decisions += decision to reasonCode }
        override fun conclude(j: JdbcOperations, incidentId: UUID, resolved: Boolean, actor: String) { concluded += resolved }
        private fun ref(j: JdbcOperations, id: UUID) = j.query("SELECT vehicle_id, service_id, status, revision, kind FROM taktovia.incident WHERE id = ?",
            { rs, _ -> IncidentRef(id, rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getLong(4), rs.getString(5)) }, id).firstOrNull()
    }

    /** Die Buchungen zählen jeden Stornoauslöser der Zahlung (der Erstattungsweg der M2): genau einer je Storno. */
    val bookings: Bookings = Bookings.create(cf.fx.base.ctxTx, cf.fx.base.outbox, cf.fx.quotes, cf.fx.clock, cf.fx.base.config.effective,
        cancellation = CancellationListener { _, e -> refunds += e; RefundHint("VOID_REQUESTED", 0, "EUR") }, audit = JdbcAuditPort())
    val outage: OutageReplacement = OutageReplacement.create(cf.fx.base.ctxTx, cf.fx.base.outbox, JdbcAuditPort(), cf.fx.base.config.effective, bookings, gateway, cf.fx.clock)

    init {
        cf.fx.adminUpdate("INSERT INTO taktovia.incident(tenant_id, id, kind, vehicle_id, status, dedupe_key, created_by, service_id) VALUES (?, ?, 'VEHICLE_OUTAGE', ?, 'OPEN', ?, 'system:workflow', ?)",
            s.a.world.tenant.value, incidentId, s.runA.vehicleId, "VEHICLE_OUTAGE:${s.runA.vehicleId}", s.a.world.serviceId)
    }

    val tenant get() = s.a.world.tenant
    val bookingA: UUID get() = s.a.a.id
    val bookingB: UUID get() = s.a.b.id

    /** Sperrt das Fahrzeug von A (Auslöser der Störung). */
    fun block() { cf.fx.adminUpdate("UPDATE taktovia.vehicle SET active_block = true WHERE id = ?", s.runA.vehicleId) }

    fun search() = outage.search(tenant, incidentId, s.runA.vehicleId)
    fun overview() = outage.overview(op, incidentId)
    fun view(booking: UUID) = overview().bookings.first { it.bookingId == booking }
    fun stateOf(booking: UUID): String = cf.stringOf("SELECT state FROM taktovia.outage_booking WHERE incident_id = ? AND booking_id = ?", incidentId, booking)
    fun bookingState(booking: UUID): String = cf.stringOf("SELECT state FROM taktovia.booking WHERE id = ?", booking)
    fun bookingRevision(booking: UUID): Long = cf.stringOf("SELECT revision::text FROM taktovia.booking WHERE id = ?", booking).toLong()
}
