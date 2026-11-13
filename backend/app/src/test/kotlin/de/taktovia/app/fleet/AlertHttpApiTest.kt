package de.taktovia.app.fleet

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.app.probe.Resp
import de.taktovia.fleetsafety.api.AlertKinds
import de.taktovia.platform.alert.BusinessAlertPort
import de.taktovia.platform.alert.BusinessAlertRequest
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.tenant.TenantId
import de.taktovia.stafftime.api.PlanAckOverdue
import de.taktovia.stafftime.api.PlanAckOverdueSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-52 über die echte Anwendung (Test-Issuer statt Keycloak, echte Datenbank): Arbeitsliste „Offene Störungen“ und Quittierung (`/v1/operator/alerts`): Alarm erscheint und wird quittiert,
 * Dienst- und Mandantengrenzen, Quittierung ohne Rolle, `If-Match` (428/412), zwei gleichzeitige Quittierungen, Hinweis an die Leitstelle bei ausbleibender Planbestätigung als Alarm
 * (Dedupe), Meldung über den Live-Kanal, keine Personendaten in der Antwort.
 */
class AlertHttpApiTest {
    private val env = IdentityEnv
    private val port: BusinessAlertPort get() = env.server.ctx.getBean(BusinessAlertPort::class.java)
    private val ctxTx: ContextTransactions get() = env.server.ctx.getBean(ContextTransactions::class.java)

    private class Actor(val token: String, val subject: UUID)

    private fun actor(t: TenantId, role: String, services: List<UUID>? = emptyList()): Actor {
        val s = UUID.randomUUID(); env.grant(t, s, role, services); return Actor(env.token(s), s)
    }

    private fun raise(t: TenantId, kind: String = AlertKinds.VEHICLE_BLOCK_CRITICAL, service: UUID? = null, source: UUID = UUID.randomUUID()): UUID =
        env.tx.run(t) { j -> port.raise(j, BusinessAlertRequest(kind, sourceEventId = source, serviceId = service)).alertId }

    private fun ack(a: Actor, id: UUID, revision: Long?, cause: String = "HANDLED", key: String = "ack-" + UUID.randomUUID(), body: String = """{"cause":"$cause","note":"erledigt"}"""): Resp =
        env.server.send("POST", "/v1/operator/alerts/$id/acknowledgements", body, env.headers(a.token, extra = buildMap { put("Idempotency-Key", key); if (revision != null) put("If-Match", "\"$revision\"") }))

    @Test
    fun alarm_erscheint_in_der_arbeitsliste_und_wird_quittiert() {
        val t = env.tenant(); val d = actor(t, "dispatcher"); val id = raise(t)
        val list = env.get("/v1/operator/alerts", d.token); assertEquals(200, list.status, list.text)
        val item = list.json["items"].values().single { it["id"].asString() == id.toString() }
        assertEquals("OPEN", item["state"].asString()); assertEquals("CRITICAL", item["priority"].asString()); assertEquals(1, item["escalation_level"].asInt(), "kritisch: von Beginn an eskaliert")
        assertEquals(1, item["policy_revision_no"].asInt()); assertTrue(item["acknowledgement"].isNull)
        val one = env.get("/v1/operator/alerts/$id", d.token); assertEquals(200, one.status); assertEquals("\"1\"", one.header("ETag"))
        val done = ack(d, id, 1, "REPAIRED"); assertEquals(200, done.status, done.text); assertEquals("\"2\"", done.header("ETag"))
        assertEquals("ACKNOWLEDGED", done.json["state"].asString()); assertEquals("user:${d.subject}", done.json["acknowledgement"]["acknowledged_by"].asString()); assertEquals("REPAIRED", done.json["acknowledgement"]["cause"].asString())
        assertTrue(Instant.parse(done.json["acknowledgement"]["acknowledged_at"].asString()).isBefore(Instant.now().plusSeconds(5)))
        assertTrue(env.get("/v1/operator/alerts", d.token).json["items"].values().none { it["id"].asString() == id.toString() }, "quittiert: nicht mehr offen")
        assertEquals(1, env.get("/v1/operator/alerts?state=ACKNOWLEDGED", d.token).json["items"].size(), "ein quittierter Alarm bleibt nachvollziehbar")
        // Leerzustand und Seitenform
        val empty = env.get("/v1/operator/alerts", actor(env.tenant(), "dispatcher").token); assertEquals(0, empty.json["items"].size()); assertTrue(empty.json.has("as_of") && empty.json["next_cursor"].isNull)
    }

    @Test
    fun antwort_enthaelt_nur_codes_und_kennungen() {
        val t = env.tenant(); val d = actor(t, "dispatcher"); raise(t, AlertKinds.OVERDUE)
        val item = env.get("/v1/operator/alerts", d.token).json["items"][0]
        assertEquals(setOf("id", "kind", "priority", "state", "vehicle_id", "service_id", "recipient_role", "policy_revision_no", "escalation_level", "escalated_at", "escalate_at", "reason_code", "ref_type", "ref_id",
            "created_at", "updated_at", "resolved_at", "revision", "acknowledgement", "case_number", "affected_run_count"), item.propertyNames().toSet())
        for (forbidden in listOf("name", "phone", "email", "address", "diagnos", "passenger", "contact")) assertFalse(item.toString().contains("\"$forbidden", ignoreCase = true), forbidden)
    }

    @Test
    fun fremder_dienst_fremder_mandant_und_quittierung_ohne_rolle() {
        val t = env.tenant(); val s1 = UUID.randomUUID(); val s2 = UUID.randomUUID()
        val mine = raise(t, service = s1); val other = raise(t, service = s2)
        val d = actor(t, "dispatcher", listOf(s1)); val reader = actor(t, "callcenter", listOf(s1))
        assertEquals(listOf(mine.toString()), env.get("/v1/operator/alerts", d.token).json["items"].values().map { it["id"].asString() })
        assertEquals(404, env.get("/v1/operator/alerts/$other", d.token).status); assertEquals(404, ack(d, other, 1).status, "fremder Dienst")
        // lesende Rolle sieht, quittiert aber nicht
        assertEquals(200, env.get("/v1/operator/alerts/$mine", reader.token).status)
        assertEquals(403, ack(reader, mine, 1).status)
        assertEquals(403, env.get("/v1/operator/alerts", actor(t, "passenger", null).token).status); assertEquals(403, env.get("/v1/operator/alerts", actor(t, "driver", null).token).status)
        assertEquals(401, env.server.get("/v1/operator/alerts", emptyMap()).status)
        // fremder Mandant
        val foreign = actor(env.tenant(), "dispatcher", listOf(s1))
        assertEquals(404, env.get("/v1/operator/alerts/$mine", foreign.token).status); assertEquals(404, ack(foreign, mine, 1).status)
        assertEquals(0, env.get("/v1/operator/alerts", foreign.token).json["items"].size())
        assertEquals("OPEN", env.get("/v1/operator/alerts/$mine", d.token).json["state"].asString(), "nichts wurde quittiert")
    }

    @Test
    fun if_match_eingaben_und_doppelte_quittierung() {
        val t = env.tenant(); val d = actor(t, "dispatcher"); val id = raise(t)
        assertEquals(428, ack(d, id, null).status)
        val stale = ack(d, id, 9); assertEquals(412, stale.status); assertEquals("\"1\"", stale.header("ETag")); assertEquals("REVISION_MISMATCH", stale.json["code"].asString())
        assertEquals(422, ack(d, id, 1, body = """{"cause":"EGAL"}""").status); assertEquals(422, ack(d, id, 1, body = "{}").status)
        assertEquals(422, ack(d, id, 1, body = """{"cause":"HANDLED","tenant_id":"${t}"}""").status, "tenant_id im Body ist kein Recht")
        assertEquals(422, ack(d, id, 1, body = """{"cause":"HANDLED","note":"${"x".repeat(501)}"}""").status)
        assertEquals(200, ack(d, id, 1).status)
        val again = ack(d, id, 2); assertEquals(409, again.status); assertEquals("INVALID_STATE_TRANSITION", again.json["code"].asString())
        assertEquals(404, ack(d, UUID.randomUUID(), 1).status); assertEquals(422, env.get("/v1/operator/alerts?state=KAPUTT", d.token).status)
    }

    @Test
    fun zwei_gleichzeitige_quittierungen_ergeben_eine() {
        val t = env.tenant(); val a = actor(t, "dispatcher"); val b = actor(t, "dispatcher"); val id = raise(t)
        val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
        val results = listOf(a, b).map { who -> pool.submit<Resp> { go.await(); ack(who, id, 1) } }
        go.countDown(); val out = results.map { it.get(30, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(listOf(200, 412), out.map { it.status }.sorted(), out.joinToString { it.text })
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.alert_acknowledgement WHERE tenant_id = ? AND alert_id = ?", t.value, id))
    }

    @Test
    fun ausbleibende_planbestaetigung_erzeugt_genau_einen_alarm_je_schicht_und_revision() {
        val t = env.tenant(); val d = actor(t, "dispatcher"); val sink = env.server.ctx.getBean(PlanAckOverdueSink::class.java)
        val shift = UUID.randomUUID(); val run = UUID.randomUUID(); val vehicle = UUID.randomUUID()
        env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'x','{}')", t.value, vehicle)
        repeat(3) { sink.overdue(t, PlanAckOverdue(shift, vehicle, run, 4, Instant.now())) }   // wiederholter Lauf des Betriebsauftrags
        sink.overdue(t, PlanAckOverdue(shift, vehicle, run, 5, Instant.now()))                // neue Revision: neuer Hinweis
        val items = env.get("/v1/operator/alerts", d.token).json["items"].values().filter { it["kind"].asString() == "PLAN_ACK_OVERDUE" }
        assertEquals(2, items.size); assertTrue(items.all { it["priority"].asString() == "NORMAL" && it["ref_type"].asString() == "staff_shift" && it["ref_id"].asString() == shift.toString() })
        assertEquals("PLAN_NOT_ACKNOWLEDGED", items[0]["reason_code"].asString())
    }

    @Test
    fun neuer_alarm_wird_ueber_den_live_kanal_nur_den_berechtigten_diensten_gemeldet() {
        val t = env.tenant(); val s1 = UUID.randomUUID(); val s2 = UUID.randomUUID(); val d = actor(t, "dispatcher", listOf(s1))
        val c0 = env.get("/v1/operator/live/feed", d.token).json["next_cursor"].asString()
        val mine = raise(t, service = s1); raise(t, service = s2)
        val page = env.get("/v1/operator/live/feed?after=$c0", d.token); val items = page.json["items"].values().toList(); val c1 = page.json["next_cursor"].asString()
        assertEquals(listOf(mine.toString()), items.map { it["aggregate_id"].asString() }); assertEquals("alert.raised", items[0]["type"].asString()); assertEquals("CRITICAL", items[0]["status"]["priority"].asString())
        // Quittierung wird den Leitstellen ebenfalls gemeldet
        ack(d, mine, 1)
        val after = env.get("/v1/operator/live/feed?after=$c1", d.token)
        assertTrue(after.json["items"].values().any { it["type"].asString() == "alert.acknowledged" && it["aggregate_id"].asString() == mine.toString() })
    }
}
