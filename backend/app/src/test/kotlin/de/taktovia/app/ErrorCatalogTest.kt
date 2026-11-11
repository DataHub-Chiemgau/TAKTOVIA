package de.taktovia.app

import de.taktovia.app.probe.ProbeServer
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.dataformat.yaml.YAMLMapper
import java.nio.file.Files
import java.nio.file.Path

/** Der Fehlercode-Katalog im Code und in den OpenAPI-Komponenten (`api/common.yaml`) darf nicht auseinanderlaufen. */
class ErrorCatalogTest {
    private val common = YAMLMapper().readTree(Files.readString(Path.of(System.getProperty("taktovia.repoRoot"), "api/common.yaml")))["components"]["schemas"]

    @Test
    fun fehlercodes_und_http_status_stimmen_mit_openapi_ueberein() {
        val enum = common["ErrorCode"]["enum"].values().map { it.asString() }
        assertEquals(ErrorCode.entries.map { it.name }, enum)
        val status = common["ErrorCode"]["x-http-status"]
        ErrorCode.entries.forEach { assertEquals(it.status, status[it.name].asInt(), it.name) }
        // Kap. 05 §5.4: diese Codes mit diesem Status sind Pflicht
        mapOf("NOT_FOUND" to 404, "REVISION_MISMATCH" to 412, "PRECONDITION_REQUIRED" to 428, "IDEMPOTENCY_CONFLICT" to 409,
            "OPERATION_IN_PROGRESS" to 409, "OFFER_EXPIRED" to 409, "HOLD_EXPIRED" to 409, "REQUOTE_REQUIRED" to 409,
            "CAPACITY_UNAVAILABLE" to 409, "COOPERATION_NOT_ALLOWED" to 403, "PROVIDER_UNAVAILABLE" to 503).forEach { (c, st) ->
            assertEquals(st, ErrorCode.of(c)!!.status, c)
        }
    }

    @Test
    fun feldverletzungscodes_stimmen_mit_openapi_ueberein() {
        assertEquals(ViolationCode.entries.map { it.name }, common["ViolationCode"]["enum"].values().map { it.asString() })
    }

    @Test
    fun problem_beispiel_und_typ_uri_sind_stabil() {
        val ex = common["Problem"]["examples"][0]
        assertEquals(ErrorCode.OFFER_EXPIRED.type, ex["type"].asString())
        assertEquals(ErrorCode.OFFER_EXPIRED.status, ex["status"].asInt())
        // der Katalog ist über das geladene Schema validierbar (Registry kennt alle Dateien)
        assertEquals(emptyList<Any>(), ProbeServer.get().registry.schema("Problem").validate(ex))
    }
}
