package de.taktovia.analytics.internal

import de.taktovia.analytics.api.KmCategory
import de.taktovia.analytics.api.KmClassification
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Eine Position des Fahrzeugs; [distanceFromPrevM] ist der Abstand zur vorherigen Position in Metern (`null` bei der ersten). */
internal data class KmPoint(val id: UUID, val at: Instant, val receivedAt: Instant, val distanceFromPrevM: Long?)

/** Betriebsdienst (Schicht) des Fahrzeugs; [hasPlan] heißt: Umlauf mit mindestens einem Stopp der jüngsten Planrevision (Fahrtenplandeckung). */
internal data class KmShift(val id: UUID, val from: Instant, val to: Instant, val function: String?, val hasPlan: Boolean)

/** Fahrgast an Bord (Einstieg bis Ausstieg einer Buchung); [to] `null` = nie ausgestiegen. */
internal data class KmOccupancy(val bookingId: UUID, val from: Instant, val to: Instant?)

internal data class KmSegmentOut(val from: Instant, val to: Instant, val category: KmCategory, val meters: Long)

/**
 * Regelversion 1 (Vorschlag, in WP-M4-01 freizugeben; Überführung und Werkstattfahrt ohne Datenmodell, OP-22). Jeder Abschnitt zwischen zwei aufeinanderfolgenden Positionen (Länge = Abstand in Metern)
 * wird genau einer Kategorie zugeordnet, nach dem Zeitpunkt seiner Mitte:
 *  1. Abstand der Positionen größer als `MAX_GAP_SECONDS` (Lücke in den Positionsdaten): `UNASSIGNED`.
 *  2. Mitte in keiner Schicht des Fahrzeugs: `UNASSIGNED`.
 *  3. Schichtfunktion mit Überführungsbezug (`transfer`, `überführ`/`ueberfuehr`): `TRANSFER`; mit Werkstattbezug (`workshop`, `werkstatt`): `WORKSHOP`.
 *  4. Schicht ohne Fahrtenplan: `UNASSIGNED` (es wird keine Kategorie erfunden).
 *  5. Mindestens ein Fahrgast an Bord (Einstieg ≤ Mitte < Ausstieg; ein nie ausgestiegener Fahrgast bleibt bis zum Schichtende an Bord): `PASSENGER`, sonst `EMPTY`.
 * Aufeinanderfolgende Abschnitte gleicher Kategorie werden zu einem Abschnitt zusammengefasst; die Summe der Meter aller Abschnitte ist die Gesamtstrecke (Ganzzahl, keine Gleitkommasummen).
 */
internal object KmClassifier {
    fun classify(points: List<KmPoint>, shifts: List<KmShift>, occupancy: List<KmOccupancy>): List<KmSegmentOut> {
        val out = ArrayList<KmSegmentOut>()
        var prev: KmPoint? = null
        for (p in points) {
            val a = prev; prev = p
            if (a == null || !p.at.isAfter(a.at)) continue
            val meters = p.distanceFromPrevM ?: 0L
            val cat = categoryOf(a.at, p.at, shifts, occupancy)
            val last = out.lastOrNull()
            if (last != null && last.category == cat && last.to == a.at) out[out.size - 1] = last.copy(to = p.at, meters = last.meters + meters)
            else out += KmSegmentOut(a.at, p.at, cat, meters)
        }
        return out
    }

    private fun categoryOf(from: Instant, to: Instant, shifts: List<KmShift>, occupancy: List<KmOccupancy>): KmCategory {
        if (Duration.between(from, to).seconds > KmClassification.MAX_GAP_SECONDS) return KmCategory.UNASSIGNED
        val mid = from.plus(Duration.between(from, to).dividedBy(2))
        val shift = shifts.firstOrNull { !mid.isBefore(it.from) && mid.isBefore(it.to) } ?: return KmCategory.UNASSIGNED
        val f = shift.function?.lowercase().orEmpty()
        if (f.contains("transfer") || f.contains("überführ") || f.contains("ueberfuehr")) return KmCategory.TRANSFER
        if (f.contains("workshop") || f.contains("werkstatt")) return KmCategory.WORKSHOP
        if (!shift.hasPlan) return KmCategory.UNASSIGNED
        val onBoard = occupancy.any { !mid.isBefore(it.from) && mid.isBefore(it.to ?: shift.to) }
        return if (onBoard) KmCategory.PASSENGER else KmCategory.EMPTY
    }
}
