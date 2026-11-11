package de.taktovia.identity.api

import de.taktovia.identity.internal.DriverReleaseServicesFactory
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Zeitlich begrenzte Fahrerfreigabe mit minimierter Projektion (WP-M1-35, LH-I.4-MUL-005). Detailvertrag: `docs/umsetzungsplan/vertraege/LH-I.4-MUL-005.md`,
 * Zustände: `zustaende/driver-release.md`, Projektionsschema: `schemas/driver-release-projection.v1.schema.json`. Tabellen- und Feldnamen sind Planungsannahme (OP-26).
 *
 * Der Eigentümer ([grant], [revoke]) erzeugt die Projektion in seiner Transaktion und speichert sie unter dem Mandanten des Empfängers; der Empfänger liest
 * ausschließlich diese Projektion ([projection]). Ablauf und Widerruf prüft die Datenbank bei jedem Zugriff (kein Cache, daher keine Invalidierung nötig).
 */
interface DriverReleases {
    /** Erteilt die Freigabe eines Fahrers des Eigentümermandanten (Kontext) an [GrantDriverRelease.recipientTenant] für die genannten Dienste und den Zeitraum. */
    fun grant(ctx: RequestContext, cmd: GrantDriverRelease): DriverReleaseView

    /** Widerruft eine eigene Freigabe (geplant oder aktiv); sofort wirksam. 404 für fremde und unbekannte, 412 bei veralteter Revision, 409 wenn nicht mehr aktiv oder geplant. */
    fun revoke(ctx: RequestContext, releaseId: UUID, expectedRevision: Long): DriverReleaseView

    /** Eigene Freigabe des Eigentümers mit abgeleitetem Zustand; 404, wenn sie ihm nicht gehört. */
    fun get(ctx: RequestContext, releaseId: UUID): DriverReleaseView

    /** Projektion im Empfängerkontext für einen freigegebenen Dienst. Gleichartig 404 für unbekannt, fremden Mandanten, geplant, abgelaufen, widerrufen und nicht freigegebenen Dienst. */
    fun projection(ctx: RequestContext, releaseId: UUID, serviceId: UUID): DriverReleaseProjection

    companion object {
        fun create(ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox, clock: Clock = Clock.systemUTC()): DriverReleases =
            DriverReleaseServicesFactory.create(ctxTx, audit, outbox, clock)
    }
}

/** Abgeleiteter Zustand (siehe `zustaende/driver-release.md`). */
enum class DriverReleaseState { PLANNED, ACTIVE, EXPIRED, REVOKED }

data class GrantDriverRelease(val driverId: UUID, val recipientTenant: UUID, val serviceIds: List<UUID>, val validFrom: Instant, val validTo: Instant)

/** Sicht des Eigentümers; enthält bewusst keine Qualifikationen oder Kontaktdaten des Fahrers. */
data class DriverReleaseView(
    val id: UUID, val driverId: UUID, val recipientTenant: UUID, val serviceIds: List<UUID>, val validFrom: Instant, val validTo: Instant,
    val revokedAt: Instant?, val revision: Long, val state: DriverReleaseState,
)

data class ProjectedQualification(val code: String, val validFrom: Instant, val validTo: Instant?)

/** Feste Feldliste der Projektion (Schema `driver-release-projection.v1`): nichts darüber hinaus. */
data class DriverReleaseProjection(
    val id: UUID, val serviceIds: List<UUID>, val validFrom: Instant, val validTo: Instant,
    val qualifications: List<ProjectedQualification>, val contactKind: String, val contactValue: String,
) {
    override fun toString() = "DriverReleaseProjection(id=$id)"   // keine Kontaktdaten in Logs

    /** Schlüssel in fester Reihenfolge, genau die Felder des Schemas. */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id.toString(), "service_ids" to serviceIds.map { it.toString() }, "valid_from" to validFrom.toString(), "valid_to" to validTo.toString(),
        "qualifications" to qualifications.map { linkedMapOf("code" to it.code, "valid_from" to it.validFrom.toString(), "valid_to" to it.validTo?.toString()) },
        "contact_channel" to linkedMapOf("kind" to contactKind, "value" to contactValue),
    )
}
