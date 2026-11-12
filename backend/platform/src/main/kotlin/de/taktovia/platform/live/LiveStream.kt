package de.taktovia.platform.live

import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Entscheidung der Berechtigungsprüfung eines laufenden Abonnements, bei **jedem** Abruf neu getroffen. */
sealed interface LiveDecision {
    data class Granted(val grant: LiveGrant) : LiveDecision
    /** Das Abonnement endet; [reason] ist ein fester Code (`session_revoked`, `membership_revoked`, `unavailable`), nie ein Freitext. */
    data class Ended(val reason: String) : LiveDecision
}

/** Senke eines Abonnements (Antwortstrom); wirft eine [java.io.IOException], wenn der Client weg ist. */
fun interface LiveSink { fun write(frame: String) }

data class LiveSettings(
    /** Abstand der Abrufe der Datenbank je Abonnement (Zielintervall der Leitstelle 5 s, Statuswechsel sichtbar ≤ 10 s, OP-05). */
    val pollInterval: Duration = Duration.ofMillis(1000),
    /** Kommentarzeile gegen Zwischenspeicher und Leerlaufabbruch von Proxys. */
    val heartbeat: Duration = Duration.ofSeconds(15),
    /** Höchstdauer einer Verbindung; danach verbindet der Client mit seinem Cursor neu (Berechtigung wird dabei vollständig neu geprüft). */
    val maxDuration: Duration = Duration.ofSeconds(55),
    val retryMillis: Long = 3000,
    val pageSize: Int = 100,
    /** Obergrenze gleichzeitiger Abonnements je Instanz (Schutz der Verbindungs-Threads). */
    val maxConnections: Int = 200,
) {
    init {
        require(!pollInterval.isNegative && !pollInterval.isZero && pollInterval <= Duration.ofSeconds(30)) { "Abrufintervall außerhalb 1 ms..30 s" }
        require(maxDuration <= Duration.ofMinutes(10)) { "Höchstdauer über 10 min" }
        require(pageSize in 1..LiveFeed.MAX_PAGE) { "Seitengröße außerhalb 1..${LiveFeed.MAX_PAGE}" }
    }
}

/**
 * Schreibt einen Server-Sent-Events-Strom (WP-M3-35, OP-05): `ready` (Cursor und Datenstand), je Ereignis `id:<cursor>` und `event: live`, Kommentar-Herzschlag, am Ende `end` mit festem
 * Grund. Berechtigung (Sitzung, Mitgliedschaft, Dienste) wird **je Abruf** über [authorize] erneut bestimmt: ein Entzug beendet den Strom bzw. begrenzt ihn sofort. Der Strom ist
 * absichtlich begrenzt ([LiveSettings.maxDuration]); danach verbindet der Client mit dem letzten `id` neu und lädt seinen Zustand über die gewöhnlichen Listen nach (kein Ereignis trägt den
 * Zustand, jedes nur Referenz und Revision).
 */
class LiveStream(private val feed: LiveFeed, private val clock: Clock = Clock.systemUTC(), private val settings: LiveSettings = LiveSettings(), private val sleeper: (Duration) -> Unit = { Thread.sleep(it) }) {
    private val json = JsonMapper.builder().build()

    /** Letzter ausgelieferter Cursor und Endegrund. */
    data class Outcome(val cursor: LiveCursor, val reason: String)

    fun run(tenant: de.taktovia.platform.tenant.TenantId, start: LiveCursor?, wait: Duration, authorize: () -> LiveDecision, sink: LiveSink): Outcome {
        var cursor = start ?: feed.horizon(tenant)
        val begin = clock.instant()
        val deadline = begin.plus(wait.coerceAtMost(settings.maxDuration))
        var lastWrite = begin
        fun write(frame: String) { sink.write(frame); lastWrite = clock.instant() }
        write("retry: ${settings.retryMillis}\nevent: ready\ndata: ${json.writeValueAsString(linkedMapOf("cursor" to cursor.toString(), "as_of" to begin.toString()))}\n\n")
        while (true) {
            val decision = try { authorize() } catch (e: RuntimeException) { LiveDecision.Ended("unavailable") }
            if (decision is LiveDecision.Ended) { write(end(cursor, decision.reason)); return Outcome(cursor, decision.reason) }
            val grant = (decision as LiveDecision.Granted).grant
            do {
                val page = feed.read(tenant, cursor, grant, settings.pageSize)
                page.events.forEach { e -> write("id: ${e.cursor}\nevent: live\ndata: ${json.writeValueAsString(e.envelope())}\n\n") }
                cursor = page.nextCursor
            } while (page.hasMore)
            val now: Instant = clock.instant()
            if (!now.isBefore(deadline)) { write(end(cursor, "max_duration")); return Outcome(cursor, "max_duration") }
            if (Duration.between(lastWrite, now) >= settings.heartbeat) write(": heartbeat\n\n")
            sleeper(minOf(settings.pollInterval, Duration.between(now, deadline)).coerceAtLeast(Duration.ofMillis(1)))
        }
    }

    private fun end(cursor: LiveCursor, reason: String) = "event: end\ndata: ${json.writeValueAsString(linkedMapOf("reason" to reason, "cursor" to cursor.toString()))}\n\n"
}
