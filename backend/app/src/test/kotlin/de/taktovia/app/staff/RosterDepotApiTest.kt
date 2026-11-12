package de.taktovia.app.staff

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.UUID

/** WP-M4-16 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer): Depotrecht an allen Pfaden unter `/v1/rosters`, nicht sichtbare Dienstpläne 404, fehlender Scope 403, `tenant_id` im Payload wirkungslos. */
class RosterDepotApiTest {
    private val w by lazy { ShiftHttpWorld() }

    private fun send(method: String, path: String, body: String? = null, token: String = w.plannerToken, ifMatch: String? = null) =
        w.g.env.server.send(method, path, body, w.g.env.headers(token, extra = (if (method == "GET") emptyMap() else w.g.idem()) + (if (ifMatch != null) mapOf("If-Match" to "\"$ifMatch\"") else emptyMap())))

    private fun otherDepot() = w.g.id("/v1/depots", """{"region_id":"${w.g.id("/v1/regions", """{"name":"Region 2"}""")}","name":"Depot Süd"}""")

    @Test
    fun planer_fremdes_depot_lesen_aendern_veroeffentlichen_abgewiesen_ueber_http() {
        val depotB = otherDepot()
        val rosterB = send("POST", "/v1/rosters", """{"depot_id":"$depotB","week_start":"2026-11-30"}""")
        assertEquals(404, rosterB.status, "Planer von Depot A darf in Depot B nichts anlegen")
        // Dienstplan in B durch einen Planer mit Depot-B-Recht
        val planB = UUID.randomUUID(); w.g.env.grant(w.g.tenant, planB, "planner"); w.grantDepot(planB, depotB)
        val tokenB = w.g.env.token(planB)
        val created = send("POST", "/v1/rosters", """{"depot_id":"$depotB","week_start":"2026-11-30"}""", token = tokenB); assertEquals(201, created.status, created.text)
        val id = created.json["id"].asString()
        val shiftB = send("POST", "/v1/rosters/shifts", w.body(depotId = depotB, roster = id), token = tokenB).json["id"].asString()
        // Planer A: 404 überall (kein 403, kein Hinweis auf Existenz), nichts geändert
        assertEquals(404, send("GET", "/v1/rosters/$id").status)
        assertEquals(404, send("GET", "/v1/rosters/shifts?roster_id=$id").status)
        assertEquals(404, send("GET", "/v1/rosters/shifts/$shiftB?roster_id=$id").status)
        assertEquals(404, send("PUT", "/v1/rosters/shifts/$shiftB", w.body(depotId = depotB, roster = id, need = 9), ifMatch = "1").status)
        assertEquals(404, send("POST", "/v1/rosters/shifts", w.body(depotId = depotB, roster = id)).status)
        assertEquals(404, send("POST", "/v1/rosters/$id/draft", null, ifMatch = "1").status)
        assertFalse(send("GET", "/v1/rosters").text.contains(id)); assertFalse(send("GET", "/v1/rosters?depot_id=$depotB").text.contains(id))
        assertEquals(2, send("GET", "/v1/rosters/shifts/$shiftB?roster_id=$id", token = tokenB).json["staffing_need"].asInt())
        // fehlender Scope: 403, auch auf eigene Dienstpläne
        assertEquals(403, send("GET", "/v1/rosters/${w.roster}", token = w.dispatcherToken).status)
    }

    @Test
    fun manipulierte_tenant_id_im_payload_wirkungslos_ueber_http() {
        val other = ShiftHttpWorld()
        // eine fremde `tenant_id` im Body wird nie übernommen: der Aufruf wird gleichartig wie ein fremdes Objekt abgewiesen (404, Sicherheitsereignis im eigenen Mandanten), nichts angelegt
        val before = w.g.env.events(w.g.tenant, "security.tenant.mismatch")
        val r = send("POST", "/v1/rosters", """{"depot_id":"${w.depot}","week_start":"2026-12-07","tenant_id":"${other.g.tenant}"}""")
        assertEquals(404, r.status, r.text); assertEquals(before + 1, w.g.env.events(w.g.tenant, "security.tenant.mismatch"))
        assertEquals(0, w.g.count("SELECT count(*) FROM taktovia.roster_revision WHERE tenant_id=? AND week_start='2026-12-07'", w.g.tenant.value))
        assertEquals(404, send("POST", "/v1/rosters", """{"depot_id":"${other.depot}","week_start":"2026-12-07"}""").status)
        assertEquals(0, w.g.count("SELECT count(*) FROM taktovia.roster_revision WHERE tenant_id=?", other.g.tenant.value) - 1, "Mandant der anderen Welt unverändert (nur ihr eigener Dienstplan)")
        val shift = send("POST", "/v1/rosters/shifts", w.body().replace("\"function\"", "\"tenant_id\":\"${other.g.tenant}\",\"function\""))
        assertEquals(404, shift.status, shift.text)
    }

    @Test
    fun depotuebergreifendes_recht_und_entzug_ueber_http() {
        val depotB = otherDepot()
        val all = UUID.randomUUID(); w.g.env.grant(w.g.tenant, all, "planner"); w.grantDepot(all, kind = "ALL_DEPOTS"); val token = w.g.env.token(all)
        val created = send("POST", "/v1/rosters", """{"depot_id":"$depotB","week_start":"2026-11-30"}""", token = token); assertEquals(201, created.status, created.text)
        assertEquals(201, send("POST", "/v1/rosters/shifts", w.body(depotId = depotB, roster = created.json["id"].asString()), token = token).status)
        assertEquals(2, w.g.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='staff.roster.cross_depot_edit'", w.g.tenant.value))
        // Entzug: der nächste Aufruf scheitert
        w.g.env.exec("UPDATE taktovia.roster_depot_grant SET revoked_at=now(), revoked_by='user:test-admin' WHERE tenant_id=? AND subject=?", w.g.tenant.value, all.toString().lowercase())
        assertEquals(404, send("GET", "/v1/rosters/${created.json["id"].asString()}", token = token).status)
    }
}
