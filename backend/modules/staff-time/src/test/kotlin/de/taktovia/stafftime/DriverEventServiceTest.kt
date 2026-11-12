package de.taktovia.stafftime

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.stafftime.api.DriverEventCommand
import de.taktovia.stafftime.api.DriverEventEffects
import de.taktovia.stafftime.api.DriverEventOutcome
import de.taktovia.stafftime.api.DriverEventResult
import de.taktovia.stafftime.api.DriverEventType
import de.taktovia.stafftime.api.EffectKind
import de.taktovia.stafftime.api.EffectRequest
import de.taktovia.stafftime.api.EffectResult
import de.taktovia.stafftime.api.NoShowReason
import de.taktovia.stafftime.api.StafftimeApi
import de.taktovia.stafftime.api.StafftimeSettings
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcOperations
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-29 / LH-6.6-DRV-005, Eingangslogik des Moduls `staff-time` gegen die echte Datenbank (Runtime-Rolle) mit einer **Attrappe des Ports** [DriverEventEffects] ([RecordingEffects]): hier wird
 * geprüft, WANN das Modul die Wirkung auslöst (Prüfkette, Wartefrist, Uhrabweichung, Deduplikation, getrennte Zeiten, Klärfälle ohne Wirkung), nicht was `booking-dispatch` daraus macht.
 * Dass Buchung und Fahrt danach konsistent sind, weist [de.taktovia.app.driver.DriverEventEffectsHttpTest] mit der echten Verdrahtung nach (Cluster wave12-c3, eigene Fixture).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DriverEventServiceTest {
    private lateinit var fx: StaffFixture
    private val repo = Path.of(System.getProperty("taktovia.repoRoot"))
    private val registry = SchemaRegistry.withDefaultDialect(Dialects.getDraft202012())

    @BeforeAll fun up() { fx = StaffFixture() }
    @AfterAll fun down() { fx.close() }

    /** Attrappe: zeichnet die ausgelösten Wirkungen auf und liefert [result]; [grace] ist der „konfigurierte“ Wert von `no_show_grace_min`. */
    class RecordingEffects : DriverEventEffects {
        val calls = CopyOnWriteArrayList<EffectRequest>()
        @Volatile var result: EffectResult = EffectResult.Applied("CONFIRMED", "PLANNED")
        @Volatile var grace: Int? = null
        override fun apply(j: JdbcOperations, ctx: RequestContext, r: EffectRequest): EffectResult { calls += r; return result }
        override fun noShowGraceMinutes(j: JdbcOperations, ctx: RequestContext, bookingId: UUID, at: Instant): Int? = grace
    }

    private inner class Env(val effects: RecordingEffects = RecordingEffects(), started: Boolean = true) {
        val clock = MovableClock(Instant.parse("2026-12-01T06:00:00Z"))
        val w = ShiftWorld(fx, clock)
        val p = w.person(); val vehicle = w.vehicle()
        val shift = w.shiftRow(p, vehicle, "2026-12-01T05:00:00Z", "2026-12-01T14:00:00Z")
        val device = w.device(p); val ctx = w.driverCtx(p)
        val api: StafftimeApi = StafftimeApi.create(fx.ctxTx, JdbcAuditPort(), Outbox(), clock, StafftimeSettings(), null, effects = effects)
        val stop = UUID.randomUUID(); val booking = UUID.randomUUID()

        init {
            w.release(shift, vehicle); w.unblock(vehicle)
            if (started) fx.exec("UPDATE taktovia.staff_shift SET state='STARTED', started_at=?, started_device_id=? WHERE id=?", Timestamp.from(Instant.parse("2026-12-01T05:30:00Z")), device, shift)
        }

        fun run(revision: Long) = fx.exec("INSERT INTO taktovia.vehicle_run(tenant_id,id,vehicle_id,shift_id,revision,state) VALUES (?,?,?,?,?,'ACTIVE')", w.tenant.value, UUID.randomUUID(), vehicle, shift, revision)

        fun ev(type: DriverEventType, id: UUID = UUID.randomUUID(), rev: Long = 1, offline: Boolean = false, occurred: Instant = clock.now, deviceAt: Instant = occurred, stopId: UUID? = stop,
               bookingId: UUID? = booking, noShow: NoShowReason? = null) =
            DriverEventCommand(id, device, shift, vehicle, rev, type, if (type == DriverEventType.ARRIVAL) null else bookingId, stopId, occurred, deviceAt, offline, noShow)

        fun noShow(id: UUID = UUID.randomUUID(), reason: NoShowReason? = NoShowReason.NOT_PRESENT) = ev(DriverEventType.NO_SHOW, id, noShow = reason)
        fun record(c: DriverEventCommand): DriverEventResult = api.driverEvents.record(ctx, c)
        fun journal(type: String) = fx.strings("SELECT outcome || ':' || coalesce(reason_code,'-') FROM taktovia.driver_event WHERE tenant_id = ? AND shift_id = ? AND event_type = ?", w.tenant.value, shift, type)
        fun payloads(type: String, eventId: UUID) = fx.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id = ? AND event_type = ? AND aggregate_id = ?", w.tenant.value, type, eventId)
    }

    private fun problem(block: () -> Unit) = ShiftWorld.problem(block)

    @Test
    fun ankunft_startet_wartefrist() {
        val e = Env()
        val a = e.record(e.ev(DriverEventType.ARRIVAL)); assertEquals(DriverEventOutcome.APPLIED, a.outcome)
        assertEquals(listOf(EffectKind.ARRIVAL), e.effects.calls.map { it.kind }, "die Ankunft löst die Wirkung aus (Stopp prüfen), ohne Buchung")
        assertEquals(null, e.effects.calls.single().bookingId)
        e.clock.now = Instant.parse("2026-12-01T06:05:00Z")
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { e.record(e.noShow()) }.code, "die Wartefrist läuft ab der Ankunft (05 von 10 Minuten)")
        e.clock.now = Instant.parse("2026-12-01T06:10:00Z")
        assertEquals(DriverEventOutcome.APPLIED, e.record(e.noShow()).outcome, "genau auf der Grenze ist die Frist abgelaufen")
    }

    @Test
    fun no_show_vor_wartefrist_abgewiesen() {
        val e = Env()
        e.record(e.ev(DriverEventType.ARRIVAL))
        e.clock.now = Instant.parse("2026-12-01T06:09:59Z")
        val p = problem { e.record(e.noShow()) }
        assertEquals(ErrorCode.VALIDATION_FAILED, p.code); assertEquals(ViolationCode.NO_SHOW_GRACE_NOT_ELAPSED, p.violations.single().code)
        assertEquals(emptyList<String>(), e.journal("NO_SHOW"), "abgewiesen: nichts gespeichert")
        assertEquals(listOf(EffectKind.ARRIVAL), e.effects.calls.map { it.kind }, "keine Statuswirkung")
        // ein konfigurierter Dienstwert verlängert die Frist
        e.effects.grace = 15; e.clock.now = Instant.parse("2026-12-01T06:12:00Z")
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { e.record(e.noShow()) }.code, "konfigurierte 15 Minuten statt des Planungsdefaults")
        e.clock.now = Instant.parse("2026-12-01T06:15:00Z")
        assertEquals(DriverEventOutcome.APPLIED, e.record(e.noShow()).outcome)
        // ohne Grund: Pflichtfeld
        assertEquals(ErrorCode.VALIDATION_FAILED, problem { e.record(e.noShow(reason = null)) }.code)
    }

    @Test
    fun no_show_speichert_grund_wartezeit_und_regelstand() {
        val e = Env(); e.effects.grace = 12
        e.record(e.ev(DriverEventType.ARRIVAL))
        e.clock.now = Instant.parse("2026-12-01T06:13:30Z")
        val id = UUID.randomUUID(); val r = e.record(e.noShow(id, NoShowReason.REFUSED_TO_BOARD)); assertEquals(DriverEventOutcome.APPLIED, r.outcome)
        val row = fx.strings("SELECT no_show_reason || '|' || no_show_wait_seconds || '|' || no_show_grace_min FROM taktovia.driver_event WHERE tenant_id = ? AND event_id = ?", e.w.tenant.value, id).single()
        assertEquals("REFUSED_TO_BOARD|810|12", row, "Grund, Wartezeit (06:00 bis 06:13:30) und Regelstand (konfigurierte Frist)")
        val p = e.payloads("driver.no_show_recorded", id).single()
        val schema = registry.getSchema(Files.readString(repo.resolve("api/events/driver.no_show_recorded.v1.schema.json")))
        assertEquals(emptyList<Any>(), schema.validate(p, InputFormat.JSON).toList().map { it.message })
        assertTrue(p.contains("\"fee_booked\": false") || p.contains("\"fee_booked\":false"), "keine Gebühr in diesem Paket")
        assertEquals(1, e.payloads("driver.event_recorded", id).size)
    }

    @Test
    fun no_show_ohne_ankunft_wird_zum_klaerfall() {
        val e = Env(); e.clock.now = Instant.parse("2026-12-01T07:00:00Z")
        val r = e.record(e.noShow()); assertEquals(DriverEventOutcome.REVIEW_REQUIRED, r.outcome); assertEquals("INVALID_ORDER", r.reasonCode)
        assertEquals(emptyList<EffectRequest>(), e.effects.calls.toList(), "keine Wirkung ohne Ankunft")
    }

    @Test
    fun ausstieg_ohne_einstieg_invalid_order() {
        // die Reihenfolge Einstieg vor Ausstieg prüft die Wirkung auf Buchung und Fahrt; das Modul reicht ihr Ergebnis unverändert als Klärfall durch
        val e = Env(); e.effects.result = EffectResult.Review("INVALID_ORDER")
        val r = e.record(e.ev(DriverEventType.CHECK_OUT)); assertEquals(DriverEventOutcome.REVIEW_REQUIRED, r.outcome); assertEquals("INVALID_ORDER", r.reasonCode)
        assertEquals(listOf("REVIEW_REQUIRED:INVALID_ORDER"), e.journal("CHECK_OUT"))
        assertEquals(1, e.payloads("driver.review_opened", r.eventId).size, "Klärfall eröffnet")
        assertEquals(0, e.payloads("driver.passenger_alighted", r.eventId).size, "kein Ausstieg gemeldet")
    }

    @Test
    fun falscher_stopp_outside_stop() {
        val e = Env(); e.effects.result = EffectResult.Review("OUTSIDE_STOP")
        val r = e.record(e.ev(DriverEventType.CHECK_IN)); assertEquals("OUTSIDE_STOP", r.reasonCode); assertEquals(DriverEventOutcome.REVIEW_REQUIRED, r.outcome)
        e.effects.result = EffectResult.NotFound
        assertEquals(ErrorCode.NOT_FOUND, problem { e.record(e.ev(DriverEventType.CHECK_IN)) }.code, "Stopp oder Buchung nicht in der eigenen Schicht: 404")
        e.effects.result = EffectResult.Review("BOOKING_CANCELED")
        assertEquals("BOOKING_CANCELED", e.record(e.ev(DriverEventType.CHECK_IN)).reasonCode, "Widerspruch zum Storno: Klärfall")
    }

    @Test
    fun uhrabweichung_clock_skew() {
        val e = Env()
        val online = e.record(e.ev(DriverEventType.ARRIVAL, deviceAt = e.clock.now.plusSeconds(600), occurred = e.clock.now))
        assertEquals("CLOCK_SKEW", online.reasonCode, "Gerätezeit weicht online um 10 Minuten von der Serveruhr ab")
        val future = e.record(e.ev(DriverEventType.ARRIVAL, offline = true, occurred = e.clock.now.plusSeconds(600), deviceAt = e.clock.now.plusSeconds(600)))
        assertEquals("CLOCK_SKEW", future.reasonCode, "ein Ereignis aus der Zukunft")
        val offlinePast = e.record(e.ev(DriverEventType.ARRIVAL, offline = true, occurred = e.clock.now.minusSeconds(3600)))
        assertEquals(DriverEventOutcome.APPLIED, offlinePast.outcome, "ein offline erfasstes Ereignis aus der Vergangenheit ist plausibel")
        assertEquals(1, e.effects.calls.size, "nur das plausible Ereignis hat eine Wirkung ausgelöst")
        // getrennte Zeiten: Ereigniszeit (Gerät), Gerätezeit der Erfassung, Serverempfangszeit und plausibilisierte Zeit
        val row = fx.strings("SELECT occurred_at || '|' || device_recorded_at || '|' || received_at || '|' || effective_at FROM taktovia.driver_event WHERE tenant_id = ? AND event_id = ?", e.w.tenant.value, offlinePast.eventId).single()
        val (occ, dev, rec, eff) = row.split("|").map { Instant.parse(it.replace(" ", "T").replace("+00", "Z")) }
        assertEquals(Instant.parse("2026-12-01T05:00:00Z"), occ); assertEquals(occ, dev); assertEquals(Instant.parse("2026-12-01T06:00:00Z"), rec); assertEquals(occ, eff, "offline gilt die Ereigniszeit")
    }

    @Test
    fun offline_checkin_alte_planrevision_review_required() {
        val e = Env(); e.run(3)
        val r = e.record(e.ev(DriverEventType.CHECK_IN, rev = 2, offline = true, occurred = e.clock.now.minusSeconds(120)))
        assertEquals(DriverEventOutcome.REVIEW_REQUIRED, r.outcome); assertEquals("STALE_PLAN", r.reasonCode); assertEquals(3L, r.currentPlanRevision)
        assertEquals(emptyList<EffectRequest>(), e.effects.calls.toList(), "keine ausgeführte Beförderungsbuchung")
    }

    @Test
    fun fremde_schicht_ergibt_404() {
        val e = Env(); val other = Env()
        assertEquals(ErrorCode.NOT_FOUND, problem { e.record(e.ev(DriverEventType.ARRIVAL).copy(shiftId = other.shift, vehicleId = other.vehicle)) }.code, "Schicht eines anderen Mandanten")
        assertEquals(ErrorCode.NOT_FOUND, problem { e.record(e.ev(DriverEventType.ARRIVAL).copy(shiftId = UUID.randomUUID())) }.code)
        assertEquals(emptyList<EffectRequest>(), e.effects.calls.toList())
    }

    @Test
    fun ereignis_ohne_einsatzfreigabe_oder_nach_schichtende_ohne_wirkung() {
        val e = Env()
        fx.exec("UPDATE taktovia.resource_release SET revoked_at = now() WHERE tenant_id = ? AND shift_id = ?", e.w.tenant.value, e.shift)
        val noRelease = e.record(e.ev(DriverEventType.CHECK_IN)); assertEquals("RELEASE_REQUIRED", noRelease.reasonCode); assertEquals(DriverEventOutcome.REVIEW_REQUIRED, noRelease.outcome)
        assertEquals(DriverEventOutcome.APPLIED, e.record(e.ev(DriverEventType.CHECK_OUT)).outcome, "der Ausstieg bleibt möglich: ein Fahrgast im Fahrzeug muss aussteigen können")
        fx.exec("UPDATE taktovia.staff_shift SET state='ENDED', ended_at=? WHERE id=?", Timestamp.from(Instant.parse("2026-12-01T06:30:00Z")), e.shift)
        e.clock.now = Instant.parse("2026-12-01T06:45:00Z")
        val after = e.record(e.ev(DriverEventType.ARRIVAL)); assertEquals("SHIFT_ENDED", after.reasonCode)
        val before = e.record(e.ev(DriverEventType.CHECK_OUT, offline = true, occurred = Instant.parse("2026-12-01T06:20:00Z")))
        assertEquals(DriverEventOutcome.APPLIED, before.outcome, "ein vor dem Schichtende erfasstes, später synchronisiertes Ereignis gilt")
        assertEquals(listOf(EffectKind.ALIGHTED, EffectKind.ALIGHTED), e.effects.calls.map { it.kind }, "nur die Ausstiege hatten eine Wirkung")
    }

    @Test
    fun schicht_nicht_angetreten_ist_409() {
        val e = Env(started = false)
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, problem { e.record(e.ev(DriverEventType.ARRIVAL)) }.code)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.driver_event WHERE tenant_id = ?", e.w.tenant.value)); assertEquals(0, e.effects.calls.size)
    }

    @Test
    fun gleiche_event_id_anderer_inhalt_409() {
        val e = Env(); val c = e.ev(DriverEventType.ARRIVAL); e.record(c)
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, problem { e.record(c.copy(routeStopId = UUID.randomUUID())) }.code)
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, problem { e.record(e.ev(DriverEventType.NO_SHOW, c.eventId, noShow = NoShowReason.OTHER)) }.code)
        assertEquals(1, e.effects.calls.size, "die Wirkung des ersten Ereignisses bleibt die einzige")
    }

    @Test
    fun tenant_id_im_request_ohne_wirkung() {
        // Der Mandant kommt allein aus dem Kontext: dieselbe Schicht im Kontext eines anderen Mandanten ist nicht auffindbar (der HTTP-Fall mit `tenant_id` im Body steht in DriverEventEffectsHttpTest)
        val e = Env(); val other = Env()
        assertEquals(ErrorCode.NOT_FOUND, problem { other.api.driverEvents.record(other.ctx, e.ev(DriverEventType.ARRIVAL).copy(deviceId = other.device)) }.code)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.driver_event WHERE tenant_id = ?", e.w.tenant.value))
    }

    @Test
    fun verspaetetes_ereignis_hebt_schadenssperre_nicht_auf() {
        val e = Env()
        fx.exec("UPDATE taktovia.vehicle SET active_block = true WHERE tenant_id = ? AND id = ?", e.w.tenant.value, e.vehicle)
        val releases = fx.count("SELECT count(*) FROM taktovia.resource_release WHERE tenant_id = ?", e.w.tenant.value)
        val late = e.record(e.ev(DriverEventType.CHECK_IN, offline = true, occurred = e.clock.now.minusSeconds(1800)))
        assertEquals(DriverEventOutcome.REVIEW_REQUIRED, late.outcome); assertEquals("RELEASE_REQUIRED", late.reasonCode)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.vehicle WHERE tenant_id = ? AND id = ? AND active_block", e.w.tenant.value, e.vehicle), "die Sperre bleibt")
        assertEquals(releases, fx.count("SELECT count(*) FROM taktovia.resource_release WHERE tenant_id = ?", e.w.tenant.value), "keine neue Freigabe")
        assertEquals(0, e.effects.calls.size)
    }

    @Test
    fun checkin_parallel_zu_storno_klaerfall() {
        // Das Ergebnis der Wirkung (hier: der Storno hat gewonnen) wird unverändert Klärfall, ohne Zustandsrücksprung; die echte Parallelität gegen die Buchung steht in DriverEventEffectsHttpTest
        val e = Env(); e.effects.result = EffectResult.Review("BOOKING_CANCELED")
        val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
        val rs = (1..8).map { pool.submit<DriverEventResult> { go.await(); e.record(e.ev(DriverEventType.CHECK_IN)) } }
        go.countDown(); val all = rs.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertTrue(all.all { it.outcome == DriverEventOutcome.REVIEW_REQUIRED && it.reasonCode == "BOOKING_CANCELED" })
        assertEquals(8, e.journal("CHECK_IN").size)
    }

    @Test
    fun hundert_identische_ereignisse_eine_wirkung() {
        val e = Env(); val c = e.ev(DriverEventType.ARRIVAL)
        val pool = Executors.newFixedThreadPool(20); val go = CountDownLatch(1)
        val futures = (1..100).map { pool.submit<Any> { go.await(); try { e.record(c) } catch (t: Throwable) { t } } }
        go.countDown(); val rs = futures.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(emptyList<Any>(), rs.filterIsInstance<Throwable>().map { it.toString() })
        val by = rs.filterIsInstance<DriverEventResult>().groupingBy { it.outcome }.eachCount()
        assertEquals(1, by[DriverEventOutcome.APPLIED], by.toString()); assertEquals(99, by[DriverEventOutcome.DUPLICATE], by.toString())
        assertEquals(1, e.effects.calls.size, "genau eine Wirkung, auch wenn alle gleichzeitig eintreffen")
        assertEquals(1, e.payloads("driver.event_recorded", c.eventId).size)
    }

    @Test
    fun outbox_ereignisse_entsprechen_den_schemas() {
        val e = Env(); fun schema(n: String) = registry.getSchema(Files.readString(repo.resolve("api/events/$n.v1.schema.json")))
        val a = e.record(e.ev(DriverEventType.ARRIVAL)); val ci = e.record(e.ev(DriverEventType.CHECK_IN)); val co = e.record(e.ev(DriverEventType.CHECK_OUT))
        e.effects.result = EffectResult.Review("OUTSIDE_STOP"); val rv = e.record(e.ev(DriverEventType.CHECK_IN))
        val cases = listOf("driver.event_recorded" to a, "driver.passenger_boarded" to ci, "driver.passenger_alighted" to co, "driver.review_opened" to rv, "driver.event_recorded" to rv)
        for ((type, r) in cases) {
            val p = checkNotNull(e.payloads(type, r.eventId).singleOrNull()) { "$type fehlt" }
            assertEquals(emptyList<Any>(), schema(type).validate(p, InputFormat.JSON).toList().map { it.message }, type)
        }
    }
}
