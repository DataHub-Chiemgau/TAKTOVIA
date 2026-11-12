package de.taktovia.stafftime.api

import de.taktovia.platform.context.RequestContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Publikationsbefehl (WP-M4-14, `PublishRequest`): [effectiveFrom] Wirksamkeitsbeginn, [reason] Begründung (3 bis 1000 Zeichen), [impactReportId] Verweis auf den Wirkungsbericht (nur als UUID
 * validiert, nicht fachlich ausgewertet, OP-43), [expectedEtag] Stand des Dienstplans (`If-Match`). [idempotencyKey]: dieselbe Wiederholung liefert dieselbe Revision, ein anderer Inhalt unter
 * demselben Schlüssel ist `409 IDEMPOTENCY_CONFLICT`.
 */
data class PublishRoster(val effectiveFrom: Instant, val reason: String, val impactReportId: UUID, val expectedEtag: Long, val idempotencyKey: String? = null)

/**
 * Ergebnis der Publikation (`PublishedRevision`). [revision] ist die Nummer der veröffentlichten Planrevision, [etag] der Stand des Dienstplans danach. [changedPersons] sind die Personen,
 * deren Schichten sich gegenüber der vorherigen veröffentlichten Revision geändert haben (nur sie werden benachrichtigt). [hintCount] = Zahl der Regelhinweise (Warnungen, WP-M4-13) zum Zeitpunkt der
 * Publikation. [replayed] ist wahr, wenn dieselbe Anfrage (gleicher Idempotenzschlüssel und Inhalt) wiederholt wurde und keine neue Revision entstand.
 */
data class PublishedRoster(
    val rosterId: UUID, val revision: Long, val etag: Long, val effectiveFrom: Instant, val publishedAt: Instant, val publishedBy: String, val auditEventId: UUID,
    val changedPersons: List<UUID>, val hintCount: Int, val replayed: Boolean,
)

/** Eigene veröffentlichte Schicht der Eigensicht (Fahrer-App „Nächster Dienst“, UI-13): nur Schichten der aktuellen veröffentlichten Revision, nie Entwürfe. */
data class OwnShift(
    val shiftId: UUID, val rosterId: UUID, val rosterRevision: Long, val operatingDay: LocalDate, val startsAt: Instant, val endsAt: Instant, val timezone: ZoneId, val function: String?, val state: String,
    val breakMinutes: Long,
)

/**
 * Veröffentlichung des Dienstplans und Eigensicht (WP-M4-14, LH-6.23-SCH-005). Veröffentlichen braucht `rosters:publish` und das Depotrecht (sonst 404); die Eigensicht `time-events:read`.
 * Die Publikation läuft in **einer** Transaktion: Konfliktprüfung (WP-M4-07) unter Sperre erneut, Regelhinweise (WP-M4-13) als Warnung, Entwurf wird zur veröffentlichten Revision (kein Überschreiben:
 * die bisher veröffentlichte Revision bleibt als `SUPERSEDED` unverändert erhalten), Schichten werden verbindlich, Auditereignis und Outbox-Ereignis `staff.roster_published`. Ein Konflikt ergibt
 * 409 ohne jede Wirkung, ein veralteter Stand 412. Die Publikation erteilt keine technische Fahrzeugfreigabe. Die Zustellung der Fahrermeldung ist Sache der Outbox-Verarbeitung; ein Zustellfehler
 * macht die Publikation nicht rückgängig.
 */
interface PublishedRosters {
    fun publish(ctx: RequestContext, rosterId: UUID, cmd: PublishRoster): PublishedRoster

    /** Eigene Schichten der aktuellen veröffentlichten Revisionen im Zeitraum der Betriebstage `[from, to]` (höchstens 62 Tage); fremde Schichten und Entwürfe gibt es hier nie. */
    fun ownShifts(ctx: RequestContext, from: LocalDate, to: LocalDate): List<OwnShift>
}
