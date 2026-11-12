package de.taktovia.app.booking

import tools.jackson.databind.JsonNode
import de.taktovia.app.probe.Resp
import java.time.Instant
import java.util.UUID

/**
 * Testhilfen für Hold, Freigabe und Angebotsabruf über die echte Anwendung (WP-M2-21/-22, eigene Klasse des Clusters). Die Produktions-Verdrahtung bindet Angebote erst mit der Einfügeheuristik
 * (WP-M2-17 bis -19) an eine Planrevision; für den Hold-Nachweis über HTTP wird die Bindung deshalb im Testaufbau gesetzt: ein Umlauf mit Fahrzeug, Schicht, Freigabe und Poolzuordnung entsteht
 * per SQL, und die Bindung an das Angebot wird mit abgeschaltetem Inhaltswächter (`ride_offer_guard`) nachgetragen. Nichts davon ist ein Ersatz für den Nachweis der Verdrahtung selbst.
 */
internal class OfferHttpSupport(val world: RideOfferHttpWorld) {
    private val env = world.w.env
    val tenant = world.w.tenant

    /** Stellt ein Angebot für einen neuen Fahrgast aus; liefert Token, Antwort und Angebots-ID. */
    class Issued(val token: String, val response: Resp, val offer: JsonNode) { val id: String get() = offer["id"].asString() }

    fun newPassenger(): String {
        val subject = UUID.randomUUID(); env.grant(tenant, subject, "passenger"); return env.token(subject)
    }

    fun issue(token: String = newPassenger(), days: Long = 3, passengers: Int = 2): Issued {
        val r = world.request(world.body(world.later(days), passengers = passengers), token)
        check(r.status == 200 && r.json["offers"].size() == 1) { "Angebot nicht ausgestellt: ${r.status} ${r.text}" }
        return Issued(token, r, r.json["offers"][0])
    }

    /** Bindet das Angebot an einen frischen ACTIVE-Umlauf (Kapazität [seats] Sitze, ein Rollstuhlplatz), Revision 1, Abschnitte `[0, 2)`. */
    fun bind(issued: Issued, seats: Int = 4): UUID {
        val t = tenant.value; val vehicle = UUID.randomUUID(); val shift = UUID.randomUUID(); val run = UUID.randomUUID(); val svc = UUID.fromString(world.service)
        val from = Instant.parse(issued.offer["pickup_window"]["earliest_at"].asString()).minusSeconds(3600)
        val to = Instant.parse(issued.offer["arrival_window"]["latest_at"].asString()).plusSeconds(3600)
        env.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,active_block,safety_revision) VALUES (?,?,'fed-org-http',CAST(? AS jsonb),false,1)", t, vehicle,
            """{"schema_version":"1.0","seats":$seats,"wheelchair":1,"equipment":["RAMP"]}""")
        env.exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,?::timestamptz,?::timestamptz,'Europe/Berlin',1,'PUBLISHED')", t, shift, UUID.randomUUID(), vehicle, from.toString(), to.toString())
        env.exec("INSERT INTO taktovia.resource_release(tenant_id,id,vehicle_id,shift_id,safety_revision,inspection_ref,qualification_revision,valid_until) VALUES (?,?,?,?,1,?,1,?::timestamptz)", t, UUID.randomUUID(), vehicle, shift, UUID.randomUUID(), to.toString())
        env.exec("INSERT INTO taktovia.vehicle_pool_assignment(tenant_id,id,service_id,vehicle_id,valid_from,created_by) VALUES (?,?,?,?,'2026-01-01T00:00:00Z','system:test')", t, UUID.randomUUID(), svc, vehicle)
        env.exec("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (?,?,?,?,1,'ACTIVE')", t, run, vehicle, shift)
        updateOffer("run_id = ?, plan_revision = 1, interval_from = 0, interval_to = 2", issued.id, run)
        return run
    }

    /** Setzt das Angebot rückwirkend ab (Ablauf in der Vergangenheit); nur im Testaufbau, mit abgeschaltetem Inhaltswächter. */
    fun expire(issued: Issued) = updateOffer("created_at = now() - interval '10 minutes', expires_at = now() - interval '5 minutes'", issued.id)

    private fun updateOffer(set: String, offerId: String, vararg args: Any?) {
        env.exec("ALTER TABLE taktovia.ride_offer DISABLE TRIGGER ride_offer_guard")
        try { env.exec("UPDATE taktovia.ride_offer SET $set WHERE id = ?::uuid", *args, offerId) }
        finally { env.exec("ALTER TABLE taktovia.ride_offer ENABLE TRIGGER ride_offer_guard") }
    }

    fun get(issued: Issued, token: String = issued.token) = env.get("/v1/ride-offers/${issued.id}", token)

    fun hold(id: String, token: String, ifMatch: String?, gross: Long = 900, key: String = "hold-" + UUID.randomUUID(), currency: String = "EUR"): Resp =
        env.server.send("POST", "/v1/ride-offers/$id/hold", """{"max_gross":{"amount_minor":$gross,"currency":"$currency","exponent":2}}""",
            env.headers(token, extra = mapOf("Idempotency-Key" to key) + (if (ifMatch != null) mapOf("If-Match" to "\"$ifMatch\"") else emptyMap())))

    fun release(id: String, token: String, ifMatch: String?, key: String = "rel-" + UUID.randomUUID()): Resp =
        env.server.send("POST", "/v1/ride-offers/$id/hold/release", null, env.headers(token, extra = mapOf("Idempotency-Key" to key) + (if (ifMatch != null) mapOf("If-Match" to "\"$ifMatch\"") else emptyMap())))
}
