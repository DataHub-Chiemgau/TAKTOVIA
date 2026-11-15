package de.taktovia.analytics

import de.taktovia.analytics.api.MetricDimensions
import de.taktovia.analytics.api.MetricFilter
import de.taktovia.analytics.api.MetricQuery
import de.taktovia.analytics.api.MetricReason
import de.taktovia.analytics.api.MetricState
import de.taktovia.analytics.api.MetricValue
import de.taktovia.analytics.api.ReportScope
import de.taktovia.analytics.api.ReportingPeriod
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * WP-M4-58 / LH-6.14-ANA-003 (und LH-I.6-KPI-011): Filterdimensionen der Berichte ohne Mandantenüberschreitung auf der echten Datenbank (Runtime-Rolle, RLS). Die erwarteten Werte kommen aus dem unabhängigen
 * Referenzmodell der Fixture ([ReportFilterFixture.Att]), nicht aus den Fakten. Schwelle `kpi_aggregation_min_n` im Test: 3 (Testwert, nicht freigegeben).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportFilterTest {
    private lateinit var fx: ReportFilterFixture
    @BeforeAll fun up() { fx = ReportFilterFixture() }
    @AfterAll fun down() { fx.close() }

    private fun requests(w: ReportFilterFixture.World, f: MetricFilter, scope: ReportScope = ReportScope.TENANT_WIDE): Long = fx.eval(w, "requests", f, scope = scope).let { check(it.state == MetricState.OK) { "$f: ${it.state} ${it.reason}" }; it.value!!.toLong() }

    private fun world(): ReportFilterFixture.World = fx.world().also { fx.standardData(it) }

    @Test
    fun jeder_filter_veraendert_ergebnis_gegen_referenz() {
        val w = world(); val all = w.reference.size.toLong()
        assertEquals(22L, all); assertEquals(all, requests(w, MetricFilter()))
        data class Case(val name: String, val filter: MetricFilter, val expected: Long)
        val cases = listOf(
            Case("Betreiber 1", MetricFilter(operatorId = w.o1), w.ref { it.operator == w.o1 }.size.toLong()),
            Case("Betreiber 2", MetricFilter(operatorId = w.o2), w.ref { it.operator == w.o2 }.size.toLong()),
            Case("Dienst 1", MetricFilter(serviceId = w.s1), w.ref { it.service == w.s1 }.size.toLong()),
            Case("Dienst 3", MetricFilter(serviceId = w.s3), w.ref { it.service == w.s3 }.size.toLong()),
            Case("Region 1", MetricFilter(regionId = w.r1), w.ref { it.region == w.r1 }.size.toLong()),
            Case("Region 2", MetricFilter(regionId = w.r2), w.ref { it.region == w.r2 }.size.toLong()),
            Case("Gebiet 1", MetricFilter(serviceAreaId = w.a1), w.ref { it.area == w.a1 }.size.toLong()),
            Case("Gebiet 2", MetricFilter(serviceAreaId = w.a2), w.ref { it.area == w.a2 }.size.toLong()),
            Case("Stunde 8", MetricFilter(hour = 8), w.ref { it.localHour == 8 }.size.toLong()),
            Case("Stunde 17", MetricFilter(hour = 17), w.ref { it.localHour == 17 }.size.toLong()),
            Case("Tag 1.12.", MetricFilter(day = LocalDate.of(2026, 12, 1)), all),
        )
        for (c in cases) {
            val got = requests(w, c.filter)
            assertEquals(c.expected, got, c.name)
            if (c.name != "Tag 1.12.") assertNotEquals(all, got, "${c.name} verändert das Ergebnis")
        }
        // eine Ablehnungskennzahl: dieselbe Referenzberechnung (Zähler je Filter, Nenner = Anfragen im Filter)
        for (c in cases.filter { it.name.startsWith("Region") || it.name.startsWith("Betreiber") }) {
            val v = fx.eval(w, "capacity_rejections", c.filter)
            val expectedRejected = w.ref { a -> a.rejected && (c.filter.operatorId?.let { a.operator == it } ?: true) && (c.filter.regionId?.let { a.region == it } ?: true) }.size
            assertEquals(expectedRejected.toLong(), v.value!!.toLong(), c.name)
        }
        // ein Tag ohne Daten im Filter: der Tagesfilter außerhalb des Berichtszeitraums ist ein Eingabefehler
        val other = assertThrows<ProblemException> { fx.eval(w, "requests", MetricFilter(day = LocalDate.of(2026, 12, 9))) }
        assertEquals(ErrorCode.BAD_REQUEST, other.code)
    }

    @Test
    fun region_und_stunde_einschraenkbar() {
        val w = world()
        // Bericht über zwei Regionen (alle 22), eingeschränkt auf Region 2 und die Ortsstunde 8: S2 6x + S3 4x
        val expected = w.ref { it.region == w.r2 && it.localHour == 8 }.size.toLong()
        assertEquals(10L, expected)
        val v = fx.eval(w, "requests", MetricFilter(regionId = w.r2, hour = 8))
        assertEquals(MetricState.OK, v.state); assertEquals(expected, v.value!!.toLong())
        // Kombination aus drei Dimensionen: Betreiber 1, Region 2, Stunde 17 (nur Dienst 2: 3 Versuche, genau an der Schwelle)
        assertEquals(w.ref { it.operator == w.o1 && it.region == w.r2 && it.localHour == 17 }.size.toLong(), requests(w, MetricFilter(operatorId = w.o1, regionId = w.r2, hour = 17)))
        // der Filter steht im Ergebnis (Snapshot-Schlüssel) und die Kombination mit Dienst/Gebiet stimmt ebenfalls
        assertEquals(w.r2.toString(), v.filter.toMap()[MetricDimensions.REGION_ID]); assertEquals("8", v.filter.toMap()[MetricDimensions.HOUR])
        assertEquals(w.ref { it.area == w.a1 && it.localHour == 17 }.size.toLong(), requests(w, MetricFilter(serviceAreaId = w.a1, hour = 17)))
        // Funnel und Ablehnungsstatistik tragen dieselben Dimensionen
        val rej = fx.metrics.rejections(w.tenant, fx.day, MetricFilter(regionId = w.r2, hour = 8))
        assertEquals(expected, rej.attempts); assertEquals(w.ref { it.region == w.r2 && it.localHour == 8 && it.rejected }.size.toLong(), rej.rejections)
        val funnel = fx.metrics.funnel(w.tenant, fx.day, MetricFilter(regionId = w.r2, hour = 8))
        assertEquals(expected, funnel.requests)
    }

    @Test
    fun stundengrenzen_in_reporting_timezone_auch_am_dst_wechsel() {
        val w = fx.world()
        val oct25 = LocalDate.of(2026, 10, 25)   // 25 Stunden: 02:30 CEST (00:30Z) und 02:30 CET (01:30Z) sind beide Ortsstunde 2
        val period = ReportingPeriod.ofDays(fx.zone, oct25, oct25)
        assertEquals(25L, java.time.Duration.between(period.from, period.to).toHours())
        repeat(3) { fx.attemptAtInstant(w, w.s1, "2026-10-25T00:${30 + it}:00Z", false, w.a1, w.o1, w.r1, 2, oct25) }
        repeat(3) { fx.attemptAtInstant(w, w.s1, "2026-10-25T01:${30 + it}:00Z", false, w.a1, w.o1, w.r1, 2, oct25) }
        repeat(3) { fx.attemptAtInstant(w, w.s1, "2026-10-25T02:${30 + it}:00Z", false, w.a1, w.o1, w.r1, 3, oct25) }
        fun n(hour: Int?) = fx.eval(w, "requests", MetricFilter(hour = hour), period).value!!.toLong()
        assertEquals(9L, n(null)); assertEquals(6L, n(2), "beide Durchläufe der Ortsstunde 2"); assertEquals(3L, n(3))
    }

    @Test
    fun fremde_dienst_und_fahrzeug_id_liefern_keine_fremden_daten() {
        val a = world(); val b = world()
        // Dienst, Region, Betreiber, Gebiet des Mandanten B im Berichtskontext von A: dieselbe Antwort wie eine nie vergebene Kennung
        for (filter in listOf(MetricFilter(serviceId = b.s1), MetricFilter(regionId = b.r1), MetricFilter(operatorId = b.o1), MetricFilter(serviceAreaId = b.a1))) {
            val foreign = fx.eval(a, "requests", filter); val unknown = fx.eval(a, "requests", filter.copy(serviceId = filter.serviceId?.let { UUID.randomUUID() }, regionId = filter.regionId?.let { UUID.randomUUID() },
                operatorId = filter.operatorId?.let { UUID.randomUUID() }, serviceAreaId = filter.serviceAreaId?.let { UUID.randomUUID() }))
            assertEquals(0, foreign.value!!.toInt(), "$filter: keine Daten des Mandanten B"); assertEquals(unknown.value, foreign.value); assertEquals(unknown.state, foreign.state); assertEquals(unknown.reason, foreign.reason)
            assertEquals(unknown.numerator, foreign.numerator); assertEquals(unknown.asOf, foreign.asOf)
        }
        // fremdes Fahrzeug: wie ein unbekanntes Fahrzeug 404
        val vehicleB = UUID.randomUUID(); fx.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config) VALUES (?,?,'x','{}')", b.tenant.value, vehicleB)
        val foreign = assertThrows<ProblemException> { fx.eval(a, "energy_cost_per_100km", MetricFilter(vehicleId = vehicleB)) }
        val unknown = assertThrows<ProblemException> { fx.eval(a, "energy_cost_per_100km", MetricFilter(vehicleId = UUID.randomUUID())) }
        assertEquals(ErrorCode.NOT_FOUND, foreign.code); assertEquals(unknown.code, foreign.code); assertEquals(unknown.message, foreign.message)
        // Dimension ohne Bezug zur Kennzahl: Eingabefehler statt stillschweigend ignoriertem Filter
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows<ProblemException> { fx.eval(a, "requests", MetricFilter(vehicleType = "KLEINBUS")) }.code)
    }

    @Test
    fun manipulierte_tenant_id_liefert_keine_fremden_daten() {
        // Die Abfrage kennt den Mandanten nur aus dem Kontext; Kennungen des Mandanten B in jeder Dimension liefern nichts von B, und die Fakten von B bleiben unter dem Kontext von A unsichtbar (RLS)
        val a = world(); val b = world(); fx.attempt(b, b.s1, 9)   // B hat 23 Versuche, A 22
        assertEquals(22L, requests(a, MetricFilter())); assertEquals(23L, requests(b, MetricFilter()))
        assertEquals(0L, fx.eval(a, "requests", MetricFilter(serviceId = b.s1, regionId = b.r1, hour = 9)).value!!.toLong())
        // die Anfrage des Mandanten A mit dem Dienstscope des Kontexts von B (fremde Dienste) ergibt ebenfalls nichts
        val ctx = ctx(a.tenant, setOf(b.s1, b.s2))
        assertEquals(0L, fx.eval(a, "requests", MetricFilter(), scope = ReportScope(ctx)).value!!.toLong())
        assertEquals(0, fx.count("SELECT count(*) FROM taktovia.fact_attempt WHERE tenant_id = ? AND service_id = ANY (?)", a.tenant.value, arrayOf(b.s1, b.s2)), "kein Fakt des Mandanten A trägt einen Dienst von B")
    }

    private fun ctx(t: de.taktovia.platform.tenant.TenantId, services: Set<UUID>, membership: UUID? = null) =
        RequestContext(Actor(ActorType.HUMAN, "https://issuer.test", UUID.randomUUID().toString(), null, false), t, setOf("dispatcher"), setOf("analytics:read"), services, UUID.randomUUID(),
            membershipIds = membership?.let { setOf(it) } ?: emptySet())

    @Test
    fun filter_erweitert_berechtigungsscope_nie() {
        val w = world(); val scope = ReportScope(ctx(w.tenant, setOf(w.s1)))   // nur Dienst 1 (9 Versuche)
        assertEquals(9L, requests(w, MetricFilter(), scope), "ohne Filter genau der Scope")
        assertEquals(w.ref { it.service == w.s1 && it.localHour == 8 }.size.toLong(), requests(w, MetricFilter(hour = 8), scope))
        assertEquals(9L, requests(w, MetricFilter(serviceId = w.s1), scope)); assertEquals(9L, requests(w, MetricFilter(operatorId = w.o1), scope), "Betreiber 1 hat auch Dienst 2, der Scope schneidet ihn ab")
        // Wunsch auf Dienst 2, Region 2 oder Betreiber 2 (außerhalb des Scopes): leer, wie ein nicht vorhandenes Objekt
        for (f in listOf(MetricFilter(serviceId = w.s2), MetricFilter(regionId = w.r2), MetricFilter(operatorId = w.o2), MetricFilter(serviceAreaId = w.a2))) {
            val v = fx.eval(w, "requests", f, scope = scope); assertEquals(0, v.value!!.toInt(), "$f"); assertEquals(MetricState.OK, v.state)
        }
        // der Snapshot der eingeschränkten Sicht ist ein anderer als der des ganzen Mandanten
        assertNotEquals(fx.eval(w, "requests", MetricFilter(), scope = scope).snapshotId, fx.eval(w, "requests", MetricFilter()).snapshotId)
        // Ablehnungsstatistik und Trichter gelten im selben Scope
        assertEquals(9L, fx.metrics.rejections(w.tenant, fx.day, MetricFilter(), null, scope).attempts); assertEquals(9L, fx.metrics.funnel(w.tenant, fx.day, MetricFilter(), scope).requests)
    }

    @Test
    fun kleingruppe_nicht_durch_differenzbildung_offengelegt() {
        // PH-AT-021: Dienst 1 hat 10 Versuche (Stunde 8: 9, Stunde 9: 1), Dienst 2 hat 2 Versuche; Schwelle 3
        val w = fx.world()
        repeat(9) { fx.attempt(w, w.s1, 8, minute = 5 + it) }; fx.attempt(w, w.s1, 9); repeat(2) { fx.attempt(w, w.s2, 8, minute = 30 + it) }
        fun v(f: MetricFilter): MetricValue = fx.eval(w, "requests", f)
        fun assertSuppressed(x: MetricValue, why: String) { assertEquals(MetricState.NOT_COMPUTABLE, x.state, why); assertEquals(MetricReason.SMALL_GROUP, x.reason, why); assertTrue(x.value == null && x.numerator == null, "$why: weder 0 noch ein Wert") }
        assertEquals(12L, v(MetricFilter()).value!!.toLong())
        assertSuppressed(v(MetricFilter(serviceId = w.s2)), "Gruppe unter der Schwelle")
        // das Komplement des benachbarten Filters liegt unter der Schwelle: 12 - 10 = 2 wäre ablesbar, also ist auch Dienst 1 unterdrückt
        assertSuppressed(v(MetricFilter(serviceId = w.s1)), "Differenz Gesamt minus Dienst 1")
        assertSuppressed(v(MetricFilter(regionId = w.r1)), "Region 1 = Dienst 1")
        // gleiche Regel über die Stunde: Stunde 9 (1 Versuch) und Stunde 8 (Komplement 1)
        assertSuppressed(v(MetricFilter(hour = 9)), "Stunde 9"); assertSuppressed(v(MetricFilter(hour = 8)), "Differenz Gesamt minus Stunde 9")
        // Kombination: Dienst 1 und Stunde 8 hat 9 Versuche, aber das Weglassen der Stunde ergäbe 10-9 = 1
        assertSuppressed(v(MetricFilter(serviceId = w.s1, hour = 8)), "Differenz innerhalb Dienst 1")
        // eine Gruppe, deren Nachbarn alle mindestens die Schwelle haben, bleibt sichtbar (Gesamtfilter ohne Dimension ist keine Differenzbildung)
        assertEquals(12L, v(MetricFilter(day = LocalDate.of(2026, 12, 1))).value!!.toLong())
        // der Mandant-Parameter ändert die Schwelle: mit 2 ist Dienst 2 (2 Versuche) sichtbar, Dienst 1 (Komplement 2) ebenso
        val two = ReportScope(null, minGroupSize = 2)
        assertEquals(2L, fx.eval(w, "requests", MetricFilter(serviceId = w.s2), scope = two).value!!.toLong()); assertEquals(10L, fx.eval(w, "requests", MetricFilter(serviceId = w.s1), scope = two).value!!.toLong())
        // Leere Gruppe (0) ist kein Kleingruppenfall; dasselbe gilt für die Abfrage ohne Dimension
        assertEquals(0L, v(MetricFilter(serviceId = w.s3)).value!!.toLong())
    }

    @Test
    fun parallele_mandanten_abfragen_ohne_vermischung() {
        val a = world(); val b = fx.world().also { w -> repeat(7) { fx.attempt(w, w.s1, 8, minute = 5 + it) }; repeat(5) { fx.attempt(w, w.s2, 17, minute = 5 + it) } }
        val expectedA = mapOf("all" to 22L, "r2h8" to 10L, "s1" to 9L); val expectedB = mapOf("all" to 12L, "r2h8" to 0L, "s1" to 7L)
        val workers = 16; val rounds = 40; val pool = Executors.newFixedThreadPool(workers); val start = CountDownLatch(1); val errors = AtomicInteger(); val wrong = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val done = CountDownLatch(workers)
        repeat(workers) { i ->
            pool.submit {
                try {
                    start.await(); val mine = if (i % 2 == 0) a else b; val exp = if (i % 2 == 0) expectedA else expectedB
                    repeat(rounds) {
                        val got = mapOf(
                            "all" to fx.eval(mine, "requests", MetricFilter()).value, "r2h8" to fx.eval(mine, "requests", MetricFilter(regionId = mine.r2, hour = 8)).value, "s1" to fx.eval(mine, "requests", MetricFilter(serviceId = mine.s1)).value)
                        got.forEach { (k, v) -> val ev = exp.getValue(k); if (v == null || v.toLong() != ev) wrong += "worker $i $k: $v erwartet $ev" }
                    }
                } catch (e: Throwable) { errors.incrementAndGet(); wrong += "worker $i: $e" } finally { done.countDown() }
            }
        }
        start.countDown(); assertTrue(done.await(120, TimeUnit.SECONDS)); pool.shutdownNow()
        assertEquals(0, errors.get(), wrong.toString()); assertTrue(wrong.isEmpty(), wrong.take(5).toString())
    }

    @Test
    fun scope_entzug_wirkt_ab_commit() {
        val w = world(); val membership = UUID.randomUUID(); val subject = UUID.randomUUID().toString()
        fx.exec("INSERT INTO taktovia.membership(tenant_id,id,issuer,subject,role_code,service_scope) VALUES (?,?,'https://issuer.test',?,'dispatcher',CAST(? AS uuid[]))", w.tenant.value, membership, subject, "{${w.s1},${w.s2}}")
        val ctx = RequestContext(Actor(ActorType.HUMAN, "https://issuer.test", subject, null, false), w.tenant, setOf("dispatcher"), setOf("analytics:read"), setOf(w.s1, w.s2), UUID.randomUUID(), membershipIds = setOf(membership))
        val scope = ReportScope(ctx)
        assertEquals(18L, requests(w, MetricFilter(), scope), "Dienst 1 und 2: 9 + 9")
        // Entzug von Dienst 2 (der Kontext der Anfrage trägt noch beide, die Datenbank nur noch Dienst 1)
        fx.exec("UPDATE taktovia.membership SET service_scope = CAST(? AS uuid[]) WHERE tenant_id = ? AND id = ?", "{${w.s1}}", w.tenant.value, membership)
        assertEquals(9L, requests(w, MetricFilter(), scope), "ab dem Commit nur noch Dienst 1")
        assertEquals(0L, requests(w, MetricFilter(serviceId = w.s2), scope))
        // vollständiger Entzug: der Kontext trug Dienste, die Datenbank keinen mehr: keine Daten, nie „mandantenweit“
        fx.exec("UPDATE taktovia.membership SET revoked_at = clock_timestamp() WHERE tenant_id = ? AND id = ?", w.tenant.value, membership)
        assertEquals(0L, requests(w, MetricFilter(), scope))
        // parallel: Leser laufen ununterbrochen, der Entzug committet; Abfragen, die nach dem Commit beginnen, sehen Dienst 2 nie
        fx.exec("UPDATE taktovia.membership SET revoked_at = NULL, service_scope = CAST(? AS uuid[]) WHERE tenant_id = ? AND id = ?", "{${w.s1},${w.s2}}", w.tenant.value, membership)
        val committed = AtomicBoolean(false); val stop = AtomicBoolean(false); val violations = java.util.concurrent.ConcurrentLinkedQueue<String>(); val seenOld = AtomicInteger()
        val pool = Executors.newFixedThreadPool(6); val ready = CountDownLatch(6)
        repeat(6) {
            pool.submit {
                ready.countDown()
                while (!stop.get()) {
                    val after = committed.get(); val n = requests(w, MetricFilter(), scope)
                    if (!after && n == 18L) seenOld.incrementAndGet()
                    if (after && n != 9L) violations += "nach dem Commit begonnen, aber $n"
                }
            }
        }
        ready.await(); Thread.sleep(300)
        fx.exec("UPDATE taktovia.membership SET service_scope = CAST(? AS uuid[]) WHERE tenant_id = ? AND id = ?", "{${w.s1}}", w.tenant.value, membership); committed.set(true)
        Thread.sleep(500); stop.set(true); pool.shutdown(); assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertTrue(violations.isEmpty(), violations.take(5).toString()); assertTrue(seenOld.get() > 0, "vor dem Entzug wurde der alte Scope gesehen")
    }
}
