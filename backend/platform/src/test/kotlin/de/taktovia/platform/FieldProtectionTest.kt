package de.taktovia.platform

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import com.zaxxer.hikari.HikariDataSource
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextRevokedException
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.fieldprotection.EnvelopeCipher
import de.taktovia.platform.fieldprotection.FieldAccess
import de.taktovia.platform.fieldprotection.FieldAccessException
import de.taktovia.platform.fieldprotection.FieldAccessRoleGuard
import de.taktovia.platform.fieldprotection.FieldDecryptionException
import de.taktovia.platform.fieldprotection.FieldRights
import de.taktovia.platform.fieldprotection.KekProvider
import de.taktovia.platform.fieldprotection.ProtectedColumnStore
import de.taktovia.platform.fieldprotection.ProtectedColumns
import de.taktovia.platform.fieldprotection.ProtectedFields
import de.taktovia.platform.fieldprotection.SupportNeeds
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.OutboxEvent
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * WP-M1-31 / LH-6.18-SEC-005: Feldschutz für Mobilitäts- und Unterstützungsmerkmale gegen die echte Datenbank. Die Fachtransaktion läuft
 * unter der Runtime-Rolle, der Zugriff auf die Ciphertext-Spalte über den getrennten Modulzugang `taktovia_fields`. Als Ersatz für den noch
 * nicht gebauten Fahrgastprofil-Endpunkt (WP-M1-29) wird der Baustein [FieldAccess] direkt aufgerufen; es gibt in M1 keinen Endpunkt, der das
 * Merkmal ausgibt (belegt zusätzlich der OpenAPI-Lint tests/acceptance/security/test_feldschutz_api.py).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FieldProtectionTest {
    /** Wie `JdbcContextRevalidator` des Moduls identity (hier nachgebaut, die Plattform kennt kein Fachmodul); optional mit Haltepunkt nach der Sperre. */
    private class TestRevalidator : ContextRevalidator {
        @Volatile var afterLock: ((RequestContext) -> Unit)? = null
        override fun revalidate(jdbc: JdbcOperations, ctx: RequestContext, lock: Boolean) {
            val ids = ctx.membershipIds
            if (ids.isEmpty()) throw ContextRevokedException()
            val active = jdbc.queryForObject(
                "SELECT count(*) FROM (SELECT 1 FROM taktovia.membership m WHERE m.id = ANY(CAST(? AS uuid[])) AND m.revoked_at IS NULL ${if (lock) "FOR SHARE OF m" else ""}) x",
                Int::class.java, ids.joinToString(",", "{", "}"))
            if (active != ids.size) throw ContextRevokedException()
            afterLock?.invoke(ctx)
        }
    }

    private class Keys(override val currentKeyId: String, private val keys: Map<String, ByteArray>) : KekProvider {
        override fun kek(keyId: String) = keys[keyId] ?: error("unbekannte Schlüsselkennung")
    }

    private val roles = listOf("passenger", "driver", "callcenter", "support", "dispatcher", "planner", "tenant_admin", "finance", "staff_planning", "workshop")
    private val allScopes = setOf("passengers:support:read", "passengers:contact:read", "payments:reference:read")
    private val needsA = SupportNeeds.of(listOf("WHEELCHAIR_SPACE", "ASSISTANCE_ANIMAL"))
    private val needsB = SupportNeeds.of(listOf("BOARDING_HELP"))

    private lateinit var runtime: HikariDataSource
    private lateinit var fieldPool: HikariDataSource
    private lateinit var admin: JdbcTemplate
    private lateinit var tx: TenantTransactions
    private lateinit var fieldTx: TenantTransactions
    private lateinit var revalidator: TestRevalidator
    private lateinit var cipher: EnvelopeCipher
    private lateinit var store: ProtectedColumnStore
    private lateinit var access: FieldAccess
    private val meters = SimpleMeterRegistry()
    private val kek = ByteArray(32) { (it * 7 + 3).toByte() }
    private val tenantA = TenantId(UUID.randomUUID()); private val tenantB = TenantId(UUID.randomUUID())
    private val passengerA = UUID.randomUUID(); private val passengerB = UUID.randomUUID(); private val passengerA2 = UUID.randomUUID()

    @BeforeAll fun setup() {
        TestDatabase.require()
        admin = JdbcTemplate(TestDatabase.admin())
        for (t in listOf(tenantA, tenantB)) admin.update("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?, 'ACTIVE')", t.value, "feldschutz-$t")
        runtime = TestDatabase.runtime(poolSize = 24); fieldPool = TestDatabase.fieldAccess(poolSize = 24)
        tx = TenantTransactions(runtime); fieldTx = TenantTransactions(fieldPool)
        revalidator = TestRevalidator()
        cipher = EnvelopeCipher(Keys("v1", mapOf("v1" to kek)))
        store = ProtectedColumnStore(cipher)
        val audit = JdbcAuditPort()
        access = FieldAccess(ContextTransactions(tx, revalidator), fieldTx, FieldRights.load(), store, audit, SecurityEventPort(audit), meters)
        seedPassenger(tenantA, passengerA, needsA); seedPassenger(tenantA, passengerA2, null); seedPassenger(tenantB, passengerB, needsB)
    }

    @AfterAll fun teardown() { if (::runtime.isInitialized) runtime.close(); if (::fieldPool.isInitialized) fieldPool.close() }

    private fun seedPassenger(t: TenantId, id: UUID, needs: SupportNeeds?) {
        tx.run(t) { j -> j.update("INSERT INTO taktovia.passenger_private(tenant_id,id,subject_token,retention_until) VALUES (taktovia.current_tenant(),?,?,now() + interval '1 year')", id, "tok-$id") }
        if (needs != null) fieldTx.run(t) { j -> assertTrue(store.write(j, t.value, ProtectedColumns.SUPPORT_NEEDS, id, needs.toBytes())) }
    }

    private fun membership(t: TenantId, role: String, subject: String): UUID {
        val id = UUID.randomUUID()
        tx.run(t) { j -> j.update("INSERT INTO taktovia.membership(tenant_id,id,issuer,subject,role_code) VALUES (taktovia.current_tenant(),?,?,?,?)", id, "https://idp.test", subject, role) }
        return id
    }

    private fun humanCtx(t: TenantId, vararg roleCodes: String, scopes: Set<String> = allScopes): RequestContext {
        val subject = "sub-" + UUID.randomUUID()
        val ids = roleCodes.map { membership(t, it, subject) }.toSet()
        return RequestContext(Actor(ActorType.HUMAN, "https://idp.test", subject, null, true), t, roleCodes.toSet(), scopes, emptySet(), UUID.randomUUID(), membershipIds = ids)
    }

    private fun serviceCtx(t: TenantId, scopes: Set<String> = allScopes) =
        RequestContext(Actor(ActorType.SERVICE, "https://idp.test", "adapter-psp", null, false), t, emptySet(), scopes, emptySet(), UUID.randomUUID())

    private fun count(sql: String, vararg args: Any?) = admin.queryForObject(sql, Int::class.java, *args)!!
    private fun readAudits(passenger: UUID) = count("SELECT count(*) FROM taktovia.audit_event WHERE object_ref = ? AND action = 'passenger.support_needs.read'", "passenger:$passenger")
    private fun denialAudits(passenger: UUID) = count("SELECT count(*) FROM taktovia.audit_event WHERE object_ref = ? AND action = 'security.access.denied'", "passenger:$passenger")
    private fun kind(block: () -> Unit) = assertThrows<FieldAccessException> { block() }.kind

    @Test fun unbeteiligte_rolle_erhaelt_unterstuetzungsmerkmal_nicht() {
        val purposeOf = mapOf("callcenter" to "BOOKING_ASSISTANCE", "support" to "SUPPORT_CASE", "dispatcher" to "TRANSPORT_PLANNING")
        val readsBefore = readAudits(passengerA)
        for (role in roles) {
            val ctx = humanCtx(tenantA, role)   // jede Rolle der Rechtematrix, auch mit allen Feldscopes (stärkster Fall)
            val purpose = purposeOf[role] ?: "SUPPORT_CASE"
            if (role in purposeOf) {
                assertEquals(needsA, access.readSupportNeeds(ctx, passengerA, purpose), "berechtigte Rolle $role")
            } else {
                assertEquals(FieldAccessException.Kind.DENIED, kind { access.readSupportNeeds(ctx, passengerA, purpose) }, "Rolle $role ohne Feldrecht")
                for (p in FieldRights.load().purposes) assertEquals(FieldAccessException.Kind.DENIED, kind { access.readSupportNeeds(ctx, passengerA, p) }, "$role/$p")
            }
        }
        assertEquals(readsBefore + 3, readAudits(passengerA), "nur die drei berechtigten Rollen erzeugen einen Lesezugriff")
        // Tenantadmin ohne gesonderte Zuweisung: kein Zugriff; mit zusätzlicher Fachrolle (gesonderte Zuweisung) Zugriff
        assertEquals(FieldAccessException.Kind.DENIED, kind { access.readSupportNeeds(humanCtx(tenantA, "tenant_admin"), passengerA, "SUPPORT_CASE") })
        assertEquals(needsA, access.readSupportNeeds(humanCtx(tenantA, "tenant_admin", "support"), passengerA, "SUPPORT_CASE"))
        // Berechtigte Rolle, aber der Funktionsscope fehlt (Feldscope): kein Zugriff
        assertEquals(FieldAccessException.Kind.DENIED, kind { access.readSupportNeeds(humanCtx(tenantA, "support", scopes = emptySet()), passengerA, "SUPPORT_CASE") })
        // Dienstkonto mit allen Scopes: kein Recht auf das Feld
        assertEquals(FieldAccessException.Kind.DENIED, kind { access.readSupportNeeds(serviceCtx(tenantA), passengerA, "SUPPORT_CASE") })
        // Richtige Rolle, falscher Zweck für diese Rolle
        assertEquals(FieldAccessException.Kind.DENIED, kind { access.readSupportNeeds(humanCtx(tenantA, "support"), passengerA, "TRANSPORT_PLANNING") })
        // Die Verweigerungen sind als Sicherheitsereignis nachvollziehbar
        assertTrue(denialAudits(passengerA) >= 10)
        // Fahrgast ohne hinterlegten Bedarf: berechtigter Zugriff liefert „keine Angabe“, nicht 404
        assertNull(access.readSupportNeeds(humanCtx(tenantA, "support"), passengerA2, "SUPPORT_CASE"))
    }

    @Test fun zugriff_mit_zweck_ist_im_audit_mit_akteur_und_zweck_nachweisbar() {
        val ctx = humanCtx(tenantA, "callcenter")
        assertEquals(needsA, access.readSupportNeeds(ctx, passengerA, "BOOKING_ASSISTANCE"))
        val row = admin.queryForMap("SELECT actor_ref, action, redacted_changes::text AS changes, correlation_id FROM taktovia.audit_event WHERE correlation_id = ? AND action = 'passenger.support_needs.read'", ctx.correlationId)
        assertEquals("user:${ctx.actor.subject}", row["actor_ref"])
        assertTrue(row["changes"].toString().contains("BOOKING_ASSISTANCE"), row["changes"].toString())
        assertFalse(row["changes"].toString().contains("WHEELCHAIR"), "kein Wert im Audit")
    }

    @Test fun fehlender_zweck_und_fremdmandant_werden_abgewiesen() {
        val ctx = humanCtx(tenantA, "support")
        for (p in listOf(null, "", "   ")) assertEquals(FieldAccessException.Kind.PURPOSE_REQUIRED, kind { access.readSupportNeeds(ctx, passengerA, p) }, "Zweck '$p'")
        assertEquals(FieldAccessException.Kind.DENIED, kind { access.readSupportNeeds(ctx, passengerA, "ERFUNDENER_ZWECK") })
        val before = readAudits(passengerA)
        // Fremdmandant: Fahrgast von A mit Kontext von B (richtige Rolle, richtiger Zweck) -> nicht gefunden, kein Leseeintrag, keine Daten
        val foreign = humanCtx(tenantB, "support")
        assertEquals(FieldAccessException.Kind.NOT_FOUND, kind { access.readSupportNeeds(foreign, passengerA, "SUPPORT_CASE") })
        assertEquals(before, readAudits(passengerA)); assertEquals(0, count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND object_ref = ?", tenantB.value, "passenger:$passengerA"))
        // und umgekehrt der eigene Mandant B liefert sein eigenes Merkmal
        assertEquals(needsB, access.readSupportNeeds(foreign, passengerB, "SUPPORT_CASE"))
        // Nicht existierender Fahrgast im eigenen Mandanten: ebenfalls NOT_FOUND, ohne Leseeintrag
        val ghost = UUID.randomUUID()
        assertEquals(FieldAccessException.Kind.NOT_FOUND, kind { access.readSupportNeeds(ctx, ghost, "SUPPORT_CASE") })
        assertEquals(0, readAudits(ghost))
    }

    @Test fun merkmal_nicht_in_logs_metriken_outbox() {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val old = root.level
        root.addAppender(appender); root.level = Level.DEBUG
        val ctxs = listOf(humanCtx(tenantA, "support"), humanCtx(tenantA, "planner"), humanCtx(tenantA, "tenant_admin"), humanCtx(tenantB, "support"))
        try {
            for (c in ctxs) runCatching { access.readSupportNeeds(c, passengerA, "SUPPORT_CASE") }
            // Standardantwort (kein Zweck): die Projektion lässt das Feld weg; ein daraus gebauter Ereignis-Payload enthält es nicht.
            val rights = FieldRights.load()
            val projected = rights.project(ctxs[0], ProtectedFields.SUPPORT_NEEDS, null, needsA.codes.toList())
            assertNull(projected)
            tx.run(tenantA) { j -> Outbox().append(j, OutboxEvent(passengerA, 1, "passenger.profile_changed", mapOf("passenger_id" to passengerA.toString(), "support_needs" to projected), Instant.now())) }
        } finally { root.detachAppender(appender); root.level = old }
        val forbidden = SupportNeeds.CATALOG   // die Werte; der Spaltenname im SQL-Debuglog ist kein Merkmal
        val logLines = appender.list.map { e -> e.formattedMessage + " " + (e.argumentArray?.joinToString(" ") ?: "") + " " + (e.throwableProxy?.let { ThrowableProxyUtil.asString(it) } ?: "") }
        assertTrue(logLines.isNotEmpty(), "ohne mitgeschnittene Logzeilen belegt der Scan nichts")
        val metricText = meters.meters.joinToString(" ") { m -> m.id.name + " " + m.id.tags.joinToString(" ") { "${it.key}=${it.value}" } }
        assertTrue(metricText.contains("taktovia.field.access"), metricText)
        val outbox = count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND aggregate_id = ?", tenantA.value, passengerA)
        assertEquals(1, outbox)
        val outboxText = admin.queryForList("SELECT payload::text FROM taktovia.outbox WHERE tenant_id = ?", String::class.java, tenantA.value).joinToString(" ")
        val auditText = admin.queryForList("SELECT redacted_changes::text FROM taktovia.audit_event WHERE tenant_id IN (?,?)", String::class.java, tenantA.value, tenantB.value).joinToString(" ")
        for (code in forbidden) {
            assertFalse(logLines.any { it.contains(code) }, "Log enthält $code")
            assertFalse(metricText.contains(code), "Metrik enthält $code")
            assertFalse(outboxText.contains(code), "Outbox enthält $code")
            assertFalse(auditText.contains(code), "Audit enthält $code")
        }
    }

    @Test fun ciphertext_ist_im_ruhezustand_nicht_klartext() {
        val raw = admin.queryForObject("SELECT support_ciphertext FROM taktovia.passenger_private WHERE tenant_id = ? AND id = ?", ByteArray::class.java, tenantA.value, passengerA)!!
        val text = String(raw, Charsets.ISO_8859_1)
        for (code in needsA.codes + listOf("codes", "WHEELCHAIR")) assertFalse(text.contains(code), "Klartext $code im Ruhezustand")
        assertEquals(1, raw[0].toInt()); assertEquals("v1", String(raw, 2, raw[1].toInt(), Charsets.US_ASCII))
        // zufällige Nonce: gleicher Klartext ergibt jedes Mal anderen Ciphertext
        val ctx = EnvelopeCipher.context(tenantA.value, ProtectedColumns.SUPPORT_NEEDS, passengerA)
        assertNotEquals(cipher.encrypt(needsA.toBytes(), ctx).toList(), cipher.encrypt(needsA.toBytes(), ctx).toList())
        assertArrayEquals(needsA.toBytes(), cipher.decrypt(raw, ctx))
        // an Mandant, Spalte und Zeile gebunden: ein kopierter Wert lässt sich anderswo nicht entschlüsseln
        assertThrows<FieldDecryptionException> { cipher.decrypt(raw, EnvelopeCipher.context(tenantA.value, ProtectedColumns.SUPPORT_NEEDS, passengerA2)) }
        assertThrows<FieldDecryptionException> { cipher.decrypt(raw, EnvelopeCipher.context(tenantB.value, ProtectedColumns.SUPPORT_NEEDS, passengerA)) }
        assertThrows<FieldDecryptionException> { cipher.decrypt(raw, EnvelopeCipher.context(tenantA.value, ProtectedColumns.CONTACT, passengerA)) }
        // Manipulation und falscher Schlüssel
        assertThrows<FieldDecryptionException> { cipher.decrypt(raw.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }, ctx) }
        assertThrows<FieldDecryptionException> { EnvelopeCipher(Keys("v1", mapOf("v1" to ByteArray(32)))).decrypt(raw, ctx) }
        // Rotation: neuer KEK v2 wird aktiv, alte Werte bleiben lesbar und lassen sich auf v2 umschlüsseln, der Klartext bleibt gleich
        val rotated = EnvelopeCipher(Keys("v2", mapOf("v1" to kek, "v2" to ByteArray(32) { 9 })))
        assertEquals("v1", rotated.keyIdOf(raw))
        val rewrapped = rotated.rewrap(raw, ctx)
        assertEquals("v2", rotated.keyIdOf(rewrapped))
        assertArrayEquals(needsA.toBytes(), rotated.decrypt(rewrapped, ctx))
        assertThrows<FieldDecryptionException> { EnvelopeCipher(Keys("v1", mapOf("v1" to kek))).decrypt(rewrapped, ctx) }   // v2 unbekannt -> Fehler statt Klartext
    }

    @Test fun keine_diagnosen_nur_katalogwerte() {
        for (bad in listOf("DIABETES", "Parkinson", "rollstuhl, gelähmt", "")) assertThrows<IllegalArgumentException>(bad) { SupportNeeds.of(listOf(bad)) }
        val e = assertThrows<IllegalArgumentException> { SupportNeeds.of(listOf("DIABETES")) }
        assertFalse(e.message!!.contains("DIABETES"), "Fehlertext wiederholt die Eingabe nicht")
        assertEquals(SupportNeeds.of(listOf("LOW_FLOOR")), SupportNeeds.fromBytes(SupportNeeds.of(listOf("LOW_FLOOR")).toBytes()))
    }

    @Test fun datenbankverbindungen_haben_die_vorgesehenen_rechte() {
        assertEquals(emptyList<String>(), FieldAccessRoleGuard.problems(fieldPool, runtime))
        assertTrue(FieldAccessRoleGuard.problems(runtime, fieldPool).size >= 2, "vertauschte Verbindungen werden erkannt")
    }

    @Test fun zwei_mandanten_lesen_parallel_ohne_vermischung() {
        val ctxA = (1..4).map { humanCtx(tenantA, "support") }; val ctxB = (1..4).map { humanCtx(tenantB, "support") }
        val start = CountDownLatch(1); val exec = Executors.newFixedThreadPool(8)
        try {
            val jobs = (0 until 8).map { i ->
                exec.submit(Callable {
                    start.await()
                    repeat(25) { n ->
                        if (i % 2 == 0) assertEquals(needsA, access.readSupportNeeds(ctxA[i / 2], passengerA, "SUPPORT_CASE"), "A Lauf $n")
                        else assertEquals(needsB, access.readSupportNeeds(ctxB[i / 2], passengerB, "SUPPORT_CASE"), "B Lauf $n")
                        // jeder Versuch über die Mandantengrenze bleibt 404
                        if (n % 5 == 0) {
                            val k = if (i % 2 == 0) kind { access.readSupportNeeds(ctxA[i / 2], passengerB, "SUPPORT_CASE") } else kind { access.readSupportNeeds(ctxB[i / 2], passengerA, "SUPPORT_CASE") }
                            assertEquals(FieldAccessException.Kind.NOT_FOUND, k)
                        }
                    }
                })
            }
            start.countDown(); jobs.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally { exec.shutdownNow() }
    }

    @Test fun feldrecht_entzug_parallel_zum_lesen() {
        // 1. Ein laufender Lesezugriff hält die Mitgliedschaft gesperrt: der Entzug wartet, danach scheitert jeder Zugriff.
        val ctx = humanCtx(tenantA, "support")
        val membershipId = ctx.membershipIds.single()
        val inside = CountDownLatch(1); val release = CountDownLatch(1); val revokeDone = AtomicBoolean(false)
        val firstRead = AtomicBoolean(true)
        revalidator.afterLock = { c -> if (c === ctx && firstRead.getAndSet(false)) { inside.countDown(); release.await(20, TimeUnit.SECONDS) } }
        val exec = Executors.newFixedThreadPool(2)
        try {
            val reader = exec.submit(Callable { access.readSupportNeeds(ctx, passengerA, "SUPPORT_CASE") })
            assertTrue(inside.await(10, TimeUnit.SECONDS))
            val revoker = exec.submit { tx.run(tenantA) { j -> j.update("UPDATE taktovia.membership SET revoked_at = now() WHERE id = ?", membershipId) }; revokeDone.set(true) }
            Thread.sleep(700)
            assertFalse(revokeDone.get(), "der Entzug wartet auf den laufenden Lesezugriff (FOR SHARE)")
            release.countDown()
            assertEquals(needsA, reader.get(20, TimeUnit.SECONDS), "der begonnene Zugriff liegt vor dem Entzug und ist vollständig")
            revoker.get(20, TimeUnit.SECONDS)
            assertTrue(revokeDone.get())
            // 2. Nach dem Commit des Entzugs ist jeder Zugriff verweigert.
            assertThrows<ContextRevokedException> { access.readSupportNeeds(ctx, passengerA, "SUPPORT_CASE") }
        } finally { revalidator.afterLock = null; exec.shutdownNow() }

        // 3. Unter Last: jede Anfrage, die nach dem Commit des Entzugs beginnt, scheitert; keine erfolgt danach erfolgreich.
        val victim = humanCtx(tenantA, "support"); val victimMembership = victim.membershipIds.single()
        val committedAt = AtomicReference<Long?>(null)
        val pool = Executors.newFixedThreadPool(12)
        val stop = AtomicBoolean(false)
        try {
            val readers = (1..8).map {
                pool.submit(Callable {
                    val violations = mutableListOf<String>(); var ok = 0
                    while (!stop.get()) {
                        val started = System.nanoTime(); val committedBefore = committedAt.get()
                        val success = runCatching { access.readSupportNeeds(victim, passengerA, "SUPPORT_CASE") }.fold({ true }, { if (it is ContextRevokedException) false else throw it })
                        if (success) ok++
                        if (success && committedBefore != null && started > committedBefore) violations += "Zugriff nach Commit des Entzugs erfolgreich"
                    }
                    Pair(ok, violations)
                })
            }
            Thread.sleep(400)
            tx.run(tenantA) { j -> j.update("UPDATE taktovia.membership SET revoked_at = now() WHERE id = ?", victimMembership) }
            committedAt.set(System.nanoTime())
            Thread.sleep(600); stop.set(true)
            val results = readers.map { it.get(30, TimeUnit.SECONDS) }
            assertTrue(results.sumOf { it.first } > 0, "vor dem Entzug gab es erfolgreiche Zugriffe")
            assertEquals(emptyList<String>(), results.flatMap { it.second })
        } finally { stop.set(true); pool.shutdownNow() }
        assertThrows<ContextRevokedException> { access.readSupportNeeds(victim, passengerA, "SUPPORT_CASE") }
    }
}
