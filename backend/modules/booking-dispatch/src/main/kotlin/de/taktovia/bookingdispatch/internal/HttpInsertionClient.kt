package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.InsertionWorker
import de.taktovia.bookingdispatch.api.RoutingException
import de.taktovia.bookingdispatch.api.RoutingFailure
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * Synchroner Client zur Einfügeheuristik des Routing-Workers (`POST /v1/insert`, WP-M2-17) mit hartem Zeitbudget (Aufrufbudget plus Übertragungsreserve). Fehlerabbildung wie
 * beim Routing: 504/Timeout `PROVIDER_TIMEOUT`, 422 `SCOPE_VIOLATION`/400 als technischer Fehler `PROVIDER_UNAVAILABLE` (nie ein Plan ohne Prüfung).
 */
internal class HttpInsertionClient(private val baseUrl: String, private val transferReserve: Duration = Duration.ofMillis(500)) : InsertionWorker {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    override fun insert(requestJson: String, budget: Duration): String {
        val req = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + "/v1/insert")).timeout(budget.plus(transferReserve)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestJson)).build()
        val resp = try {
            http.send(req, HttpResponse.BodyHandlers.ofString())
        } catch (e: HttpTimeoutException) {
            throw RoutingException(RoutingFailure.PROVIDER_TIMEOUT, "Einfügeheuristik antwortet nicht innerhalb des Budgets")
        } catch (e: java.io.IOException) {
            throw RoutingException(RoutingFailure.PROVIDER_UNAVAILABLE, "Routing-Worker nicht erreichbar")
        }
        if (resp.statusCode() != 200) {
            throw RoutingException(if (resp.statusCode() == 504) RoutingFailure.PROVIDER_TIMEOUT else RoutingFailure.PROVIDER_UNAVAILABLE, "Einfügeheuristik: HTTP ${resp.statusCode()}")
        }
        return resp.body()
    }
}
