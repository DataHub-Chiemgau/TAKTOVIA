package de.taktovia.app.eta

import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.EtaApi
import de.taktovia.bookingdispatch.api.EtaRoadRoute
import de.taktovia.bookingdispatch.api.EtaRoadRouteSource
import de.taktovia.bookingdispatch.api.EtaStopKeys
import de.taktovia.bookingdispatch.api.RoutingException
import de.taktovia.bookingdispatch.api.RoutingFailure
import de.taktovia.bookingdispatch.api.RunPlans
import de.taktovia.config.api.ConfigApi
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.readports.PositionReadPort
import de.taktovia.platform.readports.PositionView
import de.taktovia.stafftime.api.StafftimeApi
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Instant
import java.util.UUID

/**
 * Verdrahtung des ETA-Grundmodells (WP-M4-61/-62) und seiner Verbraucher (WP-M4-66): Angebotsbildung, Buchungsdetail (UI-04), Stoppliste des Fahrers, Leitstellenansicht und Auslöser der Benachrichtigung
 * beziehen Fahrzeit und Prognosezeit aus **einem** Baustein. Straßendaten kommen aus dem Routing-Worker (`taktovia.routing.worker-url`); ohne Worker (D-09 offen) liefert die Straßendatenquelle den
 * definierten Ausfall, alle Verbraucher weisen dann den Rückfallgrund aus und behalten Plan- bzw. Planungszeit (nie eine erfundene Prognose). Der Positionsbezug (Datenstand) kommt aus `staff-time`;
 * der Verweis ist lazy, weil `staff-time` die Stoppliste (und damit diese Verdrahtung) selbst braucht.
 */
@Configuration
class EtaConfiguration {

    @Bean fun etaRunPlans(ctxTx: ContextTransactions, outbox: Outbox): RunPlans = BookingdispatchApi.create(ctxTx, outbox = outbox).runs

    /** Datenstand je Fahrzeug aus der letzten Position (nur die Messzeit geht in die Prognose ein, die Koordinate wird nicht ausgewertet). */
    @Bean fun etaPositions(staff: ObjectProvider<StafftimeApi>): PositionReadPort = object : PositionReadPort {
        override fun latest(ctx: RequestContext, vehicleId: UUID): PositionView? =
            staff.getObject().positions.latestOfVehicle(ctx.tenant, vehicleId)?.let { PositionView(vehicleId, NO_SHIFT, it.longitude, it.latitude, it.measuredAt) }
    }

    /** Routing-Schlüssel eines Haltepunkts: die Haltepunktkennung (`stop_revision.stop_id`); der Katalog des Workers ist bis D-09 offen. */
    @Bean fun etaStopKeys(ctxTx: ContextTransactions): EtaStopKeys = EtaStopKeys { ctx, revisionId ->
        ctxTx.run(ctx, readOnly = true) { j -> j.queryForObject("SELECT stop_id::text FROM taktovia.stop_revision WHERE id = ?", String::class.java, revisionId) } ?: revisionId.toString()
    }

    @Bean fun etaRoads(ctxTx: ContextTransactions, outbox: Outbox, env: Environment): EtaRoadRouteSource {
        val url = env.getProperty("taktovia.routing.worker-url")?.takeIf { it.isNotBlank() }
        val routing = url?.let { BookingdispatchApi.create(ctxTx, outbox = outbox, routingWorkerUrl = it, routingBudgetMillis = env.getProperty("taktovia.routing.budget-millis", Long::class.java, 900)).routing }
        return if (routing != null) EtaApi.roadsFrom(routing) else EtaRoadRouteSource { _: RequestContext, _: String, _: String, _: String, _: Instant, _: String? ->
            throw RoutingException(RoutingFailure.PROVIDER_UNAVAILABLE, "Kein Routing-Worker konfiguriert")
        }
    }

    @Bean fun etaApi(ctxTx: ContextTransactions, config: ConfigApi, roads: EtaRoadRouteSource, runs: RunPlans, positions: PositionReadPort, keys: EtaStopKeys): EtaApi =
        EtaApi.create(ctxTx, config.effective, roads, runs = runs, positions = positions, stopKeys = keys)

    private companion object { val NO_SHIFT = UUID(0, 0) }
}
