package de.taktovia.stafftime

import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.tenant.TenantId
import de.taktovia.stafftime.api.AckRunCommand
import de.taktovia.stafftime.api.DriverEventOutcome
import de.taktovia.stafftime.api.DriverEventCommand
import de.taktovia.stafftime.api.DriverEventType
import de.taktovia.stafftime.api.DriverRunProjection
import de.taktovia.stafftime.api.DriverRunSource
import de.taktovia.stafftime.api.DriverRunStopView
import de.taktovia.stafftime.api.PlanAckOverdue
import de.taktovia.stafftime.api.PlanAckOverdueSink
import de.taktovia.stafftime.api.PlanRevisionState
import de.taktovia.stafftime.api.PlanRevisionWakeup
import de.taktovia.stafftime.api.StafftimeApi
import de.taktovia.stafftime.api.StafftimeSettings
import de.taktovia.stafftime.api.StartShiftCommand
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

/**
 * WP-M3-27 / LH-6.6-DRV-004: Planänderungen erreichen den Fahrer gekennzeichnet (neu, entfallen, verschoben), werden bestätigt bzw. nach Regel kontrolliert übernommen; die bestätigte Revision
 * steht je Fahrer serverseitig; veraltete Bestätigungen, fremde Schichten, zwei Geräte und kurz aufeinanderfolgende Revisionen. Gegen die echte Datenbank unter der Runtime-Rolle; die Stoppliste
 * liefert eine Testquelle mit Revisionsverlauf (wie der Fahrtenplan von `booking-dispatch`); die Zustellung über Live-Kanal und HTTP (ETag/304) steht in `PlanRevisionDeliveryHttpTest` der Anwendung.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanRevisionDeliveryTest {
    private lateinit var fx: StaffFixture
    private lateinit var w: ShiftWorld
    private lateinit var staff: StafftimeApi
    private val overdue = CopyOnWriteArrayList<Pair<TenantId, PlanAckOverdue>>()

    /** Revisionsverlauf je Schicht (Revision -> Plan): so liest der Fahrtenplan frühere Revisionen (`route_stop` ist je Revision unveränderlich). */
    private val history = ConcurrentHashMap<UUID, ConcurrentHashMap<Long, DriverRunProjection>>()
    private val source = object : DriverRunSource {
        override fun projection(ctx: RequestContext, shiftId: UUID, vehicleId: UUID): DriverRunProjection? = history[shiftId]?.let { h -> h[h.keys.max()] }
        override fun revision(tenant: TenantId, shiftId: UUID, vehicleId: UUID, revision: Long): DriverRunProjection? = history[shiftId]?.get(revision)
    }

    @BeforeAll fun up() {
        fx = StaffFixture(); w = ShiftWorld(fx, source = source)
        staff = StafftimeApi.create(fx.ctxTx, JdbcAuditPort(), Outbox(), w.clock, StafftimeSettings(), source, planAckSink = PlanAckOverdueSink { t, h -> overdue += t to h })
    }
    @AfterAll fun down() = fx.close()

    private fun at(iso: String) { w.clock.now = Instant.parse(iso) }
    private val t0 = Instant.parse("2026-12-01T07:00:00Z")

    private fun stop(name: String, minutes: Long, bookings: List<UUID> = listOf(UUID.randomUUID()), stopRev: UUID = UUID.randomUUID(), seq: Int = 1) =
        DriverRunStopView(UUID.randomUUID(), seq, "PICKUP", t0.plusSeconds(minutes * 60), t0.plusSeconds(minutes * 60), name, 13.74, 51.05, 1, bookings, null, emptyList(), stopRev, 1, 0, null, null)

    private inner class Scene {
        val p = w.person(); val v = w.vehicle(); val s = w.shiftRow(p, v, "2026-12-01T06:00:00Z", "2026-12-01T14:00:00Z"); val ctx = w.driverCtx(p); val dev = w.device(p); val dev2 = w.device(p)
        val run = UUID.randomUUID()
        var revision = 0L
        val stopsA = stop("Marktplatz", 0); val stopsB = stop("Hauptbahnhof", 20)
        init {
            w.release(s, v); w.unblock(v); at("2026-12-01T06:00:00Z"); w.staff.driverShifts.start(ctx, StartShiftCommand(s, v, dev)); at("2026-12-01T10:00:00Z")
            fx.exec("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (?,?,?,?,1,'ACTIVE')", w.tenant.value, run, v, s)
            revision = 1; history[s] = ConcurrentHashMap(mapOf(1L to DriverRunProjection(1, listOf(stopsA, stopsB), "ACTIVE")))
        }

        /** Plan-Commit: Revision +1 (wie `RunPlans.appendRevision`) mit Planänderung; die Quelle liefert danach den neuen Plan. */
        fun commit(stops: List<DriverRunStopView>, cause: String = "REPLAN"): Long {
            val next = revision + 1
            fx.exec("UPDATE taktovia.vehicle_run SET revision = ? WHERE tenant_id = ? AND id = ?", next, w.tenant.value, run)
            fx.exec("INSERT INTO taktovia.plan_change(tenant_id,id,run_id,run_revision,previous_revision,cause,recorded_at) VALUES (?,?,?,?,?,?,?)", w.tenant.value, UUID.randomUUID(), run, next, revision, cause, Timestamp.from(w.clock.now))
            history.getValue(s)[next] = DriverRunProjection(next, stops, "ACTIVE"); revision = next
            return next
        }
        fun view() = w.staff.driverShifts.run(ctx, s)
        fun acks() = fx.count("SELECT count(*) FROM taktovia.driver_plan_ack WHERE tenant_id = ? AND shift_id = ?", w.tenant.value, s)
    }

    @Test
    fun neue_route_innerhalb_p95_dargestellt() {
        // Server-Teil der Messung „Commit bis dargestellte Clientrevision“ über 100 Planänderungen: nach dem Commit liefert der Abruf die neue Revision gekennzeichnet; der Transportteil
        // (Live-Kanal und Abruf, ETag) wird in PlanRevisionDeliveryHttpTest über die echte Anwendung gemessen.
        val sc = Scene(); val latencies = ArrayList<Long>()
        var previousStops = listOf(sc.stopsA, sc.stopsB)
        repeat(100) { i ->
            val extra = stop("Halt $i", 30L + i)
            previousStops = previousStops + extra
            val started = System.nanoTime()
            val rev = sc.commit(previousStops)
            val shown = sc.view()
            latencies += System.nanoTime() - started
            assertEquals(rev, shown.planRevision); assertEquals(PlanRevisionState.NEW_UNCONFIRMED, shown.revisionState); assertTrue(shown.acknowledgementRequired)
            assertEquals("NEW", shown.stops.single { it.stopName == "Halt $i" }.change, "die neue Route ist gegenüber der Vorrevision gekennzeichnet")
        }
        val p95 = latencies.sorted()[(latencies.size * 95 / 100) - 1]
        assertTrue(Duration.ofNanos(p95) <= Duration.ofSeconds(10), "p95 Commit bis Darstellung ${Duration.ofNanos(p95).toMillis()} ms")
    }

    @Test
    fun aenderung_wird_gekennzeichnet_neu_entfallen_verschoben_und_kleine_verschiebung_automatisch_uebernommen() {
        val sc = Scene()
        val moved = sc.stopsA.copy(routeStopId = UUID.randomUUID(), plannedAt = sc.stopsA.plannedAt.plusSeconds(10 * 60), forecastAt = null)
        val added = stop("Rathaus", 40)
        sc.commit(listOf(moved, added))   // B entfällt, A um 10 min verschoben, Rathaus neu
        val v = sc.view()
        assertEquals(PlanRevisionState.NEW_UNCONFIRMED, v.revisionState); assertTrue(v.acknowledgementRequired); assertEquals(1L, v.baseRevision)
        assertEquals("MOVED", v.stops.single { it.stopName == "Marktplatz" }.change); assertEquals(sc.stopsA.plannedAt, v.stops.single { it.stopName == "Marktplatz" }.previousPlannedAt)
        assertEquals("NEW", v.stops.single { it.stopName == "Rathaus" }.change)
        assertEquals(listOf("Hauptbahnhof"), v.removedStops.map { it.stopName })
        // nur eine kleine Verschiebung (unter der Schwelle): angezeigt und automatisch übernommen, keine Bestätigung nötig
        val sc2 = Scene()
        sc2.commit(listOf(sc2.stopsA.copy(routeStopId = UUID.randomUUID(), plannedAt = sc2.stopsA.plannedAt.plusSeconds(2 * 60)), sc2.stopsB))
        val small = sc2.view()
        assertEquals(PlanRevisionState.AUTO_ADOPTED, small.revisionState); assertFalse(small.acknowledgementRequired)
        assertEquals("MOVED", small.stops.first().change)
        // unverändert (nur neue Revision ohne Inhaltsänderung): aktuell
        val sc3 = Scene(); sc3.commit(listOf(sc3.stopsA, sc3.stopsB), "MANUAL_CHANGE")
        assertEquals(PlanRevisionState.CURRENT, sc3.view().revisionState); assertTrue(sc3.view().stops.all { it.change == null })
    }

    @Test
    fun bestaetigung_wird_je_fahrer_gespeichert() {
        val sc = Scene(); val rathaus = stop("Rathaus", 40); val rev = sc.commit(listOf(sc.stopsA, sc.stopsB, rathaus))
        val r = staff.planAcks.acknowledge(sc.ctx, sc.s, rev, AckRunCommand(rev, w.clock.now, sc.dev))
        assertTrue(r.created); assertEquals(rev, r.run.acknowledgedRevision); assertEquals(PlanRevisionState.CONFIRMED, r.run.revisionState); assertFalse(r.run.acknowledgementRequired)
        assertEquals(1, sc.acks())
        assertEquals(listOf("user:${sc.p.subject}"), fx.strings("SELECT created_by FROM taktovia.driver_plan_ack WHERE tenant_id = ? AND shift_id = ?", w.tenant.value, sc.s))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'staff.driver.plan_acknowledged' AND object_ref = ?", w.tenant.value, "staff_shift:${sc.s}"))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'driver.run_acknowledged' AND payload ->> 'shift_id' = ?", w.tenant.value, sc.s.toString()), "Ereignis driver.run_acknowledged in derselben Transaktion")
        // Vergleichsbasis der nächsten Anzeige ist die bestätigte Revision
        sc.commit(listOf(sc.stopsA, sc.stopsB, rathaus, stop("Bahnhof Süd", 50)))
        val next = sc.view()
        assertEquals(rev, next.baseRevision); assertEquals(listOf("NEW"), next.stops.mapNotNull { it.change })
        // anderer Fahrer sieht diese Bestätigung nie
        val other = w.person(); val ov = w.vehicle(); val os = w.shiftRow(other, ov, "2026-12-01T06:00:00Z", "2026-12-01T14:00:00Z")
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.driver_plan_ack WHERE tenant_id = ? AND shift_id = ?", w.tenant.value, os))
    }

    @Test
    fun bestaetigung_veralteter_revision_abgewiesen() {
        val sc = Scene(); val r2 = sc.commit(listOf(sc.stopsA, sc.stopsB, stop("Rathaus", 40))); val r3 = sc.commit(listOf(sc.stopsA, sc.stopsB, stop("Rathaus", 40), stop("Süd", 50)))
        val stale = assertThrowsProblem { staff.planAcks.acknowledge(sc.ctx, sc.s, r2, AckRunCommand(r2, w.clock.now)) }
        assertEquals(ErrorCode.REVISION_MISMATCH, stale.code); assertEquals("\"$r3\"", stale.headers["ETag"], "die Antwort nennt die aktuelle Revision")
        assertEquals(0, sc.acks(), "eine übersprungene Revision wird nie bestätigt")
        // If-Match und Body müssen dieselbe Revision nennen
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrowsProblem { staff.planAcks.acknowledge(sc.ctx, sc.s, r3, AckRunCommand(r2, w.clock.now)) }.code)
        // Zeitstempel weit in der Zukunft
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrowsProblem { staff.planAcks.acknowledge(sc.ctx, sc.s, r3, AckRunCommand(r3, w.clock.now.plusSeconds(3600))) }.code)
        assertTrue(staff.planAcks.acknowledge(sc.ctx, sc.s, r3, AckRunCommand(r3, w.clock.now)).created, "die aktuelle Revision lässt sich bestätigen")
    }

    @Test
    fun bestaetigung_fremder_schicht_ergibt_404() {
        val sc = Scene(); val rev = sc.commit(listOf(sc.stopsA, sc.stopsB, stop("Rathaus", 40)))
        val other = w.person(); val octx = w.driverCtx(other)
        assertEquals(ErrorCode.NOT_FOUND, assertThrowsProblem { staff.planAcks.acknowledge(octx, sc.s, rev, AckRunCommand(rev, w.clock.now)) }.code)
        val w2 = ShiftWorld(fx); val p2 = w2.person()
        assertEquals(ErrorCode.NOT_FOUND, assertThrowsProblem { staff.planAcks.acknowledge(w2.driverCtx(p2), sc.s, rev, AckRunCommand(rev, w.clock.now)) }.code, "Schicht eines anderen Mandanten")
        assertEquals(ErrorCode.NOT_FOUND, assertThrowsProblem { staff.planAcks.acknowledge(sc.ctx, UUID.randomUUID(), rev, AckRunCommand(rev, w.clock.now)) }.code)
        assertEquals(ErrorCode.FORBIDDEN, assertThrowsProblem { staff.planAcks.acknowledge(w.dispatcher(), sc.s, rev, AckRunCommand(rev, w.clock.now)) }.code, "Leitstelle bestätigt nicht für den Fahrer")
        // fremdes Gerät
        val foreignDevice = w.device(other)
        assertEquals(ErrorCode.NOT_FOUND, assertThrowsProblem { staff.planAcks.acknowledge(sc.ctx, sc.s, rev, AckRunCommand(rev, w.clock.now, foreignDevice)) }.code)
        assertEquals(0, sc.acks())
    }

    @Test
    fun push_nutzlast_ohne_fahrgast_und_ortsdaten() {
        val sc = Scene(); sc.commit(listOf(sc.stopsA.copy(stopName = "Hauptstraße 1 Erika Muster"), sc.stopsB))
        val payload = PlanRevisionWakeup.payload(sc.s, sc.revision)
        assertEquals(setOf("type", "revision"), payload.keys, "nur der Hinweis auf eine neue Revision")
        assertEquals("plan_revision", payload["type"]); assertEquals(sc.revision.toString(), payload["revision"])
        val text = payload.toString()
        for (forbidden in listOf("Muster", "Hauptstraße", "Marktplatz", "13.74", "51.05", sc.s.toString(), sc.stopsA.bookingIds.first().toString())) assertFalse(text.contains(forbidden), "Nutzlast enthält $forbidden")
    }

    @Test
    fun verlorener_push_abruf_liefert_neue_revision() {
        val sc = Scene()
        // der Fahrer bestätigt Revision 1; danach gehen zwei Planänderungen und jeder Weckruf verloren
        staff.planAcks.acknowledge(sc.ctx, sc.s, 1, AckRunCommand(1, w.clock.now))
        val r2 = sc.commit(listOf(sc.stopsA, sc.stopsB, stop("Rathaus", 40))); val r3 = sc.commit(listOf(sc.stopsA, sc.stopsB, stop("Rathaus", 40), stop("Süd", 50)))
        val v = sc.view()   // Abruf beim nächsten Sync
        assertEquals(r3, v.planRevision); assertEquals(1L, v.acknowledgedRevision); assertTrue(r2 < r3)
        assertEquals(2, v.stops.count { it.change == "NEW" }, "der Abruf zeigt die Änderung seit der zuletzt bestätigten Revision, nicht nur die letzte Revision")
        assertEquals(PlanRevisionState.NEW_UNCONFIRMED, v.revisionState)
    }

    @Test
    fun ausbleibende_bestaetigung_hinweis_an_leitstelle() {
        val sc = Scene(); overdue.clear()
        val rev = sc.commit(listOf(sc.stopsA, sc.stopsB, stop("Rathaus", 40)))
        assertTrue(staff.planAcks.reportOverdue(w.tenant).none { it.shiftId == sc.s }, "vor Ablauf von plan_ack_overdue_min kein Hinweis")
        at("2026-12-01T10:06:00Z")
        val hints = staff.planAcks.reportOverdue(w.tenant).filter { it.shiftId == sc.s }
        assertEquals(1, hints.size); assertEquals(rev, hints.single().planRevision); assertEquals(sc.run, hints.single().runId)
        assertEquals(1, overdue.count { it.second.shiftId == sc.s && it.first == w.tenant }, "die Senke (Alarmdienst) erhält den Hinweis")
        // bestätigt: kein Hinweis mehr
        staff.planAcks.acknowledge(sc.ctx, sc.s, rev, AckRunCommand(rev, w.clock.now))
        assertTrue(staff.planAcks.reportOverdue(w.tenant).none { it.shiftId == sc.s })
        // kleine, automatisch übernommene Änderung: kein Hinweis
        at("2026-12-01T10:10:00Z")
        val small = Scene(); at("2026-12-01T10:10:00Z")
        small.commit(listOf(small.stopsA.copy(routeStopId = UUID.randomUUID(), plannedAt = small.stopsA.plannedAt.plusSeconds(60)), small.stopsB)); at("2026-12-01T10:30:00Z")
        assertTrue(staff.planAcks.reportOverdue(w.tenant).none { it.shiftId == small.s }, "nicht bestätigungspflichtig: kein Hinweis")
        // nicht angetretene Schicht: kein Hinweis
        val idle = w.shiftRow(w.person(), w.vehicle(), "2026-12-01T09:00:00Z", "2026-12-01T17:00:00Z")
        assertTrue(staff.planAcks.reportOverdue(w.tenant).none { it.shiftId == idle })
    }

    @Test
    fun ereignis_auf_unbestaetigter_veralteter_revision_review_required() {
        val sc = Scene()
        val r2 = sc.commit(listOf(sc.stopsA, sc.stopsB, stop("Rathaus", 40)))
        // das Gerät kennt nur Revision 1 (Änderung noch nicht bestätigt, nicht eingetroffen): das Ereignis wird nicht still angewendet
        val booking = sc.stopsA.bookingIds.first()
        fun cmd(rev: Long) = DriverEventCommand(UUID.randomUUID(), sc.dev, sc.s, sc.v, rev, DriverEventType.CHECK_IN, booking, sc.stopsA.routeStopId, w.clock.now, w.clock.now, true)
        val ctx = w.driverCtx(sc.p)
        val stale = staff.driverEvents.record(ctx, cmd(1))
        assertEquals(DriverEventOutcome.REVIEW_REQUIRED, stale.outcome); assertEquals("STALE_PLAN", stale.reasonCode); assertEquals(r2, stale.currentPlanRevision)
        val current = staff.driverEvents.record(ctx, cmd(r2))
        assertEquals(DriverEventOutcome.APPLIED, current.outcome)
    }

    @Test
    fun zwei_revisionen_hintereinander_endet_auf_neuester() {
        val sc = Scene()
        val r2 = sc.commit(listOf(sc.stopsA, sc.stopsB, stop("Rathaus", 40)))
        val r3 = sc.commit(listOf(sc.stopsA, sc.stopsB, stop("Rathaus", 40), stop("Süd", 50)))
        // die App war nach r1 offline, holt jetzt ab: sie sieht r3, nicht r2 (der Abruf liefert immer den neuesten Plan)
        val v = sc.view(); assertEquals(r3, v.planRevision)
        // eine Bestätigung von r2 (übersprungen) ist nicht möglich; die von r3 bestätigt r3 und nur r3
        assertEquals(ErrorCode.REVISION_MISMATCH, assertThrowsProblem { staff.planAcks.acknowledge(sc.ctx, sc.s, r2, AckRunCommand(r2, w.clock.now)) }.code)
        val ok = staff.planAcks.acknowledge(sc.ctx, sc.s, r3, AckRunCommand(r3, w.clock.now))
        assertEquals(r3, ok.run.acknowledgedRevision); assertEquals(listOf(r3), fx.strings("SELECT plan_revision::text FROM taktovia.driver_plan_ack WHERE tenant_id = ? AND shift_id = ?", w.tenant.value, sc.s).map { it.toLong() })
        // Wettlauf: Bestätigung gegen gleichzeitigen Commit — entweder wird r3 bestätigt oder die Bestätigung scheitert mit 412; nie wird eine überholte Revision bestätigt
        val sc2 = Scene(); val a = sc2.commit(listOf(sc2.stopsA, sc2.stopsB, stop("X", 40)))
        val pool = Executors.newFixedThreadPool(2); val go = CountDownLatch(1)
        val ackF = pool.submit<Any> { go.await(); try { staff.planAcks.acknowledge(sc2.ctx, sc2.s, a, AckRunCommand(a, w.clock.now)) } catch (e: Throwable) { e } }
        val commitF = pool.submit<Any> { go.await(); sc2.commit(listOf(sc2.stopsA, sc2.stopsB, stop("X", 40), stop("Y", 50))) }
        go.countDown(); ackF.get(30, TimeUnit.SECONDS); commitF.get(30, TimeUnit.SECONDS); pool.shutdown()
        val acked = fx.strings("SELECT plan_revision::text FROM taktovia.driver_plan_ack WHERE tenant_id = ? AND shift_id = ?", w.tenant.value, sc2.s).map { it.toLong() }
        assertTrue(acked.all { it == a }, "höchstens die zum Zeitpunkt gültige Revision $a wurde bestätigt: $acked")
        val final = sc2.view(); assertEquals(a + 1, final.planRevision)
        assertTrue(final.acknowledgementRequired, "die neueste Revision bleibt unbestätigt")
    }

    @Test
    fun bestaetigung_zwei_geraete_ein_eintrag() {
        val sc = Scene(); val rev = sc.commit(listOf(sc.stopsA, sc.stopsB, stop("Rathaus", 40)))
        val ctx2 = w.driverCtx(sc.p)
        val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
        val results = (0 until 8).map { i -> pool.submit<Any> { go.await(); try { staff.planAcks.acknowledge(ctx2, sc.s, rev, AckRunCommand(rev, w.clock.now, if (i % 2 == 0) sc.dev else sc.dev2)) } catch (e: Throwable) { e } } }
        go.countDown(); val out = results.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(emptyList<Any>(), out.filterIsInstance<Throwable>().map { it.toString() })
        assertEquals(1, sc.acks(), "derselbe Fahrer, dieselbe Revision, mehrere Geräte: ein Eintrag")
        assertEquals(1, out.count { (it as de.taktovia.stafftime.api.AckRunResult).created })
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'staff.driver.plan_acknowledged' AND object_ref = ?", w.tenant.value, "staff_shift:${sc.s}"))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id = ? AND event_type = 'driver.run_acknowledged' AND payload ->> 'shift_id' = ?", w.tenant.value, sc.s.toString()), "ein Ereignis je Bestätigung")
    }

    @Test
    fun ohne_lesbare_basis_wird_nie_still_uebernommen() {
        // die Quelle kennt die Vergleichsbasis nicht: die Revision bleibt bestätigungspflichtig, ohne erfundene Einzelkennzeichnung
        val sc = Scene(); sc.commit(listOf(sc.stopsA, sc.stopsB, stop("Rathaus", 40)))
        history.getValue(sc.s).remove(1L)
        val v = sc.view()
        assertEquals(PlanRevisionState.NEW_UNCONFIRMED, v.revisionState); assertTrue(v.acknowledgementRequired); assertTrue(v.stops.all { it.change == null }); assertNull(v.removedStops.firstOrNull())
    }

    private fun assertThrowsProblem(block: () -> Unit) = ShiftWorld.problem(block)
}
