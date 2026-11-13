package de.taktovia.platform.money

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Zentrale Rundungsregeln für Geld (LH-6.19-NFR-012, Kap. 03 §2.1, WP-M5-07). Fachmodule runden nicht selbst: sie rufen diese Bausteine
 * (Architekturtest `ZeitGeldRegelnTest`). Alle Rechnungen sind exakt (BigInteger/BigDecimal), nie Gleitkomma; gleiche Eingaben liefern
 * auf jeder Instanz und nach jedem Neustart dieselben Ergebnisse (keine Uhr, kein Zufall, keine Hash-Reihenfolge).
 */
object MoneyRounding {
    /** Steuerrundung und Standardrundung: `ROUND_HALF_UP` (0,5 weg von null), je Steuergruppe auf eine ganze kleinste Einheit. */
    fun halfUp(exact: BigDecimal): BigInteger = exact.setScale(0, RoundingMode.HALF_UP).toBigIntegerExact()

    /** `numerator / denominator` kaufmännisch auf eine ganze kleinste Einheit (HALF_UP). */
    fun halfUpDiv(numerator: BigInteger, denominator: BigInteger): BigInteger {
        require(denominator.signum() > 0) { "Nenner muss positiv sein" }
        return BigDecimal(numerator).divide(BigDecimal(denominator), 0, RoundingMode.HALF_UP).toBigIntegerExact()
    }

    /** Steuer auf einen Nettobetrag: `net * rate`, HALF_UP je Steuergruppe. `rate` als Dezimalzahl (0,19), nie Double. */
    fun tax(netMinor: BigInteger, rate: BigDecimal): BigInteger = halfUp(BigDecimal(netMinor).multiply(rate))

    /** Nettoanteil eines Bruttobetrags: `gross / (1 + rate)`, HALF_UP. */
    fun netOfGross(grossMinor: BigInteger, rate: BigDecimal): BigInteger =
        BigDecimal(grossMinor).divide(BigDecimal.ONE.add(rate), 0, RoundingMode.HALF_UP).toBigIntegerExact()

    /** Prozentsatz-Anteil, aufgerundet (Puffer in Sekunden, Mengen): `value * pct / 100`, CEILING. */
    fun ceilPercent(value: BigInteger, pct: BigDecimal): BigInteger =
        BigDecimal(value).multiply(pct).divide(BigDecimal(100), 0, RoundingMode.CEILING).toBigIntegerExact()

    /** Verhältnis `numerator / denominator` auf `scale` Nachkommastellen, HALF_UP (Prozentanteile zur Anzeige; nie Grundlage von Beträgen). */
    fun ratioHalfUp(numerator: BigDecimal, denominator: BigDecimal, scale: Int): BigDecimal = numerator.divide(denominator, scale, RoundingMode.HALF_UP)

    /** Dezimalmenge/Satz auf `scale` Nachkommastellen, HALF_UP (z. B. Prozentanzeige mit 8 Stellen). Nie Grundlage von Beträgen. */
    fun scaleHalfUp(value: BigDecimal, scale: Int): BigDecimal = value.setScale(scale, RoundingMode.HALF_UP)
}

/**
 * Largest-Remainder-Umlage (Hamilton): verteilt `totalMinor` auf Empfänger nach nichtnegativen Gewichten (höchstens 8 Nachkommastellen).
 * Die Summe der Teile ist exakt `totalMinor`; jeder Teil ist die abgerundete Quote plus höchstens 1; die Reste vergeben die größten
 * Restanteile, bei Gleichstand gewinnt die kleinere Kennung (Textordnung, identisch zur Datenbank). Negative Beträge: zuerst der absolute
 * Betrag verteilt, dann das Vorzeichen angewendet (Kap. 03 §2.1).
 */
object LargestRemainder {
    const val MAX_WEIGHT_SCALE = 8

    /** Ergebnis in Eingabereihenfolge. `weights`: (Kennung als Text, Gewicht). */
    fun distribute(totalMinor: Long, weights: List<Pair<String, BigDecimal>>): List<Long> {
        require(weights.isNotEmpty()) { "keine Empfänger" }
        require(weights.map { it.first }.toSet().size == weights.size) { "Empfänger nicht eindeutig" }
        val ints = weights.map { (_, w) ->
            require(w.signum() >= 0) { "Gewicht negativ" }
            w.setScale(MAX_WEIGHT_SCALE, RoundingMode.UNNECESSARY).movePointRight(MAX_WEIGHT_SCALE).toBigIntegerExact()
        }
        val total = ints.fold(BigInteger.ZERO, BigInteger::add)
        require(total.signum() > 0) { "Summe der Gewichte ist 0" }
        val abs = BigInteger.valueOf(totalMinor).abs()
        val floors = ints.map { abs.multiply(it).divide(total) }
        val remainders = ints.map { abs.multiply(it).mod(total) }
        var rest = abs.subtract(floors.fold(BigInteger.ZERO, BigInteger::add)).toInt()   // kleiner als die Anzahl der Empfänger
        val order = weights.indices.sortedWith(compareByDescending<Int> { remainders[it] }.thenBy { weights[it].first })
        val extra = IntArray(weights.size)
        for (i in order) { if (rest == 0) break; extra[i] = 1; rest-- }
        val sign = if (totalMinor < 0) -1L else 1L
        return weights.indices.map { sign * (floors[it].toLong() + extra[it]) }
    }

    /** Gleichverteilung auf `ids` (Gewicht 1 je Empfänger). */
    fun distributeEqually(totalMinor: Long, ids: List<String>): List<Long> = distribute(totalMinor, ids.map { it to BigDecimal.ONE })

    /**
     * Rücknahme einer Umlage: spiegelt die gespeicherten Teilbeträge (Vorzeichen umgekehrt), nie eine Neuberechnung. Eine Teilrücknahme
     * (`reverseMinor` < Summe) verteilt den Rücknahmebetrag nach den gespeicherten Teilbeträgen als Gewicht, ebenfalls mit Gleichstandregel.
     */
    fun reverse(stored: List<Long>): List<Long> = stored.map { Math.negateExact(it) }

    fun reversePartial(storedIds: List<String>, stored: List<Long>, reverseMinor: Long): List<Long> {
        require(storedIds.size == stored.size && stored.all { it >= 0 }) { "gespeicherte Teilbeträge nichtnegativ" }
        val sum = stored.sum()
        require(reverseMinor in 0..sum) { "Rücknahme übersteigt die Umlage" }
        if (reverseMinor == sum) return reverse(stored)
        return distribute(reverseMinor, storedIds.zip(stored.map { BigDecimal.valueOf(it) })).map { -it }
    }
}
