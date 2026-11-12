package de.taktovia.booking

import de.taktovia.bookingdispatch.OfferObjectsFixture
import de.taktovia.bookingdispatch.RideOfferFixture
import de.taktovia.bookingdispatch.api.BookingDetail
import de.taktovia.bookingdispatch.api.Bookings
import de.taktovia.bookingdispatch.api.CandidateGate
import de.taktovia.bookingdispatch.api.ConfirmBooking
import de.taktovia.bookingdispatch.api.ConfirmedBooking
import de.taktovia.bookingdispatch.api.HoldRideOffer
import de.taktovia.bookingdispatch.api.IssuedOffer
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import java.util.UUID

/**
 * Testaufbau für Bestätigung, Idempotenz, Historie und Storno (WP-M2-23 bis -27, eigene Klasse des Clusters wave10-c1) gegen die echte Datenbank unter der Runtime-Rolle. Baut auf
 * [OfferObjectsFixture] (Mandant, Dienst, Tarif, Umläufe, Uhr) auf und ergänzt Fahrgastkonten, Betriebspersonal, ausgestellte und gehaltene Angebote sowie den Buchungsdienst.
 */
class ConfirmFixture : AutoCloseable {
    val fx = OfferObjectsFixture()
    val bookings: Bookings = Bookings.create(fx.base.ctxTx, fx.base.outbox, fx.quotes, fx.clock, fx.base.config.effective)
    val runs: de.taktovia.bookingdispatch.api.RunPlans = de.taktovia.bookingdispatch.api.BookingdispatchApi.create(fx.base.ctxTx, fx.clock, fx.base.outbox).runs

    /** Mandant mit Dienst und Tarif; [deadlineMin] und [reasons] sind die veröffentlichten Stornoparameter (ohne Angabe gelten die Planungsdefaults). */
    fun world(deadlineMin: Int? = null, reasons: List<String>? = null): RideOfferFixture.World {
        val params = linkedMapOf<String, Any?>("min_booking_lead_min" to 30, "max_booking_horizon_days" to 14, "pickup_window_min" to 15, "arrival_grace_min" to 5, "stop_walk_max_m" to 400)
        deadlineMin?.let { params["cancellation_deadline_min"] = it }
        reasons?.let { params["cancellation_reasons"] = mapOf("reasons" to it) }
        val w = fx.base.world(params); fx.publishTariff(w, 450, 50)
        return w
    }

    /** Fahrgast mit Konto (`passenger_private`, Bindung an Aussteller und Subject des Tokens). */
    class Passenger(val ctx: RequestContext, val accountId: UUID)

    fun passenger(w: RideOfferFixture.World): Passenger {
        val ctx = fx.passenger(w.tenant)
        val id = UUID.randomUUID()
        fx.adminUpdate(
            "INSERT INTO taktovia.passenger_private(tenant_id, id, subject_token, retention_until, idp_issuer, idp_subject, status) VALUES (?, ?, ?, now() + interval '5 years', ?, ?, 'ACTIVE')",
            w.tenant.value, id, "tok-$id", ctx.actor.issuer, ctx.actor.subject,
        )
        return Passenger(ctx, id)
    }

    /** Betriebspersonal mit Dienstscope [services] (Rolle `dispatcher`, `rides:read`). */
    fun operator(tenant: TenantId, services: Set<UUID>, roles: Set<String> = setOf("dispatcher")): RequestContext =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/booking", "betrieb-" + UUID.randomUUID(), "sid-x", true), tenant, roles, setOf("rides:read", "rides:write"), services, UUID.randomUUID())

    /** Ein Angebot mit Hold: [offerRevision] ist die ETag-Revision der Hold-Antwort (`If-Match` des Confirm). */
    class Held(val passenger: Passenger, val offer: IssuedOffer, val holdId: UUID, val offerRevision: Long, val grossMinor: Long)

    fun held(
        w: RideOfferFixture.World, run: OfferObjectsFixture.Run, passenger: Passenger = passenger(w), passengers: Int = 2, wheelchair: Int = 0, from: Int = 0, to: Int = 2, planRevision: Long = 1,
        offers: de.taktovia.bookingdispatch.api.RideOffers = fx.offers,
    ): Held {
        val offer = fx.issue(w, passenger.ctx, fx.assigning(run, planRevision, from, to), passengers, wheelchair)
        val gross = offer.quote.payAtPlatformMinor
        val h = offers.hold(passenger.ctx, offer.id, HoldRideOffer(offer.revision, "hold-" + UUID.randomUUID(), gross, "EUR"))
        return Held(passenger, offer, h.hold.id, h.offerRevision, gross)
    }

    fun confirm(h: Held, gross: Long = h.grossMinor, ctx: RequestContext = h.passenger.ctx, ifMatch: Long = h.offerRevision): ConfirmedBooking =
        bookings.confirm(ctx, ConfirmBooking(h.holdId, ifMatch, gross, "EUR"))

    fun booked(w: RideOfferFixture.World, run: OfferObjectsFixture.Run, passenger: Passenger = passenger(w), passengers: Int = 2, wheelchair: Int = 0, planRevision: Long = 1): Pair<Held, BookingDetail> {
        val h = held(w, run, passenger, passengers, wheelchair, planRevision = planRevision); return h to confirm(h).booking
    }

    fun count(sql: String, vararg args: Any?) = fx.count(sql, *args)
    fun stringOf(sql: String, vararg args: Any?) = fx.base.stringOf(sql, *args)

    override fun close() = fx.close()
}
