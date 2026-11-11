package de.taktovia.communicationsupport.internal

import de.taktovia.communicationsupport.api.AttemptOutcome
import de.taktovia.communicationsupport.api.ContactLookup
import de.taktovia.communicationsupport.api.DeliveryGateway
import de.taktovia.communicationsupport.api.DispatchConfig
import de.taktovia.communicationsupport.api.DispatchStats
import de.taktovia.communicationsupport.api.FailureReason
import de.taktovia.communicationsupport.api.NotificationChannel
import de.taktovia.communicationsupport.api.NotificationDispatcher
import de.taktovia.communicationsupport.api.OutboundMessage
import de.taktovia.communicationsupport.api.ProviderResult
import de.taktovia.communicationsupport.api.ProviderStatus
import de.taktovia.communicationsupport.api.ProviderTimeoutException
import de.taktovia.communicationsupport.api.RecipientDirectory
import de.taktovia.communicationsupport.api.TemplateQuery
import de.taktovia.communicationsupport.api.TemplateRenderException
import de.taktovia.communicationsupport.api.TemplateRenderer
import de.taktovia.communicationsupport.api.TemplateResolution
import de.taktovia.communicationsupport.api.TemplateRevisionView
import de.taktovia.platform.alert.OperatorAlertPort
import de.taktovia.platform.alert.OperatorWarning
import de.taktovia.platform.alert.Severity
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Versand-Worker (WP-M2-32). Jeder Schritt ist eine kurze Mandantentransaktion; der Providerruf, die Kontakt- und die Spracherhebung laufen **außerhalb** jeder
 * Transaktion (Leitlinie 3). Eigentum am Auftrag: das Beanspruchen setzt `SENDING` mit Lease; jede Ergebnisbuchung gilt nur für `state = 'SENDING'` und führt
 * Zustand, Versuchszähler, Versuchszeile und Ereignis in einer Transaktion nach. Protokolliert werden nur IDs und technische Codes, nie Kontakt oder Inhalt.
 */
internal class NotificationWorker(
    private val tx: TenantTransactions, private val templates: JdbcTemplates, private val gateway: DeliveryGateway, private val directory: RecipientDirectory,
    private val alerts: OperatorAlertPort, private val outbox: Outbox, private val config: DispatchConfig, private val clock: Clock, private val metrics: MeterRegistry?,
) : NotificationDispatcher {
    private val log = LoggerFactory.getLogger("taktovia.notification.dispatch")
    private val mapper = JsonMapper.builder().build()

    private class Claimed(val id: UUID, val recipient: UUID, val channel: NotificationChannel, val eventType: String, val serviceId: UUID?, val params: Map<String, String>,
                          val templateRevisionId: UUID?, val language: String?)

    override fun runTenant(tenant: TenantId): DispatchStats {
        var stats = recoverStale(tenant)
        stats += reconcileDue(tenant)
        while (true) {
            val claimed = claim(tenant)
            if (claimed.isEmpty()) return stats
            for (c in claimed) stats += process(tenant, c)
            if (claimed.size < config.batchSize) return stats
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- Beanspruchen

    private fun claim(tenant: TenantId): List<Claimed> {
        val now = clock.instant()
        return tx.run(tenant) { j ->
            j.query(
                "UPDATE taktovia.notification n SET state = 'SENDING', claimed_until = ?, next_attempt_at = NULL " +
                    "WHERE n.id IN (SELECT id FROM taktovia.notification WHERE state IN ('QUEUED', 'RETRY_WAIT') AND (next_attempt_at IS NULL OR next_attempt_at <= ?) " +
                    "ORDER BY created_at, id LIMIT ? FOR UPDATE SKIP LOCKED) " +
                    "RETURNING n.id, n.recipient_ref, n.channel, n.event_type, n.service_id, n.params::text, n.template_revision_id, n.language",
                { rs, _ ->
                    @Suppress("UNCHECKED_CAST")
                    Claimed(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), NotificationChannel.valueOf(rs.getString(3)), rs.getString(4), rs.getObject(5, UUID::class.java),
                        mapper.readValue(rs.getString(6), Map::class.java) as Map<String, String>, rs.getObject(7, UUID::class.java), rs.getString(8))
                },
                Timestamp.from(now.plus(config.lease)), Timestamp.from(now), config.batchSize,
            )
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- Versand

    private fun process(tenant: TenantId, c: Claimed): DispatchStats {
        try {
            // 1. Kontakt (nie gespeichert, nie protokolliert)
            val contact = directory.contact(tenant, c.recipient, c.channel)
            if (contact !is ContactLookup.Found) return suppress(tenant, c)
            // 2. Vorlage: beim ersten Versuch auflösen und binden, danach bleibt die gebundene Revision
            val template = bindTemplate(tenant, c) ?: return failWithoutAttempt(tenant, c, FailureReason.TEMPLATE_UNAVAILABLE)
            val rendered = try { TemplateRenderer.render(c.eventType, c.channel, template.subjectTemplate, template.bodyTemplate, c.params) } catch (e: TemplateRenderException) {
                log.warn("Nachricht {}: Rendering fehlgeschlagen ({})", c.id, e.code); return failWithoutAttempt(tenant, c, FailureReason.TEMPLATE_UNAVAILABLE)
            }
            // 3. Providerruf ausserhalb jeder Transaktion; die Nachrichten-ID ist die Idempotenzreferenz
            val started = clock.instant()
            val result: Any = try { gateway.send(OutboundMessage(c.id, c.channel, contact.address, rendered.subject, rendered.body)) }
            catch (e: ProviderTimeoutException) { e } catch (e: Exception) { log.warn("Nachricht {}: Providerfehler ({})", c.id, e.javaClass.simpleName); e }
            return record(tenant, c, started, result)
        } catch (e: Exception) {
            // unerwartet (z. B. Datenbankfehler): Lease läuft ab, der Auftrag wird als unbestimmt abgeglichen; nichts geht verloren
            log.warn("Nachricht {}: Verarbeitung abgebrochen ({})", c.id, e.javaClass.simpleName)
            return DispatchStats()
        }
    }

    private fun bindTemplate(tenant: TenantId, c: Claimed): TemplateRevisionView? {
        if (c.templateRevisionId != null) return tx.run(tenant, readOnly = true) { j -> templates.byId(j, c.templateRevisionId) }
        val raw = directory.language(tenant, c.recipient)
        val language = raw?.takeIf { JdbcTemplates.LANGUAGE.matches(it) } ?: JdbcTemplates.DEFAULT_LANGUAGE
        return tx.run(tenant) { j ->
            val found = (templates.resolve(j, TemplateQuery(c.eventType, c.serviceId, language, c.channel, clock.instant())) as? TemplateResolution.Found)?.template ?: return@run null
            j.update("UPDATE taktovia.notification SET template_revision_id = ?, language = ? WHERE id = ? AND state = 'SENDING' AND template_revision_id IS NULL", found.id, language, c.id)
            found
        }
    }

    private fun suppress(tenant: TenantId, c: Claimed): DispatchStats {
        tx.run(tenant) { j -> j.update("UPDATE taktovia.notification SET state = 'SUPPRESSED', failure_reason = 'NO_VERIFIED_CONTACT', claimed_until = NULL WHERE id = ? AND state = 'SENDING'", c.id) }
        metric(c.channel, "suppressed"); log.info("Nachricht {} unterdrückt (kein verifizierter Kontakt)", c.id)
        return DispatchStats(suppressed = 1)
    }

    /** Fehlschlag vor dem Providerruf (Vorlage fehlt oder nicht renderbar): kein Versuch, aber dokumentierter Kanalfehler, Ereignis und Warnung. */
    private fun failWithoutAttempt(tenant: TenantId, c: Claimed, reason: FailureReason): DispatchStats {
        val failed = tx.run(tenant) { j -> fail(j, tenant, c.id, c.channel, c.eventType, reason, "SENDING") }
        if (failed) metric(c.channel, "failed")
        return DispatchStats(failed = if (failed) 1 else 0)
    }

    /** Bucht das Ergebnis des Providerrufs. Gilt nur, solange der Auftrag `SENDING` ist (verlorene Lease: nichts buchen). */
    private fun record(tenant: TenantId, c: Claimed, started: Instant, result: Any): DispatchStats {
        val finished = clock.instant()
        return tx.run(tenant) { j ->
            val outcome: AttemptOutcome; val providerRef: String?; val error: String?
            when (result) {
                is ProviderResult.Accepted -> { outcome = AttemptOutcome.ACCEPTED; providerRef = result.providerRef.take(200); error = null }
                is ProviderResult.Rejected -> { outcome = AttemptOutcome.REJECTED; providerRef = null; error = result.reason.name.lowercase() }
                is ProviderTimeoutException -> { outcome = AttemptOutcome.TIMED_OUT; providerRef = null; error = "timeout" }
                else -> { outcome = AttemptOutcome.REJECTED; providerRef = null; error = "provider_exception" }
            }
            // Zähler und Zustand in einer Anweisung: gilt nur für SENDING; die Versuchsnummer ist der neue Zählerstand
            val bumped = j.query("UPDATE taktovia.notification SET attempt_count = attempt_count + 1 WHERE id = ? AND state = 'SENDING' RETURNING attempt_count",
                { rs, _ -> rs.getInt(1) }, c.id).firstOrNull()
            if (bumped == null) { log.warn("Nachricht {}: Lease verloren, Ergebnis nicht gebucht", c.id); return@run null }
            val attemptNo: Int = bumped
            j.update("INSERT INTO taktovia.notification_attempt(tenant_id, notification_id, attempt_no, started_at, finished_at, outcome, provider_ref, error_code) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, ?, ?, ?)",
                c.id, attemptNo, Timestamp.from(started), Timestamp.from(finished), outcome.name, providerRef, error)
            when {
                result is ProviderResult.Accepted -> { deliver(j, tenant, c.id, c.channel, c.eventType); metric(c.channel, "delivered"); DispatchStats(delivered = 1) }
                result is ProviderResult.Rejected && result.permanent -> { fail(j, tenant, c.id, c.channel, c.eventType, result.reason, "SENDING"); metric(c.channel, "failed"); DispatchStats(failed = 1) }
                result is ProviderTimeoutException -> {
                    j.update("UPDATE taktovia.notification SET state = 'UNKNOWN', claimed_until = NULL, next_attempt_at = ? WHERE id = ?", Timestamp.from(finished.plus(config.delay(attemptNo))), c.id)
                    metric(c.channel, "unknown"); DispatchStats(unknown = 1)
                }
                attemptNo >= config.maxAttempts -> { fail(j, tenant, c.id, c.channel, c.eventType, FailureReason.RETRIES_EXHAUSTED, "SENDING"); metric(c.channel, "failed"); DispatchStats(failed = 1) }
                else -> {
                    j.update("UPDATE taktovia.notification SET state = 'RETRY_WAIT', claimed_until = NULL, next_attempt_at = ? WHERE id = ?", Timestamp.from(finished.plus(config.delay(attemptNo))), c.id)
                    metric(c.channel, "retry"); DispatchStats(retried = 1)
                }
            }
        } ?: DispatchStats()
    }

    // ---------------------------------------------------------------------------------------------------------------- Abgleich

    /** Ein Worker ist nach dem Beanspruchen ausgefallen: der Ausgang ist unbestimmt (`UNKNOWN`), der Versuch wird als `TIMED_OUT` dokumentiert und anschließend abgeglichen. */
    private fun recoverStale(tenant: TenantId): DispatchStats {
        val now = clock.instant(); val ts = Timestamp.from(now)
        val recovered = tx.run(tenant) { j ->
            val rows = j.query(
                "UPDATE taktovia.notification n SET state = 'UNKNOWN', attempt_count = n.attempt_count + 1, claimed_until = NULL, next_attempt_at = ? " +
                    "WHERE n.id IN (SELECT id FROM taktovia.notification WHERE state = 'SENDING' AND claimed_until < ? FOR UPDATE SKIP LOCKED) RETURNING n.id, n.attempt_count, n.channel",
                { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getInt(2), rs.getString(3)) }, ts, ts)
            rows.forEach { (id, no, _) ->
                j.update("INSERT INTO taktovia.notification_attempt(tenant_id, notification_id, attempt_no, started_at, finished_at, outcome, error_code) VALUES (taktovia.current_tenant(), ?, ?, ?, ?, 'TIMED_OUT', 'lease_expired')", id, no, ts, ts)
            }
            rows
        }
        recovered.forEach { log.warn("Nachricht {}: Lease abgelaufen, Ausgang unbestimmt", it.first) }
        return DispatchStats(unknown = recovered.size)
    }

    private class Unknown(val id: UUID, val channel: NotificationChannel, val eventType: String, val attempts: Int, val reconciles: Int)

    /**
     * Abgleich unbestimmter Aufträge **vor** jeder Wiederholung (Leitlinie 6): der Provider kennt die Nachrichten-ID als zugestellt (`DELIVERED`), nicht (`RETRY_WAIT`, sofort fällig) oder
     * kann es nicht sagen (bleibt `UNKNOWN`, nächster Abgleich nach Staffel; nach [DispatchConfig.maxAttempts] erfolglosen Abgleichen `FAILED` mit Betreiberwarnung).
     */
    private fun reconcileDue(tenant: TenantId): DispatchStats {
        val now = clock.instant(); val ts = Timestamp.from(now)
        val due = tx.run(tenant) { j ->
            j.query(
                "UPDATE taktovia.notification n SET claimed_until = ? WHERE n.id IN (SELECT id FROM taktovia.notification WHERE state = 'UNKNOWN' AND (next_attempt_at IS NULL OR next_attempt_at <= ?) " +
                    "AND (claimed_until IS NULL OR claimed_until <= ?) ORDER BY created_at, id LIMIT ? FOR UPDATE SKIP LOCKED) RETURNING n.id, n.channel, n.event_type, n.attempt_count, n.reconcile_count",
                { rs, _ -> Unknown(rs.getObject(1, UUID::class.java), NotificationChannel.valueOf(rs.getString(2)), rs.getString(3), rs.getInt(4), rs.getInt(5)) },
                Timestamp.from(now.plus(config.lease)), ts, ts, config.batchSize)
        }
        var stats = DispatchStats()
        for (u in due) {
            val status = try { gateway.status(u.channel, u.id) } catch (e: Exception) { ProviderStatus.Unavailable }
            stats += tx.run(tenant) { j ->
                when {
                    status is ProviderStatus.Accepted -> { if (deliver(j, tenant, u.id, u.channel, u.eventType, "UNKNOWN")) { metric(u.channel, "delivered"); DispatchStats(delivered = 1) } else DispatchStats() }
                    status is ProviderStatus.NotKnown && u.attempts < config.maxAttempts -> {
                        j.update("UPDATE taktovia.notification SET state = 'RETRY_WAIT', claimed_until = NULL, next_attempt_at = ? WHERE id = ? AND state = 'UNKNOWN'", ts, u.id); DispatchStats(retried = 1)
                    }
                    status is ProviderStatus.NotKnown -> { if (fail(j, tenant, u.id, u.channel, u.eventType, FailureReason.RETRIES_EXHAUSTED, "UNKNOWN")) { metric(u.channel, "failed"); DispatchStats(failed = 1) } else DispatchStats() }
                    u.reconciles + 1 >= config.maxAttempts -> {
                        j.update("UPDATE taktovia.notification SET reconcile_count = reconcile_count + 1 WHERE id = ? AND state = 'UNKNOWN'", u.id)
                        if (fail(j, tenant, u.id, u.channel, u.eventType, FailureReason.RETRIES_EXHAUSTED, "UNKNOWN")) { metric(u.channel, "failed"); DispatchStats(failed = 1) } else DispatchStats()
                    }
                    else -> {
                        j.update("UPDATE taktovia.notification SET reconcile_count = reconcile_count + 1, claimed_until = NULL, next_attempt_at = ? WHERE id = ? AND state = 'UNKNOWN'",
                            Timestamp.from(now.plus(config.delay(u.attempts + u.reconciles + 1))), u.id)
                        DispatchStats(unknown = 1)
                    }
                }
            }
        }
        return stats
    }

    // ---------------------------------------------------------------------------------------------------------------- Endzustände

    /** Zustand `DELIVERED` und Ereignis „Nachricht versendet“ in derselben Transaktion. */
    private fun deliver(j: JdbcOperations, tenant: TenantId, id: UUID, channel: NotificationChannel, eventType: String, from: String = "SENDING"): Boolean {
        val row = j.query("UPDATE taktovia.notification SET state = 'DELIVERED', delivered_at = ?, claimed_until = NULL, next_attempt_at = NULL WHERE id = ? AND state = ? RETURNING revision, attempt_count, delivered_at",
            { rs, _ -> Triple(rs.getLong(1), rs.getInt(2), rs.getTimestamp(3).toInstant()) }, Timestamp.from(clock.instant()), id, from).firstOrNull() ?: return false
        outbox.append(j, OutboxEvent(id, row.first, "communication.notification_sent", linkedMapOf(
            "notification_id" to id.toString(), "channel" to channel.name, "template" to eventType, "attempts" to maxOf(row.second, 1), "delivered_at" to row.third.toString()), clock.instant()))
        return true
    }

    /** Endgültiger Kanalfehler: `FAILED` mit Grund und Dead-Letter-Markierung, Ereignis „Nachricht endgültig fehlgeschlagen“ und genau eine Betreiberwarnung (kein stiller Verlust). */
    private fun fail(j: JdbcOperations, tenant: TenantId, id: UUID, channel: NotificationChannel, eventType: String, reason: FailureReason, from: String): Boolean {
        val row = j.query("UPDATE taktovia.notification SET state = 'FAILED', failure_reason = ?, dead_lettered_at = ?, claimed_until = NULL, next_attempt_at = NULL WHERE id = ? AND state = ? RETURNING revision, attempt_count",
            { rs, _ -> rs.getLong(1) to rs.getInt(2) }, reason.name, Timestamp.from(clock.instant()), id, from).firstOrNull() ?: return false
        outbox.append(j, OutboxEvent(id, row.first, "communication.notification_failed", linkedMapOf(
            "notification_id" to id.toString(), "channel" to channel.name, "template" to eventType, "attempts" to maxOf(row.second, 1), "failure" to reason.name), clock.instant()))
        warnAfterCommit(tenant, eventType, row.second, reason)
        log.warn("Nachricht {} endgültig fehlgeschlagen ({})", id, reason)
        return true
    }

    /** Die Warnung ist ein Meldepfad ohne Datenbankbezug; sie wird nach dem Eintragen ausgelöst, ihr Fehlschlag macht den Fachvorgang nicht rückgängig. */
    private fun warnAfterCommit(tenant: TenantId, eventType: String, attempts: Int, reason: FailureReason) {
        try {
            alerts.report(OperatorWarning("notification", "dead_letter", Severity.CRITICAL, UUID.randomUUID(), tenant.value,
                mapOf("event_type" to eventType, "attempts" to attempts.toString(), "reason_code" to reason.name.lowercase())))
        } catch (e: Exception) { log.warn("Betreiberwarnung nicht ausgelöst ({})", e.javaClass.simpleName) }
    }

    private fun metric(channel: NotificationChannel, outcome: String) {
        metrics?.counter("taktovia_notification_results_total", "channel", channel.name.lowercase(), "outcome", outcome)?.increment()
    }
}
