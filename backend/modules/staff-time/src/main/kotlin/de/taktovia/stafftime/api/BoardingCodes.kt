package de.taktovia.stafftime.api

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.outbox.InboxHandler
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.util.UUID

/** Der Boarding-Code im Klartext: nur beim Ausstellen und beim Vorzeigen/Eingeben; steht nie in `toString` (Logs, Crashreports) und wird nie gespeichert (nur der Hash, WP-M3-30). */
class BoardingCodeValue(val value: String) {
    init { require(Regex("[0-9]{6}").matches(value)) { "Boarding-Code: 6 Ziffern" } }
    override fun toString() = "BoardingCode(redacted)"
    override fun equals(other: Any?) = other is BoardingCodeValue && other.value == value
    override fun hashCode() = value.hashCode()
}

/** Ausgestellter Code: der Klartext wird **einmal** zurückgegeben (Übergabeweg an den Fahrgast: WP-M7a-14, OP-18); danach gibt es nur noch den Hash. */
data class IssuedBoardingCode(val bookingId: UUID, val code: BoardingCodeValue, val validUntil: Instant)

/** Port: Übergabe eines frisch ausgestellten Codes an den Fahrgast (Bestätigungsnachricht, UI-04). Bis zur Entscheidung des Auftraggebers (OP-18) gibt es keine Implementierung. */
fun interface BoardingCodeSink { fun deliver(j: JdbcOperations, issued: IssuedBoardingCode) }

/**
 * Boarding-Code je Buchung (WP-M3-30, LH-6.6-DRV-006, Planungsannahmen OP-18: 6 Ziffern, 24 h gültig, höchstens 5 Fehlversuche je Buchung). Erzeugung und Prüfung gehören `staff-time`;
 * gespeichert wird nur ein gesalzener Hash (PBKDF2-HMAC-SHA256, optional mit serverseitigem Pfeffer), der Klartext nie. Die Prüfung beim Check-in geschieht im Ereigniseingang
 * ([DriverEvents]); hier steht die Ausstellung. Zustände (abgeleitet): ausgestellt, entwertet (`used_at`), gesperrt (`locked_at`), ungültig (abgelaufen oder Buchung storniert).
 */
interface BoardingCodes {
    /** Ausstellung durch die Leitstelle (Rolle `dispatcher`): 409, wenn die Buchung schon einen Code hat; fremde oder unbekannte Buchung 404. Der Klartext wird einmal zurückgegeben. */
    fun issue(ctx: RequestContext, bookingId: UUID): IssuedBoardingCode

    /** Ausstellung in der laufenden Transaktion (Verbraucher des Bestätigungsereignisses, Testfixture); `null`, wenn die Buchung bereits einen Code hat. */
    fun issueIn(j: JdbcOperations, bookingId: UUID, createdBy: String): IssuedBoardingCode?

    /**
     * Verbraucher des Ereignisses `booking.confirmed` (Inbox, Dedupe über `(consumer, event_id)`): stellt je bestätigter Buchung genau einen Code aus und übergibt den Klartext dem [sink];
     * ohne Sink (OP-18: Übergabeweg offen) bleibt nur der Hash, die Leitstelle kann den Code nicht erneut anzeigen.
     */
    fun eventConsumer(sink: BoardingCodeSink?): InboxHandler
}

/** Ereignistyp, auf den der Verbraucher reagiert. */
const val BOARDING_CODE_TRIGGER_EVENT = "booking.confirmed"

internal fun EventEnvelope.isBoardingTrigger() = eventType == BOARDING_CODE_TRIGGER_EVENT
