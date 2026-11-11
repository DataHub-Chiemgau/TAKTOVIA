package de.taktovia.app.payment

import de.taktovia.app.probe.Resp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `POST /v1/payments/provider-events/{provider}` über die echte Filterkette (WP-M2-29, Kap. 05 §5.6/§5.7): Ressourcenserver eingeschaltet, **kein** Bearer-Token, Authentisierung nur durch die PSP-Signatur.
 * Positiv, negativ (Signatur, Zeitfenster, Größe, Inhalt, manipulierte Mandantenangabe) und konkurrierend (100 identische Zustellungen).
 */
class ProviderEventApiTest {
    private val env = PspEventEnv
    private val secret = UUID.randomUUID().toString().toByteArray()   // je Lauf zufällig, nie ein Wert im Repository

    private class Setup(val tenant: de.taktovia.platform.tenant.TenantId, val provider: String)
    private fun setup(secretBytes: ByteArray = secret): Setup { env.server; val t = env.tenant(); val p = env.provider(); PspTestKeys.register(t, p, secretBytes); return Setup(t, p) }
    private fun rows(s: Setup, where: String = "true") = env.count("SELECT count(*) FROM taktovia.payment_event WHERE tenant_id = ? AND provider = ? AND $where", s.tenant.value, s.provider)
    private fun outbox(s: Setup) = env.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'payment.provider_event_received' AND payload->>'provider' = ?", s.tenant.value, s.provider)

    @Test
    fun nur_der_psp_eingang_ist_signaturbasiert_alle_anderen_operationen_verlangen_ein_bearer_token() {
        assertEquals(setOf("receiveProviderEvent"), env.server.registry.operations.filter { it.signatureAuthenticated }.map { it.operationId }.toSet())
        // Gegenprobe: ein gewöhnlicher Endpunkt ohne Token wird vom Ressourcenserver abgewiesen
        assertEquals(401, env.server.get("/v1/operations/${UUID.randomUUID()}").status)
    }

    @Test
    fun angenommen_202_dann_wiederholung_200_already_received_ohne_bearer_token() {
        val s = setup(); val b = env.body()
        val first = env.post(s.provider, b, secret)
        assertEquals(202, first.status, first.text); assertEquals("ACCEPTED", first.json["status"].asString()); assertTrue(first.json["event_id"].asString().startsWith("evt_"))
        val again = env.post(s.provider, b, secret)
        assertEquals(200, again.status); assertEquals("ALREADY_RECEIVED", again.json["status"].asString())
        assertEquals(1, rows(s)); assertEquals(1, outbox(s))
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.payment_event WHERE tenant_id = ? AND provider = ? AND internal_kind = 'AUTHORIZED' AND authorized_minor = 1250", s.tenant.value, s.provider))
    }

    @Test
    fun hundert_identische_webhooks_parallel_ein_effekt_neunundneunzig_duplikate() {
        val s = setup(); val b = env.body()
        val pool = Executors.newFixedThreadPool(100); val start = CountDownLatch(1)
        val futures = (1..100).map { pool.submit<Resp> { start.await(); env.post(s.provider, b, secret) } }
        start.countDown(); val rs = futures.map { it.get(90, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, rs.count { it.status == 202 }, rs.map { it.status }.toString()); assertEquals(99, rs.count { it.status == 200 && it.json["status"].asString() == "ALREADY_RECEIVED" })
        assertEquals(1, rows(s)); assertEquals(1, outbox(s), "genau eine fachliche Wirkung")
    }

    @Test
    fun gleiche_ereignis_id_mit_anderem_payload_409_und_sicherheitsereignis() {
        val s = setup(); val id = "evt_" + UUID.randomUUID().toString().replace("-", "").take(16)
        assertEquals(202, env.post(s.provider, env.body(id), secret).status)
        val changed = env.post(s.provider, env.body(id).replace("1250", "99999"), secret)
        assertEquals(409, changed.status); assertEquals("CONFLICT", changed.json["code"].asString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'security.event.payload_hash_mismatch' AND object_ref = ?", s.tenant.value,
            "event:" + de.taktovia.farepayment.api.providerEventObjectId(s.provider, id)), "als Sicherheitsereignis gemeldet")
        assertEquals(1, rows(s)); assertEquals(1, outbox(s)); assertEquals(200, env.post(s.provider, env.body(id), secret).status, "das Original bleibt ein Duplikat")
    }

    @Test
    fun ungueltige_signatur_zeitfenster_und_unbekannter_provider_werden_ohne_verarbeitung_abgewiesen() {
        val s = setup(); val b = env.body()
        val wrong = env.post(s.provider, b, "falscher-schluessel-0123456789abcd".toByteArray())
        assertEquals(401, wrong.status); assertEquals("UNAUTHENTICATED", wrong.json["code"].asString())
        assertEquals(401, env.post(s.provider, b, secret, skew = -301).status, "Replay außerhalb des Zeitfensters")
        assertEquals(401, env.post(s.provider, b, secret, skew = 301).status)
        val unknown = env.post("unbekannter-provider", b, secret)
        assertEquals(401, unknown.status); assertEquals(wrong.json["detail"], unknown.json["detail"], "unbekannter Provider und falsche Signatur sind nicht unterscheidbar")
        // ohne Signatur: kein Zugang (der Eingang ist nicht „offen“, nur weil er kein Bearer-Token verlangt)
        assertEquals(401, env.server.post("/v1/payments/provider-events/${s.provider}", b, mapOf("X-Provider-Timestamp" to "1700000000")).status)
        assertEquals(0, rows(s)); assertEquals(0, outbox(s))
        assertEquals(202, env.post(s.provider, b, secret, skew = -299).status, "Gegenprobe: im Fenster gültig")
    }

    @Test
    fun fehlender_zeitstempel_header_zu_grosser_und_ungueltiger_body_werden_abgewiesen() {
        val s = setup(); val b = env.body()
        assertEquals(401, env.server.post("/v1/payments/provider-events/${s.provider}", b, mapOf("X-Provider-Signature" to "t=1,v1=" + "a".repeat(64))).status, "Zeitstempel-Header fehlt: wie ungültige Signatur abgewiesen")
        val big = env.body(dataExtra = ""","x":"${"a".repeat(PspEventEnv.MAX_BODY)}"""")
        assertTrue(env.post(s.provider, big, secret).status in setOf(413, 422), "zu groß")
        assertTrue(env.post(s.provider, """{"event_id":"x"}""", secret).status in setOf(400, 422), "Schemaverstoß")
        assertTrue(env.post(s.provider, env.body(state = "PAID"), secret).status in setOf(400, 422), "unbekannter Zahlstatus")
        assertEquals(0, rows(s))
    }

    @Test
    fun manipulierte_tenant_id_ist_wirkungslos_der_mandant_folgt_dem_schluessel() {
        val s = setup(); val other = env.tenant()
        assertTrue(env.post(s.provider, env.body(top = ""","tenant_id":"${other.value}""""), secret).status in setOf(400, 422), "tenant_id auf oberster Ebene ist ungültig")
        assertEquals(202, env.post(s.provider, env.body(dataExtra = ""","tenant_id":"${other.value}""""), secret, extra = mapOf("X-Tenant-Id" to other.toString())).status, "in den Nutzdaten und als Header: ohne Wirkung")
        assertEquals(1, rows(s)); assertEquals(0, env.count("SELECT count(*) FROM taktovia.payment_event WHERE tenant_id = ?", other.value), "nichts im angegebenen fremden Mandanten")
        // Schlüssel eines zweiten Mandanten beim selben Provider: Ereignis nur in dessen Mandanten
        val secondSecret = UUID.randomUUID().toString().toByteArray(); PspTestKeys.register(other, s.provider, secondSecret)
        assertEquals(202, env.post(s.provider, env.body(), secondSecret).status)
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.payment_event WHERE tenant_id = ? AND provider = ?", other.value, s.provider)); assertEquals(2, rows(s) + 1)
    }

    @Test
    fun signaturheader_mit_luhn_gueltiger_ziffernfolge_wird_nicht_als_kartennummer_abgewiesen() {
        val s = setup(); val b = env.body()
        val digits = "4111111111111111"   // 16 Ziffern, gültige Luhn-Summe: als Hexwert eines HMAC rein zufällig möglich
        val sig = "t=${java.time.Instant.now().epochSecond},v1=" + digits + "a".repeat(48)
        val ts = sig.substringAfter("t=").substringBefore(",")
        assertEquals(401, env.server.post("/v1/payments/provider-events/${s.provider}", b, mapOf("X-Provider-Signature" to sig, "X-Provider-Timestamp" to ts)).status, "Signatur falsch (401), aber nicht als Kartendaten abgewiesen (422)")
        // Gegenprobe: derselbe Wert in einem anderen Header wird weiterhin abgewiesen
        assertEquals(422, env.server.post("/v1/payments/provider-events/${s.provider}", b, mapOf("X-Other" to digits, "X-Provider-Signature" to sig, "X-Provider-Timestamp" to ts)).status)
        assertEquals(0, rows(s))
    }

    @Test
    fun ereignisse_enthalten_keine_kartendaten_kartennummer_im_payload_wird_abgewiesen() {
        val s = setup()
        val r = env.post(s.provider, env.body(dataExtra = ""","note":"4111 1111 1111 1111""""), secret)
        assertEquals(422, r.status); assertTrue(!r.text.contains("4111"), "der Wert wird nie ausgegeben"); assertEquals(0, rows(s))
    }
}
