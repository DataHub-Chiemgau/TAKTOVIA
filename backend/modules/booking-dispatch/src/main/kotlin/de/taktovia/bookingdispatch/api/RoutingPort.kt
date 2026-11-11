package de.taktovia.bookingdispatch.api

import de.taktovia.platform.context.RequestContext

/** Fehlerklassen des Routings (LH-6.5-OPT-007): definiert und stabil, nie ein geschätztes Ergebnis. Ein Routingfehler ist ein technischer Fehler (OPT-011). */
enum class RoutingFailure { NOT_A_STOP, NO_ROUTE, UNKNOWN_PROFILE, PROVIDER_TIMEOUT, PROVIDER_UNAVAILABLE }

class RoutingException(val failure: RoutingFailure, message: String) : RuntimeException(message)

/** Ergebnis einer Einzelroute. Start und Ziel sind definierte Haltepunkte (`origin`, `destination`); Profil und Kartendatenversion gehören zum Ergebnis. */
data class RouteInfo(
    val origin: String, val destination: String, val timeSeconds: Int, val distanceMeters: Int,
    val geometry: List<Pair<Double, Double>>, val profile: String, val mapVersion: String, val provider: String,
)

data class MatrixCell(val timeSeconds: Int, val distanceMeters: Int)

/** `cells[i][j]` gehört zu `origins[i]` nach `destinations[j]`; `null` = nicht erreichbar (kein Schätzwert). */
data class MatrixInfo(
    val origins: List<String>, val destinations: List<String>, val cells: List<List<MatrixCell?>>, val profile: String, val mapVersion: String, val provider: String,
)

/**
 * Port `RoutingPort` (WP-M2-10): Fahrzeiten, Distanzen und Wege über Straßengraph, Fahrtrichtungen, Fahrzeugprofil und definierte Haltepunkte. Aufrufart zum Routing-Worker:
 * synchron mit hartem Zeitbudget (Empfehlung Spike (b), innerhalb der 1 s lokalen Kandidatensuche, Planungsdefault Kap. 01 §1.4). Ergebnisse werden mandantengebunden
 * gecacht (PostgreSQL `route_matrix_cache`, Redis nur flüchtig); ein Eintrag mit anderer Kartendatenversion wird nie geliefert. Fehler: [RoutingException].
 */
interface RoutingPort {
    fun route(ctx: RequestContext, originStop: String, destinationStop: String, profile: String): RouteInfo

    /** Fußweg zwischen zwei Haltepunkten (Profil `walk`). */
    fun walk(ctx: RequestContext, originStop: String, destinationStop: String): RouteInfo

    fun matrix(ctx: RequestContext, originStops: List<String>, destinationStops: List<String>, profile: String): MatrixInfo
}
