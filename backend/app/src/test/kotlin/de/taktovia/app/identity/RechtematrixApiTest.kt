package de.taktovia.app.identity

import de.taktovia.app.openapi.ApiOperation
import de.taktovia.app.openapi.RouteContract
import de.taktovia.identity.api.RightsMatrix
import de.taktovia.identity.api.RoleCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.util.UUID

/**
 * LH-6.2-IAM-004 / WP-M1-07: Rechtematrixtest gegen die echte Anwendung (echte Datenbank, Runtime-Rolle, lokaler JWT-Test-Issuer).
 * Der Lauf ist aus der Matrixdatei generiert: je Rolle und je Tätigkeit der Matrix ein Aufruf mit einer echten Mitgliedschaft in dieser Rolle.
 */
class RechtematrixApiTest {
    private val env = IdentityEnv
    private val matrix = RightsMatrix.load()

    /** Ein Aufruf einer Tätigkeit; bei gebauten Operationen mit gültigen Eingaben, damit „erlaubt“ wirklich Erfolg heißt. */
    private class Call(val method: String, val path: String, val body: String?, val extra: Map<String, String>, val success: Set<Int>?)

    private fun call(op: ApiOperation, tenant: de.taktovia.platform.tenant.TenantId): Call {
        val idem = mapOf("Idempotency-Key" to "matrix-" + UUID.randomUUID())
        return when (op.operationId) {
            "getMe" -> Call("GET", "/v1/me", null, emptyMap(), setOf(200))
            "switchTenant" -> Call("POST", "/v1/me/tenant-switch", """{"tenant_id":"$tenant"}""", idem, setOf(200))
            "listMemberships" -> Call("GET", "/v1/memberships", null, emptyMap(), setOf(200))
            "createMembership" -> Call("POST", "/v1/memberships", """{"issuer":"${env.idp.issuer}","subject":"matrix-${UUID.randomUUID()}","role_code":"support"}""", idem, setOf(201))
            "revokeMembership" -> Call("DELETE", "/v1/memberships/${env.grant(tenant, UUID.randomUUID(), "support")}", null, idem + ("If-Match" to "\"1\""), setOf(204))
            else -> Call(op.method, op.path.replace(Regex("\\{[^/}]+}")) { UUID.randomUUID().toString() }, if (op.method in setOf("POST", "PUT")) "{}" else null,
                idem + ("If-Match" to "\"1\""), null)
        }
    }

    @Test
    fun matrix_alle_rollen_erlaubt_und_verweigert() {
        val tenant = env.tenant("Matrix-" + UUID.randomUUID())
        val ops = env.server.registry.operations.associateBy { it.operationId }
        val tokens = matrix.roles.associateWith { role -> UUID.randomUUID().let { sub -> env.grant(tenant, sub, role); env.token(sub) } }
        val failures = mutableListOf<String>(); var allowedCells = 0; var deniedCells = 0
        for (row in matrix.operations) {
            val op = ops[row.operationId] ?: continue.also { failures += "${row.operationId}: nicht im Vertrag" }
            for (role in matrix.roles) {
                val c = call(op, tenant)
                val r = env.server.send(c.method, c.path, c.body, env.headers(tokens.getValue(role), extra = c.extra))
                if (role in row.allowed) {
                    allowedCells++
                    // erlaubt: nie 401/403; gebaute Operationen mit gültigen Eingaben liefern Erfolg
                    if (r.status == 401 || r.status == 403) failures += "$role darf ${row.operationId}, erhielt ${r.status}"
                    else if (c.success != null && r.status !in c.success) failures += "$role darf ${row.operationId}, erwartet ${c.success}, erhielt ${r.status}: ${r.text.take(120)}"
                } else {
                    deniedCells++
                    if (r.status != 403 && r.status != 404) failures += "$role darf ${row.operationId} nicht, erhielt ${r.status}"
                    // Verweigerung ohne Details: weder Rolle noch Scope noch Operation im Fehlertext
                    if (r.status == 403 && Regex("(?i)" + listOfNotNull(row.scope.takeIf { it != "-" }, row.operationId, "matrix").joinToString("|") { Regex.escape(it) }).containsMatchIn(r.text)) failures += "403 für $role/${row.operationId} nennt Details: ${r.text}"
                }
            }
        }
        assertEquals(emptyList<String>(), failures)
        assertTrue(allowedCells > 100 && deniedCells > 100, "der Lauf belegt beide Richtungen ($allowedCells erlaubt, $deniedCells verweigert)")
        assertEquals(matrix.operations.size * matrix.roles.size, allowedCells + deniedCells)
    }

    @Test
    fun route_ohne_matrixeintrag_scheitert() {
        val server = env.server
        val contract = server.registry.operations.associate { it.operationId to it.scopes }
        assertEquals(emptyList<String>(), matrix.verifyContract(contract), "jede Operation der Verträge hat genau einen Matrixeintrag mit demselben Scope")
        // jede tatsächlich registrierte Route der Anwendung steht in der Matrix
        val routes = RouteContract.routes(server.ctx.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping::class.java))
        val routed = RouteContract.verify(routes, server.registry, emptySet()).routed.keys
        assertTrue(routed.containsAll(listOf("getMe", "switchTenant", "listMemberships", "createMembership", "revokeMembership")))
        assertEquals(emptySet<String>(), routed.filter { matrix.operation(it) == null }.toSet())
        // Negativ: eine neue Route/Operation ohne Matrixeintrag lässt die Prüfung (und damit den Build) scheitern
        assertTrue(matrix.verifyContract(contract + ("listVehicles" to listOf("fleet:read"))).single().contains("listVehicles"))
    }

    @Test
    fun rollenkatalog_matrix_openapi_und_datenbank_sind_synchron() {
        val schema = env.server.registry.schema("MembershipCreate", "identity.yaml")
        val reg = env.server.registry
        fun errors(code: String) = reg.schemaErrors(schema, tools.jackson.databind.json.JsonMapper().readTree("""{"issuer":"i","subject":"s","role_code":"$code"}"""))
        RoleCatalog.CODES.forEach { assertEquals(emptyList<String>(), errors(it), it) }
        assertTrue(errors("superuser").isNotEmpty() && errors("ADMIN").isNotEmpty(), "OpenAPI-Enum entspricht dem Rollenkatalog")
        assertEquals(RoleCatalog.CODES, matrix.roles.toSet())
        assertEquals(RoleCatalog.ROLES.map { Triple(it.code, it.labelDe, it.placeholder) }.sortedBy { it.first },
            env.string("SELECT role_code || '|' || label_de || '|' || placeholder FROM taktovia.role_catalog").map { it.split('|').let { p -> Triple(p[0], p[1], p[2].toBoolean()) } }.sortedBy { it.first })
        val check = env.string("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='membership_role_code_catalog'").single()
        assertEquals(RoleCatalog.CODES, Regex("'([a-z_]+)'").findAll(check).map { it.groupValues[1] }.toSet())
    }

    @Test
    fun ausrollen_der_matrix_wird_beim_start_protokolliert() {
        env.server   // Anwendung gestartet: der Startschritt hat Version und Prüfsumme festgehalten
        assertEquals(matrix.sha256, env.string("SELECT matrix_sha256 FROM taktovia.rights_matrix_rollout ORDER BY id DESC LIMIT 1").single().trim())
        assertEquals(matrix.version, env.count("SELECT matrix_version FROM taktovia.rights_matrix_rollout ORDER BY id DESC LIMIT 1"))
    }

    @Test
    fun rollenentzug_waehrend_sitzung_verweigert() {
        val t = env.tenant(); val s = UUID.randomUUID()
        env.grant(t, s, "support"); val admin = env.grant(t, s, "tenant_admin")
        val token = env.token(s)
        assertEquals(200, env.get("/v1/memberships", token).status)
        env.revokeDirect(t, admin)
        assertEquals(403, env.get("/v1/memberships", token).status, "entzogene Rolle: Folgeanfrage verweigert")
        assertEquals(200, env.get("/v1/me", token).status, "die verbleibende Rolle gilt weiter")
    }

    @Test
    fun administrator_ohne_zuweisung_hat_keinen_zugriff_auf_pii_hr_finanzen_ueber_http() {
        val t = env.tenant(); val admin = UUID.randomUUID(); env.grant(t, admin, "tenant_admin")
        val token = env.token(admin)
        listOf("/v1/passengers", "/v1/driver-clearances").forEach { assertEquals(403, env.get(it, token).status, it) }
        assertEquals(403, env.post("/v1/cost-events/${UUID.randomUUID()}/allocations", "{}", token, extra = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())).status)
        // gesonderte Zuweisung der Fachrolle öffnet genau diesen Zugriff
        val both = UUID.randomUUID(); env.grant(t, both, "tenant_admin"); env.grant(t, both, "callcenter")
        assertTrue(env.get("/v1/passengers", env.token(both)).status != 403)
        assertEquals(403, env.get("/v1/driver-clearances", env.token(both)).status)
    }
}
