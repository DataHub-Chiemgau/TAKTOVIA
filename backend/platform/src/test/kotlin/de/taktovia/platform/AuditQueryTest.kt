package de.taktovia.platform

import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditActions
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditFilter
import de.taktovia.platform.audit.AuditPurposes
import de.taktovia.platform.audit.AuditQueryService
import de.taktovia.platform.audit.ObjectRef
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** WP-M1-12 (LH-6.18-SEC-006): Auditabfrage mit Rollen- und Zweckprüfung gegen die echte Datenbank unter der Runtime-Rolle. */
class AuditQueryTest {
    companion object {
        private lateinit var s: AuditTestSupport
        private lateinit var query: AuditQueryService
        @BeforeAll @JvmStatic fun setup() { s = AuditTestSupport(); query = AuditQueryService(s.ctxTx, s.audit) }
        @AfterAll @JvmStatic fun teardown() { if (::s.isInitialized) s.close() }
    }

    private val purpose = AuditPurposes.SECURITY_REVIEW

    @Test fun `abfrage_weist_akteur_zeitpunkt_aktivitaet_aus`() {
        val admin = UUID.randomUUID(); val member = UUID.randomUUID(); val marker = "m" + UUID.randomUUID().toString().take(8)
        val t0 = Instant.parse("2026-03-01T10:00:00Z")
        // Rollenänderung, Login und Konfigurationspublikation, jeweils mit eigenem Zeitpunkt (Ports aus WP-M1-46)
        val role = AuditEntry(ActorRef.user(admin), AuditActions.MEMBERSHIP_GRANTED, ObjectRef("membership", member), UUID.randomUUID(), mapOf("role_code" to "dispatcher"), t0)
        val login = AuditEntry(ActorRef.user(member), AuditActions.SECURITY_LOGIN_SUCCEEDED, ObjectRef("user", member), UUID.randomUUID(), mapOf("result" to "success", "marker" to marker), t0.plusSeconds(60))
        val config = AuditEntry(ActorRef.user(admin), AuditActions.CONFIG_PUBLISHED, ObjectRef("config_revision", "rev-$marker"), UUID.randomUUID(), emptyMap(), t0.plusSeconds(120))
        s.tx.run(s.a) { j -> listOf(role, login, config).forEach { s.audit.record(j, it) } }

        val ctx = s.context(s.a, admin)
        val rows = query.query(ctx, purpose, AuditFilter(from = t0, to = t0.plusSeconds(120)), 50, null)
        assertEquals(listOf(role.action, login.action, config.action), rows.map { it.action })
        val byAction = rows.associateBy { it.action }
        byAction.getValue(role.action).also { assertEquals("user:$admin", it.actorRef); assertEquals("membership:$member", it.objectRef); assertEquals(t0, it.occurredAt); assertEquals(role.correlationId, it.correlationId) }
        byAction.getValue(login.action).also { assertEquals("user:$member", it.actorRef); assertEquals(t0.plusSeconds(60), it.occurredAt); assertEquals("success", it.redactedChanges["result"]) }
        byAction.getValue(config.action).also { assertEquals("config_revision:rev-$marker", it.objectRef); assertEquals(t0.plusSeconds(120), it.occurredAt) }

        // Filter Akteur, Objekt, Aktion, Zeitraum
        assertEquals(listOf(role.action, config.action), query.query(ctx, purpose, AuditFilter(actorRef = "user:$admin", from = t0, to = t0.plusSeconds(120)), 50, null).map { it.action })
        assertEquals(listOf(role.action), query.query(ctx, purpose, AuditFilter(objectRef = "membership:$member"), 50, null).map { it.action })
        assertEquals(listOf(login.action), query.query(ctx, purpose, AuditFilter(action = AuditActions.SECURITY_LOGIN_SUCCEEDED, from = t0), 50, null).map { it.action })
        assertEquals(listOf(config.action), query.query(ctx, purpose, AuditFilter(from = t0.plusSeconds(90), to = t0.plusSeconds(150)), 50, null).map { it.action })

        // Cursor: stabile Folgeseite ab der letzten Position
        val first = query.query(ctx, purpose, AuditFilter(from = t0, to = t0.plusSeconds(120)), 2, null)
        assertEquals(3, first.size, "limit+1 Zeilen zeigen die Folgeseite an")
        val rest = query.query(ctx, purpose, AuditFilter(from = t0, to = t0.plusSeconds(120)), 2, first[1].occurredAt to first[1].id)
        assertEquals(listOf(config.action), rest.map { it.action })

        // Der Auditzugriff selbst wird auditiert: Akteur, Zweck, Filter
        val self = query.query(ctx, purpose, AuditFilter(actorRef = "user:$admin", action = AuditActions.AUDIT_READ), 50, null)
        assertTrue(self.isNotEmpty() && self.all { it.actorRef == "user:$admin" && it.redactedChanges["purpose"] == purpose }, self.toString())
    }

    @Test fun `ohne_berechtigung_ohne_zweck_und_fremdmandant_abgewiesen`() {
        val secretMarker = "geheim-" + UUID.randomUUID().toString().take(8)
        s.tx.run(s.b) { j -> s.audit.record(j, AuditEntry(ActorRef.system("b-only"), "identity.organization.changed", ObjectRef("organization", secretMarker), UUID.randomUUID(), emptyMap())) }
        val ctxA = s.context(s.a)
        val before = query.query(ctxA, purpose, AuditFilter(action = AuditActions.AUDIT_READ), 100, null).size

        // ohne Berechtigung: 403, nichts gelesen, nichts auditiert
        val noRight = s.context(s.a, scopes = setOf("memberships:read"))
        assertEquals(ErrorCode.FORBIDDEN, assertThrows<ProblemException> { query.query(noRight, purpose, AuditFilter(), 10, null) }.code)
        // ohne oder mit unbekanntem Zweck: 422 mit Feldverletzung, nichts gelesen
        for (bad in listOf(null, "", "neugier")) {
            val ex = assertThrows<ProblemException> { query.query(ctxA, bad, AuditFilter(), 10, null) }
            assertEquals(ErrorCode.VALIDATION_FAILED, ex.code, "Zweck=$bad"); assertEquals("/\$query/purpose", ex.violations.single().pointer)
        }
        assertEquals(before + 1, query.query(ctxA, purpose, AuditFilter(action = AuditActions.AUDIT_READ), 100, null).size, "abgewiesene Abfragen hinterlassen keinen Leseeintrag; nur die zwei Zählabfragen")

        // Fremdmandant: A sieht nichts von B, auch nicht mit gezieltem Objektfilter
        assertTrue(query.query(ctxA, purpose, AuditFilter(objectRef = "organization:$secretMarker"), 10, null).isEmpty())
        assertTrue(query.query(ctxA, purpose, AuditFilter(actorRef = "system:b-only"), 10, null).isEmpty())
        assertTrue(query.query(ctxA, purpose, AuditFilter(), 100, null).none { it.objectRef.contains(secretMarker) })
        assertEquals(1, query.query(s.context(s.b), purpose, AuditFilter(objectRef = "organization:$secretMarker"), 10, null).size)
    }

    @Test fun `zwei_mandanten_parallel_ohne_vermischung`() {
        val n = 40
        val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        fun worker(tenant: de.taktovia.platform.tenant.TenantId, tag: String) = Callable {
            val ctx = s.context(tenant); start.await()
            repeat(n) { i -> s.tx.run(tenant) { j -> s.audit.record(j, AuditEntry(ActorRef.system("par-$tag"), "identity.organization.changed", ObjectRef("organization", "$tag-$i"), UUID.randomUUID(), emptyMap())) } }
            val seen = mutableListOf<String>()
            repeat(20) { seen += query.query(ctx, purpose, AuditFilter(), 100, null).map { it.actorRef + "|" + it.objectRef } }
            seen
        }
        val fa = pool.submit(worker(s.a, "ta")); val fb = pool.submit(worker(s.b, "tb")); start.countDown()
        val ra = fa.get(); val rb = fb.get(); pool.shutdown()
        assertTrue(ra.isNotEmpty() && rb.isNotEmpty())
        assertTrue(ra.none { it.contains("par-tb") || it.contains("organization:tb-") }, "Mandant A sah Zeilen von B")
        assertTrue(rb.none { it.contains("par-ta") || it.contains("organization:ta-") }, "Mandant B sah Zeilen von A")
        assertTrue(ra.any { it.contains("par-ta") } && rb.any { it.contains("par-tb") })
    }
}
