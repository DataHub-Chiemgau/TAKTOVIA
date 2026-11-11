package de.taktovia.platform.security

import de.taktovia.platform.alert.OperatorAlertPort
import de.taktovia.platform.alert.OperatorWarning
import de.taktovia.platform.alert.Severity
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.SecurityEventListener
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Alarmregel auf Sicherheitsereignisse (WP-M1-41, LH-6.18-SEC-011): löst aus, wenn in [window] mindestens [threshold] Ereignisse
 * mit einer der [actions] im Mandanten stehen. Schwellwerte und Fenster sind Konfiguration; das Pflichtenheft beziffert sie nicht (OP-32,
 * die Standardwerte sind Planungsannahmen).
 */
data class IncidentRule(val code: String, val actions: Set<String>, val threshold: Int, val window: Duration, val severity: Severity) {
    init {
        require(Regex("[a-z][a-z0-9_]{1,30}").matches(code)) { "ungültiger Regelcode" }
        require(threshold >= 1) { "Schwellwert mindestens 1" }
        require(!window.isNegative && !window.isZero) { "Fenster muss positiv sein" }
        require(actions.isNotEmpty() && actions.all { it in AuditActions.CATALOG && it.startsWith("security.") }) { "nur Sicherheitsaktionen des Katalogs" }
    }
}

object IncidentRules {
    const val CROSS_TENANT = "cross_tenant_access"
    const val MFA_FAILURES = "mfa_failures"
    const val PAYLOAD_CHANGED = "payload_changed"

    /** Planungswerte (OP-32): gehäufte Fremdzugriffe 3 in 10 min, MFA-Fehlschläge 5 in 10 min, veränderter Payload bei gleicher Ereignis-ID schon beim ersten Mal. */
    fun defaults(
        crossTenantThreshold: Int = 3, mfaThreshold: Int = 5, payloadThreshold: Int = 1,
        window: Duration = Duration.ofMinutes(10), payloadWindow: Duration = Duration.ofHours(1),
    ) = listOf(
        IncidentRule(CROSS_TENANT, setOf(AuditActions.SECURITY_TENANT_MISMATCH, AuditActions.SECURITY_RECIPIENT_MISMATCH), crossTenantThreshold, window, Severity.CRITICAL),
        IncidentRule(MFA_FAILURES, setOf(AuditActions.SECURITY_MFA_FAILED), mfaThreshold, window, Severity.WARNING),
        IncidentRule(PAYLOAD_CHANGED, setOf(AuditActions.SECURITY_PAYLOAD_HASH_MISMATCH), payloadThreshold, payloadWindow, Severity.CRITICAL),
    )
}

/**
 * Wertet die Alarmregeln gegen den **Sicherheitsereignisspeicher** (`audit_event`, Aktionen `security.*`) aus; die Datenbank ist die Quelle,
 * kein Ereignis geht verloren, auch wenn ein Alarm zusammengefasst wird. Gleichartige Auslösungen ergeben über den Betriebsalarmkanal
 * (WP-M1-47, [OperatorAlertPort]) genau einen offenen Alarm je Regel und Mandant (Fingerabdruck `security-incident/<regel>.<mandantenkürzel>`;
 * das Kürzel hält Vorfälle verschiedener Mandanten auseinander, statt den zweiten stillschweigend zu verschlucken).
 * Die Warnung enthält nur Regelcode, Mandanten-ID, Ereignisart und Schweregrad, nie Inhalte der Ereignisse.
 *
 * Als [SecurityEventListener] merkt sich der Monitor den Mandanten eines gemeldeten Ereignisses (nach COMMIT); [drain] wertet die
 * vorgemerkten Mandanten aus und wird von einem Zeitgeber der Anwendung regelmäßig aufgerufen.
 */
class SecurityIncidentMonitor(
    private val tx: TenantTransactions,
    private val alerts: OperatorAlertPort,
    private val rules: List<IncidentRule> = IncidentRules.defaults(),
    private val clock: Clock = Clock.systemUTC(),
) : SecurityEventListener {
    private val dirty = ConcurrentHashMap.newKeySet<TenantId>()

    override fun reported(tenant: TenantId, action: String) {
        if (rules.any { action in it.actions }) dirty.add(tenant)
    }

    /** Wertet alle seit dem letzten Aufruf betroffenen Mandanten aus; liefert die ausgelösten Regeln je Mandant. */
    fun drain(): Map<TenantId, List<String>> {
        val out = linkedMapOf<TenantId, List<String>>()
        for (t in dirty.toList()) {
            dirty.remove(t)
            val fired = try { evaluate(t) } catch (e: Exception) { dirty.add(t); throw e }   // nicht auswertbar: später erneut versuchen
            if (fired.isNotEmpty()) out[t] = fired
        }
        return out
    }

    /** Wertet die Regeln für einen Mandanten aus und meldet jede ausgelöste an den Betriebsalarmkanal. */
    fun evaluate(tenant: TenantId): List<String> {
        val now = clock.instant()
        val fired = mutableListOf<String>()
        for (rule in rules) {
            val since = Timestamp.from(now.minus(rule.window))
            val count = tx.run(tenant, readOnly = true) { j ->
                rule.actions.sumOf { a ->
                    j.queryForObject("SELECT count(*) FROM taktovia.audit_event WHERE action = ? AND occurred_at >= ?", Long::class.java, a, since) ?: 0L
                }
            }
            if (count >= rule.threshold) {
                fired += rule.code
                alerts.report(OperatorWarning(SOURCE, "${rule.code}.${tenant.value.toString().take(8)}", rule.severity, UUID.randomUUID(), tenant.value,
                    mapOf("reason_code" to rule.code, "event_type" to rule.actions.first(), "attempts" to count.toString())))
            }
        }
        return fired
    }

    companion object { const val SOURCE = "security-incident" }
}
