package de.taktovia.documentsfinance

import de.taktovia.documentsfinance.api.ClamdScanner
import de.taktovia.documentsfinance.api.EicarSignatureScanner
import de.taktovia.documentsfinance.api.ScanResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * WP-M4-21: der clamd-Anschluss spricht das echte clamd-Protokoll (`zINSTREAM`, Blöcke mit 4-Byte-Länge, Abschluss mit Länge 0) und bewertet jede Störung als „nicht verfügbar“,
 * nie als sauber. Die Gegenstelle ist ein **Testserver** (kein ClamAV in der Entwicklungsumgebung): er erkennt die EICAR-Signatur; gegen einen echten clamd ist der Anschluss nicht ausgeführt.
 */
class ClamdScannerTest {
    private class FakeClamd(private val reply: ((ByteArray) -> String?)) : AutoCloseable {
        val server = ServerSocket(0); val port get() = server.localPort
        @Volatile var received: ByteArray? = null; @Volatile var command: String? = null; @Volatile var chunks = 0
        private val t = thread(isDaemon = true) {
            try {
                while (true) {
                    server.accept().use { s ->
                        val inp = DataInputStream(s.getInputStream())
                        val cmd = ByteArrayOutputStream(); while (true) { val b = inp.read(); if (b <= 0) break; cmd.write(b) }
                        command = cmd.toString(Charsets.US_ASCII)
                        val body = ByteArrayOutputStream(); chunks = 0
                        while (true) { val n = inp.readInt(); if (n == 0) break; body.write(inp.readNBytes(n)); chunks++ }
                        received = body.toByteArray()
                        reply(received!!)?.let { s.getOutputStream().write((it + "\u0000").toByteArray()) }
                    }
                }
            } catch (_: Exception) { /* Server beendet */ }
        }
        override fun close() { server.close() }
    }

    private val eicar = EicarSignatureScanner.EICAR.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun sauber_und_infiziert_werden_nach_clamd_antwort_bewertet_und_grosse_dateien_in_bloecken_gesendet() {
        FakeClamd { b -> if (String(b, Charsets.ISO_8859_1).contains(EicarSignatureScanner.EICAR)) "stream: Eicar-Test-Signature FOUND" else "stream: OK" }.use { fake ->
            val scanner = ClamdScanner("127.0.0.1", fake.port, chunkSize = 1000)
            val clean = ByteArray(5500) { it.toByte() }
            assertEquals(ScanResult.Clean, scanner.scan(clean))
            assertEquals("zINSTREAM", fake.command); assertEquals(6, fake.chunks, "5500 Byte in Blöcken zu 1000"); assertTrue(fake.received!!.contentEquals(clean), "Inhalt unverändert übertragen")
            assertEquals(ScanResult.Infected("Eicar-Test-Signature"), scanner.scan(eicar))
        }
    }

    @Test
    fun jede_stoerung_ist_nicht_verfuegbar_nie_sauber() {
        FakeClamd { "stream: INSTREAM size limit exceeded. ERROR" }.use { assertTrue(ClamdScanner("127.0.0.1", it.port).scan(byteArrayOf(1)) is ScanResult.Unavailable, "ERROR-Antwort") }
        FakeClamd { null }.use { assertTrue(ClamdScanner("127.0.0.1", it.port, timeoutMillis = 500).scan(byteArrayOf(1)) is ScanResult.Unavailable, "keine Antwort") }
        val closed = ServerSocket(0).use { it.localPort }
        assertTrue(ClamdScanner("127.0.0.1", closed, timeoutMillis = 500).scan(byteArrayOf(1)) is ScanResult.Unavailable, "Dienst nicht erreichbar")
    }
}
