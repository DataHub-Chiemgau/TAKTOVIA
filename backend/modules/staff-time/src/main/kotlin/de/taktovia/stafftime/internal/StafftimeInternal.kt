package de.taktovia.stafftime.internal

import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import de.taktovia.stafftime.api.Absences
import de.taktovia.stafftime.api.DriverDevices
import de.taktovia.stafftime.api.DriverEvents
import de.taktovia.stafftime.api.DriverRunSource
import de.taktovia.stafftime.api.DriverShifts
import de.taktovia.stafftime.api.Drivers
import de.taktovia.stafftime.api.Positions
import de.taktovia.stafftime.api.Shifts
import de.taktovia.stafftime.api.StafftimeSettings
import de.taktovia.stafftime.api.StafftimeApi
import de.taktovia.stafftime.api.TimeEvents
import de.taktovia.stafftime.api.WorkRuleSource
import java.time.Clock

/** Nur innerhalb des Moduls `staff-time` sichtbar (durch Architekturtest erzwungen). */
internal object StafftimeServicesFactory {
    fun create(ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox, clock: Clock, settings: StafftimeSettings, runSource: DriverRunSource?, workRules: WorkRuleSource,
        effects: de.taktovia.stafftime.api.DriverEventEffects = de.taktovia.stafftime.api.DriverEventEffects.NONE,
        planAckSink: de.taktovia.stafftime.api.PlanAckOverdueSink = de.taktovia.stafftime.api.PlanAckOverdueSink.NONE): StafftimeApi {
        val devices = JdbcDriverDevices(ctxTx, audit)
        val codes = JdbcBoardingCodes(ctxTx, outbox, clock, settings)
        val events = JdbcDriverEvents(ctxTx, outbox, clock, settings, effects, codes)
        val drivers = JdbcDrivers(ctxTx, audit)
        val shiftsImpl = JdbcShifts(ctxTx, audit)
        val driverShiftsImpl = JdbcDriverShifts(ctxTx, audit, clock, settings, runSource)
        val positionsImpl = JdbcPositions(ctxTx, clock, settings)
        val planAcksImpl = JdbcDriverPlanAcks(ctxTx, audit, outbox, clock, settings, runSource, planAckSink, driverShiftsImpl)
        val absence = JdbcAbsences(ctxTx, audit)
        val conflictChecker = RosterConflictChecker(drivers, absence)
        val timeEv = JdbcTimeEvents(ctxTx, audit, outbox, clock)
        return object : StafftimeApi {
            override val driverDevices: DriverDevices = devices
            override val driverEvents: DriverEvents = events
            override val boardingCodes: de.taktovia.stafftime.api.BoardingCodes = codes
            override val drivers: Drivers = drivers
            override val shifts: Shifts = shiftsImpl
            override val driverShifts: DriverShifts = driverShiftsImpl
            override val positions: Positions = positionsImpl
            override val planAcks: de.taktovia.stafftime.api.DriverPlanAcks = planAcksImpl
            override val absences: Absences = absence
            override val timeEvents: TimeEvents = timeEv
            override val rosterShifts: de.taktovia.stafftime.api.RosterShifts = JdbcRosterShifts(ctxTx, audit, conflictChecker)
            override val rosters: de.taktovia.stafftime.api.Rosters = JdbcRosters(ctxTx, audit, conflictChecker, RosterHints(workRules))
            override val publishedRosters: de.taktovia.stafftime.api.PublishedRosters = JdbcRosterPublication(ctxTx, audit, outbox, clock, conflictChecker, RosterHints(workRules))
            override val rosterHistory: de.taktovia.stafftime.api.RosterHistory = JdbcRosterHistory(ctxTx)
            override val rosterDepotAccess: de.taktovia.stafftime.api.RosterDepotAccess = JdbcRosterDepotAccess(ctxTx, audit)
            override val timeTargetActual: de.taktovia.stafftime.api.TimeTargetActual = JdbcTargetActual(ctxTx, workRules)
            override val driverReadPort: de.taktovia.platform.readports.DriverReadPort = JdbcDriverReadPort(ctxTx)
        }
    }
}
