package de.taktovia.analytics

import de.taktovia.analytics.api.Completeness
import de.taktovia.analytics.api.ConsumeOutcome
import de.taktovia.analytics.api.FactSource
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * WP-M4-45: Faktenbasis und Datenstand gegen die echte Datenbank (Runtime-Rolle, RLS): Projektion der Fixtures, Reihenfolge-, Wiederholungs- und Verspätungsunabhängigkeit, Wiederaufbau,
 * Datenstand mit Vollständigkeit, Mandantentrennung, Lücken in der Aggregatrevision, parallele Worker, keine Klartextdaten. (Der Architekturtest „schreibt nur eigene Tabellen“ ist
 * `M4TableOwnershipTest` in `backend/app`.)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FactBaseTest {
    private lateinit var fx: AnalyticsFixture
    @BeforeAll fun up() { fx = AnalyticsFixture() }
    @AfterAll fun down() { fx.close() }

    private class Ids {
        val attempt = UUID.randomUUID(); val offer = UUID.randomUUID(); val booking = UUID.randomUUID(); val service = UUID.randomUUID(); val hold = UUID.randomUUID(); val run = UUID.randomUUID()
        val shift = UUID.randomUUID(); val vehicle = UUID.randomUUID(); val stop1 = UUID.randomUUID(); val stop2 = UUID.randomUUID(); val block = UUID.randomUUID(); val release = UUID.randomUUID()
        val cancelled = UUID.randomUUID(); val noShow = UUID.randomUUID()
    }

    /** Ereignisse einer Fahrt in fachlicher Reihenfolge; die Eigentümer-Revisionen entsprechen den Verträgen (Versuch/Angebot/Fahrerereignis 1, Sperre 1/2, Buchung 1 und 3). */
    private fun fixtureEvents(t: TenantId, i: Ids): List<Pair<UUID, String>> {
        fun e(type: String, agg: UUID, rev: Long, at: String, payload: String) = fx.envelope(t, type, agg, rev, at, payload)
        fun drv(type: String, at: String, booking: UUID?, stop: UUID, extra: String = "") = UUID.randomUUID().let { id ->
            e("driver.event_recorded", id, 1, at, """{"event_id":"$id","shift_id":"${i.shift}","vehicle_id":"${i.vehicle}","event_type":"$type","outcome":"APPLIED","route_stop_id":"$stop","booking_id":${booking?.let { "\"$it\"" }},
                "reason_code":null,"effective_at":"$at","offline_recorded":false,"no_show_reason":null,"no_show_wait_seconds":null,"no_show_grace_min":null$extra,"received_at":"$at"}""")
        }
        return listOf(
            e("booking.attempt_recorded", i.attempt, 1, "2026-12-01T05:00:00Z", """{"attempt_id":"${i.attempt}","service_id":"${i.service}","outcome":"RECORDED","time_mode":"DEPARTURE","requested_at":"2026-12-01T05:00:00Z","passengers":2,"wheelchair":0,"coarse_origin":"Z-12","coarse_destination":"Z-14","channel":"APP"}"""),
            e("booking.offer_created", i.offer, 1, "2026-12-01T05:00:05Z", """{"offer_id":"${i.offer}","attempt_id":"${i.attempt}","service_id":"${i.service}","expires_at":"2026-12-01T05:10:00Z","pickup_earliest":"2026-12-01T06:00:00Z","pickup_latest":"2026-12-01T06:10:00Z","gross_minor":850,"currency":"EUR"}"""),
            e("booking.confirmed", i.booking, 3, "2026-12-01T05:02:00Z", """{"booking_id":"${i.booking}","hold_id":"${i.hold}","service_id":"${i.service}","run_id":"${i.run}","pickup_earliest":"2026-12-01T06:00:00Z","pickup_latest":"2026-12-01T06:10:00Z","gross_minor":850,"currency":"EUR","capacity_vector":{"seats":2}}"""),
            e("fleet.vehicle_blocked", i.block, 1, "2026-12-01T04:00:00Z", """{"vehicle_id":"${i.vehicle}","block_id":"${i.block}","source":"SAFETY","reason_code":"MANUAL","safety_revision":2}"""),
            e("fleet.vehicle_block_lifted", i.block, 2, "2026-12-01T04:30:00Z", """{"vehicle_id":"${i.vehicle}","block_id":"${i.block}","source":"SAFETY","safety_revision":2,"release_decision_id":"${UUID.randomUUID()}"}"""),
            e("fleet.release_issued", i.release, 1, "2026-12-01T04:40:00Z", """{"release_id":"${i.release}","vehicle_id":"${i.vehicle}","shift_id":"${i.shift}","inspection_id":"${UUID.randomUUID()}","safety_revision":2,"valid_until":"2026-12-01T18:00:00Z"}"""),
            drv("ARRIVAL", "2026-12-01T06:01:00Z", null, i.stop1),
            drv("CHECK_IN", "2026-12-01T06:03:00Z", i.booking, i.stop1),
            drv("CHECK_OUT", "2026-12-01T06:20:00Z", i.booking, i.stop2),
            e("booking.confirmed", i.cancelled, 3, "2026-12-01T05:03:00Z", """{"booking_id":"${i.cancelled}","hold_id":"${UUID.randomUUID()}","service_id":"${i.service}","run_id":"${i.run}","pickup_earliest":"2026-12-01T07:00:00Z","pickup_latest":"2026-12-01T07:10:00Z","gross_minor":400,"currency":"EUR","capacity_vector":{"seats":1}}"""),
            e("booking.cancelled", i.cancelled, 5, "2026-12-01T05:30:00Z", """{"booking_id":"${i.cancelled}","reason":"PASSENGER_REQUEST","reason_code":"PASSENGER_REQUEST","cause_class":"FAHRGAST","initiator":"PASSENGER"}"""),
            e("booking.confirmed", i.noShow, 3, "2026-12-01T05:04:00Z", """{"booking_id":"${i.noShow}","hold_id":"${UUID.randomUUID()}","service_id":"${i.service}","run_id":"${i.run}","pickup_earliest":"2026-12-01T06:30:00Z","pickup_latest":"2026-12-01T06:40:00Z","gross_minor":600,"currency":"EUR","capacity_vector":{"seats":1}}"""),
            drv("NO_SHOW", "2026-12-01T06:55:00Z", i.noShow, i.stop2, ""","no_show_wait_seconds_x":1""").let { (id, json) -> id to json.replace("\"no_show_wait_seconds\":null", "\"no_show_wait_seconds\":720").replace("\"no_show_grace_min\":null", "\"no_show_grace_min\":10") },
        )
    }

    private fun feed(t: TenantId, events: List<Pair<UUID, String>>) = events.map { fx.analytics.facts.consume(t, it.second) }

    @Test
    fun projektion_der_fixtures() {
        val t = fx.tenant(); val i = Ids()
        val out = feed(t, fixtureEvents(t, i))
        assertTrue(out.all { it == ConsumeOutcome.PROCESSED }, out.toString())
        val dump = fx.factDump(t)
        assertEquals("attempt|${i.attempt}|${i.service}|RECORDED|DEPARTURE|2026-12-01 05:00:00+00|2|0|Z-12|Z-14|APP", dump.single { it.startsWith("attempt|") }.replace("|null", ""))
        assertTrue(dump.single { it.startsWith("offer|") }.contains("850|EUR"))
        val b = dump.filter { it.startsWith("booking|") }
        assertTrue(b.single { it.contains(i.booking.toString()) }.endsWith("|COMPLETED"), "bestätigt, eingestiegen, ausgestiegen: abgeschlossen")
        assertTrue(b.single { it.contains(i.cancelled.toString()) }.endsWith("|CANCELED") && b.single { it.contains(i.cancelled.toString()) }.contains("PASSENGER_REQUEST|FAHRGAST"))
        assertTrue(b.single { it.contains(i.noShow.toString()) }.endsWith("|720|NO_SHOW"), b.toString())
        assertEquals(3, dump.count { it.startsWith("vehicle|") }); assertEquals(4, dump.count { it.startsWith("stop|") })
    }

    @Test
    fun doppelte_verspaetete_und_ungeordnete_ereignisse_ergeben_identische_fakten() {
        val ordered = fx.tenant(); val io = Ids()
        val base = fixtureEvents(ordered, io)
        feed(ordered, base)
        // dieselben Ereignisse (gleiche IDs, Mandant ausgenommen) in 6 zufälligen Reihenfolgen, mit Wiederholungen
        for (seed in 1..6) {
            val t = fx.tenant()
            val same = base.map { (id, json) -> id to json.replace(ordered.value.toString(), t.value.toString()) }
            val rnd = Random(seed)
            val delivery = (same + same.shuffled(rnd).take(5) + same.shuffled(rnd).take(3)).shuffled(rnd)
            val outcomes = delivery.map { fx.analytics.facts.consume(t, it.second) }
            assertEquals(same.size, outcomes.count { it == ConsumeOutcome.PROCESSED }, "Seed $seed: jedes Ereignis wirkt genau einmal")
            assertEquals(delivery.size - same.size, outcomes.count { it == ConsumeOutcome.DUPLICATE })
            assertEquals(fx.factDump(ordered), fx.factDump(t), "Seed $seed: gleiche Fakten wie bei geordneter Einmalzustellung")
        }
    }

    @Test
    fun wiederaufbau_liefert_dieselben_zahlen() {
        val t = fx.tenant(); val i = Ids()
        feed(t, fixtureEvents(t, i))
        val before = fx.factDump(t)
        fx.analytics.facts.rebuild(t)
        assertEquals(before, fx.factDump(t), "Wiederaufbau aus den gespeicherten Ereignissen")
        fx.exec("DELETE FROM taktovia.fact_booking WHERE tenant_id = ?", t.value); fx.exec("DELETE FROM taktovia.fact_stop_event WHERE tenant_id = ?", t.value)   // beschädigte Projektion
        fx.analytics.facts.rebuild(t)
        assertEquals(before, fx.factDump(t), "auch aus einer beschädigten Projektion")
    }

    @Test
    fun jede_faktenquelle_hat_datenstand_und_vollstaendigkeit() {
        val t = fx.tenant(); val i = Ids()
        assertEquals(emptyList<Any>(), fx.analytics.facts.watermarks(t), "ohne Ereignisse gibt es keinen Datenstand")
        feed(t, fixtureEvents(t, i))
        val w = fx.analytics.facts.watermarks(t).associateBy { it.source }
        assertEquals(FactSource.entries.toSet(), w.keys, "Datenstand je Quelle")
        assertTrue(w.values.all { it.completeness == Completeness.COMPLETE && it.gapCount == 0 && it.processedUntil != null && it.eventCount > 0 })
        assertEquals(java.time.Instant.parse("2026-12-01T06:55:00Z"), w.getValue(FactSource.DRIVER_EVENT).processedUntil)
        assertEquals(java.time.Instant.parse("2026-12-01T04:40:00Z"), w.getValue(FactSource.VEHICLE_STATE).processedUntil)
        assertEquals(4L, w.getValue(FactSource.BOOKING).eventCount - 0L)
    }

    @Test
    fun ereignis_eines_fremden_mandanten_im_falschen_kontext_wird_verworfen() {
        val a = fx.tenant(); val b = fx.tenant(); val i = Ids()
        val (_, foreign) = fx.envelope(b, "booking.attempt_recorded", i.attempt, 1, "2026-12-01T05:00:00Z", """{"attempt_id":"${i.attempt}","service_id":"${i.service}","outcome":"RECORDED","time_mode":"DEPARTURE","requested_at":"2026-12-01T05:00:00Z","passengers":1}""")
        assertEquals(ConsumeOutcome.REJECTED_TENANT, fx.analytics.facts.consume(a, foreign), "Ereignis des Mandanten B im Kontext des Workers von A")
        val (_, crossRecipient) = fx.envelope(a, "booking.attempt_recorded", i.attempt, 1, "2026-12-01T05:00:00Z", """{"attempt_id":"${i.attempt}","service_id":"${i.service}","outcome":"RECORDED","time_mode":"DEPARTURE","requested_at":"2026-12-01T05:00:00Z","passengers":1}""", recipient = b)
        assertEquals(ConsumeOutcome.REJECTED_TENANT, fx.analytics.facts.consume(a, crossRecipient)); assertEquals(ConsumeOutcome.REJECTED_TENANT, fx.analytics.facts.consume(b, crossRecipient), "Eigentümer und Empfänger weichen ab")
        for (t in listOf(a, b)) { assertEquals(0, fx.count("SELECT count(*) FROM taktovia.analytics_event_inbox WHERE tenant_id = ?", t.value)); assertEquals(0, fx.count("SELECT count(*) FROM taktovia.fact_attempt WHERE tenant_id = ?", t.value)) }
        // Mandantentrennung durch RLS: Fakten von A sind im Kontext von B nicht sichtbar
        feed(a, listOf(fx.envelope(a, "booking.attempt_recorded", i.attempt, 1, "2026-12-01T05:00:00Z", """{"attempt_id":"${i.attempt}","service_id":"${i.service}","outcome":"RECORDED","time_mode":"DEPARTURE","requested_at":"2026-12-01T05:00:00Z","passengers":1}""")))
        assertEquals(1, fx.tx.run(a, readOnly = true) { j -> j.queryForObject("SELECT count(*) FROM taktovia.fact_attempt", Int::class.java) })
        assertEquals(0, fx.tx.run(b, readOnly = true) { j -> j.queryForObject("SELECT count(*) FROM taktovia.fact_attempt", Int::class.java) })
        assertEquals(emptyList<Any>(), fx.analytics.facts.watermarks(b))
    }

    @Test
    fun luecke_in_der_aggregatrevision_wird_als_unvollstaendig_markiert() {
        val t = fx.tenant(); val i = Ids()
        val blocked = fx.envelope(t, "fleet.vehicle_blocked", i.block, 1, "2026-12-01T04:00:00Z", """{"vehicle_id":"${i.vehicle}","block_id":"${i.block}","source":"SAFETY","reason_code":"MANUAL","safety_revision":1}""")
        val lifted = fx.envelope(t, "fleet.vehicle_block_lifted", i.block, 2, "2026-12-01T04:30:00Z", """{"vehicle_id":"${i.vehicle}","block_id":"${i.block}","source":"SAFETY","safety_revision":1,"release_decision_id":"${UUID.randomUUID()}"}""")
        assertEquals(ConsumeOutcome.PROCESSED, fx.analytics.facts.consume(t, lifted.second), "Revision 2 vor Revision 1: wird nicht verworfen")
        val gap = fx.analytics.facts.watermarks(t).single()
        assertEquals(Completeness.INCOMPLETE, gap.completeness); assertEquals(1, gap.gapCount); assertEquals(FactSource.VEHICLE_STATE, gap.source)
        assertEquals(ConsumeOutcome.PROCESSED, fx.analytics.facts.consume(t, blocked.second), "die fehlende Revision kommt verspätet")
        val closed = fx.analytics.facts.watermarks(t).single()
        assertEquals(Completeness.COMPLETE, closed.completeness); assertEquals(0, closed.gapCount); assertEquals(2L, closed.eventCount)
        assertEquals(2, fx.count("SELECT count(*) FROM taktovia.fact_vehicle_state WHERE tenant_id = ?", t.value))
    }

    @Test
    fun zwei_worker_verarbeiten_dasselbe_ereignis_parallel_eine_wirkung() {
        val t = fx.tenant(); val i = Ids()
        val ev = fx.envelope(t, "booking.confirmed", i.booking, 3, "2026-12-01T05:02:00Z", """{"booking_id":"${i.booking}","hold_id":"${i.hold}","service_id":"${i.service}","run_id":"${i.run}","pickup_earliest":"2026-12-01T06:00:00Z","pickup_latest":"2026-12-01T06:10:00Z","gross_minor":850,"currency":"EUR","capacity_vector":{"seats":2}}""")
        val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
        val rs = (1..8).map { pool.submit<ConsumeOutcome> { go.await(); fx.analytics.facts.consume(t, ev.second) } }
        go.countDown(); val out = rs.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, out.count { it == ConsumeOutcome.PROCESSED }, out.toString()); assertEquals(7, out.count { it == ConsumeOutcome.DUPLICATE })
        assertEquals(1, fx.count("SELECT count(*) FROM taktovia.analytics_event_inbox WHERE tenant_id = ?", t.value)); assertEquals(1, fx.count("SELECT count(*) FROM taktovia.fact_booking WHERE tenant_id = ?", t.value))
        assertEquals(1L, fx.analytics.facts.watermarks(t).single().eventCount)
    }

    @Test
    fun verschiedene_ereignisse_desselben_fakts_parallel_ergeben_den_vollstaendigen_fakt() {
        repeat(3) {
            val t = fx.tenant(); val i = Ids()
            val events = fixtureEvents(t, i).filter { it.second.contains(i.booking.toString()) }   // Bestätigung, Einstieg, Ausstieg derselben Buchung
            val pool = Executors.newFixedThreadPool(events.size); val go = CountDownLatch(1)
            val rs = events.map { e -> pool.submit<ConsumeOutcome> { go.await(); fx.analytics.facts.consume(t, e.second) } }
            go.countDown(); rs.forEach { assertEquals(ConsumeOutcome.PROCESSED, it.get(60, TimeUnit.SECONDS)) }; pool.shutdown()
            assertTrue(fx.factDump(t).single { it.startsWith("booking|") }.endsWith("|COMPLETED"), "kein verlorenes Ereignis durch gleichzeitige Ableitung")
        }
    }

    @Test
    fun fakten_enthalten_keine_klartextdaten_und_nur_vergroebertes_start_und_ziel() {
        val t = fx.tenant(); val i = Ids()
        val pii = """{"attempt_id":"${i.attempt}","service_id":"${i.service}","outcome":"RECORDED","time_mode":"DEPARTURE","requested_at":"2026-12-01T05:00:00Z","passengers":1,"coarse_origin":"Z-12","coarse_destination":"Z-14",
            "passenger_name":"Erika Mustermann","email":"erika@example.org","phone":"+49 351 1234567","pickup_address":"Hauptstr. 1, 01067 Dresden","origin_lat":51.0504,"origin_lon":13.7373}"""
        assertEquals(ConsumeOutcome.PROCESSED, fx.analytics.facts.consume(t, fx.envelope(t, "booking.attempt_recorded", i.attempt, 1, "2026-12-01T05:00:00Z", pii).second))
        val stored = fx.strings("SELECT payload::text FROM taktovia.analytics_event_inbox WHERE tenant_id = ?", t.value).single() + fx.factDump(t).joinToString()
        for (leak in listOf("Mustermann", "erika@example.org", "1234567", "Hauptstr", "51.0504", "13.7373", "passenger_name", "pickup_address")) assertFalse(stored.contains(leak), "Klartext $leak in Eingang oder Fakten")
        assertTrue(stored.contains("Z-12") && stored.contains("Z-14"), "vergröberte Herkunft und Ziel bleiben")
        val columns = fx.strings("SELECT column_name FROM information_schema.columns WHERE table_schema = 'taktovia' AND table_name LIKE 'fact\\_%'")
        assertEquals(emptyList<String>(), columns.filter { Regex("name|email|phone|address|(^|_)(lat|lon|latitude|longitude)($|_)|contact|iban").containsMatchIn(it) }, "kein Faktenfeld für Personendaten")
    }

    @Test
    fun gleiche_event_id_anderer_inhalt_unbekannter_typ_und_kaputte_huelle() {
        val t = fx.tenant(); val i = Ids()
        val id = UUID.randomUUID()
        val a = fx.envelope(t, "booking.offer_created", i.offer, 1, "2026-12-01T05:00:05Z", """{"offer_id":"${i.offer}","attempt_id":"${i.attempt}","service_id":"${i.service}","expires_at":"2026-12-01T05:10:00Z","pickup_earliest":"2026-12-01T06:00:00Z","pickup_latest":"2026-12-01T06:10:00Z","gross_minor":850,"currency":"EUR"}""", eventId = id)
        val b = fx.envelope(t, "booking.offer_created", i.offer, 1, "2026-12-01T05:00:05Z", """{"offer_id":"${i.offer}","attempt_id":"${i.attempt}","service_id":"${i.service}","expires_at":"2026-12-01T05:10:00Z","pickup_earliest":"2026-12-01T06:00:00Z","pickup_latest":"2026-12-01T06:10:00Z","gross_minor":999,"currency":"EUR"}""", eventId = id)
        assertEquals(ConsumeOutcome.PROCESSED, fx.analytics.facts.consume(t, a.second)); assertEquals(ConsumeOutcome.REJECTED_PAYLOAD_MISMATCH, fx.analytics.facts.consume(t, b.second))
        assertTrue(fx.factDump(t).single { it.startsWith("offer|") }.contains("|850|"), "der erste Inhalt bleibt")
        assertEquals(ConsumeOutcome.UNSUPPORTED_EVENT, fx.analytics.facts.consume(t, fx.envelope(t, "payment.captured", UUID.randomUUID(), 1, "2026-12-01T05:00:05Z", "{}").second))
        assertEquals(ConsumeOutcome.REJECTED_SCHEMA, fx.analytics.facts.consume(t, "{\"kein\":\"ereignis\"}"))
        assertEquals(ConsumeOutcome.REJECTED_SCHEMA, fx.analytics.facts.consume(t, fx.envelope(t, "booking.offer_created", i.offer, 1, "2026-12-01T05:00:05Z", """{"gross_minor":1}""").second), "Pflichtfeld des Fakts fehlt")
    }
}
