package de.taktovia.app.contract

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.app.staff.ShiftHttpWorld
import java.util.UUID

/** Vertragsfälle der Veröffentlichung, Eigensicht, Revisionen und Historie des Dienstplans (WP-M4-14, WP-M4-15, WP-M4-20): je Operation ein positiver Fall und ein Fehlerfall gegen die echte Anwendung. */
internal object RosterPublishContractCases {
    private fun hg(w: ShiftHttpWorld, token: String = w.hrToken, extra: Map<String, String> = emptyMap()) = w.g.env.headers(token, extra = extra)
    private fun etag(w: ShiftHttpWorld) = w.get("/v1/rosters/${w.roster}", w.hrToken).json["revision"].asLong()
    private fun publishBody() = """{"effective_from":"2026-12-01T00:00:00Z","reason":"Freigabe Woche 49","impact_report_id":"${UUID.randomUUID()}"}"""

    val cases: List<ContractCase> = listOf(
        ContractCase("publishRoster",
            200, { val w = ShiftHttpWorld(); w.post(w.body()); ContractRequest("POST", "/v1/rosters/${w.roster}/publish", publishBody(), hg(w, extra = w.g.idem() + mapOf("If-Match" to "\"${etag(w)}\""))) },
            428, { val w = ShiftHttpWorld(); w.post(w.body()); ContractRequest("POST", "/v1/rosters/${w.roster}/publish", publishBody(), hg(w, extra = w.g.idem())) }, server = { IdentityEnv.server }),
        ContractCase("listOwnRosterShifts",
            200, { val w = ShiftHttpWorld(); ContractRequest("GET", "/v1/rosters/own-shifts?from=2026-11-30&to=2026-12-06", headers = hg(w, w.plannerToken)) },
            422, { val w = ShiftHttpWorld(); ContractRequest("GET", "/v1/rosters/own-shifts?from=2026-12-06&to=2026-11-30", headers = hg(w, w.plannerToken)) }, server = { IdentityEnv.server }),
        ContractCase("listRosterRevisions",
            200, { val w = ShiftHttpWorld(); ContractRequest("GET", "/v1/rosters/${w.roster}/revisions", headers = hg(w)) },
            404, { val w = ShiftHttpWorld(); ContractRequest("GET", "/v1/rosters/${UUID.randomUUID()}/revisions", headers = hg(w)) }, server = { IdentityEnv.server }),
        ContractCase("getRosterRevision",
            200, { val w = ShiftHttpWorld(); w.post(w.body()); w.publishDraft(); ContractRequest("GET", "/v1/rosters/${w.roster}/revisions/1", headers = hg(w)) },
            404, { val w = ShiftHttpWorld(); ContractRequest("GET", "/v1/rosters/${w.roster}/revisions/99", headers = hg(w)) }, server = { IdentityEnv.server }),
        ContractCase("listRosterShiftHistory",
            200, { val w = ShiftHttpWorld(); val shift = w.post(w.body()).json["id"].asString(); ContractRequest("GET", "/v1/rosters/${w.roster}/shifts/$shift/history", headers = hg(w)) },
            404, { val w = ShiftHttpWorld(); ContractRequest("GET", "/v1/rosters/${w.roster}/shifts/${UUID.randomUUID()}/history", headers = hg(w)) }, server = { IdentityEnv.server }),
        ContractCase("listOwnAbsences",
            200, { val w = ShiftHttpWorld(); ContractRequest("GET", "/v1/absences/own", headers = hg(w, w.plannerToken)) },
            403, { val w = ShiftHttpWorld(); val admin = UUID.randomUUID(); w.g.env.grant(w.g.tenant, admin, "tenant_admin"); ContractRequest("GET", "/v1/absences/own", headers = hg(w, w.g.env.token(admin))) }, server = { IdentityEnv.server }),
    )
}
