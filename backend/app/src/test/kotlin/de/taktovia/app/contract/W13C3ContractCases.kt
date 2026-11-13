package de.taktovia.app.contract

import de.taktovia.app.fleet.DefectApiTest
import de.taktovia.app.identity.IdentityEnv
import java.util.UUID

/** Vertragsfälle der Welle 13, Cluster 3 (Schadenklassifikation und Triage WP-M3-45, Schadenliste WP-M3-49, Reparaturauftrag und Wiederfreigabe WP-M3-51). */
internal object W13C3ContractCases {
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())
    private fun w() = DefectApiTest.World(IdentityEnv)
    private fun defect(w: DefectApiTest.World): String = IdentityEnv.post("/v1/defects", w.body(), w.dispatcherToken, null, idem()).json["id"].asString()

    private fun headers(w: DefectApiTest.World, token: String, ifMatch: String? = null) = w.env.headers(token, extra = idem() + (if (ifMatch != null) mapOf("If-Match" to ifMatch) else emptyMap()))

    /** Akte in Bearbeitung (Triage durch die Disposition). */
    private fun inProgress(w: DefectApiTest.World): String {
        val id = defect(w)
        IdentityEnv.post("/v1/defects/$id/triage", """{"severity":"COSMETIC","decision":"IN_PROGRESS"}""", w.dispatcherToken, null, idem() + ("If-Match" to "\"1\""))
        return id
    }

    private fun order(w: DefectApiTest.World, defect: String = inProgress(w)): Pair<String, String> {
        val r = IdentityEnv.post("/v1/repair-orders", """{"defect_id":"$defect","cost_reference":"KST-1"}""", w.workshopToken, null, idem())
        return r.json["id"].asString() to r.header("ETag")!!
    }

    /** Reparaturauftrag mit Nachweis (PROOF_RECORDED) samt Folgeprüfung des Fahrzeugs nach dem Nachweis; liefert Auftrag und Prüflauf. */
    private fun proven(w: DefectApiTest.World): Pair<String, UUID> {
        val defect = inProgress(w); val (id, etag) = order(w, defect)
        val running = IdentityEnv.post("/v1/repair-orders/$id/transitions", """{"to":"IN_PROGRESS"}""", w.workshopToken, null, idem() + ("If-Match" to etag))
        val ev = UUID.randomUUID()
        IdentityEnv.exec("INSERT INTO taktovia.defect_evidence(tenant_id,id,defect_id,media_ref,sha256,captured_at,created_by) VALUES (?,?,?,'obj',?,now(),'user:t')", w.tenant.value, ev, UUID.fromString(defect), UUID.randomUUID().toString().replace("-", "").repeat(2))
        IdentityEnv.post("/v1/repair-orders/$id/transitions", """{"to":"PROOF_RECORDED","proof_evidence_id":"$ev"}""", w.workshopToken, null, idem() + ("If-Match" to running.header("ETag")!!))
        val shift = UUID.randomUUID(); val tpl = UUID.randomUUID(); val run = UUID.randomUUID()
        IdentityEnv.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,now(),now()+interval '1 hour','Europe/Berlin',0,'PUBLISHED')", w.tenant.value, shift, UUID.randomUUID(), w.vehicle)
        IdentityEnv.exec("INSERT INTO taktovia.checklist_template_revision(tenant_id,id,template_id,version,vehicle_class,service_mode,created_by) VALUES (?,?,?,1,'PKW','AREA','user:t')", w.tenant.value, tpl, UUID.randomUUID())
        IdentityEnv.exec("INSERT INTO taktovia.inspection_run(tenant_id,id,shift_id,vehicle_id,driver_ref,template_revision_id,kind,status,outcome,started_at,completed_at,received_at,created_by) VALUES (?,?,?,?,?,?,'FOLLOW_UP','COMPLETED','PASSED',now(),now() + interval '1 second',now() + interval '1 second','user:t')",
            w.tenant.value, run, shift, w.vehicle, UUID.randomUUID(), tpl)
        return id to run
    }

    private fun safetyRevision(w: DefectApiTest.World) = IdentityEnv.string("SELECT safety_revision::text FROM taktovia.vehicle WHERE id=?", w.vehicle).single()

    val cases: List<ContractCase> = listOf(
        ContractCase("triageDefect",
            200, { val w = w(); ContractRequest("POST", "/v1/defects/${defect(w)}/triage", """{"severity":"COSMETIC","decision":"REVIEWED"}""", w.env.headers(w.dispatcherToken, extra = idem() + ("If-Match" to "\"1\""))) },
            422, { val w = w(); ContractRequest("POST", "/v1/defects/${defect(w)}/triage", """{"severity":"OPERATIONALLY_RELEVANT","decision":"REVIEWED"}""", w.env.headers(w.dispatcherToken, extra = idem() + ("If-Match" to "\"1\""))) }, server = { IdentityEnv.server }),
        ContractCase("createRepairOrder",
            201, { val w = w(); ContractRequest("POST", "/v1/repair-orders", """{"defect_id":"${inProgress(w)}","workshop_ref":"Werkstatt","cost_reference":"KST-1"}""", headers(w, w.workshopToken)) },
            409, { val w = w(); ContractRequest("POST", "/v1/repair-orders", """{"defect_id":"${defect(w)}"}""", headers(w, w.workshopToken)) }, server = { IdentityEnv.server }),
        ContractCase("listRepairOrders",
            200, { val w = w(); order(w); ContractRequest("GET", "/v1/repair-orders?vehicle_id=${w.vehicle}", headers = w.env.headers(w.dispatcherToken)) },
            403, { val w = w(); ContractRequest("GET", "/v1/repair-orders", headers = w.env.headers(w.driverToken)) }, server = { IdentityEnv.server }),
        ContractCase("transitionRepairOrder",
            200, { val w = w(); val (id, etag) = order(w); ContractRequest("POST", "/v1/repair-orders/$id/transitions", """{"to":"IN_PROGRESS"}""", headers(w, w.workshopToken, etag)) },
            409, { val w = w(); val (id, etag) = order(w); ContractRequest("POST", "/v1/repair-orders/$id/transitions", """{"to":"PROOF_RECORDED"}""", headers(w, w.workshopToken, etag)) }, server = { IdentityEnv.server }),
        ContractCase("createVehicleRelease",
            201, { val w = w(); val (id, run) = proven(w); ContractRequest("POST", "/v1/vehicles/${w.vehicle}/release", """{"repair_order_id":"$id","follow_up_inspection_id":"$run","reason":"Bremsanlage erneuert"}""", headers(w, w.workshopToken, "\"${safetyRevision(w)}\"")) },
            409, { val w = w(); val (id, _) = order(w); ContractRequest("POST", "/v1/vehicles/${w.vehicle}/release", """{"repair_order_id":"$id","follow_up_inspection_id":"${UUID.randomUUID()}","reason":"zu früh"}""", headers(w, w.workshopToken, "\"${safetyRevision(w)}\"")) }, server = { IdentityEnv.server }),
    )
}
