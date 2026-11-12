package de.taktovia.farepayment

import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import de.taktovia.farepayment.api.FareComponentCode
import de.taktovia.farepayment.api.FareQuotes
import de.taktovia.farepayment.api.FareRequest
import de.taktovia.farepayment.api.QuoteHashing
import de.taktovia.farepayment.api.QuoteOutcome
import de.taktovia.farepayment.api.QuoteRequest
import de.taktovia.farepayment.api.QuoteView
import de.taktovia.farepayment.api.TariffRuleInput
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M2-09 / LH-6.10-PAY-001 gegen die echte Datenbank unter der Runtime-Rolle: Quote mit Steuer- und Gebührenkomponenten, reproduzierbarer Hash, Preisbindung, Ablauf,
 * Betragsprüfung („akzeptierter Betrag = Quote“). Die Bestätigung selbst (`POST /v1/bookings`, WP-M2-23) und die Zahlungsautorisierung (WP-M2-30) gehören nicht zu diesem
 * Paket; geprüft wird hier die Port-Prüfung [FareQuotes.requireAcceptedAmount], die beide aufrufen werden, sowie ihr Einsatz im Hold (siehe `RideOfferTest` in `booking-dispatch`).
 * Ein Test, der „keine Buchung und keine Zahlung“ nennt, belegt nur, dass die Prüfung vor jedem Schreiben abbricht und kein Datensatz entsteht.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FareQuoteTest {
    private val fx = TariffTestFixture()
    private val ctxTx = ContextTransactions(fx.tx, object : ContextRevalidator { override fun revalidate(jdbc: JdbcOperations, ctx: RequestContext, lock: Boolean) {} })
    private val quotes: FareQuotes = FareQuotes.create(ctxTx, fx.tariffs)
    private val json = JsonMapper.builder().build()
    @AfterAll fun close() = fx.close()

    private val priceAt = Instant.parse("2026-11-20T10:00:00Z")
    private val expires = Instant.parse("2026-11-20T09:05:00Z")

    private fun rule(amount: Long = 450, fee: Long = 50) =
        TariffRuleInput(ruleType = "FESTPREIS", amountMinor = amount, bookingFeeMinor = fee, taxRate = "0.07", taxRuleRevision = "DE-2026-1")

    /** Mandant, Dienst und veröffentlichte Tarifrevision [2026-01-01, 2027-01-01) mit Transportpreis, Buchungsgebühr und Steuer. */
    private fun world(amount: Long = 450, fee: Long = 50): TariffTestFixture.World {
        val w = fx.world()
        fx.published(fx.context(w.tenant), w, from = "2026-01-01T00:00:00Z", to = "2027-01-01T00:00:00Z", rules = listOf(rule(amount, fee)))
        return w
    }

    private fun attempt(w: TariffTestFixture.World): UUID = UUID.randomUUID().also { id ->
        fx.tx.run(w.tenant) { j -> j.update("INSERT INTO taktovia.travel_attempt(tenant_id, id, created_at, channel) VALUES (taktovia.current_tenant(), ?, now(), 'WEB')", id) }
    }

    private fun configRevision(w: TariffTestFixture.World): UUID =
        fx.tx.run(w.tenant) { j -> j.queryForObject("SELECT config_revision_id FROM taktovia.service WHERE id = ?", UUID::class.java, w.service)!! }

    private fun create(w: TariffTestFixture.World, at: Instant = priceAt, expiresAt: Instant = expires, persons: Int = 2): QuoteView {
        val ctx = fx.context(w.tenant)
        val out = ctxTx.run(ctx) { j -> quotes.createIn(j, ctx, QuoteRequest(attempt(w), w.service, at, FareRequest(persons), expiresAt, configRevision(w))) }
        return (out as QuoteOutcome.Created).quote
    }

    private fun problem(block: () -> Unit) = fx.problem(block)

    @Test
    fun quote_enthaelt_steuer_und_gebuehrenkomponenten_hash_reproduzierbar() {
        val w = world()
        val q = create(w)
        // 2 Personen x 450 + Gebühr 50 = 950 brutto (Steuer enthalten, 7 %): TRANSPORT, SURCHARGE und TAX sind getrennt ausgewiesen
        assertEquals(setOf(FareComponentCode.TRANSPORT, FareComponentCode.SURCHARGE, FareComponentCode.TAX), q.components.map { it.code }.toSet())
        assertEquals(950, q.payAtPlatformMinor)
        assertEquals(q.payAtPlatformMinor, q.components.sumOf { it.amountMinor }, "Endpreis = Summe der Komponenten")
        assertEquals(q.payAtPlatformMinor, q.netMinor + q.taxMinor)
        assertTrue(q.taxMinor > 0 && q.components.single { it.code == FareComponentCode.SURCHARGE }.amountMinor > 0)
        assertEquals(0, q.externalKnownMinor)
        assertEquals("EUR", q.currency); assertEquals(2, q.exponent)
        // Snapshot nach Schema `fare-quote-snapshot.v1`
        val schema = SchemaRegistry.withDialect(Dialects.getDraft202012()).getSchema(Files.readString(PaymentTestDb.repoRoot().resolve("docs/umsetzungsplan/vertraege/schemas/fare-quote-snapshot.v1.schema.json")))
        assertEquals(emptyList<String>(), schema.validate(json.readTree(q.snapshot)).map { it.message }, q.snapshot)
        assertEquals(950, json.readTree(q.snapshot).get("gross_minor").asLong())
        // Hash: SHA-256 über die kanonische Form, aus der gespeicherten Quote neu berechenbar und unabhängig von Schlüsselreihenfolge und Leerraum
        assertTrue(Regex("^[0-9a-f]{64}$").matches(q.contentHash))
        val read = ctxTx.run(fx.context(w.tenant), readOnly = true) { j -> quotes.getIn(j, q.id)!! }
        assertEquals(q.contentHash, QuoteHashing.hash(read.snapshot), "Hash der gespeicherten Quote reproduzierbar")
        assertEquals(q, read.copy(snapshot = q.snapshot), "gelesene Quote = erzeugte Quote")
        val shuffled = json.writeValueAsString(json.readTree(q.snapshot).properties().reversed().associate { it.key to it.value })
        assertEquals(q.contentHash, QuoteHashing.hash(shuffled.replace(",", " , ")), "Reihenfolge der Schlüssel und Leerraum sind ohne Einfluss")
        assertTrue(QuoteHashing.hash("""{"a":1}""") != QuoteHashing.hash("""{"a":2}"""))
        assertEquals("""{"a":[1,{"b":null,"c":"x\"y"}],"z":true}""", QuoteHashing.canonical("""{ "z": true, "a": [1, {"c": "x\"y", "b": null}] }"""))
        // Komponenten in der Datenbank (Segment 1) und unveränderlich
        assertEquals(q.components.size, fx.adminCount("SELECT count(*) FROM taktovia.fare_component WHERE quote_id = ? AND segment_no = 1", q.id))
        assertEquals(q.contentHash, fx.admin.queryForObject("SELECT content_hash FROM taktovia.fare_quote WHERE id = ?", String::class.java, q.id)!!.trim())
    }

    @Test
    fun confirm_mit_abweichendem_betrag_ergibt_requote() {
        val w = world(); val q = create(w); val ctx = fx.context(w.tenant)
        val bookings = fx.adminCount("SELECT count(*) FROM taktovia.booking WHERE tenant_id = ?", w.tenant.value)
        val payments = fx.adminCount("SELECT count(*) FROM taktovia.payment_intent WHERE tenant_id = ?", w.tenant.value)
        for (accepted in listOf(q.payAtPlatformMinor + 1, q.payAtPlatformMinor - 1, 0L, q.payAtPlatformMinor * 2)) {
            val p = problem { ctxTx.run(ctx, readOnly = true) { j -> quotes.requireAcceptedAmount(j, q.id, accepted, "EUR", expires.minusSeconds(60)) } }
            assertEquals(ErrorCode.REQUOTE_REQUIRED, p.code, "akzeptiert $accepted")
        }
        assertEquals(ErrorCode.REQUOTE_REQUIRED, problem { ctxTx.run(ctx, readOnly = true) { j -> quotes.requireAcceptedAmount(j, q.id, q.payAtPlatformMinor, "USD", expires.minusSeconds(60)) } }.code, "andere Währung")
        assertEquals(bookings, fx.adminCount("SELECT count(*) FROM taktovia.booking WHERE tenant_id = ?", w.tenant.value), "keine Buchung")
        assertEquals(payments, fx.adminCount("SELECT count(*) FROM taktovia.payment_intent WHERE tenant_id = ?", w.tenant.value), "keine Zahlung")
    }

    @Test
    fun confirm_mit_quote_betrag_autorisiert_exakt_pay_at_platform_minor() {
        val w = world(); val q = create(w); val ctx = fx.context(w.tenant)
        val accepted = ctxTx.run(ctx, readOnly = true) { j -> quotes.requireAcceptedAmount(j, q.id, q.payAtPlatformMinor, "EUR", expires.minusMillis(1)) }
        // der zu buchende und zur Zahlung zu autorisierende Betrag ist genau dieser eine Wert: Quote = Snapshot = Summe der Komponenten
        assertEquals(950, accepted.payAtPlatformMinor)
        assertEquals(accepted.payAtPlatformMinor, json.readTree(accepted.snapshot).get("gross_minor").asLong())
        assertEquals(accepted.payAtPlatformMinor, accepted.components.sumOf { it.amountMinor })
    }

    @Test
    fun abgelaufene_quote_ergibt_409_ohne_buchung_und_zahlung() {
        val w = world(); val q = create(w); val ctx = fx.context(w.tenant)
        val bookings = fx.adminCount("SELECT count(*) FROM taktovia.booking WHERE tenant_id = ?", w.tenant.value)
        // Zeitgrenze exakt bei expires_at: `now >= expires_at` gilt als abgelaufen, eine Millisekunde davor noch nicht
        ctxTx.run(ctx, readOnly = true) { j -> quotes.requireAcceptedAmount(j, q.id, q.payAtPlatformMinor, "EUR", q.expiresAt.minusMillis(1)) }
        for (now in listOf(q.expiresAt, q.expiresAt.plusSeconds(1), q.expiresAt.plusSeconds(86_400))) {
            val p = problem { ctxTx.run(ctx, readOnly = true) { j -> quotes.requireAcceptedAmount(j, q.id, q.payAtPlatformMinor, "EUR", now) } }
            assertEquals(ErrorCode.OFFER_EXPIRED, p.code); assertEquals(409, p.code.status)
        }
        assertEquals(bookings, fx.adminCount("SELECT count(*) FROM taktovia.booking WHERE tenant_id = ?", w.tenant.value))
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.payment_intent WHERE tenant_id = ?", w.tenant.value))
    }

    @Test
    fun hold_und_confirm_ohne_quote_nicht_moeglich() {
        val w = world(); val q = create(w); val ctx = fx.context(w.tenant)
        // unbekannte Quote: 404, nie ein Preis
        assertEquals(ErrorCode.NOT_FOUND, problem { ctxTx.run(ctx, readOnly = true) { j -> quotes.requireAcceptedAmount(j, UUID.randomUUID(), 950, "EUR", priceAt) } }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { quotes.get(ctx, UUID.randomUUID()) }.code)
        // fremder Mandant: die Quote ist unter RLS nicht lesbar (404, gleich wie unbekannt)
        val other = fx.world().tenant
        assertEquals(ErrorCode.NOT_FOUND, problem { quotes.get(fx.context(other), q.id) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { ctxTx.run(fx.context(other), readOnly = true) { j -> quotes.requireAcceptedAmount(j, q.id, q.payAtPlatformMinor, "EUR", expires.minusSeconds(60)) } }.code)
        // ohne wirksamen Tarif gibt es keine Quote („Kein Tarif“), nie einen Preis einer abgelaufenen Regel
        val noTariff = fx.world()
        val ctx2 = fx.context(noTariff.tenant)
        val out = ctxTx.run(ctx2) { j -> quotes.createIn(j, ctx2, QuoteRequest(attempt(noTariff), noTariff.service, priceAt, FareRequest(1), expires, configRevision(noTariff))) }
        assertTrue(out is QuoteOutcome.NoTariff, out.toString())
        val expired = ctxTx.run(ctx) { j -> quotes.createIn(j, ctx, QuoteRequest(attempt(w), w.service, Instant.parse("2027-01-01T00:00:00Z"), FareRequest(1), expires, configRevision(w))) }
        assertTrue(expired is QuoteOutcome.NoTariff, "nach valid_to kein Preis der abgelaufenen Regel")
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.fare_quote WHERE tenant_id = ?", noTariff.tenant.value))
    }

    @Test
    fun tarifwechsel_parallel_zum_confirm_betrag_bleibt_eingefroren() {
        val w = world(amount = 450, fee = 50); val ctx = fx.context(w.tenant)
        val q = create(w)
        val before = q.contentHash
        val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(9)
        try {
            // 8 Bestätigungsprüfungen laufen, während die Folgerevision (anderer Preis) veröffentlicht wird
            val checks = (1..8).map {
                pool.submit<QuoteView> { start.await(); ctxTx.run(ctx, readOnly = true) { j -> quotes.requireAcceptedAmount(j, q.id, 950, "EUR", expires.minusSeconds(60)) } }
            }
            val change = pool.submit {
                start.await()
                fx.published(fx.context(w.tenant), w, from = "2027-01-01T00:00:00Z", rules = listOf(rule(900, 100)))
            }
            start.countDown()
            change.get(60, TimeUnit.SECONDS)
            checks.forEach { assertEquals(950, it.get(60, TimeUnit.SECONDS).payAtPlatformMinor) }
        } finally { pool.shutdownNow() }
        // der gebundene Preisstand ist unverändert (Betrag, Revision, Hash), ein neuer Preis ist eine neue Quote mit der neuen Revision
        val after = ctxTx.run(ctx, readOnly = true) { j -> quotes.getIn(j, q.id)!! }
        assertEquals(950, after.payAtPlatformMinor); assertEquals(before, after.contentHash); assertEquals(q.tariffRevisionId, after.tariffRevisionId)
        val later = create(w, at = Instant.parse("2027-02-01T10:00:00Z"), expiresAt = Instant.parse("2027-02-01T09:00:00Z"))
        assertEquals(1900, later.payAtPlatformMinor, "2 x 900 + 100 Gebühr"); assertTrue(later.tariffRevisionId != q.tariffRevisionId)
        assertEquals(ErrorCode.REQUOTE_REQUIRED, problem { ctxTx.run(ctx, readOnly = true) { j -> quotes.requireAcceptedAmount(j, later.id, 950, "EUR", Instant.parse("2027-02-01T08:00:00Z")) } }.code)
    }
}
