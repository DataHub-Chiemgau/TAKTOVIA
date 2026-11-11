package de.taktovia.adapters.psp

import org.junit.jupiter.api.Assumptions.assumeTrue
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Startet den echten PSP-Simulator (`tests/sandbox/psp/psp_sim.py`, WP-M0-23, Python-Standardbibliothek) als Kindprozess auf einem freien Port und steuert ihn über seine
 * Steuerschnittstelle (Fehlermodi, Uhr, Webhook-Zustellung). Kein Mock: der Adapter spricht echtes HTTP mit dem Simulator. Ohne python3 werden die Tests übersprungen,
 * mit `TAKTOVIA_REQUIRE_DB=true` schlagen sie fehl (kein stilles Überspringen in der CI).
 */
class PspSimulatorHarness private constructor(private val process: Process, val url: String) : AutoCloseable {
    private val mapper = JsonMapper.builder().build()
    private val http = HttpClient.newHttpClient()

    fun get(path: String): JsonNode = send("GET", path, null)
    fun post(path: String, body: String = "{}"): JsonNode = send("POST", path, body)
    fun delete(path: String): JsonNode = send("DELETE", path, null)

    private fun send(method: String, path: String, body: String?): JsonNode {
        val b = HttpRequest.newBuilder(URI("$url$path")).method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        if (body != null) b.header("Content-Type", "application/json")
        return mapper.readTree(http.send(b.build(), HttpResponse.BodyHandlers.ofString()).body())
    }

    fun fault(operation: String, mode: String, ref: String = "*", times: Int = 1, holdSeconds: Double = 0.0) =
        post("/control/faults", """{"operation":"$operation","mode":"$mode","ref":"$ref","times":$times,"hold_seconds":$holdSeconds}""")

    /** Aufrufprotokoll des Simulators je Operation (`idempotency_key`, `effect_applied`, `response_dropped`, `replayed`, …). */
    fun calls(operation: String): List<JsonNode> = get("/control/log?operation=$operation")["calls"].values().toList()
    fun events(): List<JsonNode> = get("/control/events")["events"].values().toList()
    fun webhookSecret(): String = get("/control/config")["webhook_secret"].asString()
    fun realClockNow() { post("/control/clock", """{"set":"${Instant.now()}","mode":"real"}""") }

    override fun close() { process.destroy(); if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly() }

    companion object {
        fun start(repoRoot: Path = Path.of(System.getProperty("taktovia.repoRoot"))): PspSimulatorHarness {
            val python = System.getenv("TAKTOVIA_PYTHON")?.takeIf { it.isNotBlank() } ?: "python3"
            val script = repoRoot.resolve("tests/sandbox/psp/psp_sim.py")
            val p = try {
                ProcessBuilder(python, "-I", script.toString(), "--port", "0", "--seed", "1", "--clock-start", Instant.now().toString()).redirectErrorStream(true).start()
            } catch (e: java.io.IOException) {
                if (System.getenv("TAKTOVIA_REQUIRE_DB") == "true") throw AssertionError("python3 für den PSP-Simulator nicht verfügbar", e)
                assumeTrue(false, "python3 nicht verfügbar"); throw e
            }
            val line = BufferedReader(InputStreamReader(p.inputStream)).readLine() ?: throw AssertionError("PSP-Simulator liefert keine Startzeile")
            val url = Regex("auf (http://[0-9.]+:[0-9]+)").find(line)?.groupValues?.get(1) ?: throw AssertionError("PSP-Simulator Startzeile unerwartet: $line")
            // die Ausgabe weiter lesen, damit der Kindprozess nie an einem vollen Pipe-Puffer blockiert
            Thread { p.inputStream.transferTo(java.io.OutputStream.nullOutputStream()) }.also { it.isDaemon = true }.start()
            return PspSimulatorHarness(p, url)
        }
    }
}
