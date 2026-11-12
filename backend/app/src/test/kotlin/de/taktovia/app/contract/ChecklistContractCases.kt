package de.taktovia.app.contract

import de.taktovia.app.fleet.ChecklistTemplateApiTest
import de.taktovia.app.identity.IdentityEnv
import java.util.Base64
import java.util.UUID

/** Vertragsfälle der Checklistenvorlagen (WP-M3-10): je Operation ein positiver Fall und ein Fehlerfall gegen die echte Anwendung. */
internal object ChecklistContractCases {
    private fun idem() = mapOf("Idempotency-Key" to "contract-" + UUID.randomUUID())
    private fun w() = ChecklistTemplateApiTest.World(IdentityEnv)
    private const val PNG = "iVBORw0KGgoBAgM="   // PNG-Signatur und drei Byte
    private val IMAGE = """{"content_type":"image/png","data_base64":"$PNG"}"""
    private fun create(w: ChecklistTemplateApiTest.World): String = IdentityEnv.post("/v1/operator/checklist-templates", w.body(), w.authorToken, null, idem()).json["id"].asString()
    private fun published(w: ChecklistTemplateApiTest.World): String = create(w).also {
        IdentityEnv.server.send("POST", "/v1/operator/checklist-templates/$it/publish", null, IdentityEnv.headers(w.reviewerToken, extra = idem() + ("If-Match" to "\"1\"")), null)
    }

    val cases: List<ContractCase> = listOf(
        ContractCase("listChecklistTemplates",
            200, { val w = w(); ContractRequest("GET", "/v1/checklist-templates", headers = w.env.headers(w.driverToken)) },
            403, { val w = w(); ContractRequest("GET", "/v1/checklist-templates", headers = w.env.headers(w.passengerToken())) }, server = { IdentityEnv.server }),
        ContractCase("listChecklistTemplateRevisions",
            200, { val w = w(); create(w); ContractRequest("GET", "/v1/operator/checklist-templates", headers = w.env.headers(w.dispatcherToken)) },
            422, { val w = w(); ContractRequest("GET", "/v1/operator/checklist-templates?state=ARCHIV", headers = w.env.headers(w.dispatcherToken)) }, server = { IdentityEnv.server }),
        ContractCase("createChecklistTemplate",
            201, { val w = w(); ContractRequest("POST", "/v1/operator/checklist-templates", w.body(), w.env.headers(w.authorToken, extra = idem())) },
            422, { val w = w(); ContractRequest("POST", "/v1/operator/checklist-templates", w.body(steps = """[{"step_code":"BRAKES","input_kind":"OK_DEFECT_NA","mandatory":true}]"""), w.env.headers(w.authorToken, extra = idem())) }, server = { IdentityEnv.server }),
        ContractCase("getChecklistTemplateRevision",
            200, { val w = w(); ContractRequest("GET", "/v1/operator/checklist-templates/${create(w)}", headers = w.env.headers(w.dispatcherToken)) },
            404, { val w = w(); ContractRequest("GET", "/v1/operator/checklist-templates/${UUID.randomUUID()}", headers = w.env.headers(w.dispatcherToken)) }, server = { IdentityEnv.server }),
        ContractCase("updateChecklistTemplateDraft",
            200, { val w = w(); ContractRequest("PUT", "/v1/operator/checklist-templates/${create(w)}", w.body(cls = "BUS"), w.env.headers(w.authorToken, extra = idem() + ("If-Match" to "\"1\""))) },
            412, { val w = w(); ContractRequest("PUT", "/v1/operator/checklist-templates/${create(w)}", w.body(), w.env.headers(w.authorToken, extra = idem() + ("If-Match" to "\"9\""))) }, server = { IdentityEnv.server }),
        ContractCase("createChecklistTemplateRevision",
            201, { val w = w(); ContractRequest("POST", "/v1/operator/checklist-templates/${published(w)}/revisions", null, w.env.headers(w.authorToken, extra = idem() + ("If-Match" to "\"2\"")), null) },
            409, { val w = w(); ContractRequest("POST", "/v1/operator/checklist-templates/${create(w)}/revisions", null, w.env.headers(w.authorToken, extra = idem() + ("If-Match" to "\"1\"")), null) }, server = { IdentityEnv.server }),
        ContractCase("publishChecklistTemplateRevision",
            200, { val w = w(); ContractRequest("POST", "/v1/operator/checklist-templates/${create(w)}/publish", null, w.env.headers(w.reviewerToken, extra = idem() + ("If-Match" to "\"1\"")), null) },
            403, { val w = w(); ContractRequest("POST", "/v1/operator/checklist-templates/${create(w)}/publish", null, w.env.headers(w.authorToken, extra = idem() + ("If-Match" to "\"1\"")), null) }, server = { IdentityEnv.server }),
        ContractCase("putChecklistStepExampleImage",
            200, { val w = w(); ContractRequest("PUT", "/v1/operator/checklist-templates/${create(w)}/steps/BRAKES/example-image", IMAGE, w.env.headers(w.authorToken, extra = idem() + ("If-Match" to "\"1\""))) },
            415, { val w = w(); ContractRequest("PUT", "/v1/operator/checklist-templates/${create(w)}/steps/BRAKES/example-image", """{"content_type":"image/gif","data_base64":"AAAA"}""", w.env.headers(w.authorToken, extra = idem() + ("If-Match" to "\"1\""))) }, server = { IdentityEnv.server }),
        ContractCase("getChecklistStepExampleImage",
            200, { val w = w(); val id = create(w); IdentityEnv.put("/v1/operator/checklist-templates/$id/steps/BRAKES/example-image", IMAGE, w.authorToken, "\"1\""); ContractRequest("GET", "/v1/operator/checklist-templates/$id/steps/BRAKES/example-image", headers = w.env.headers(w.dispatcherToken)) },
            404, { val w = w(); ContractRequest("GET", "/v1/operator/checklist-templates/${create(w)}/steps/REMARK/example-image", headers = w.env.headers(w.dispatcherToken)) }, server = { IdentityEnv.server }),
    )
}
