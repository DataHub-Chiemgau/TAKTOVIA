package de.taktovia.app

import de.taktovia.TaktoviaTestSupport
import de.taktovia.app.probe.InboundProbe
import de.taktovia.app.probe.ProbeDb
import de.taktovia.app.probe.ProbeServer
import de.taktovia.app.probe.Resp
import de.taktovia.platform.webhook.WebhookSignature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Eingangsfilter über die echte Filterkette (WP-M1-16, Kap. 05 §5.6): exakter Rohbody, Quittungen 202/200/409, Abweisung ohne Inbox-Eintrag. Der Eingang ist ein Testendpunkt über
 * den wiederverwendbaren [de.taktovia.app.webhook.InboundWebhookResponder]; die Partner- und PSP-Eingänge folgen mit M6/M2 über denselben Baustein.
 */
class InboundWebhookHttpTest {
    private val server get() = ProbeServer.get("taktovia.probe.inbound=true", "taktovia.api.extra-specs=classpath:probe/inbound-probe-api.yaml", "taktovia.api.rate-limit.requests=1000000", "spring.datasource.hikari.maximum-pool-size=24")
    private fun body(id: UUID = UUID.randomUUID(), data: String = """{"n":1}""") = """{"event_id":"$id","event_type":"partner.thing.changed","occurred_at":"2026-01-02T03:04:05Z","data":$data}"""
    private fun sig(secret: ByteArray, kid: String, body: String, skew: Long = 0) = WebhookSignature.header(secret, kid, Instant.now().plusSeconds(skew), body.toByteArray())
    private fun post(body: String, signature: String?, source: String = "partner-x"): Resp = server.send("POST", "/v1/probe/inbound/$source", body, signature?.let { mapOf(WebhookSignature.HEADER to it) } ?: emptyMap())
    private fun inbox(id: UUID? = null) = TaktoviaTestSupport.adminDataSourceOrNull()!!.connection.use { c ->
        c.prepareStatement("SELECT count(*) FROM taktovia.inbox WHERE tenant_id=? AND consumer='webhook.partner-x'" + if (id != null) " AND event_id=?" else "").use { ps ->
            ps.setObject(1, ProbeDb.tenantA); if (id != null) ps.setObject(2, id); ps.executeQuery().use { it.next(); it.getInt(1) } }
    }

    @Test
    fun hundert_identische_eingaenge_ein_inbox_eintrag_99_already_received() {
        val b = body(); val id = UUID.fromString(Regex("\"event_id\":\"([^\"]+)\"").find(b)!!.groupValues[1]); val s = sig(InboundProbe.secretNew, "k-new", b); val before = InboundProbe.effects.get()
        val pool = Executors.newFixedThreadPool(100); val start = CountDownLatch(1)
        val futures = (1..100).map { pool.submit<Resp> { start.await(); post(b, s) } }
        start.countDown(); val rs = futures.map { it.get(90, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, rs.count { it.status == 202 }); assertEquals(99, rs.count { it.status == 200 })
        assertTrue(rs.filter { it.status == 200 }.all { it.json["status"].asString() == "ALREADY_RECEIVED" })
        assertEquals(1, inbox(id)); assertEquals(before + 1, InboundProbe.effects.get(), "genau eine Wirkung")
    }

    @Test
    fun falsche_signatur_zeitabweichung_und_zu_grosser_body_werden_abgewiesen_ohne_inbox_eintrag() {
        val before = inbox(); val effects = InboundProbe.effects.get(); val b = body()
        val wrongKey = post(b, sig("falscher-schluessel-0123456789abc".toByteArray(), "k-new", b)); assertEquals(401, wrongKey.status); assertEquals("UNAUTHENTICATED", wrongKey.json["code"].asString())
        assertEquals(401, post(b, null).status, "ohne Signatur")
        assertEquals(401, post(b, sig(InboundProbe.secretNew, "k-new", b, -301)).status, "Zeitabweichung über 300 s (zu alt)")
        assertEquals(401, post(b, sig(InboundProbe.secretNew, "k-new", b, 301)).status, "Zeitabweichung über 300 s (zu neu)")
        assertEquals(401, post(b.replace("\"n\":1", "\"n\":2"), sig(InboundProbe.secretNew, "k-new", b)).status, "Body nach dem Signieren verändert")
        assertEquals(401, post(b, sig(InboundProbe.secretNew, "k-new", b), source = "anderer-absender").status, "Schlüssel gehört zu anderer Quelle")
        val big = body(data = """{"x":"${"a".repeat(InboundProbe.MAX_BODY)}"}""")
        assertEquals(413, post(big, sig(InboundProbe.secretNew, "k-new", big)).status, "zu großer Body")
        assertEquals(before, inbox(), "kein Inbox-Eintrag bei Abweisung"); assertEquals(effects, InboundProbe.effects.get())
        assertTrue(!wrongKey.text.contains("k-new") && !wrongKey.text.contains("Signatur ungültig.".lowercase()), "keine Hinweise auf Schlüssel in der Antwort")
    }

    @Test
    fun exakter_rohbody_formatierung_aendert_die_signatur() {
        val compact = body(); val spaced = compact.replace(",", ", ")
        val s = sig(InboundProbe.secretNew, "k-new", compact)
        assertEquals(401, post(spaced, s).status, "Signatur gilt dem exakten Rohbody, nicht dem geparsten JSON")
        assertEquals(202, post(spaced, sig(InboundProbe.secretNew, "k-new", spaced)).status)
    }

    @Test
    fun gleiche_ereignis_id_mit_anderem_payload_409_sicherheitsereignis_und_quarantaene() {
        val id = UUID.randomUUID(); val first = body(id); val changed = body(id, """{"n":99}""")
        assertEquals(202, post(first, sig(InboundProbe.secretOld, "k-old", first)).status, "alter Schlüssel gilt während der Rotation")
        val c = post(changed, sig(InboundProbe.secretNew, "k-new", changed))
        assertEquals(409, c.status); assertEquals("CONFLICT", c.json["code"].asString())
        val admin = TaktoviaTestSupport.adminDataSourceOrNull()!!
        admin.connection.use { cn ->
            cn.prepareStatement("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='security.event.payload_hash_mismatch' AND object_ref=?").use { ps -> ps.setObject(1, ProbeDb.tenantA); ps.setString(2, "event:$id"); ps.executeQuery().use { it.next(); assertEquals(1, it.getInt(1)) } }
            cn.prepareStatement("SELECT count(*) FROM taktovia.webhook_quarantine WHERE tenant_id=? AND event_id=?").use { ps -> ps.setObject(1, ProbeDb.tenantA); ps.setObject(2, id); ps.executeQuery().use { it.next(); assertEquals(1, it.getInt(1)) } }
        }
        assertEquals(1, inbox(id)); assertEquals(200, post(first, sig(InboundProbe.secretNew, "k-new", first)).status, "das Original bleibt ALREADY_RECEIVED, auch mit dem neuen Schlüssel")
    }

    @Test
    fun formal_ungueltiges_ereignis_trotz_gueltiger_signatur_400() {
        val bad = """{"event_id":"kein-uuid","event_type":"partner.thing.changed","occurred_at":"2026-01-02T03:04:05Z"}"""
        assertEquals(400, post(bad, sig(InboundProbe.secretNew, "k-new", bad)).status)
    }
}
