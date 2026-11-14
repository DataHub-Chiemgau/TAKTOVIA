package de.taktovia.app.analytics

import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * WP-M4-51 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): `GET /v1/analytics/metrics?breakdown=funnel` weist die vier Zählgrößen getrennt (nie als Summe) und die Endzustände aus, Betreiber-
 * und Fahrgaststorno sind unterscheidbar, ein Mandant sieht nur seine Zahlen, der Kanalfilter ist für den Trichter 400, ohne Recht 403.
 */
class FunnelApiTest {
    private val env = IdentityEnv
    private val mapper = JsonMapper.builder().build()
    private val analytics get() = env.server.ctx.getBean(AnalyticsApi::class.java)
    private val service = UUID.randomUUID()

    private class World(val env: IdentityEnv) {
        val tenant = env.tenant("Trichter-" + UUID.randomUUID())
        val planner = UUID.randomUUID(); val driver = UUID.randomUUID()
        init { env.grant(tenant, planner, "planner"); env.grant(tenant, driver, "driver") }
        val plannerToken = env.token(planner); val driverToken = env.token(driver)
    }

    private fun send(t: TenantId, type: String, aggregate: UUID, revision: Long, at: String, payload: Map<String, Any?>) {
        val e = EventEnvelope(UUID.randomUUID(), type, "1.0", aggregate, revision, Instant.parse(at), Instant.parse(at), t.value, t.value, UUID.randomUUID(), mapper.valueToTree(payload))
        analytics.facts.consume(t, e.toJson())
    }

    private fun scenario(t: TenantId) {
        val at = "2026-12-01T05:00:00Z"
        val attempts = (1..3).map { UUID.randomUUID() }
        attempts.forEach { a -> send(t, "booking.attempt_recorded", a, 1, at, mapOf("attempt_id" to a.toString(), "service_id" to service.toString(), "outcome" to "RECORDED", "time_mode" to "DEPARTURE", "requested_at" to at, "passengers" to 1, "channel" to "WEB")) }
        val rejected = UUID.randomUUID()
        send(t, "booking.attempt_recorded", rejected, 1, at, mapOf("attempt_id" to rejected.toString(), "service_id" to service.toString(), "outcome" to "REJECTED", "primary_reason" to "NO_CAPACITY", "time_mode" to "DEPARTURE", "requested_at" to at, "passengers" to 1, "channel" to "WEB"))
        val offers = attempts.map { UUID.randomUUID() }
        offers.forEachIndexed { i, o -> send(t, "booking.offer_created", o, 1, "2026-12-01T05:01:00Z", mapOf("offer_id" to o.toString(), "attempt_id" to attempts[i].toString(), "service_id" to service.toString(), "expires_at" to "2026-12-01T05:16:00Z", "pickup_earliest" to "2026-12-01T06:00:00Z", "pickup_latest" to "2026-12-01T06:10:00Z", "gross_minor" to 500, "currency" to "EUR")) }
        val bookings = (1..2).map { UUID.randomUUID() }
        bookings.forEachIndexed { i, b -> send(t, "booking.confirmed", b, 3, "2026-12-01T05:02:00Z", mapOf("booking_id" to b.toString(), "offer_id" to offers[i].toString(), "service_id" to service.toString(), "pickup_earliest" to "2026-12-01T06:00:00Z", "pickup_latest" to "2026-12-01T06:10:00Z", "gross_minor" to 500, "currency" to "EUR")) }
        send(t, "booking.cancelled", bookings[0], 5, "2026-12-01T05:30:00Z", mapOf("booking_id" to bookings[0].toString(), "reason" to "PASSENGER_REQUEST", "reason_code" to "PASSENGER_REQUEST", "cause_class" to "FAHRGAST", "initiator" to "PASSENGER"))
        send(t, "booking.cancelled", bookings[1], 5, "2026-12-01T05:31:00Z", mapOf("booking_id" to bookings[1].toString(), "reason" to "OPERATOR_CANCELLATION", "reason_code" to "OPERATOR_CANCELLATION", "cause_class" to "BETREIBER", "initiator" to "OPERATOR"))
    }

    private val q = "from=2026-12-01&to=2026-12-01&breakdown=funnel"

    @Test
    fun trichter_weist_vier_zaehlgroessen_getrennt_und_endzustaende_aus() {
        val w = World(env); scenario(w.tenant)
        val r = env.get("/v1/analytics/metrics?$q", w.plannerToken)
        assertEquals(200, r.status, r.text)
        val f = r.json["funnel"]
        assertEquals(setOf("requests", "offers", "confirmed_bookings", "completed_bookings"), f["counts"].properties().map { it.key }.toSet(), "genau die vier Größen, keine Summe")
        assertEquals(listOf(4, 3, 2, 0), listOf("requests", "offers", "confirmed_bookings", "completed_bookings").map { f["counts"][it].asInt() })
        val e = f["end_states"]
        assertEquals(1, e["request_rejected"].asInt()); assertEquals(1, e["offer_not_booked"].asInt()); assertEquals(0, e["payment_failed"].asInt())
        assertEquals(1, e["cancelled_by_passenger"].asInt()); assertEquals(1, e["cancelled_by_operator"].asInt(), "Betreiber- und Fahrgaststorno getrennt")
        assertEquals("OK", f["state"].asString()); assertEquals("2026-12-01T05:00:00Z", f["as_of"].asString(), "Datenstand = älteste der drei Quellen (konservativ)"); assertTrue(r.json["rejections"].isNull)
        // die Kennzahlen mit Stufenbezug sind ausführbar und tragen Definitionsversion, Zähler und Nenner
        val offerRate = r.json["values"].single { it["metric_id"].asString() == "offer_rate" }
        assertEquals("OK", offerRate["state"].asString()); assertEquals(3, offerRate["numerator"].asInt()); assertEquals(4, offerRate["denominator"].asInt())
    }

    @Test
    fun mandanten_getrennt_kanalfilter_unzulaessig_und_recht() {
        val a = World(env); val b = World(env); scenario(a.tenant)
        val fb = env.get("/v1/analytics/metrics?$q", b.plannerToken).json["funnel"]
        assertEquals(0, fb["counts"]["requests"].asInt()); assertEquals("NOT_COMPUTABLE", fb["state"].asString()); assertTrue(fb["as_of"].isNull)
        assertEquals(400, env.get("/v1/analytics/metrics?$q&channel=WEB", a.plannerToken).status, "Buchungen kennen keinen Kanal")
        assertEquals(403, env.get("/v1/analytics/metrics?$q", a.driverToken).status)
        assertEquals(422, env.get("/v1/analytics/metrics?from=2026-12-01&breakdown=unbekannt", a.plannerToken).status, "Aufzählung wird schon von der Schemaprüfung der Anfrage abgelehnt")
        assertFalse(env.get("/v1/analytics/metrics?from=2026-12-01&to=2026-12-01", a.plannerToken).json["funnel"].isObject, "ohne breakdown kein Trichter")
    }
}
