package de.taktovia.farepayment.api

import de.taktovia.platform.money.MoneyLimits
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.UUID

/** Komponentencodes (Schema `FareComponent` in `api/federation.yaml`, Tabelle `fare_component`). Rabatt, Zuschuss und Provision erzeugt die Engine in Phase 1 nicht (P2). */
enum class FareComponentCode { TRANSPORT, DISCOUNT, SURCHARGE, SUBSIDY, COMMISSION, TAX }

/** Eingang der Berechnung neben der wirksamen Revision: Personenzahl (1 bis [FareEngine.MAX_PERSONS]), Start-/Zielzone (Gebietsschlüssel), tarifliche Strecke in Metern. */
data class FareRequest(val persons: Int, val originZone: UUID? = null, val destinationZone: UUID? = null, val distanceMeters: Long? = null)

/** Eine Preiskomponente in der kleinsten Einheit; [ruleId] ist die Regel, aus der sie stammt. */
data class FareComponentAmount(val code: FareComponentCode, val amountMinor: Long, val ruleId: UUID)

/**
 * Ergebnis der Berechnung: disjunkte additive Komponenten. [grossMinor] ist die Summe **aller** Komponenten (Netto, Gebühren, Steuer), [taxMinor] die Komponente `TAX`
 * (höchstens einmal), [netMinor] die Summe der übrigen. Es gilt immer `netMinor + taxMinor == grossMinor`.
 */
data class FareBreakdown(val currency: String, val components: List<FareComponentAmount>) {
    val taxMinor: Long get() = components.filter { it.code == FareComponentCode.TAX }.sumOf { it.amountMinor }
    val grossMinor: Long get() = components.sumOf { it.amountMinor }
    val netMinor: Long get() = grossMinor - taxMinor
}

/** Gründe, aus denen die Engine nicht bepreist (nie ein geratener Preis). */
enum class FareRejection { NO_RULES, UNKNOWN_RULE_TYPE, INCONSISTENT_RULES, INVALID_PERSONS, MISSING_ZONE, ZONE_NOT_PRICED, DISTANCE_REQUIRED, OVERFLOW, INVALID_RATE }

sealed interface FareResult {
    data class Priced(val breakdown: FareBreakdown) : FareResult
    data class Rejected(val reason: FareRejection, val detail: String) : FareResult
}

/**
 * Tarifengine (WP-M2-08, LH-6.10-PAY-002): reine, zustandslose und deterministische Berechnung ohne Gleitkomma (nur `Long`, [BigInteger] und [BigDecimal]).
 * Gleiche Eingaben ergeben dasselbe Ergebnis; es gibt keinen geteilten veränderlichen Zustand. Formeln und Rundung (Detailvertrag `LH-6.10-PAY-002.md`):
 *  * Einheiten: `PER_PERSON` = Personenzahl, `PER_BOOKING` = 1.
 *  * `FESTPREIS` und `ZONENPREIS`: Preis je Einheit = `amount_minor` (Zonenpreis: der Satz des gerichteten Zonenpaars aus Start- und Zielzone).
 *  * `GRUNDPREIS_STRECKE`: Preis je Einheit = `amount_minor + runden(rate_per_km * Meter / 1000)`; die Strecke muss größer als 0 sein.
 *  * Transportbetrag = Preis je Einheit * Einheiten; die Gebühr `booking_fee_minor` fällt einmal je Buchung an (Komponente `SURCHARGE`).
 *  * Steuer einmal auf die Summe: `GROSS` (Beträge enthalten die Steuer): Netto = `runden(brutto / (1 + satz))`, Steuer = brutto − Netto; die Gebühr wird ebenso in Netto
 *    umgerechnet, der Transport erhält den Rest. `NET`: Steuer = `runden((transport + gebühr) * satz)`, brutto = Summe + Steuer. Satz 0: keine `TAX`-Komponente.
 *  * Rundung ist überall kaufmännisch (`HALF_UP`) auf ganze kleinste Einheiten, genau einmal je Rechenschritt, in dieser Reihenfolge.
 *  * Überlaufprüfung **vor** jeder Multiplikation und nach jeder Addition gegen 2^53 − 1 ([MoneyLimits.MAX_SAFE], Kap. 04 §4.1); sonst [FareRejection.OVERFLOW].
 */
object FareEngine {
    const val MAX_PERSONS = 100
    private val MAX = BigInteger.valueOf(MoneyLimits.MAX_SAFE)

    fun price(revision: TariffRevisionView, request: FareRequest): FareResult = price(revision.currency, revision.rules, request)

    fun price(currency: String, rules: List<TariffRuleView>, request: FareRequest): FareResult {
        if (request.persons !in 1..MAX_PERSONS) return reject(FareRejection.INVALID_PERSONS, "Personenzahl muss zwischen 1 und $MAX_PERSONS liegen")
        if (rules.isEmpty()) return reject(FareRejection.NO_RULES, "Die Revision enthält keine Preisregel")
        val types = rules.map { r -> TariffRuleType.entries.firstOrNull { it.name == r.ruleType } ?: return reject(FareRejection.UNKNOWN_RULE_TYPE, "Unbekannter Regeltyp") }.toSet()
        if (types.size != 1) return reject(FareRejection.INCONSISTENT_RULES, "Eine Revision darf nur einen Regeltyp enthalten")
        val type = types.single()
        val rule: TariffRuleView = when (type) {
            TariffRuleType.ZONENPREIS -> {
                val from = request.originZone; val to = request.destinationZone
                if (from == null || to == null) return reject(FareRejection.MISSING_ZONE, "Start- und Zielzone sind erforderlich")
                rules.firstOrNull { it.zoneFrom == from && it.zoneTo == to } ?: return reject(FareRejection.ZONE_NOT_PRICED, "Für das Zonenpaar ist kein Preis hinterlegt")
            }
            else -> { if (rules.size != 1) return reject(FareRejection.INCONSISTENT_RULES, "Dieser Regeltyp erlaubt genau eine Regel"); rules.single() }
        }
        val rate = decimal(rule.taxRate) ?: return reject(FareRejection.INVALID_RATE, "Steuersatz ungültig")
        if (rate.signum() < 0 || rate >= BigDecimal.ONE) return reject(FareRejection.INVALID_RATE, "Steuersatz außerhalb von 0 bis unter 1")
        val units = if (rule.personMode == PersonMode.PER_BOOKING.name) 1L else request.persons.toLong()

        var unit = BigInteger.valueOf(rule.amountMinor)
        if (type == TariffRuleType.GRUNDPREIS_STRECKE) {
            val meters = request.distanceMeters
            if (meters == null || meters <= 0) return reject(FareRejection.DISTANCE_REQUIRED, "Die tarifliche Strecke muss größer als 0 sein")
            val perKm = rule.ratePerKm?.let(::decimal) ?: return reject(FareRejection.INVALID_RATE, "Streckensatz ungültig")
            if (perKm.signum() < 0) return reject(FareRejection.INVALID_RATE, "Streckensatz ungültig")
            // Überlaufprüfung vor der Multiplikation: der exakte Wert (Satz * Meter / 1000) darf die Betragsgrenze nicht überschreiten
            val exact = perKm.multiply(BigDecimal.valueOf(meters)).movePointLeft(3)
            if (exact > BigDecimal(MAX)) return reject(FareRejection.OVERFLOW, "Streckenanteil überschreitet den Betragsbereich")
            unit += exact.setScale(0, RoundingMode.HALF_UP).toBigIntegerExact()
            if (unit > MAX) return reject(FareRejection.OVERFLOW, "Preis je Einheit überschreitet den Betragsbereich")
        }
        if (unit.signum() != 0 && BigInteger.valueOf(units) > MAX.divide(unit)) return reject(FareRejection.OVERFLOW, "Transportbetrag überschreitet den Betragsbereich")
        val transport = unit.multiply(BigInteger.valueOf(units))
        val fee = BigInteger.valueOf(rule.bookingFeeMinor)
        val base = transport + fee
        if (base > MAX) return reject(FareRejection.OVERFLOW, "Summe überschreitet den Betragsbereich")

        val out = ArrayList<FareComponentAmount>(3)
        fun add(code: FareComponentCode, v: BigInteger) { out += FareComponentAmount(code, v.toLong(), rule.id) }
        val taxed = rate.signum() > 0
        if (rule.taxMode == TaxMode.NET.name) {
            val tax = if (taxed) BigDecimal(base).multiply(rate).setScale(0, RoundingMode.HALF_UP).toBigIntegerExact() else BigInteger.ZERO
            if (base + tax > MAX) return reject(FareRejection.OVERFLOW, "Bruttobetrag überschreitet den Betragsbereich")
            add(FareComponentCode.TRANSPORT, transport)
            if (fee.signum() > 0) add(FareComponentCode.SURCHARGE, fee)
            if (taxed) add(FareComponentCode.TAX, tax)
        } else {
            val divisor = BigDecimal.ONE.add(rate)
            val net = if (taxed) BigDecimal(base).divide(divisor, 0, RoundingMode.HALF_UP).toBigIntegerExact() else base
            val feeNet = if (taxed) BigDecimal(fee).divide(divisor, 0, RoundingMode.HALF_UP).toBigIntegerExact() else fee
            add(FareComponentCode.TRANSPORT, net - feeNet)
            if (fee.signum() > 0) add(FareComponentCode.SURCHARGE, feeNet)
            if (taxed) add(FareComponentCode.TAX, base - net)
        }
        return FareResult.Priced(FareBreakdown(currency, out))
    }

    private fun reject(reason: FareRejection, detail: String) = FareResult.Rejected(reason, detail)

    /** Dezimalsatz aus dem Textformat (`0.07000000`); alles andere ist ungültig. Kein Gleitkomma. */
    private fun decimal(text: String): BigDecimal? = if (Regex("^[0-9]{1,12}(\\.[0-9]{1,8})?$").matches(text)) BigDecimal(text) else null
}
