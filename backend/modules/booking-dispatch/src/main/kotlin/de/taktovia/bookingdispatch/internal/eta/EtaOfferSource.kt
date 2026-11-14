package de.taktovia.bookingdispatch.internal.eta

import de.taktovia.bookingdispatch.api.EtaFallbackReason
import de.taktovia.bookingdispatch.api.EtaForecastRequest
import de.taktovia.bookingdispatch.api.EtaForecastStop
import de.taktovia.bookingdispatch.api.EtaForecaster
import de.taktovia.bookingdispatch.api.EtaStopKeys
import de.taktovia.bookingdispatch.api.EtaUnavailableException
import de.taktovia.bookingdispatch.api.OfferEta
import de.taktovia.bookingdispatch.api.OfferEtaSource
import de.taktovia.platform.context.RequestContext
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Angebotszeit aus dem Grundmodell (WP-M4-66): ein Snapshot (Modellrevision und wirksame Konfiguration, eine Lesetransaktion), eine Prognose über Abhol- und Zielstopp mit **diesem** Snapshot; so trägt
 * jedes Angebot genau eine Version, auch wenn die Konfiguration während der Angebotsbildung wechselt. Die Prüfroutine „keine Geschwindigkeitsüberschreitung“ (WP-M4-62) läuft für jedes Fenster: die
 * Grundzeit darf die Summe der Mindestfahrzeiten (zulässige Geschwindigkeit je Abschnitt) nie unterschreiten, sonst gilt der Rückfall `ROUTE_DATA_INCONSISTENT`.
 */
internal class EtaOfferSource(private val forecaster: EtaForecaster, private val keys: EtaStopKeys, private val profile: String) : OfferEtaSource {
    override fun ground(ctx: RequestContext, serviceId: UUID, pickupStopRevision: UUID, dropoffStopRevision: UUID, at: Instant): OfferEta {
        val snapshot = try { forecaster.snapshot(ctx, serviceId, null, at) } catch (e: EtaUnavailableException) { return fallback(e.reason, null, null) }
        val request = EtaForecastRequest(listOf(EtaForecastStop(keys.keyOf(ctx, pickupStopRevision)), EtaForecastStop(keys.keyOf(ctx, dropoffStopRevision))), profile, at, serviceId, null)
        val f = forecaster.forecast(ctx, request, snapshot)
        f.fallbackReason?.let { return fallback(it, f.modelVersion, f.configVersion) }
        val direct = Duration.between(at, f.stops[1].arrivalEarliest).seconds.toInt()
        val legalMinimum = f.stops[0].dwellSeconds + f.legs.sumOf { it.legalMinimumSeconds }
        // Prüfroutine am ausgegebenen Fenster: die früheste Ankunft liegt nie unter Haltezeit plus rechtmäßiger Mindestfahrzeit
        if (direct < legalMinimum || f.legs.any { it.referenceSeconds < it.legalMinimumSeconds }) return fallback(EtaFallbackReason.ROUTE_DATA_INCONSISTENT, f.modelVersion, f.configVersion)
        return OfferEta(direct, f.bufferSeconds, f.modelVersion, f.configVersion, null, legalMinimum)
    }

    private fun fallback(reason: EtaFallbackReason, model: Int?, config: String?) = OfferEta(null, 0, model, config, reason)
}
