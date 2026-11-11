package de.taktovia.identity.internal

import de.taktovia.identity.api.DriverReleases
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import java.time.Clock

/** Nur innerhalb des Moduls `identity` sichtbar (durch Architekturtest erzwungen). */
internal object DriverReleaseServicesFactory {
    fun create(ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox, clock: Clock): DriverReleases = JdbcDriverReleases(ctxTx, audit, outbox, clock)
}
