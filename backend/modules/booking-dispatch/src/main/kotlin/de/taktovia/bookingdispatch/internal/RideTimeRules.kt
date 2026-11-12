package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.RejectionCode
import de.taktovia.bookingdispatch.api.RideBookingKind
import de.taktovia.bookingdispatch.api.RideOfferParameters
import de.taktovia.bookingdispatch.api.RideTimeKind
import de.taktovia.bookingdispatch.api.RideTimeWindow
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

internal sealed interface TimeDecision {
    data class Ok(val bookingKind: RideBookingKind, val pickup: RideTimeWindow, val arrival: RideTimeWindow) : TimeDecision
    data class Reject(val code: RejectionCode) : TimeDecision
}

/**
 * Zeitfenster und Fristen einer Anfrage (WP-M2-11, BKG-001), rein und ohne Datenbank.
 *
 * **Fenster:** Breite des Abholfensters = `pickup_window_min`; die Direktfahrzeit `d` verschiebt das Ankunftsfenster.
 *  - Abfahrtswunsch `t`: Abholung `[t, t + W]`, Ankunft `[t + d, t + W + d]`.
 *  - Ankunftswunsch `t`: Ankunft `[t - W, t]`, Abholung `[t - W - d, t - d]` (spätestens `t` am Ziel).
 * Die Ankunftstoleranz `arrival_grace_min` liegt über dem späten Rand des Ankunftsfensters und wird getrennt ausgewiesen (wie in der Zusage, WP-M2-19).
 *
 * **Fristen:** Sofort (Abfahrtswunsch höchstens 5 Minuten von jetzt; Ankunftswunsch, dessen Abholung sofort beginnen müsste) hat keinen Mindestvorlauf; Voraus verlangt `Abholung >= jetzt + min_booking_lead_min` (Abfahrtswunsch: der Wunsch
 * selbst; Ankunftswunsch: der späte Rand des Abholfensters, der frühe Rand wird auf die Frist angehoben) und `Wunsch <= jetzt + max_booking_horizon_days` (kalendertageweise in
 * der Dienstzone, DST-fest). Grenzwerte sind zulässig (gleich ist innerhalb). Ein Wunsch in der Vergangenheit ist `LEAD_TIME`, ein Ankunftswunsch, dessen Abholung schon
 * vorbei sein müsste, `WINDOW_NOT_FEASIBLE`.
 */
internal object RideTimeRules {
    private val TOLERANCE = Duration.ofMinutes(RideOfferParameters.IMMEDIATE_TOLERANCE_MIN)

    /** Sofort oder Voraus ohne Fristprüfung (für abgelehnte Anfragen): Abfahrtswunsch höchstens 5 Minuten von jetzt, Ankunftswunsch mit sofortigem Abholbeginn. */
    fun bookingKind(now: Instant, kind: RideTimeKind, desired: Instant, p: RideOfferParameters, directSeconds: Int): RideBookingKind {
        val tol = TOLERANCE
        return when (kind) {
            RideTimeKind.DEPARTURE -> if (!desired.isBefore(now.minus(tol)) && !desired.isAfter(now.plus(tol))) RideBookingKind.IMMEDIATE else RideBookingKind.ADVANCE
            RideTimeKind.ARRIVAL -> {
                val pickupWish = desired.minus(Duration.ofMinutes(p.pickupWindowMin.toLong())).minus(Duration.ofSeconds(directSeconds.toLong()))
                if (!pickupWish.isAfter(now.plus(tol))) RideBookingKind.IMMEDIATE else RideBookingKind.ADVANCE
            }
        }
    }

    fun evaluate(now: Instant, zone: ZoneId, kind: RideTimeKind, desired: Instant, p: RideOfferParameters, directSeconds: Int): TimeDecision {
        val width = Duration.ofMinutes(p.pickupWindowMin.toLong())
        val direct = Duration.ofSeconds(directSeconds.toLong())
        val lead = Duration.ofMinutes(p.minBookingLeadMin.toLong())
        val horizonEnd = now.atZone(zone).plusDays(p.maxBookingHorizonDays.toLong()).toInstant()
        return when (kind) {
            RideTimeKind.DEPARTURE -> {
                val immediate = !desired.isBefore(now.minus(TOLERANCE)) && !desired.isAfter(now.plus(TOLERANCE))
                when {
                    immediate -> {
                        val start = maxOf(desired, now)
                        TimeDecision.Ok(RideBookingKind.IMMEDIATE, RideTimeWindow(start, start.plus(width)), RideTimeWindow(start.plus(direct), start.plus(width).plus(direct)))
                    }
                    desired.isBefore(now) -> TimeDecision.Reject(RejectionCode.LEAD_TIME)
                    desired.isBefore(now.plus(lead)) -> TimeDecision.Reject(RejectionCode.LEAD_TIME)
                    desired.isAfter(horizonEnd) -> TimeDecision.Reject(RejectionCode.HORIZON)
                    else -> TimeDecision.Ok(RideBookingKind.ADVANCE, RideTimeWindow(desired, desired.plus(width)), RideTimeWindow(desired.plus(direct), desired.plus(width).plus(direct)))
                }
            }
            RideTimeKind.ARRIVAL -> {
                val pickupLatest = desired.minus(direct)
                val pickupWish = desired.minus(width).minus(direct)
                val allowedFrom = now.plus(lead)
                when {
                    desired.isAfter(horizonEnd) -> TimeDecision.Reject(RejectionCode.HORIZON)
                    pickupLatest.isBefore(now) -> TimeDecision.Reject(RejectionCode.WINDOW_NOT_FEASIBLE)
                    // Sofort: der Fahrgast müsste (fast) jetzt losfahren, der frühe Rand wird auf jetzt angehoben, kein Vorlauf
                    !pickupWish.isAfter(now.plus(TOLERANCE)) -> {
                        val pe = maxOf(pickupWish, now)
                        TimeDecision.Ok(RideBookingKind.IMMEDIATE, RideTimeWindow(pe, pickupLatest), RideTimeWindow(pe.plus(direct), desired))
                    }
                    pickupLatest.isBefore(allowedFrom) -> TimeDecision.Reject(RejectionCode.LEAD_TIME)
                    else -> {
                        val pe = maxOf(pickupWish, allowedFrom)
                        TimeDecision.Ok(RideBookingKind.ADVANCE, RideTimeWindow(pe, pickupLatest), RideTimeWindow(pe.plus(direct), desired))
                    }
                }
            }
        }
    }
}
