package de.taktovia.app

import de.taktovia.app.probe.ProbeDb
import de.taktovia.app.probe.ProbeServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/** W3C `traceparent`: gültig → Trace-ID wird Correlation-ID; ungültig oder fehlend → neue Correlation-ID (Kap. 05 §5.1). */
class TraceparentTest {
    private val s get() = ProbeServer.get()
    private val base get() = mapOf("X-Test-Tenant" to ProbeDb.tenantA.toString(), "X-Test-Actor" to "trace-test")

    @Test
    fun ungueltiger_traceparent_neue_correlation_id() {
        val ok = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        val good = s.get("/v1/probe/nicht-vorhanden", base + ("traceparent" to ok))
        assertEquals("4bf92f35-77b3-4da6-a3ce-929d0e0e4736", good.json["correlation_id"].asString())
        assertEquals("4bf92f35-77b3-4da6-a3ce-929d0e0e4736", good.header("X-Correlation-Id"))
        val seen = mutableSetOf<String>()
        listOf("kaputt", "00-zz-zz-zz", "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
            "00-00000000000000000000000000000000-00f067aa0ba902b7-01", "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01").forEach { bad ->
            val r = s.get("/v1/probe/nicht-vorhanden", base + ("traceparent" to bad))
            val id = r.json["correlation_id"].asString()
            UUID.fromString(id)
            assertNotEquals("4bf92f35-77b3-4da6-a3ce-929d0e0e4736", id, bad)
            assertEquals(id, r.header("X-Correlation-Id"))
            seen += id
        }
        assertEquals(5, seen.size, "jede Anfrage mit ungültigem Header erhält eine eigene Correlation-ID")
        // ohne Header ebenfalls neue ID
        assertNotEquals(s.get("/v1/probe/nicht-vorhanden", base).json["correlation_id"].asString(), s.get("/v1/probe/nicht-vorhanden", base).json["correlation_id"].asString())
    }
}
