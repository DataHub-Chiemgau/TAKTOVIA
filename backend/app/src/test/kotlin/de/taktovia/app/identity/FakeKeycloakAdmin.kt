package de.taktovia.app.identity

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lokaler HTTP-Ersatz der Keycloak-Admin-REST-API (Ersatz für den nicht ausführbaren Keycloak-Dienst, kein Docker): Dienstkonto-Token per Client
 * Credentials, `users/{id}` lesen/ändern, `users/{id}/logout`, `sessions/{id}` löschen, `execute-actions-email`. Er prüft das Bearer-Token wie
 * der echte Server und protokolliert jede Anfrage als `METHOD pfad` (Nutzdaten nur für `execute-actions-email` und `enabled`). Nur für Tests.
 */
class FakeKeycloakAdmin(val realm: String, private val clientId: String = "taktovia-idp-admin", private val clientSecret: String = "test-admin-secret") {
    class User(var enabled: Boolean = true, var email: String? = "nutzer@example.test", var emailVerified: Boolean = true)

    private val json = JsonMapper()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val baseUrl get() = "http://127.0.0.1:${server.address.port}"
    val calls = CopyOnWriteArrayList<String>()
    val users = ConcurrentHashMap<String, User>()
    @Volatile var down = false
    @Volatile var tokensIssued = 0

    fun user(id: Any): User = users.computeIfAbsent(id.toString()) { User() }

    private fun send(ex: HttpExchange, status: Int, body: String = "") {
        val bytes = body.toByteArray()
        if (bytes.isNotEmpty()) ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
    }

    init {
        server.createContext("/realms/$realm/protocol/openid-connect/token") { ex ->
            val form = String(ex.requestBody.readAllBytes()).split('&').associate { java.net.URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to java.net.URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8) }
            if (down) return@createContext send(ex, 503)
            if (form["grant_type"] == "client_credentials" && form["client_id"] == clientId && form["client_secret"] == clientSecret) {
                tokensIssued++
                send(ex, 200, """{"access_token":"admin-token","token_type":"Bearer","expires_in":60}""")
            } else send(ex, 401, """{"error":"unauthorized_client"}""")
        }
        server.createContext("/admin/realms/$realm") { ex ->
            if (down) return@createContext send(ex, 503)
            if (ex.requestHeaders.getFirst("Authorization") != "Bearer admin-token") return@createContext send(ex, 401)
            val path = ex.requestURI.path.removePrefix("/admin/realms/$realm")
            val body = String(ex.requestBody.readAllBytes())
            val m = ex.requestMethod
            val parts = path.trim('/').split('/')
            when {
                m == "POST" && parts.size == 3 && parts[0] == "users" && parts[2] == "logout" -> { calls += "POST $path"; send(ex, 204) }
                m == "DELETE" && parts.size == 2 && parts[0] == "sessions" -> { calls += "DELETE $path"; send(ex, 204) }
                m == "GET" && parts.size == 2 && parts[0] == "users" -> {
                    calls += "GET $path"; val u = user(parts[1])
                    send(ex, 200, json.writeValueAsString(mapOf("id" to parts[1], "enabled" to u.enabled, "email" to u.email, "emailVerified" to u.emailVerified)))
                }
                m == "PUT" && parts.size == 2 && parts[0] == "users" -> {
                    val enabled = json.readTree(body)["enabled"].asBoolean(); user(parts[1]).enabled = enabled; calls += "PUT $path enabled=$enabled"; send(ex, 204)
                }
                m == "PUT" && parts.size == 3 && parts[0] == "users" && parts[2] == "execute-actions-email" -> {
                    calls += "PUT $path actions=" + json.readTree(body).joinToString(",") { it.asString() }; send(ex, 204)
                }
                else -> send(ex, 404)
            }
        }
        server.start()
    }

    fun close() = server.stop(0)
}

/** Lokaler `redis-server` auf freiem Port ohne Persistenz (nur Tests); `null`, wenn die Binärdatei fehlt. */
class LocalRedis private constructor(private val proc: Process, val port: Int) : AutoCloseable {
    val url get() = "redis://127.0.0.1:$port"
    fun stop() { proc.destroy(); proc.waitFor() }
    override fun close() = stop()

    companion object {
        fun startOrNull(): LocalRedis? {
            val bin = listOf("/usr/bin/redis-server", "/usr/local/bin/redis-server").firstOrNull { java.nio.file.Files.isExecutable(java.nio.file.Path.of(it)) } ?: return null
            val port = java.net.ServerSocket(0).use { it.localPort }
            val dir = java.nio.file.Files.createTempDirectory("redis-sess")
            val proc = ProcessBuilder(bin, "--port", port.toString(), "--bind", "127.0.0.1", "--save", "", "--appendonly", "no", "--dir", dir.toString()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            repeat(50) { Thread.sleep(100); if (runCatching { java.net.Socket("127.0.0.1", port).close() }.isSuccess) return LocalRedis(proc, port).also { r -> Runtime.getRuntime().addShutdownHook(Thread { r.stop() }) } }
            proc.destroy(); return null
        }
    }
}
