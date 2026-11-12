package de.taktovia.stafftime.api

import de.taktovia.platform.context.RequestContext
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.util.UUID

/** Art der Wirkung eines Fahrerereignisses auf Stopp, Buchung und Fahrt (WP-M3-29). */
enum class EffectKind { ARRIVAL, BOARDED, ALIGHTED, NO_SHOW }

data class EffectRequest(val shiftId: UUID, val vehicleId: UUID, val routeStopId: UUID, val bookingId: UUID?, val kind: EffectKind, val at: Instant, val eventId: UUID)

sealed interface EffectResult {
    /** Wirksam; Zustand der Buchung und des Fahrtsegments danach (`null` bei Ankunft). */
    data class Applied(val bookingState: String?, val legState: String?) : EffectResult

    /** Nicht wirksam, Klärfall: `OUTSIDE_STOP`, `INVALID_ORDER`, `STALE_PLAN` oder `BOOKING_CANCELED`. */
    data class Review(val reasonCode: String) : EffectResult

    /** Stopp, Buchung oder Umlauf gehören nicht zur eigenen Schicht: 404. */
    data object NotFound : EffectResult
}

/**
 * Port: Wirkung eines Fahrerereignisses auf Buchung und Fahrt, **in der Transaktion des Ereigniseingangs** (Modul-API `booking-dispatch`, in der Anwendung verdrahtet; `staff-time`
 * kennt kein anderes Modul). Ohne verdrahtete Wirkung ([NONE]) werden Ereignisse nur erfasst und nicht gegen Stopp und Buchung geprüft (nur für Modultests der Eingangslogik).
 */
interface DriverEventEffects {
    fun apply(j: JdbcOperations, ctx: RequestContext, r: EffectRequest): EffectResult

    /**
     * Wirksame Wartefrist `no_show_grace_min` in Minuten für die Buchung zum Zeitpunkt [at] (Dienstparameter), `null` = kein freigegebener Wert; dann gilt die Planungsannahme
     * [StafftimeSettings.noShowGraceDefault].
     */
    fun noShowGraceMinutes(j: JdbcOperations, ctx: RequestContext, bookingId: UUID, at: Instant): Int?

    companion object {
        val NONE = object : DriverEventEffects {
            override fun apply(j: JdbcOperations, ctx: RequestContext, r: EffectRequest): EffectResult = EffectResult.Applied(null, null)
            override fun noShowGraceMinutes(j: JdbcOperations, ctx: RequestContext, bookingId: UUID, at: Instant): Int? = null
        }
    }
}
