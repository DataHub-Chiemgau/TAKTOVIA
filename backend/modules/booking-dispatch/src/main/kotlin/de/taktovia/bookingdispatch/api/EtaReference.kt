package de.taktovia.bookingdispatch.api

import de.taktovia.bookingdispatch.internal.eta.EtaServicesFactory
import de.taktovia.config.api.EffectiveConfig
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.readports.PositionReadPort
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

/*
 * ETA-Grundmodell (WP-M4-61, LH-I.3-ETA-001): fahrerneutrale Referenzfahrzeit. Dieses Paket und `internal.eta` kennen kein Fahrerprofil und keine Fahrer-Kennung
 * (Architekturtest EtaArchitectureTest, D-10: P1 ohne individuelles Fahrerprofil). Modulzuordnung laut OP-34: die Tabelle gehört `analytics`, der Aufrufer ist die Disposition,
 * der Baustein liegt bis zur Entscheidung im Aufrufermodul `booking-dispatch`.
 */

/**
 * Definierte Gründe, aus denen keine Prognose geliefert wird oder die Prognose nicht aus Live-Daten stammt (Kap. 01 §1.6): nie ein geschätzter Wert an ihrer Stelle.
 * [SPEED_LIMIT_MISSING] ist das Verhalten bei einem Abschnitt ohne Geschwindigkeitsbegrenzung in den Routendaten (fail-closed, Vorschlag aus dem Vertrag LH-I.3-ETA-002).
 */
enum class EtaFallbackReason {
    NO_APPROVED_MODEL, ROAD_DATA_VERSION_MISMATCH, ROAD_DATA_VERSION_NOT_APPROVED, ROUTING_TIMEOUT, ROUTING_UNAVAILABLE, NO_ROUTE, ROUTING_REQUEST_INVALID,
    SPEED_LIMIT_DATA_UNAVAILABLE, SPEED_LIMIT_MISSING, ROUTE_DATA_INCONSISTENT, POSITION_MISSING, POSITION_STALE,
}

/** Abschnitt der Routendaten mit der zulässigen Höchstgeschwindigkeit; `null` = die Routendaten nennen keine Begrenzung (wird nie als hohes Limit angenommen). */
data class EtaRoadSegment(val lengthMeters: Int, val speedLimitKmh: Int?)

/**
 * Route des Routingadapters (WP-M2-10) in genau einer [roadDataVersion]. [timeSeconds] ist die rechtmäßige Routingzeit; [segments] tragen die Geschwindigkeitsattribute der Routendaten
 * (`null`: der Anbieter liefert sie nicht; heute der Fall für den Routing-Worker, bis D-09 entschieden ist).
 */
data class EtaRoadRoute(val roadDataVersion: String, val timeSeconds: Int, val distanceMeters: Int, val segments: List<EtaRoadSegment>?, val provider: String)

/** Die angeforderte Straßendatenversion steht nicht mehr (oder noch nicht) zur Verfügung. */
class EtaRoadDataVersionException(message: String) : RuntimeException(message)

/**
 * Routingadapter-Sicht des ETA-Grundmodells. Eingaben ausschließlich Strecke, Zeit, Fahrzeugprofil und Straßendatenversion. [roadDataVersion] `null`: der Anbieter wählt seine aktuelle
 * Version; sonst muss genau diese geliefert werden (sonst [EtaRoadDataVersionException]). Fehler des Routingdienstes: `RoutingException` (WP-M2-10).
 */
fun interface EtaRoadRouteSource {
    fun route(ctx: RequestContext, originStop: String, destinationStop: String, vehicleProfile: String, at: Instant, roadDataVersion: String?): EtaRoadRoute
}

/** Quantile der Sicherheitsreserve (`eta_model_quantile`). */
enum class EtaQuantile(val code: String) {
    P50("p50"), P75("p75"), P85("p85"), P90("p90");

    companion object {
        fun of(code: String?): EtaQuantile? = entries.firstOrNull { it.code == code }
    }
}

/** Freigegebene Revision des Grundmodells (`eta_model_revision`, nur lesend). */
data class EtaModelRevisionView(
    val id: UUID, val version: Int, val roadDataVersion: String?, val defaultStopDwellSec: Int, val defaultWheelchairBoardingSec: Int,
    val bufferPctByQuantile: Map<EtaQuantile, BigDecimal>, val minBufferSec: Int,
)

/** Eingaben der Referenzfahrzeit: Strecke, Zeit, Fahrzeugprofil, Straßendatenversion. Es gibt bewusst keine Fahrer-Angabe (der [RequestContext] ist die Anfrage, kein Eingabewert). */
data class EtaReferenceRequest(val originStop: String, val destinationStop: String, val vehicleProfile: String, val at: Instant, val roadDataVersion: String? = null)

/**
 * Referenzfahrzeit mit Modellversion und Straßendatenversion. Genau eines von [timeSeconds] und [fallbackReason] ist gesetzt. [timeSeconds] liegt nie unter [legalMinimumSeconds]
 * (rechtmäßige Mindestfahrzeit aus Länge und zulässiger Geschwindigkeit je Abschnitt, soweit die Routendaten Limits tragen).
 */
data class EtaReferenceTime(
    val timeSeconds: Int?, val distanceMeters: Int?, val legalMinimumSeconds: Int?, val modelVersion: Int?, val roadDataVersion: String?, val provider: String?,
    val segments: List<EtaRoadSegment>?, val fallbackReason: EtaFallbackReason?,
) {
    init { require((timeSeconds == null) == (fallbackReason != null)) { "Referenzzeit und Fallbackgrund schließen sich aus" } }
}

/** Fahrerneutrale Referenzfahrzeit. Determinismus: gleiche Eingaben, gleiche Datenversion, gleiche Modellrevision ergeben dieselbe Grundzeit. */
interface EtaReferenceModel {
    fun reference(ctx: RequestContext, request: EtaReferenceRequest): EtaReferenceTime
}

/** Lesender Zugriff auf die freigegebene Modellrevision des Mandanten. */
interface EtaModelRevisions {
    /** Höchste freigegebene Revision des Mandanten im Kontext oder `null`. */
    fun current(ctx: RequestContext): EtaModelRevisionView?
}

/** Zusammenstellung der ETA-Bausteine (WP-M4-61/-62). */
interface EtaApi {
    val revisions: EtaModelRevisions
    val reference: EtaReferenceModel
    val forecast: EtaForecaster
    /** Leseport „ETA je Stopp“; nur mit Positionsquelle und Stopp-Schlüsseln. */
    val stopEta: EtaStopEtaPort?

    companion object {
        fun create(
            ctxTx: ContextTransactions, config: EffectiveConfig, roads: EtaRoadRouteSource, clock: Clock = Clock.systemUTC(),
            runs: RunPlans? = null, positions: PositionReadPort? = null, stopKeys: EtaStopKeys? = null,
        ): EtaApi = EtaServicesFactory.create(ctxTx, config, roads, clock, runs, positions, stopKeys)

        /** Straßendatenquelle aus dem Routingport: ohne Geschwindigkeitsattribute (der Worker liefert sie nicht), die Prognose bleibt dann fail-closed (`SPEED_LIMIT_DATA_UNAVAILABLE`). */
        fun roadsFrom(routing: RoutingPort): EtaRoadRouteSource = EtaServicesFactory.roadsFrom(routing)
    }
}
