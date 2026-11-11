package de.taktovia.platform.idempotency

import de.taktovia.platform.outbox.TenantSource
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Idempotenzfilter ohne Webabhängigkeit (WP-M1-13): führt einen Befehl je Scope genau einmal aus und wiederholt danach das gespeicherte Ergebnis.
 *  - Erstausführung: Eintrag IN_PROGRESS, [action] läuft **außerhalb** jeder Registertransaktion, danach wird ein angenommenes Ergebnis (2xx)
 *    gespeichert; jedes andere Ergebnis und jede Ausnahme geben den Schlüssel frei (die Wirkung ist dann nicht eingetreten, ein Retry führt neu aus).
 *  - Wiederholung: dasselbe Ergebnis (Status, Location/ETag, Body), keine zweite Ausführung. Ein angenommenes Kommando erscheint dabei nie nachträglich
 *    als abgelaufen, weil die fachliche Prüfung nicht erneut läuft.
 *  - Anderer Fingerprint zum selben Schlüssel: [IdempotencyResult.Conflict] (HTTP 409 IDEMPOTENCY_CONFLICT), nie das Ergebnis des anderen Requests.
 *  - Parallele Wiederholung während der Erstverarbeitung wartet bis [IdempotencyConfig.inProgressWait] auf das Ergebnis, danach
 *    [IdempotencyResult.InProgress] (HTTP 409 OPERATION_IN_PROGRESS mit `Retry-After`).
 * Authentisierung und Leseberechtigung prüft der Aufrufer **vor** diesem Baustein bei jeder Anfrage, auch bei Wiederholungen (der Akteur ist Teil des Scopes).
 * Grenze (bewusst): Stirbt der Prozess nach dem Commit der Fachwirkung und vor [IdempotencyRegistry.complete], führt die Übernahme nach Leaseablauf den
 * Befehl erneut aus; die Wirkung muss deshalb durch fachliche Unique-Schlüssel (Outbox, M2/M4-Aggregate) einmalig sein.
 */
class IdempotencyService(
    private val registry: IdempotencyRegistry,
    private val clock: Clock = Clock.systemUTC(),
    private val config: IdempotencyConfig = IdempotencyConfig(),
) {
    private val log = LoggerFactory.getLogger("taktovia.idempotency")

    fun execute(req: IdempotentRequest, action: () -> StoredResponse): IdempotencyResult {
        require(req.principalRef.isNotBlank()) { "Idempotenz setzt einen authentisierten Akteur voraus" }
        require(req.commandScope.isNotBlank()) { "Operationsname fehlt" }
        require(IdempotencyKeys.valid(req.key)) { "Idempotency-Key ungültig (16 bis 128 Zeichen aus A-Za-z0-9._:-)" }
        val startedNanos = System.nanoTime()
        while (true) {
            when (val b = registry.begin(req, clock.instant(), config.lease, config.responseTtl)) {
                Begin.Execute -> return run(req, action)
                is Begin.Replay -> return IdempotencyResult.Replayed(b.response)
                Begin.Conflict -> return IdempotencyResult.Conflict
                Begin.InProgress -> {
                    if (System.nanoTime() - startedNanos >= config.inProgressWait.toNanos()) return IdempotencyResult.InProgress(config.retryAfterSeconds)
                    Thread.sleep(config.poll.toMillis().coerceAtLeast(1))
                }
            }
        }
    }

    private fun run(req: IdempotentRequest, action: () -> StoredResponse): IdempotencyResult {
        val response = try { action() } catch (t: Throwable) {
            runCatching { registry.release(req) }.onFailure { log.warn("Idempotenz-Eintrag nach Fehler nicht freigegeben ({})", it.javaClass.simpleName) }
            throw t
        }
        if (response.status !in 200..299) {
            runCatching { registry.release(req) }.onFailure { log.warn("Idempotenz-Eintrag nach Fehlerantwort nicht freigegeben ({})", it.javaClass.simpleName) }
            return IdempotencyResult.Executed(response, stored = false)
        }
        val stored = try { registry.complete(req, clock.instant(), config.responseTtl, response) } catch (e: Exception) {
            log.warn("Ergebnis nicht gespeichert ({}); Wiederholungen warten bis Leaseablauf", e.javaClass.simpleName); false
        }
        if (!stored) log.warn("Idempotenz-Eintrag gehört dieser Verarbeitung nicht mehr (Lease abgelaufen oder bereinigt)")
        return IdempotencyResult.Executed(response, stored)
    }
}

/** Bereinigungsjob (WP-M1-13): entfernt abgelaufene Einträge aller Mandanten der Quelle, begrenzt je Mandant und Lauf. */
class IdempotencyCleanup(private val registry: IdempotencyRegistry, private val tenants: TenantSource, private val clock: Clock = Clock.systemUTC(), private val batch: Int = 1000) {
    /** Anzahl entfernter Einträge. Fehler eines Mandanten stoppen die übrigen nicht. */
    fun runOnce(): Int = tenants.tenants().distinct().sumOf { t -> runCatching { registry.purgeExpired(t, clock.instant(), batch) }.getOrDefault(0) }
}
