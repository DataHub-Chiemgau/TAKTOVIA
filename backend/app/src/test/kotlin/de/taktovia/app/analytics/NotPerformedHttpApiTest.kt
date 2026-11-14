package de.taktovia.app.analytics

import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-55 (LH-I.6-KPI-009) über die echte Anwendung (Test-Issuer statt Keycloak, echte Datenbank, Runtime-Rolle): Auswertung nicht durchgeführter Fahrten nach Ursache und Kategorie (`GET /v1/analytics/metrics?breakdown=not_performed`,
 * „Ursache offen“ sichtbar) und die Korrektur (`POST /v1/analytics/not-performed/{booking_id}/corrections`) mit Vermerk, Rechten, Pflichtgrund, Mandantentrennung und parallelen Korrekturen.
 */
class NotPerformedHttpApiTest {
    private val env = IdentityEnv
    private val today = LocalDate.now(ZoneId.of("Europe/Berlin"))

    private class World(val env: IdentityEnv) {
        val tenant = env.tenant("NP-" + UUID.randomUUID()); val dispatcher = UUID.randomUUID(); val analyst = UUID.randomUUID(); val driver = UUID.randomUUID()
        val outage = UUID.randomUUID(); val noShow = UUID.randomUUID(); val open = UUID.randomUUID(); val service = UUID.randomUUID()
        init {
            env.grant(tenant, dispatcher, "dispatcher"); env.grant(tenant, analyst, "planner"); env.grant(tenant, driver, "driver")
            env.tx.run(tenant) { j ->
                fun fact(b: UUID, state: String, reason: String?, cause: String?, noShowAt: Boolean = false) {
                    j.update("INSERT INTO taktovia.fact_booking(tenant_id, booking_id, service_id, confirmed_at, pickup_earliest, cancelled_at, cancel_reason, cause_class, no_show_at, state) VALUES (taktovia.current_tenant(), ?, ?, now(), now(), CASE WHEN ? THEN NULL ELSE now() END, ?, ?, CASE WHEN ? THEN now() END, ?)",
                        b, service, noShowAt, reason, cause, noShowAt, state)
                    j.query("SELECT taktovia.not_performed_refresh(?)", { _, _ -> 0 }, b)
                }
                fact(outage, "CANCELED", "VEHICLE_OUTAGE", "FAHRZEUGAUSFALL"); fact(noShow, "NO_SHOW", null, null, noShowAt = true); fact(open, "CANCELED", "HOLD_EXPIRED", "FAHRGAST")
            }
        }
        val token = env.token(dispatcher); val analystToken = env.token(analyst); val driverToken = env.token(driver)
    }

    private fun correct(w: World, booking: UUID, body: String, token: String = w.token, key: String = "np-" + UUID.randomUUID()) =
        env.post("/v1/analytics/not-performed/$booking/corrections", body, token, extra = mapOf("Idempotency-Key" to key))

    @Test
    fun auswertung_nach_ursache_und_kategorie_mit_ursache_offen_und_ohne_fahrerzuordnung() {
        val w = World(env)
        val r = env.get("/v1/analytics/metrics?from=$today&to=$today&breakdown=not_performed", w.analystToken); assertEquals(200, r.status, r.text)
        val np = r.json["not_performed"]
        assertEquals(3, np["total"].asInt()); assertEquals(1, np["open_count"].asInt()); assertEquals(0, np["conflict_count"].asInt())
        val causes = np["by_cause"].values().associate { it["cause"].asString() to it["category"].asString() }
        assertEquals(mapOf("VEHICLE_OUTAGE" to "OPERATOR", "PASSENGER_NO_SHOW" to "PASSENGER"), causes, "Fahrzeugausfall und Fahrgast-No-Show: verschiedene Ursache und Kategorie")
        val items = np["items"].values().associateBy { it["booking_id"].asString() }
        assertEquals("OPEN", items.getValue(w.open.toString())["cause_state"].asString()); assertTrue(items.getValue(w.open.toString())["cause"].isNull, "Ursache offen: sichtbar, nie ausgeschlossen")
        assertEquals(1, np["rates"]["vehicle_outage_rate"]["numerator"].asInt()); assertEquals(1, np["rates"]["no_show_rate"]["numerator"].asInt())
        np.toString().let { t -> assertFalse(t.contains("driver", true) || t.contains("shift_id") || t.contains("vehicle_id"), "keine Fahrerzuordnung in der Auswertung") }
        // ohne Rolle 403, fremder Mandant sieht nichts
        assertEquals(403, env.get("/v1/analytics/metrics?from=$today&to=$today&breakdown=not_performed", w.driverToken).status)
        val other = World(env)
        assertEquals(3, env.get("/v1/analytics/metrics?from=$today&to=$today&breakdown=not_performed", other.analystToken).json["not_performed"]["total"].asInt(), "der andere Mandant sieht nur die eigenen drei Fahrten")
        assertEquals(0, env.get("/v1/analytics/metrics?from=$today&to=$today&breakdown=not_performed", env.token(UUID.randomUUID().also { env.grant(env.tenant(), it, "planner") })).json["not_performed"]["total"].asInt())
        assertEquals(422, env.get("/v1/analytics/metrics?from=$today&breakdown=gibtesnicht", w.analystToken).status)
    }

    @Test
    fun korrektur_mit_vermerk_rechten_pflichtgrund_und_mandantentrennung() {
        val w = World(env)
        val ok = correct(w, w.open, """{"new_cause":"EXTERNAL_EVENT","reason":"Unwetter, Strecke gesperrt"}"""); assertEquals(200, ok.status, ok.text)
        assertEquals("CORRECTED", ok.json["cause_state"].asString()); assertEquals("EXTERNAL_EVENT", ok.json["cause"].asString()); assertEquals("EXTERNAL", ok.json["category"].asString()); assertEquals("\"2\"", ok.header("ETag"))
        val v = ok.json["corrections"][0]
        assertTrue(v["old_cause"].isNull); assertEquals("EXTERNAL_EVENT", v["new_cause"].asString()); assertEquals("Unwetter, Strecke gesperrt", v["reason"].asString()); assertTrue(v["actor"].asString().startsWith("user:")); assertFalse(v["at"].isNull)
        // zweite Korrektur: der alte Wert bleibt als Vermerk
        val second = correct(w, w.open, """{"new_cause":"OPERATOR_CANCELLATION","reason":"Betreiber hat tatsächlich storniert","expected_revision":2}""")
        assertEquals(200, second.status, second.text); assertEquals("EXTERNAL_EVENT", second.json["corrections"][1]["old_cause"].asString()); assertEquals(2, second.json["corrections"].size())
        // Negativ: ohne Recht 403, ohne Grund 422, unbekannte Ursache 422, veraltet 412, fremder Mandant 404, unbekannte Fahrt 404
        assertEquals(403, correct(w, w.outage, """{"new_cause":"EXTERNAL_EVENT","reason":"Grund"}""", w.driverToken).status)
        assertEquals(403, correct(w, w.outage, """{"new_cause":"EXTERNAL_EVENT","reason":"Grund"}""", w.analystToken).status, "Lesen genügt nicht zum Korrigieren")
        assertEquals(422, correct(w, w.outage, """{"new_cause":"EXTERNAL_EVENT","reason":""}""").status); assertEquals(422, correct(w, w.outage, """{"new_cause":"EXTERNAL_EVENT"}""").status)
        assertEquals(422, correct(w, w.outage, """{"new_cause":"ERFUNDEN","reason":"Grund"}""").status)
        assertEquals(412, correct(w, w.outage, """{"new_cause":"EXTERNAL_EVENT","reason":"Grund","expected_revision":9}""").status)
        assertEquals(404, correct(w, UUID.randomUUID(), """{"new_cause":"EXTERNAL_EVENT","reason":"Grund"}""").status)
        assertEquals(404, correct(World(env), w.outage, """{"new_cause":"EXTERNAL_EVENT","reason":"Grund"}""").status)
        assertEquals(0, w.let { env.string("SELECT count(*) FROM taktovia.not_performed_correction WHERE booking_id = ?::uuid", w.outage).single().toInt() }, "abgewiesene Korrekturen schreiben keinen Vermerk")
        // Idempotenz: dieselbe Anfrage mit demselben Schlüssel wirkt einmal, anderer Inhalt zum selben Schlüssel 409
        val key = "np-" + UUID.randomUUID()
        assertEquals(200, correct(w, w.outage, """{"new_cause":"EXTERNAL_EVENT","reason":"Grund eins"}""", key = key).status)
        assertEquals(200, correct(w, w.outage, """{"new_cause":"EXTERNAL_EVENT","reason":"Grund eins"}""", key = key).status)
        assertEquals(409, correct(w, w.outage, """{"new_cause":"PASSENGER_NO_SHOW","reason":"Grund zwei"}""", key = key).status)
        assertEquals(1, env.string("SELECT count(*) FROM taktovia.not_performed_correction WHERE booking_id = ?::uuid", w.outage).single().toInt())
    }

    @Test
    fun parallele_korrekturen_ueber_http_behalten_beide_vermerke() {
        val w = World(env); val go = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        val rs = listOf("EXTERNAL_EVENT", "OPERATOR_CANCELLATION").map { c -> pool.submit<Int> { go.await(); correct(w, w.open, """{"new_cause":"$c","reason":"Parallele Korrektur $c"}""").status } }
        go.countDown(); assertEquals(listOf(200, 200), rs.map { it.get(60, TimeUnit.SECONDS) }); pool.shutdown()
        val nos = env.string("SELECT correction_no::text FROM taktovia.not_performed_correction WHERE booking_id = ?::uuid ORDER BY correction_no", w.open)
        assertEquals(listOf("1", "2"), nos)
        assertEquals(env.string("SELECT new_cause FROM taktovia.not_performed_correction WHERE booking_id = ?::uuid ORDER BY correction_no DESC LIMIT 1", w.open), env.string("SELECT cause FROM taktovia.not_performed_trip WHERE booking_id = ?::uuid", w.open))
    }
}
