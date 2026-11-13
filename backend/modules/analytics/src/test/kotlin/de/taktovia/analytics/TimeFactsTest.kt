package de.taktovia.analytics

import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.analytics.api.ConsumeOutcome
import de.taktovia.analytics.api.NotAssessable
import de.taktovia.analytics.api.RecordOutcome
import de.taktovia.analytics.api.StopEvent
import de.taktovia.analytics.api.TimeFactCommand
import de.taktovia.analytics.api.TimeKind
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Zeitfakten (WP-M4-52, LH-I.6-KPI-006) gegen die echte Datenbank unter der Runtime-Rolle. `ANNOUNCED` nur mit Testwert (OP 28). */
class TimeFactsTest {
    companion object {
        private val fx = AnalyticsFixture()
        private val tf = AnalyticsApi.createTimeFacts(fx.tx)
        @JvmStatic @AfterAll fun close() = fx.close()
    }

    private class Ids { val booking = UUID.randomUUID(); val hold = UUID.randomUUID(); val service = UUID.randomUUID(); val run = UUID.randomUUID(); val shift = UUID.randomUUID(); val vehicle = UUID.randomUUID(); val stop = UUID.randomUUID() }

    private fun confirmed(t: TenantId, i: Ids, revision: Long = 1, earliest: String = "2026-12-01T10:00:00Z", latest: String = "2026-12-01T10:15:00Z", arrival: String = "2026-12-01T10:45:00Z") =
        fx.envelope(t, "booking.confirmed", i.booking, revision, "2026-12-01T09:00:00Z", """{"booking_id":"${i.booking}","hold_id":"${i.hold}","service_id":"${i.service}","run_id":"${i.run}","run_revision":1,"pickup_earliest":"$earliest","pickup_latest":"$latest","arrival_latest":"$arrival","gross_minor":850,"currency":"EUR","capacity_vector":{"seats":1}}""")

    private fun revised(t: TenantId, i: Ids, revision: Long, pickup: String, dropoff: String) =
        fx.envelope(t, "dispatch.run_revised", i.run, revision, "2026-12-01T09:30:00Z", """{"run_id":"${i.run}","previous_revision":${revision - 1},"revision":$revision,"cause":"REPLAN","booking_changes":[{"booking_id":"${i.booking}","kind":"TIME_CHANGED","sequence":$revision,"pickup_after":"$pickup","dropoff_after":"$dropoff"}]}""")

    private fun driver(t: TenantId, i: Ids, type: String, effective: String, received: String, offline: Boolean = false, outcome: String = "APPLIED") =
        fx.envelope(t, "driver.event_recorded", UUID.randomUUID(), 1, received, """{"event_id":"${UUID.randomUUID()}","shift_id":"${i.shift}","vehicle_id":"${i.vehicle}","event_type":"$type","outcome":"$outcome","route_stop_id":"${i.stop}","received_at":"$received","booking_id":"${i.booking}","reason_code":null,"effective_at":"$effective","offline_recorded":$offline,"no_show_reason":null,"no_show_wait_seconds":null,"no_show_grace_min":null}""")

    private fun at(s: String) = Instant.parse(s)

    @Test
    fun fehlende_ist_zeit_ist_nicht_bewertbar() {
        val t = fx.tenant(); val i = Ids()
        assertEquals(ConsumeOutcome.PROCESSED, tf.consume(t, confirmed(t, i).second))
        val trip = tf.forBooking(t, i.booking)
        assertEquals(1, trip.pickup.promised.size); assertEquals(emptyList<Any>(), trip.pickup.actual)
        assertNull(trip.pickup.delay.seconds, "keine Ist-Zeit: kein Wert, schon gar nicht 0"); assertEquals(NotAssessable.NO_ACTUAL, trip.pickup.delay.notAssessable)
        assertEquals(NotAssessable.NO_ACTUAL, trip.arrival.delay.notAssessable)
        // ohne jede Zusage: ebenfalls nicht bewertbar (Zustand „Zusage nicht vorhanden“)
        val none = tf.forBooking(t, UUID.randomUUID())
        assertEquals(NotAssessable.NO_PROMISE, none.pickup.delay.notAssessable); assertNull(none.arrival.delay.seconds)
        // ein Ereignis, das nicht wirksam wurde (Prüfung erforderlich), erzeugt keine Ist-Zeit
        assertEquals(ConsumeOutcome.UNSUPPORTED_EVENT, tf.consume(t, driver(t, i, "CHECK_IN", "2026-12-01T10:20:00Z", "2026-12-01T10:20:01Z", outcome = "REVIEW_REQUIRED").second))
        assertEquals(NotAssessable.NO_ACTUAL, tf.forBooking(t, i.booking).pickup.delay.notAssessable)
    }

    @Test
    fun fuenf_zeitarten_getrennt_verspaetung_nutzt_zusageversion_trotz_umplanung() {
        val t = fx.tenant(); val i = Ids()
        tf.consume(t, confirmed(t, i).second)
        tf.consume(t, revised(t, i, 2, "2026-12-01T10:05:00Z", "2026-12-01T10:35:00Z").second)
        assertEquals(RecordOutcome.CREATED, tf.record(t, TimeFactCommand(i.booking, StopEvent.PICKUP, TimeKind.FORECAST, at("2026-12-01T10:12:00Z"), "ETA_MODEL", i.run, forecastVersion = 1)))
        assertEquals(RecordOutcome.CREATED, tf.record(t, TimeFactCommand(i.booking, StopEvent.PICKUP, TimeKind.ANNOUNCED, at("2026-12-01T10:10:00Z"), "TEST_OP28", UUID.randomUUID())))
        tf.consume(t, driver(t, i, "CHECK_IN", "2026-12-01T10:20:00Z", "2026-12-01T10:20:02Z").second)
        tf.consume(t, driver(t, i, "CHECK_OUT", "2026-12-01T10:50:00Z", "2026-12-01T10:50:02Z").second)
        val before = tf.forBooking(t, i.booking)
        assertEquals(1, before.pickup.planned.size); assertEquals(1, before.pickup.announced.size); assertEquals(1, before.pickup.promised.size); assertEquals(1, before.pickup.forecast.size); assertEquals(1, before.pickup.actual.size)
        assertEquals(300L, before.pickup.delay.seconds, "Ist 10:20 minus Ende des Zusagefensters 10:15"); assertEquals(1L, before.pickup.delay.promiseVersion)
        assertEquals(300L, before.arrival.delay.seconds!!, "Ankunft 10:50 gegen zugesagt 10:45")
        assertEquals(at("2026-12-01T10:00:00Z"), before.pickup.promised.single().windowEarliest)
        // weitere Umplanungen und Prognosen nach der Zusage ändern die Vergleichsbasis nicht
        tf.consume(t, revised(t, i, 3, "2026-12-01T11:00:00Z", "2026-12-01T11:30:00Z").second)
        tf.record(t, TimeFactCommand(i.booking, StopEvent.PICKUP, TimeKind.FORECAST, at("2026-12-01T11:10:00Z"), "ETA_MODEL", i.run, forecastVersion = 2))
        val after = tf.forBooking(t, i.booking)
        assertEquals(before.pickup.delay, after.pickup.delay); assertEquals(before.pickup.promised, after.pickup.promised)
        assertEquals(2, after.pickup.planned.size); assertEquals(2, after.pickup.forecast.size)
        assertEquals(listOf(2L, 3L), after.pickup.planned.map { it.planRevision })
    }

    @Test
    fun einvernehmliche_aenderung_erzeugt_neue_zusageversion_die_alte_bleibt_lesbar() {
        val t = fx.tenant(); val i = Ids()
        tf.consume(t, confirmed(t, i).second)
        tf.consume(t, driver(t, i, "CHECK_IN", "2026-12-01T10:20:00Z", "2026-12-01T10:20:02Z").second)
        assertEquals(300L, tf.forBooking(t, i.booking).pickup.delay.seconds)
        // die geänderte Zusage (promise.changed, Revision 2) kommt über den Leseport der Buchung und wird als neue Version angehängt
        assertEquals(RecordOutcome.CREATED, tf.record(t, TimeFactCommand(i.booking, StopEvent.PICKUP, TimeKind.PROMISED, at("2026-12-01T10:30:00Z"), "PROMISE_CHANGE", i.booking, windowEarliest = at("2026-12-01T10:15:00Z"), promiseVersion = 2)))
        val trip = tf.forBooking(t, i.booking)
        assertEquals(listOf(1L, 2L), trip.pickup.promised.map { it.promiseVersion }, "beide Versionen lesbar")
        assertEquals(-600L, trip.pickup.delay.seconds); assertEquals(2L, trip.pickup.delay.promiseVersion)
    }

    @Test
    fun offline_ist_zeit_behaelt_geraete_und_serverzeit_und_dubletten_wirken_einmal() {
        val t = fx.tenant(); val i = Ids()
        tf.consume(t, confirmed(t, i).second)
        val ev = driver(t, i, "CHECK_IN", "2026-12-01T10:20:00Z", "2026-12-01T10:52:00Z", offline = true)
        assertEquals(ConsumeOutcome.PROCESSED, tf.consume(t, ev.second)); assertEquals(ConsumeOutcome.DUPLICATE, tf.consume(t, ev.second))
        val a = tf.forBooking(t, i.booking).pickup.actual.single()
        assertEquals(at("2026-12-01T10:20:00Z"), a.deviceTime); assertEquals(at("2026-12-01T10:52:00Z"), a.serverTime); assertTrue(a.offlineRecorded); assertNotEquals(a.deviceTime, a.serverTime)
        // dieselbe Version mit anderem Wert: nicht überschrieben
        assertEquals(RecordOutcome.DUPLICATE, tf.record(t, TimeFactCommand(i.booking, StopEvent.PICKUP, TimeKind.PROMISED, at("2026-12-01T10:15:00Z"), "BOOKING_CONFIRMED", i.booking, windowEarliest = at("2026-12-01T10:00:00Z"), promiseVersion = 1)))
        assertEquals(RecordOutcome.CONFLICT, tf.record(t, TimeFactCommand(i.booking, StopEvent.PICKUP, TimeKind.PROMISED, at("2026-12-01T12:00:00Z"), "BOOKING_CONFIRMED", i.booking, promiseVersion = 1)))
        assertEquals(at("2026-12-01T10:15:00Z"), tf.forBooking(t, i.booking).pickup.promised.single().value)
        assertThrows<IllegalArgumentException> { TimeFactCommand(i.booking, StopEvent.PICKUP, TimeKind.ACTUAL, at("2026-12-01T10:20:00Z"), "X", UUID.randomUUID(), serverTime = at("2026-12-01T10:52:00Z"), offlineRecorded = true) }
    }

    @Test
    fun dst_doppelstunde_ergibt_zwei_eindeutige_zeitpunkte() {
        val t = fx.tenant(); val i = Ids()
        // 25.10.2026, 02:30 Ortszeit Europe/Berlin gibt es zweimal: +02:00 und +01:00
        tf.consume(t, driver(t, i, "CHECK_IN", "2026-10-25T02:30:00+02:00", "2026-10-25T00:30:05Z").second)
        tf.consume(t, driver(t, i, "CHECK_IN", "2026-10-25T02:30:00+01:00", "2026-10-25T01:30:05Z").second)
        val actual = tf.forBooking(t, i.booking).pickup.actual.map { it.value }
        assertEquals(listOf(at("2026-10-25T00:30:00Z"), at("2026-10-25T01:30:00Z")), actual)
    }

    @Test
    fun zwei_parallele_umplanungen_und_prognose_lassen_die_zusage_unveraendert_jede_revision_einmal() {
        val t = fx.tenant(); val i = Ids()
        tf.consume(t, confirmed(t, i).second)
        val promiseBefore = tf.forBooking(t, i.booking).pickup.promised
        val work = listOf(
            { tf.consume(t, revised(t, i, 2, "2026-12-01T10:40:00Z", "2026-12-01T11:10:00Z").second) },
            { tf.consume(t, revised(t, i, 2, "2026-12-01T10:40:00Z", "2026-12-01T11:10:00Z").second) },
            { tf.consume(t, revised(t, i, 3, "2026-12-01T11:00:00Z", "2026-12-01T11:30:00Z").second) },
            { if (tf.record(t, TimeFactCommand(i.booking, StopEvent.PICKUP, TimeKind.FORECAST, at("2026-12-01T11:20:00Z"), "ETA_MODEL", i.run, forecastVersion = 2)) == RecordOutcome.CREATED) ConsumeOutcome.PROCESSED else ConsumeOutcome.DUPLICATE },
            { if (tf.record(t, TimeFactCommand(i.booking, StopEvent.PICKUP, TimeKind.FORECAST, at("2026-12-01T11:20:00Z"), "ETA_MODEL", i.run, forecastVersion = 2)) == RecordOutcome.CREATED) ConsumeOutcome.PROCESSED else ConsumeOutcome.DUPLICATE },
        )
        val pool = Executors.newFixedThreadPool(work.size); val go = CountDownLatch(1)
        val rs = work.map { w -> pool.submit<ConsumeOutcome> { go.await(); w() } }
        go.countDown(); val out = rs.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(3, out.count { it == ConsumeOutcome.PROCESSED }, out.toString()); assertEquals(2, out.count { it == ConsumeOutcome.DUPLICATE })
        val trip = tf.forBooking(t, i.booking)
        assertEquals(promiseBefore, trip.pickup.promised, "Zusageversion unverändert")
        assertEquals(listOf(2L, 3L), trip.pickup.planned.mapNotNull { it.planRevision }.sorted(), "jede Planrevision genau einmal")
        assertEquals(listOf(2L), trip.pickup.forecast.map { it.forecastVersion })
    }

    @Test
    fun fremder_mandant_ist_nicht_sichtbar_und_die_runtime_rolle_kann_nur_anhaengen() {
        val a = fx.tenant(); val b = fx.tenant(); val i = Ids()
        tf.consume(a, confirmed(a, i).second)
        assertEquals(ConsumeOutcome.REJECTED_TENANT, tf.consume(b, confirmed(a, i).second), "Ereignis des Mandanten A im Kontext von B")
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.time_fact WHERE tenant_id = ?", b.value))
        val other = tf.forBooking(b, i.booking)
        assertEquals(emptyList<Any>(), other.pickup.promised); assertEquals(NotAssessable.NO_PROMISE, other.pickup.delay.notAssessable)
        for (sql in listOf("UPDATE taktovia.time_fact SET value_at = now()", "DELETE FROM taktovia.time_fact")) {
            assertThrows<Exception>(sql) { fx.tx.run(a) { j -> j.update(sql) } }
        }
        assertEquals(1, tf.forBooking(a, i.booking).pickup.promised.size)
        assertEquals(ConsumeOutcome.UNSUPPORTED_EVENT, tf.consume(a, fx.envelope(a, "booking.cancelled", i.booking, 2, "2026-12-01T09:10:00Z", """{"booking_id":"${i.booking}"}""").second))
    }
}
