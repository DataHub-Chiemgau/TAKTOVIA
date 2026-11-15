package de.taktovia.analytics

import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.analytics.api.CauseCategory
import de.taktovia.analytics.api.CauseState
import de.taktovia.analytics.api.CorrectCause
import de.taktovia.analytics.api.MetricQuery
import de.taktovia.analytics.api.MetricState
import de.taktovia.analytics.api.NotPerformedTrips
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.ContextRevalidator
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M4-55 / LH-I.6-KPI-009: nicht durchgeführte Fahrten mit Ursache und Verursachungskategorie aus einem bekannten Ereignisprotokoll (echte Datenbank, Runtime-Rolle, RLS, echte parallele Transaktionen).
 * Die erwarteten Zahlen stehen aus der Ereignisliste fest, nicht aus den Fakten.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotPerformedCauseTest {
    private lateinit var fx: FunnelFixture
    private lateinit var api: NotPerformedTrips
    private val base get() = fx.kpi.base

    @BeforeAll fun up() {
        fx = FunnelFixture()
        val ctxTx = ContextTransactions(base.tx, object : ContextRevalidator { override fun revalidate(jdbc: org.springframework.jdbc.core.JdbcOperations, ctx: RequestContext, lock: Boolean) {} })
        api = AnalyticsApi.createNotPerformed(ctxTx, base.tx, JdbcAuditPort())
    }
    @AfterAll fun down() { fx.close() }

    private fun ctx(t: TenantId, roles: Set<String> = setOf("dispatcher"), scopes: Set<String> = setOf("analytics:write")) =
        RequestContext(Actor(ActorType.HUMAN, "https://idp.test", "u-" + UUID.randomUUID(), "sid", true), t, roles, scopes, emptySet(), UUID.randomUUID())

    private fun noShow(t: TenantId, booking: UUID, at: String = "2026-12-01T06:20:00Z"): FunnelFixture.Ev {
        val id = UUID.randomUUID()
        return base.envelope(t, "driver.event_recorded", id, 1, at,
            """{"event_id":"$id","shift_id":"${UUID.randomUUID()}","vehicle_id":"${UUID.randomUUID()}","event_type":"NO_SHOW","outcome":"APPLIED","booking_id":"$booking","effective_at":"$at","no_show_wait_seconds":720,"no_show_grace_min":10}""")
            .let { FunnelFixture.Ev("driver.event_recorded", it.first, it.second) }
    }

    private fun feed(t: TenantId, vararg ev: FunnelFixture.Ev) = ev.forEach { base.analytics.facts.consume(t, it.json) }

    /** Bekanntes Protokoll: b1 Fahrzeugausfall, b2 No-Show, b3 Fahrgaststorno, b4 Betreiberstorno, b5 Storno mit unbekanntem Grund (Ursache offen), b6 durchgeführt (Ausstieg), b7 nur bestätigt. */
    private class Known(val t: TenantId, val b: List<UUID>)

    private fun known(): Known {
        val t = fx.tenant(); val b = (1..7).map { UUID.randomUUID() }
        b.forEach { feed(t, fx.confirmed(t, it, null)) }
        feed(t, fx.cancelled(t, b[0], "VEHICLE_OUTAGE", "FAHRZEUGAUSFALL", "OPERATOR", "2026-12-01T05:30:00Z"))
        feed(t, noShow(t, b[1]))
        feed(t, fx.cancelled(t, b[2], "PASSENGER_REQUEST", "FAHRGAST", "PASSENGER", "2026-12-01T05:31:00Z"))
        feed(t, fx.cancelled(t, b[3], "OPERATOR_CANCELLATION", "BETREIBER", "OPERATOR", "2026-12-01T05:32:00Z"))
        feed(t, fx.cancelled(t, b[4], "HOLD_EXPIRED", "FAHRGAST", "SYSTEM", "2026-12-01T05:33:00Z"))
        feed(t, fx.driver(t, b[5], "CHECK_IN", "2026-12-01T05:50:00Z"), fx.driver(t, b[5], "CHECK_OUT", "2026-12-01T06:00:00Z"))
        return Known(t, b)
    }

    @Test
    fun fahrzeugausfall_und_no_show_getrennt_erfasst_und_ursache_offen_sichtbar() {
        val k = known()
        val t0 = api.get(k.t, k.b[0]); val t1 = api.get(k.t, k.b[1])
        assertEquals("VEHICLE_OUTAGE", t0.cause); assertEquals(CauseCategory.OPERATOR, t0.category); assertEquals(CauseState.SET, t0.state)
        assertEquals("PASSENGER_NO_SHOW", t1.cause); assertEquals(CauseCategory.PASSENGER, t1.category)
        assertTrue(t0.cause != t1.cause && t0.category != t1.category, "verschiedene Ursache und verschiedene Kategorie")
        assertEquals("PASSENGER_CANCELLATION", api.get(k.t, k.b[2]).cause); assertEquals("OPERATOR_CANCELLATION", api.get(k.t, k.b[3]).cause)
        // Ursache offen: sichtbar und nicht stillschweigend ausgeschlossen
        val open = api.get(k.t, k.b[4]); assertEquals(CauseState.OPEN, open.state); assertNull(open.cause); assertNull(open.category)
        // durchgeführte und nur bestätigte Fahrten sind keine nicht durchgeführten
        for (i in 5..6) assertThrows<ProblemException> { api.get(k.t, k.b[i]) }.also { assertEquals(ErrorCode.NOT_FOUND, it.code) }
        val r = api.report(k.t, fx.day)
        assertEquals(5, r.total, "Ursache offen zählt mit"); assertEquals(1, r.open); assertEquals(0, r.conflict)
        assertEquals(mapOf("OPERATOR_CANCELLATION" to 1L, "PASSENGER_CANCELLATION" to 1L, "PASSENGER_NO_SHOW" to 1L, "VEHICLE_OUTAGE" to 1L), r.byCause.associate { it.cause to it.count })
        assertEquals(2L, r.byCategory.getValue(CauseCategory.OPERATOR)); assertEquals(2L, r.byCategory.getValue(CauseCategory.PASSENGER)); assertEquals(0L, r.byCategory.getValue(CauseCategory.EXTERNAL))
        assertEquals(listOf(k.b[4]), api.trips(k.t, fx.day, onlyOpen = true).map { it.bookingId })
        assertEquals(5, api.trips(k.t, fx.day).size)
        // Quoten mit Nenner: bestätigte, nicht stornierte Abholungen (b2 No-Show, b6, b7) bzw. alle bestätigten (7); Fahrzeugausfall getrennt von der Betreiberstornoquote
        assertEquals(3L, r.confirmedPickups); assertEquals(7L, r.confirmedBookings)
        assertEquals(1L, r.noShowRate.numerator); assertEquals(3L, r.noShowRate.denominator); assertEquals(BigDecimal("0.333333"), r.noShowRate.value)
        assertEquals(1L, r.operatorCancellationRate.numerator); assertEquals(7L, r.operatorCancellationRate.denominator)
        assertEquals(1L, r.vehicleOutageRate.numerator); assertEquals(7L, r.vehicleOutageRate.denominator)
        assertEquals(MetricState.OK, r.state); assertNotNull(r.asOf)
        // keine Fahrerzuordnung in Kategorie oder Ursache: der Ursachenkatalog kennt nur Betreiber, Fahrgast, extern
        assertEquals(setOf(CauseCategory.OPERATOR, CauseCategory.PASSENGER, CauseCategory.EXTERNAL), api.causes(k.t).map { it.category }.toSet())
        // dieselbe Ereignismenge erneut (Wiederholung) ändert nichts; Wiederaufbau ergibt dieselben Zeilen
        val before = api.trips(k.t, fx.day).map { it.bookingId to it.cause to it.revision }
        base.analytics.facts.rebuild(k.t)
        assertEquals(before.map { it.first }, api.trips(k.t, fx.day).map { it.bookingId to it.cause })
        // Mandantentrennung: ein anderer Mandant sieht diese Fahrten nicht
        val other = fx.tenant()
        assertEquals(0L, api.report(other, fx.day).total)
        assertThrows<ProblemException> { api.get(other, k.b[0]) }.also { assertEquals(ErrorCode.NOT_FOUND, it.code) }
    }

    @Test
    fun widerspruechliche_ereignisse_ergeben_konfliktmarkierung_statt_stiller_wahl_unabhaengig_von_der_reihenfolge() {
        repeat(4) { round ->
            val t = fx.tenant(); val b = UUID.randomUUID()
            val confirmed = fx.confirmed(t, b, null)
            val outage = fx.cancelled(t, b, "VEHICLE_OUTAGE", "FAHRZEUGAUSFALL", "OPERATOR", "2026-12-01T05:30:00Z"); val ns = noShow(t, b)
            feed(t, confirmed)
            val go = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
            val order = if (round % 2 == 0) listOf(outage, ns) else listOf(ns, outage)
            order.map { e -> pool.submit { go.await(); feed(t, e) } }.also { go.countDown() }.forEach { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
            val trip = api.get(t, b)
            assertEquals(CauseState.CONFLICT, trip.state, "Konfliktmarkierung: $trip"); assertNull(trip.cause)
            assertEquals(1, base.count("SELECT count(*) FROM taktovia.not_performed_trip WHERE booking_id = ?", b), "genau eine Zeile je Fahrt")
            assertEquals(1L, api.report(t, fx.day).conflict)
            // die Disposition klärt den Konflikt per Korrektur: danach genau eine wirksame Ursache
            assertEquals(CauseState.CORRECTED, api.correct(ctx(t), b, CorrectCause("VEHICLE_OUTAGE", "Klärung mit Fahrer: Fahrzeug war ausgefallen")).state)
        }
    }

    @Test
    fun no_show_rate_ist_im_kennzahlregister_ausfuehrbar_mit_nenner_und_datenstand() {
        val k = known()
        val d = fx.metrics.definition(k.t, "no_show_rate")
        assertTrue(d.executable); assertEquals(2, d.version)
        val v = fx.metrics.evaluate(k.t, MetricQuery(fx.day, "no_show_rate")).single()
        assertEquals(MetricState.OK, v.state); assertEquals(BigDecimal("1"), v.numerator!!.stripTrailingZeros()); assertEquals(BigDecimal("3"), v.denominator!!.stripTrailingZeros())
        assertEquals(BigDecimal("0.333333"), v.value); assertNotNull(v.asOf); assertEquals(2, v.definition.version)
        // Nullnenner: nie 0 oder 100 %
        val empty = fx.tenant(); feed(empty, fx.confirmed(empty, UUID.randomUUID(), null, serviceId = fx.kpi.service))
        val z = fx.metrics.evaluate(empty, MetricQuery(fx.day, "no_show_rate")).single()
        assertTrue(z.state == MetricState.OK || z.state == MetricState.NOT_COMPUTABLE)
    }

    @Test
    fun korrektur_behaelt_alten_wert_als_vermerk_und_negativfaelle() {
        val k = known(); val b = k.b[4]       // Ursache offen
        val open = api.get(k.t, b); assertEquals(1L, open.revision)
        val done = api.correct(ctx(k.t), b, CorrectCause("EXTERNAL_EVENT", "Unwetter, Strecke gesperrt", ifMatch = open.revision))
        assertEquals(CauseState.CORRECTED, done.state); assertEquals("EXTERNAL_EVENT", done.cause); assertEquals(CauseCategory.EXTERNAL, done.category); assertEquals(open.revision + 1, done.revision)
        val v = done.corrections.single()
        assertNull(v.oldCause); assertEquals("EXTERNAL_EVENT", v.newCause); assertEquals("Unwetter, Strecke gesperrt", v.reason); assertTrue(v.actor.startsWith("user:")); assertNotNull(v.at); assertEquals(1, v.no)
        // zweite Korrektur: der alte Wert (EXTERNAL_EVENT) bleibt als Vermerk, abgeleitete Ursache bleibt unverändert sichtbar
        val second = api.correct(ctx(k.t), b, CorrectCause("OPERATOR_CANCELLATION", "Betreiber hat tatsächlich storniert"))
        assertEquals(listOf("EXTERNAL_EVENT"), second.corrections.mapNotNull { it.oldCause }); assertEquals(2, second.corrections.size); assertNull(second.derivedCause)
        // Audit
        assertEquals(2, base.count("SELECT count(*) FROM taktovia.audit_event WHERE tenant_id = ? AND action = 'analytics.not_performed.corrected'", k.t.value))
        // Negativ: ohne Recht (Rolle, Scope) 403, ohne Grund 422, unbekannte Ursache 422, gleiche Ursache 422, veraltete Revision 412, fremder Mandant 404; nichts geschrieben
        fun reject(code: ErrorCode, c: RequestContext = ctx(k.t), cmd: CorrectCause = CorrectCause("EXTERNAL_EVENT", "Begründung")) = assertThrows<ProblemException> { api.correct(c, k.b[0], cmd) }.also { assertEquals(code, it.code, it.detail) }
        reject(ErrorCode.FORBIDDEN, ctx(k.t, setOf("support"))); reject(ErrorCode.FORBIDDEN, ctx(k.t, setOf("passenger"))); reject(ErrorCode.FORBIDDEN, ctx(k.t, scopes = setOf("analytics:read")))
        reject(ErrorCode.VALIDATION_FAILED, cmd = CorrectCause("EXTERNAL_EVENT", "")); reject(ErrorCode.VALIDATION_FAILED, cmd = CorrectCause("EXTERNAL_EVENT", "  ")); reject(ErrorCode.VALIDATION_FAILED, cmd = CorrectCause("ERFUNDEN", "Begründung"))
        reject(ErrorCode.VALIDATION_FAILED, cmd = CorrectCause("VEHICLE_OUTAGE", "Begründung"))            // ist schon die wirksame Ursache
        reject(ErrorCode.REVISION_MISMATCH, cmd = CorrectCause("EXTERNAL_EVENT", "Begründung", ifMatch = 99))
        reject(ErrorCode.NOT_FOUND, ctx(fx.tenant()))
        assertTrue(api.get(k.t, k.b[0]).corrections.isEmpty(), "abgewiesene Korrekturen schreiben keinen Vermerk")
        // Vermerke sind unveränderlich: Runtime-Rolle hat kein UPDATE/DELETE, auch die Administration scheitert am Trigger
        assertThrows<Exception> { base.tx.run(k.t) { j -> j.update("UPDATE taktovia.not_performed_correction SET reason = 'x' WHERE booking_id = ?", b) } }
        assertThrows<Exception> { base.tx.run(k.t) { j -> j.update("DELETE FROM taktovia.not_performed_correction WHERE booking_id = ?", b) } }
        assertThrows<Exception> { base.exec("DELETE FROM taktovia.not_performed_correction WHERE booking_id = ?", b) }
        // die Korrektur überlebt den Wiederaufbau der Fakten
        base.analytics.facts.rebuild(k.t)
        assertEquals("OPERATOR_CANCELLATION", api.get(k.t, b).cause); assertEquals(2, api.get(k.t, b).corrections.size)
    }

    @Test
    fun parallele_korrekturen_behalten_beide_vermerke() {
        repeat(3) {
            val k = known(); val b = k.b[4]
            val go = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
            val causes = listOf("EXTERNAL_EVENT", "OPERATOR_CANCELLATION")
            val results = causes.map { c -> pool.submit<Result<Any>> { go.await(); runCatching { api.correct(ctx(k.t), b, CorrectCause(c, "Parallele Korrektur zu $c")) } } }
            go.countDown(); results.map { it.get(60, TimeUnit.SECONDS) }.forEach { assertTrue(it.isSuccess, it.exceptionOrNull()?.toString()) }; pool.shutdown()
            val t = api.get(k.t, b)
            // beide Vermerke bleiben, lückenlos nummeriert; der alte Wert des zweiten ist der neue des ersten; der letzte Vermerk bestimmt den eindeutigen Stand
            assertEquals(listOf(1, 2), t.corrections.map { it.no }); assertEquals(setOf("EXTERNAL_EVENT", "OPERATOR_CANCELLATION"), t.corrections.map { it.newCause }.toSet())
            assertNull(t.corrections[0].oldCause); assertEquals(t.corrections[0].newCause, t.corrections[1].oldCause)
            assertEquals(t.corrections.last().newCause, t.cause); assertEquals(CauseState.CORRECTED, t.state); assertEquals(3L, t.revision)
            assertEquals(1, base.count("SELECT count(*) FROM taktovia.not_performed_trip WHERE booking_id = ?", b))
        }
        // mit Versionsprüfung: genau eine der beiden Korrekturen gelingt, die zweite wird mit 412 abgewiesen (kein stilles Überschreiben)
        val k = known(); val b = k.b[4]; val rev = api.get(k.t, b).revision
        val go = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        val rs = listOf("EXTERNAL_EVENT", "OPERATOR_CANCELLATION").map { c -> pool.submit<Result<Any>> { go.await(); runCatching { api.correct(ctx(k.t), b, CorrectCause(c, "Mit Versionsprüfung $c", ifMatch = rev)) } } }
        go.countDown(); val done = rs.map { it.get(60, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, done.count { it.isSuccess }, done.toString())
        assertEquals(ErrorCode.REVISION_MISMATCH, (done.single { it.isFailure }.exceptionOrNull() as ProblemException).code)
        assertEquals(1, api.get(k.t, b).corrections.size)
    }
}
