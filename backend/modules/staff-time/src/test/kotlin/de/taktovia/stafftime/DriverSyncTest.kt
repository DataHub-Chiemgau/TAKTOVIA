package de.taktovia.stafftime

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.stafftime.api.DevicePlatform
import de.taktovia.stafftime.api.DriverEventCommand
import de.taktovia.stafftime.api.DriverEventOutcome
import de.taktovia.stafftime.api.DriverEventType
import de.taktovia.stafftime.api.RegisterDriverDevice
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-24: Geräte-/Sitzungsbindung und Ereigniseingang der Fahrer-App gegen die echte Datenbank (Runtime-Rolle): Positiv (Gerät binden, Ereignis wirkt einmal, Serverzeit),
 * Negativ (fremde Schicht und Mandant 404, gesperrtes oder an andere Sitzung gebundenes Gerät 403, gleiche `event_id` mit anderem Inhalt 409, fremdes Konto am Gerät 409),
 * Konkurrierend (100 identische Wiederholungen = eine Wirkung, parallele Erstregistrierung eines Geräts).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DriverSyncTest {
    private lateinit var fx: StaffFixture
    private val repo = Path.of(System.getProperty("taktovia.repoRoot"))
    private val registry = SchemaRegistry.withDefaultDialect(Dialects.getDraft202012())

    @BeforeAll fun up() { fx = StaffFixture() }
    @AfterAll fun down() { fx.close() }

    private fun problem(block: () -> Unit): ProblemException = try { block(); throw AssertionError("ProblemException erwartet") } catch (e: ProblemException) { e }

    private fun device(w: StaffFixture.World, id: UUID = UUID.randomUUID()): UUID {
        fx.staff.driverDevices.register(fx.context(w.tenant, w.subject), RegisterDriverDevice(id, DevicePlatform.ANDROID, "1.0.0", null)); return id
    }

    private fun cmd(w: StaffFixture.World, device: UUID, id: UUID = UUID.randomUUID(), type: DriverEventType = DriverEventType.BREAK_START, planRevision: Long = 1, recordedAt: Instant = Instant.parse("2020-01-01T00:00:00Z")) =
        DriverEventCommand(id, device, w.shift, w.vehicle, planRevision, type, if (type.needsBooking) UUID.randomUUID() else null, if (type.needsBooking) UUID.randomUUID() else null, recordedAt, recordedAt, true)

    @Test
    fun ereignis_wirkt_einmal_mit_serverzeit_und_outbox_ereignis_gegen_schema() {
        val w = fx.world(); val ctx = fx.context(w.tenant, w.subject); val d = device(w)
        val before = Instant.now().minusSeconds(5)
        val c = cmd(w, d)
        val r = fx.staff.driverEvents.record(ctx, c)
        assertEquals(DriverEventOutcome.APPLIED, r.outcome)
        assertTrue(r.receivedAt.isAfter(before), "Empfangszeit ist die Serverzeit, nicht die Gerätezeit von 2020: ${r.receivedAt}")
        assertEquals(Instant.parse("2020-01-01T00:00:00Z"), fx.tx.run(w.tenant, readOnly = true) { j -> j.queryForObject("SELECT device_recorded_at FROM taktovia.driver_event WHERE event_id = ?", java.sql.Timestamp::class.java, c.eventId)!!.toInstant() }, "Gerätezeit bleibt getrennt gespeichert")
        val again = fx.staff.driverEvents.record(ctx, c)
        assertEquals(DriverEventOutcome.DUPLICATE, again.outcome); assertEquals(r.receivedAt, again.receivedAt, "Duplikat nennt die erste Empfangszeit")
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.driver_event WHERE event_id = ?", c.eventId))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='driver.event_recorded' AND aggregate_id=?", w.tenant.value, c.eventId), "genau ein Ereignis")
        val payload = fx.strings("SELECT payload::text FROM taktovia.outbox WHERE tenant_id=? AND event_type='driver.event_recorded' AND aggregate_id=?", w.tenant.value, c.eventId).single()
        val schema = registry.getSchema(Files.readString(repo.resolve("api/events/driver.event_recorded.v1.schema.json")))
        assertEquals(emptyList<Any>(), schema.validate(payload, InputFormat.JSON).toList().map { it.message }, "Payload entspricht dem Ereignisschema")
    }

    @Test
    fun hundert_identische_wiederholungen_ergeben_eine_wirkung() {
        val w = fx.world(); val ctx = fx.context(w.tenant, w.subject); val d = device(w)
        val c = cmd(w, d)
        val pool = Executors.newFixedThreadPool(20); val start = CountDownLatch(1)
        val futures = (1..100).map { pool.submit<Any> { start.await(); try { fx.staff.driverEvents.record(ctx, c) } catch (e: Throwable) { e } } }
        start.countDown()
        val results = futures.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(emptyList<Any>(), results.filterIsInstance<Throwable>().map { it.toString() })
        val outcomes = results.filterIsInstance<de.taktovia.stafftime.api.DriverEventResult>().groupingBy { it.outcome }.eachCount()
        assertEquals(1, outcomes[DriverEventOutcome.APPLIED], outcomes.toString()); assertEquals(99, outcomes[DriverEventOutcome.DUPLICATE], outcomes.toString())
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.driver_event WHERE event_id = ?", c.eventId))
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.outbox WHERE tenant_id=? AND event_type='driver.event_recorded'", w.tenant.value))
    }

    @Test
    fun gleiche_event_id_mit_anderem_inhalt_ist_ein_konflikt() {
        val w = fx.world(); val ctx = fx.context(w.tenant, w.subject); val d = device(w)
        val c = cmd(w, d); fx.staff.driverEvents.record(ctx, c)
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, problem { fx.staff.driverEvents.record(ctx, c.copy(type = DriverEventType.DISRUPTION)) }.code)
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, problem { fx.staff.driverEvents.record(ctx, c.copy(occurredAt = c.occurredAt.plusSeconds(1))) }.code)
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.driver_event WHERE event_id = ?", c.eventId), "die Wirkung des ersten Ereignisses bleibt")
    }

    @Test
    fun fremde_schicht_fremdes_fahrzeug_und_fremder_mandant_ergeben_404() {
        val w = fx.world(); val other = fx.world(); val ctx = fx.context(w.tenant, w.subject); val d = device(w)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.staff.driverEvents.record(ctx, cmd(w, d).copy(shiftId = UUID.randomUUID())) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.staff.driverEvents.record(ctx, cmd(w, d).copy(vehicleId = UUID.randomUUID())) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.staff.driverEvents.record(ctx, cmd(w, d).copy(shiftId = other.shift, vehicleId = other.vehicle)) }.code, "Schicht eines anderen Mandanten")
        // Schicht eines anderen Fahrers im selben Mandanten
        val fremderFahrer = fx.context(w.tenant, UUID.randomUUID()); val d2 = device(StaffFixture.World(w.tenant, fremderFahrer.actor.subject.let(UUID::fromString), w.vehicle, w.shift))
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.staff.driverEvents.record(fremderFahrer, cmd(w, d2)) }.code)
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.driver_event WHERE tenant_id = ?", w.tenant.value))
    }

    @Test
    fun geraet_gesperrt_oder_an_andere_sitzung_gebunden_ist_verboten_und_unbekannt_404() {
        val w = fx.world(); val ctx = fx.context(w.tenant, w.subject, "sid-aaaaaaaa"); val d = device(w)
        val neueSitzung = fx.context(w.tenant, w.subject, "sid-bbbbbbbb")
        assertEquals(ErrorCode.FORBIDDEN, problem { fx.staff.driverEvents.record(neueSitzung, cmd(w, d)) }.code, "Gerät ist an die alte Sitzung gebunden: neu registrieren")
        val reg = fx.staff.driverDevices.register(neueSitzung, RegisterDriverDevice(d, DevicePlatform.ANDROID, "1.0.1", null))
        assertEquals(2L, reg.revision, "Rebind zählt die Revision")
        assertEquals(DriverEventOutcome.APPLIED, fx.staff.driverEvents.record(neueSitzung, cmd(w, d)).outcome)
        fx.staff.driverDevices.revoke(neueSitzung, d, 2)
        assertEquals(ErrorCode.FORBIDDEN, problem { fx.staff.driverEvents.record(neueSitzung, cmd(w, d)) }.code, "beendete Bindung")
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.staff.driverEvents.record(ctx, cmd(w, UUID.randomUUID())) }.code, "unbekanntes Gerät")
    }

    @Test
    fun geraet_gehoert_einem_konto_und_beendigung_ist_endgueltig() {
        val w = fx.world(); val ctx = fx.context(w.tenant, w.subject); val d = device(w)
        val again = fx.staff.driverDevices.register(ctx, RegisterDriverDevice(d, DevicePlatform.ANDROID, "1.0.0", null))
        assertEquals(1L, again.revision, "wiederholte Registrierung ist unverändert")
        val fremd = fx.context(w.tenant, UUID.randomUUID())
        assertEquals(ErrorCode.CONFLICT, problem { fx.staff.driverDevices.register(fremd, RegisterDriverDevice(d, DevicePlatform.IOS, "9", null)) }.code)
        assertEquals(ErrorCode.NOT_FOUND, problem { fx.staff.driverDevices.revoke(fremd, d, 1) }.code, "fremdes Gerät nicht auffindbar")
        assertEquals(ErrorCode.REVISION_MISMATCH, problem { fx.staff.driverDevices.revoke(ctx, d, 7) }.code)
        fx.staff.driverDevices.revoke(ctx, d, 1)
        assertEquals(ErrorCode.CONFLICT, problem { fx.staff.driverDevices.register(ctx, RegisterDriverDevice(d, DevicePlatform.ANDROID, "1.0.0", null)) }.code, "beendete Bindung bleibt beendet")
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id=? AND object_ref=?", w.tenant.value, "driver_device:$d"), "Registrierung und Beendigung auditiert")
    }

    @Test
    fun parallele_erstregistrierung_desselben_geraets_ergibt_eine_bindung() {
        val w = fx.world(); val ctx = fx.context(w.tenant, w.subject); val d = UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(10); val start = CountDownLatch(1)
        val futures = (1..20).map { pool.submit<Any> { start.await(); try { fx.staff.driverDevices.register(ctx, RegisterDriverDevice(d, DevicePlatform.ANDROID, "1.0.0", null)) } catch (e: Throwable) { e } } }
        start.countDown(); val results = futures.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(emptyList<Any>(), results.filterIsInstance<Throwable>().map { it.toString() })
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.driver_device WHERE id = ?", d))
        assertEquals(1, fx.count("SELECT revision FROM taktovia.driver_device WHERE id = ?", d))
    }

    @Test
    fun veralteter_plan_ist_ein_klaerfall_und_statusereignisse_nicht() {
        val w = fx.world(runRevision = 5); val ctx = fx.context(w.tenant, w.subject); val d = device(w)
        // angetretene, freigegebene Schicht (ab WP-M3-29 nimmt der Eingang stoppbezogene Ereignisse nur dann an)
        fx.exec("UPDATE taktovia.staff_shift SET state='STARTED', started_at=now(), started_device_id=? WHERE id=?", d, w.shift)
        fx.exec("INSERT INTO taktovia.resource_release(tenant_id,id,vehicle_id,shift_id,safety_revision,inspection_ref,qualification_revision,valid_until) VALUES (?,?,?,?,1,?,1,now()+interval '4 hours')",
            w.tenant.value, UUID.randomUUID(), w.vehicle, w.shift, UUID.randomUUID())
        fx.exec("UPDATE taktovia.vehicle SET active_block = false WHERE tenant_id = ? AND id = ?", w.tenant.value, w.vehicle)
        val now = Instant.now()
        fun checkIn(rev: Long) = cmd(w, d, type = DriverEventType.CHECK_IN, planRevision = rev, recordedAt = now)
        val staleCmd = checkIn(4)
        val first = fx.staff.driverEvents.record(ctx, staleCmd)
        assertEquals(DriverEventOutcome.REVIEW_REQUIRED, first.outcome); assertEquals("STALE_PLAN", first.reasonCode); assertEquals(5L, first.currentPlanRevision)
        val repeat = fx.staff.driverEvents.record(ctx, staleCmd)
        assertEquals(DriverEventOutcome.DUPLICATE, repeat.outcome); assertEquals(DriverEventOutcome.REVIEW_REQUIRED, repeat.originalOutcome, "ein Klärfall bleibt bei Wiederholung erkennbar")
        assertEquals("STALE_PLAN", repeat.reasonCode); assertEquals(first.receivedAt, repeat.receivedAt)
        val current = fx.staff.driverEvents.record(ctx, checkIn(5))
        assertEquals(DriverEventOutcome.APPLIED, current.outcome)
        val status = fx.staff.driverEvents.record(ctx, cmd(w, d, type = DriverEventType.BREAK_START, planRevision = 1))
        assertEquals(DriverEventOutcome.APPLIED, status.outcome, "Statusereignisse sind nicht planbezogen")
        assertNotEquals(0, fx.count("SELECT count(*) FROM taktovia.driver_event WHERE event_id = ? AND outcome='REVIEW_REQUIRED'", first.eventId))
    }

    @Test
    fun checkin_ohne_buchung_wird_abgewiesen() {
        val w = fx.world(); val ctx = fx.context(w.tenant, w.subject); val d = device(w)
        val e = problem { fx.staff.driverEvents.record(ctx, cmd(w, d, type = DriverEventType.CHECK_IN).copy(bookingId = null)) }
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code)
    }
}
