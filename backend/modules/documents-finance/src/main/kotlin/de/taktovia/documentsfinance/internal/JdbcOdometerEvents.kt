package de.taktovia.documentsfinance.internal

import de.taktovia.documentsfinance.api.OdometerEventConsumer
import de.taktovia.documentsfinance.api.OdometerEvents
import de.taktovia.documentsfinance.api.OdometerSource
import de.taktovia.documentsfinance.api.RecordOdometer
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.outbox.AggregateSnapshot
import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.outbox.InboxHandler
import de.taktovia.platform.outbox.InboxOutcome
import de.taktovia.platform.outbox.InboxProcessor
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/**
 * Ereigniskonsum des Kilometerstandsregisters über die Inbox (WP-M1-14): Deduplikation `(consumer, event_id)` und Wirkung in einer Transaktion des Empfängermandanten; zusätzlich ist
 * `(source, source_ref)` im Register eindeutig, sodass auch ein erneut erzeugtes Ereignis zu demselben Quellobjekt keinen zweiten Eintrag ergibt.
 *
 * Das Register hält keinen Aggregatzustand; die Reihenfolge der Stände bestimmt die Messzeit, nicht die Zustellreihenfolge. Deshalb wird jedes Ereignis als eigenes Aggregat (Kennung = Ereigniskennung,
 * Revision 1) verarbeitet: die Lückenerkennung der Inbox (Revisionen des Quellaggregats, die über mehrere Ereignistypen laufen) soll hier nie zu einem verlorenen Stand führen.
 */
internal class JdbcOdometerEvents(private val processor: InboxProcessor, private val register: JdbcOdometerRegister) : OdometerEventConsumer {
    private val log = LoggerFactory.getLogger("taktovia.odometer.events")

    private val handler = object : InboxHandler {
        override fun handle(jdbc: JdbcOperations, event: EventEnvelope) {
            val p = event.payload
            when (event.eventType) {
                OdometerEvents.INSPECTION_COMPLETED -> inspection(jdbc, event, p)
                OdometerEvents.FUEL_RECORDED -> fuel(jdbc, event, p)
                OdometerEvents.EXPENSE_APPROVED -> approved(jdbc, event, p)
                else -> Unit
            }
        }
        override fun applySnapshot(jdbc: JdbcOperations, snapshot: AggregateSnapshot) = Unit
    }

    override fun consume(json: String): InboxOutcome {
        val e = try { EventEnvelope.fromJson(json) } catch (ex: IllegalArgumentException) { return processor.process(CONSUMER, json, handler) }   // die Inbox meldet das Schemaproblem
        if (e.eventType !in OdometerEvents.CONSUMED) return InboxOutcome.PROCESSED.also { log.debug("Ereignistyp {} wird vom Kilometerstandsregister nicht verarbeitet", e.eventType) }
        val own = e.copy(aggregateId = e.eventId, aggregateRevision = 1)
        return processor.process("$CONSUMER.${e.eventType}", own.toJson(), handler)
    }

    private fun inspection(j: JdbcOperations, e: EventEnvelope, p: JsonNode) {
        val km = p.long("odometer_km") ?: return
        val vehicle = p.uuid("vehicle_id") ?: return
        val inspection = p.text("inspection_id") ?: return
        record(j, e, vehicle, RecordOdometer(OdometerSource.DRIVER_CHECK, inspection, km, p.instant("odometer_measured_at") ?: e.occurredAt))
    }

    /** Erst mit vollständigen Tankwerten und freigegebenem Beleg (Schema `fuel.transaction_recorded`); sonst holt `expense.approved` die Aufnahme nach. */
    private fun fuel(j: JdbcOperations, e: EventEnvelope, p: JsonNode) {
        if (p.get("complete")?.asBoolean() != true) return
        val doc = p.uuid("document_id") ?: return
        if (!isApproved(j, doc)) return
        val vehicle = p.uuid("vehicle_id") ?: return
        val km = p.long("odometer_km") ?: return
        record(j, e, vehicle, RecordOdometer(OdometerSource.FUEL_RECEIPT, "$doc:$km", km, p.instant("fueled_at") ?: e.occurredAt))
    }

    private fun approved(j: JdbcOperations, e: EventEnvelope, p: JsonNode) {
        val doc = p.uuid("document_id") ?: return
        val f = j.query(
            "SELECT t.vehicle_id, t.odometer_km, t.fueled_at, t.recorded_at FROM taktovia.fuel_transaction t JOIN taktovia.expense_document d ON d.tenant_id = t.tenant_id AND d.id = t.document_id " +
                "WHERE t.document_id = ? AND d.state IN ('APPROVED', 'POSTED') AND t.vehicle_id IS NOT NULL AND t.fuel_type IS NOT NULL AND t.quantity IS NOT NULL AND t.unit_price IS NOT NULL AND t.odometer_km IS NOT NULL",
            { rs, _ -> arrayOf(rs.getObject(1, UUID::class.java), rs.getLong(2), (rs.getTimestamp(3) ?: rs.getTimestamp(4)).toInstant()) }, doc).firstOrNull() ?: return
        val km = f[1] as Long
        record(j, e, f[0] as UUID, RecordOdometer(OdometerSource.FUEL_RECEIPT, "$doc:$km", km, f[2] as Instant))
    }

    private fun isApproved(j: JdbcOperations, doc: UUID): Boolean =
        j.query("SELECT 1 FROM taktovia.expense_document WHERE id = ? AND state IN ('APPROVED', 'POSTED')", { _, _ -> 1 }, doc).isNotEmpty()

    /** Ein unbekanntes Fahrzeug (z. B. Löschung, fremder Mandant) ist kein Zustellfehler: das Ereignis wird protokolliert verworfen, statt endlos erneut zugestellt zu werden. */
    private fun record(j: JdbcOperations, e: EventEnvelope, vehicle: UUID, cmd: RecordOdometer) {
        if (j.query("SELECT 1 FROM taktovia.vehicle WHERE id = ?", { _, _ -> 1 }, vehicle).isEmpty()) { log.warn("Stand verworfen: Fahrzeug im Mandanten unbekannt (event={})", e.eventId); return }
        try { register.check(cmd) } catch (ex: de.taktovia.platform.problem.ProblemException) { log.warn("Stand verworfen: ungültig (event={}, {})", e.eventId, ex.code); return }
        register.recordIn(j, ActorRef.service("odometer-register"), e.correlationId ?: UUID.randomUUID(), vehicle, cmd)
    }

    private fun JsonNode.text(f: String): String? = get(f)?.takeIf { it.isString }?.asString()
    private fun JsonNode.uuid(f: String): UUID? = text(f)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    private fun JsonNode.long(f: String): Long? = get(f)?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong()
    private fun JsonNode.instant(f: String): Instant? = text(f)?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private companion object { const val CONSUMER = "odometer-register" }
}
