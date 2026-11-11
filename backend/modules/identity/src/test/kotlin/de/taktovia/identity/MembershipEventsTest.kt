package de.taktovia.identity

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import de.taktovia.identity.api.GrantMembership
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DuplicateKeyException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M1-06 / LH-6.17-INT-002: Erteilen und Entziehen einer Mitgliedschaft sind auditiert und erzeugen genau ein Outbox-Ereignis,
 * gültig gegen das Ereignisschema; Fehlerfälle hinterlassen nichts (eine Transaktion); paralleles Erteilen derselben Mitgliedschaft
 * ergibt genau eine Zeile und ein Ereignis. Echte Datenbank, Runtime-Rolle.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MembershipEventsTest {
    private lateinit var fx: IdentityFixture
    private val repo = Path.of(System.getProperty("taktovia.repoRoot"))
    private val registry = SchemaRegistry.withDefaultDialect(Dialects.getDraft202012())

    @BeforeAll fun up() { fx = IdentityFixture() }
    @AfterAll fun down() = fx.close()

    private fun schemaErrors(event: String, payload: String): List<String> {
        val schema = registry.getSchema(Files.readString(repo.resolve("api/events/$event.v1.schema.json")))
        return schema.validate(payload, InputFormat.JSON).map { it.toString() }
    }

    private fun payloads(aggregate: UUID, type: String): List<String> = fx.adminStrings("SELECT payload::text FROM taktovia.outbox WHERE aggregate_id=? AND event_type=?", aggregate, type)

    @Test
    fun erteilen_und_entziehen_auditiert_und_ereignis_genau_einmal() {
        val t = fx.tenant()
        val ctx = fx.context(t, "admin-1", setOf(fx.seedMembership(t, "admin-1")))
        val target = "user-" + UUID.randomUUID()

        val granted = fx.identity.admin.grant(ctx, GrantMembership(fx.issuer, target, "dispatcher", null))
        assertEquals(1, granted.revision); assertEquals("dispatcher", granted.roleCode)
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='identity.membership.granted' AND object_ref=?", t.value, "membership:${granted.id}"))
        val g = payloads(granted.id, "membership.granted")
        assertEquals(1, g.size, "genau ein Outbox-Ereignis erteilt")
        assertEquals(emptyList<String>(), schemaErrors("membership.granted", g.single()))

        fx.identity.admin.revoke(ctx, granted.id, 1)
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='identity.membership.revoked' AND object_ref=?", t.value, "membership:${granted.id}"))
        val r = payloads(granted.id, "membership.revoked")
        assertEquals(1, r.size, "genau ein Outbox-Ereignis entzogen")
        assertEquals(emptyList<String>(), schemaErrors("membership.revoked", r.single()))
        assertEquals(2, fx.adminCount("SELECT aggregate_revision FROM taktovia.outbox WHERE aggregate_id=? AND event_type='membership.revoked'", granted.id))
        // der Audit-Eintrag enthält weder Token noch Klartext-Kontaktdaten; Akteur ist die Subject-Referenz
        assertEquals(2, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND actor_ref='user:admin-1'", t.value))
    }

    @Test
    fun schemadatei_lehnt_falsches_ereignis_ab() {
        assertTrue(schemaErrors("membership.granted", """{"membership_id":"x"}""").isNotEmpty(), "Negativprobe: das Schema prüft wirklich")
        val ok = """{"membership_id":"00000000-0000-4000-8000-0000000000e1","issuer":"i","subject":"s","role_code":"dispatcher","service_scope":null,"valid_from":"2026-11-02T08:00:00Z","valid_to":null,"revoked_at":null,"revision":2}"""
        assertTrue(schemaErrors("membership.granted", ok).isNotEmpty(), "granted verlangt Revision 1")
    }

    @Test
    fun fehlerfaelle_beim_entziehen_und_nichts_bleibt_zurueck() {
        val t = fx.tenant(); val other = fx.tenant()
        val ctx = fx.context(t, "admin-2", setOf(fx.seedMembership(t, "admin-2")))
        val m = fx.identity.admin.grant(ctx, GrantMembership(fx.issuer, "u-" + UUID.randomUUID(), "support", null))
        val foreign = fx.seedMembership(other, "x")

        fun code(block: () -> Unit) = assertThrows(ProblemException::class.java, block).code
        assertEquals(ErrorCode.NOT_FOUND, code { fx.identity.admin.revoke(ctx, foreign, 1) }, "fremder Mandant wie nicht vorhanden")
        assertEquals(ErrorCode.NOT_FOUND, code { fx.identity.admin.revoke(ctx, UUID.randomUUID(), 1) })
        assertEquals(ErrorCode.REVISION_MISMATCH, code { fx.identity.admin.revoke(ctx, m.id, 7) })
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.outbox WHERE aggregate_id=? AND event_type='membership.revoked'", m.id), "Fehler hinterlässt kein Ereignis")
        fx.identity.admin.revoke(ctx, m.id, 1)
        assertEquals(ErrorCode.CONFLICT, code { fx.identity.admin.revoke(ctx, m.id, 2) }, "schon entzogen")
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.outbox WHERE aggregate_id=? AND event_type='membership.revoked'", m.id))
        // fremde Mitgliedschaft blieb unberührt
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.membership WHERE id=? AND revoked_at IS NOT NULL", foreign))
    }

    @Test
    fun doppelte_mitgliedschaft_wird_abgewiesen_und_hinterlaesst_nichts() {
        val t = fx.tenant()
        val ctx = fx.context(t, "admin-3", setOf(fx.seedMembership(t, "admin-3")))
        val subject = "u-" + UUID.randomUUID()
        fx.identity.admin.grant(ctx, GrantMembership(fx.issuer, subject, "support", null))
        assertThrows(DuplicateKeyException::class.java) { fx.identity.admin.grant(ctx, GrantMembership(fx.issuer, subject, "support", null)) }
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='identity.membership.granted'", t.value), "nur der erste Versuch ist auditiert")
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='membership.granted'", t.value))
        // eine andere Rolle derselben Person ist keine Dublette
        fx.identity.admin.grant(ctx, GrantMembership(fx.issuer, subject, "dispatcher", null))
    }

    @Test
    fun ungueltige_eingaben_werden_vor_der_datenbank_abgewiesen() {
        val t = fx.tenant()
        val ctx = fx.context(t, "admin-4", setOf(fx.seedMembership(t, "admin-4")))
        val e = assertThrows(ProblemException::class.java) { fx.identity.admin.grant(ctx, GrantMembership("", "s", "Ungültig Rolle", null)) }
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code)
        assertEquals(setOf("/issuer", "/role_code"), e.violations.map { it.pointer }.toSet())
    }

    @Test
    fun paralleles_erteilen_derselben_mitgliedschaft_erzeugt_genau_eine_zeile_und_ein_ereignis() {
        val t = fx.tenant()
        val ctx = fx.context(t, "admin-5", setOf(fx.seedMembership(t, "admin-5")))
        val subject = "u-" + UUID.randomUUID()
        val n = 6
        val pool = Executors.newFixedThreadPool(n); val start = CountDownLatch(1)
        val results = (1..n).map {
            pool.submit<String> {
                start.await()
                try { fx.identity.admin.grant(ctx, GrantMembership(fx.issuer, subject, "support", null)); "ok" } catch (e: DuplicateKeyException) { "dup" }
            }
        }
        start.countDown()
        val r = results.map { it.get(30, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, r.count { it == "ok" }, r.toString()); assertEquals(n - 1, r.count { it == "dup" })
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.membership WHERE tenant_id=? AND subject=?", t.value, subject))
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.outbox o JOIN taktovia.membership m ON m.id=o.aggregate_id WHERE o.tenant_id=? AND m.subject=? AND o.event_type='membership.granted'", t.value, subject))
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.audit_event a JOIN taktovia.membership m ON a.object_ref='membership:'||m.id WHERE a.tenant_id=? AND m.subject=?", t.value, subject))
    }

    @Test
    fun liste_ist_auf_den_mandanten_des_kontexts_begrenzt_und_seitenweise() {
        val a = fx.tenant(); val b = fx.tenant()
        val ctxA = fx.context(a, "admin-6", setOf(fx.seedMembership(a, "admin-6")))
        repeat(4) { fx.seedMembership(a, "list-$it", "support") }
        fx.seedMembership(b, "fremd", "support")
        val first = fx.identity.admin.list(ctxA, 2, null)
        assertEquals(3, first.size, "limit+1: der Aufrufer erkennt die Folgeseite")
        val next = fx.identity.admin.list(ctxA, 10, first[1].createdAt to first[1].id)
        assertEquals(5 - 2, next.size)
        assertTrue((first + next).none { it.subject == "fremd" })
    }
}
