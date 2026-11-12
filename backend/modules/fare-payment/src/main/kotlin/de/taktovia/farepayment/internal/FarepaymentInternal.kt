package de.taktovia.farepayment.internal

import de.taktovia.farepayment.api.FareQuotes
import de.taktovia.farepayment.api.Tariffs
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.authz.ObjectAuthorizer
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import java.time.Clock

/** Nur innerhalb des Moduls `fare-payment` sichtbar (durch Architekturtest erzwungen). */
internal object FarepaymentServicesFactory {
    fun tariffs(ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox, clock: Clock, authorizer: ObjectAuthorizer): Tariffs = JdbcTariffs(ctxTx, audit, outbox, clock, authorizer)

    fun quotes(ctxTx: ContextTransactions, tariffs: Tariffs, clock: Clock): FareQuotes =
        JdbcFareQuotes(ctxTx, tariffs as? JdbcTariffs ?: throw IllegalArgumentException("Quote-Bildung braucht die Tarifverwaltung dieses Moduls"), clock)
}
