package de.taktovia.app

import de.taktovia.TaktoviaTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import java.io.File
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.Security
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/**
 * WP-M1-36 (SEC-002): der Server bedient nur TLS 1.2/1.3 mit HSTS, Klartext liefert keine Nutzdaten, alte Protokollversionen scheitern,
 * Klartext-Konfiguration verhindert den Start. Schlüsselspeicher wird zur Laufzeit erzeugt (kein Schlüsselmaterial im Repository).
 */
class TransportSecurityTest {
    companion object {
        private val pw = UUID.randomUUID().toString()
        private lateinit var dir: Path
        private lateinit var ks: File
        private var ctx: ConfigurableApplicationContext? = null
        private var port = 0

        @BeforeAll @JvmStatic fun start() {
            TaktoviaTestSupport.requireDatabase()
            dir = Files.createTempDirectory("tls"); ks = dir.resolve("server.p12").toFile()
            val p = ProcessBuilder("keytool", "-genkeypair", "-alias", "srv", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
                "-keystore", ks.path, "-storepass", pw, "-keypass", pw, "-dname", "CN=localhost", "-validity", "1",
                "-ext", "san=dns:localhost,ip:127.0.0.1").redirectErrorStream(true).start()
            check(p.waitFor() == 0) { "keytool: " + p.inputStream.readBytes().decodeToString() }
            ctx = SpringApplicationBuilder(TaktoviaApplication::class.java).run(
                "--server.port=0", "--server.ssl.enabled=true", "--server.ssl.key-store=${ks.path}", "--server.ssl.key-store-password=$pw",
                "--server.ssl.enabled-protocols=TLSv1.3,TLSv1.2")
            port = ctx!!.environment.getProperty("local.server.port")!!.toInt()
        }
        @AfterAll @JvmStatic fun stop() { ctx?.close() }
    }

    private fun trust(): SSLContext {
        val store = KeyStore.getInstance("PKCS12").also { s -> ks.inputStream().use { s.load(it, pw.toCharArray()) } }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).also { it.init(store) }
        return SSLContext.getInstance("TLS").also { it.init(null, tmf.trustManagers, null) }
    }

    @Test fun `HTTPS wird bedient und HSTS ist gesetzt`() {
        val r = HttpClient.newBuilder().sslContext(trust()).build()
            .send(HttpRequest.newBuilder(URI("https://localhost:$port/actuator/health")).build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, r.statusCode())
        val hsts = r.headers().firstValue("Strict-Transport-Security").orElse("")
        assertTrue(hsts.contains("max-age=31536000"), hsts)
    }

    @Test fun `unverschluesselte HTTP-Anfrage an den TLS-Port liefert keine Nutzdaten`() {
        val answer = try {
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 3000
                s.getOutputStream().write("GET /actuator/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                s.getOutputStream().flush()
                s.getInputStream().readBytes().decodeToString()
            }
        } catch (e: java.io.IOException) { "" }
        assertFalse(answer.contains("\"UP\"") || answer.contains("200 OK"), "Klartextantwort mit Nutzdaten: $answer")
    }

    @Test fun `TLS 1_2 und 1_3 werden angenommen, TLS 1_1 und 1_0 scheitern`() {
        fun handshake(protocol: String) {
            (trust().socketFactory.createSocket("127.0.0.1", port) as SSLSocket).use { s ->
                s.enabledProtocols = arrayOf(protocol); s.soTimeout = 3000; s.startHandshake()
            }
        }
        handshake("TLSv1.3"); handshake("TLSv1.2")
        val old = Security.getProperty("jdk.tls.disabledAlgorithms")
        Security.setProperty("jdk.tls.disabledAlgorithms", "")   // Client darf alte Versionen versuchen; der Server muss sie abweisen
        try {
            for (p in listOf("TLSv1.1", "TLSv1")) assertThrows<java.io.IOException>("Server nahm $p an") { handshake(p) }
        } finally { Security.setProperty("jdk.tls.disabledAlgorithms", old) }
    }

    private fun env(vararg kv: Pair<String, String>) = StandardEnvironment().also { it.propertySources.addFirst(MapPropertySource("t", mapOf(*kv))) }

    @Test fun `Klartext-Konfiguration ausserhalb von local wird abgewiesen, TLS-Konfiguration nicht`() {
        val tls = arrayOf("server.ssl.enabled" to "true", "spring.datasource.url" to "jdbc:postgresql://db/x?sslmode=verify-full",
            "taktovia.rabbitmq.uri" to "amqps://mq:5671", "taktovia.redis.uri" to "rediss://r:6380",
            "taktovia.objectstore.endpoint" to "https://s3", "taktovia.idp.issuer-uri" to "https://idp/realm")
        assertEquals(emptyList<String>(), TransportGuard(env(*tls)).violations())
        for ((k, bad) in listOf("spring.datasource.url" to "jdbc:postgresql://db/x", "taktovia.rabbitmq.uri" to "amqp://mq:5672",
            "taktovia.redis.uri" to "redis://r:6379", "taktovia.objectstore.endpoint" to "http://s3", "taktovia.idp.issuer-uri" to "http://idp", "server.ssl.enabled" to "false")) {
            val e = env(*(tls.toMap() + (k to bad)).toList().toTypedArray())
            assertEquals(1, TransportGuard(e).violations().size, k)
            assertThrows<IllegalStateException>(k) { TransportGuard(e).afterPropertiesSet() }
        }
        assertEquals(emptyList<String>(), TransportGuard(env("taktovia.environment" to "local", "spring.datasource.url" to "jdbc:postgresql://db/x")).violations())
        assertEquals(1, TransportGuard(env("taktovia.environment" to "staging", "server.ssl.enabled" to "false", "taktovia.transport.tls-at-gateway" to "false")).violations().size)
    }

    @Test fun `die Anwendung startet in staging nicht mit Klartext-Datenbankverbindung`() {
        val e = assertThrows<Exception> {
            SpringApplicationBuilder(TaktoviaApplication::class.java).run("--server.port=0", "--taktovia.environment=staging",
                "--server.ssl.enabled=true", "--server.ssl.key-store=${ks.path}", "--server.ssl.key-store-password=$pw",
                "--TAKTOVIA_DB_SSLMODE=disable").close()
        }
        var c: Throwable? = e; var found = false
        while (c != null) { if (c.message?.contains("Klartext-Konfiguration abgewiesen") == true) found = true; c = c.cause }
        assertTrue(found, "Startabbruch wegen Klartext erwartet, war: ${e.message}")
        assertNotNull(e)
    }
}
