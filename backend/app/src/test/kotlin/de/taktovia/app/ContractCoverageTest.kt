package de.taktovia.app

import de.taktovia.app.contract.ContractRunner
import de.taktovia.app.contract.M1ContractCases
import de.taktovia.app.contract.ProbeContractCases
import de.taktovia.app.openapi.RegisteredRoute
import de.taktovia.app.openapi.RouteContract
import de.taktovia.app.probe.ProbeServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.nio.file.Files
import java.nio.file.Path

/**
 * LH-6.17-INT-001 / WP-M1-03: Jede registrierte Route hat genau eine operationId in `api/`, jeder gebaute Endpunkt hat
 * Vertragsfälle (positiv und Fehlerfall), ein Endpunkt ohne Vertrag bricht den Build. Das Gerüst wird an den Prüf-Endpunkten
 * bewiesen; die M1-Endpunkte selbst stehen bis zu ihrer Umsetzung auf `api/contract-pending.txt`.
 */
class ContractCoverageTest {
    private val repo = Path.of(System.getProperty("taktovia.repoRoot"))
    private val pending: Set<String> = Files.readAllLines(repo.resolve("api/contract-pending.txt"))
        .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()

    private fun routes(s: ProbeServer) = RouteContract.routes(s.ctx.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping::class.java))

    @Test
    fun jede_registrierte_route_hat_genau_eine_operation_id() {
        // Produktive Anwendung: alle /v1-Routen sind im Vertrag; Pending-Liste stimmt mit dem Ist überein.
        val prod = ProbeServer.production()
        val prodRoutes = routes(prod)
        val v = RouteContract.verify(prodRoutes, prod.registry, pending)
        assertTrue(v.ok, v.errors.joinToString("\n"))
        // Bericht für den CI-Schritt api/tools/check_routes.py (liest dieselben Routen unabhängig nach)
        val out = Path.of("build/contract/routes.json")
        Files.createDirectories(out.parent)
        Files.writeString(out, prodRoutes.joinToString(",\n", "[\n", "\n]\n") { """  {"method": "${it.method}", "path": "${it.path}"}""" })

        // Mit den Prüf-Endpunkten: ihre Verträge stehen in probe/probe-api.yaml, jede Route findet genau eine operationId.
        val probe = ProbeServer.get()
        val pv = RouteContract.verify(routes(probe), probe.registry, pending)
        assertTrue(pv.ok, pv.errors.joinToString("\n"))
        assertTrue(pv.routed.keys.containsAll(listOf("createProbeItem", "getProbeItem", "updateProbeItem")), pv.routed.keys.toString())

        // Negativ: eine absichtlich undokumentierte Route, eine falsche Methode, ein Pfad außerhalb von /v1
        val bad = RouteContract.verify(routes(probe) + listOf(RegisteredRoute("GET", "/v1/undokumentiert"), RegisteredRoute("DELETE", "/v1/probe/items"),
            RegisteredRoute("GET", "/intern/debug")), probe.registry, pending)
        assertFalse(bad.ok)
        assertTrue(bad.errors.any { it.contains("GET /v1/undokumentiert") && it.contains("keine operationId") }, bad.errors.toString())
        assertTrue(bad.errors.any { it.contains("DELETE /v1/probe/items") }, bad.errors.toString())
        assertTrue(bad.errors.any { it.contains("/intern/debug") }, bad.errors.toString())
        // Negativ: zwei Routen auf dieselbe operationId; dokumentierte Operation ohne Route und ohne Pending-Eintrag; gebaute Operation noch in Pending
        val dup = RouteContract.verify(routes(probe) + RegisteredRoute("GET", "/v1/probe/items/{andere_id}"), probe.registry, pending)
        assertTrue(dup.errors.any { it.contains("getProbeItem") && it.contains("doppelt") }, dup.errors.toString())
        val missingPending = RouteContract.verify(routes(probe), probe.registry, pending - "getOffer")
        assertTrue(missingPending.errors.any { it.contains("getOffer") && it.contains("nicht in contract-pending.txt") }, missingPending.errors.toString())
        val staleness = RouteContract.verify(routes(probe), probe.registry, pending + "getProbeItem")
        assertTrue(staleness.errors.any { it.contains("getProbeItem") && it.contains("gebaut") }, staleness.errors.toString())
    }

    @Test
    fun alle_m1_endpunkte_positiv_und_fehlerfall() {
        // 1. Produktive Endpunkte: jeder gebaute hat einen Vertragsfall, der grün läuft.
        val prod = ProbeServer.production()
        val v = RouteContract.verify(routes(prod), prod.registry, pending)
        assertTrue(v.ok, v.errors.joinToString("\n"))
        val prodRunner = ContractRunner(prod, prod.registry)
        val findings = v.routed.keys.flatMap { id ->
            val case = M1ContractCases.cases[id] ?: return@flatMap listOf("$id: gebaut, aber ohne Vertragsfall (M1ContractCases)")
            prodRunner.run(case)
        }
        assertEquals(emptyList<String>(), findings)
        assertEquals(emptySet<String>(), M1ContractCases.cases.keys - v.routed.keys, "Vertragsfall für nicht gebauten Endpunkt")

        // 2. Das Gerüst an den Prüf-Endpunkten: jeder dort gebaute Endpunkt hat positiv + Fehlerfall und besteht.
        val probe = ProbeServer.get("taktovia.api.rate-limit.requests=100000")
        val pc = ProbeContractCases(probe)
        val routed = RouteContract.verify(routes(probe), probe.registry, pending).routed.keys.filter { it.contains("Probe") }
        assertEquals(emptyList<String>(), routed.filter { it !in pc.cases && it !in pc.exempt }.map { "$it: ohne Vertragsfall" })
        val runner = ContractRunner(probe, probe.registry)
        val probeFindings = pc.cases.values.filter { it.operationId != "getProbeLeaky" }.flatMap { runner.run(it) }
        assertEquals(emptyList<String>(), probeFindings)
        assertTrue(pc.cases.size >= 6)
    }

    @Test
    fun antwort_mit_zusaetzlichem_feld_wird_erkannt() {
        val probe = ProbeServer.get("taktovia.api.rate-limit.requests=100000")
        val runner = ContractRunner(probe, probe.registry)
        val findings = runner.run(ProbeContractCases(probe).cases.getValue("getProbeLeaky"))
        assertTrue(findings.any { it.contains("getProbeLeaky") && it.contains("verletzt das Vertragsschema") && it.contains("additionalProperties") },
            "Feld außerhalb des Vertrags muss erkannt werden: $findings")
        assertFalse(findings.any { it.contains("darf nicht in der Antwort stehen") }, "Befund enthält keine Antwortwerte: $findings")
        // ein nicht dokumentierter Status ist ebenfalls ein Befund
        val undocumented = de.taktovia.app.contract.ContractCase("getProbeAdmin", 200, { de.taktovia.app.contract.ContractRequest("GET", "/v1/probe/admin", headers = mapOf("X-Test-Scopes" to "probe:admin")) },
            404, { de.taktovia.app.contract.ContractRequest("GET", "/v1/probe/nicht-vorhanden") })
        assertTrue(runner.run(undocumented).any { it.contains("nicht dokumentiert") }, "404 ist für getProbeAdmin nicht dokumentiert")
    }
}
