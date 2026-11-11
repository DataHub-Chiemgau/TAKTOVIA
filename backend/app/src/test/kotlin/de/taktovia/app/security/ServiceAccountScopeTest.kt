package de.taktovia.app.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * LH-6.2-IAM-002 / WP-M1-04 AK 4: Dienstkonten (Client Credentials) sind auf Adapter und Zweck begrenzt: Aufrufe außerhalb ihrer Scopes
 * ergeben 403; ein Dienstkonto erhält keine Menschen-Sitzung und keinen Mandantenwechsel.
 */
class ServiceAccountScopeTest {
    private val env get() = SecurityEnv.shared
    private val idp get() = env.idp

    @Test
    fun ausserhalb_scope_403_kein_mandantenwechsel() {
        // Konfiguriert: adapter-psp darf höchstens probe:read (Obergrenze im Backend), Token trägt probe:read und probe:write.
        val svc = idp.sign(idp.serviceClaims(client = "adapter-psp", scope = "probe:read probe:write", tenants = listOf(env.tenantA)))
        assertEquals(200, env.call(svc).status, "im Scope: GET (probe:read)")

        val write = env.server.post("/v1/probe/free", "{}", idp.bearer(svc))
        assertEquals(403, write.status, "probe:write steht im Token, aber nicht in der Obergrenze des Adapters: ${write.text}")
        assertEquals("FORBIDDEN", write.json["code"].asString())
        assertEquals(403, env.call(svc, "/v1/probe/admin").status, "Operation mit anderem Scope (probe:admin)")
        assertEquals(403, env.call(svc, "/v1/probe/gibt-es-nicht").status, "Pfad ohne Vertrag: kein Scope ableitbar, kein Zugriff")

        // Token ohne jeden Scope, und Dienstkonto eines nicht freigegebenen Adapters
        assertEquals(403, env.call(idp.sign(idp.serviceClaims(client = "adapter-psp", scope = "openid"))).status, "kein passender Scope")
        assertEquals(403, env.call(idp.sign(idp.serviceClaims(client = "adapter-ocr", scope = "probe:read"))).status, "Adapter ohne Freigabe")

        // Keine Menschen-Sitzung: Session-ID im Dienstkonto-Token ist ein Widerspruch zum Claims-Vertrag; Menschen-Endpunkte sind gesperrt.
        assertEquals(401, env.call(idp.sign(idp.serviceClaims().also { it["sid"] = "sid-12345678" })).status, "Dienstkonto mit Sitzung")
        assertEquals(401, env.call(idp.sign(idp.serviceClaims(tenants = listOf(env.tenantA, java.util.UUID.randomUUID())))).status, "Dienstkonto mit zwei Mandanten")
        listOf("GET" to "/v1/me", "POST" to "/v1/me/tenant-switch", "GET" to "/v1/sessions", "DELETE" to "/v1/sessions/6f0c1d2e-3a4b-4c5d-8e9f-0a1b2c3d4e5f").forEach { (m, p) ->
            val r = env.server.send(m, p, if (m == "POST") "{}" else null, idp.bearer(svc))
            assertEquals(403, r.status, "$m $p: Dienstkonto darf keine Menschen-Sitzung und keinen Mandantenwechsel: ${r.text}")
        }
        // Mandantenwahl per Header wird für Dienstkonten nicht akzeptiert (der Mandant ist im Token gebunden).
        assertEquals(403, env.server.get("/v1/probe/leaky", idp.bearer(svc) + ("X-Tenant-Id" to java.util.UUID.randomUUID().toString())).status)
        // Ein Mensch erreicht dieselben Pfade nicht wegen der Dienstkonto-Regel gesperrt (404, weil nicht gebaut), nicht 403
        val human = idp.sign(idp.humanClaims())
        assertEquals(404, env.call(human, "/v1/me/nicht-gebaut").status)
        // Ein Menschen-Token mit principal_type=service wird nicht als Mensch behandelt (Typ kommt aus dem signierten Token)
        assertEquals(401, env.call(idp.sign(idp.humanClaims().also { it["principal_type"] = "service" })).status, "service-Typ mit Session-ID ist widersprüchlich")
    }
}
