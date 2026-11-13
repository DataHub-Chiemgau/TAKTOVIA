package de.taktovia.app.fleet

import de.taktovia.app.identity.IdentityEnv
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * WP-M3-51 über die echte Anwendung: `POST /v1/repair-orders`, `POST /v1/repair-orders/{id}/transitions` und `POST /v1/vehicles/{id}/release` mit Rollen der Rechtematrix, `If-Match`
 * (`safety_revision`), Vieraugenpflicht, Idempotenz und Fehlversuchen im Audit. AT-35: der Nachweis allein hebt die Sperre nicht auf; AT-51: die Disposition kann sie nicht entfernen; AT-60: erst
 * Nachweis, zweite Freigabe und neue Prüfung machen das Fahrzeug disponierbar.
 */
class RepairReleaseApiTest {
    private val env = IdentityEnv
    private fun key() = "k-" + UUID.randomUUID()
    private fun post(path: String, body: String, token: String, ifMatch: String? = null, idem: String = key()) =
        env.server.send("POST", path, body, env.headers(token, extra = mapOf("Idempotency-Key" to idem) + (if (ifMatch != null) mapOf("If-Match" to ifMatch) else emptyMap())))

    private fun revision(w: DefectApiTest.World) = env.string("SELECT safety_revision::text FROM taktovia.vehicle WHERE id=?", w.vehicle).single()
    private fun blocked(w: DefectApiTest.World) = env.count("SELECT count(*) FROM taktovia.vehicle_block WHERE vehicle_id=? AND status='ACTIVE'", w.vehicle)

    @Test
    fun wiederfreigabe_ueber_die_api_von_der_sperre_bis_zur_freigabe() {
        val w = DefectApiTest.World(env)
        // kritische Meldung: Fahrzeug sofort gesperrt
        val d = env.post("/v1/defects", w.body(category = "BRAKES", extra = ""","kind":"MALFUNCTION","vehicle_area":"WHEELS_TIRES","observed_effect":"NOT_ROADWORTHY""""), w.dispatcherToken, null, mapOf("Idempotency-Key" to key()))
        assertEquals(201, d.status, d.text); val defect = d.json["id"].asString(); assertEquals(1, blocked(w))
        val triaged = post("/v1/defects/$defect/triage", """{"severity":"SAFETY_CRITICAL","decision":"IN_PROGRESS"}""", w.workshopToken, d.header("ETag"))
        assertEquals(200, triaged.status, triaged.text); assertEquals("IN_PROGRESS", triaged.json["status"].asString())
        // Reparaturauftrag: nur die Werkstatt; die Akte geht auf REPAIR
        assertEquals(403, post("/v1/repair-orders", """{"defect_id":"$defect"}""", w.dispatcherToken).status)
        val created = post("/v1/repair-orders", """{"defect_id":"$defect","workshop_ref":"Werkstatt Nord","cost_reference":"KST-100"}""", w.workshopToken)
        assertEquals(201, created.status, created.text); val order = created.json["id"].asString(); assertEquals("OPEN", created.json["status"].asString()); assertEquals("/v1/repair-orders/$order", created.header("Location"))
        assertEquals("REPAIR", env.get("/v1/defects/$defect", w.workshopToken).json["status"].asString())
        assertEquals(409, post("/v1/repair-orders", """{"defect_id":"$defect"}""", w.workshopToken).status, "zweiter aktiver Auftrag")
        // Übergänge mit If-Match; Nachweis nur mit Evidenz der Akte
        assertEquals(428, post("/v1/repair-orders/$order/transitions", """{"to":"IN_PROGRESS"}""", w.workshopToken).status)
        assertEquals(403, post("/v1/repair-orders/$order/transitions", """{"to":"IN_PROGRESS"}""", w.dispatcherToken, created.header("ETag")).status)
        val running = post("/v1/repair-orders/$order/transitions", """{"to":"IN_PROGRESS"}""", w.workshopToken, created.header("ETag")); assertEquals(200, running.status, running.text)
        val ev = UUID.randomUUID()
        env.exec("INSERT INTO taktovia.defect_evidence(tenant_id,id,defect_id,media_ref,sha256,captured_at,created_by) VALUES (?,?,?,'obj',?,now(),'user:t')", w.tenant.value, ev, UUID.fromString(defect), "b".repeat(64))
        val noProof = post("/v1/repair-orders/$order/transitions", """{"to":"PROOF_RECORDED"}""", w.workshopToken, running.header("ETag"))
        assertEquals(422, noProof.status); assertEquals("/proof_evidence_id", noProof.json["violations"][0]["pointer"].asString())
        val proven = post("/v1/repair-orders/$order/transitions", """{"to":"PROOF_RECORDED","proof_evidence_id":"$ev"}""", w.workshopToken, running.header("ETag"))
        assertEquals(200, proven.status, proven.text); assertEquals("PROOF_RECORDED", proven.json["status"].asString()); assertEquals(ev.toString(), proven.json["proof_evidence_id"].asString())
        assertEquals(1, blocked(w), "AT-35: Werkstattabschluss ohne technische Freigabe lässt das Fahrzeug gesperrt")
        assertEquals(1, env.get("/v1/repair-orders?defect_id=$defect", w.dispatcherToken).json["items"].size()); assertEquals(403, env.get("/v1/repair-orders", w.driverToken).status)
        // Folgeprüfung nach dem Nachweis
        val shift = UUID.randomUUID(); val tpl = UUID.randomUUID(); val run = UUID.randomUUID()
        env.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,now(),now()+interval '1 hour','Europe/Berlin',0,'PUBLISHED')", w.tenant.value, shift, UUID.randomUUID(), w.vehicle)
        env.exec("INSERT INTO taktovia.checklist_template_revision(tenant_id,id,template_id,version,vehicle_class,service_mode,created_by) VALUES (?,?,?,1,'PKW','AREA','user:t')", w.tenant.value, tpl, UUID.randomUUID())
        env.exec("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,status,outcome,started_at,completed_at,received_at,created_by) VALUES (?,?,?,?,?,?,'FOLLOW_UP','COMPLETED','PASSED',now(),now() + interval '1 second',now() + interval '1 second','user:t')",
            w.tenant.value, run, shift, w.vehicle, UUID.randomUUID(), tpl)
        val body = """{"repair_order_id":"$order","follow_up_inspection_id":"$run","reason":"Bremsanlage erneuert und geprüft"}"""
        val path = "/v1/vehicles/${w.vehicle}/release"; val rev = "\"${revision(w)}\""
        // AT-51: die Disposition kann die Sperre nicht entfernen; ohne If-Match 428, veraltet 412
        assertEquals(403, post(path, body, w.dispatcherToken, rev).status); assertEquals(403, post(path, body, w.driverToken, rev).status); assertEquals(403, post(path, body, w.supportToken, rev).status)
        assertEquals(428, post(path, body, w.workshopToken).status); assertEquals(412, post(path, body, w.workshopToken, "\"99\"").status)
        assertEquals(422, post(path, """{"repair_order_id":"$order"}""", w.workshopToken, rev).status)
        assertEquals(1, blocked(w))
        // erste Freigabe (technisch): ausstehend; Wiederholung mit demselben Idempotenzschlüssel liefert dieselbe Antwort ohne zweite Entscheidung
        val k = key(); val first = post(path, body, w.workshopToken, rev, k)
        assertEquals(201, first.status, first.text); assertEquals("PENDING_SECOND_APPROVAL", first.json["decision"].asString()); assertEquals(0, first.json["lifted_block_ids"].size())
        val replay = post(path, body, w.workshopToken, rev, k); assertEquals(201, replay.status); assertEquals(first.json["id"].asString(), replay.json["id"].asString())
        assertEquals(1, env.count("SELECT count(*) FROM taktovia.vehicle_release_decision WHERE repair_order_id=?", UUID.fromString(order)))
        assertEquals(1, blocked(w))
        // dieselbe Person mit neuem Schlüssel kann nicht auch die zweite sein (Vieraugenpflicht); Fehlversuche stehen im Audit
        val same = post(path, body, w.workshopToken, rev); assertEquals(403, same.status, same.text)
        assertTrue(env.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='fleet.release.attempt_rejected' AND object_ref=?", w.tenant.value, "vehicle:${w.vehicle}") >= 1)
        // zweite Person (Betriebsleitung): Entscheidung RELEASED, Sperre dieser Akte aufgehoben, safety_revision erhöht, keine Einsatzfreigabe
        val second = post(path, body, w.adminToken, rev)
        assertEquals(201, second.status, second.text); assertEquals("RELEASED", second.json["decision"].asString()); assertEquals(1, second.json["lifted_block_ids"].size())
        assertEquals("workshop", second.json["decided_by_role"].asString()); assertEquals("tenant_admin", second.json["second_approver_role"].asString()); assertEquals(defect, second.json["defect_id"].asString())
        assertEquals(0, blocked(w)); assertEquals((rev.trim('"').toLong() + 1).toString(), revision(w)); assertEquals(second.json["safety_revision"].asLong().toString(), revision(w))
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.resource_release WHERE vehicle_id=? AND revoked_at IS NULL", w.vehicle), "die Entscheidung erteilt keine Einsatzfreigabe")
        // Wiederholung nach abgeschlossener Entscheidung (neuer Schlüssel, neuer Stand): 200 mit derselben Entscheidung
        val again = post(path, body, w.adminToken, "\"${revision(w)}\""); assertEquals(200, again.status, again.text); assertEquals(second.json["id"].asString(), again.json["id"].asString())
    }

    @Test
    fun fremde_objekte_ergeben_404_und_ohne_nachweis_409() {
        val w = DefectApiTest.World(env); val other = DefectApiTest.World(env)
        val d = env.post("/v1/defects", w.body(), w.dispatcherToken, null, mapOf("Idempotency-Key" to key()))
        post("/v1/defects/${d.json["id"].asString()}/triage", """{"severity":"COSMETIC","decision":"IN_PROGRESS"}""", w.dispatcherToken, d.header("ETag"))
        val order = post("/v1/repair-orders", """{"defect_id":"${d.json["id"].asString()}"}""", w.workshopToken)
        assertEquals(404, post("/v1/repair-orders", """{"defect_id":"${d.json["id"].asString()}"}""", other.workshopToken).status)
        assertEquals(404, post("/v1/repair-orders/${order.json["id"].asString()}/transitions", """{"to":"IN_PROGRESS"}""", other.workshopToken, order.header("ETag")).status)
        assertEquals(404, post("/v1/vehicles/${other.vehicle}/release", """{"repair_order_id":"${order.json["id"].asString()}","follow_up_inspection_id":"${UUID.randomUUID()}","reason":"x"}""", other.workshopToken, "\"1\"").status)
        val noProof = post("/v1/vehicles/${w.vehicle}/release", """{"repair_order_id":"${order.json["id"].asString()}","follow_up_inspection_id":"${UUID.randomUUID()}","reason":"zu früh"}""", w.workshopToken, "\"${revision(w)}\"")
        assertEquals(409, noProof.status, noProof.text); assertEquals("INVALID_STATE_TRANSITION", noProof.json["code"].asString())
        assertEquals(0, env.count("SELECT count(*) FROM taktovia.vehicle_release_decision WHERE tenant_id=?", w.tenant.value))
    }
}
