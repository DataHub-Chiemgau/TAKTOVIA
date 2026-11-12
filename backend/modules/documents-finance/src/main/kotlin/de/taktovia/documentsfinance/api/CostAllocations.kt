package de.taktovia.documentsfinance.api

import de.taktovia.platform.context.RequestContext
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.UUID

/** Art der Aufteilung (WP-M4-35): Beträge je Zeile (`AMOUNT`), Prozent je Zeile mit Summe 100 (`PERCENT`) oder Schlüsselmengen (`WEIGHT`, z. B. gleiche Anteile oder Fahrzeugkilometer). */
enum class AllocationMode { AMOUNT, PERCENT, WEIGHT }

/** Zielkostenobjekte einer Aufteilung nach Beleg-Gegenstand: Kostenstelle, Fahrzeug oder Projekt (`cost_object.kind`). */
val ALLOCATION_TARGET_KINDS: Set<String> = setOf("COST_CENTER", "VEHICLE", "PROJECT")

/**
 * Rechenkern der Aufteilung (WP-M4-35, Kap. 03 §2.1): **Largest Remainder** in exakter Ganzzahlarithmetik (kein Gleitkomma). Je Empfänger wird der Anteil am Betrag **abgerundet**; die übrigen
 * Cent erhalten die Empfänger mit dem größten Dezimalrest, bei Gleichstand der Empfänger mit der kleineren Kennung (Textform der UUID, wie `ORDER BY` in der Datenbank; stabil, deterministisch). Ein negativer Betrag (Korrektur) wird über seinen
 * Absolutbetrag verteilt und danach mit dem Vorzeichen versehen; eine Korrektur über denselben Schlüssel hebt die ursprünglichen Beträge daher exakt auf.
 */
object AllocationCalculator {
    const val ALGORITHM_VERSION = "LRM-1"

    /** Verteilt `basisMinor` auf die Empfänger nach `weights` (Schlüssel >= 0, Summe > 0; höchstens 8 Nachkommastellen). Ergebnis in Eingabereihenfolge, Summe = `basisMinor`. */
    fun distribute(basisMinor: Long, weights: List<Pair<UUID, BigDecimal>>): List<Long> {
        require(weights.isNotEmpty()) { "keine Empfänger" }
        require(weights.map { it.first }.toSet().size == weights.size) { "Empfänger nicht eindeutig" }
        val ints = weights.map { (_, w) ->
            require(w.signum() >= 0) { "Gewicht negativ" }
            w.setScale(8, java.math.RoundingMode.UNNECESSARY).movePointRight(8).toBigIntegerExact()
        }
        val total = ints.fold(BigInteger.ZERO, BigInteger::add)
        require(total.signum() > 0) { "Summe der Gewichte ist 0" }
        val abs = BigInteger.valueOf(basisMinor).abs()
        val floors = ints.map { abs.multiply(it).divide(total) }
        val remainders = ints.map { abs.multiply(it).mod(total) }
        var rest = abs.subtract(floors.fold(BigInteger.ZERO, BigInteger::add)).toInt()   // < Anzahl der Empfänger
        val order = weights.indices.sortedWith(compareByDescending<Int> { remainders[it] }.thenBy { weights[it].first.toString() })   // Kennung als Text: dieselbe Reihenfolge wie die Datenbank (unsigned), nicht die vorzeichenbehaftete UUID-Ordnung der JVM
        val extra = IntArray(weights.size)
        for (i in order) { if (rest == 0) break; extra[i] = 1; rest-- }
        val sign = if (basisMinor < 0) -1L else 1L
        return weights.indices.map { sign * (floors[it].toLong() + extra[it]) }
    }

    /** Gewicht in Prozent (8 Nachkommastellen, kaufmännisch gerundet) für die Anzeige und die Spalte `weight`; nie Grundlage der Beträge. */
    fun percentOf(amountMinor: Long, basisMinor: Long): BigDecimal =
        if (basisMinor == 0L) BigDecimal.ZERO.setScale(8) else BigDecimal(amountMinor).multiply(BigDecimal(100)).divide(BigDecimal(basisMinor), 8, java.math.RoundingMode.HALF_UP)

}

/** Eine Zeile der Aufteilung im Befehl: genau eine Art der Vorgabe je Aufteilung (`amountMinor`, `percent` oder `quantity`; `quantity` darf die beiden anderen ergänzen). */
data class AllocationLineInput(
    val costObjectId: UUID, val costObjectType: String? = null, val amountMinor: Long? = null, val amountCurrency: String? = null, val amountExponent: Int? = null,
    val percent: BigDecimal? = null, val quantity: BigDecimal? = null, val unit: String? = null,
)

/** Befehl der Aufteilung eines Kostenereignisses; `policyRevision` = Revision der Umlageregel (Audit), `reason` 3 bis 1000 Zeichen. */
data class AllocateCost(val policyRevision: String, val reason: String, val lines: List<AllocationLineInput>)

data class AllocationLineView(val costObjectId: UUID, val costObjectKind: String, val amountMinor: Long, val percent: BigDecimal, val quantity: BigDecimal?, val unit: String?)

/** Eine Umlagerevision: `state` ist immer `POSTED`; `active` = die aktuell wirksame Revision des Ereignisses. */
data class AllocationView(
    val id: UUID, val eventId: UUID, val revision: Long, val state: String, val legalEntityId: UUID, val currency: String, val exponent: Int, val basisMinor: Long,
    val algorithmVersion: String, val mode: AllocationMode, val policyRevision: String, val reason: String, val lines: List<AllocationLineView>, val active: Boolean, val createdBy: String?,
)

/**
 * Aufteilung eines gebuchten Kostenereignisses auf mehrere Kostenobjekte desselben Rechtsträgers (WP-M4-35, DOC-011). Eine Allokation erzeugt **weder** ein Journal **noch** eine Verbindlichkeit **noch**
 * eine zweite Quellkostenbuchung: sie verteilt nur die Kostenbasis des Ereignisses. Unter Sperre (Kostenobjekte `FOR SHARE`, Fiskalperiode `FOR UPDATE`) werden Periode, Revision, Rechtsträger, Währung,
 * Summe und Basis geprüft; der Satz entsteht als `DRAFT` mit Zeilen und wird dann `POSTED` (Summe = Basis, per Datenbank erzwungen); die aktive Revision wechselt atomar über `allocation_activation`.
 *
 * Recht: Scope `costs:allocate` und Finanzrolle (403 sonst). `expectedRevision` = `If-Match` = aktive Revision (0 = noch keine). Fehler: 404 Ereignis/Kostenobjekt (auch fremder Mandant), 409
 * `PERIOD_CLOSED`, 409 `ALREADY_ALLOCATED` (Aufteilung ohne bekannte Revision, obwohl schon eine aktive existiert), 412 veraltete Revision, 422 `ALLOCATION_SUM_MISMATCH` (Summe ungleich Basis, auch um einen Cent;
 * Prozent ungleich 100), 422 für fremden Rechtsträger, inaktives oder doppeltes Kostenobjekt, Mischung der Vorgabearten und Währungs-/Exponentenabweichung.
 */
interface CostAllocations {
    fun allocate(ctx: RequestContext, eventId: UUID, expectedRevision: Long, cmd: AllocateCost): AllocationView

    /** Die aktive Umlagerevision des Ereignisses oder `null`. Scope `costs:read`. */
    fun active(ctx: RequestContext, eventId: UUID): AllocationView?

    /** Alle Umlagerevisionen des Ereignisses, aufsteigend nach Revision. Scope `costs:read`. */
    fun revisions(ctx: RequestContext, eventId: UUID): List<AllocationView>

    /**
     * Gesamtansicht der Ist-Kosten des Rechtsträgers in der Währung (nur gebuchte Ist-Ereignisse, Gegenereignisse negativ): Gesamtsumme und Verteilung auf Kostenobjekte. Ein Ereignis und seine
     * Aufteilung werden nie zusammengezählt (100,00 EUR bleiben 100,00 EUR, nicht 200,00 EUR). Scope `costs:read`.
     */
    fun effectiveCost(ctx: RequestContext, legalEntityId: UUID, currency: String): EffectiveCost
}

/** `totalMinor` = Summe der Kostenbasis aller gebuchten Ist-Ereignisse; `byCostObject` + `unassignedMinor` = `totalMinor` (aktive Umlage bzw. einziges zugeordnetes Kostenobjekt, sonst nicht zugeordnet). */
data class EffectiveCost(val currency: String, val totalMinor: Long, val byCostObject: Map<UUID, Long>, val unassignedMinor: Long)

/** Aufteilungsplan eines Belegs vor dem Posting (Offener Punkt 18): Zeilen nur zu Kostenobjekten, die dem Beleg zugeordnet sind. */
data class SplitLineView(val costObjectId: UUID, val percent: BigDecimal?, val amountMinor: Long?, val quantity: BigDecimal?, val unit: String?)

data class SplitView(val mode: AllocationMode, val policyRevision: String, val reason: String, val lines: List<SplitLineView>, val setBy: String, val setAt: Instant)

/** Befehl: Aufteilungsplan setzen (ersetzt den bisherigen, erzeugt eine neue Belegrevision). */
data class SetSplit(val policyRevision: String, val reason: String, val lines: List<AllocationLineInput>)

/**
 * Beleg-Split (WP-M4-35). Ein Beleg mit **mehreren** zugeordneten Kostenobjekten ist erst freigabefähig, wenn sein Aufteilungsplan vollständig ist (422 `SPLIT_INCOMPLETE`, [ReleaseBlocker.SPLIT_INCOMPLETE]):
 * die Zeilen decken genau die zugeordneten Kostenobjekte ab und die Beträge ergeben centgenau die Kostenbasis (bzw. Prozent 100). Das Posting wandelt den Plan atomar in die erste Umlagerevision des Ereignisses.
 * Setzen: Scope `receipts:write`, nur im Belegzustand `STORED`, `If-Match`; eine Änderung der Kostenzuordnung verwirft den Plan.
 */
interface ExpenseSplits {
    fun set(ctx: RequestContext, documentId: UUID, expectedRevision: Long, cmd: SetSplit): ExpenseView
    fun get(ctx: RequestContext, documentId: UUID): SplitView?
}
