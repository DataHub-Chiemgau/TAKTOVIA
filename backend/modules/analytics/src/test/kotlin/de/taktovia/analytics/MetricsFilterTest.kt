package de.taktovia.analytics

import de.taktovia.analytics.api.AnalyticsApi
import de.taktovia.analytics.api.MetricFilter
import de.taktovia.analytics.api.MetricState
import de.taktovia.analytics.api.PickupTolerance
import de.taktovia.analytics.api.PickupToleranceSource
import de.taktovia.analytics.api.ReportingPeriod
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.UUID

/**
 * WP-M4-58 / LH-I.6-KPI-011: Leistungsdaten nach Mandant (nur der aktive), Betreiber, Bediengebiet, Dienst, Tag, Stunde und Fahrzeugtyp, einzeln und kombiniert. Ein Bericht über zwei Regionen wird auf eine Region
 * und eine Stunde eingeschränkt und stimmt mit der Referenzberechnung überein: erwartet wird aus der bekannten Ereignisliste gezählt, nicht aus den Fakten. Echte Datenbank, Runtime-Rolle.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MetricsFilterTest {
    private lateinit var fx: ReportFilterFixture
    @BeforeAll fun up() { fx = ReportFilterFixture() }
    @AfterAll fun down() { fx.close() }

    private fun bd(v: Long) = BigDecimal(v)

    @Test
    fun filter_region_und_stunde_stimmt_mit_referenz() {
        val w = fx.world(); val offersByAttempt = mutableMapOf<ReportFilterFixture.Att, UUID>()
        // Zwei Regionen, zwei Stunden: Region 1 (Dienst 1) und Region 2 (Dienst 2/3); zu jedem zweiten Versuch ein Angebot zur selben Ortsstunde
        val spec = listOf(Triple(w.s1, 8, 6), Triple(w.s1, 17, 4), Triple(w.s2, 8, 5), Triple(w.s2, 17, 5), Triple(w.s3, 8, 3), Triple(w.s3, 17, 3))
        for ((s, h, n) in spec) repeat(n) { i ->
            val attempt = fx.attempt(w, s, h, rejected = i == 0, minute = 5 + i)
            if (i % 2 == 1) offersByAttempt[w.reference.last()] = offer(w, s, attempt, h, 5 + i)   // Angebot zum selben Versuch (Ort und Stunde wie der Versuch)
        }
        // Referenz: aus der Ereignisliste gezählt (Region 2, Stunde 8)
        val r2h8 = w.ref { it.region == w.r2 && it.localHour == 8 }
        assertEquals(8L, r2h8.size.toLong())
        val f = MetricFilter(regionId = w.r2, hour = 8)
        assertEquals(bd(r2h8.size.toLong()), fx.eval(w, "requests", f).value)
        assertEquals(bd(r2h8.count { it.rejected }.toLong()), fx.eval(w, "capacity_rejections", f).value)
        val offers = offersByAttempt.keys.count { it.region == w.r2 && it.localHour == 8 }
        val funnel = fx.metrics.funnel(w.tenant, fx.day, f)
        assertEquals(r2h8.size.toLong(), funnel.requests, "Trichter: Anfragen"); assertEquals(offers.toLong(), funnel.offers, "Trichter: Angebote derselben Region und Stunde")
        // Quote aus dem Trichter: Versuche mit Angebot / Versuche ohne technischen Fehler (hier alle)
        val rate = fx.eval(w, "offer_rate", f)
        assertEquals(bd(offers.toLong()), rate.numerator); assertEquals(bd(r2h8.size.toLong()), rate.denominator)
        // dieselbe Einschränkung nur nach Region bzw. nur nach Stunde: Zeilen der anderen Region/Stunde zählen nicht
        assertEquals(bd(w.ref { it.region == w.r2 }.size.toLong()), fx.eval(w, "requests", MetricFilter(regionId = w.r2)).value)
        assertEquals(bd(w.ref { it.localHour == 8 }.size.toLong()), fx.eval(w, "requests", MetricFilter(hour = 8)).value)
        assertEquals(spec.sumOf { it.third }.toLong(), w.reference.size.toLong(), "Kontrolle der Verteilung")
    }

    @Test
    fun dimensionen_einzeln_und_kombiniert_filterbar() {
        val w = fx.world(); fx.standardData(w)
        val day = LocalDate.of(2026, 12, 1)
        data class C(val f: MetricFilter, val keep: (ReportFilterFixture.Att) -> Boolean)
        val all = listOf(
            C(MetricFilter(operatorId = w.o1)) { it.operator == w.o1 }, C(MetricFilter(serviceAreaId = w.a2)) { it.area == w.a2 }, C(MetricFilter(serviceId = w.s2)) { it.service == w.s2 },
            C(MetricFilter(day = day)) { it.localDay == day }, C(MetricFilter(hour = 17)) { it.localHour == 17 },
            C(MetricFilter(operatorId = w.o1, hour = 8)) { it.operator == w.o1 && it.localHour == 8 },
            C(MetricFilter(regionId = w.r2, serviceAreaId = w.a2)) { it.region == w.r2 && it.area == w.a2 },
            C(MetricFilter(operatorId = w.o1, regionId = w.r2, day = day, hour = 17)) { it.operator == w.o1 && it.region == w.r2 && it.localHour == 17 },
        )
        for (c in all) {
            val v = fx.eval(w, "requests", c.f)
            assertEquals(MetricState.OK, v.state, c.f.toString()); assertEquals(bd(w.ref(c.keep).size.toLong()), v.value, c.f.toString())
        }
        // Dimension „Mandant“ ist wirkungslos: der Bericht umfasst nur den aktiven Mandanten, auch wenn zwei Mandanten dieselben Dienstkennungen kennen
        val other = fx.world(); fx.standardData(other)
        assertEquals(bd(22), fx.eval(w, "requests").value); assertEquals(bd(22), fx.eval(other, "requests").value)
    }

    @Test
    fun fahrzeugtyp_und_fahrzeug_filtern_abholkennzahlen_gegen_referenz() {
        val w = fx.world(); val tf = AnalyticsApi.createTimeFacts(fx.kpi.base.tx)
        val kleinbus = UUID.randomUUID(); val bus = UUID.randomUUID(); val kleinbus2 = UUID.randomUUID()
        listOf(kleinbus to "KLEINBUS", kleinbus2 to "KLEINBUS", bus to "BUS").forEach { (v, type) ->
            fx.exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_type) VALUES (?,?,'x','{}',?)", w.tenant.value, v, type)
        }
        class P(val service: UUID, val vehicle: UUID, val type: String, val delayMin: Long, val hour: Int)
        val ps = mutableListOf<P>()
        val spec = listOf(Triple(w.s1, kleinbus, 4), Triple(w.s2, kleinbus2, 4), Triple(w.s1, bus, 3), Triple(w.s2, bus, 3))
        var k = 0
        for ((s, v, n) in spec) repeat(n) { ps += P(s, v, if (v == bus) "BUS" else "KLEINBUS", (k++ % 5).toLong(), 8) }
        val windowEnd = "2026-12-01T08:15:00Z"
        for (p in ps) {
            val booking = UUID.randomUUID()
            val confirmed = fx.kpi.base.envelope(w.tenant, "booking.confirmed", booking, 1, "2026-12-01T06:00:00Z",
                """{"booking_id":"$booking","hold_id":"${UUID.randomUUID()}","service_id":"${p.service}","run_id":"${UUID.randomUUID()}","run_revision":1,"pickup_earliest":"2026-12-01T08:10:00Z","pickup_latest":"$windowEnd","gross_minor":850,"currency":"EUR","capacity_vector":{"seats":1}}""").second
            val actual = Instant.parse(windowEnd).plusSeconds(p.delayMin * 60).toString()
            val ev = UUID.randomUUID()
            val driver = fx.kpi.base.envelope(w.tenant, "driver.event_recorded", UUID.randomUUID(), 1, actual,
                """{"event_id":"$ev","shift_id":"${UUID.randomUUID()}","vehicle_id":"${p.vehicle}","event_type":"CHECK_IN","outcome":"APPLIED","route_stop_id":"${UUID.randomUUID()}","received_at":"$actual","booking_id":"$booking","reason_code":null,"effective_at":"$actual","offline_recorded":false,"no_show_reason":null,"no_show_wait_seconds":null,"no_show_grace_min":null}""").second
            listOf(confirmed, driver).forEach { fx.kpi.base.analytics.facts.consume(w.tenant, it); tf.consume(w.tenant, it) }
        }
        val src = PickupToleranceSource { _, _, _, _ -> PickupTolerance(2, "TENANT", UUID.randomUUID(), 1) }
        fun eval(metric: String, f: MetricFilter) = fx.metrics.evaluate(w.tenant, de.taktovia.analytics.api.MetricQuery(fx.day, metric, null, f), src).single()
        fun refOnTime(keep: (P) -> Boolean): Pair<Long, Long> = ps.filter(keep).let { l -> l.count { it.delayMin <= 2 }.toLong() to l.size.toLong() }
        val cases = listOf<Pair<MetricFilter, (P) -> Boolean>>(
            MetricFilter(vehicleType = "KLEINBUS") to { p -> p.type == "KLEINBUS" }, MetricFilter(vehicleType = "BUS") to { p -> p.type == "BUS" },
            MetricFilter(vehicleId = kleinbus) to { p -> p.vehicle == kleinbus }, MetricFilter(regionId = w.r1, vehicleType = "KLEINBUS") to { p -> p.service == w.s1 && p.type == "KLEINBUS" },
        )
        for ((f, keep) in cases) {
            val (num, den) = refOnTime(keep); val v = eval("pickup_on_time", f)
            assertEquals(MetricState.OK, v.state, f.toString()); assertEquals(bd(num), v.numerator, "$f Zähler"); assertEquals(bd(den), v.denominator, "$f Nenner")
            val late = ps.filter(keep).sumOf { maxOf(it.delayMin, 0L) }
            assertEquals(0, BigDecimal(late).compareTo(eval("mean_pickup_delay", f).numerator), "$f Summe der Verspätungsminuten")
        }
        // ein Fahrzeugtyp, der in den Daten nicht vorkommt: keine Fahrzeuge, kein Wert (nie 0 Prozent pünktlich)
        val none = eval("pickup_on_time", MetricFilter(vehicleType = "TAXI")); assertEquals(MetricState.NOT_COMPUTABLE, none.state); assertTrue(none.value == null)
    }

    private fun offer(w: ReportFilterFixture.World, service: UUID, attempt: UUID, localHour: Int, minute: Int): UUID {
        val id = UUID.randomUUID(); val at = ZonedDateTime.of(LocalDate.of(2026, 12, 1), LocalTime.of(localHour, minute, 30), fx.zone).toInstant().toString()
        val e = fx.kpi.base.envelope(w.tenant, "booking.offer_created", id, 1, at,
            """{"offer_id":"$id","attempt_id":"$attempt","service_id":"$service","expires_at":"2026-12-01T23:00:00Z","pickup_earliest":"2026-12-01T20:00:00Z","pickup_latest":"2026-12-01T20:10:00Z","gross_minor":850,"currency":"EUR"}""").second
        fx.kpi.base.analytics.facts.consume(w.tenant, e); return id
    }
}
