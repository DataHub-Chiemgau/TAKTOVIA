package de.taktovia.app.communication

import de.taktovia.platform.outbox.AggregateSnapshot
import de.taktovia.platform.outbox.SnapshotFetcher
import de.taktovia.platform.outbox.SnapshotNotYetAvailableException
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Autorisierter Snapshotabruf beim Eigentümer für den Ereignisverbraucher der Benachrichtigungen (WP-M2-34): nur die aktuelle Revision der Aggregate, deren Ereignisse die Auslöser
 * verbrauchen (Umlauf und Buchung); mehr braucht der Verbraucher nicht, er hält keinen Aggregatzustand. Eigentümer und Empfänger sind derselbe Mandant (RecipientPolicy.OWNER_ONLY),
 * der Abruf läuft im Mandantenkontext des Empfängers. Unbekanntes Aggregat: der Snapshot ist noch nicht verfügbar, die Nachricht wird erneut zugestellt.
 */
internal class OwnerSnapshotFetcher(private val tx: TenantTransactions) : SnapshotFetcher {
    private val mapper = JsonMapper.builder().build()

    override fun fetch(recipient: TenantId, owner: UUID, aggregateId: UUID): AggregateSnapshot {
        require(recipient.value == owner) { "Snapshot nur beim Eigentümer" }
        val revision = tx.run(recipient, readOnly = true) { j ->
            j.query("SELECT revision FROM taktovia.vehicle_run WHERE id = ?", { rs, _ -> rs.getLong(1) }, aggregateId).firstOrNull()
                ?: j.query("SELECT revision FROM taktovia.booking WHERE id = ?", { rs, _ -> rs.getLong(1) }, aggregateId).firstOrNull()
        } ?: throw SnapshotNotYetAvailableException("Aggregat unbekannt")
        return AggregateSnapshot(aggregateId, revision, mapper.createObjectNode())
    }
}
