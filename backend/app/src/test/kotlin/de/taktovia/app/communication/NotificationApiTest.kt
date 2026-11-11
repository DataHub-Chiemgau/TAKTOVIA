package de.taktovia.app.communication

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.communicationsupport.api.CommunicationsupportApi
import de.taktovia.communicationsupport.api.NotificationChannel
import de.taktovia.communicationsupport.api.NotificationRequest
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * LH-6.12-COM-003 / WP-M2-32 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer statt Keycloak): Lesen von Auftrag und Zustellstatus nur für berechtigte Rollen
 * des eigenen Mandanten (Rechtematrix `notifications:read`), fremde ID ergibt 404, keine Schreibaktionen, weder Inhalt noch Kontaktdaten in der Antwort.
 */
class NotificationApiTest {
    private val env = IdentityEnv
    private val api: CommunicationsupportApi get() = env.server.ctx.getBean(CommunicationsupportApi::class.java)

    private class World(val env: IdentityEnv) {
        val tenant: TenantId = env.tenant()
        val callcenter = UUID.randomUUID(); val support = UUID.randomUUID(); val dispatcher = UUID.randomUUID(); val admin = UUID.randomUUID()
        val passenger = UUID.randomUUID(); val planner = UUID.randomUUID(); val passengerAccount = UUID.randomUUID()
        init {
            env.grant(tenant, callcenter, "callcenter"); env.grant(tenant, support, "support"); env.grant(tenant, dispatcher, "dispatcher")
            env.grant(tenant, admin, "tenant_admin"); env.grant(tenant, planner, "planner"); env.grant(tenant, passengerAccount, "passenger")
            env.tx.run(tenant) { j -> j.update("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,retention_until) VALUES (taktovia.current_tenant(),?,?,now() + interval '1 year')", passenger, "tok-$passenger") }
        }
        fun token(subject: UUID) = env.token(subject)
    }

    private fun create(w: World, channel: NotificationChannel = NotificationChannel.SMS, eventId: UUID = UUID.randomUUID()): UUID =
        env.tx.run(w.tenant) { j ->
            api.notifications.send(j, NotificationRequest(eventId, w.passenger, channel, "booking.confirmed", null, mapOf("booking_reference" to "GEHEIM-REF-1", "meeting_point" to "Geheimer Treffpunkt"))).notificationId
        }

    @Test
    fun lesen_nur_berechtigte_rolle_eigener_mandant() {
        val w = World(env); val id = create(w)
        for (reader in listOf(w.callcenter, w.support, w.dispatcher, w.admin)) {
            val list = env.get("/v1/notifications?limit=10", w.token(reader)); assertEquals(200, list.status, list.text)
            assertEquals(1, list.json["items"].size()); assertEquals(id.toString(), list.json["items"][0]["id"].asString())
            val one = env.get("/v1/notifications/$id", w.token(reader)); assertEquals(200, one.status, one.text)
            assertEquals("\"1\"", one.header("ETag"))
        }
        for (denied in listOf(w.planner, w.passengerAccount)) {
            assertEquals(403, env.get("/v1/notifications", w.token(denied)).status)
            assertEquals(403, env.get("/v1/notifications/$id", w.token(denied)).status)
        }
        assertEquals(401, env.server.get("/v1/notifications").status)
        // keine Schreibaktionen
        for (method in listOf("POST", "PUT", "PATCH", "DELETE")) {
            val r = env.server.send(method, "/v1/notifications/$id", if (method == "DELETE") null else "{}", env.headers(w.token(w.admin), extra = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())))
            assertTrue(r.status in setOf(404, 405, 403, 415), "$method ${r.status}")
        }
        assertEquals(1, api.notifications.list(contextOf(w), null, 10, null).size, "unverändert")
    }

    private fun contextOf(w: World) = de.taktovia.platform.context.RequestContext(
        de.taktovia.platform.context.Actor(de.taktovia.platform.context.ActorType.SERVICE, "https://idp.test/realms/x", "test", "sid", true), w.tenant, emptySet(), emptySet(), emptySet(), UUID.randomUUID())

    @Test
    fun fremde_nachricht_ergibt_404() {
        val a = World(env); val b = World(env); val mine = create(a); val theirs = create(b)
        assertEquals(200, env.get("/v1/notifications/$mine", a.token(a.admin)).status)
        val foreign = env.get("/v1/notifications/$theirs", a.token(a.admin))
        assertEquals(404, foreign.status, foreign.text)
        assertEquals(404, env.get("/v1/notifications/${UUID.randomUUID()}", a.token(a.admin)).status, "unbekannte ID ist ununterscheidbar")
        assertEquals(foreign.json["code"].asString(), env.get("/v1/notifications/${UUID.randomUUID()}", a.token(a.admin)).json["code"].asString())
        val list = env.get("/v1/notifications?limit=50", a.token(a.admin)).json["items"].values().map { it["id"].asString() }
        assertEquals(listOf(mine.toString()), list, "die Liste enthält nur Aufträge des eigenen Mandanten")
    }

    @Test
    fun antwort_enthaelt_weder_inhalt_noch_kontaktdaten_und_filter_und_seiten_funktionieren() {
        val w = World(env); val ids = (1..5).map { create(w, if (it % 2 == 0) NotificationChannel.EMAIL else NotificationChannel.SMS) }
        val first = env.get("/v1/notifications?limit=2", w.token(w.admin)); assertEquals(200, first.status, first.text)
        assertEquals(2, first.json["items"].size()); val cursor = first.json["next_cursor"].asString()
        val second = env.get("/v1/notifications?limit=10&cursor=$cursor", w.token(w.admin))
        assertEquals(3, second.json["items"].size()); assertTrue(second.json["next_cursor"].isNull)
        assertEquals(ids.map { it.toString() }.toSet(), (first.json["items"].values() + second.json["items"].values()).map { it["id"].asString() }.toSet())
        val item = first.json["items"][0]
        assertEquals(setOf("id", "event_id", "channel", "template", "state", "attempts", "last_failure", "created_at", "revision"), item.propertyNames().toSet())
        assertEquals("QUEUED", item["state"].asString()); assertEquals("booking.confirmed", item["template"].asString()); assertEquals(0, item["attempts"].asInt()); assertTrue(item["last_failure"].isNull)
        for (secret in listOf("GEHEIM", "Treffpunkt", w.passenger.toString())) assertFalse(first.text.contains(secret) || second.text.contains(secret), "Inhalt oder Empfänger in der Antwort: $secret")
        assertEquals(5, env.get("/v1/notifications?state=QUEUED&limit=50", w.token(w.admin)).json["items"].size())
        assertEquals(0, env.get("/v1/notifications?state=FAILED", w.token(w.admin)).json["items"].size())
        assertEquals(422, env.get("/v1/notifications?state=BOGUS", w.token(w.admin)).status)
        // Cursor gilt nur für diesen Filter
        val reused = env.get("/v1/notifications?state=QUEUED&limit=2&cursor=$cursor", w.token(w.admin))
        assertEquals(400, reused.status); assertEquals("INVALID_CURSOR", reused.json["code"].asString())
    }
}
