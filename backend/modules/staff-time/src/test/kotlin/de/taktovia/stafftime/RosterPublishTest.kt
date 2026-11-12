package de.taktovia.stafftime

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.stafftime.api.PublishRoster
import de.taktovia.stafftime.api.PublishedRoster
import de.taktovia.stafftime.api.RecordTimeEvent
import de.taktovia.stafftime.api.RosterViewKind
import de.taktovia.stafftime.api.TimeEventKind
import de.taktovia.stafftime.api.TimeEventSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WP-M4-14 / LH-6.23-SCH-005: Veröffentlichung des Dienstplans gegen die echte Datenbank (Runtime-Rolle): Entwurf und veröffentlichter Plan getrennt, Fahrer-Eigensicht, Konflikt-Sperre,
 * Idempotenz, Benachrichtigung nur der geänderten Personen und konkurrierende Publikationen.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RosterPublishTest {
    private lateinit var fx: StaffFixture
    @BeforeAll fun up() { fx = StaffFixture() }
    @AfterAll fun down() = fx.close()

    private val from = Instant.parse("2026-12-01T00:00:00Z")
    private fun etag(ctx: RequestContext, rosterId: UUID) = fx.staff.rosters.get(ctx, rosterId).etag
    private fun cmd(ctx: RequestContext, rosterId: UUID, key: String? = null, reason: String = "Freigabe Woche 49") = PublishRoster(from, reason, UUID.randomUUID(), etag(ctx, rosterId), key)
    private fun publish(ctx: RequestContext, rosterId: UUID, key: String? = null): PublishedRoster = fx.staff.publishedRosters.publish(ctx, rosterId, cmd(ctx, rosterId, key))
    private fun self(w: RosterTestWorld, p: RosterTestWorld.Person) = StaffM4Support.employee(w.tenant, UUID.fromString(p.subject))
    private fun own(w: RosterTestWorld, p: RosterTestWorld.Person) = fx.staff.publishedRosters.ownShifts(self(w, p), LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 6))
    private fun events(w: RosterTestWorld, rosterId: UUID) = fx.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id = ? AND aggregate_id = ? AND event_type = 'staff.roster_published' ORDER BY aggregate_revision", w.tenant.value, rosterId)

    @Test
    fun fahrer_sieht_vor_veroeffentlichung_keine_aenderung() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val p = w.person(); val r = w.roster(ctx)
        val s1 = fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-01", start = "06:00", end = "12:00", driver = p.driverId))
        assertEquals(emptyList<Any>(), own(w, p), "vor der ersten Veröffentlichung sieht der Fahrer nichts aus dem Entwurf")
        val first = publish(ctx, r.rosterId)
        assertEquals(1L, first.revision); assertEquals(listOf(p.driverId), first.changedPersons)
        val seen = own(w, p).single(); assertEquals(s1.id, seen.shiftId); assertEquals(1L, seen.rosterRevision)
        // neuer Entwurf: Schicht verschieben, weitere Schicht anlegen
        val release = fx.count("SELECT count(*) FROM taktovia.resource_release WHERE tenant_id = ?", w.tenant.value)
        fx.staff.rosters.openDraft(ctx, r.rosterId, etag(ctx, r.rosterId))
        val draftShift = fx.staff.rosterShifts.list(ctx, r.rosterId, 10, null).single()
        fx.staff.rosterShifts.update(ctx, r.rosterId, draftShift.id, draftShift.revision, w.input(day = "2026-12-01", start = "07:00", end = "13:00", driver = p.driverId))
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-02", start = "06:00", end = "12:00", driver = p.driverId))
        // Fahrer: unverändert der vorherige veröffentlichte Stand (keine Planverschiebung aus dem Entwurf)
        val before = own(w, p).single()
        assertEquals(s1.id, before.shiftId); assertEquals(Instant.parse("2026-12-01T05:00:00Z"), before.startsAt)
        // operative Schichtansicht: nur Schichten der veröffentlichten Revision; der Entwurf ist getrennt
        assertEquals(listOf(s1.id), fx.staff.rosterShifts.list(ctx, r.rosterId, 10, null, RosterViewKind.PUBLISHED).map { it.id })
        assertEquals(2, fx.staff.rosterShifts.list(ctx, r.rosterId, 10, null).size)
        val second = publish(ctx, r.rosterId)
        assertEquals(2L, second.revision)
        val after = own(w, p)
        assertEquals(2, after.size); assertTrue(after.all { it.rosterRevision == 2L }, "genau die neue veröffentlichte Revision")
        assertEquals(Instant.parse("2026-12-01T06:00:00Z"), after.first().startsAt)
        // Meldung mit der neuen Planrevision (Outbox), Audit mit Akteur, Revision und Zeit, keine Fahrzeugfreigabe
        val ev = events(w, r.rosterId)
        assertEquals(2, ev.size); assertTrue(ev.last().contains("\"revision\": 2"), ev.last()); assertTrue(ev.last().contains(p.subject))
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND object_ref = ? AND action = 'staff.roster.published' AND actor_ref = ?", w.tenant.value, "roster:${r.rosterId}", "user:" + ctx.actor.subject))
        assertEquals(release, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE tenant_id = ?", w.tenant.value), "die Veröffentlichung erteilt keine technische Fahrzeugfreigabe")
        // die ersetzte Revision bleibt unverändert erhalten
        assertEquals(listOf("SUPERSEDED", "PUBLISHED"), fx.strings("SELECT state FROM taktovia.roster_revision WHERE tenant_id = ? AND roster_id = ? ORDER BY revision", w.tenant.value, r.rosterId))
    }

    @Test
    fun ereignis_entspricht_dem_vertragsschema() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val p = w.person(); val r = w.roster(ctx)
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-01", start = "06:00", end = "12:00", driver = p.driverId))
        publish(ctx, r.rosterId)
        val registry = com.networknt.schema.SchemaRegistry.withDefaultDialect(com.networknt.schema.dialect.Dialects.getDraft202012())
        val schema = registry.getSchema(java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("taktovia.repoRoot")).resolve("api/events/staff.roster_published.v1.schema.json")))
        val payload = events(w, r.rosterId).single()
        assertEquals(emptyList<String>(), schema.validate(payload, com.networknt.schema.InputFormat.JSON).map { it.toString() }, payload)
    }

    @Test
    fun publikation_mit_konflikt_409_ohne_revision_und_ereignis() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val p = w.person(); val r = w.roster(ctx)
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-01", start = "06:00", end = "12:00", driver = p.driverId))
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-01", start = "08:00", end = "14:00", driver = p.driverId))   // Doppelbelegung im Entwurf
        val e = assertThrows<ProblemException> { publish(ctx, r.rosterId) }
        assertEquals(ErrorCode.CONFLICT, e.code); assertTrue(e.violations.any { it.code == ViolationCode.ROSTER_DOUBLE_BOOKING }, "Konfliktliste mit stabilem Code")
        assertEquals(listOf("DRAFT"), fx.strings("SELECT state FROM taktovia.roster_revision WHERE tenant_id = ? AND roster_id = ?", w.tenant.value, r.rosterId), "keine Revision")
        assertEquals(0, events(w, r.rosterId).size, "kein Ereignis")
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'staff.roster.published'", w.tenant.value))
        assertEquals(setOf("DRAFT"), fx.strings("SELECT state FROM taktovia.staff_shift WHERE tenant_id = ? AND roster_id = ?", w.tenant.value, r.rosterId).toSet())
    }

    @Test
    fun rechte_stand_und_eingabe_werden_geprueft() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val p = w.person(); val r = w.roster(ctx)
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-01", start = "06:00", end = "12:00", driver = p.driverId))
        val good = cmd(ctx, r.rosterId)
        assertEquals(ErrorCode.FORBIDDEN, assertThrows<ProblemException> { fx.staff.publishedRosters.publish(w.driver(), r.rosterId, good) }.code, "fehlender Scope rosters:publish")
        val outsider = w.planner(depots = listOf(w.newDepot("anderes Depot")))
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.staff.publishedRosters.publish(outsider, r.rosterId, good) }.code, "ohne Depotrecht nicht auffindbar")
        assertEquals(ErrorCode.REVISION_MISMATCH, assertThrows<ProblemException> { fx.staff.publishedRosters.publish(ctx, r.rosterId, good.copy(expectedEtag = good.expectedEtag + 7)) }.code, "veralteter Stand")
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows<ProblemException> { fx.staff.publishedRosters.publish(ctx, r.rosterId, good.copy(reason = "ab")) }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.staff.publishedRosters.publish(ctx, UUID.randomUUID(), good) }.code)
        // nichts davon hat etwas veröffentlicht; danach gibt es ohne Entwurf nichts zu veröffentlichen
        assertEquals(listOf("DRAFT"), fx.strings("SELECT state FROM taktovia.roster_revision WHERE tenant_id = ? AND roster_id = ?", w.tenant.value, r.rosterId))
        publish(ctx, r.rosterId)
        assertEquals(ErrorCode.CONFLICT, assertThrows<ProblemException> { fx.staff.publishedRosters.publish(ctx, r.rosterId, cmd(ctx, r.rosterId)) }.code)
        // fremder Mandant
        val other = RosterTestWorld(fx)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.staff.publishedRosters.publish(other.planner(), r.rosterId, good) }.code)
    }

    @Test
    fun parallele_publikation_genau_eine_gewinnt() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val p = w.person(); val r = w.roster(ctx)
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-01", start = "06:00", end = "12:00", driver = p.driverId))
        val c = cmd(ctx, r.rosterId)
        val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(4)
        val results = (1..4).map { pool.submit<Result<PublishedRoster>> { start.await(); runCatching { fx.staff.publishedRosters.publish(ctx, r.rosterId, c) } } }
        start.countDown()
        val all = results.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, all.count { it.isSuccess }, "genau eine Publikation gewinnt")
        all.filter { it.isFailure }.forEach { assertEquals(ErrorCode.REVISION_MISMATCH, (it.exceptionOrNull() as ProblemException).code, "die anderen erhalten 412") }
        assertEquals(listOf("PUBLISHED"), fx.strings("SELECT state FROM taktovia.roster_revision WHERE tenant_id = ? AND roster_id = ?", w.tenant.value, r.rosterId))
        assertEquals(1, events(w, r.rosterId).size)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND object_ref = ? AND action = 'staff.roster.published'", w.tenant.value, "roster:${r.rosterId}"))
    }

    @Test
    fun idempotenz_gleicher_schluessel_dieselbe_revision() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val p = w.person(); val r = w.roster(ctx)
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-01", start = "06:00", end = "12:00", driver = p.driverId))
        val c = cmd(ctx, r.rosterId, key = "key-1")
        val a = fx.staff.publishedRosters.publish(ctx, r.rosterId, c)
        val b = fx.staff.publishedRosters.publish(ctx, r.rosterId, c)   // Wiederholung mit veraltetem Stand und gleichem Schlüssel
        assertFalse(a.replayed); assertTrue(b.replayed)
        assertEquals(a.revision, b.revision); assertEquals(a.auditEventId, b.auditEventId); assertEquals(a.etag, b.etag); assertEquals(a.publishedAt, b.publishedAt)
        assertEquals(1, events(w, r.rosterId).size, "kein zweites Ereignis"); assertEquals(1, fx.count("SELECT count(*) FROM taktovia.roster_revision WHERE tenant_id = ? AND roster_id = ?", w.tenant.value, r.rosterId))
        val other = assertThrows<ProblemException> { fx.staff.publishedRosters.publish(ctx, r.rosterId, c.copy(reason = "ein anderer Grund")) }
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, other.code, "gleicher Schlüssel, anderer Inhalt")
        assertEquals(1, events(w, r.rosterId).size)
    }

    @Test
    fun benachrichtigung_nur_an_geaenderte_personen() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val p1 = w.person(); val p2 = w.person(); val p3 = w.person(); val r = w.roster(ctx)
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-01", start = "06:00", end = "12:00", driver = p1.driverId))
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-01", start = "06:00", end = "12:00", driver = p2.driverId))
        val first = publish(ctx, r.rosterId)
        assertEquals(setOf(p1.driverId, p2.driverId), first.changedPersons.toSet(), "erste Veröffentlichung: alle geplanten Personen")
        // Entwurf: nur die Schicht von p1 verschieben; p3 bekommt eine neue Schicht; p2 bleibt unverändert
        fx.staff.rosters.openDraft(ctx, r.rosterId, etag(ctx, r.rosterId))
        val mine = fx.staff.rosterShifts.list(ctx, r.rosterId, 10, null).single { it.driverRef == p1.driverId }
        fx.staff.rosterShifts.update(ctx, r.rosterId, mine.id, mine.revision, w.input(day = "2026-12-01", start = "07:00", end = "13:00", driver = p1.driverId))
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-03", start = "06:00", end = "12:00", driver = p3.driverId))
        val second = publish(ctx, r.rosterId)
        assertEquals(setOf(p1.driverId, p3.driverId), second.changedPersons.toSet(), "nur Personen mit geänderten Schichten")
        val payload = events(w, r.rosterId).last()
        assertTrue(payload.contains(p1.subject) && payload.contains(p3.subject), payload); assertFalse(payload.contains(p2.subject), "unveränderte Person wird nicht benachrichtigt")
        // dritte Veröffentlichung ohne inhaltliche Änderung: niemand
        fx.staff.rosters.openDraft(ctx, r.rosterId, etag(ctx, r.rosterId))
        assertEquals(emptyList<UUID>(), publish(ctx, r.rosterId).changedPersons)
    }

    @Test
    fun publikation_parallel_zu_konfliktausloesender_zuweisung_nie_veroeffentlichter_konflikt() {
        repeat(6) {
            val w = RosterTestWorld(fx); val ctx = w.planner(); val p = w.person(); val r = w.roster(ctx)
            fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-01", start = "06:00", end = "12:00", driver = p.driverId))
            val c = cmd(ctx, r.rosterId)
            val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
            val pub = pool.submit<Result<PublishedRoster>> { start.await(); runCatching { fx.staff.publishedRosters.publish(ctx, r.rosterId, c) } }
            val add = pool.submit<Result<Any>> { start.await(); runCatching { fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-01", start = "08:00", end = "14:00", driver = p.driverId)) } }   // Doppelbelegung
            start.countDown(); val pr = pub.get(60, TimeUnit.SECONDS); val ar = add.get(60, TimeUnit.SECONDS); pool.shutdown()
            // Invariante: nie eine veröffentlichte Revision mit Doppelbelegung; entweder war die Zuweisung zuerst (Publikation 409) oder die Publikation (Zuweisung 409, kein Entwurf mehr)
            val published = fx.count("SELECT count(*) FROM taktovia.staff_shift WHERE tenant_id = ? AND roster_id = ? AND state = 'PUBLISHED'", w.tenant.value, r.rosterId)
            if (pr.isSuccess) { assertEquals(1, published); assertTrue(ar.isFailure, "Zuweisung nach der Publikation hat keinen Entwurf mehr"); assertEquals(ErrorCode.CONFLICT, (ar.exceptionOrNull() as ProblemException).code) }
            else { assertEquals(0, published); assertTrue(ar.isSuccess); assertTrue((pr.exceptionOrNull() as ProblemException).code in setOf(ErrorCode.CONFLICT, ErrorCode.REVISION_MISMATCH), "Konflikt (409) oder veralteter Stand (412), nie ein veröffentlichter Konflikt") }
        }
    }

    @Test
    fun fahrerabruf_waehrend_der_publikation_sieht_nie_einen_mischstand() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val p = w.person(); val r = w.roster(ctx)
        val days = (1..5).map { "2026-12-0$it" }
        days.forEach { fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = it, start = "06:00", end = "12:00", driver = p.driverId)) }
        publish(ctx, r.rosterId)
        fx.staff.rosters.openDraft(ctx, r.rosterId, etag(ctx, r.rosterId))
        fx.staff.rosterShifts.list(ctx, r.rosterId, 10, null).forEach { s -> fx.staff.rosterShifts.update(ctx, r.rosterId, s.id, s.revision, w.input(day = s.operatingDay.toString(), start = "07:00", end = "13:00", driver = p.driverId)) }
        val stop = AtomicBoolean(false); val mixed = java.util.concurrent.atomic.AtomicInteger(); val polls = java.util.concurrent.atomic.AtomicInteger()
        val pool = Executors.newFixedThreadPool(3)
        val readers = (1..2).map { pool.submit { while (!stop.get()) { val rows = own(w, p); polls.incrementAndGet(); if (rows.size != 5 || rows.map { it.rosterRevision }.toSet().size != 1) mixed.incrementAndGet() } } }
        val c = cmd(ctx, r.rosterId)
        Thread.sleep(100); val done = fx.staff.publishedRosters.publish(ctx, r.rosterId, c); Thread.sleep(100)
        stop.set(true); readers.forEach { it.get(30, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(0, mixed.get(), "ein Abruf sieht entweder vollständig die alte oder vollständig die neue Revision (${polls.get()} Abrufe)")
        assertTrue(polls.get() > 0); assertEquals(2L, done.revision)
        assertEquals(setOf(2L), own(w, p).map { it.rosterRevision }.toSet())
    }

    @Test
    fun publikation_parallel_zu_stempel_hinweis_konsistent_ohne_datenaenderung() {
        val w = RosterTestWorld(fx); val ctx = w.planner(); val p = w.person(); val r = w.roster(ctx)
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-01", start = "06:00", end = "12:00", driver = p.driverId))
        fx.staff.rosterShifts.create(ctx, r.rosterId, w.input(day = "2026-12-01", start = "22:59", end = "23:59", driver = p.driverId))   // Ruhezeit 1 min unter der Grenze
        val hintsBefore = fx.staff.rosters.get(ctx, r.rosterId).hints!!
        assertEquals(1, hintsBefore.size)
        val me = self(w, p); val stamps = 20
        val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1); val c = cmd(ctx, r.rosterId)
        val stamping = pool.submit {
            start.await()
            repeat(stamps) { i ->
                val t = Instant.parse("2026-12-01T05:00:00Z").plusSeconds(i * 600L)
                fx.staff.timeEvents.record(me, RecordTimeEvent(UUID.randomUUID(), if (i % 2 == 0) TimeEventKind.WORK_START else TimeEventKind.WORK_END, t, t, TimeEventSource.APP, null))
            }
        }
        val publishing = pool.submit<PublishedRoster> { start.await(); fx.staff.publishedRosters.publish(ctx, r.rosterId, c) }
        start.countDown(); val published = publishing.get(60, TimeUnit.SECONDS); stamping.get(60, TimeUnit.SECONDS); pool.shutdown()
        assertEquals(1, published.hintCount, "die Hinweisberechnung der Publikation ist konsistent und unabhängig von den Stempeln")
        assertEquals(hintsBefore.map { it.rule to it.actual }, fx.staff.rosters.get(ctx, r.rosterId).hints!!.map { it.rule to it.actual })
        assertEquals(stamps, fx.count("SELECT count(*) FROM taktovia.time_event WHERE tenant_id = ? AND person_ref = ?", w.tenant.value, p.subject), "kein Stempel geht verloren oder wird verändert")
        assertEquals(setOf("PUBLISHED"), fx.strings("SELECT state FROM taktovia.staff_shift WHERE tenant_id = ? AND roster_id = ?", w.tenant.value, r.rosterId).toSet())
    }
}
