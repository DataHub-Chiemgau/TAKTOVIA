package de.taktovia.app.payment

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

/** Startet den echten PSP-Simulator (`tests/sandbox/psp`, Python-Standardbibliothek) als Kindprozess und steuert ihn über seine Steuerschnittstelle. Ohne python3: übersprungen, mit `TAKTOVIA_REQUIRE_DB=true` Fehler. */
class PspAppSimulator private constructor(private val process: Process, val url: String) : AutoCloseable {
    private val mapper = JsonMapper.builder().build()
    private val http = HttpClient.newHttpClient()

    fun get(path: String): JsonNode = send("GET", path, null)
    fun post(path: String, body: String = "{}"): JsonNode = send("POST", path, body)

    private fun send(method: String, path: String, body: String?): JsonNode {
        val b = HttpRequest.newBuilder(URI("$url$path")).method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        if (body != null) b.header("Content-Type", "application/json")
        return mapper.readTree(http.send(b.build(), HttpResponse.BodyHandlers.ofString()).body())
    }

    fun webhookSecret(): String = get("/control/config")["webhook_secret"].asString()
    fun events(): List<JsonNode> = get("/control/events")["events"].values().toList()
    fun deliveries(): List<JsonNode> = get("/control/log")["deliveries"].values().toList()
    fun configure(webhookUrl: String, auto: Boolean) { post("/control/config", """{"webhook_url":"$webhookUrl","webhook_auto":$auto}""") }
    fun deliver(eventId: String, mode: String = "valid", repeat: Int = 1, lateSeconds: Int = 900): JsonNode =
        post("/control/webhooks/deliver", """{"event_id":"$eventId","mode":"$mode","repeat":$repeat,"late_seconds":$lateSeconds}""")

    override fun close() { process.destroy(); if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly() }

    companion object {
        fun start(): PspAppSimulator {
            val repo = Path.of(System.getProperty("taktovia.repoRoot"))
            val python = System.getenv("TAKTOVIA_PYTHON")?.takeIf { it.isNotBlank() } ?: "python3"
            val p = try {
                ProcessBuilder(python, "-I", repo.resolve("tests/sandbox/psp/psp_sim.py").toString(), "--port", "0", "--seed", "1", "--clock-start", Instant.now().toString()).redirectErrorStream(true).start()
            } catch (e: java.io.IOException) {
                if (System.getenv("TAKTOVIA_REQUIRE_DB") == "true") throw AssertionError("python3 für den PSP-Simulator nicht verfügbar", e)
                assumeTrue(false, "python3 nicht verfügbar"); throw e
            }
            val line = BufferedReader(InputStreamReader(p.inputStream)).readLine() ?: throw AssertionError("PSP-Simulator ohne Startzeile")
            val url = Regex("auf (http://[0-9.]+:[0-9]+)").find(line)?.groupValues?.get(1) ?: throw AssertionError("Startzeile unerwartet: $line")
            Thread { p.inputStream.transferTo(java.io.OutputStream.nullOutputStream()) }.also { it.isDaemon = true }.start()
            return PspAppSimulator(p, url).also { it.post("/control/clock", """{"set":"${Instant.now()}","mode":"real"}""") }
        }
    }
}
