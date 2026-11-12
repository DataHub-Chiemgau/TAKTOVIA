package de.taktovia.app.driver

import de.taktovia.bookingdispatch.api.BookingExecution
import de.taktovia.bookingdispatch.api.ExecutionKind
import de.taktovia.bookingdispatch.api.ExecutionRequest
import de.taktovia.bookingdispatch.api.ExecutionResult
import de.taktovia.config.api.ConfigApi
import de.taktovia.config.api.EffectiveConfig
import de.taktovia.config.api.EffectiveRequest
import de.taktovia.config.api.EffectiveStatus
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.stafftime.api.DriverEventEffects
import de.taktovia.stafftime.api.EffectKind
import de.taktovia.stafftime.api.EffectRequest
import de.taktovia.stafftime.api.EffectResult
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcOperations
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Verdrahtung der Wirkung der Fahrerereignisse (WP-M3-29): `staff-time` kennt kein anderes Modul, die Anwendung bildet seinen Port [DriverEventEffects] auf die Modul-API
 * [BookingExecution] von `booking-dispatch` ab (gleiche Transaktion, gleicher Mandantenkontext) und löst die Wartefrist `no_show_grace_min` des Dienstes der Buchung aus der wirksamen
 * Konfiguration auf. Hier wird nichts entschieden, nur umgeformt.
 */
@Configuration
class DriverEventEffectsConfiguration {
    @Bean fun bookingExecution(outbox: Outbox): BookingExecution = BookingExecution.create(outbox, Clock.systemUTC())

    @Bean fun driverEventEffects(execution: BookingExecution, config: ObjectProvider<ConfigApi>): DriverEventEffects = AppDriverEventEffects(execution) { config.getObject().effective }
}

/** Abbildung des Ports `staff-time` auf `booking-dispatch` und die Konfiguration; öffentlich nur für den Verdrahtungstest. */
class AppDriverEventEffects(private val execution: BookingExecution, private val config: () -> EffectiveConfig) : DriverEventEffects {
    override fun apply(j: JdbcOperations, ctx: RequestContext, r: EffectRequest): EffectResult {
        val kind = when (r.kind) { EffectKind.ARRIVAL -> ExecutionKind.ARRIVAL; EffectKind.BOARDED -> ExecutionKind.BOARDED; EffectKind.ALIGHTED -> ExecutionKind.ALIGHTED; EffectKind.NO_SHOW -> ExecutionKind.NO_SHOW }
        return when (val e = execution.apply(j, ExecutionRequest(r.shiftId, r.vehicleId, r.routeStopId, r.bookingId, kind, r.at, r.eventId))) {
            is ExecutionResult.Applied -> EffectResult.Applied(e.bookingState, e.legState)
            is ExecutionResult.Review -> EffectResult.Review(e.reasonCode)
            ExecutionResult.NotFound -> EffectResult.NotFound
        }
    }

    override fun bookingInShiftRun(j: JdbcOperations, ctx: RequestContext, shiftId: UUID, vehicleId: UUID, bookingId: UUID): Boolean = execution.inRun(j, shiftId, vehicleId, bookingId)

    override fun noShowGraceMinutes(j: JdbcOperations, ctx: RequestContext, bookingId: UUID, at: Instant): Int? {
        val service = execution.serviceOf(j, bookingId) ?: return null
        val view = config().resolveInTransaction(j, ctx, EffectiveRequest(parameters = setOf(PARAM), serviceId = service, at = at))
        return view.items.firstOrNull { it.parameter == PARAM && it.status == EffectiveStatus.EFFECTIVE }?.value.let { (it as? Number)?.toInt() }
    }

    private companion object { const val PARAM = "no_show_grace_min" }
}
