package de.taktovia.bookingdispatch.internal.eta

import de.taktovia.bookingdispatch.api.EtaBasis
import de.taktovia.bookingdispatch.api.EtaFallbackPolicy
import de.taktovia.bookingdispatch.api.EtaFallbackReason
import de.taktovia.bookingdispatch.api.EtaForecast
import de.taktovia.bookingdispatch.api.EtaForecastRequest
import de.taktovia.bookingdispatch.api.EtaForecastStop
import de.taktovia.bookingdispatch.api.EtaForecaster
import de.taktovia.bookingdispatch.api.EtaStopEta
import de.taktovia.bookingdispatch.api.EtaStopEtaPort
import de.taktovia.bookingdispatch.api.EtaStopEtaReport
import de.taktovia.bookingdispatch.api.EtaStopKeys
import de.taktovia.bookingdispatch.api.EtaUnavailableException
import de.taktovia.bookingdispatch.api.RunPlans
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.readports.PositionReadPort
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Leseport „ETA je Stopp“ (WP-M4-62). Datenstand ist die Messzeit der letzten Position des Fahrzeugs; ist sie älter als `eta_max_data_age_sec` (oder fehlt sie), gibt der Port den
 * Fallbackgrund aus und greift laut `eta_fallback_policy` auf die Planzeit bzw. die Referenzprognose ab dem Plananker zurück, statt eine scheinbar aktuelle Prognose zu zeigen.
 * Die verbindliche Zusage ([EtaStopEta.promisedAt], Planzeit der Planrevision) wird nie verändert.
 */
internal class DefaultEtaStopEtaPort(
    private val runs: RunPlans, private val positions: PositionReadPort, private val stopKeys: EtaStopKeys, private val forecaster: EtaForecaster, private val clock: Clock,
) : EtaStopEtaPort {
    override fun etaPerStop(ctx: RequestContext, runId: UUID, vehicleProfile: String, serviceId: UUID?, fromSeq: Int): EtaStopEtaReport {
        val now = clock.instant()
        val run = runs.run(ctx, runId)
        val plan = runs.currentPlan(ctx, runId)
        val stops = plan.stops.sortedBy { it.seq }
        require(fromSeq in 0 until maxOf(1, stops.size)) { "fromSeq außerhalb des Plans" }
        val position = positions.latest(ctx, run.vehicleId)
        fun plannedOnly(reason: EtaFallbackReason?, model: Int?, config: String?, road: String?) = EtaStopEtaReport(
            runId, plan.revision, now, position?.measuredAt, stops.filter { it.seq >= fromSeq }.map { EtaStopEta(it.seq, it.stopRevisionId, it.plannedAt, it.plannedAt, it.plannedAt, EtaBasis.PLAN_TIME) },
            model, config, road, reason,
        )
        if (stops.size - fromSeq < 2) return plannedOnly(null, null, null, null)   // höchstens ein Stopp übrig: nichts zu prognostizieren, die Zusage gilt
        val snapshot = try { forecaster.snapshot(ctx, serviceId, run.vehicleId, now) } catch (e: EtaUnavailableException) { return plannedOnly(e.reason, null, null, null) }

        val staleReason = when {
            position == null -> EtaFallbackReason.POSITION_MISSING
            Duration.between(position.measuredAt, now) > Duration.ofSeconds(snapshot.settings.maxDataAgeSec.toLong()) -> EtaFallbackReason.POSITION_STALE
            else -> null
        }
        val anchor = stops[fromSeq].plannedAt
        // Frische Position: nicht vor der aktuellen Zeit; sonst (Rückfall) ab dem Plananker ohne Live-Bezug
        val startAt = if (staleReason == null) maxOf(anchor, now) else anchor
        if (staleReason != null && snapshot.settings.fallbackPolicy == EtaFallbackPolicy.PLAN_TIME) {
            return plannedOnly(staleReason, snapshot.model.version, snapshot.configVersion, null)
        }
        val remaining = stops.filter { it.seq >= fromSeq }
        val request = EtaForecastRequest(
            remaining.map { EtaForecastStop(stopKeys.keyOf(ctx, it.stopRevisionId), (it.boardings + it.alightings).any { m -> m.vector.wheelchair > 0 }) },
            vehicleProfile, startAt, serviceId, run.vehicleId,
        )
        val f: EtaForecast = forecaster.forecast(ctx, request, snapshot)
        if (f.fallbackReason != null) return plannedOnly(f.fallbackReason, f.modelVersion, f.configVersion, f.roadDataVersion)
        val basis = if (staleReason == null) EtaBasis.LIVE_POSITION else EtaBasis.REFERENCE_FORECAST
        return EtaStopEtaReport(
            runId, plan.revision, now, position?.measuredAt,
            remaining.zip(f.stops).map { (p, s) -> EtaStopEta(p.seq, p.stopRevisionId, p.plannedAt, s.arrivalEarliest, s.arrivalLatest, basis) },
            f.modelVersion, f.configVersion, f.roadDataVersion, staleReason,
        )
    }
}
