package de.taktovia.app.driver

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-07 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): Schichtzuweisung der Leitstelle mit eindeutigen Schichtgrenzen. 409 bei doppelter Fahrerschicht (Originalabnahme)
 * und doppelter Fahrzeugbelegung, Ausnahme mit Genehmiger und Grund, aneinandergrenzende Schichten, 422 bei fehlenden Zeiten, 404 für fremde Mandanten, Rechte (nur Disposition schreibt),
 * Revisionsschutz, Audit, und zwei gleichzeitige HTTP-Anfragen legen höchstens eine von zwei überlappenden Schichten an.
 */
class ShiftApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())

    private class World(val env: IdentityEnv) {
        val tenant: TenantId = env.tenant("Schicht-" + UUID.randomUUID())
        val dispatcher = UUID.randomUUID(); val planner = UUID.randomUUID(); val driverAccount = UUID.randomUUID(); val callcenter = UUID.randomUUID()
        init { env.grant(tenant, dispatcher, "dispatcher"); env.grant(tenant, planner, "planner"); env.grant(tenant, driverAccount, "driver"); env.grant(tenant, callcenter, "callcenter") }
        val token = env.token(dispatcher); val plannerToken = env.token(planner); val driverToken = env.token(driverAccount); val callcenterToken = env.token(callcenter)
        fun driver(): UUID = UUID.randomUUID().also { env.exec("INSERT INTO taktovia.driver(tenant_id,id,display_name,contact_channel,contact_value) VALUES (?,?,'Fahrer','APP','x')", tenant.value, it) }
        fun vehicle(): UUID = UUID.randomUUID().also { env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_label) VALUES (?,?,'fixture','{}',?)", tenant.value, it, "V-" + it.toString().take(8)) }
        fun body(driver: UUID, vehicle: UUID, from: String, to: String, extra: String = "") = """{"driver_id":"$driver","vehicle_id":"$vehicle","starts_at":"$from","ends_at":"$to","timezone":"Europe/Berlin"$extra}"""
    }

    private fun post(w: World, body: String, token: String = w.token) = env.post("/v1/shifts", body, token, null, idem())

    @Test
    fun anlegen_lesen_aendern_und_doppelschicht_ergibt_409() {
        val w = World(env); val d = w.driver(); val v1 = w.vehicle(); val v2 = w.vehicle()
        val a = post(w, w.body(d, v1, "2026-12-01T06:00:00Z", "2026-12-01T14:00:00Z")); assertEquals(201, a.status, a.text)
        assertEquals("\"1\"", a.header("ETag")); val id = a.json["id"].asString(); assertEquals("/v1/shifts/$id", a.header("Location"))
        assertEquals("PLANNED", a.json["state"].asString()); assertEquals(480, a.json["duration_minutes"].asInt()); assertEquals(0, a.json["roster_revision"].asInt()); assertTrue(a.json["overlap_exception"].isNull)
        // Originalabnahme: doppelte Fahrerschicht ohne Ausnahme -> 409 mit Konfliktursache
        val dup = post(w, w.body(d, v2, "2026-12-01T13:00:00Z", "2026-12-01T20:00:00Z"))
        assertEquals(409, dup.status, dup.text); assertEquals("CONFLICT", dup.json["code"].asString())
        assertEquals("/driver_id", dup.json["violations"][0]["pointer"].asString()); assertTrue(dup.json["detail"].asString().contains(id))
        // dieselbe Fahrzeugbelegung durch einen anderen Fahrer
        val veh = post(w, w.body(w.driver(), v1, "2026-12-01T10:00:00Z", "2026-12-01T12:00:00Z")); assertEquals(409, veh.status, veh.text); assertEquals("/vehicle_id", veh.json["violations"][0]["pointer"].asString())
        // aneinandergrenzend ist keine Überlappung
        assertEquals(201, post(w, w.body(d, v1, "2026-12-01T14:00:00Z", "2026-12-01T22:00:00Z")).status)
        // lesen, ändern mit If-Match
        assertEquals(200, env.get("/v1/shifts/$id", w.token).status)
        val noMatch = env.put("/v1/shifts/$id", w.body(d, v1, "2026-12-01T06:00:00Z", "2026-12-01T13:00:00Z"), w.token, null); assertEquals(428, noMatch.status)
        val stale = env.put("/v1/shifts/$id", w.body(d, v1, "2026-12-01T06:00:00Z", "2026-12-01T13:00:00Z"), w.token, "\"9\""); assertEquals(412, stale.status)
        val ok = env.put("/v1/shifts/$id", w.body(d, v1, "2026-12-01T06:00:00Z", "2026-12-01T13:00:00Z"), w.token, "\"1\""); assertEquals(200, ok.status, ok.text); assertEquals("\"2\"", ok.header("ETag"))
        // Änderung in die Überlappung hinein: 409 (gegen alle anderen Schichten, nie gegen sich selbst)
        val into = env.put("/v1/shifts/$id", w.body(d, v1, "2026-12-01T06:00:00Z", "2026-12-01T15:00:00Z"), w.token, "\"2\""); assertEquals(409, into.status, into.text)
        // Liste mit Filter
        val list = env.get("/v1/shifts?driver_id=$d", w.token); assertEquals(200, list.status); assertEquals(2, list.json["items"].size())
        assertEquals(1, env.events(w.tenant, "staff.shift.changed")); assertEquals(2, env.events(w.tenant, "staff.shift.created"))
    }

    @Test
    fun ausnahme_pflichtfelder_mandant_und_rechte() {
        val w = World(env); val d = w.driver(); val v1 = w.vehicle(); val v2 = w.vehicle()
        assertEquals(201, post(w, w.body(d, v1, "2026-12-02T06:00:00Z", "2026-12-02T14:00:00Z")).status)
        val overlap = w.body(d, v2, "2026-12-02T12:00:00Z", "2026-12-02T18:00:00Z")
        assertEquals(409, post(w, overlap.dropLast(1) + ""","overlap_exception":{"reason":"Krankheitsvertretung"}}""").status, "ohne Genehmiger bleibt es beim Konflikt")
        val ex = post(w, overlap.dropLast(1) + ""","overlap_exception":{"approved_by":"user:leitung","reason":"Krankheitsvertretung"}}"""); assertEquals(201, ex.status, ex.text)
        assertEquals("user:leitung", ex.json["overlap_exception"]["approved_by"].asString()); assertTrue(ex.json["overlap_exception"]["approved_at"].asString().endsWith("Z"))
        // Pflichtfelder: fehlende Zeiten, ends_at <= starts_at, Zeit ohne Offset, tenant_id im Body
        assertEquals(422, post(w, """{"driver_id":"$d","vehicle_id":"$v1","timezone":"Europe/Berlin"}""").status)
        val rev = post(w, w.body(d, v1, "2026-12-03T10:00:00Z", "2026-12-03T10:00:00Z")); assertEquals(422, rev.status); assertEquals("/ends_at", rev.json["violations"][0]["pointer"].asString())
        assertEquals(422, post(w, w.body(d, v1, "2026-12-03T10:00:00", "2026-12-03T12:00:00Z")).status)
        assertEquals(422, post(w, w.body(d, v1, "2026-12-03T10:00:00Z", "2026-12-03T12:00:00Z", ""","tenant_id":"${w.tenant.value}"""")).status, "eigener tenant_id ist ein unbekanntes Feld")
        // fremder Mandant: Fahrer, Fahrzeug, Schicht -> 404
        val o = World(env); val od = o.driver(); val ov = o.vehicle()
        assertEquals(404, post(w, w.body(od, v1, "2026-12-04T06:00:00Z", "2026-12-04T08:00:00Z")).status)
        assertEquals(404, post(w, w.body(d, ov, "2026-12-04T06:00:00Z", "2026-12-04T08:00:00Z")).status)
        val foreign = post(o, o.body(od, ov, "2026-12-04T06:00:00Z", "2026-12-04T08:00:00Z")).json["id"].asString()
        assertEquals(404, env.get("/v1/shifts/$foreign", w.token).status)
        assertEquals(404, env.put("/v1/shifts/$foreign", w.body(d, v1, "2026-12-04T06:00:00Z", "2026-12-04T08:00:00Z"), w.token, "\"1\"").status)
        // Rechte: Planer liest, schreibt nicht; Fahrer und Callcenter haben keinen Zugriff auf Schichtzuweisungen (HR)
        assertEquals(200, env.get("/v1/shifts", w.plannerToken).status); assertEquals(403, post(w, w.body(d, v1, "2026-12-05T06:00:00Z", "2026-12-05T08:00:00Z"), w.plannerToken).status)
        assertEquals(403, env.get("/v1/shifts", w.driverToken).status); assertEquals(403, env.get("/v1/shifts", w.callcenterToken).status)
    }

    @Test
    fun zwei_gleichzeitige_anfragen_ueberlappender_schichten_speichern_hoechstens_eine() {
        repeat(3) { round ->
            val w = World(env); val d = w.driver(); val v1 = w.vehicle(); val v2 = w.vehicle()
            val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
            val bodies = listOf(w.body(d, v1, "2026-12-0${round + 1}T06:00:00Z", "2026-12-0${round + 1}T14:00:00Z"), w.body(d, v2, "2026-12-0${round + 1}T10:00:00Z", "2026-12-0${round + 1}T18:00:00Z"))
            val results = bodies.map { b -> pool.submit<Int> { start.await(); post(w, b).status } }
            start.countDown(); val status = results.map { it.get(60, TimeUnit.SECONDS) }.sorted(); pool.shutdown()
            assertEquals(listOf(201, 409), status, "Runde $round")
            assertEquals(1, env.count("SELECT count(*) FROM taktovia.staff_shift WHERE tenant_id=? AND driver_ref=?", w.tenant.value, d))
        }
    }
}
