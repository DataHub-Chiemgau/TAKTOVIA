package de.taktovia.bookingdispatch.internal.eta

import de.taktovia.bookingdispatch.api.EtaApi
import de.taktovia.bookingdispatch.api.EtaForecaster
import de.taktovia.bookingdispatch.api.EtaModelRevisions
import de.taktovia.bookingdispatch.api.EtaReferenceModel
import de.taktovia.bookingdispatch.api.EtaRoadRouteSource
import de.taktovia.bookingdispatch.api.EtaStopEtaPort
import de.taktovia.bookingdispatch.api.EtaStopKeys
import de.taktovia.bookingdispatch.api.RoutingPort
import de.taktovia.bookingdispatch.api.RunPlans
import de.taktovia.config.api.EffectiveConfig
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.readports.PositionReadPort
import java.time.Clock

/** Nur innerhalb des Moduls sichtbar (Architekturtest): setzt die ETA-Bausteine zusammen. */
internal object EtaServicesFactory {
    fun create(ctxTx: ContextTransactions, config: EffectiveConfig, roads: EtaRoadRouteSource, clock: Clock, runs: RunPlans?, positions: PositionReadPort?, stopKeys: EtaStopKeys?): EtaApi {
        val revisions = JdbcEtaModelRevisions(ctxTx)
        val reference = DefaultEtaReferenceModel(revisions, roads)
        val forecaster = DefaultEtaForecaster(ctxTx, config, reference, revisions)
        val stopEta: EtaStopEtaPort? = if (runs != null && positions != null && stopKeys != null) DefaultEtaStopEtaPort(runs, positions, stopKeys, forecaster, clock) else null
        return object : EtaApi {
            override val revisions: EtaModelRevisions = revisions
            override val reference: EtaReferenceModel = reference
            override val forecast: EtaForecaster = forecaster
            override val stopEta: EtaStopEtaPort? = stopEta
        }
    }

    fun roadsFrom(routing: RoutingPort): EtaRoadRouteSource = RoutingEtaRoadRouteSource(routing)
}
