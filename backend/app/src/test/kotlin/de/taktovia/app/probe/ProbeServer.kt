package de.taktovia.app.probe

import de.taktovia.TaktoviaTestSupport
import de.taktovia.app.TaktoviaApplication
import de.taktovia.app.openapi.OpenApiRegistry
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

/** Antwort eines Testaufrufs. */
class Resp(val status: Int, val headers: Map<String, List<String>>, val text: String) {
    val json: JsonNode by lazy { JsonMapper().readTree(text) }
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
}

/**
 * Startet die echte Anwendung (Runtime-Rolle, echte Datenbank) mit den Prüf-Endpunkten und liefert einen schlichten
 * HTTP-Client. Eine Instanz je Konfiguration; geteilt, um Startzeit zu sparen, und beim Beenden der JVM geschlossen.
 */
class ProbeServer private constructor(val ctx: ConfigurableApplicationContext) {
    private val http = HttpClient.newHttpClient()
    val port: Int = ctx.environment.getProperty("local.server.port")!!.toInt()
    val registry: OpenApiRegistry get() = ctx.getBean(OpenApiRegistry::class.java)

    fun send(method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap(), contentType: String? = "application/json"): Resp {
        val b = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        b.method(method, publisher)
        if (body != null && contentType != null) b.header("Content-Type", contentType)
        headers.forEach { (k, v) -> b.header(k, v) }
        val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return Resp(r.statusCode(), r.headers().map(), r.body())
    }

    fun get(path: String, headers: Map<String, String> = emptyMap()) = send("GET", path, null, headers)
    fun post(path: String, body: String, headers: Map<String, String> = emptyMap()) = send("POST", path, body, headers)

    companion object {
        private val servers = HashMap<String, ProbeServer>()

        /** Gemeinsame Instanz mit Standardquote; Tests, die Quoten ändern, übergeben eigene [props]. */
        @Synchronized
        fun get(vararg props: String): ProbeServer = start(true, props.toList())

        /** Die Anwendung ohne Prüf-Endpunkte und ohne Zusatzverträge: genau die produktiven Routen. */
        @Synchronized
        fun production(vararg props: String): ProbeServer = start(false, props.toList())

        private fun start(probe: Boolean, props: List<String>): ProbeServer {
            TaktoviaTestSupport.requireDatabase()
            ProbeDb.ensure()
            if (probe) PlatformBlocksDb.ensure()
            return servers.getOrPut((if (probe) "probe|" else "prod|") + props.joinToString("|")) {
                val base = listOf("server.port=0", "taktovia.api.cursor-secret=test-cursor-secret-test-cursor-secret-1234") +
                    if (probe) listOf("taktovia.probe.enabled=true", "taktovia.api.extra-specs=classpath:probe/probe-api.yaml,classpath:probe/platform-blocks-api.yaml") else emptyList()
                val ctx = SpringApplicationBuilder(TaktoviaApplication::class.java).run(*(base + props).map { "--$it" }.toTypedArray())
                Runtime.getRuntime().addShutdownHook(Thread { ctx.close() })
                ProbeServer(ctx)
            }
        }
    }
}

/** Tabelle und Mandanten für die Prüf-Endpunkte; angelegt vom Administrator, genutzt unter der Runtime-Rolle mit RLS. */
object ProbeDb {
    val tenantA: UUID = UUID.fromString("a0000000-0000-4000-8000-0000000000a1")
    val tenantB: UUID = UUID.fromString("b0000000-0000-4000-8000-0000000000b1")
    private var ready = false

    @Synchronized
    fun ensure() {
        if (ready) return
        val admin = TaktoviaTestSupport.adminDataSourceOrNull() ?: return TaktoviaTestSupport.skipOrFail("Administratorzugang nicht konfiguriert")
        admin.connection.use { c ->
            c.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS public.etag_probe")
                st.execute("""CREATE TABLE public.etag_probe(tenant_id uuid NOT NULL, id uuid NOT NULL, revision bigint NOT NULL DEFAULT 1, name text NOT NULL,
                    value text, created_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(tenant_id, id))""")
                st.execute("ALTER TABLE public.etag_probe ENABLE ROW LEVEL SECURITY")
                st.execute("ALTER TABLE public.etag_probe FORCE ROW LEVEL SECURITY")
                st.execute("CREATE POLICY tenant_isolation ON public.etag_probe USING (tenant_id = taktovia.current_tenant()) WITH CHECK (tenant_id = taktovia.current_tenant())")
                st.execute("GRANT SELECT, INSERT, UPDATE ON public.etag_probe TO taktovia_runtime")
                for ((id, name) in listOf(tenantA to "probe-a", tenantB to "probe-b"))
                    st.execute("INSERT INTO taktovia.tenant(id, name, status) VALUES ('$id', '$name', 'ACTIVE') ON CONFLICT DO NOTHING")
            }
        }
        ready = true
    }
}
