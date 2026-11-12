package de.taktovia.bookingdispatch

import de.taktovia.bookingdispatch.api.CandidateGate
import de.taktovia.bookingdispatch.api.CandidateGateResult
import de.taktovia.bookingdispatch.api.DirectTravelTime
import de.taktovia.bookingdispatch.api.HoldExpiry
import de.taktovia.bookingdispatch.api.IssuedOffer
import de.taktovia.bookingdispatch.api.OfferAssignment
import de.taktovia.bookingdispatch.api.RideOfferCommand
import de.taktovia.bookingdispatch.api.RideOfferRequests
import de.taktovia.bookingdispatch.api.RideOffers
import de.taktovia.farepayment.api.CreateTariffRevision
import de.taktovia.farepayment.api.FareQuotes
import de.taktovia.farepayment.api.TariffRuleInput
import de.taktovia.farepayment.api.Tariffs
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** Steuerbare Uhr (Zeitgrenzen und Ablauf in Tests); eine Instanz je Fixture, [set] gilt für alle Dienste, die sie benutzen. */
class MutableClock(@Volatile var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
    fun set(i: Instant) { now = i }
}

/**
 * Testaufbau für Angebotsobjekt, Hold und Freigabe (WP-M2-21/-22) gegen die echte Datenbank unter der Runtime-Rolle. Eigene Klasse (Cluster wave9-c1), baut auf [RideOfferFixture]
 * (Mandant, Dienst, Gebiet, Haltepunkte, Konfiguration) auf und ergänzt Tarif (FESTPREIS 450 je Person plus Gebühr 50, 7 % Steuer enthalten: 2 Personen = 950), Umläufe mit Fahrzeug,
 * Schicht, Freigabe und Poolzuordnung sowie eine steuerbare Uhr (Jetzt = Montag, 2. November 2026, 07:00Z).
 */
class OfferObjectsFixture : AutoCloseable {
    val base = RideOfferFixture()
    val clock = MutableClock(base.now)
    val tariffs: Tariffs = Tariffs.create(base.ctxTx, JdbcAuditPort(), base.outbox)
    val quotes: FareQuotes = FareQuotes.create(base.ctxTx, tariffs, clock)
    val offers: RideOffers = RideOffers.create(base.ctxTx, base.outbox, quotes, base.config.effective, clock)
    val expiry: HoldExpiry = HoldExpiry.create(base.tx, base.outbox, clock)

    /** Angebotsdienst mit abweichender Hold-Dauer (Ablauftests mit Millisekunden). */
    fun offersWithTtl(ttl: Duration): RideOffers = RideOffers.create(base.ctxTx, base.outbox, quotes, base.config.effective, clock, ttl)

    /** Mandant mit Dienst, Gebiet, Haltepunkten und veröffentlichtem Tarif (gültig 2026-01-01 bis 2027-01-01). */
    fun world(withTariff: Boolean = true): RideOfferFixture.World {
        val w = base.world()
        if (withTariff) publishTariff(w, 450, 50)
        return w
    }

    fun publishTariff(w: RideOfferFixture.World, amount: Long, fee: Long, from: String = "2026-01-01T00:00:00Z", to: String? = "2027-01-01T00:00:00Z") {
        val scopes = setOf("tariffs:read", "tariffs:write", "tariffs:publish", "services:read", "services:write")
        val creator = base.context(w.tenant, scopes = scopes); val reviewer = base.context(w.tenant, scopes = scopes)
        val rule = TariffRuleInput(ruleType = "FESTPREIS", amountMinor = amount, bookingFeeMinor = fee, taxRate = "0.07", taxRuleRevision = "DE-2026-1")
        val draft = tariffs.create(creator, CreateTariffRevision(w.serviceId, "STANDARD", Instant.parse(from), to?.let(Instant::parse), "EUR", "Testtarif", listOf(rule)))
        val reviewed = tariffs.review(reviewer, draft.id, draft.revision)
        tariffs.publish(reviewer, draft.id, reviewed.revision)
    }

    /** Umlauf (Zustand `ACTIVE`, Revision 1) mit eigenem Fahrzeug [capacityJson] im Pool des Dienstes; Schicht und Freigabe decken 2026-11-02 08:00Z bis 18:00Z. */
    class Run(val runId: UUID, val vehicleId: UUID, val shiftId: UUID)

    fun run(w: RideOfferFixture.World, capacityJson: String = CAP_4_1): Run {
        val t = w.tenant.value; val vehicle = UUID.randomUUID(); val shift = UUID.randomUUID(); val run = UUID.randomUUID()
        base.adminUpdate("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,active_block,safety_revision) VALUES (?,?,'fed-org-offer',CAST(? AS jsonb),false,1)", t, vehicle, capacityJson)
        base.adminUpdate("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,'2026-11-02 08:00:00+00','2026-11-02 18:00:00+00','Europe/Berlin',1,'PUBLISHED')", t, shift, UUID.randomUUID(), vehicle)
        base.adminUpdate("INSERT INTO taktovia.resource_release(tenant_id,id,vehicle_id,shift_id,safety_revision,inspection_ref,qualification_revision,valid_until) VALUES (?,?,?,?,1,?,1,'2026-11-02 18:00:00+00')", t, UUID.randomUUID(), vehicle, shift, UUID.randomUUID())
        base.adminUpdate("INSERT INTO taktovia.vehicle_pool_assignment(tenant_id,id,service_id,vehicle_id,valid_from,created_by) VALUES (?,?,?,?,'2026-01-01T00:00:00Z','system:test')", t, UUID.randomUUID(), w.serviceId, vehicle)
        base.adminUpdate("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (?,?,?,?,1,'ACTIVE')", t, run, vehicle, shift)
        return Run(run, vehicle, shift)
    }

    /** Tor der Kandidatensuche, das jedes zulässige Angebot an [run] (Revision [revision], Abschnitte `[from, to)`) bindet. */
    fun assigning(run: Run, revision: Long = 1, from: Int = 0, to: Int = 2) =
        CandidateGate { _, _ -> CandidateGateResult.Assigned(OfferAssignment(run.runId, revision, from, to)) }

    fun requests(gate: CandidateGate = CandidateGate.PASS_THROUGH, travel: DirectTravelTime = DirectTravelTime { _, _ -> 600 }): RideOfferRequests =
        RideOfferRequests.create(base.ctxTx, base.outbox, base.geo, base.config.effective, clock, travel, gate, quotes = quotes)

    /** Stellt ein Angebot für [passenger] aus (Abfahrtswunsch 10:00 Ortszeit, gleicher Tag wie das Jetzt; Fenster 09:00Z bis 09:25Z). */
    fun issue(w: RideOfferFixture.World, passenger: RequestContext, gate: CandidateGate = CandidateGate.PASS_THROUGH, passengers: Int = 2, wheelchair: Int = 0): IssuedOffer {
        val cmd: RideOfferCommand = base.command(w, "2026-11-02T10:00:00+01:00", passengers = passengers, wheelchair = wheelchair)
        val r = requests(gate).request(passenger, cmd)
        return r.offers.single().offer ?: error("kein Angebotsobjekt: ${r.outcome}")
    }

    fun passenger(tenant: TenantId) = base.passenger(tenant)
    fun count(sql: String, vararg args: Any?) = base.count(sql, *args)
    fun adminUpdate(sql: String, vararg args: Any?) = base.adminUpdate(sql, *args)

    /** Zustand eines Holds, gelesen mit Administratorrechten (Gegenprobe unabhängig vom Code unter Test). */
    fun stateOf(holdId: UUID): String = base.stringOf("SELECT state FROM taktovia.capacity_hold WHERE id = ?", holdId)

    fun advance(d: Duration) { clock.set(clock.instant().plus(d)) }

    override fun close() = base.close()

    companion object {
        const val CAP_4_1 = """{"schema_version":"1.0","seats":4,"wheelchair":1,"equipment":["RAMP"]}"""
        fun cap(seats: Int, wheelchair: Int) = """{"schema_version":"1.0","seats":$seats,"wheelchair":$wheelchair,"equipment":["RAMP"]}"""
    }
}
