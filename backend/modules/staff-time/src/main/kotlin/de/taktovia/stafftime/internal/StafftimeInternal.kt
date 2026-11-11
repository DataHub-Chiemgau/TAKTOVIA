package de.taktovia.stafftime.internal

import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import de.taktovia.stafftime.api.Absences
import de.taktovia.stafftime.api.DriverDevices
import de.taktovia.stafftime.api.DriverEvents
import de.taktovia.stafftime.api.StafftimeApi
import java.time.Clock

/** Nur innerhalb des Moduls `staff-time` sichtbar (durch Architekturtest erzwungen). */
internal object StafftimeServicesFactory {
    fun create(ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox, clock: Clock): StafftimeApi {
        val devices = JdbcDriverDevices(ctxTx, audit)
        val events = JdbcDriverEvents(ctxTx, outbox, clock)
        val absence = JdbcAbsences(ctxTx, audit)
        return object : StafftimeApi { override val driverDevices: DriverDevices = devices; override val driverEvents: DriverEvents = events; override val absences: Absences = absence }
    }
}
