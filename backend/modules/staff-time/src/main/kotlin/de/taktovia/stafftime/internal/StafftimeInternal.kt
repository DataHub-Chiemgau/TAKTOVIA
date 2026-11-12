package de.taktovia.stafftime.internal

import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import de.taktovia.stafftime.api.Absences
import de.taktovia.stafftime.api.DriverDevices
import de.taktovia.stafftime.api.DriverEvents
import de.taktovia.stafftime.api.Drivers
import de.taktovia.stafftime.api.StafftimeApi
import de.taktovia.stafftime.api.TimeEvents
import java.time.Clock

/** Nur innerhalb des Moduls `staff-time` sichtbar (durch Architekturtest erzwungen). */
internal object StafftimeServicesFactory {
    fun create(ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox, clock: Clock): StafftimeApi {
        val devices = JdbcDriverDevices(ctxTx, audit)
        val events = JdbcDriverEvents(ctxTx, outbox, clock)
        val drivers = JdbcDrivers(ctxTx, audit)
        val absence = JdbcAbsences(ctxTx, audit)
        val timeEv = JdbcTimeEvents(ctxTx, audit, outbox, clock)
        return object : StafftimeApi {
            override val driverDevices: DriverDevices = devices
            override val driverEvents: DriverEvents = events
            override val drivers: Drivers = drivers
            override val absences: Absences = absence
            override val timeEvents: TimeEvents = timeEv
            override val shifts: de.taktovia.stafftime.api.Shifts = JdbcShifts(ctxTx, audit)
            override val driverReadPort: de.taktovia.platform.readports.DriverReadPort = JdbcDriverReadPort(ctxTx)
        }
    }
}
