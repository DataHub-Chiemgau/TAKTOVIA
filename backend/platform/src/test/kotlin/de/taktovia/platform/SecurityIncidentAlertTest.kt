package de.taktovia.platform

import de.taktovia.platform.alert.AlertChannel
import de.taktovia.platform.alert.AlertNotification
import de.taktovia.platform.alert.OperatorAlertService
import de.taktovia.platform.alert.Severity
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.audit.SecurityEvent
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.security.IncidentRules
import de.taktovia.platform.security.SecurityIncidentMonitor
import de.taktovia.platform.tenant.TenantId
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M1-41 (LH-6.18-SEC-011): Alarmregeln auf Sicherheitsereignisse. Echte Datenbank (Ereignisse über den SecurityEventPort mit COMMIT),
 * echter Betriebsalarmkanal ([OperatorAlertService]) mit aufzeichnendem Empfänger.
 */
class SecurityIncidentAlertTest {
    companion object {
        private lateinit var s: AuditTestSupport
        @BeforeAll @JvmStatic fun setup() { s = AuditTestSupport(32) }
        @AfterAll @JvmStatic fun teardown() { if (::s.isInitialized) s.close() }
    }

    private class Recorder : AlertChannel {
        val got = CopyOnWriteArrayList<AlertNotification>()
        override fun deliver(notification: AlertNotification) { got.add(notification) }
    }

    private val recorder = Recorder()
    private val alerts = OperatorAlertService(recorder, SimpleMeterRegistry(), { })
    private fun monitor(rules: List<de.taktovia.platform.security.IncidentRule> = IncidentRules.defaults()) = SecurityIncidentMonitor(s.tx, alerts, rules)
    private fun port(m: SecurityIncidentMonitor) = SecurityEventPort(s.audit, s.tx, m)

    private fun freshTenant(): TenantId = TenantId(UUID.randomUUID()).also {
        JdbcTemplate(TestDatabase.admin()).update("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?, 'ACTIVE')", it.value, "inc41-${it.value}")
    }

    private fun report(p: SecurityEventPort, t: TenantId, action: String, n: Int, actor: UUID = UUID.randomUUID()) =
        repeat(n) { s.tx.run(t) { j -> p.report(j, SecurityEvent(ActorRef.user(actor), action, ObjectRef("user", actor), UUID.randomUUID(), mapOf("surface" to "api"))) } }

    private fun stored(t: TenantId, action: String) =
        s.tx.run(t, readOnly = true) { j -> j.queryForObject("SELECT count(*) FROM taktovia.audit_event WHERE action = ?", Int::class.java, action)!! }

    @Test fun `cross_tenant_uebung_erzeugt_alarm_an_betriebskanal`() {
        val m = monitor(); val p = port(m); val t = freshTenant()
        val compromised = UUID.randomUUID()
        // Übungsszenario: kompromittiertes Konto versucht dreimal, auf Daten eines fremden Mandanten zuzugreifen
        report(p, t, AuditActions.SECURITY_TENANT_MISMATCH, 3, compromised)
        val fired = m.drain()
        assertEquals(mapOf(t to listOf(IncidentRules.CROSS_TENANT)), fired)
        val n = recorder.got.single()
        assertEquals("security-incident/cross_tenant_access.${t.value.toString().take(8)}", n.dedupKey)
        assertEquals(Severity.CRITICAL, n.severity)
        assertEquals(t.value, n.tenantRef)
        assertEquals("cross_tenant_access", n.attributes["reason_code"])
        assertTrue(n.attributes.values.none { it.contains(compromised.toString()) }, "Alarm enthält keine Personenbezüge: ${n.attributes}")
        assertEquals(3, stored(t, AuditActions.SECURITY_TENANT_MISMATCH))
    }

    @Test fun `unterhalb_schwellwert_kein_alarm`() {
        val m = monitor(); val p = port(m)
        for ((action, below) in listOf(AuditActions.SECURITY_TENANT_MISMATCH to 2, AuditActions.SECURITY_MFA_FAILED to 4)) {
            val t = freshTenant(); report(p, t, action, below)
            assertEquals(emptyMap<TenantId, List<String>>(), m.drain(), action)
        }
        // veränderter Payload: Schwellwert ist Konfiguration; bei Schwelle 3 bleiben 2 Ereignisse unterhalb
        val m3 = monitor(IncidentRules.defaults(payloadThreshold = 3)); val t = freshTenant()
        report(port(m3), t, AuditActions.SECURITY_PAYLOAD_HASH_MISMATCH, 2)
        assertEquals(emptyMap<TenantId, List<String>>(), m3.drain())
        assertEquals(0, recorder.got.size)
        assertEquals(0, alerts.openAlerts().size)
    }

    @Test fun `alarmregeln_mfa_fehlschlag_und_payload_veraenderung_loesen_aus`() {
        val m = monitor(); val p = port(m)
        val t1 = freshTenant(); report(p, t1, AuditActions.SECURITY_MFA_FAILED, 5)
        assertEquals(mapOf(t1 to listOf(IncidentRules.MFA_FAILURES)), m.drain())
        val t2 = freshTenant(); report(p, t2, AuditActions.SECURITY_PAYLOAD_HASH_MISMATCH, 1)
        assertEquals(mapOf(t2 to listOf(IncidentRules.PAYLOAD_CHANGED)), m.drain())
        assertEquals(setOf(Severity.WARNING, Severity.CRITICAL), recorder.got.map { it.severity }.toSet())
        assertEquals(2, recorder.got.size)
    }

    @Test fun `zweihundert_ereignisse_ergeben_genau_einen_alarm`() {
        val m = monitor(); val p = port(m); val t = freshTenant()
        val pool = Executors.newFixedThreadPool(24); val start = CountDownLatch(1)
        val futures = (1..200).map { pool.submit { start.await(); report(p, t, AuditActions.SECURITY_TENANT_MISMATCH, 1) } }
        start.countDown(); futures.forEach { it.get(60, TimeUnit.SECONDS) }
        // gleichzeitige Auswertungen (mehrere Instanzen/Zeitgeber): weiterhin genau ein Alarm, genau eine Zustellung
        val drains = (1..8).map { pool.submit { m.evaluate(t) } }; drains.forEach { it.get(60, TimeUnit.SECONDS) }
        m.drain(); pool.shutdown()
        assertEquals(1, alerts.openAlerts().size)
        assertEquals(1, recorder.got.size)
        assertEquals(200, stored(t, AuditActions.SECURITY_TENANT_MISMATCH), "kein Ereignis geht verloren")
    }

    @Test fun `alarm_ohne_erreichbaren_empfaenger_wird_als_zustellfehler_sichtbar`() {
        val reg = SimpleMeterRegistry(); val lines = CopyOnWriteArrayList<String>()
        val failing = OperatorAlertService({ throw java.io.IOException("Empfänger nicht erreichbar") }, reg, { lines.add(it) })
        val m = SecurityIncidentMonitor(s.tx, failing, IncidentRules.defaults()); val t = freshTenant()
        report(port(m), t, AuditActions.SECURITY_PAYLOAD_HASH_MISMATCH, 1); m.drain()
        assertEquals(1, failing.pendingDeliveries(), "Mitteilung bleibt in der Warteschlange")
        assertTrue(lines.any { it.contains("operator_alert_delivery_failed") }, lines.toString())
        assertEquals(1.0, reg.counter("taktovia_operator_alert_deliveries_total", "result", "failed").count())
    }

    @Test fun `ereignis_im_zurueckgerollten_vorgang_loest_keine_auswertung_aus`() {
        val m = monitor(); val p = port(m); val t = freshTenant()
        runCatching { s.tx.run(t) { j -> p.report(j, SecurityEvent(ActorRef.system("test"), AuditActions.SECURITY_PAYLOAD_HASH_MISMATCH, ObjectRef("event", "x1"), UUID.randomUUID())); error("Rollback") } }
        assertEquals(emptyMap<TenantId, List<String>>(), m.drain())
        assertEquals(0, stored(t, AuditActions.SECURITY_PAYLOAD_HASH_MISMATCH))
    }
}
