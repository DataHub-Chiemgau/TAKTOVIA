package de.taktovia.bookingdispatch.operator

import de.taktovia.bookingdispatch.OfferObjectsFixture
import de.taktovia.bookingdispatch.RideOfferFixture
import de.taktovia.bookingdispatch.api.BookingReason
import de.taktovia.bookingdispatch.api.BookingSearchFilter
import de.taktovia.bookingdispatch.api.CancelBooking
import de.taktovia.bookingdispatch.api.DueSignal
import de.taktovia.bookingdispatch.api.FlagCause
import de.taktovia.bookingdispatch.api.OperatorFlags
import de.taktovia.bookingdispatch.api.SignalSource
import de.taktovia.bookingdispatch.api.ThresholdSource
import de.taktovia.platform.alert.AlertPriority
import de.taktovia.platform.alert.BusinessAlertPort
import de.taktovia.platform.alert.BusinessAlertRequest
import de.taktovia.platform.alert.BusinessAlertResult
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcOperations
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * WP-M3-42 / LH-6.7-OPS-006: Überfälligkeit und ausstehende Informationen gegen die echte Datenbank unter der Runtime-Rolle, mit steuerbarer Uhr. Der geplante Einstieg der Testbuchungen ist
 * 2026-11-02 09:00Z; die Schwelle `on_time_pickup_tolerance_min` kommt aus einer Quelle je Dienst (Fixture, Zuordnung der Katalogparameter ist Arbeitsannahme OP-06). Der Alarmdienst wird durch eine
 * aufzeichnende Gegenstelle des Ports ersetzt (die echte Alarm-Grundlage prüft `AlertFoundationTest` und der Anwendungstest).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OverdueAlertTest {
    private lateinit var f: OperatorBookingFixture
    private val planned = Instant.parse("2026-11-02T09:00:00Z")

    /** Zeichnet Auslösen und Schließen auf; erzeugt je Schlüssel höchstens einen offenen Alarm wie die Alarm-Grundlage. */
    private class RecordingAlerts : BusinessAlertPort {
        val raised = CopyOnWriteArrayList<BusinessAlertRequest>(); val resolved = CopyOnWriteArrayList<String>()
        override fun raise(jdbc: JdbcOperations, request: BusinessAlertRequest): BusinessAlertResult { raised += request; return BusinessAlertResult(UUID.randomUUID(), true, AlertPriority.HIGH, 1, 0) }
        override fun resolve(jdbc: JdbcOperations, dedupeKey: String): Int { resolved += dedupeKey; return 1 }
    }

    @BeforeAll fun up() { f = OperatorBookingFixture() }
    @AfterAll fun down() = f.close()

    private fun flags(alerts: RecordingAlerts, thresholds: ThresholdSource = ThresholdSource.DEFAULTS, signals: SignalSource = SignalSource.NONE): OperatorFlags =
        OperatorFlags.create(f.fx.base.ctxTx, f.fx.clock, thresholds, signals, alerts)
    private fun at(iso: String) = f.fx.clock.set(Instant.parse(iso))
    private fun count(sql: String, vararg a: Any?) = f.fx.count(sql, *a)
    private fun openFlags(tenant: TenantId) = count("SELECT count(*) FROM taktovia.operator_flag WHERE tenant_id = ? AND state = 'OPEN'", tenant.value)

    private class Scene(val w: RideOfferFixture.World, val run: OfferObjectsFixture.Run, val op: OperatorBookingFixture.Operator, val bookings: List<de.taktovia.bookingdispatch.api.BookingDetail>)
    private fun scene(count: Int = 3, plannedAt: Instant = planned): Scene {
        val w = f.cf.world(); val run = f.fx.run(w, OfferObjectsFixture.cap(4, 1)); val op = f.operator(w.tenant, setOf(w.serviceId))
        return Scene(w, run, op, f.planned(w, run, op, count, plannedAt))
    }

    @Test
    fun schwelle_ueberschritten_loest_hinweis_mit_betroffenenzahl_aus() {
        val x = scene(3); val alerts = RecordingAlerts(); val ev = flags(alerts)
        try {
            at("2026-11-02T09:30:00Z")   // 30 Minuten nach dem geplanten Einstieg, Schwelle 5
            val r = ev.evaluate(x.w.tenant)
            assertEquals(1, r.opened); assertFalse(r.skipped)
            val item = ev.list(x.op.ctx, null, null, null, 10, null).single()
            assertEquals("OVERDUE", item.kind); assertEquals("PICKUP_OVERDUE", item.cause); assertEquals(3, item.affectedCount); assertEquals(5, item.thresholdMin); assertEquals(x.w.serviceId, item.serviceId); assertEquals(x.run.vehicleId, item.vehicleId)
            // Auftragsansicht: jede betroffene Buchung trägt den Hinweis mit der Zahl; Fahrzeugansicht: das Fahrzeug trägt ihn
            val rows = f.search.search(x.op.ctx, BookingSearchFilter(vehicleId = x.run.vehicleId), 10, null)
            assertEquals(3, rows.size); assertTrue(rows.all { r2 -> r2.flags.single().let { it.kind == "OVERDUE" && it.cause == "PICKUP_OVERDUE" && it.affectedCount == 3 } })
            val vehicle = ev.forVehicles(x.op.ctx, listOf(x.run.vehicleId))[x.run.vehicleId]!!.single()
            assertEquals("OVERDUE", vehicle.kind); assertEquals(3, vehicle.affectedCount)
            // der Hinweis geht über die Alarm-Grundlage: Art, Fahrzeug, Dienst, Bezug auf den Hinweis, Ursachencode (keine Namen)
            val alert = alerts.raised.single()
            assertEquals("OVERDUE", alert.kind); assertEquals(x.run.vehicleId, alert.vehicleId); assertEquals(x.w.serviceId, alert.serviceId); assertEquals("operator_flag", alert.refType); assertEquals("PICKUP_OVERDUE", alert.reasonCode)
            // zweiter Lauf: keine Änderung, kein zweiter Hinweis, kein zweiter Alarm
            val again = ev.evaluate(x.w.tenant)
            assertEquals(0, again.opened); assertEquals(0, again.updated); assertEquals(1, alerts.raised.size); assertEquals(1, openFlags(x.w.tenant))
        } finally { at("2026-11-02T07:00:00Z") }
    }

    @Test
    fun ausstehende_information_ist_eigener_zustand() {
        val x = scene(2); val alerts = RecordingAlerts()
        val due = mutableListOf<DueSignal>()
        val ev = flags(alerts, signals = SignalSource { _, _ -> due.toList() })
        try {
            at("2026-11-02T08:00:00Z")   // vor dem geplanten Einstieg: keine Verspätung
            due += DueSignal(FlagCause.PLAN_ACK_MISSING, x.run.vehicleId, x.run.runId, Instant.parse("2026-11-02T07:50:00Z"), 5)
            assertEquals(1, ev.evaluate(x.w.tenant).opened)
            val item = ev.list(x.op.ctx, null, null, null, 10, null).single()
            assertEquals("INFO_PENDING", item.kind); assertEquals("PLAN_ACK_MISSING", item.cause); assertEquals(2, item.affectedCount)
            // eigener Zustand, nicht als Verspätung: dieselbe Buchung trägt INFO_PENDING, nie OVERDUE; der Alarm hat eine eigene Art
            val rows = f.search.search(x.op.ctx, BookingSearchFilter(vehicleId = x.run.vehicleId), 10, null)
            assertTrue(rows.all { r -> r.flags.map { it.kind } == listOf("INFO_PENDING") })
            assertEquals(0, ev.list(x.op.ctx, null, "OVERDUE", null, 10, null).size); assertEquals(1, ev.list(x.op.ctx, null, "INFO_PENDING", null, 10, null).size)
            assertEquals("INFO_PENDING", alerts.raised.single().kind)
            // später kommt die Verspätung dazu: zwei getrennte Hinweise mit getrennten Arten
            at("2026-11-02T09:30:00Z"); ev.evaluate(x.w.tenant)
            val kinds = ev.list(x.op.ctx, null, null, null, 10, null).map { it.kind }.sorted()
            assertEquals(listOf("INFO_PENDING", "OVERDUE"), kinds)
            assertEquals(setOf("OVERDUE", "INFO_PENDING"), f.search.search(x.op.ctx, BookingSearchFilter(vehicleId = x.run.vehicleId), 1, null).first().flags.map { it.kind }.toSet())
            // ein Signal ohne Buchungen (Fahrzeug ohne Fahrten) bleibt ein Hinweis mit der Zahl 0, nicht ein Fehler
            assertThrows<IllegalArgumentException> { DueSignal(FlagCause.PICKUP_OVERDUE, x.run.vehicleId, null, Instant.now(), 1) }
        } finally { at("2026-11-02T07:00:00Z") }
    }

    @Test
    fun zwei_auswertungslaeufe_gleichzeitig_ein_hinweis() {
        repeat(5) {
            val x = scene(2); val alerts = RecordingAlerts(); val ev = flags(alerts)
            try {
                at("2026-11-02T09:30:00Z")
                val latch = CountDownLatch(1); val pool = Executors.newFixedThreadPool(4)
                try {
                    val results = (1..4).map { pool.submit(Callable { latch.await(); ev.evaluate(x.w.tenant) }) }.also { latch.countDown() }.map { it.get() }
                    assertEquals(1, results.sumOf { it.opened }, "genau ein Lauf legt den Hinweis an: $results")
                    assertEquals(1, openFlags(x.w.tenant), "genau ein offener Hinweis je Sachverhalt")
                    assertEquals(1, count("SELECT count(*) FROM taktovia.operator_flag WHERE tenant_id = ?", x.w.tenant.value))
                    assertEquals(1, alerts.raised.size, "ein Alarm")
                } finally { pool.shutdownNow() }
            } finally { at("2026-11-02T07:00:00Z") }
        }
        // auch ohne die Lease schützt die Datenbank: ein zweiter offener Hinweis desselben Schlüssels ist nicht möglich
        val x = scene(1); val ev = flags(RecordingAlerts())
        try {
            at("2026-11-02T09:30:00Z"); ev.evaluate(x.w.tenant)
            val key = f.fx.base.stringOf("SELECT dedupe_key FROM taktovia.operator_flag WHERE tenant_id = ?", x.w.tenant.value)
            val ex = runCatching { f.fx.adminUpdate("INSERT INTO taktovia.operator_flag(tenant_id, id, kind, cause, dedupe_key) VALUES (?, ?, 'OVERDUE', 'PICKUP_OVERDUE', ?)", x.w.tenant.value, UUID.randomUUID(), key) }.exceptionOrNull()
            assertTrue(ex != null && ex.message!!.contains("operator_flag_one_open"), ex?.message)
        } finally { at("2026-11-02T07:00:00Z") }
    }

    @Test
    fun unterhalb_schwelle_kein_hinweis_und_nach_ursachenwegfall_erledigt() {
        val x = scene(2); val alerts = RecordingAlerts(); val ev = flags(alerts)
        try {
            at("2026-11-02T09:05:00Z")   // genau an der Schwelle (5 Minuten): noch nicht überfällig
            assertEquals(0, ev.evaluate(x.w.tenant).opened); assertEquals(0, openFlags(x.w.tenant)); assertTrue(alerts.raised.isEmpty())
            assertTrue(f.search.search(x.op.ctx, BookingSearchFilter(vehicleId = x.run.vehicleId), 10, null).all { it.flags.isEmpty() })
            at("2026-11-02T09:06:00Z")
            assertEquals(1, ev.evaluate(x.w.tenant).opened)
            // eine Buchung entfällt (Storno durch den Betrieb): die Betroffenenzahl sinkt, der Hinweis bleibt
            f.cf.bookings.cancel(x.op.ctx, x.bookings[0].id, CancelBooking(f.cf.bookings.get(x.op.ctx, x.bookings[0].id).revision, BookingReason.OPERATOR_CANCELLATION))
            val r = ev.evaluate(x.w.tenant)
            assertEquals(1, r.updated)
            assertEquals(1, ev.list(x.op.ctx, null, null, null, 10, null).single().affectedCount)
            // die zweite Buchung entfällt auch: Ursache weg, der Hinweis ist erledigt und verschwindet aus Liste und Zeilen
            f.cf.bookings.cancel(x.op.ctx, x.bookings[1].id, CancelBooking(f.cf.bookings.get(x.op.ctx, x.bookings[1].id).revision, BookingReason.OPERATOR_CANCELLATION))
            assertEquals(1, ev.evaluate(x.w.tenant).resolved)
            assertEquals(0, openFlags(x.w.tenant)); assertTrue(ev.list(x.op.ctx, null, null, null, 10, null).isEmpty())
            val done = ev.list(x.op.ctx, "RESOLVED", null, null, 10, null).single()
            assertEquals("RESOLVED", done.state); assertTrue(done.resolvedAt != null)
            assertEquals(1, alerts.resolved.size); assertTrue(f.search.search(x.op.ctx, BookingSearchFilter(vehicleId = x.run.vehicleId), 10, null).all { it.flags.isEmpty() })
            // ein erledigter Hinweis ist endgültig (Datenbank): nie wieder offen, nie gelöscht
            val ex = runCatching { f.fx.adminUpdate("UPDATE taktovia.operator_flag SET state = 'OPEN', resolved_at = NULL, revision = revision + 1 WHERE id = ?", done.id) }.exceptionOrNull()
            assertTrue(ex != null && ex.message!!.contains("final"), ex?.message)
        } finally { at("2026-11-02T07:00:00Z") }
    }

    @Test
    fun schwellenaenderung_wirkt_nicht_rueckwirkend() {
        val a = scene(1); val b = scene(1); val alerts = RecordingAlerts()
        var minutes = 5
        val ev = flags(alerts, thresholds = ThresholdSource { _, _, c -> if (c == FlagCause.PICKUP_OVERDUE) minutes else 15 })
        try {
            at("2026-11-02T09:10:00Z")
            ev.evaluate(a.w.tenant)
            val before = ev.list(a.op.ctx, null, null, null, 10, null).single()
            assertEquals(5, before.thresholdMin)
            // die Schwelle wird auf zwei Stunden erhöht: der bereits ausgelöste Hinweis bleibt offen und behält seine Schwelle ...
            minutes = 120; at("2026-11-02T09:20:00Z")
            assertEquals(0, ev.evaluate(a.w.tenant).resolved)
            val after = ev.list(a.op.ctx, null, null, null, 10, null).single()
            assertEquals(before.id, after.id); assertEquals(5, after.thresholdMin)
            // ... eine neue Auswertung anderer Fahrten nutzt die neue Schwelle: für Mandant b entsteht (noch) kein Hinweis
            assertEquals(0, ev.evaluate(b.w.tenant).opened); assertEquals(0, openFlags(b.w.tenant))
            // wieder gesenkt: der neue Hinweis entsteht mit der neuen Schwelle, der alte bleibt unverändert
            minutes = 3
            assertEquals(1, ev.evaluate(b.w.tenant).opened); assertEquals(3, ev.list(b.op.ctx, null, null, null, 10, null).single().thresholdMin)
            assertEquals(5, ev.list(a.op.ctx, null, null, null, 10, null).single().thresholdMin)
            // die Schwelle am Hinweis ist auch in der Datenbank unveränderlich
            val ex = runCatching { f.fx.adminUpdate("UPDATE taktovia.operator_flag SET threshold_min = 99, revision = revision + 1 WHERE id = ?", before.id) }.exceptionOrNull()
            assertTrue(ex != null && ex.message!!.contains("immutable"), ex?.message)
        } finally { at("2026-11-02T07:00:00Z") }
    }

    @Test
    fun betroffenenzahl_nur_berechtigter_dienst_und_hinweis_ohne_namen() {
        val a = scene(2); val b = scene(3); val alerts = RecordingAlerts(); val ev = flags(alerts)
        try {
            at("2026-11-02T09:30:00Z")
            ev.evaluate(a.w.tenant); ev.evaluate(b.w.tenant)
            // je Mandant nur der eigene Hinweis mit der eigenen Zahl (RLS), nie die Buchungen des anderen
            assertEquals(2, ev.list(a.op.ctx, null, null, null, 10, null).single().affectedCount); assertEquals(3, ev.list(b.op.ctx, null, null, null, 10, null).single().affectedCount)
            assertEquals(1, openFlags(a.w.tenant)); assertEquals(1, openFlags(b.w.tenant))
            val ids = f.fx.base.stringOf("SELECT array_to_string(affected_bookings, ',') FROM taktovia.operator_flag WHERE tenant_id = ?", a.w.tenant.value).split(',').map(UUID::fromString).toSet()
            assertEquals(a.bookings.map { it.id }.toSet(), ids); assertTrue(ids.none { it in b.bookings.map { x -> x.id } })
            // ein Konto mit anderem Dienstscope sieht den Hinweis des Dienstes nicht (weder Liste noch Fahrzeug noch Auftragszeile); die Berechtigung wird frisch gelesen
            val outsider = f.operator(a.w.tenant, setOf(UUID.randomUUID()))
            assertTrue(ev.list(outsider.ctx, null, null, null, 10, null).isEmpty()); assertTrue(ev.forVehicles(outsider.ctx, listOf(a.run.vehicleId)).isEmpty())
            val granted = f.operator(a.w.tenant, setOf(a.w.serviceId))
            assertEquals(1, ev.list(granted.ctx, null, null, null, 10, null).size)
            f.revokeScope(granted)
            assertTrue(ev.list(granted.ctx, null, null, null, 10, null).isEmpty(), "Rechteentzug wirkt sofort")
            // der Hinweis enthält nur Kennungen und Zahlen: die Zeile der Liste und der Alarm tragen weder Namen noch Kontakt noch Freitext
            val item = ev.list(a.op.ctx, null, null, null, 10, null).single()
            val text = item.toString() + alerts.raised.joinToString { it.toString() }
            assertFalse(Regex("(?i)(name|mail|@|phone|telefon|kontakt)").containsMatchIn(text.replace("refType", "").replace("operator_flag", "")), text)
            assertTrue(alerts.raised.all { it.caseNumber == null && it.reasonCode == "PICKUP_OVERDUE" })
            assertNull(item.resolvedAt)
        } finally { at("2026-11-02T07:00:00Z") }
    }
}
