package de.taktovia.app.identity

import de.taktovia.app.contract.DriverReleaseWorld
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * LH-I.4-MUL-005 / WP-M1-35 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer statt Keycloak): Erteilen nur durch die Personalplanung des Eigentümers (403 sonst,
 * kein Audit), der Empfänger sieht nur die feste Feldliste im Zeitraum, nach dem Widerruf mit der nächsten Anfrage nichts mehr, nicht freigegebener Dienst und fremder
 * Mandant gleichartig 404, die Freigabe gilt nur für die benannten Dienste.
 */
class DriverReleaseApiTest {
    private val env = IdentityEnv
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())

    @Test
    fun erteilen_lesen_widerrufen_ueber_die_api() {
        val w = DriverReleaseWorld()
        val c = env.post("/v1/driver-releases", w.body(), w.plannerToken, null, idem())
        assertEquals(201, c.status, c.text); assertEquals("\"1\"", c.header("ETag")); val id = c.json["id"].asString(); assertEquals("/v1/driver-releases/$id", c.header("Location"))
        assertEquals(w.recipient.toString(), c.json["recipient_tenant_id"].asString())

        val p = env.get("/v1/driver-releases/$id/projection?service_id=${w.service}", w.dispatcherToken)
        assertEquals(200, p.status, p.text)
        assertEquals(setOf("id", "service_ids", "valid_from", "valid_to", "qualifications", "contact_channel"), p.json.propertyNames().toSet())
        assertEquals("PBEF", p.json["qualifications"][0]["code"].asString()); assertEquals("PHONE", p.json["contact_channel"]["kind"].asString())
        listOf("Erika", "akte-1", w.driver.toString(), w.owner.toString()).forEach { assertFalse(p.text.contains(it), "Antwort darf '$it' nicht enthalten") }
        // Projektion nur für den freigegebenen Dienst, nur im Empfängerkontext; alles andere gleichartig 404
        val other = env.get("/v1/driver-releases/$id/projection?service_id=${UUID.randomUUID()}", w.dispatcherToken)
        val foreign = env.get("/v1/driver-releases/$id/projection?service_id=${w.service}", w.env.token(UUID.randomUUID().also { w.env.grant(w.env.tenant(), it, "dispatcher") }))
        val ownerRole = env.get("/v1/driver-releases/$id/projection?service_id=${w.service}", w.env.token(UUID.randomUUID().also { u -> w.env.grant(w.owner, u, "dispatcher") }))
        listOf(other, foreign, ownerRole).forEach { assertEquals(404, it.status, it.text) }
        assertEquals(1, listOf(other, foreign, ownerRole).map { it.json["type"].asString() + "|" + it.json["title"].asString() }.toSet().size, "gleichartige Antwort")
        assertEquals(422, env.get("/v1/driver-releases/$id/projection?service_id=kein-uuid", w.dispatcherToken).status)

        // Widerruf: nur Eigentümer-Personalplanung; der Empfänger kann weder widerrufen noch ansehen; nach dem Widerruf kein Zugriff mehr
        assertEquals(404, env.delete("/v1/driver-releases/$id", w.recipientPlannerToken, 1).status, "Empfänger kann nicht widerrufen")
        assertEquals(403, env.delete("/v1/driver-releases/$id", w.dispatcherToken, 1).status)
        assertEquals(428, env.delete("/v1/driver-releases/$id", w.plannerToken, null).status)
        assertEquals(412, env.delete("/v1/driver-releases/$id", w.plannerToken, 9).status)
        assertEquals(204, env.delete("/v1/driver-releases/$id", w.plannerToken, 1).status)
        assertEquals(404, env.get("/v1/driver-releases/$id/projection?service_id=${w.service}", w.dispatcherToken).status, "Widerruf wirkt mit der nächsten Anfrage")
        assertEquals(409, env.delete("/v1/driver-releases/$id", w.plannerToken, 2).status)
        assertEquals(1, env.events(w.owner, "staff.driver_release.granted")); assertEquals(1, env.events(w.owner, "staff.driver_release.revoked"))
    }

    @Test
    fun erteilen_nur_fuer_berechtigte_rolle_ohne_wirkung_und_ohne_audit() {
        val w = DriverReleaseWorld()
        assertEquals(403, env.post("/v1/driver-releases", w.body(), w.adminToken, null, idem()).status, "Administrator hat keinen Zugriff auf HR-Daten ohne Fachrolle")
        assertEquals(403, env.post("/v1/driver-releases", w.body(), w.dispatcherToken, null, idem()).status)
        assertEquals(404, env.post("/v1/driver-releases", w.body(driverId = w.driver, recipientId = w.owner.value), w.recipientPlannerToken, null, idem()).status, "Empfänger kann den Fahrer des Eigentümers nicht freigeben")
        assertEquals(404, env.post("/v1/driver-releases", w.body(recipientId = UUID.randomUUID()), w.plannerToken, null, idem()).status)
        assertEquals(422, env.post("/v1/driver-releases", w.body(recipientId = w.owner.value), w.plannerToken, null, idem()).status)
        assertEquals(422, env.post("/v1/driver-releases", w.body(from = "2030-01-02T00:00:00Z", to = "2030-01-01T00:00:00Z"), w.plannerToken, null, idem()).status)
        assertEquals(422, env.post("/v1/driver-releases", w.body().replace("\"valid_to\"", "\"salary\":1,\"valid_to\""), w.plannerToken, null, idem()).status, "unbekanntes Feld")
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.driver_release WHERE tenant_id IN (?,?)", w.owner.value, w.recipient.value))
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.audit_event WHERE action LIKE 'staff.driver_release.%' AND tenant_id IN (?,?)", w.owner.value, w.recipient.value))
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.outbox WHERE event_type LIKE 'driver.release_%' AND tenant_id IN (?,?)", w.owner.value, w.recipient.value))
    }

    @Test
    fun zeitraum_zwei_empfaenger_und_nur_benannte_dienste() {
        val w = DriverReleaseWorld(); val c = DriverReleaseWorld()   // C ist ein zweiter Empfänger mit eigenem Dienst
        val future = w.grant(w.body(from = "2098-01-01T00:00:00Z", to = "2099-01-01T00:00:00Z"))
        assertEquals(404, env.get("/v1/driver-releases/${future.first}/projection?service_id=${w.service}", w.dispatcherToken).status, "vor T1")
        val s2 = UUID.randomUUID()
        val multi = w.grant(w.body(services = listOf(w.service, s2)))
        assertEquals(200, env.get("/v1/driver-releases/${multi.first}/projection?service_id=$s2", w.dispatcherToken).status)
        assertEquals(200, env.get("/v1/driver-releases/${multi.first}/projection?service_id=${w.service}", w.dispatcherToken).status)
        assertEquals(404, env.get("/v1/driver-releases/${multi.first}/projection?service_id=${UUID.randomUUID()}", w.dispatcherToken).status, "Dienst nicht freigegeben")
        // derselbe Fahrer zusätzlich an C (anderer Mandant): C sieht nur seine eigene Projektion, B nicht die von C
        val toC = w.grant(w.body(recipientId = c.recipient.value, services = listOf(c.service)))
        assertEquals(200, env.get("/v1/driver-releases/${toC.first}/projection?service_id=${c.service}", c.dispatcherToken).status)
        assertEquals(404, env.get("/v1/driver-releases/${toC.first}/projection?service_id=${c.service}", w.dispatcherToken).status)
        assertEquals(404, env.get("/v1/driver-releases/${multi.first}/projection?service_id=${w.service}", c.dispatcherToken).status)
        // SQL unter der Runtime-Rolle von B: keine Fahrerdaten des Eigentümers (Aufbau über die Anwendung, nicht über den Administrator)
        val seen = env.tx.run(w.recipient) { j ->
            listOf("driver", "qualification_revision", "driver_release").map { t -> j.queryForObject("SELECT count(*) FROM taktovia.$t", Int::class.java)!! } + j.queryForObject("SELECT count(*) FROM taktovia.driver_release_projection", Int::class.java)!!
        }
        assertEquals(listOf(0, 0, 0), seen.take(3)); assertEquals(1, seen[3], "nur die aktive Projektion ist lesbar, die künftige nicht")
    }
}
