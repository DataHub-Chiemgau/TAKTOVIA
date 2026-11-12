package de.taktovia.farepayment

import de.taktovia.farepayment.api.BeginPayment
import de.taktovia.farepayment.api.FareQuotes
import de.taktovia.farepayment.api.FareRequest
import de.taktovia.farepayment.api.PaymentConfig
import de.taktovia.farepayment.api.PaymentGateway
import de.taktovia.farepayment.api.Payments
import de.taktovia.farepayment.api.QuoteOutcome
import de.taktovia.farepayment.api.QuoteRequest
import de.taktovia.farepayment.api.TariffRuleInput
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.operation.OperationConfig
import de.taktovia.platform.operation.OperationRunner
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.tenant.TenantId
import org.springframework.jdbc.core.JdbcOperations
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** Steuerbare Uhr für die Zahlungstests (eigene Klasse des Clusters WP-M2-30/-31). */
class PayClock : Clock() {
    @Volatile var now: Instant = Instant.now()
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?) = this
    override fun instant(): Instant = now
    fun advance(d: Duration) { now = now.plus(d) }
}

/** Eine Buchung der Testwelt mit ihrer Journey und Quote (Fachketten außerhalb der Zahlung sind per Administrator mit ausgeschalteten Fremdschlüsseln aufgebaut). */
class PayBooking(val id: UUID, val journey: UUID, val quote: UUID, val serviceId: UUID, val amountMinor: Long)

/**
 * Testwelt des Zahlungsautomaten (WP-M2-30): echte Datenbank unter der Runtime-Rolle, echte Quote (Tarifrevision, Engine), echte Operationen mit Lease und Fencing und ein [PaymentGateway]
 * als Gegenstelle. Die Buchung ist ein Datensatz ohne Buchungsautomat: das Zahlungsmodul schreibt sie nie, der Test prüft, dass sie unberührt bleibt.
 */
internal class PayTestWorld(val gateway: PaymentGateway, val clock: PayClock = PayClock(), config: PaymentConfig = PaymentConfig(provider = gateway.provider, operationDeadline = Duration.ofDays(1))) : AutoCloseable {
    val fx = TariffTestFixture()
    val world = fx.world()
    val tenant: TenantId = world.tenant
    private val ctxTx = ContextTransactions(fx.tx, object : ContextRevalidator { override fun revalidate(jdbc: JdbcOperations, ctx: RequestContext, lock: Boolean) {} })
    val quotes: FareQuotes = FareQuotes.create(ctxTx, fx.tariffs, clock)
    val outbox = Outbox()
    val payments: Payments = Payments.create(fx.tx, ctxTx, quotes, outbox, config, clock)
    /** Wiederholungen von Abgleichen werden erst nach einer Stunde fällig: der Test steuert sie mit [PayClock.advance]. */
    val runner = OperationRunner(fx.tx, payments.handlers(gateway), outbox, clock, OperationConfig(reconcileDelay = Duration.ofHours(1), lease = Duration.ofSeconds(60)), "worker-test")

    init {
        fx.published(fx.context(tenant), world, from = "2026-01-01T00:00:00Z", to = "2030-01-01T00:00:00Z", rules = listOf(TariffRuleInput(ruleType = "FESTPREIS", amountMinor = 950, taxRate = "0.07", taxRuleRevision = "DE-2026-1")))
    }

    /** Buchung im Zustand [bookingState] samt Journey und Quote (Preis 950). */
    fun newBooking(bookingState: String = "CONFIRMING"): PayBooking {
        val ctx = fx.context(tenant)
        val attempt = UUID.randomUUID()
        fx.tx.run(tenant) { j -> j.update("INSERT INTO taktovia.travel_attempt(tenant_id, id, created_at, channel) VALUES (taktovia.current_tenant(), ?, now(), 'WEB')", attempt) }
        val cfg = fx.tx.run(tenant) { j -> j.queryForObject("SELECT config_revision_id FROM taktovia.service WHERE id = ?", UUID::class.java, world.service)!! }
        val quote = (ctxTx.run(ctx) { j -> quotes.createIn(j, ctx, QuoteRequest(attempt, world.service, Instant.parse("2026-11-20T10:00:00Z"), FareRequest(1), clock.instant().plusSeconds(3600), cfg)) } as QuoteOutcome.Created).quote
        val journey = UUID.randomUUID(); val passenger = UUID.randomUUID(); val booking = UUID.randomUUID(); val hold = UUID.randomUUID()
        fx.admin.dataSource!!.connection.use { c ->
            c.autoCommit = false
            c.createStatement().use { it.execute("SET LOCAL session_replication_role = replica") }   // nur Testaufbau (nur diese Transaktion): Hold, Fahrtenplan und Fahrgastkonto sind nicht Gegenstand der Zahlung
            try {
                c.prepareStatement("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,retention_until) VALUES (?,?,?,now())").use { it.setObject(1, tenant.value); it.setObject(2, passenger); it.setString(3, passenger.toString()); it.executeUpdate() }
                c.prepareStatement("INSERT INTO taktovia.journey(tenant_id,id,passenger_id,quote_id,state,created_at,currency) VALUES (?,?,?,?,'HELD',now(),'EUR')").use { it.setObject(1, tenant.value); it.setObject(2, journey); it.setObject(3, passenger); it.setObject(4, quote.id); it.executeUpdate() }
                c.prepareStatement("INSERT INTO taktovia.booking(tenant_id,id,hold_id,service_id,federation_ref,state,promise_snapshot,revision,journey_id,created_by) VALUES (?,?,?,?,?,?,'{}'::jsonb,3,?,'user:test')").use {
                    it.setObject(1, tenant.value); it.setObject(2, booking); it.setObject(3, hold); it.setObject(4, world.service); it.setString(5, "fed-$booking"); it.setString(6, bookingState); it.setObject(7, journey); it.executeUpdate()
                }
                c.commit()
            } catch (e: Exception) { c.rollback(); throw e }
        }
        return PayBooking(booking, journey, quote.id, world.service, quote.payAtPlatformMinor)
    }

    fun begin(b: PayBooking, method: String = "pm_ok", amount: Long = b.amountMinor, authorizeBy: Instant = clock.instant().plus(Duration.ofDays(1))) =
        fx.tx.run(tenant) { j -> payments.beginIn(j, BeginPayment(b.id, b.serviceId, b.journey, b.quote, amount, "EUR", method, authorizeBy)) }

    /** Führt höchstens [max] fällige Operationen aus (RECONCILING-Wiederholungen sind erst nach [PayClock.advance] fällig). */
    fun drain(max: Int = 20): Int { var n = 0; while (n < max && runner.runNext(tenant) != null) n++; return n }

    fun view(b: PayBooking) = fx.tx.run(tenant, readOnly = true) { j -> payments.forBookingIn(j, b.id) }
    fun bookingState(b: PayBooking): Pair<String, Long> = fx.admin.queryForObject("SELECT state || '|' || revision FROM taktovia.booking WHERE id = ?", String::class.java, b.id)!!.split('|').let { it[0] to it[1].toLong() }
    fun opStatus(operationId: UUID): String = fx.admin.queryForObject("SELECT status || '/' || COALESCE(result_code, '') FROM taktovia.operation WHERE id = ?", String::class.java, operationId)!!
    fun count(sql: String, vararg a: Any?): Int = fx.adminCount(sql, *a)
    fun events(type: String, paymentId: UUID): Int = count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = ? AND aggregate_id = ?", tenant.value, type, paymentId)

    override fun close() = fx.close()
}
