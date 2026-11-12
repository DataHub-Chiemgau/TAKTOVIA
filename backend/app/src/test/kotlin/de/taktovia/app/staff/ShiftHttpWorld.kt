package de.taktovia.app.staff

import de.taktovia.app.geo.GeoWorld
import java.util.UUID

/** Testwelt der Schichten und Dienstpläne über die echte Anwendung (WP-M4-03/-04): Mandant mit Betreiber, Depot, Dienstplan, Planer und Personalrolle. Eigene Klasse. */
internal class ShiftHttpWorld {
    val g = GeoWorld("shift-" + UUID.randomUUID())
    val planner = UUID.randomUUID(); val hr = UUID.randomUUID(); val dispatcher = UUID.randomUUID()
    init { g.env.grant(g.tenant, planner, "planner"); g.env.grant(g.tenant, hr, "staff_planning"); g.env.grant(g.tenant, dispatcher, "dispatcher") }
    val plannerToken = g.env.token(planner); val hrToken = g.env.token(hr); val dispatcherToken = g.env.token(dispatcher)
    val operator: String = g.operator
    val depot: String = g.id("/v1/depots", """{"region_id":"${g.id("/v1/regions", """{"name":"Region"}""")}","name":"Depot Nord"}""")
    /** Depotrecht (WP-M4-16) für Planer und Personalrolle; die Verwaltungsschnittstelle ist der Port `RosterDepotAccess`, hier per Administration vergeben. */
    fun grantDepot(subject: UUID, depotId: String = depot, kind: String = "DEPOT") =
        g.env.exec("INSERT INTO taktovia.roster_depot_grant(tenant_id,id,subject,scope_kind,depot_id,granted_by) VALUES (?,?,?,?,CAST(? AS uuid),'user:test-admin')", g.tenant.value, UUID.randomUUID(), subject.toString().lowercase(), kind, if (kind == "DEPOT") depotId else null)
    init { grantDepot(planner); grantDepot(hr) }

    /** Dienstplan des Depots für die Woche ab Montag 2026-11-30 (WP-M4-04); die Schichten der Tests liegen am 1. und 2. Dezember 2026. */
    val roster: String = g.env.server.send("POST", "/v1/rosters", """{"depot_id":"$depot","week_start":"2026-11-30"}""", g.env.headers(plannerToken, extra = g.idem())).also { check(it.status == 201) { it.text } }.json["id"].asString()

    fun body(start: String = "2026-12-01T22:00:00", end: String = "2026-12-02T06:00:00", extra: String = "", tz: String = "Europe/Berlin", depotId: String = depot, roster: String = this.roster, need: Int = 2) =
        """{"roster_id":"$roster","starts_local":"$start","ends_local":"$end","timezone":"$tz","operator_id":"$operator","depot_id":"$depotId","function":"Fahrdienst","required_qualification":"PERSONENBEFOERDERUNG","staffing_need":$need$extra}"""

    fun post(body: String, token: String = plannerToken) = g.env.server.send("POST", "/v1/rosters/shifts", body, g.env.headers(token, extra = g.idem()))
    fun get(path: String, token: String = plannerToken) = g.env.server.send("GET", path, null, g.env.headers(token))
    fun put(id: String, body: String, ifMatch: String?, token: String = plannerToken) =
        g.env.server.send("PUT", "/v1/rosters/shifts/$id", body, g.env.headers(token, extra = g.idem() + (if (ifMatch != null) mapOf("If-Match" to "\"$ifMatch\"") else emptyMap())))

    /** Stellt die Veröffentlichung des Entwurfs nach (Administration; der Befehl folgt mit WP-M4-14). */
    fun publishDraft(rosterId: String = roster) {
        g.env.exec("UPDATE taktovia.staff_shift SET state = 'PUBLISHED' WHERE tenant_id = ? AND roster_id = ?::uuid AND state = 'DRAFT'", g.tenant.value, rosterId)
        g.env.exec("UPDATE taktovia.roster_revision SET state = 'PUBLISHED', published_at = now(), published_by = 'user:test' WHERE tenant_id = ? AND roster_id = ?::uuid AND state = 'DRAFT'", g.tenant.value, rosterId)
    }
}
