package de.taktovia.app

import de.taktovia.app.probe.ProbeServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** LH-6.18-SEC-009: SSRF-Ziele in Webhook-Abonnements werden von der Schema-Prüfung (x-url) abgewiesen, bevor irgendein Handler läuft. */
class SsrfApiTest {
    private val s get() = ProbeServer.production()
    private fun create(url: String) = s.post("/v1/webhook-subscriptions", """{"url":"$url","event_types":["membership.granted"]}""")

    @Test
    fun ssrf_ziele_werden_mit_feldfehler_abgewiesen() {
        for (u in listOf("https://127.0.0.1/hook", "https://localhost/hook", "https://169.254.169.254/latest/meta-data/", "https://10.0.0.1/x", "https://192.168.1.1/x",
            "https://[::1]/x", "https://2130706433/x", "https://0x7f.0.0.1/x", "https://intern.internal/x", "https://user:pw@partner.example.org/x", "https://partner.example.org:8080/x",
            "http://partner.example.org/x", "ftp://partner.example.org/x", "file:///etc/passwd")) {
            val r = create(u)
            assertEquals(422, r.status, "$u -> ${r.text}")
            assertTrue(r.json["violations"].values().any { it["pointer"].asString() == "/url" && it["code"].asString() in setOf("TARGET_NOT_ALLOWED", "PATTERN_MISMATCH") }, r.text)
        }
        // zulässiges Ziel passiert die Eingabeprüfung; der Endpunkt (WP-M1-16) verlangt dann einen Anfragekontext: ohne Anmeldung 401, nicht 422
        val ok = create("https://partner.example.org/hooks/taktovia")
        assertEquals(401, ok.status, ok.text)
    }
}
