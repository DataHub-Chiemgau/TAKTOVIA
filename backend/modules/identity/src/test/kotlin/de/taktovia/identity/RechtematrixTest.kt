package de.taktovia.identity

import de.taktovia.identity.api.Decision
import de.taktovia.identity.api.IdentityApi
import de.taktovia.identity.api.MatrixAuthorizer
import de.taktovia.identity.api.RightsMatrix
import de.taktovia.identity.api.RoleCatalog
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.context.ContextRevokedException
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * LH-6.2-IAM-004 / WP-M1-07: Rollenmodell und Rechtematrix. Die Matrixdatei ist die einzige Rechtequelle; hier werden ihre Struktur, die
 * Administrator-Invariante (Kap. 01 §1.7), die zentrale Autorisierung, die Versionierung/Prüfsumme und das Protokoll des Ausrollens belegt.
 * Der generierte Lauf Rolle x Tätigkeit gegen die echte Anwendung steht in `RechtematrixApiTest` (Modul app).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RechtematrixTest {
    private val repo = Path.of(System.getProperty("taktovia.repoRoot"))
    private val matrixFile = repo.resolve("backend/modules/identity/src/main/resources/rechtematrix/rechtematrix.csv")
    private val lockFile = repo.resolve("backend/modules/identity/src/main/resources/rechtematrix/rechtematrix.lock")
    private val matrix = RightsMatrix.load()
    private lateinit var fx: IdentityFixture

    @BeforeAll fun up() { fx = IdentityFixture() }
    @AfterAll fun down() = fx.close()

    private fun ctx(roles: Set<String>) = fx.context(fx.tenant(), "u-" + UUID.randomUUID(), emptySet(), roles)

    @Test
    fun matrixdatei_ist_strukturell_gueltig_und_deckt_sieben_pflichtrollen_ab() {
        assertEquals(emptyList<String>(), matrix.problems())
        assertTrue(matrix.roles.containsAll(RoleCatalog.REQUIRED), "sieben Pflichtrollen als Spalten")
        assertEquals(7, RoleCatalog.REQUIRED.size)
        assertEquals(setOf("passenger", "driver", "callcenter", "support", "dispatcher", "planner", "tenant_admin"), RoleCatalog.REQUIRED.toSet())
        // jede Pflichtrolle darf etwas und darf etwas nicht (der Matrixlauf prüft beides, hier: die Matrix ist nicht degeneriert)
        RoleCatalog.REQUIRED.forEach { role ->
            assertTrue(matrix.operations.any { role in it.allowed }, "$role darf nichts")
            assertTrue(matrix.operations.any { role !in it.allowed }, "$role darf alles")
        }
        assertTrue(matrix.operations.size >= 50)
    }

    @Test
    fun strukturpruefung_hat_zaehne() {
        val text = Files.readString(matrixFile)
        val admin = matrix.roles.indexOf("tenant_admin")
        // Administrator bekommt PII-Zugriff: die Prüfung muss das melden
        val bad = text.lines().joinToString("\n") { l -> if (l.startsWith("listPassengers;")) l.split(';').toMutableList().also { it[4 + admin] = "J" }.joinToString(";") else l }
        assertTrue(RightsMatrix.parse(bad).problems().any { it.contains("listPassengers") && it.contains("Administrator") }, RightsMatrix.parse(bad).problems().toString())
        // gleicher Scope, unterschiedliche Rollen
        val split = text.lines().joinToString("\n") { l -> if (l.startsWith("createRegion;")) l.split(';').toMutableList().also { it[4] = "J" }.joinToString(";") else l }
        assertTrue(RightsMatrix.parse(split).problems().any { it.contains("organization:write") }, RightsMatrix.parse(split).problems().toString())
        // doppelte Zeile, falsche Datenklasse, unbekannte Rollenspalte
        assertTrue(RightsMatrix.parse(text + text.lines().first { it.startsWith("getMe;") } + "\n").problems().any { it.contains("doppelt") })
        assertTrue(RightsMatrix.parse(text.replace("listPassengers;passengers:read;PII", "listPassengers;passengers:read;GEHEIM")).problems().any { it.contains("Datenklasse") })
        assertTrue(RightsMatrix.parse(text.replace(";workshop\n", ";werkstatt\n")).problems().any { it.contains("Rollenkatalog") })
        assertThrows(IllegalArgumentException::class.java) { RightsMatrix.parse(text.replace("getMe;me:read;-;M1;J", "getMe;me:read;-;M1;X")) }
    }

    @Test
    fun administrator_ohne_zuweisung_kein_pii_hr_finanzzugriff() {
        val sensitive = matrix.operations.filter { it.dataClass != "-" }
        assertEquals(setOf("PII", "HR", "FINANZ"), sensitive.map { it.dataClass }.toSet(), "alle drei Klassen sind in der Matrix vertreten")
        val adminOnly = setOf("tenant_admin")
        val adminScopes = matrix.scopesFor(adminOnly)
        sensitive.forEach { op ->
            assertTrue("tenant_admin" !in op.allowed, "${op.operationId}: Administrator darf nicht auf ${op.dataClass}")
            assertTrue(op.scope !in adminScopes, "${op.operationId}: Scope ${op.scope} darf dem Administrator nicht zufallen")
            assertTrue(matrix.allows(adminOnly, op.operationId).not())
            assertEquals(Decision.Denied("role_not_allowed"), MatrixAuthorizer(matrix).decide(ctx(adminOnly), op.operationId, listOf(op.scope)))
        }
        // gesonderte Zuweisung: Administrator + Fachrolle erhält genau den Zugriff dieser Fachrolle
        assertTrue(matrix.allows(setOf("tenant_admin", "finance"), "approveExpense"))
        assertTrue(matrix.allows(setOf("tenant_admin", "staff_planning"), "publishRoster"))
        assertTrue(matrix.allows(setOf("tenant_admin", "callcenter"), "listPassengers"))
        assertTrue(!matrix.allows(setOf("tenant_admin", "finance"), "listPassengers"), "Finanzrolle öffnet keine PII")
        assertEquals(Decision.Allowed, MatrixAuthorizer(matrix).decide(ctx(setOf("tenant_admin", "finance")), "approveExpense", listOf("expenses:approve")))
    }

    @Test
    fun scopes_der_rollen_folgen_aus_der_matrix() {
        assertEquals(setOf("me:read", "tenant:switch", "journeys:plan", "journeys:read", "journeys:book", "journeys:cancel", "passengers:write"), matrix.scopesFor(setOf("passenger")))
        assertTrue(matrix.scopesFor(setOf("driver")).containsAll(listOf("driver:events", "me:read")))
        assertTrue("memberships:write" in matrix.scopesFor(setOf("tenant_admin")) && "memberships:write" !in matrix.scopesFor(setOf("dispatcher")))
        assertEquals(emptySet<String>(), matrix.scopesFor(setOf("gibt-es-nicht")), "unbekannte Rolle: keine Scopes")
        assertEquals(matrix.scopesFor(setOf("support", "planner")), matrix.scopesFor(setOf("support")) + matrix.scopesFor(setOf("planner")), "Rollen vereinigen ihre Scopes")
    }

    @Test
    fun zentrale_autorisierung_deny_by_default() {
        val a = MatrixAuthorizer(matrix)
        assertEquals(Decision.Allowed, a.decide(ctx(setOf("tenant_admin")), "createMembership", listOf("memberships:write")))
        assertEquals(Decision.Denied("role_not_allowed"), a.decide(ctx(setOf("dispatcher")), "createMembership", listOf("memberships:write")))
        assertEquals(Decision.Denied("not_in_matrix"), a.decide(ctx(setOf("tenant_admin")), "neueOperation", listOf("x:y")))
        assertEquals(Decision.Denied("no_scope_declared"), a.decide(ctx(setOf("tenant_admin")), "createMembership", emptyList()))
        assertEquals(Decision.Denied("matrix_scope_mismatch"), a.decide(ctx(setOf("tenant_admin")), "createMembership", listOf("memberships:read")))
        assertEquals(Decision.Denied("role_not_allowed"), a.decide(ctx(emptySet()), "getMe", listOf("me:read")), "ohne Rollen nichts")
        // Dienstkonto: nur Scope des Kontexts, nie Rollen
        val svc = fx.context(fx.tenant(), UUID.randomUUID().toString(), emptySet(), emptySet(), de.taktovia.platform.context.ActorType.SERVICE)
        assertEquals(Decision.Denied("scope_missing"), a.decide(svc, "listMemberships", listOf("memberships:read")))
    }

    @Test
    fun vertragsabgleich_meldet_fehlende_veraltete_und_abweichende_eintraege() {
        val contract = matrix.operations.associate { it.operationId to if (it.scope == "-") emptyList() else listOf(it.scope) }
        assertEquals(emptyList<String>(), matrix.verifyContract(contract))
        assertTrue(matrix.verifyContract(contract + ("neueRoute" to listOf("neu:read"))).any { it.contains("neueRoute") && it.contains("keinen Eintrag") })
        assertTrue(matrix.verifyContract(contract - "getMe").any { it.contains("getMe") && it.contains("veralteter") })
        assertTrue(matrix.verifyContract(contract + ("getMe" to listOf("me:write"))).any { it.contains("getMe") && it.contains("Scope") })
    }

    @Test
    fun matrixaenderung_nachvollziehbar_und_auditiert() {
        // 1. Nachvollziehbar im Repository: Version und Prüfsumme der Matrixdatei stehen in rechtematrix.lock; eine Änderung der Matrix ohne
        //    Aktualisierung der Sperrdatei (und damit ein sichtbarer Eintrag im Diff) scheitert hier.
        val lock = Files.readAllLines(lockFile).filter { it.isNotBlank() }.associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
        assertEquals(matrix.version.toString(), lock["version"], "rechtematrix.lock: Version stimmt nicht mit der Matrixdatei überein (Version erhöhen)")
        assertEquals(matrix.sha256, lock["sha256"], "rechtematrix.lock: Matrix geändert, aber Sperrdatei nicht erneuert (sha256 der neuen Matrix: ${matrix.sha256})")
        val changed = RightsMatrix.parse(Files.readString(matrixFile).replace("# matrix_version: 1", "# matrix_version: 2"))
        assertNotEquals(matrix.sha256, changed.sha256, "jede Änderung ändert die Prüfsumme")

        // 2. Auditiert beim Ausrollen: eine Zeile je Änderung (Version, Prüfsumme, Vorgänger), nie doppelt, nur anhängbar.
        val audit = JdbcAuditPort(); val real = fx.identity
        real.rollout.record("service:test-a")                       // Ausgangslage (ggf. schon protokolliert)
        assertEquals(false, real.rollout.record("service:test-a").recorded, "unveränderte Matrix wird nicht erneut protokolliert")
        val count0 = fx.adminCount("SELECT count(*) FROM taktovia.rights_matrix_rollout")
        val v2 = IdentityApi.create(fx.tx, audit, SecurityEventPort(audit), Outbox(), rights = changed)
        val r = v2.rollout.record("service:test-b")
        assertTrue(r.recorded); assertEquals(2, r.version); assertEquals(matrix.sha256, r.previousSha256)
        assertEquals(false, v2.rollout.record("service:test-b").recorded)
        assertEquals(count0 + 1, fx.adminCount("SELECT count(*) FROM taktovia.rights_matrix_rollout"))
        // Rückkehr zur Matrix der Repository-Version ist wieder eine Änderung und wird protokolliert; die Folge bleibt lückenlos.
        val back = real.rollout.record("service:test-a")
        assertTrue(back.recorded); assertEquals(changed.sha256, back.previousSha256)
        assertEquals(count0 + 2, fx.adminCount("SELECT count(*) FROM taktovia.rights_matrix_rollout"))
        assertEquals(matrix.sha256, fx.adminStrings("SELECT matrix_sha256 FROM taktovia.rights_matrix_rollout ORDER BY id DESC LIMIT 1").single().trim())
    }

    @Test
    fun rollenentzug_waehrend_sitzung_verweigert() {
        val t = fx.tenant(); val s = "u-" + UUID.randomUUID()
        val support = fx.seedMembership(t, s, "support"); val admin = fx.seedMembership(t, s, "tenant_admin")
        val both = fx.context(t, s, setOf(support, admin), roles = setOf("support", "tenant_admin"))
        val ctxTx = ContextTransactions(fx.tx, fx.identity.revalidator)
        assertEquals(1, ctxTx.run(both) { 1 })
        assertTrue(fx.identity.authorizer.decide(both, "createMembership", listOf("memberships:write")) == Decision.Allowed)
        fx.tx.run(t) { j -> j.update("UPDATE taktovia.membership SET revoked_at = clock_timestamp() WHERE id = ?", admin) }
        assertThrows(ContextRevokedException::class.java) { ctxTx.run(both) { 1 } }   // laufender Kontext mit entzogener Rolle: Folgeanfrage verweigert
        // neu aufgelöster Kontext: die verbleibende Rolle gilt, die entzogene nicht
        val fresh = fx.identity.lookup.activeIn(de.taktovia.identity.api.SubjectRef(fx.issuer, s), t)!!
        assertEquals(setOf("support"), fresh.roles)
        assertEquals(Decision.Denied("role_not_allowed"), fx.identity.authorizer.decide(fx.context(t, s, fresh.membershipIds, fresh.roles), "createMembership", listOf("memberships:write")))
    }
}
