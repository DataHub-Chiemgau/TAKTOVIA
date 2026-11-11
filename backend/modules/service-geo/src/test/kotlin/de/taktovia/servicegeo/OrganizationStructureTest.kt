package de.taktovia.servicegeo

import de.taktovia.config.api.ScopeKind
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.servicegeo.api.CreateService
import de.taktovia.servicegeo.api.UpdateService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M1-09 / LH-I.4-MUL-002 gegen die echte Datenbank unter der Runtime-Rolle: zwei Gebiete mit eigenen Regeln im selben Login, Dienstanlage mit
 * erster Konfigurationsrevision, Mandantentrennung, Revisionen (412), Audit je Änderung.
 */
class OrganizationStructureTest {
    private companion object {
        val fx = StructureFixture()
        @JvmStatic @AfterAll fun close() = fx.close()
    }
    private val s get() = fx.geo.structure

    @Test
    fun zwei_regionen_eigene_regeln_ein_login() {
        val w = fx.world(); val admin = fx.context(w.tenant)
        val nord = s.createRegion(admin, "Region Nord"); val sued = s.createRegion(admin, "Region Süd")
        val dNord = s.createService(admin, CreateService(w.operator, "Rufbus Nord", "Europe/Berlin", nord.id))
        val dSued = s.createService(admin, CreateService(w.operator, "Rufbus Süd", "Europe/Berlin", sued.id))
        // eigene Konfigurationsrevisionen je Region und je Dienst mit unterschiedlichen Werten
        fx.publishedRevision(admin, ScopeKind.REGION, nord.id, mapOf("max_wait_min" to 15))
        fx.publishedRevision(admin, ScopeKind.REGION, sued.id, mapOf("max_wait_min" to 40))
        fx.publishedRevision(admin, ScopeKind.SERVICE, dNord.id, mapOf("max_wait_min" to 12) + fx.serviceConfig(w.operator))
        fx.publishedRevision(admin, ScopeKind.SERVICE, dSued.id, mapOf("max_wait_min" to 35) + fx.serviceConfig(w.operator))
        val at = Instant.parse("2026-06-01T00:00:00Z")
        assertEquals(15, fx.config.revisions.asOf(admin, ScopeKind.REGION, nord.id, at)!!.values["max_wait_min"])
        assertEquals(40, fx.config.revisions.asOf(admin, ScopeKind.REGION, sued.id, at)!!.values["max_wait_min"])
        assertEquals(12, fx.config.revisions.asOf(admin, ScopeKind.SERVICE, dNord.id, at)!!.values["max_wait_min"])
        assertEquals(35, fx.config.revisions.asOf(admin, ScopeKind.SERVICE, dSued.id, at)!!.values["max_wait_min"])
        // ein Nutzer (ein Login, eine Mitgliedschaft mit beiden Diensten) arbeitet ohne erneute Anmeldung in beiden Gebieten
        val reader = setOf("services:read")
        val login = fx.context(w.tenant, services = setOf(dNord.id, dSued.id), scopes = reader)
        val visible = s.listServices(login, 10, null)
        assertEquals(setOf(dNord.id, dSued.id), visible.map { it.id }.toSet())
        assertEquals(nord.id, visible.single { it.id == dNord.id }.regionId); assertEquals(sued.id, visible.single { it.id == dSued.id }.regionId)
        assertTrue(login.mayUseService(dNord.id) && login.mayUseService(dSued.id))
        // ein Nutzer nur mit dem Nord-Dienst sieht den Süd-Dienst nicht
        val nordOnly = fx.context(w.tenant, services = setOf(dNord.id), scopes = reader)
        assertEquals(listOf(dNord.id), s.listServices(nordOnly, 10, null).map { it.id })
        assertEquals(emptyList<UUID>(), s.listServices(nordOnly, 10, null, requested = setOf(dSued.id)).map { it.id }, "Filter auf fremden Dienst: leere Liste")
        assertTrue(s.listServices(fx.context(w.tenant, scopes = reader), 10, null).isEmpty(), "ohne Dienstscope: kein Dienstzugriff (fail-closed)")
        // verwaltende Rolle (services:write) sieht alle Dienste des Mandanten
        assertEquals(setOf(dNord.id, dSued.id), s.listServices(admin, 10, null).map { it.id }.toSet())
        // die Dienstanlage hat jeweils die erste Revision (Entwurf, Revision 1) erzeugt und mit dem Dienst verknüpft
        val first = fx.config.revisions.get(admin, dNord.configRevisionId)
        assertEquals(ScopeKind.SERVICE, first.scopeKind); assertEquals(dNord.id, first.scopeId); assertEquals(1L, first.revision)
    }

    @Test
    fun dienst_in_fremdem_mandanten_wird_abgewiesen() {
        val a = fx.world(); val b = fx.world(); val ctxA = fx.context(a.tenant); val ctxB = fx.context(b.tenant)
        val regionB = s.createRegion(ctxB, "Region B")
        // Region eines fremden Mandanten ist nicht referenzierbar (404), auch nicht als Betreiber oder Betriebshof-Ziel
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { s.createService(ctxA, CreateService(a.operator, "X", "Europe/Berlin", regionB.id)) }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { s.createService(ctxA, CreateService(b.operator, "X", "Europe/Berlin")) }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { s.createDepot(ctxA, regionB.id, "Hof") }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { s.updateRegion(ctxA, regionB.id, 1, "Übernahme") }.code)
        // keine halb angelegten Dienste: weder Dienst noch Revision entstanden
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.service WHERE tenant_id = ?", a.tenant.value))
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.config_revision WHERE tenant_id = ? AND scope_kind = 'SERVICE'", a.tenant.value))
        // Region, Betriebshof und Dienst des Mandanten B sind aus A nicht sichtbar
        val depotB = s.createDepot(ctxB, regionB.id, "Hof B"); val serviceB = s.createService(ctxB, CreateService(b.operator, "Dienst B", "Europe/Berlin", regionB.id))
        assertTrue(s.listRegions(ctxA, 10, null).isEmpty() && s.listDepots(ctxA, 10, null).isEmpty() && s.listServices(ctxA, 10, null).isEmpty())
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { s.updateDepot(ctxA, depotB.id, 1, regionB.id, "Hof") }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { s.updateService(ctxA, serviceB.id, 1, UpdateService("x", "Europe/Berlin")) }.code)
        assertEquals(1, s.listServices(ctxB, 10, null).size)
    }

    @Test
    fun ungueltige_iana_zone_ergibt_422() {
        val w = fx.world(); val ctx = fx.context(w.tenant)
        for (tz in listOf("Mars/Olympus", "CET", "Etc/GMT+1", "+01:00", "", "Europe/Berlin ")) {
            val e = assertThrows<ProblemException>(tz) { s.createService(ctx, CreateService(w.operator, "X", tz)) }
            assertEquals(ErrorCode.VALIDATION_FAILED, e.code, tz)
            assertTrue(e.violations.any { it.pointer == "/timezone" }, tz)
        }
        val ok = s.createService(ctx, CreateService(w.operator, "Dienst", "Europe/Berlin"))
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows<ProblemException> { s.updateService(ctx, ok.id, 1, UpdateService("Dienst", "Mars/Olympus")) }.code)
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.service WHERE tenant_id = ? AND timezone = 'Mars/Olympus'", w.tenant.value))
        // Namen: leer und zu lang
        assertTrue(assertThrows<ProblemException> { s.createRegion(ctx, "") }.violations.any { it.pointer == "/name" && it.code == ViolationCode.TOO_SHORT })
        assertTrue(assertThrows<ProblemException> { s.createRegion(ctx, "x".repeat(201)) }.violations.any { it.code == ViolationCode.TOO_LONG })
    }

    @Test
    fun zwei_parallele_aenderungen_derselben_region_eine_412() {
        val w = fx.world(); val ctx = fx.context(w.tenant); val r = s.createRegion(ctx, "Alt")
        val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        val results = (1..2).map { i -> pool.submit<Result<Any>> { start.await(); runCatching { s.updateRegion(ctx, r.id, 1, "Neu $i") } } }
        start.countDown()
        val out = results.map { it.get(30, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, out.count { it.isSuccess }, out.toString())
        assertEquals(ErrorCode.REVISION_MISMATCH, (out.single { it.isFailure }.exceptionOrNull() as ProblemException).code)
        assertEquals(2L, s.listRegions(ctx, 10, null).single().revision)
        // genau ein Auditeintrag je Änderung: Anlage + eine erfolgreiche Änderung
        assertEquals(2, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'identity.region.changed' AND object_ref = ?", w.tenant.value, "region:${r.id}"))
    }

    @Test
    fun aenderung_ohne_wirkung_erhoeht_revision_nicht_und_schreibt_kein_audit_und_veraltet_ist_412() {
        val w = fx.world(); val ctx = fx.context(w.tenant); val r = s.createRegion(ctx, "Gleich")
        val same = s.updateRegion(ctx, r.id, 1, "Gleich")
        assertEquals(1L, same.revision)
        val changed = s.updateRegion(ctx, r.id, 1, "Anders"); assertEquals(2L, changed.revision)
        assertEquals(ErrorCode.REVISION_MISMATCH, assertThrows<ProblemException> { s.updateRegion(ctx, r.id, 1, "Alt") }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { s.updateRegion(ctx, UUID.randomUUID(), 1, "x") }.code)
        assertEquals(2, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'identity.region.changed'", w.tenant.value))
    }

    @Test
    fun dienstanlage_erzeugt_erste_revision_und_audit_und_aenderung_per_if_match() {
        val w = fx.world(); val ctx = fx.context(w.tenant); val region = s.createRegion(ctx, "R")
        val d = s.createService(ctx, CreateService(w.operator, "Rufbus", "Europe/Berlin", region.id))
        assertEquals(1L, d.revision); assertNotNull(d.configRevisionId)
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.config_revision WHERE tenant_id = ? AND scope_kind = 'SERVICE' AND scope_id = ? AND state = 'DRAFT'", w.tenant.value, d.id))
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'identity.service.changed' AND object_ref = ?", w.tenant.value, "service:${d.id}"))
        val u = s.updateService(ctx, d.id, 1, UpdateService("Rufbus neu", "Europe/Vienna", null))
        assertEquals(2L, u.revision); assertEquals("Europe/Vienna", u.timezone); assertEquals(null, u.regionId); assertEquals(w.operator, u.operatorId)
        assertEquals(ErrorCode.REVISION_MISMATCH, assertThrows<ProblemException> { s.updateService(ctx, d.id, 1, UpdateService("x", "Europe/Berlin")) }.code)
        assertNotEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'config.revision_created'", w.tenant.value))
    }

    @Test
    fun mehrere_betriebshoefe_je_region_und_auflistung_mit_cursor() {
        val w = fx.world(); val ctx = fx.context(w.tenant); val r1 = s.createRegion(ctx, "R1"); val r2 = s.createRegion(ctx, "R2")
        val hoefe = listOf(s.createDepot(ctx, r1.id, "H1"), s.createDepot(ctx, r1.id, "H2"), s.createDepot(ctx, r2.id, "H3"))
        val first = s.listDepots(ctx, 2, null)
        assertEquals(3, first.size)   // limit + 1: der Aufrufer erkennt die Folgeseite
        val rest = s.listDepots(ctx, 2, first[1].createdAt to first[1].id)
        assertEquals(setOf(hoefe[2].id), rest.map { it.id }.toSet())
        val moved = s.updateDepot(ctx, hoefe[0].id, 1, r2.id, "H1")
        assertEquals(r2.id, moved.regionId); assertEquals(2L, moved.revision)
    }
}
