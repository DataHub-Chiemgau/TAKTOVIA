package de.taktovia.app.contract

import de.taktovia.app.fleet.DefectApiTest
import de.taktovia.app.fleet.DefectEvidenceApiTest
import de.taktovia.app.identity.IdentityEnv
import de.taktovia.platform.storage.ObjectKey
import de.taktovia.platform.storage.TenantObjectStore
import java.util.UUID

/** Vertragsfälle der Fotoevidenz (WP-M3-44): je Operation ein positiver Fall und ein Fehlerfall gegen die echte Anwendung. */
internal object DefectEvidenceContractCases {
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())
    private fun w() = DefectApiTest.World(IdentityEnv).also { it.shift(it.driver) }
    private val store get() = IdentityEnv.server.ctx.getBean(TenantObjectStore::class.java)

    private fun ticket(w: DefectApiTest.World, seed: Int, upload: Boolean = true): String {
        val bytes = DefectEvidenceApiTest.jpeg(seed)
        val t = IdentityEnv.post("/v1/defect-evidence-uploads", DefectEvidenceApiTest.ticketBody(bytes), w.driverToken, null, idem())
        if (upload) store.put(ObjectKey(w.tenant, t.json["upload_key"].asString()), bytes, "image/jpeg")
        return t.json["id"].asString()
    }

    /** Akte mit einem freigegebenen Foto; liefert (Akte, Foto). */
    private fun released(w: DefectApiTest.World, seed: Int): Pair<String, String> {
        val id = ticket(w, seed)
        IdentityEnv.post("/v1/defect-evidence-uploads/$id/complete", "{}", w.driverToken, null, idem() + ("If-Match" to "\"1\""))
        val d = IdentityEnv.post("/v1/defects", w.body(extra = ""","evidence_ids":["$id"]"""), w.driverToken, null, idem())
        return d.json["id"].asString() to id
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("requestDefectEvidenceUpload",
            201, { val w = w(); val b = DefectEvidenceApiTest.jpeg(11); ContractRequest("POST", "/v1/defect-evidence-uploads", DefectEvidenceApiTest.ticketBody(b), w.env.headers(w.driverToken, extra = idem())) },
            422, { val w = w(); val b = DefectEvidenceApiTest.jpeg(12); ContractRequest("POST", "/v1/defect-evidence-uploads", DefectEvidenceApiTest.ticketBody(b, mime = "image/gif"), w.env.headers(w.driverToken, extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("getDefectEvidenceUpload",
            200, { val w = w(); ContractRequest("GET", "/v1/defect-evidence-uploads/${ticket(w, 13, upload = false)}", headers = w.env.headers(w.driverToken)) },
            404, { val w = w(); ContractRequest("GET", "/v1/defect-evidence-uploads/${UUID.randomUUID()}", headers = w.env.headers(w.driverToken)) }, server = { IdentityEnv.server }),
        ContractCase("completeDefectEvidenceUpload",
            200, { val w = w(); ContractRequest("POST", "/v1/defect-evidence-uploads/${ticket(w, 14)}/complete", "{}", w.env.headers(w.driverToken, extra = idem() + ("If-Match" to "\"1\""))) },
            422, { val w = w(); ContractRequest("POST", "/v1/defect-evidence-uploads/${ticket(w, 15, upload = false)}/complete", "{}", w.env.headers(w.driverToken, extra = idem() + ("If-Match" to "\"1\""))) }, server = { IdentityEnv.server }),
        ContractCase("listDefectEvidence",
            200, { val w = w(); val (d, _) = released(w, 16); ContractRequest("GET", "/v1/defects/$d/evidence", headers = w.env.headers(w.workshopToken)) },
            404, { val w = w(); ContractRequest("GET", "/v1/defects/${UUID.randomUUID()}/evidence", headers = w.env.headers(w.workshopToken)) }, server = { IdentityEnv.server }),
        ContractCase("getDefectEvidenceDownload",
            200, { val w = w(); val (d, e) = released(w, 17); ContractRequest("GET", "/v1/defects/$d/evidence/$e/download", headers = w.env.headers(w.workshopToken)) },
            404, { val w = w(); ContractRequest("GET", "/v1/defects/${UUID.randomUUID()}/evidence/${UUID.randomUUID()}/download", headers = w.env.headers(w.workshopToken)) }, server = { IdentityEnv.server }),
        ContractCase("readSignedObject",
            200, { val w = w(); val (d, e) = released(w, 18); val url = IdentityEnv.get("/v1/defects/$d/evidence/$e/download", w.workshopToken).json["url"].asString(); ContractRequest("GET", url, headers = w.env.headers(w.workshopToken)) },
            404, { val w = w(); ContractRequest("GET", "/v1/objects/${w.tenant}/defect-evidence-x?exp=1&purpose=defect_evidence&sig=${"0".repeat(64)}", headers = w.env.headers(w.workshopToken)) }, server = { IdentityEnv.server }),
    )
}
