package de.taktovia.app.driver

import de.taktovia.app.fleet.InspectionApiTest
import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * WP-M3-23 / LH-6.21-CHK-016 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer statt Keycloak): `GET /v1/driver/shifts/{id}/run` und `GET /v1/operator/vehicles` liefern denselben
 * serverseitig abgeleiteten Prüfstatus samt monoton steigender Revision (ausstehend -> in Bearbeitung -> bestanden -> gesperrt). Der Fahrer eines anderen Fahrzeugs erhält keinen Status (404).
 * Die Darstellung in Flutter und Web ist nicht Teil dieses Tests; Gegenstelle ist der echte Server.
 */
class InspectionStatusDriverHttpTest {
    private val env = IdentityEnv

    @Test
    fun fahrer_und_leitstelle_sehen_denselben_status_und_fremder_fahrer_erhaelt_404() {
        val w = InspectionApiTest.World(env)
        env.exec("INSERT INTO taktovia.qualification_revision(tenant_id,id,driver_id,qualification_code,revision,valid_from,approval_status,driver_seq) VALUES (?,?,?,'PBEFG',1,now() - interval '30 days','APPROVED',1)", w.tenant.value, UUID.randomUUID(), w.driverId)
        env.exec("UPDATE taktovia.driver SET qualification_seq = 1 WHERE id = ?", w.driverId)
        w.template("PKW", steps = 3)
        val vehicle = w.vehicle("PKW"); val shift = w.shift(vehicle = vehicle, started = false); val device = UUID.randomUUID()
        check(env.post("/v1/driver/devices", """{"device_id":"$device","platform":"ANDROID","app_version":"1.0.0"}""", w.token, extra = mapOf("Idempotency-Key" to "d-" + UUID.randomUUID())).status == 201)
        check(env.post("/v1/driver/shift-starts", """{"shift_id":"$shift","vehicle_id":"$vehicle","device_id":"$device"}""", w.token, extra = mapOf("Idempotency-Key" to "st-" + UUID.randomUUID())).status == 200)

        fun driverView() = env.get("/v1/driver/shifts/$shift/run", w.token).also { assertEquals(200, it.status, it.text) }.json
        fun operatorView() = env.get("/v1/operator/vehicles/$vehicle", w.dispatcherToken).also { assertEquals(200, it.status, it.text) }.json
        fun both(expected: String): Pair<Long, Long> {
            val d = driverView(); val o = operatorView()
            assertEquals(expected, d["inspection_status"].asString(), "Fahrer"); assertEquals(expected, o["inspection_status"].asString(), "Leitstelle")
            return d["inspection_status_revision"].asLong() to o["inspection_status_revision"].asLong()
        }

        val pending = both("PENDING")
        val id = env.post("/v1/inspections", w.body(shift, vehicle), w.token, null, mapOf("Idempotency-Key" to "i-" + UUID.randomUUID())).also { check(it.status == 201) { it.text } }.json["id"].asString()
        val open = both("IN_PROGRESS")
        val answers = (1..3).joinToString(",") { """{"step_code":"S$it","answer":"OK","answered_at":"${Instant.now().minusSeconds(20)}"}""" }
        val submit = env.server.send("POST", "/v1/inspections/$id/submit", """{"answers":[$answers],"client_submitted_at":"${Instant.now().minusSeconds(2)}"}""",
            env.headers(w.token, extra = mapOf("Idempotency-Key" to "s-" + UUID.randomUUID(), "If-Match" to "\"1\"")), "application/json")
        assertEquals(200, submit.status, submit.text)
        val passed = both("PASSED")
        env.exec("INSERT INTO taktovia.vehicle_block(tenant_id,id,vehicle_id,source,reason_code,set_by) VALUES (?,?,?,'SAFETY','DEFECT_CRITICAL','user:t')", w.tenant.value, UUID.randomUUID(), vehicle)
        val blocked = both("BLOCKED")
        assertTrue(pending.first < open.first && open.first < passed.first && passed.first < blocked.first, "Fahrer-Revisionen steigen streng: $pending $open $passed $blocked")
        assertTrue(pending.second < open.second && open.second < passed.second && passed.second < blocked.second, "Leitstellen-Revisionen steigen: $pending $open $passed $blocked")
        assertEquals(blocked.first, blocked.second, "beide Oberflächen tragen dieselbe Revision")
        // Fahrer eines anderen Fahrzeugs (anderes Konto, nicht dieselbe Schicht): kein Status, einheitlich 404; Fahrgast: 403
        assertEquals(404, env.get("/v1/driver/shifts/$shift/run", w.otherToken).status)
        assertEquals(403, env.get("/v1/driver/shifts/$shift/run", w.passengerToken).status)
    }
}
