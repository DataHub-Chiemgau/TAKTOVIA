package de.taktovia.platform

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyStore
import java.util.UUID
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Zur Laufzeit erzeugtes Testzertifikat (keytool); kein Schlüsselmaterial im Repository. */
object TestTls {
    class Material(val server: SSLContext, val client: SSLContext)

    fun material(): Material {
        val dir = Files.createTempDirectory("tls"); val ks = dir.resolve("s.p12").toFile(); val pw = UUID.randomUUID().toString()
        val p = ProcessBuilder("keytool", "-genkeypair", "-alias", "srv", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12", "-keystore", ks.path,
            "-storepass", pw, "-keypass", pw, "-dname", "CN=localhost", "-validity", "1", "-ext", "san=dns:localhost,ip:127.0.0.1").redirectErrorStream(true).start()
        check(p.waitFor() == 0) { p.inputStream.readBytes().decodeToString() }
        val store = KeyStore.getInstance("PKCS12").also { s -> ks.inputStream().use { s.load(it, pw.toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).also { it.init(store, pw.toCharArray()) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).also { it.init(store) }
        return Material(SSLContext.getInstance("TLS").also { it.init(kmf.keyManagers, null, null) }, SSLContext.getInstance("TLS").also { it.init(null, tmf.trustManagers, null) })
    }

    fun server(m: Material, handler: (String) -> Int): HttpsServer =
        HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).also { s ->
            s.httpsConfigurator = HttpsConfigurator(m.server)
            s.createContext("/") { ex -> val body = ex.requestBody.readBytes().decodeToString(); val code = handler(body); ex.sendResponseHeaders(code, -1); ex.close() }
            s.start()
        }
}
