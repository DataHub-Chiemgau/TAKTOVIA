package de.taktovia.bookingdispatch.operator

import de.taktovia.booking.ConfirmFixture
import de.taktovia.bookingdispatch.OfferObjectsFixture
import de.taktovia.bookingdispatch.api.PlanCommit
import de.taktovia.bookingdispatch.api.ReplanCommand
import de.taktovia.bookingdispatch.api.StopChange
import de.taktovia.bookingdispatch.api.StopChangeKind
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.RequestContext
import java.util.UUID

/**
 * Testaufbau der manuellen Umplanung (WP-M3-40, eigene Klasse des Clusters wave14-c6): Umlauf A mit zwei bestätigten Buchungen (Ausgangsplan wie [VehiclePlanScene]) und ein zweiter, leerer Umlauf B im selben
 * Dienst. [params] sind die veröffentlichten Mandantenparameter (u. a. `protected_stop_horizon_min`, `max_reassignments`).
 */
class ReplanScene(
    val cf: ConfirmFixture, params: Map<String, Any?> = emptyMap(), capacityA: String = OfferObjectsFixture.cap(4, 1), capacityB: String = OfferObjectsFixture.cap(4, 1), wheelchairA: Int = 0,
) {
    private val world = cf.fx.base.world(mapOf("min_booking_lead_min" to 30, "max_booking_horizon_days" to 14, "pickup_window_min" to 15, "arrival_grace_min" to 5, "stop_walk_max_m" to 400) + params).also { cf.fx.publishTariff(it, 450, 50) }
    val a = VehiclePlanScene(cf, world, capacityA, wheelchairA = wheelchairA)
    val runA get() = a.run
    val runB = cf.fx.run(world, capacityB)
    val op: RequestContext get() = a.op
    val commit: PlanCommit = PlanCommit.create(cf.fx.base.ctxTx, cf.fx.base.outbox, JdbcAuditPort(), cf.fx.base.config.effective, cf.fx.clock)

    fun stopId(run: UUID, revision: Long, seq: Int): UUID = UUID.fromString(cf.fx.base.stringOf("SELECT id::text FROM taktovia.route_stop WHERE run_id = ? AND run_revision = ? AND seq = ?", run, revision, seq))
    fun revisionOf(run: UUID): Long = cf.fx.base.stringOf("SELECT revision::text FROM taktovia.vehicle_run WHERE id = ?", run).toLong()
    fun count(sql: String, vararg args: Any?) = cf.count(sql, *args)

    /** Übernimmt die Buchung [booking] von Umlauf A (Revision [fromRevision], Stopps [boardSeq] und [alightSeq]) als Einstieg und Ausstieg an den Positionen [pos] in Umlauf B. */
    fun transferToB(booking: UUID, fromRevision: Long = revisionOf(runA.runId), boardSeq: Int = 0, alightSeq: Int = 2, pos: Pair<Int, Int> = 1 to 2): List<StopChange> = listOf(
        StopChange(StopChangeKind.INSERT, stopId(runA.runId, fromRevision, boardSeq), pos.first, booking),
        StopChange(StopChangeKind.INSERT, stopId(runA.runId, fromRevision, alightSeq), pos.second, booking),
    )

    /** Harmlose Umordnung: der zweite Stopp (Einstieg von B) rückt an die erste Stelle; Zeitraster und Zusagefenster bleiben eingehalten. */
    fun swapFirstTwo(run: UUID = runA.runId, revision: Long = revisionOf(run)): List<StopChange> = listOf(StopChange(StopChangeKind.MOVE, stopId(run, revision, 1), 1))

    fun cmd(changes: List<StopChange>, base: Long, reason: String = "Fahrzeugausfall, Umbuchung durch die Leitstelle", compareOnly: Boolean = false, exception: de.taktovia.bookingdispatch.api.ReplanException? = null) =
        ReplanCommand(changes, reason, base, compareOnly, exception)
}
