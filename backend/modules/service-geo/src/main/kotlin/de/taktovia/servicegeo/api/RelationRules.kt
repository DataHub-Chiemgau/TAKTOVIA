package de.taktovia.servicegeo.api

import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.util.UUID

/** Normierter Ablehnungsgrund einer Relation, Zuordnung zum Katalog von WP-M2-13 (Vorschlag OP-14): [OUTSIDE_AREA] = Gebiet, [RULE_FORBIDDEN] = Regelverbot. */
enum class RelationRejection { OUTSIDE_AREA, RULE_FORBIDDEN }

/** Ein Ende einer Relation: Koordinate und/oder Haltepunkt ([stopId]; dann zählt dessen zum Zeitpunkt wirksame Koordinate und die Zielpunktmenge). */
data class RelationEnd(val point: GeoPoint? = null, val stopId: UUID? = null) {
    init { require(point != null || stopId != null) { "Koordinate oder Haltepunkt nötig" } }
}

/** Ergebnis der Prüfung: zulässig, sonst genau ein Grund (deterministische Priorität) und ein kurzer Text ohne Koordinaten. */
data class RelationDecision(val admissible: Boolean, val reason: RelationRejection?, val detail: String?)

/** Eine Version der erlaubten Zielpunkte eines Dienstes: [destinationStopIds] leer = keine Einschränkung durch Zielpunkte. Wirksam `validFrom <= t < validTo`. */
data class RelationRuleView(
    val id: UUID, val serviceId: UUID, val revision: Long, val destinationStopIds: List<UUID>, val validFrom: Instant, val validTo: Instant?,
    val reason: String, val createdBy: String, val createdAt: Instant,
)

/** Eingabe einer neuen Mengenversion; [destinationStopIds] als Liste von UUID-Texten, jede muss ein Haltepunkt des Mandanten sein. [reason] ist Pflicht (Audit). */
data class PublishRelationRule(val destinationStopIds: Any?, val validFrom: Instant, val validTo: Instant?, val reason: String)

/**
 * Start-/Zielzulässigkeit nach getrennten Zonen und erlaubten Zielpunkten (WP-M2-06, LH-6.9-GEO-003).
 *
 * **Wahrheitstabelle** (Planungsannahme PA-04; Priorität von oben nach unten, genau ein Grund):
 * 1. Start nicht in der wirksamen Startzone (`ORIGIN`/`BOTH`; ohne Startzone ebenso) → `OUTSIDE_AREA`.
 * 2. Ziel ist ein Haltepunkt, der zum Zeitpunkt nicht wirksam oder inaktiv ist → `RULE_FORBIDDEN`.
 * 3. Ziel nicht in der wirksamen Zielzone (`DESTINATION`/`BOTH`; ohne Zielzone ebenso) → `OUTSIDE_AREA`.
 * 4. Zielpunktmenge wirksam und nicht leer, Ziel kein Haltepunkt oder nicht in der Menge → `RULE_FORBIDDEN`.
 * 5. sonst zulässig. Punkte auf der Zonengrenze gelten als innerhalb (`ST_Covers`). Abgelaufene oder noch nicht gültige Regeln und Zonen wirken nicht.
 * Die Rückrichtung wird mit vertauschten Enden unabhängig nach ihren eigenen Zonen und Regeln bewertet. Die Prüfung liest alle Fakten in **einer** Anweisung
 * (ein Snapshot): sie sieht vollständig die alte oder die neue wirksame Version einer gleichzeitigen Regeländerung.
 */
interface RelationRules {
    /** Jüngste Mengenversion oder `null`, wenn es noch keine gibt (keine Einschränkung durch Zielpunkte). Unbekannter oder fremder Dienst: 404. */
    fun head(ctx: RequestContext, serviceId: UUID): RelationRuleView?
    /** Neue Mengenversion; [expectedRevision] `0` = noch keine. Veraltet `REVISION_MISMATCH`, überlappende Wirksamkeit `CONFLICT`, ungültige Eingaben 422 mit Pointer. */
    fun publish(ctx: RequestContext, serviceId: UUID, expectedRevision: Long, cmd: PublishRelationRule): RelationRuleView
    /** „Relation zulässig?“ für Dienst, Start, Ziel und Zeitpunkt; unbekannter oder fremder Dienst 404. */
    fun check(ctx: RequestContext, serviceId: UUID, origin: GeoPoint, destination: RelationEnd, at: Instant): RelationDecision
}
