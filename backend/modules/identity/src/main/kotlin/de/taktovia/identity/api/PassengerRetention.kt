package de.taktovia.identity.api

import de.taktovia.identity.internal.JdbcPassengerArchive
import de.taktovia.identity.internal.JdbcPassengerRetentionTarget
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.retention.RetentionTarget
import java.time.Instant
import java.util.UUID

/**
 * Aufbewahrung, Archivierung und Löschung des Fahrgastkontos (WP-M1-33, LH-6.18-SEC-007; Datenklasse `LK_PII`). Das Modul besitzt `passenger_private` und die
 * Kontaktbestände; der Löschlauf der Plattform ([de.taktovia.platform.retention.ErasureEngine]) ruft das [RetentionTarget] auf.
 *
 * Löschen und Anonymisieren sind für das Fahrgastkonto dieselbe Wirkung: die Zeile bleibt als Tombstone (Schlüssel für aufbewahrungspflichtige Bezüge wie den
 * Einwilligungsnachweis) ohne jede Personenangabe, Kontaktkanäle, Verifikationsaufgaben und Präferenzen werden gelöscht (Kap. 01 §1.7: aufbewahrungspflichtige
 * Bezüge getrennt von entbehrlicher PII). Das Journal führt die tatsächlich ausgeführte Aktion.
 */
object PassengerRetention {
    fun target(): RetentionTarget = JdbcPassengerRetentionTarget()

    fun archive(ctxTx: ContextTransactions, audit: AuditPort): PassengerArchive = JdbcPassengerArchive(ctxTx, audit)
}

/** Abruf eines archivierten Fahrgastkontos durch die berechtigte Rolle (Scope `retention:read`) bis zum Ende des Archivzustands; Kontaktkanäle ohne Werte. */
data class ArchivedPassenger(val id: UUID, val archivedAt: Instant, val archiveUntil: Instant, val retentionUntil: Instant, val contactChannels: Set<ContactChannel>)

interface PassengerArchive {
    /** Wirft 403 ohne den Scope `retention:read` (operative Rollen) und 404, wenn das Konto nicht (mehr) im Archivzustand ist (auch nach Fristende und im fremden Mandanten). Auditiert. */
    fun read(ctx: RequestContext, passengerId: UUID): ArchivedPassenger
}
