package de.taktovia.app.geo

import de.taktovia.platform.api.InMemoryRateLimitStore
import de.taktovia.platform.api.Quota
import de.taktovia.platform.api.RateLimitStore
import de.taktovia.platform.api.RateLimiter
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.servicegeo.api.GeoPoint
import de.taktovia.servicegeo.api.GeocodeCandidate
import de.taktovia.servicegeo.api.Geocoder
import de.taktovia.servicegeo.api.PlaceType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Quelle für Adresse und POI (WP-M2-38). `taktovia.geocoding.provider`: `none` (Standard; Adress-/POI-Treffer fehlen, Haltestelle, Favorit und Kartenpunkt funktionieren) oder
 * `simulator` (feste Beispieltreffer für Entwicklung und Tests, nie in Produktion). Ein OSM-gestützter Adapter folgt, sobald D-09 (Quelle und Lizenz) entschieden ist; er
 * implementiert nur [Geocoder] und sendet ausschließlich Suchtext und Suchkoordinate an die Quelle.
 */
@Configuration
class GeocodingConfiguration {
    @Bean fun geocoder(env: Environment): Geocoder = when (val p = env.getProperty("taktovia.geocoding.provider", "none")) {
        "none" -> Geocoder.unavailable()
        "simulator" -> {
            check(env.getProperty("taktovia.environment") !in setOf("production", "staging")) { "Der Geocoder-Simulator ist in Staging und Produktion nicht erlaubt" }
            SimulatorGeocoder()
        }
        else -> error("taktovia.geocoding.provider unbekannt: $p")
    }

    /** Eigene Quote der Ortssuche je Mandant und Akteur (Planungsdefault 30 je Minute, OP-20); getrennte Zähler von der allgemeinen Quote. */
    @Bean fun placeSearchLimiter(store: RateLimitStore, env: Environment) = PlaceSearchLimiter(RateLimiter(store,
        Quota(env.getProperty("taktovia.api.places.rate-limit.requests", Int::class.java, 30), env.getProperty("taktovia.api.places.rate-limit.window-seconds", Int::class.java, 60))))
}

/** Quote der Ortssuche; Überschreitung ergibt 429 mit `Retry-After`. Der Schlüssel enthält nie Suchtext oder Ort. */
class PlaceSearchLimiter(private val limiter: RateLimiter) {
    fun check(tenant: String, actor: String) {
        val d = limiter.check(tenant, "places:$actor")
        if (!d.allowed) throw ProblemException(ErrorCode.RATE_LIMITED, "Die Quote der Ortssuche ist ausgeschöpft; nach Ablauf von Retry-After erneut senden.", headers = mapOf("Retry-After" to d.retryAfterSeconds.toString()))
    }
}

/** Deterministischer Ersatz für Entwicklung und Tests: wenige feste Orte in Dresden; Verhalten (Ausfall) einstellbar. Kein Ersatz für eine echte Quelle. */
class SimulatorGeocoder : Geocoder {
    @Volatile var failing: Boolean = false
    private val asOf = Instant.parse("2026-01-01T00:00:00Z")
    private val entries = listOf(
        GeocodeCandidate(PlaceType.ADDRESS, "Hauptstraße 1, Dresden", 51.05, 13.74, "simulator", asOf),
        GeocodeCandidate(PlaceType.ADDRESS, "Königstraße 10, Dresden", 51.06, 13.75, "simulator", asOf),
        GeocodeCandidate(PlaceType.POI, "Zwinger", 51.053, 13.734, "simulator", asOf),
        GeocodeCandidate(PlaceType.POI, "Frauenkirche", 51.0519, 13.7416, "simulator", asOf),
    )
    val calls = AtomicLong()

    override fun search(text: String, bias: GeoPoint?, limit: Int): List<GeocodeCandidate> {
        calls.incrementAndGet(); if (failing) throw IllegalStateException("simulierter Ausfall")
        return entries.filter { it.label.contains(text, ignoreCase = true) }.take(limit)
    }
    override fun reverse(point: GeoPoint): GeocodeCandidate? {
        calls.incrementAndGet(); if (failing) throw IllegalStateException("simulierter Ausfall")
        return entries.minByOrNull { (it.latitude - point.latitude) * (it.latitude - point.latitude) + (it.longitude - point.longitude) * (it.longitude - point.longitude) }
            ?.takeIf { kotlin.math.abs(it.latitude - point.latitude) < 0.002 && kotlin.math.abs(it.longitude - point.longitude) < 0.002 }
    }
}
