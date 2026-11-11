package de.taktovia.platform.authz

import com.zaxxer.hikari.HikariDataSource
import de.taktovia.platform.TestDatabase
import de.taktovia.platform.api.CursorCodec
import de.taktovia.platform.api.CursorPosition
import de.taktovia.platform.api.CursorScope
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcOperations
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Testrepository mit Dienstbezug: Flächen eines Dienstes (`service_area`, Kernschema, `service_id`) werden **nur über den Port** gelesen und geändert. Es steht für
 * das echte Buchungsaggregat, den Nachweis dort liefert WP-M2-23 über denselben Port.
 */
@ServiceScoped
class ScopedAreaRepository(private val ctxTx: ContextTransactions, private val authorizer: ObjectAuthorizer) {
    class Row(val id: UUID, val serviceId: UUID, val revision: Long)

    fun get(ctx: RequestContext, id: UUID): Row = ctxTx.run(ctx, readOnly = true) { j ->
        val row = j.query("SELECT id, service_id, revision FROM taktovia.service_area WHERE id = ?", { rs, _ -> Row(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getLong(3)) }, id).firstOrNull()
            ?: throw ProblemException.notFound()
        authorizer.requireRead(j, ctx, ScopedObject("service_area", row.id, ctx.tenant, row.serviceId))
        row
    }

    /** Ändert die Fläche (Revision +1) nach der Prüfung in derselben Transaktion; [beforeWrite] liegt zwischen Prüfung und Schreiben (Test der Nebenläufigkeit). */
    fun touch(ctx: RequestContext, id: UUID, beforeWrite: () -> Unit = {}): Row = ctxTx.run(ctx) { j ->
        val row = j.query("SELECT id, service_id, revision FROM taktovia.service_area WHERE id = ?", { rs, _ -> Row(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getLong(3)) }, id).firstOrNull()
            ?: throw ProblemException.notFound()
        authorizer.requireWrite(j, ctx, ScopedObject("service_area", row.id, ctx.tenant, row.serviceId))
        beforeWrite()
        j.update("UPDATE taktovia.service_area SET revision = revision + 1 WHERE id = ?", id)
        Row(row.id, row.serviceId, row.revision + 1)
    }

    /** Liste: `requested` ist ein Filterwunsch auf Dienste; der Dienstfilter des Ports schneidet ihn mit dem Dienstscope. */
    fun list(ctx: RequestContext, limit: Int, cursor: String?, codec: CursorCodec, requested: Set<UUID>? = null): Pair<List<Row>, String?> {
        val services = authorizer.effectiveServices(ctx, requested)
        val scope = CursorScope(ctx.tenant.toString(), ctx.actor.subject, "areas:" + ServiceViews.fingerprint(services))
        val after = cursor?.let { codec.decode(it, scope) }
        if (services != null && services.isEmpty()) return emptyList<Row>() to null
        val rows = ctxTx.run(ctx, readOnly = true) { j ->
            j.query("SELECT id, service_id, revision, valid_from FROM taktovia.service_area WHERE service_id = ANY(CAST(? AS uuid[])) AND (CAST(? AS timestamptz) IS NULL OR (valid_from, id) > (CAST(? AS timestamptz), CAST(? AS uuid))) ORDER BY valid_from, id LIMIT ?",
                { rs, _ -> Triple(Row(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getLong(3)), rs.getTimestamp(4).toInstant(), 0) },
                services!!.joinToString(",", "{", "}"), after?.createdAt?.let(Timestamp::from), after?.createdAt?.let(Timestamp::from), after?.id, limit + 1)
        }
        val items = rows.take(limit)
        val next = if (rows.size > limit) codec.encode(CursorPosition(items.last().second, items.last().first.id), scope) else null
        return items.map { it.first } to next
    }
}

class ObjectAuthorizationTest {
    private companion object {
        val admin by lazy { TestDatabase.require(); TestDatabase.admin() }
        val runtime: HikariDataSource by lazy { TestDatabase.require(); TestDatabase.runtime(8) }
        val tx by lazy { TenantTransactions(runtime) }
        val ctxTx by lazy { ContextTransactions(tx, object : ContextRevalidator { override fun revalidate(jdbc: JdbcOperations, ctx: RequestContext, lock: Boolean) = Unit }) }
        val repo by lazy { ScopedAreaRepository(ctxTx, ServiceScopeAuthorizer) }
        val codec = CursorCodec(ByteArray(32) { it.toByte() })
        @JvmStatic @AfterAll fun close() { runtime.close() }

        fun exec(sql: String, vararg a: Any?) = admin.connection.use { c ->
            c.autoCommit = true
            c.createStatement().use { it.execute("SET session_replication_role = replica") }   // Testobjekte ohne vollständige Fremdschlüsselkette (Dienst, Betreiber)
            c.prepareStatement(sql).use { ps -> a.forEachIndexed { i, x -> ps.setObject(i + 1, x) }; ps.executeUpdate() }
        }
        fun count(sql: String, vararg a: Any?) = admin.connection.use { c -> c.prepareStatement(sql).use { ps -> a.forEachIndexed { i, x -> ps.setObject(i + 1, x) }; ps.executeQuery().use { it.next(); it.getInt(1) } } }
    }

    /** Ein Mandant mit zwei Regionen: Region 1 = Dienste [s1a, s1b], Region 2 = Dienst [s2]; je eine Fläche je Dienst; ein Disponent mit Mitgliedschaft für Region 1. */
    private inner class World {
        val tenant = TenantId(UUID.randomUUID())
        val s1a = UUID.randomUUID(); val s1b = UUID.randomUUID(); val s2 = UUID.randomUUID()
        val a1a = UUID.randomUUID(); val a1b = UUID.randomUUID(); val a2 = UUID.randomUUID()
        val membership = UUID.randomUUID()
        val subject = UUID.randomUUID().toString()
        init {
            exec("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?,'ACTIVE')", tenant.value, "t-${tenant.value}")
            for ((a, s) in listOf(a1a to s1a, a1b to s1b, a2 to s2))
                exec("INSERT INTO taktovia.service_area(tenant_id,id,service_id,revision,area,valid_from) VALUES (?,?,?,1,ST_Multi(ST_GeomFromText('POLYGON((0 0,0 1,1 1,1 0,0 0))',4326)),?)", tenant.value, a, s, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(listOf(a1a, a1b, a2).indexOf(a).toLong())))
            exec("INSERT INTO taktovia.membership(tenant_id,id,issuer,subject,role_code,service_scope) VALUES (?,?,'https://idp.test/realms/authz',?,'dispatcher',CAST(? AS uuid[]))", tenant.value, membership, subject, "{$s1a,$s1b}")
        }
        fun ctx(services: Set<UUID> = setOf(s1a, s1b), tenant: TenantId = this.tenant, membershipIds: Set<UUID> = setOf(membership)) =
            RequestContext(Actor(ActorType.HUMAN, "https://idp.test/realms/authz", subject, "sid", true), tenant, setOf("dispatcher"), setOf("config:read"), services, UUID.randomUUID(), membershipIds = membershipIds)
        fun setScope(scope: String?) { exec("UPDATE taktovia.membership SET service_scope = CAST(? AS uuid[]) WHERE id = ?", scope, membership) }
    }

    @Test
    fun disponent_eigene_region_bearbeitet() {
        val w = World(); val ctx = w.ctx()
        assertEquals(w.s1a, repo.get(ctx, w.a1a).serviceId)
        assertEquals(2L, repo.touch(ctx, w.a1a).revision); assertEquals(2L, repo.touch(ctx, w.a1b).revision)
        assertEquals(setOf(w.a1a, w.a1b), repo.list(ctx, 10, null, codec).first.map { it.id }.toSet())
        assertTrue(ServiceScopeAuthorizer.canRead(ctx, ScopedObject("service_area", w.a1a, w.tenant, w.s1a)) && ServiceScopeAuthorizer.canWrite(ctx, ScopedObject("service_area", w.a1a, w.tenant, w.s1a)))
    }

    @Test
    fun disponent_fremde_region_ergibt_404() {
        val w = World(); val ctx = w.ctx()
        // Detail- und Änderungszugriff auf ein Objekt der Region 2 desselben Mandanten: 404, die Zeile bleibt unverändert
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { repo.get(ctx, w.a2) }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { repo.touch(ctx, w.a2) }.code)
        assertEquals(1, count("SELECT revision FROM taktovia.service_area WHERE id = ?", w.a2))
        // dasselbe Fehlerbild wie bei einer nicht vorhandenen Kennung (kein Hinweis auf Existenz)
        val missing = assertThrows<ProblemException> { repo.get(ctx, UUID.randomUUID()) }
        assertEquals(ErrorCode.NOT_FOUND, missing.code)
        // Liste: Region 2 erscheint nie
        assertTrue(repo.list(ctx, 10, null, codec).first.none { it.id == w.a2 })
        assertFalse(ServiceScopeAuthorizer.canRead(ctx, ScopedObject("service_area", w.a2, w.tenant, w.s2)))
        assertFalse(ServiceScopeAuthorizer.canWrite(ctx, ScopedObject("service_area", w.a2, w.tenant, w.s2)))
    }

    @Test
    fun fremder_dienst_per_listenfilter_leere_liste() {
        val w = World(); val ctx = w.ctx()
        assertEquals(emptyList<UUID>(), repo.list(ctx, 10, null, codec, requested = setOf(w.s2)).first.map { it.id })     // Filter auf fremden Dienst: leer, kein Fehler
        assertEquals(listOf(w.a1a), repo.list(ctx, 10, null, codec, requested = setOf(w.s1a, w.s2)).first.map { it.id })   // Filter erweitert den Scope nicht
        assertEquals(setOf(w.s1a), ServiceScopeAuthorizer.effectiveServices(ctx, setOf(w.s1a, w.s2)))
        assertEquals(setOf(w.s1a, w.s1b), ServiceScopeAuthorizer.effectiveServices(ctx, null))
        assertNull(ServiceScopeAuthorizer.effectiveServices(ctx, null, tenantWide = true))
    }

    @Test
    fun sortierung_cursor_fremde_sicht_wird_abgewiesen_ohne_daten() {
        val w = World(); val broad = w.ctx(setOf(w.s1a, w.s1b, w.s2)); val narrow = w.ctx(setOf(w.s1a, w.s1b))
        val (page1, cursor) = repo.list(broad, 1, null, codec)
        assertEquals(1, page1.size); val c = cursor!!
        // ein Cursor aus der breiteren Sicht gilt in der engeren nicht (400 INVALID_CURSOR, keine Daten, kein Objekt außerhalb des Scopes)
        assertEquals(ErrorCode.INVALID_CURSOR, assertThrows<ProblemException> { repo.list(narrow, 1, c, codec) }.code)
        // derselbe Cursor gilt in seiner eigenen Sicht und liefert die Folgeseite
        assertEquals(1, repo.list(broad, 1, c, codec).first.size)
        // die Reihenfolge ist fest (Beginn, Kennung): Sortierung einer anderen Sicht gibt es nicht; die enge Sicht sieht nie die Fläche der Region 2
        assertTrue(generateSequence(repo.list(narrow, 1, null, codec)) { (_, next) -> next?.let { repo.list(narrow, 1, it, codec) } }.flatMap { it.first }.none { it.id == w.a2 })
    }

    @Test
    fun fremder_mandant_404_unabhaengig_vom_scope() {
        val a = World(); val b = World()
        // Kontext des Mandanten A mit einem Scope, der den Dienst des Mandanten B enthält: das Objekt des Mandanten B bleibt unsichtbar (RLS) und im Port verweigert
        val ctx = a.ctx(services = setOf(a.s1a, b.s1a))
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { repo.get(ctx, b.a1a) }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { repo.touch(ctx, b.a1a) }.code)
        assertFalse(ServiceScopeAuthorizer.canRead(ctx, ScopedObject("service_area", b.a1a, b.tenant, b.s1a)))
        assertFalse(ServiceScopeAuthorizer.canWrite(ctx, ScopedObject("service_area", b.a1a, b.tenant, b.s1a)))
        assertEquals(1, count("SELECT revision FROM taktovia.service_area WHERE id = ?", b.a1a))
    }

    @Test
    fun service_scope_null_verweigert() {
        val w = World(); w.setScope(null)
        val ctx = w.ctx(services = emptySet())
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { repo.get(ctx, w.a1a) }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { repo.touch(ctx, w.a1a) }.code)
        assertTrue(repo.list(ctx, 10, null, codec).first.isEmpty())
        // die leere Liste ist ebenfalls „kein Dienstzugriff“; ein Objekt ohne Dienstbezug unterliegt nur der Mandantengrenze
        assertFalse(ServiceScopeAuthorizer.canRead(ctx, ScopedObject("service_area", w.a1a, w.tenant, w.s1a)))
        assertTrue(ServiceScopeAuthorizer.canRead(ctx, ScopedObject("tenant_note", UUID.randomUUID(), w.tenant, null)))
        // veraltete Momentaufnahme: der Kontext nennt den Dienst noch, die Mitgliedschaft in der Datenbank nicht mehr -> verweigert
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { repo.touch(w.ctx(), w.a1a) }.code)
    }

    @Test
    fun scope_entzug_parallel_zu_aenderung() {
        // (1) Entzug committet vor der Änderung: die Änderung wird abgewiesen, obwohl der Kontext den Dienst noch nennt
        val w = World(); val ctx = w.ctx()
        w.setScope("{${w.s1b}}")
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { repo.touch(ctx, w.a1a) }.code)
        assertEquals(1, count("SELECT revision FROM taktovia.service_area WHERE id = ?", w.a1a))
        // (2) Entzug parallel: die laufende Änderung hält die Mitgliedschaft; der Entzug wartet bis zu ihrem Ende, danach ist jede weitere Änderung abgewiesen
        w.setScope("{${w.s1a},${w.s1b}}")
        val pool = Executors.newFixedThreadPool(2); val checked = CountDownLatch(1); val proceed = CountDownLatch(1)
        val change = pool.submit<Long> { repo.touch(ctx, w.a1a) { checked.countDown(); proceed.await(30, TimeUnit.SECONDS) }.revision }
        assertTrue(checked.await(30, TimeUnit.SECONDS))
        val revoke = pool.submit { w.setScope("{${w.s1b}}") }
        Thread.sleep(500)
        assertFalse(revoke.isDone, "der Entzug wartet, solange die Änderung die Mitgliedschaft hält")
        proceed.countDown()
        assertEquals(2L, change.get(30, TimeUnit.SECONDS)); revoke.get(30, TimeUnit.SECONDS); pool.shutdown()
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { repo.touch(ctx, w.a1a) }.code, "nach dem Commit des Entzugs abgewiesen")
        assertEquals(2, count("SELECT revision FROM taktovia.service_area WHERE id = ?", w.a1a), "genau die eine Änderung vor dem Entzug")
        assertNotEquals(w.s1a, w.s1b)
    }
}
