package de.taktovia.servicegeo.api

import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.util.UUID

/** Eine Dienstversion (WP-M2-03): Betriebszeiten in Ortszeit der Dienstzone [timezone], Verkehrsproduktkennung, Wirksamkeit `[validFrom, validTo)`. */
data class ServiceRevisionView(
    val id: UUID, val serviceId: UUID, val revision: Long, val trafficProduct: String, val timezone: String,
    val weeklyHours: List<Map<String, Any?>>, val exceptions: List<Map<String, Any?>>, val validFrom: Instant, val validTo: Instant?,
    val reason: String, val createdBy: String, val createdAt: Instant,
)

/** Eingabe einer neuen Dienstversion; [weeklyHours]/[exceptions] in der JSON-Form von [OperatingSchedule.parse]. [reason] ist Pflicht (Audit). */
data class PublishServiceRevision(
    val trafficProduct: String, val weeklyHours: Any?, val exceptions: Any?, val validFrom: Instant, val validTo: Instant?, val reason: String,
)

/** Betriebsstatus eines Dienstes zu einem Zeitpunkt, ohne interne Felder (Lesesicht für Fahrgäste). [inOperation] ist `false` ohne wirksame Dienstversion. */
data class ServiceAvailability(
    val serviceId: UUID, val at: Instant, val inOperation: Boolean, val nextOpening: Instant?, val timezone: String, val trafficProduct: String?,
)

/**
 * Dienstversionen mit Betriebszeiten (WP-M2-03). Änderungen verlangen die erwartete Revision (`0` = noch keine Version): veraltet 412, überlappender
 * Gültigkeitszeitraum 409, ungültige Eingaben 422 mit Feldpointern, unbekannter oder fremder Dienst 404. Eine offene Vorgängerversion, die vor der neuen
 * beginnt, wird auf deren Beginn geschlossen. Fachzeile, Audit (mit Akteur und Grund) und Ereignis `service.revision_published` entstehen in einer Transaktion.
 */
interface ServiceRevisions {
    /** Jüngste Version des Dienstes (höchste Revision) oder `null`, wenn keine existiert. */
    fun head(ctx: RequestContext, serviceId: UUID): ServiceRevisionView?
    fun publish(ctx: RequestContext, serviceId: UUID, expectedRevision: Long, cmd: PublishServiceRevision): ServiceRevisionView
    /** Alle Versionen, neueste zuerst; [before] = nur Revisionen unterhalb. */
    fun list(ctx: RequestContext, serviceId: UUID, limit: Int, before: Long? = null): List<ServiceRevisionView>
    /** Zum Zeitpunkt [at] wirksame Version oder `null` (Angebotsbildung darf nur diese verwenden). */
    fun effective(ctx: RequestContext, serviceId: UUID, at: Instant): ServiceRevisionView?
}

/** Betriebsstatus für Fahrgäste und die Angebotsprüfung: mandantenweit lesbar (RLS), ohne Dienstscope; fremder oder unbekannter Dienst 404. */
interface ServiceAvailabilities {
    fun at(ctx: RequestContext, serviceId: UUID, at: Instant): ServiceAvailability
}
