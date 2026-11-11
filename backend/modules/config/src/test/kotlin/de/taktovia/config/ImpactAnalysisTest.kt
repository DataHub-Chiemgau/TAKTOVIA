package de.taktovia.config

import de.taktovia.config.api.ConfigApi
import de.taktovia.config.api.CreateDraft
import de.taktovia.config.api.ImpactFinding
import de.taktovia.config.api.ImpactObjectRef
import de.taktovia.config.api.ImpactProvider
import de.taktovia.config.api.ImpactReportView
import de.taktovia.config.api.ImpactSeverity
import de.taktovia.config.api.ImpactSubject
import de.taktovia.config.api.ScopeKind
import de.taktovia.config.api.UpdateDraft
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Test-Anbieter für M1 (echte Anbieter für Buchungen und Schichten folgen mit WP-M2-04/-23 und WP-M4-17): meldet für einen Mandanten vorgegebene
 * Buchungen und Schichten, sobald die Revision Bediengebiet oder Betriebszeiten des Dienstes ändert. Liest in der übergebenen Transaktion mit
 * (beweist, dass der Anbieter im Mandantenkontext der Analyse läuft).
 */
class ImpactTestProvider : ImpactProvider {
    override val name = "test-anbieter"
    val bookings = ConcurrentHashMap<UUID, MutableList<UUID>>()
    val shifts = ConcurrentHashMap<UUID, MutableList<UUID>>()
    @Volatile var failing = false
    @Volatile var extraFinding: ImpactFinding? = null
    val seenTenants = ConcurrentHashMap.newKeySet<UUID>()

    override fun analyze(j: JdbcOperations, ctx: RequestContext, subject: ImpactSubject): List<ImpactFinding> {
        if (failing) throw IllegalStateException("Anbieter ausgefallen")
        seenTenants += j.queryForObject("SELECT taktovia.current_tenant()", UUID::class.java)!!
        extraFinding?.let { return listOf(it) }
        val touched = subject.impactRelevantChanges.any { it.pointer.startsWith("/service_config/service_area_id") || it.pointer.startsWith("/service_config/operating_hours") }
        if (!touched) return emptyList()
        val b = bookings[ctx.tenant.value].orEmpty().toList(); val s = shifts[ctx.tenant.value].orEmpty().toList()
        return listOfNotNull(
            if (b.isNotEmpty()) ImpactFinding("BOOKING", ImpactSeverity.BLOCKING, b.size.toLong(), b.map { ImpactObjectRef("BOOKING", it) }) else null,
            if (s.isNotEmpty()) ImpactFinding("SHIFT", ImpactSeverity.WARNING, s.size.toLong(), s.map { ImpactObjectRef("SHIFT", it) }) else null,
        )
    }
}

/**
 * WP-M1-23 / LH-I.7-CFG-007: Wirkungsanalyse vor Publikation gegen die echte Datenbank unter der Runtime-Rolle. Mit Test-Anbieter; die Originalabnahme mit
 * echten Buchungen und Schichten folgt mit WP-M2-04, WP-M2-23, WP-M4-17.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImpactAnalysisTest {
    private lateinit var fx: ConfigFixture
    private lateinit var api: ConfigApi
    private val provider = ImpactTestProvider()

    @BeforeAll fun up() { fx = ConfigFixture(); api = ConfigApi.create(fx.ctxTx, fx.audit, fx.outbox, impactProviders = listOf(provider)) }
    @AfterAll fun down() = fx.close()

    private val t0 = Instant.parse("2026-01-01T00:00:00Z")
    private val areaBig = "6d0f5a1e-3c1f-4c0e-9a55-6a1b2d9f1111"
    private val areaSmall = "6d0f5a1e-3c1f-4c0e-9a55-6a1b2d9f4444"
    private fun serviceConfig(area: String = areaBig, to: String = "22:00") = mapOf(
        "service_area_id" to area, "traffic_type" to "AREA_ON_DEMAND", "operator_id" to "6d0f5a1e-3c1f-4c0e-9a55-6a1b2d9f2222",
        "operating_hours" to listOf(mapOf("weekdays" to listOf("MON", "TUE"), "from" to "06:00", "to" to to)), "tariff_id" to "6d0f5a1e-3c1f-4c0e-9a55-6a1b2d9f3333",
    )

    private class World(val tenant: TenantId, val ctx: RequestContext, val service: UUID, val published: UUID)

    /** Mandant mit einem publizierten Dienststand (großes Bediengebiet) und Buchungen/Schichten im Bestand. */
    private fun world(withObjects: Boolean = true): World {
        val t = fx.tenant(); val ctx = fx.context(t); val service = UUID.randomUUID()
        val r1 = api.revisions.createDraft(ctx, CreateDraft(ScopeKind.SERVICE, service, "1", mapOf("service_config" to serviceConfig()), t0, null, "Erststand"))
        fx.publish(t, r1.id)
        if (withObjects) {
            provider.bookings[t.value] = mutableListOf(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
            provider.shifts[t.value] = mutableListOf(UUID.randomUUID())
        }
        return World(t, ctx, service, r1.id)
    }

    private fun shrink(w: World, area: String = areaSmall, to: String = "22:00") =
        api.revisions.createDraft(w.ctx, CreateDraft(ScopeKind.SERVICE, w.service, "1", mapOf("service_config" to serviceConfig(area, to)), t0.plusSeconds(86400), null, "Bediengebiet verkleinert"))

    private fun analyze(w: World, id: UUID, ctx: RequestContext = w.ctx): ImpactReportView {
        val v = api.revisions.get(ctx, id).rowVersion
        return api.impact.analyze(ctx, id, v)
    }

    private fun confirm(w: World, rev: UUID, report: UUID?, ctx: RequestContext = w.ctx) =
        fx.tx.run(w.tenant) { j -> api.impact.confirmForPublication(j, ctx, rev, report) }

    private fun problem(block: () -> Any?): ProblemException = assertThrows(ProblemException::class.java) { block() }

    // ---- Positiv ----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun verkleinerung_bediengebiet_loest_konfliktpruefung_aus() {
        val w = world(); val draft = shrink(w)
        val report = analyze(w, draft.id)
        assertEquals(draft.id, report.revisionId)
        assertEquals(ImpactSeverity.BLOCKING, report.highestSeverity)
        assertEquals(4L, report.conflictCount, "3 Buchungen und 1 Schicht")
        val byKind = report.affected.associateBy { it.kind }
        assertEquals(setOf("BOOKING", "SHIFT"), byKind.keys)
        assertEquals(3L, byKind.getValue("BOOKING").count); assertEquals(ImpactSeverity.BLOCKING, byKind.getValue("BOOKING").severity)
        assertEquals(provider.bookings[w.tenant.value]!!.toSet(), byKind.getValue("BOOKING").refs.map { it.id }.toSet(), "die vom Anbieter gemeldeten Buchungen")
        assertEquals(provider.shifts[w.tenant.value]!!.toSet(), byKind.getValue("SHIFT").refs.map { it.id }.toSet())
        assertTrue(provider.seenTenants.contains(w.tenant.value), "Anbieter lief im Mandantenkontext der Analyse")
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.impact_report WHERE tenant_id=? AND config_revision_id=?", w.tenant.value, draft.id))
    }

    @Test
    fun bericht_ohne_konflikte() {
        val w = world(); provider.bookings.remove(w.tenant.value); provider.shifts.remove(w.tenant.value)
        val draft = shrink(w)
        val report = analyze(w, draft.id)
        assertEquals(ImpactSeverity.NONE, report.highestSeverity); assertEquals(0L, report.conflictCount); assertTrue(report.affected.isEmpty())
        // Änderung, die den Anbieter nicht berührt, ist ebenfalls konfliktfrei, auch wenn Bestand existiert
        val w2 = world(); val other = api.revisions.createDraft(w2.ctx, CreateDraft(ScopeKind.TENANT, w2.tenant.value, "1", mapOf("max_wait_min" to 20), t0, null, "Wartezeit"))
        assertEquals(ImpactSeverity.NONE, analyze(w2, other.id).highestSeverity)
        confirm(w, draft.id, report.id)   // ein Bericht ohne Konflikte erlaubt die Publikation
    }

    @Test
    fun bericht_ist_nachtraeglich_ueber_id_abrufbar_und_publikation_steht_im_audit() {
        val w = world(); val draft = shrink(w); val report = analyze(w, draft.id)
        val read = api.impact.get(fx.context(w.tenant, scopes = setOf("config:read")), report.id)
        assertEquals(report, read, "unverändert abrufbar")
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='config.impact.analyzed' AND object_ref=?", w.tenant.value, "impact_report:${report.id}"))
        val ref = confirm(w, draft.id, report.id)
        assertEquals(report.id, ref.id); assertEquals(report.valuesHash, ref.valuesHash)
        val changes = fx.adminStrings("SELECT redacted_changes::text FROM taktovia.audit_event WHERE tenant_id=? AND action='config.impact.bound' AND object_ref=?", w.tenant.value, "config_revision:${draft.id}").single()
        assertTrue(changes.contains(report.id.toString()) && changes.contains(report.valuesHash), "Audit der Publikation referenziert den Bericht: $changes")
    }

    @Test
    fun impact_pflichtige_parameter_sind_im_katalog_gekennzeichnet() {
        val w = world(withObjects = false)
        val items = api.parameters.catalog(w.ctx).parameters.associateBy { it.name }
        assertTrue(items.getValue("pickup_window_min").impactRequired)
        assertEquals("Bei Änderung bereits bestätigte Buchungen prüfen", items.getValue("pickup_window_min").impactHint)
        assertTrue(items.getValue("service_config").impactRequired)
        assertFalse(items.getValue("reporting_timezone").impactRequired)
    }

    // ---- Negativ ----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun publikation_ohne_bericht_wird_abgewiesen() {
        val w = world(); val draft = shrink(w)
        val e = problem { confirm(w, draft.id, null) }
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code)
        assertEquals(listOf(Violation("/impact_report_id", ViolationCode.REQUIRED, "Ohne Wirkungsbericht keine Publikation")), e.violations)
        val unknown = problem { confirm(w, draft.id, UUID.randomUUID()) }
        assertEquals(ErrorCode.VALIDATION_FAILED, unknown.code); assertEquals("/impact_report_id", unknown.violations.single().pointer)
    }

    @Test
    fun publikation_mit_fremdem_bericht_wird_abgewiesen() {
        val a = world(); val b = world()
        val da = shrink(a); val db = shrink(b)
        val reportB = analyze(b, db.id)
        // Bericht des Mandanten B im Kontext von A: nicht sichtbar, nicht von „unbekannt“ unterscheidbar
        val e = problem { confirm(a, da.id, reportB.id) }
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code); assertEquals(ViolationCode.INVALID_VALUE, e.violations.single().code)
        assertEquals(ErrorCode.NOT_FOUND, problem { api.impact.get(a.ctx, reportB.id) }.code, "Bericht eines anderen Mandanten nicht lesbar")
        assertEquals(ErrorCode.NOT_FOUND, problem { api.impact.analyze(a.ctx, db.id, 1) }.code, "Revision eines anderen Mandanten wie nicht vorhanden")
        // ... und kein Bericht zu einer anderen Revision desselben Mandanten
        val d2 = shrink(a, areaBig, "20:00"); val r2 = analyze(a, d2.id)
        assertEquals(ErrorCode.CONFLICT, problem { confirm(a, da.id, r2.id) }.code)
    }

    @Test
    fun publikation_mit_veraltetem_bericht_wird_abgewiesen() {
        val w = world(); val draft = shrink(w); val report = analyze(w, draft.id)
        api.revisions.updateDraft(w.ctx, draft.id, api.revisions.get(w.ctx, draft.id).rowVersion, UpdateDraft(values = mapOf("service_config" to serviceConfig(areaSmall, "18:00"))))
        val e = problem { confirm(w, draft.id, report.id) }
        assertEquals(ErrorCode.CONFLICT, e.code)
        // neue Analyse zum geänderten Stand erlaubt die Publikation wieder
        val fresh = analyze(w, draft.id)
        assertNotEquals(report.valuesHash, fresh.valuesHash)
        confirm(w, draft.id, fresh.id)
    }

    @Test
    fun analyse_ohne_konfigurationsrecht_wird_verweigert() {
        val w = world(); val draft = shrink(w)
        val reader = fx.context(w.tenant, roles = setOf("dispatcher"), scopes = setOf("config:read"))
        assertEquals(ErrorCode.FORBIDDEN, problem { api.impact.analyze(reader, draft.id, 1) }.code)
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.impact_report WHERE tenant_id=?", w.tenant.value))
    }

    @Test
    fun analyse_mit_veraltetem_stand_und_publizierter_revision() {
        val w = world(); val draft = shrink(w)
        val stale = problem { api.impact.analyze(w.ctx, draft.id, 99) }
        assertEquals(ErrorCode.REVISION_MISMATCH, stale.code)
        assertEquals(ErrorCode.IMMUTABLE_RECORD, problem { analyze(w, w.published) }.code, "publizierte Revision")
        assertEquals(ErrorCode.NOT_FOUND, problem { api.impact.analyze(w.ctx, UUID.randomUUID(), 1) }.code)
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.impact_report WHERE tenant_id=?", w.tenant.value))
    }

    @Test
    fun bericht_enthaelt_nur_objektverweise_und_anzahlen() {
        val w = world(); val draft = shrink(w); val report = analyze(w, draft.id)
        val json = fx.adminStrings("SELECT affected::text FROM taktovia.impact_report WHERE tenant_id=? AND id=?", w.tenant.value, report.id).single()
        val tree = tools.jackson.databind.json.JsonMapper.builder().build().readTree(json)
        tree.values().forEach { a ->
            assertEquals(setOf("kind", "severity", "count", "object_refs"), a.propertyNames().toSet())
            a.get("object_refs").values().forEach { r -> assertEquals(setOf("kind", "id"), r.propertyNames().toSet()); UUID.fromString(r.get("id").asString()) }
        }
        // Verweisarten mit Freitext (z. B. ein Name) lehnt der Dienst ab: kein Bericht
        provider.extraFinding = ImpactFinding("BOOKING", ImpactSeverity.INFO, 1, listOf(ImpactObjectRef("Max Mustermann", UUID.randomUUID())))
        try { assertEquals(ErrorCode.SERVICE_UNAVAILABLE, problem { analyze(w, draft.id) }.code) } finally { provider.extraFinding = null }
    }

    @Test
    fun ausfall_eines_anbieters_erzeugt_keinen_bericht() {
        val w = world(); val draft = shrink(w)
        provider.failing = true
        try { assertEquals(ErrorCode.SERVICE_UNAVAILABLE, problem { analyze(w, draft.id) }.code) } finally { provider.failing = false }
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.impact_report WHERE tenant_id=?", w.tenant.value), "kein „keine Konflikte“ aus einem Fehler")
    }

    @Test
    fun bericht_ist_unveraenderlich_und_mandantengetrennt_in_der_datenbank() {
        val w = world(); val other = world(); val draft = shrink(w); val report = analyze(w, draft.id)
        fun fails(sql: String, tenant: TenantId) = assertThrows(Exception::class.java) { fx.tx.run(tenant) { j -> j.update(sql) } }
        fails("UPDATE taktovia.impact_report SET conflict_count = 0", w.tenant)
        fails("DELETE FROM taktovia.impact_report", w.tenant)
        assertEquals(0, fx.tx.run(other.tenant) { j -> j.queryForObject("SELECT count(*) FROM taktovia.impact_report", Int::class.java)!! }, "Mandant B sieht keinen Bericht von A")
        // Bericht mit fremder Revision (Fremdschlüssel (tenant_id, config_revision_id)) oder fremder tenant_id wird abgewiesen
        assertThrows(Exception::class.java) { fx.tx.run(other.tenant) { j -> j.update(
            "INSERT INTO taktovia.impact_report (tenant_id, id, config_revision_id, revision_row_version, values_hash, schema_version, affected_digest, highest_severity, conflict_count, affected, created_by) " +
                "VALUES (taktovia.current_tenant(), gen_random_uuid(), ?, 1, repeat('a',64), '1', repeat('a',64), 'NONE', 0, '[]'::jsonb, 'user:x1')", draft.id) } }
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.impact_report WHERE id=?", report.id))
    }

    // ---- Konkurrierend ----------------------------------------------------------------------------------------------------------------------

    @Test
    fun neue_betroffene_objekte_zwischen_bericht_und_publikation_werden_erkannt() {
        val w = world(); val draft = shrink(w); val report = analyze(w, draft.id)
        provider.bookings.getValue(w.tenant.value) += UUID.randomUUID()    // nach der Analyse bestätigt jemand eine weitere Buchung
        assertEquals(ErrorCode.CONFLICT, problem { confirm(w, draft.id, report.id) }.code)
        val fresh = analyze(w, draft.id)
        assertEquals(4L, fresh.affected.first { it.kind == "BOOKING" }.count)
        confirm(w, draft.id, fresh.id)
    }

    @Test
    fun revision_wird_nach_berichtserstellung_geaendert_publikation_scheitert() {
        val w = world(); val draft = shrink(w); val report = analyze(w, draft.id)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val confirmed = CountDownLatch(1); val release = CountDownLatch(1)
            // Publikationstransaktion: bestätigt den Bericht und hält die Revision gesperrt, bis sie endet
            val publication = pool.submit { fx.tx.run(w.tenant) { j -> api.impact.confirmForPublication(j, w.ctx, draft.id, report.id); confirmed.countDown(); release.await(10, TimeUnit.SECONDS) } }
            assertTrue(confirmed.await(10, TimeUnit.SECONDS))
            // gleichzeitige Änderung des Entwurfs: wartet auf das Ende der Publikation, die Revision ändert sich nicht mittendrin
            val rowVersion = api.revisions.get(w.ctx, draft.id).rowVersion
            val change = pool.submit { api.revisions.updateDraft(w.ctx, draft.id, rowVersion, UpdateDraft(values = mapOf("service_config" to serviceConfig(areaSmall, "17:00")))) }
            assertThrows(java.util.concurrent.TimeoutException::class.java) { change.get(700, TimeUnit.MILLISECONDS) }
            release.countDown(); publication.get(10, TimeUnit.SECONDS); change.get(10, TimeUnit.SECONDS)
        } finally { pool.shutdownNow() }
        // Die Änderung kam nach der Bindung: ein erneuter Publikationsversuch mit demselben Bericht scheitert
        assertEquals(ErrorCode.CONFLICT, problem { confirm(w, draft.id, report.id) }.code)
    }

    @Test
    fun gleichzeitige_analyse_und_aenderung_binden_den_bericht_an_genau_einen_stand() {
        repeat(8) {
            val w = world(); val draft = shrink(w)
            val v = api.revisions.get(w.ctx, draft.id).rowVersion
            val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
            try {
                val a = pool.submit<Result<ImpactReportView>> { start.await(); runCatching { api.impact.analyze(w.ctx, draft.id, v) } }
                val u = pool.submit<Result<Any>> { start.await(); runCatching { api.revisions.updateDraft(w.ctx, draft.id, v, UpdateDraft(values = mapOf("service_config" to serviceConfig(areaSmall, "16:00")))) } }
                start.countDown()
                val ra = a.get(15, TimeUnit.SECONDS); val ru = u.get(15, TimeUnit.SECONDS)
                assertTrue(ru.isSuccess, "die Änderung mit aktuellem ETag gelingt immer")
                if (ra.isSuccess) {
                    // Analyse vor der Änderung: Bericht gehört zum alten Stand, die Publikation damit zum neuen Stand scheitert
                    assertEquals(ErrorCode.CONFLICT, problem { confirm(w, draft.id, ra.getOrThrow().id) }.code)
                } else {
                    assertEquals(ErrorCode.REVISION_MISMATCH, (ra.exceptionOrNull() as ProblemException).code)
                    assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.impact_report WHERE tenant_id=?", w.tenant.value))
                }
            } finally { pool.shutdownNow() }
        }
    }
}
