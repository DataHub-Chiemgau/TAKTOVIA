package de.taktovia.farepayment

import de.taktovia.farepayment.api.FareComponentCode
import de.taktovia.farepayment.api.FareRejection
import de.taktovia.farepayment.api.FareRequest
import de.taktovia.farepayment.api.PriceResult
import de.taktovia.farepayment.api.TariffRuleInput
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** Zusammenspiel von Tarifrevision (WP-M2-07) und Tarifengine (WP-M2-08) gegen die echte Datenbank: Preisstichtag wählt die Revision, abgelaufen heißt „kein Tarif“ oder Preis der Nachfolgerevision. */
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class TariffPricingTest {
    private val fx = TariffTestFixture()
    @AfterAll fun close() = fx.close()
    private fun at(s: String) = Instant.parse(s)

    @Test
    fun preis_der_wirksamen_revision_nachfolgerregel_oder_kein_tarif() {
        val w = fx.world(); val creator = fx.context(w.tenant); val ctx = fx.context(w.tenant)
        val zoneA = UUID.randomUUID(); val zoneB = UUID.randomUUID()
        fun zoneRule(amount: Long) = listOf(TariffRuleInput(ruleType = "ZONENPREIS", amountMinor = amount, zoneFrom = zoneA.toString(), zoneTo = zoneB.toString(), taxRate = "0.07", taxRuleRevision = "DE-2026-1"))
        val old = fx.published(creator, w, from = "2026-01-01T00:00:00Z", to = "2026-07-01T00:00:00Z", rules = zoneRule(780))
        val req = FareRequest(2, zoneA, zoneB)
        val p1 = fx.tariffs.price(ctx, w.service, "STANDARD", at("2026-06-30T23:59:59Z"), req) as PriceResult.Priced
        assertEquals(old.id, p1.revisionId); assertEquals(1560, p1.breakdown.grossMinor); assertEquals(1, p1.breakdown.components.count { it.code == FareComponentCode.TAX })
        // abgelaufen (ab valid_to) ohne Nachfolger: begründete Ablehnung „kein Tarif“, nie der alte Preis
        val none = fx.tariffs.price(ctx, w.service, "STANDARD", at("2026-07-01T00:00:00Z"), req) as PriceResult.NoTariff
        assertTrue(none.reason.contains("Kein Tarif"))
        // Nachfolgerregel: ab ihrem Beginn gilt der neue Preis
        val next = fx.published(creator, w, from = "2026-07-01T00:00:00Z", rules = zoneRule(900))
        val p2 = fx.tariffs.price(ctx, w.service, "STANDARD", at("2026-07-01T00:00:00Z"), req) as PriceResult.Priced
        assertEquals(next.id, p2.revisionId); assertEquals(1800, p2.breakdown.grossMinor)
        assertEquals(1560, (fx.tariffs.price(ctx, w.service, "STANDARD", at("2026-06-01T00:00:00Z"), req) as PriceResult.Priced).breakdown.grossMinor, "Preisstichtag davor: alter Preis")
        // andere Klasse und fremder Dienst: kein Tarif; Engine-Ablehnung wird durchgereicht (Zonenpaar ohne Preis)
        assertTrue(fx.tariffs.price(ctx, w.service, "ERMAESSIGT", at("2026-08-01T00:00:00Z"), req) is PriceResult.NoTariff)
        assertTrue(fx.tariffs.price(ctx, fx.newService(w.tenant), "STANDARD", at("2026-08-01T00:00:00Z"), req) is PriceResult.NoTariff)
        assertEquals(FareRejection.ZONE_NOT_PRICED, (fx.tariffs.price(ctx, w.service, "STANDARD", at("2026-08-01T00:00:00Z"), FareRequest(1, zoneB, zoneA)) as PriceResult.Rejected).reason)
        // fremder Mandant: nie ein Preis
        assertTrue(fx.tariffs.price(fx.context(fx.world().tenant), w.service, "STANDARD", at("2026-08-01T00:00:00Z"), req) is PriceResult.NoTariff)
    }
}
