package de.taktovia.config

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import de.taktovia.config.api.ChangeKind
import de.taktovia.config.api.CreateDraft
import de.taktovia.config.api.RevisionFilter
import de.taktovia.config.api.RevisionState
import de.taktovia.config.api.ScopeKind
import de.taktovia.config.api.UpdateDraft
import de.taktovia.config.internal.JdbcConfigRevisions
import de.taktovia.config.internal.PolicyCatalogRepository
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.Violation
import de.taktovia.platform.problem.ViolationCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DuplicateKeyException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M1-20 / LH-6.1-SYS-002, LH-I.7-CFG-003: Konfigurationsrevisionen (Entwurf, Änderungsprotokoll, As-of-Rekonstruktion) gegen die echte Datenbank
 * unter der Runtime-Rolle. Ein publizierter Stand entsteht im Test über die Zustandsübergänge in der Datenbank (Publikation selbst: WP-M1-22).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigRevisionTest {
    private lateinit var fx: ConfigFixture
    private val repo = Path.of(System.getProperty("taktovia.repoRoot"))
    private val schemaRegistry = SchemaRegistry.withDefaultDialect(Dialects.getDraft202012())

    @BeforeAll fun up() { fx = ConfigFixture() }
    @AfterAll fun down() = fx.close()

    private val t0 = Instant.parse("2026-01-01T00:00:00Z")
    private fun draft(ctx: de.taktovia.platform.context.RequestContext, scope: UUID, values: Map<String, Any?>, from: Instant = t0, to: Instant? = null, reason: String? = "Anpassung",
                      kind: ScopeKind = ScopeKind.REGION) =
        fx.config.revisions.createDraft(ctx, CreateDraft(kind, scope, "1", values, from, to, reason))

    private val serviceConfig = mapOf(
        "service_area_id" to "6d0f5a1e-3c1f-4c0e-9a55-6a1b2d9f1111", "traffic_type" to "AREA_ON_DEMAND", "operator_id" to "6d0f5a1e-3c1f-4c0e-9a55-6a1b2d9f2222",
        "operating_hours" to listOf(mapOf("weekdays" to listOf("MON", "TUE"), "from" to "06:00", "to" to "22:00")), "tariff_id" to "6d0f5a1e-3c1f-4c0e-9a55-6a1b2d9f3333",
    )

    // ---- SYS-002 ----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun aenderung_erzeugt_revision_mit_ersteller_zeitstempel_wirksamkeit() {
        val t = fx.tenant(); val ctx = fx.context(t, subject = "11111111-2222-3333-4444-555555555555"); val region = UUID.randomUUID()
        val before = Instant.now().minusSeconds(2)
        val from = Instant.parse("2026-12-01T05:00:00Z"); val to = Instant.parse("2027-06-01T00:00:00Z")
        val r1 = draft(ctx, region, mapOf("max_wait_min" to 20), from, to, "  Neue Wartezeit  ")
        assertEquals("user:11111111-2222-3333-4444-555555555555", r1.createdBy, "Ersteller aus dem Principal")
        assertTrue(r1.createdAt.isAfter(before) && r1.createdAt.isBefore(Instant.now().plusSeconds(2)), "Zeitstempel der Änderung")
        assertEquals(from, r1.effectiveFrom); assertEquals(to, r1.effectiveTo); assertEquals("Neue Wartezeit", r1.reason)
        assertEquals(RevisionState.DRAFT, r1.state); assertEquals(1L, r1.revision); assertEquals(1L, r1.rowVersion); assertNull(r1.approvedBy); assertNull(r1.publishedAt)
        assertEquals(2L, draft(ctx, region, mapOf("max_wait_min" to 25)).revision, "jede Änderung ist eine neue Revision im Scope")
        assertEquals(1L, draft(ctx, UUID.randomUUID(), mapOf("max_wait_min" to 25)).revision, "andere Scopes zählen für sich")
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND action='config.revision.created' AND object_ref=?", t.value, "config_revision:${r1.id}"))
        assertEquals("user:11111111-2222-3333-4444-555555555555", fx.adminStrings("SELECT actor_ref FROM taktovia.audit_event WHERE tenant_id=? AND object_ref=?", t.value, "config_revision:${r1.id}").single())

        // Ereignis config.revision_created genau einmal, gültig gegen das Ereignisschema
        val payload = fx.adminStrings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id=? AND aggregate_id=? AND event_type='config.revision_created'", t.value, r1.id).single()
        val schema = schemaRegistry.getSchema(Files.readString(repo.resolve("api/events/config.revision_created.v1.schema.json")))
        assertEquals(emptyList<String>(), schema.validate(payload, InputFormat.JSON).map { it.toString() })
    }

    @Test
    fun dienstkonfiguration_umfasst_alle_fuenf_bestandteile_in_einer_revision() {
        val t = fx.tenant(); val ctx = fx.context(t); val service = UUID.randomUUID()
        val r = draft(ctx, service, mapOf("service_config" to serviceConfig, "max_wait_min" to 15), kind = ScopeKind.SERVICE)
        assertEquals(serviceConfig, r.values["service_config"])
        // jede Dienstrevision trägt die vollständige Konfiguration; fehlender oder unbekannter Bestandteil, falsche Werte werden mit Feldverletzung abgewiesen
        fun rejected(values: Map<String, Any?>) = assertThrows(ProblemException::class.java) { draft(ctx, service, values, kind = ScopeKind.SERVICE) }.violations
        assertEquals(listOf(Violation("/values/service_config", ViolationCode.REQUIRED)), rejected(mapOf("max_wait_min" to 15)))
        assertEquals(listOf(Violation("/values/service_config/tariff_id", ViolationCode.REQUIRED)), rejected(mapOf("service_config" to serviceConfig - "tariff_id")))
        assertEquals(listOf(Violation("/values/service_config/operating_hours", ViolationCode.REQUIRED)), rejected(mapOf("service_config" to serviceConfig - "operating_hours")))
        assertEquals(listOf(Violation("/values/service_config/farbe", ViolationCode.UNKNOWN_FIELD)), rejected(mapOf("service_config" to serviceConfig + ("farbe" to "rot"))))
        assertEquals(listOf(Violation("/values/service_config/traffic_type", ViolationCode.NOT_IN_ENUM)), rejected(mapOf("service_config" to serviceConfig + ("traffic_type" to "TAXI"))))
        assertEquals(listOf(Violation("/values/service_config/operator_id", ViolationCode.INVALID_FORMAT)), rejected(mapOf("service_config" to serviceConfig + ("operator_id" to "kein-uuid"))))
        assertEquals(listOf(Violation("/values/service_config", ViolationCode.UNKNOWN_FIELD)), assertThrows(ProblemException::class.java) {
            draft(ctx, UUID.randomUUID(), mapOf("service_config" to serviceConfig), kind = ScopeKind.REGION) }.violations, "Dienstkonfiguration nur auf Ebene Dienst")
        assertEquals(1, fx.revisionRows(t))
    }

    @Test
    fun aenderung_ohne_grund_oder_wirksamkeit_wird_mit_422_abgewiesen() {
        val t = fx.tenant(); val ctx = fx.context(t); val region = UUID.randomUUID()
        fun rejected(reason: String?, from: Instant = t0, to: Instant? = null) = assertThrows(ProblemException::class.java) { draft(ctx, region, mapOf("max_wait_min" to 20), from, to, reason) }
        assertEquals(listOf(Violation("/reason", ViolationCode.REQUIRED)), rejected(null).violations)
        assertEquals(listOf(Violation("/reason", ViolationCode.REQUIRED)), rejected("   ").violations)
        assertEquals(listOf(Violation("/reason", ViolationCode.TOO_SHORT)), rejected("ab").violations)
        assertEquals(listOf(Violation("/reason", ViolationCode.TOO_LONG)), rejected("x".repeat(1001)).violations)
        assertEquals(listOf(Violation("/effective_to", ViolationCode.INTERVAL_INVALID)), rejected("Grund", t0, t0.minusSeconds(1)).violations, "Ende vor Beginn")
        assertEquals(listOf(Violation("/effective_to", ViolationCode.INTERVAL_INVALID)), rejected("Grund", t0, t0).violations, "leeres Intervall (rechts offen)")
        rejected("Grund", t0, t0.minusSeconds(1)).let { assertEquals(422, it.code.status) }
        assertEquals(0, fx.revisionRows(t), "keine Zeile, kein Audit, kein Ereignis")
        assertEquals(0, fx.adminCount("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=?", t.value))
        // unbekannte Schema-Version
        val e = assertThrows(ProblemException::class.java) { fx.config.revisions.createDraft(ctx, CreateDraft(ScopeKind.REGION, region, "77", mapOf("max_wait_min" to 5), t0, null, "Grund")) }
        assertEquals(listOf(Violation("/schema_version", ViolationCode.INVALID_VALUE)), e.violations)
    }

    @Test
    fun fremder_scope_und_fremder_mandant_sind_nicht_vorhanden_404() {
        val a = fx.tenant(); val b = fx.tenant(); val ctxA = fx.context(a); val ctxB = fx.context(b)
        val r = draft(ctxA, UUID.randomUUID(), mapOf("max_wait_min" to 20))
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(ProblemException::class.java) { fx.config.revisions.get(ctxB, r.id) }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(ProblemException::class.java) { fx.config.revisions.updateDraft(ctxB, r.id, 1, UpdateDraft(reason = "Fremd")) }.code)
        // Mandantenscope eines anderen Mandanten: wie nicht vorhanden
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(ProblemException::class.java) { draft(ctxA, b.value, mapOf("max_wait_min" to 20), kind = ScopeKind.TENANT) }.code)
        assertEquals(1L, draft(ctxA, a.value, mapOf("max_wait_min" to 20), kind = ScopeKind.TENANT).revision)
        assertTrue(fx.config.revisions.list(ctxB, RevisionFilter(), 50, null).isEmpty())
        assertEquals(listOf(r.id), fx.config.revisions.list(ctxA, RevisionFilter(scopeKind = ScopeKind.REGION), 50, null).map { it.id })
        assertNull(fx.config.revisions.asOf(ctxB, r.scopeKind, r.scopeId, Instant.now()))
    }

    @Test
    fun parallele_entwuerfe_gleiche_revisionsnummer_genau_einer() {
        val t = fx.tenant(); val ctx = fx.context(t); val region = UUID.randomUUID()
        val impl = JdbcConfigRevisions(fx.ctxTx, PolicyCatalogRepository(), fx.audit, fx.outbox, Clock.systemUTC())

        // Deterministisch: zwei Transaktionen legen dieselbe Revisionsnummer an; die zweite hängt am Unique-Index, bis die erste festschreibt, und scheitert dann.
        val firstInserted = CountDownLatch(1); val secondStarted = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val first = pool.submit<String> {
            fx.ctxTx.run(ctx) { j ->
                impl.insertDraft(j, UUID.randomUUID(), ScopeKind.REGION, region, 1, "1", mapOf("max_wait_min" to 10), t0, null, "user:a", "Erster")
                firstInserted.countDown(); secondStarted.await(5, TimeUnit.SECONDS); Thread.sleep(300)
            }
            "ok"
        }
        val second = pool.submit<String> {
            firstInserted.await(5, TimeUnit.SECONDS); secondStarted.countDown()
            try {
                fx.ctxTx.run(ctx) { j -> impl.insertDraft(j, UUID.randomUUID(), ScopeKind.REGION, region, 1, "1", mapOf("max_wait_min" to 11), t0, null, "user:b", "Zweiter") }
                "ok"
            } catch (e: DuplicateKeyException) { "unique" }
        }
        assertEquals(listOf("ok", "unique"), listOf(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
        pool.shutdown()
        assertEquals(1, fx.adminCount("SELECT count(*) FROM taktovia.config_revision WHERE tenant_id=? AND scope_id=?", t.value, region))

        // Über die API: acht gleichzeitige Anlagen im selben Scope. Ausgang je nach Verzahnung unterschiedlich, die Invarianten gelten immer:
        // Erfolge haben verschiedene, lückenlos fortlaufende Nummern; alle Übrigen erhalten 409 CONFLICT; Zeilen = Erfolge.
        val scope2 = UUID.randomUUID(); val start = CountDownLatch(1); val pool2 = Executors.newFixedThreadPool(8)
        val results = (1..8).map { n -> pool2.submit<Result<Long>> {
            start.await(); runCatching { draft(ctx, scope2, mapOf("max_wait_min" to n), reason = "Parallel $n").revision }
        } }
        start.countDown()
        val done = results.map { it.get(30, TimeUnit.SECONDS) }; pool2.shutdown()
        val ok = done.filter { it.isSuccess }.map { it.getOrThrow() }.sorted()
        assertTrue(ok.isNotEmpty())
        assertEquals((1L..ok.size.toLong()).toList(), ok, "verschiedene, lückenlose Nummern")
        done.filter { it.isFailure }.forEach { assertEquals(ErrorCode.CONFLICT, (it.exceptionOrNull() as ProblemException).code) }
        assertEquals(ok.size, fx.adminCount("SELECT count(*) FROM taktovia.config_revision WHERE tenant_id=? AND scope_id=?", t.value, scope2))
        assertEquals(ok.size, fx.adminCount("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='config.revision_created' AND payload->>'scope_id'=?", t.value, scope2.toString()))
    }

    // ---- Entwurf ändern ---------------------------------------------------------------------------------------------------------------------

    @Test
    fun entwurf_aendern_mit_etag_und_nach_publikation_nicht_mehr() {
        val t = fx.tenant(); val ctx = fx.context(t); val region = UUID.randomUUID()
        val r = draft(ctx, region, mapOf("max_wait_min" to 20, "max_detour_pct" to 30))
        val upd = fx.config.revisions.updateDraft(ctx, r.id, r.rowVersion, UpdateDraft(values = mapOf("max_wait_min" to 25), reason = "Wartezeit präzisiert"))
        assertEquals(2L, upd.rowVersion); assertEquals(1L, upd.revision); assertEquals(mapOf("max_wait_min" to 25), upd.values); assertEquals("Wartezeit präzisiert", upd.reason)
        assertEquals(r.createdBy, upd.createdBy); assertEquals(r.createdAt, upd.createdAt)
        assertEquals(setOf("max_wait_min", "max_detour_pct"), changedKeys(t, r.id).toSet())

        // veralteter ETag -> 412, nichts geändert
        assertEquals(ErrorCode.REVISION_MISMATCH, assertThrows(ProblemException::class.java) { fx.config.revisions.updateDraft(ctx, r.id, 1, UpdateDraft(reason = "Spät")) }.code)
        // ungültiger Wert -> 422, keine Änderung
        val bad = assertThrows(ProblemException::class.java) { fx.config.revisions.updateDraft(ctx, r.id, 2, UpdateDraft(values = mapOf("max_wait_min" to 241))) }
        assertEquals(listOf(Violation("/values/max_wait_min", ViolationCode.ABOVE_MAXIMUM)), bad.violations)
        assertEquals(2L, fx.config.revisions.get(ctx, r.id).rowVersion)
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(ProblemException::class.java) { fx.config.revisions.updateDraft(ctx, r.id, 2, UpdateDraft()) }.code, "nichts zu ändern")
        // Ende löschen und setzen
        val withEnd = fx.config.revisions.updateDraft(ctx, r.id, 2, UpdateDraft(effectiveTo = Instant.parse("2027-01-01T00:00:00Z"), effectiveToSet = true))
        assertEquals(Instant.parse("2027-01-01T00:00:00Z"), withEnd.effectiveTo)
        assertNull(fx.config.revisions.updateDraft(ctx, r.id, 3, UpdateDraft(effectiveTo = null, effectiveToSet = true)).effectiveTo)
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(ProblemException::class.java) { fx.config.revisions.updateDraft(ctx, r.id, 4, UpdateDraft(effectiveTo = t0.minusSeconds(5), effectiveToSet = true)) }.code)

        // nach der Publikation: unveränderlich
        fx.publish(t, r.id)
        val published = fx.config.revisions.get(ctx, r.id)
        assertEquals(RevisionState.PUBLISHED, published.state)
        val e = assertThrows(ProblemException::class.java) { fx.config.revisions.updateDraft(ctx, r.id, published.rowVersion, UpdateDraft(reason = "Heimlich")) }
        assertEquals(ErrorCode.IMMUTABLE_RECORD, e.code)
        assertEquals("Wartezeit präzisiert", fx.config.revisions.get(ctx, r.id).reason)
    }

    private fun changedKeys(t: de.taktovia.platform.tenant.TenantId, id: UUID): List<String> {
        val json = fx.adminStrings("SELECT redacted_changes::text FROM taktovia.audit_event WHERE tenant_id=? AND object_ref=? AND action='config.revision.changed' ORDER BY occurred_at", t.value, "config_revision:$id").first()
        return Regex("\"changed_keys\": \\[(.*?)]").find(json)!!.groupValues[1].split(',').map { it.trim().trim('"').removePrefix("/") }
    }

    @Test
    fun gleichzeitige_aenderung_desselben_entwurfs_mit_gleichem_etag_genau_eine_gewinnt() {
        val t = fx.tenant(); val ctx = fx.context(t)
        val r = draft(ctx, UUID.randomUUID(), mapOf("max_wait_min" to 20))
        val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        val results = (1..2).map { n -> pool.submit<Result<Long>> { start.await(); runCatching { fx.config.revisions.updateDraft(ctx, r.id, 1, UpdateDraft(values = mapOf("max_wait_min" to 20 + n))).rowVersion } } }
        start.countDown(); val done = results.map { it.get(15, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, done.count { it.isSuccess }); assertEquals(listOf(2L), done.filter { it.isSuccess }.map { it.getOrThrow() })
        assertEquals(ErrorCode.REVISION_MISMATCH, (done.single { it.isFailure }.exceptionOrNull() as ProblemException).code)
        assertEquals(2L, fx.config.revisions.get(ctx, r.id).rowVersion)
    }

    // ---- CFG-003 ----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun diff_altwert_neuwert_zeigt_benutzer_zeitpunkt_grund_gueltigkeitsbeginn() {
        val t = fx.tenant(); val alice = fx.context(t, subject = "alice"); val bob = fx.context(t, subject = "bob"); val region = t.value   // Mandantenrahmen: dort sind auch Berichtszeiten setzbar
        val r1 = draft(alice, region, mapOf("max_wait_min" to 20, "max_detour_pct" to 30), reason = "Erstfassung", kind = ScopeKind.TENANT)
        // ohne Vorgänger ist jeder Wert hinzugefügt
        assertEquals(setOf("/max_wait_min" to ChangeKind.ADDED, "/max_detour_pct" to ChangeKind.ADDED), r1.changeLog.changes.map { it.pointer to it.kind }.toSet())
        assertNull(r1.changeLog.predecessorId)
        fx.publish(t, r1.id)

        val from2 = Instant.parse("2026-07-01T04:00:00Z")
        val r2 = draft(bob, region, mapOf("max_wait_min" to 35, "pooling_allowed" to true, "notification_quiet_hours" to mapOf("from" to "22:00", "to" to "06:00")), from2, null, "Sommerfahrplan", ScopeKind.TENANT)
        val log = r2.changeLog
        assertEquals("user:bob", log.changedBy); assertEquals(r2.createdAt, log.changedAt); assertEquals("Sommerfahrplan", log.reason); assertEquals(from2, log.effectiveFrom)
        assertEquals(r1.id, log.predecessorId); assertEquals(1L, log.predecessorRevision)
        val byPointer = log.changes.associateBy { it.pointer }
        assertEquals(setOf("/max_wait_min", "/max_detour_pct", "/pooling_allowed", "/notification_quiet_hours/from", "/notification_quiet_hours/to"), byPointer.keys)
        byPointer.getValue("/max_wait_min").let { assertEquals(ChangeKind.CHANGED, it.kind); assertEquals(20, it.oldValue); assertEquals(35, it.newValue) }
        byPointer.getValue("/max_detour_pct").let { assertEquals(ChangeKind.REMOVED, it.kind); assertEquals(30, it.oldValue); assertNull(it.newValue) }
        byPointer.getValue("/pooling_allowed").let { assertEquals(ChangeKind.ADDED, it.kind); assertNull(it.oldValue); assertEquals(true, it.newValue) }
        assertEquals(ChangeKind.ADDED, byPointer.getValue("/notification_quiet_hours/from").kind, "Objekte werden bis zu den Blättern aufgelöst")
        // die Liste und das Lesen zeigen dasselbe Protokoll
        assertEquals(log, fx.config.revisions.get(alice, r2.id).changeLog)
        assertEquals(log, fx.config.revisions.list(alice, RevisionFilter(scopeId = region), 10, null).single { it.id == r2.id }.changeLog)
    }

    @Test
    fun as_of_liefert_damals_wirksame_revision() {
        val t = fx.tenant(); val ctx = fx.context(t); val region = UUID.randomUUID()
        val r1 = draft(ctx, region, mapOf("max_wait_min" to 10), Instant.parse("2026-01-01T00:00:00Z"), null, "Stand 1")
        val r2 = draft(ctx, region, mapOf("max_wait_min" to 20), Instant.parse("2026-03-01T00:00:00Z"), null, "Stand 2")
        val r3 = draft(ctx, region, mapOf("max_wait_min" to 30), Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"), "Stand 3")
        val r4 = draft(ctx, region, mapOf("max_wait_min" to 40), Instant.parse("2026-02-01T00:00:00Z"), null, "bleibt Entwurf")
        listOf(r1, r2, r3).forEach { fx.publish(t, it.id) }
        fun at(s: String) = fx.config.revisions.asOf(ctx, ScopeKind.REGION, region, Instant.parse(s))

        assertNull(at("2025-12-31T23:59:59Z"), "vor der ersten Revision")
        assertEquals(r1.id, at("2026-01-01T00:00:00Z")!!.id, "Beginn ist eingeschlossen")
        assertEquals(r1.id, at("2026-02-15T12:00:00Z")!!.id, "der Entwurf r4 wirkt nie")
        assertEquals(r2.id, at("2026-03-01T00:00:00Z")!!.id)
        assertEquals(r2.id, at("2026-05-31T23:59:59Z")!!.id)
        assertEquals(r3.id, at("2026-06-01T00:00:00Z")!!.id)
        assertEquals(30, at("2026-08-31T23:59:59Z")!!.values["max_wait_min"])
        // r3 endet am 2026-09-01 (rechts offen); r2 hat kein Ende, ihr Wirken endet nicht durch r3, also gilt danach wieder r2
        assertEquals(r2.id, at("2026-09-01T00:00:00Z")!!.id)
        assertEquals(r2.id, at("2030-01-01T00:00:00Z")!!.id)
        // jede Rekonstruktion nennt die Revisions-ID und die Nummer
        at("2026-04-01T00:00:00Z")!!.let { assertEquals(2L, it.revision); assertEquals(RevisionState.PUBLISHED, it.state); assertNotNull(it.publishedAt) }
        assertNull(fx.config.revisions.asOf(ctx, ScopeKind.REGION, UUID.randomUUID(), Instant.parse("2026-04-01T00:00:00Z")), "anderer Scope")
        assertNull(fx.config.revisions.asOf(ctx, ScopeKind.SERVICE, region, Instant.parse("2026-04-01T00:00:00Z")), "andere Scope-Art")
        assertEquals(RevisionState.DRAFT, fx.config.revisions.get(ctx, r4.id).state)
    }

    @Test
    fun rekonstruktion_am_testobjekt_mit_gebundener_config_revision_id() {
        // Testobjekt: ein Dienst (service) ist an die damals wirksame Revision gebunden (service.config_revision_id), wie es später ein Dispositionsfall ist (M2).
        val t = fx.tenant(); val ctx = fx.context(t); val service = UUID.randomUUID()
        fun rev(maxWait: Int, from: String, reason: String) = draft(ctx, service, mapOf("service_config" to serviceConfig, "max_wait_min" to maxWait), Instant.parse(from), null, reason, ScopeKind.SERVICE)
        val r1 = rev(10, "2026-01-01T00:00:00Z", "Stand 1")
        fx.publish(t, r1.id)
        fx.adminSession { c ->
            c.createStatement().use { it.execute("SET session_replication_role = replica") }   // nur Testdaten: Organisation und Rechtsträger des Dienstes fehlen bewusst
            c.prepareStatement("INSERT INTO taktovia.service(tenant_id,id,operator_id,name,timezone,config_revision_id) VALUES (?,?,?,'Rufbus','Europe/Berlin',?)").use {
                it.setObject(1, t.value); it.setObject(2, service); it.setObject(3, UUID.randomUUID()); it.setObject(4, r1.id); it.executeUpdate()
            }
        }
        val r2 = rev(20, "2026-03-01T00:00:00Z", "Stand 2"); fx.publish(t, r2.id)
        val r3 = rev(30, "2026-06-01T00:00:00Z", "Stand 3"); fx.publish(t, r3.id)

        // „Historischer Fall“ am 2026-02-10: gebunden ist r1; die Rekonstruktion über die gebundene ID und über den Zeitpunkt liefert dieselbe Einstellung samt Revisions-ID.
        val bound = UUID.fromString(fx.adminStrings("SELECT config_revision_id::text FROM taktovia.service WHERE tenant_id=? AND id=?", t.value, service).single())
        assertEquals(r1.id, bound)
        val viaId = fx.config.revisions.get(ctx, bound)
        val viaTime = fx.config.revisions.asOf(ctx, ScopeKind.SERVICE, service, Instant.parse("2026-02-10T12:00:00Z"))!!
        assertEquals(viaId.id, viaTime.id); assertEquals(10, viaId.values["max_wait_min"]); assertEquals(serviceConfig, viaId.values["service_config"])
        // heute gilt längst eine andere Revision, die gebundene bleibt unverändert lesbar
        assertEquals(r3.id, fx.config.revisions.asOf(ctx, ScopeKind.SERVICE, service, Instant.parse("2026-12-01T00:00:00Z"))!!.id)
        assertEquals(10, fx.config.revisions.get(ctx, bound).values["max_wait_min"])
        assertEquals(r2.id, fx.config.revisions.asOf(ctx, ScopeKind.SERVICE, service, Instant.parse("2026-04-01T00:00:00Z"))!!.id)
    }

    @Test
    fun liste_ist_nach_anlage_sortiert_filterbar_und_seitenweise() {
        val t = fx.tenant(); val ctx = fx.context(t); val scope = UUID.randomUUID()
        val ids = (1..5).map { draft(ctx, scope, mapOf("max_wait_min" to it), reason = "Nr $it").id }
        draft(ctx, UUID.randomUUID(), mapOf("max_wait_min" to 1))
        val page1 = fx.config.revisions.list(ctx, RevisionFilter(scopeKind = ScopeKind.REGION, scopeId = scope), 2, null)
        assertEquals(3, page1.size, "limit + 1 Zeilen: es gibt eine Folgeseite")
        val after = page1[1].let { it.createdAt to it.id }
        assertEquals(ids.drop(2), fx.config.revisions.list(ctx, RevisionFilter(scopeId = scope), 10, after).map { it.id })
        assertEquals(emptyList<UUID>(), fx.config.revisions.list(ctx, RevisionFilter(scopeId = scope, state = RevisionState.PUBLISHED), 10, null).map { it.id })
    }
}
