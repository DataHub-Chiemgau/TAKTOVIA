package de.taktovia.app.booking

import de.taktovia.app.identity.IdentityEnv
import de.taktovia.app.probe.Resp
import java.util.UUID

/**
 * Testhilfen für Buchungsbestätigung, Storno und Historie über die echte Anwendung (WP-M2-23 bis -27, eigene Klasse des Clusters wave10-c1). Ein Fahrgast hat hier ein Konto
 * (`passenger_private`, Bindung über Aussteller und Subject des Tokens); die Planbindung des Angebots übernimmt [OfferHttpSupport.bind] (siehe dessen Vorbehalt).
 */
internal class BookingHttpSupport(val world: RideOfferHttpWorld = RideOfferHttpWorld()) {
    val offers = OfferHttpSupport(world)
    val env = world.w.env
    val tenant = world.w.tenant

    class Account(val token: String, val subject: UUID)

    fun newAccount(): Account {
        val subject = UUID.randomUUID(); env.grant(tenant, subject, "passenger")
        val id = UUID.randomUUID()
        env.exec("INSERT INTO taktovia.passenger_private(tenant_id, id, subject_token, retention_until, idp_issuer, idp_subject, status) VALUES (?, ?, ?, now() + interval '5 years', ?, ?, 'ACTIVE')",
            tenant.value, id, "tok-$id", IdentityEnv.idp.issuer, subject.toString())
        return Account(env.token(subject), subject)
    }

    /** Betriebspersonal mit Rolle [role] und Dienstscope [services] (leer = kein Dienstzugriff). */
    fun operator(services: List<UUID>, role: String = "dispatcher"): String { val subject = UUID.randomUUID(); env.grant(tenant, subject, role, services); return env.token(subject) }

    /** Gehaltenes Angebot: Token, Angebots-ID, Hold-ID, ETag der Hold-Antwort und Betrag. */
    class Held(val account: Account, val offerId: String, val holdId: String, val etag: String, val grossMinor: Long, val run: UUID)

    fun held(account: Account = newAccount(), days: Long = 3, passengers: Int = 2, seats: Int = 4): Held {
        val issued = offers.issue(account.token, days, passengers); val run = offers.bind(issued, seats)
        val gross = issued.offer["gross"]["amount_minor"].asLong()
        val h = offers.hold(issued.id, account.token, issued.offer["revision"].asString(), gross)
        check(h.status == 201) { "Hold fehlgeschlagen: ${h.status} ${h.text}" }
        return Held(account, issued.id, h.json["id"].asString(), h.header("ETag")!!.trim('"'), gross, run)
    }

    fun confirm(h: Held, token: String = h.account.token, ifMatch: String? = h.etag, gross: Long = h.grossMinor, key: String = "confirm-" + UUID.randomUUID(), holdId: String = h.holdId): Resp =
        env.server.send("POST", "/v1/bookings", """{"hold_id":"$holdId","max_gross":{"amount_minor":$gross,"currency":"EUR","exponent":2}}""",
            env.headers(token, extra = mapOf("Idempotency-Key" to key) + (if (ifMatch != null) mapOf("If-Match" to "\"$ifMatch\"") else emptyMap())))

    fun get(id: String, token: String) = env.get("/v1/bookings/$id", token)
    fun list(token: String, query: String = "") = env.get("/v1/bookings$query", token)

    fun cancel(id: String, token: String, reason: String = "PASSENGER_REQUEST", ifMatch: String?, key: String = "cancel-" + UUID.randomUUID()): Resp =
        env.server.send("POST", "/v1/bookings/$id/cancel", """{"reason":"$reason"}""",
            env.headers(token, extra = mapOf("Idempotency-Key" to key) + (if (ifMatch != null) mapOf("If-Match" to "\"$ifMatch\"") else emptyMap())))

    fun count(sql: String, vararg args: Any?) = world.w.count(sql, *args)
}
