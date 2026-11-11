package de.taktovia.config

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import de.taktovia.config.api.CompanyProfileInput
import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M1-24 / LH-I.7-CFG-001: Unternehmensprofil des Mandanten als versionierte Konfiguration (Scope `TENANT_PROFILE`, PA-11). Der Mandantenadministrator ändert
 * Kontakt und Standardwährung selbst (kein Betreiberkonto beteiligt), die Währung wird gegen den Katalog geprüft, veraltete oder gleichzeitige Änderungen
 * erhalten 412, jede Änderung ist auditiert und erzeugt genau ein Ereignis, Mandant B bleibt unberührt. Echte Datenbank, Runtime-Rolle.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantProfileTest {
    private lateinit var fx: ConfigFixture
    private val repo = Path.of(System.getProperty("taktovia.repoRoot"))
    private val registry = SchemaRegistry.withDefaultDialect(Dialects.getDraft202012())

    @BeforeAll fun up() { fx = ConfigFixture() }
    @AfterAll fun down() = fx.close()

    private fun profile(email: String = "info@example.org", currency: String = "EUR", name: String = "Stadtwerke GmbH") =
        CompanyProfileInput(name, email, currency, address = "Musterstraße 1, 12345 Musterstadt")

    private fun seeded(tenant: de.taktovia.platform.tenant.TenantId = fx.tenant()): de.taktovia.platform.tenant.TenantId {
        fx.tx.run(tenant) { j -> fx.config.companyProfile.seed(j, ActorRef.system("test"), UUID.randomUUID(), profile()) }
        return tenant
    }

    @Test
    fun administrator_aendert_kontakt_und_waehrung_ohne_plattformrolle() {
        val t = seeded(); val ctx = fx.context(t)   // Kontext des Mandantenadministrators; weder Betreiberkonto noch Mitwirkung einer Plattformrolle
        val before = fx.config.companyProfile.get(ctx)
        assertEquals(1, before.revision); assertEquals("EUR", before.input.defaultCurrency)
        val updated = fx.config.companyProfile.update(ctx, before.revision, profile(email = "neu@example.org", currency = "CHF"))
        assertEquals(2, updated.revision)
        // nach erneutem Laden sichtbar
        val reloaded = fx.config.companyProfile.get(fx.context(t))
        assertEquals("neu@example.org", reloaded.input.contactEmail); assertEquals("CHF", reloaded.input.defaultCurrency); assertEquals(2, reloaded.revision)
        // die Vorgängerrevision bleibt erhalten (versionierte Konfiguration), beide veröffentlicht vom Administrator selbst
        assertEquals(listOf("1", "2"), fx.adminStrings("SELECT revision::text FROM taktovia.config_revision WHERE tenant_id=? AND scope_kind='TENANT_PROFILE' ORDER BY revision", t.value))
        assertEquals(listOf("user:${ctx.actor.subject}"), fx.adminStrings("SELECT DISTINCT approved_by FROM taktovia.config_revision WHERE tenant_id=? AND scope_kind='TENANT_PROFILE' AND revision=2", t.value))
        // keine Betreiberbeteiligung: kein Betreiberkonto im System nötig, der Plattformbetrieb taucht nirgends als Akteur auf
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND actor_ref LIKE 'platform%'", t.value))
        // Der Profilinhalt entspricht dem Schema der Feldliste
        val schema = registry.getSchema(Files.readString(repo.resolve("docs/umsetzungsplan/vertraege/schemas/tenant-profile.v1.schema.json")))
        fx.adminStrings("SELECT values_json::text FROM taktovia.config_revision WHERE tenant_id=?", t.value).forEach {
            assertEquals(emptyList<String>(), schema.validate(it, InputFormat.JSON).map { m -> m.toString() }, it)
        }
    }

    @Test
    fun unbekannte_waehrung_wird_abgewiesen() {
        val t = seeded(); val ctx = fx.context(t)
        for (bad in listOf("XXY", "EU", "eur", "EURO")) {
            val e = assertThrows(ProblemException::class.java) { fx.config.companyProfile.update(ctx, 1, profile(currency = bad)) }
            assertEquals(ErrorCode.VALIDATION_FAILED, e.code, bad)
            assertTrue(e.violations.any { it.pointer == "/default_currency" && it.code == ViolationCode.CURRENCY_UNKNOWN }, e.violations.toString())
        }
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(ProblemException::class.java) { fx.config.companyProfile.update(ctx, 1, profile(email = "keine-mail")) }.code)
        // nichts wurde geschrieben: weiterhin Revision 1, kein Audit, kein Ereignis
        assertEquals(1, fx.config.companyProfile.get(ctx).revision)
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='config.tenant_profile.changed' AND redacted_changes->>'revision'='2'", t.value))
        // unbekannte Währung schon bei der Erstausstattung: die Transaktion (z. B. Mandantenanlage) bricht ab
        val fresh = fx.tenant()
        assertThrows(ProblemException::class.java) { fx.tx.run(fresh) { j -> fx.config.companyProfile.seed(j, ActorRef.system("test"), UUID.randomUUID(), profile(currency = "XXY")) } }
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.config_revision WHERE tenant_id=?", fresh.value))
    }

    @Test
    fun aenderung_in_a_laesst_profil_b_unveraendert_und_ist_auditiert() {
        val a = seeded(); val b = seeded()
        val ctxA = fx.context(a, "admin-a")
        val updated = fx.config.companyProfile.update(ctxA, 1, profile(email = "a-neu@example.org", currency = "CHF"))
        assertEquals(2, updated.revision)
        val inB = fx.config.companyProfile.get(fx.context(b))
        assertEquals(1, inB.revision); assertEquals("info@example.org", inB.input.contactEmail); assertEquals("EUR", inB.input.defaultCurrency)
        // Audit: Akteur und Zeitpunkt, im Mandanten A; B hat keinen Eintrag der Änderung
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='config.tenant_profile.changed' AND actor_ref='user:admin-a' AND occurred_at IS NOT NULL AND redacted_changes->>'revision'='2'", a.value))
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='config.tenant_profile.changed' AND redacted_changes->>'revision'='2'", b.value))
        // Personendaten stehen weder im Audit noch im Ereignis; genau ein Ereignis je Änderung, gültig gegen das Schema
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND redacted_changes::text LIKE '%example.org%'", a.value))
        val events = fx.adminStrings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id=? AND event_type='tenant.profile_changed' AND aggregate_id=? ORDER BY aggregate_revision", a.value, a.value)
        assertEquals(2, events.size, "Revision 1 (Erstausstattung) und 2")
        val schema = registry.getSchema(Files.readString(repo.resolve("api/events/tenant.profilechanged.v1.schema.json")))
        events.forEach { assertEquals(emptyList<String>(), schema.validate(it, InputFormat.JSON).map { m -> m.toString() }, it) }
        assertTrue(events.last().contains("contact_email") && events.last().contains("default_currency") && !events.last().contains("example.org"), events.last())
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='tenant.profile_changed'", b.value))
        // eine Änderung ohne Wirkung ist keine Änderung
        val same = fx.config.companyProfile.update(ctxA, 2, profile(email = "a-neu@example.org", currency = "CHF"))
        assertEquals(2, same.revision)
        assertEquals(2, fx.adminCount("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='tenant.profile_changed'", a.value))
    }

    @Test
    fun veraltete_revision_und_fehlendes_profil() {
        val t = seeded(); val ctx = fx.context(t)
        fx.config.companyProfile.update(ctx, 1, profile(email = "x@example.org"))
        assertEquals(ErrorCode.REVISION_MISMATCH, assertThrows(ProblemException::class.java) { fx.config.companyProfile.update(ctx, 1, profile(email = "y@example.org")) }.code)
        assertEquals(ErrorCode.REVISION_MISMATCH, assertThrows(ProblemException::class.java) { fx.config.companyProfile.update(ctx, 0, profile(email = "y@example.org")) }.code)
        val empty = fx.context(fx.tenant())
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(ProblemException::class.java) { fx.config.companyProfile.get(empty) }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(ProblemException::class.java) { fx.config.companyProfile.update(empty, 3, profile()) }.code)
        assertEquals(1, fx.config.companyProfile.update(empty, 0, profile()).revision, "Revision 0 legt das erste Profil an")
    }

    @Test
    fun paralleler_schreibzugriff_liefert_412() {
        val t = seeded(); val ctx = fx.context(t)
        val pool = Executors.newFixedThreadPool(6); val start = CountDownLatch(1)
        val results = (1..6).map { n -> pool.submit<String> {
            start.await()
            try { fx.config.companyProfile.update(ctx, 1, profile(email = "p$n@example.org")); "ok" } catch (e: ProblemException) { e.code.name }
        } }
        start.countDown()
        val r = results.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, r.count { it == "ok" }, r.toString()); assertEquals(5, r.count { it == "REVISION_MISMATCH" }, r.toString())
        assertEquals(2, fx.config.companyProfile.get(ctx).revision)
        assertEquals(2, fx.adminCount("SELECT count(*) FROM taktovia.config_revision WHERE tenant_id=? AND scope_kind='TENANT_PROFILE'", t.value))
        assertEquals(2, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='config.tenant_profile.changed'", t.value))
    }
}
