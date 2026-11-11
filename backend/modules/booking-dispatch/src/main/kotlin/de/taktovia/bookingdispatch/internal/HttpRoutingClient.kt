package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.MatrixCell
import de.taktovia.bookingdispatch.api.MatrixInfo
import de.taktovia.bookingdispatch.api.RouteInfo
import de.taktovia.bookingdispatch.api.RoutingException
import de.taktovia.bookingdispatch.api.RoutingFailure
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.UUID

/**
 * Synchroner Client zum Routing-Worker (`workers/routing`, `server.py`) mit hartem Zeitbudget je Aufruf. Der Worker kennt nur den Mandanten für den Cache-Schlüssel und hat
 * keinen Datenbankzugriff. Fehler werden auf [RoutingFailure] abgebildet: 422 (NOT_A_STOP, NO_ROUTE, UNKNOWN_PROFILE), 504/Timeout (PROVIDER_TIMEOUT), sonst PROVIDER_UNAVAILABLE.
 * Nie ein Schätzwert, nie ein Ergebnis ohne Kartendatenversion.
 */
internal class HttpRoutingClient(private val baseUrl: String, private val budget: Duration = Duration.ofMillis(900)) {
    private val http = HttpClient.newBuilder().connectTimeout(budget).build()
    private val json = JsonMapper.builder().build()

    private fun call(method: String, path: String, body: String?): JsonNode {
        val req = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path)).timeout(budget).header("Content-Type", "application/json")
            .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)).build()
        val resp = try {
            http.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: HttpTimeoutException) {
            throw RoutingException(RoutingFailure.PROVIDER_TIMEOUT, "Routing-Worker antwortet nicht innerhalb $budget")
        } catch (e: java.io.IOException) {
            throw RoutingException(RoutingFailure.PROVIDER_UNAVAILABLE, "Routing-Worker nicht erreichbar")
        }
        val node = runCatching { json.readTree(resp.body()) }.getOrNull()
        if (resp.statusCode() == 200 && node != null) return node
        val code = node?.get("error")?.get("code")?.asString()
        throw RoutingException(
            when (code) {
                "NOT_A_STOP" -> RoutingFailure.NOT_A_STOP
                "NO_ROUTE" -> RoutingFailure.NO_ROUTE
                "UNKNOWN_PROFILE" -> RoutingFailure.UNKNOWN_PROFILE
                "PROVIDER_TIMEOUT" -> RoutingFailure.PROVIDER_TIMEOUT
                else -> if (resp.statusCode() == 504) RoutingFailure.PROVIDER_TIMEOUT else RoutingFailure.PROVIDER_UNAVAILABLE
            },
            "Routing-Worker: HTTP ${resp.statusCode()}",
        )
    }

    private fun items(n: JsonNode): List<JsonNode> = (0 until n.size()).map { n.get(it) }
    private fun q(s: String) = json.writeValueAsString(s)
    private fun qs(l: List<String>) = l.joinToString(",", "[", "]") { q(it) }

    fun mapVersion(): String = call("GET", "/v1/map-version", null).get("map_version")?.asString() ?: throw RoutingException(RoutingFailure.PROVIDER_UNAVAILABLE, "Antwort ohne Kartenversion")

    fun route(tenant: UUID, from: String, to: String, profile: String, walk: Boolean = false): RouteInfo {
        val body = if (walk) """{"tenant_id":${q(tenant.toString())},"from":${q(from)},"to":${q(to)}}"""
        else """{"tenant_id":${q(tenant.toString())},"from":${q(from)},"to":${q(to)},"profile":${q(profile)}}"""
        val n = call("POST", if (walk) "/v1/walk" else "/v1/route", body)
        val version = n.get("map_version")?.asString()
        if (version.isNullOrBlank()) throw RoutingException(RoutingFailure.PROVIDER_UNAVAILABLE, "Antwort ohne Kartenversion")
        return RouteInfo(
            n.get("origin").asString(), n.get("destination").asString(), n.get("time_s").asInt(), n.get("distance_m").asInt(),
            items(n.get("geometry")).map { it.get(0).asDouble() to it.get(1).asDouble() }, n.get("profile").asString(), version, n.get("provider").asString(),
        )
    }

    fun matrix(tenant: UUID, from: List<String>, to: List<String>, profile: String): MatrixInfo {
        val n = call("POST", "/v1/matrix", """{"tenant_id":${q(tenant.toString())},"from":${qs(from)},"to":${qs(to)},"profile":${q(profile)}}""")
        val version = n.get("map_version")?.asString()
        if (version.isNullOrBlank()) throw RoutingException(RoutingFailure.PROVIDER_UNAVAILABLE, "Antwort ohne Kartenversion")
        val cells = items(n.get("cells")).map { row -> items(row).map { c -> if (c.isNull) null else MatrixCell(c.get("time_s").asInt(), c.get("distance_m").asInt()) } }
        return MatrixInfo(from, to, cells, n.get("profile").asString(), version, n.get("provider").asString())
    }
}
