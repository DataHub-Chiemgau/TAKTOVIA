package de.taktovia.app.live

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.app.probe.Resp
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * WP-M3-35 über die echte Anwendung (Test-Issuer statt Keycloak, echte Datenbank): Live-Kanal der Leitstelle und des Fahrers als Server-Sent Events. Die Ereignisse entstehen über
 * die Outbox in der Fachtransaktion (Trigger `outbox_live`); geprüft werden Zustellung ohne Neuladen, Mandanten- und Dienstgrenzen, Wiederaufsetzen, Sitzungssperre, Rechteentzug
 * während der offenen Verbindung und der Verzicht auf Rohpayloads.
 */
class LiveChannelHttpTest {
    private val env = IdentityEnv
    private val json = JsonMapper.builder().build()
    private val outbox: Outbox get() = env.server.ctx.getBean(Outbox::class.java)

    private class Frame(val id: String?, val event: String, val data: JsonNode?)

    private fun frames(text: String): List<Frame> = text.split("\n\n").filter { it.isNotBlank() && !it.startsWith(":") }.map { block ->
        val lines = block.lines()
        Frame(lines.firstOrNull { it.startsWith("id: ") }?.removePrefix("id: "), lines.first { it.startsWith("event: ") }.removePrefix("event: "),
            lines.firstOrNull { it.startsWith("data: ") }?.removePrefix("data: ")?.let { json.readTree(it) })
    }
    private fun lives(r: Resp) = frames(r.text).filter { it.event == "live" }
    private fun end(r: Resp) = frames(r.text).last { it.event == "end" }.data!!["reason"].asString()

    private fun emit(tenant: TenantId, type: String, payload: Map<String, Any?>, aggregate: UUID = UUID.randomUUID(), revision: Long = 1): UUID =
        env.tx.run(tenant) { j -> outbox.append(j, OutboxEvent(aggregate, revision, type, payload, Instant.now())) }

    private fun async(path: String, token: String, context: String? = null): CompletableFuture<Resp> = CompletableFuture.supplyAsync { env.get(path, token, context) }
    private fun Resp.ok(): Resp { assertEquals(200, status, text); return this }

    private class Operator(val tenant: TenantId, val subject: UUID, val token: String, val membership: UUID, val sid: String)

    private fun operator(tenant: TenantId, services: List<UUID>?, role: String = "dispatcher"): Operator {
        val s = UUID.randomUUID(); val sid = "sid-" + UUID.randomUUID(); val m = env.grant(tenant, s, role, services)
        return Operator(tenant, s, env.token(s, sid), m, sid)
    }

    @Test
    fun zustandsaenderung_erscheint_ohne_neuladen() {
        val t = env.tenant(); val op = operator(t, listOf(UUID.randomUUID()))
        val stream = async("/v1/operator/live/events?wait_seconds=4", op.token)
        Thread.sleep(1200)
        val alert = UUID.randomUUID()
        emit(t, "alert.raised", mapOf("alert_id" to alert.toString(), "priority" to "CRITICAL", "state" to "OPEN"), alert)
        val r = stream.get(15, TimeUnit.SECONDS).ok()
        assertTrue(r.header("Content-Type")!!.startsWith("text/event-stream"))
        val f = frames(r.text)
        assertEquals("ready", f.first().event)
        val live = lives(r)
        assertEquals(1, live.size, r.text)
        assertEquals("alert.raised", live[0].data!!["type"].asString()); assertEquals(alert.toString(), live[0].data!!["aggregate_id"].asString())
        assertEquals("CRITICAL", live[0].data!!["status"]["priority"].asString()); assertEquals(live[0].id, live[0].data!!["cursor"].asString())
        assertEquals("max_duration", end(r))
    }

    @Test
    fun nachrichten_enthalten_nur_referenzen_und_statusfelder_keine_rohpayloads() {
        val t = env.tenant(); val op = operator(t, emptyList())
        val c0 = env.get("/v1/operator/live/feed", op.token).ok().json["next_cursor"].asString()
        emit(t, "dispatch.run_revised", mapOf("run_id" to UUID.randomUUID().toString(), "revision" to 3, "cause" to "REPLAN", "passenger_name" to "Erika Muster", "pickup_address" to "Hauptstraße 1, Dresden",
            "booking_changes" to listOf(mapOf("booking_id" to UUID.randomUUID().toString(), "contact" to "erika@example.test"))))
        val r = env.get("/v1/operator/live/events?wait_seconds=0&after=$c0", op.token).ok()
        val e = lives(r).single().data!!
        assertEquals(setOf("run_id", "revision", "cause"), e["status"].propertyNames().toSet())
        assertFalse(r.text.contains("Muster") || r.text.contains("Hauptstraße") || r.text.contains("erika@"), "keine Personendaten im Kanal")
        assertEquals(setOf("event_id", "cursor", "type", "topic", "aggregate_id", "aggregate_revision", "service_id", "shift_id", "status", "occurred_at"), e.propertyNames().toSet())
    }

    @Test
    fun abonnement_sieht_nur_den_eigenen_mandanten_und_die_berechtigten_dienste() {
        val a = env.tenant(); val b = env.tenant(); val s1 = UUID.randomUUID(); val s2 = UUID.randomUUID()
        val op = operator(a, listOf(s1))
        val c0 = env.get("/v1/operator/live/feed", op.token).ok().json["next_cursor"].asString()
        emit(b, "alert.raised", mapOf("service_id" to s1.toString(), "state" to "OPEN"))                 // fremder Mandant, gleicher Dienst: nie
        val other = emit(a, "alert.raised", mapOf("service_id" to s2.toString(), "state" to "OPEN"))      // eigener Mandant, fremder Dienst: nie
        val mine = emit(a, "alert.raised", mapOf("service_id" to s1.toString(), "state" to "OPEN"))
        val wide = emit(a, "incident.opened", mapOf("state" to "OPEN"))                                  // ohne Dienstbezug: mandantenweit
        val r = env.get("/v1/operator/live/events?wait_seconds=0&after=$c0", op.token).ok()
        assertEquals(listOf(mine.toString(), wide.toString()), lives(r).map { it.data!!["event_id"].asString() })
        assertFalse(r.text.contains(other.toString()))
        // dasselbe über den Rückfall per Abruf
        assertEquals(listOf(mine.toString(), wide.toString()), env.get("/v1/operator/live/feed?after=$c0", op.token).ok().json["items"].values().map { it["event_id"].asString() })
    }

    @Test
    fun ohne_berechtigung_ungueltige_eingaben_und_fremde_rolle() {
        val t = env.tenant(); val passenger = UUID.randomUUID(); env.grant(t, passenger, "passenger"); val driver = UUID.randomUUID(); env.grant(t, driver, "driver")
        assertEquals(401, env.server.get("/v1/operator/live/events?wait_seconds=0", emptyMap()).status)
        assertEquals(403, env.get("/v1/operator/live/events?wait_seconds=0", env.token(passenger)).status)
        assertEquals(403, env.get("/v1/operator/live/events?wait_seconds=0", env.token(driver)).status, "Fahrer am Leitstellenkanal")
        assertEquals(403, env.get("/v1/operator/live/feed", env.token(driver)).status)
        assertEquals(403, env.get("/v1/driver/live/events?wait_seconds=0", env.token(passenger)).status)
        val op = operator(t, null)
        assertEquals(403, env.get("/v1/driver/live/events?wait_seconds=0", op.token).status, "Leitstelle am Fahrerkanal")
        assertEquals(422, env.get("/v1/operator/live/events?wait_seconds=0&after=abc", op.token).status)
        assertEquals(422, env.get("/v1/operator/live/events?wait_seconds=99", op.token).status)
        assertEquals(422, env.get("/v1/operator/live/feed?after=1.x", op.token).status)
        assertEquals(422, env.get("/v1/operator/live/feed?limit=0", op.token).status)
    }

    @Test
    fun kanalabbruch_wiederaufsetzen_mit_letzter_id_ohne_verlust_und_ohne_duplikate() {
        val t = env.tenant(); val op = operator(t, emptyList())
        val ready = frames(env.get("/v1/operator/live/events?wait_seconds=0", op.token).ok().text).first { it.event == "ready" }.data!!["cursor"].asString()
        val e1 = emit(t, "fleet.vehicle_blocked", mapOf("state" to "ACTIVE")); val e2 = emit(t, "fleet.vehicle_blocked", mapOf("state" to "LIFTED"))
        val first = lives(env.get("/v1/operator/live/events?wait_seconds=0&after=$ready", op.token).ok())
        assertEquals(listOf(e1.toString(), e2.toString()), first.map { it.data!!["event_id"].asString() })
        // Abbruch: während der Client weg ist, entsteht ein weiteres Ereignis; die letzte id (Header) setzt genau dahinter fort
        val e3 = emit(t, "fleet.vehicle_blocked", mapOf("state" to "ACTIVE"))
        val resumed = lives(env.server.get("/v1/operator/live/events?wait_seconds=0", env.headers(op.token, extra = mapOf("Last-Event-ID" to first.last().id!!))).ok())
        assertEquals(listOf(e3.toString()), resumed.map { it.data!!["event_id"].asString() })
        // weit zurück: alles Spätere, nie etwas doppelt
        assertEquals(3, lives(env.get("/v1/operator/live/events?wait_seconds=0&after=$ready", op.token).ok()).size)
    }

    @Test
    fun gesperrte_sitzung_beendet_das_abonnement() {
        val t = env.tenant(); val op = operator(t, emptyList())
        env.get("/v1/operator/live/feed", op.token).ok()   // registriert die Sitzung
        val started = System.nanoTime()
        val stream = async("/v1/operator/live/events?wait_seconds=30", op.token)
        Thread.sleep(1500)
        env.exec("UPDATE taktovia.session_register SET revoked_at = clock_timestamp(), revoke_reason = 'ADMIN', revoked_by = 'test' WHERE tenant_id = ? AND idp_session_id = ?", t.value, op.sid)
        emit(t, "alert.raised", mapOf("state" to "OPEN"))
        val r = stream.get(20, TimeUnit.SECONDS).ok()
        assertEquals("session_revoked", end(r))
        assertTrue(lives(r).isEmpty(), "nach der Sperre keine Ereignisse mehr")
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 15, "das Abonnement endet zeitnah, nicht erst nach Ablauf der Verbindung")
        assertEquals(401, env.get("/v1/operator/live/events?wait_seconds=0", op.token).status, "neue Verbindung mit gesperrter Sitzung")
    }

    @Test
    fun rechteentzug_waehrend_offener_verbindung_stoppt_den_entzogenen_dienst_und_die_mitgliedschaft_beendet_den_strom() {
        val t = env.tenant(); val s1 = UUID.randomUUID(); val s2 = UUID.randomUUID(); val op = operator(t, listOf(s1, s2))
        val stream = async("/v1/operator/live/events?wait_seconds=6", op.token)
        Thread.sleep(1300)
        val before = emit(t, "alert.raised", mapOf("service_id" to s2.toString()))
        Thread.sleep(1800)
        env.exec("UPDATE taktovia.membership SET service_scope = CAST(? AS uuid[]) WHERE id = ?", "{$s1}", op.membership)   // Dienstrecht s2 entzogen
        val afterRevocationS2 = emit(t, "alert.raised", mapOf("service_id" to s2.toString()))
        val stillS1 = emit(t, "alert.raised", mapOf("service_id" to s1.toString()))
        val r = stream.get(20, TimeUnit.SECONDS).ok()
        val ids = lives(r).map { it.data!!["event_id"].asString() }
        assertTrue(before.toString() in ids, "vor dem Entzug erhalten")
        assertFalse(afterRevocationS2.toString() in ids, "nach dem Entzug keine Ereignisse dieses Dienstes")
        assertTrue(stillS1.toString() in ids, "der verbleibende Dienst läuft weiter")
        // Mitgliedschaft widerrufen: der Strom endet mit festem Grund
        val stream2 = async("/v1/operator/live/events?wait_seconds=30", op.token)
        Thread.sleep(1500); env.revokeDirect(t, op.membership)
        assertEquals("membership_revoked", end(stream2.get(20, TimeUnit.SECONDS).ok()))
    }

    @Test
    fun zwei_tabs_mit_unterschiedlichem_mandantenkontext_bleiben_getrennt() {
        val a = env.tenant("Alpha-" + UUID.randomUUID()); val b = env.tenant("Beta-" + UUID.randomUUID())
        val s = UUID.randomUUID(); env.grant(a, s, "dispatcher", emptyList()); env.grant(b, s, "dispatcher", emptyList())
        val token = env.token(s)
        val ctxA = env.switchTo(token, a).json["context_token"].asString(); val ctxB = env.switchTo(token, b).json["context_token"].asString()
        val tabA = async("/v1/operator/live/events?wait_seconds=4", token, ctxA); val tabB = async("/v1/operator/live/events?wait_seconds=4", token, ctxB)
        Thread.sleep(1200)
        val ea = emit(a, "alert.raised", mapOf("state" to "OPEN")); val eb = emit(b, "alert.raised", mapOf("state" to "OPEN"))
        val ra = tabA.get(15, TimeUnit.SECONDS).ok(); val rb = tabB.get(15, TimeUnit.SECONDS).ok()
        assertEquals(listOf(ea.toString()), lives(ra).map { it.data!!["event_id"].asString() })
        assertEquals(listOf(eb.toString()), lives(rb).map { it.data!!["event_id"].asString() })
        assertNotEquals(ea, eb)
    }

    // ---- Fahrerkanal ----

    private class DriverWorld(val tenant: TenantId, val subject: UUID, val token: String, val shift: UUID, val run: UUID)

    private fun addShift(tenant: TenantId, driverRef: UUID): Pair<UUID, UUID> {
        val vehicle = UUID.randomUUID(); val shift = UUID.randomUUID(); val run = UUID.randomUUID()
        env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'x','{}')", tenant.value, vehicle)
        env.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,now(),now()+interval '8 hours','Europe/Berlin',0,'PLANNED')",
            tenant.value, shift, driverRef, vehicle)
        env.exec("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (?,?,?,?,1,'PLANNED')", tenant.value, run, vehicle, shift)
        return shift to run
    }

    private fun driverWorld(): DriverWorld {
        val t = env.tenant(); val subject = UUID.randomUUID(); env.grant(t, subject, "driver")
        val (shift, run) = addShift(t, subject)
        return DriverWorld(t, subject, env.token(subject), shift, run)
    }

    @Test
    fun fahrer_erhaelt_nur_planrevisionen_der_eigenen_schicht() {
        val w = driverWorld(); val foreign = driverWorld()
        val other = UUID.randomUUID(); env.grant(w.tenant, other, "driver")
        val (_, run2) = addShift(w.tenant, other)   // gleicher Mandant, Schicht eines anderen Fahrers
        val stream = async("/v1/driver/live/events?wait_seconds=4", w.token)
        Thread.sleep(1200)
        val mine = emit(w.tenant, "dispatch.run_revised", mapOf("run_id" to w.run.toString(), "revision" to 2, "previous_revision" to 1, "cause" to "REPLAN"), w.run, 2)
        emit(w.tenant, "dispatch.run_revised", mapOf("run_id" to run2.toString(), "revision" to 2, "previous_revision" to 1, "cause" to "REPLAN"), run2, 2)
        emit(w.tenant, "alert.raised", mapOf("state" to "OPEN"))                                                                  // kein Fahrerereignis
        emit(foreign.tenant, "dispatch.run_revised", mapOf("run_id" to foreign.run.toString(), "revision" to 2), foreign.run, 2)   // anderer Mandant
        val r = stream.get(15, TimeUnit.SECONDS).ok()
        val live = lives(r)
        assertEquals(listOf(mine.toString()), live.map { it.data!!["event_id"].asString() }, r.text)
        assertEquals(w.shift.toString(), live[0].data!!["shift_id"].asString()); assertEquals(2, live[0].data!!["aggregate_revision"].asInt())
        assertFalse(r.text.contains(run2.toString()))
    }

    @Test
    fun entzogene_schicht_liefert_dem_fahrer_keine_ereignisse_mehr() {
        val w = driverWorld()
        val stream = async("/v1/driver/live/events?wait_seconds=5", w.token)
        Thread.sleep(1300)
        val before = emit(w.tenant, "dispatch.run_revised", mapOf("run_id" to w.run.toString(), "revision" to 2), w.run, 2)
        Thread.sleep(1800)
        env.exec("UPDATE taktovia.staff_shift SET state = 'CANCELLED' WHERE tenant_id = ? AND id = ?", w.tenant.value, w.shift)
        val after = emit(w.tenant, "dispatch.run_revised", mapOf("run_id" to w.run.toString(), "revision" to 3), w.run, 3)
        val ids = lives(stream.get(20, TimeUnit.SECONDS).ok()).map { it.data!!["event_id"].asString() }
        assertEquals(listOf(before.toString()), ids); assertFalse(after.toString() in ids)
    }
}
