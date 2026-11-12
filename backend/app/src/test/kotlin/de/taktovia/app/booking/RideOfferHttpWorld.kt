package de.taktovia.app.booking

import de.taktovia.app.geo.GeoWorld
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Testwelt der Anfrageerfassung über die echte Anwendung (WP-M2-11 bis -13): Dienst rund um die Uhr, Gebiet, zwei Haltepunkte, ein Fahrgast. Eigene Klasse, keine Duplikate fremder Fixtures. */
internal class RideOfferHttpWorld {
    val w = GeoWorld("ride-" + UUID.randomUUID())
    val service: String = w.service()

    init {
        check(w.put("/v1/services/$service/operating-hours", HOURS, "0").status == 200)
        check(w.post("/v1/service-areas", """{"service_id":"$service","area":${square(13.70, 51.00, 0.10)},"valid_from":"2026-01-01T00:00:00+01:00","valid_to":null}""").status == 201)
        check(w.post("/v1/stops", stop("Markt", 51.05, 13.74)).status == 201)
        check(w.post("/v1/stops", stop("Bahnhof", 51.08, 13.78)).status == 201)
    }

    fun request(body: String, token: String = w.passenger, key: String = "ride-" + UUID.randomUUID()) =
        w.env.server.send("POST", "/v1/ride-offers", body, w.env.headers(token, extra = mapOf("Idempotency-Key" to key)))

    /** Abfahrtswunsch in [days] Tagen um 10:00 Ortszeit Berlin (RFC 3339 mit Offset). */
    fun later(days: Long, hour: Int = 10): String =
        ZonedDateTime.now(BERLIN).plusDays(days).withHour(hour).withMinute(0).withSecond(0).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun body(desiredAt: String, kind: String = "DEPARTURE", passengers: Int = 2, extra: String = "", serviceId: String = service, zone: String = "Europe/Berlin") =
        """{"service_id":"$serviceId","origin":{"point":{"latitude":51.0501,"longitude":13.7401}},"destination":{"point":{"latitude":51.0801,"longitude":13.7801}},"time_kind":"$kind",""" +
            """"desired":{"desired_at":"$desiredAt","time_zone":"$zone"},"passengers":$passengers$extra}"""

    companion object {
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
        const val HOURS = """{"traffic_product":"AREA_ON_DEMAND","weekly_hours":[{"weekdays":["MON","TUE","WED","THU","FRI","SAT","SUN"],"from":"00:00","to":"24:00"}],"exceptions":[],"valid_from":"2026-01-01T00:00:00+01:00","valid_to":null,"reason":"Test"}"""
        fun square(x: Double, y: Double, s: Double) =
            """{"type":"MultiPolygon","coordinates":[[[[$x,$y],[${x + s},$y],[${x + s},${y + s}],[$x,${y + s}],[$x,$y]]]]}"""
        fun stop(name: String, lat: Double, lon: Double, wheelchair: String = "YES") =
            """{"name":"$name","point":{"latitude":$lat,"longitude":$lon},"accessibility":{"schema_version":"1.0","step_free":"YES","wheelchair_boarding":"$wheelchair"},"traffic_products":["AREA_ON_DEMAND"],"active":true,"valid_from":"2026-01-01T00:00:00+01:00"}"""
    }
}
