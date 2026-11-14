package de.taktovia.bookingdispatch.operator

import de.taktovia.booking.ConfirmFixture
import de.taktovia.bookingdispatch.OfferObjectsFixture
import de.taktovia.bookingdispatch.RideOfferFixture
import de.taktovia.bookingdispatch.api.AssistanceCode
import de.taktovia.bookingdispatch.api.BookingDetail
import de.taktovia.bookingdispatch.api.HoldRideOffer
import de.taktovia.bookingdispatch.api.OperatorBookingSearch
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import java.util.UUID

/**
 * Testaufbau der Leitstellen-Pakete WP-M3-37, -39 und -42 (eigene Klasse des Clusters wave14-c5, kein Duplikat von [ConfirmFixture]): baut auf ihm auf und ergänzt Mitgliedschaften mit
 * Dienstscope (echte Zeilen, damit ein Rechteentzug im Test wirklich in der Datenbank geschieht), Betriebskontexte mit Mitgliedschaft und Buchungen mit Unterstützungsmerkmalen.
 */
class OperatorBookingFixture : AutoCloseable {
    val cf = ConfirmFixture()
    val fx: OfferObjectsFixture get() = cf.fx
    val search: OperatorBookingSearch = OperatorBookingSearch.create(fx.base.ctxTx)

    /** Betriebskonto mit echter Mitgliedschaft (Rolle [role], Scope [services]); [membershipId] erlaubt den späteren Entzug. */
    class Operator(val ctx: RequestContext, val membershipId: UUID, val subject: String)

    fun operator(tenant: TenantId, services: Set<UUID>, role: String = "dispatcher", scopes: Set<String> = setOf("rides:read", "rides:write", "operator:read", "operator:write", "dispatch:write")): Operator {
        val subject = "betrieb-" + UUID.randomUUID(); val membership = UUID.randomUUID()
        fx.adminUpdate("INSERT INTO taktovia.membership(tenant_id,id,issuer,subject,role_code,service_scope) VALUES (?,?,?,?,?,CAST(? AS uuid[]))", tenant.value, membership, ISSUER, subject, role, services.joinToString(",", "{", "}"))
        val ctx = RequestContext(Actor(ActorType.HUMAN, ISSUER, subject, "sid-$subject", true), tenant, setOf(role), scopes, services, UUID.randomUUID(), membershipIds = setOf(membership))
        return Operator(ctx, membership, subject)
    }

    /** Entzieht den Dienstscope der Mitgliedschaft (committet sofort, wie ein Administrator). */
    fun revokeScope(op: Operator) { fx.adminUpdate("UPDATE taktovia.membership SET service_scope = CAST('{}' AS uuid[]) WHERE id = ?", op.membershipId) }

    /** Gebuchte Fahrt eines Fahrgasts mit Unterstützungsmerkmalen (Angebot, Hold, Bestätigung). */
    fun bookedWithAssistance(w: RideOfferFixture.World, run: OfferObjectsFixture.Run, passenger: ConfirmFixture.Passenger = cf.passenger(w), codes: Set<AssistanceCode> = setOf(AssistanceCode.BOARDING_HELP)): BookingDetail {
        val cmd = fx.base.command(w, "2026-11-02T10:00:00+01:00", passengers = 2).copy(assistanceCodes = codes)
        val offer = fx.requests(fx.assigning(run)).request(passenger.ctx, cmd).offers.single().offer!!
        val gross = offer.quote.payAtPlatformMinor
        val h = fx.offers.hold(passenger.ctx, offer.id, HoldRideOffer(offer.revision, "hold-" + UUID.randomUUID(), gross, "EUR"))
        val booking = cf.bookings.confirm(passenger.ctx, de.taktovia.bookingdispatch.api.ConfirmBooking(h.hold.id, h.offerRevision, gross, "EUR")).booking
        // Die Bestätigung trägt die Unterstützungsmerkmale der Anfrage (noch) nicht in die Fahrgastzeilen (M2); der Testaufbau legt sie wie die Ressourcenvektor-Tests als weitere Fahrgastzeile an
        fx.adminUpdate("INSERT INTO taktovia.passenger_trip(tenant_id, id, booking_id, seq, place_kind, assistance_codes) VALUES (?, ?, ?, 99, 'SEAT', CAST(? AS text[]))",
            w.tenant.value, UUID.randomUUID(), booking.id, codes.map { it.name }.sorted().joinToString(",", "{", "}"))
        return booking
    }

    override fun close() = cf.close()

    companion object { const val ISSUER = "https://idp.test/realms/booking" }
}
