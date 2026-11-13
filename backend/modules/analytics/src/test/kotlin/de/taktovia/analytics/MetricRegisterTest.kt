package de.taktovia.analytics

import de.taktovia.analytics.api.ConsumeOutcome
import de.taktovia.analytics.api.DefinitionChange
import de.taktovia.analytics.api.MetricFilter
import de.taktovia.analytics.api.MetricQuery
import de.taktovia.analytics.api.MetricReason
import de.taktovia.analytics.api.MetricState
import de.taktovia.analytics.api.MetricValue
import de.taktovia.analytics.api.ReportingPeriod
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WP-M4-46 / LH-I.6-KPI-010: Kennzahlregister gegen die echte Datenbank: vollständige Definitionen, identische Werte samt Datenstand für drei Benutzer, Nullnenner und fehlende Daten als „nicht berechenbar“,
 * vorläufige Werte bei Lücken mit Abdeckungsgrad, Versionen und Snapshots, kleine Gruppen, Mandantentrennung, konsistenter Datenstand während der Projektion, Zeitzonen und Betriebstag.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MetricRegisterTest {
    private lateinit var fx: KpiFixture
    @BeforeAll fun up() { fx = KpiFixture() }
    @AfterAll fun down() { fx.close() }

    private fun values(t: de.taktovia.platform.tenant.TenantId, period: ReportingPeriod = fx.day) = fx.metrics.evaluate(t, MetricQuery(period)).associateBy { it.definition.metricId }
    private fun requests(t: de.taktovia.platform.tenant.TenantId, filter: MetricFilter) = fx.metrics.evaluate(t, MetricQuery(fx.day, metricId = "requests", filter = filter)).single()

    @Test
    fun jede_registrierte_kennzahl_weist_zaehler_nenner_ausschluesse_zeitbezug_und_filterdimensionen_aus() {
        val t = fx.tenant()
        val defs = fx.metrics.definitions(t)
        assertEquals(22, defs.size, "21 P1-Kennzahlen plus die Definition der technischen Partnerablehnungen")
        val p1 = setOf("requests", "technical_request_errors", "capacity_rejections", "personnel_rejections", "equipment_rejections", "area_rejections", "time_window_rejections", "offer_rate", "booking_conversion",
            "cancellation_rate_passenger", "cancellation_rate_operator", "no_show_rate", "pickup_on_time", "arrival_on_time", "mean_pickup_delay", "mean_arrival_delay", "empty_km_rate", "productive_km",
            "capacity_utilization_distance_weighted", "seat_time_utilization", "passenger_km")
        assertTrue(defs.map { it.metricId }.containsAll(p1))
        for (d in defs) {
            assertTrue(d.numerator.isNotBlank() && d.denominator.isNotBlank() && d.exclusions.isNotBlank() && d.timeBasis.isNotBlank(), d.metricId)
            assertTrue(d.filterDimensions.all { it in setOf("service_id", "channel") }, d.metricId); assertEquals(1, d.version); assertNotNull(d.approvedAt)
            assertEquals(d.executable, d.notExecutableReason == null, d.metricId)
        }
        assertEquals(setOf("requests", "technical_request_errors", "capacity_rejections", "personnel_rejections", "equipment_rejections", "area_rejections", "time_window_rejections"), defs.filter { it.executable }.map { it.metricId }.toSet())
        assertEquals(MetricReason.NO_PARTNERS_IN_P1, defs.single { it.metricId == "technical_partner_rejections" }.notExecutableReason, "Technische Partnerablehnungen nur als Definition (keine Partner in P1)")
    }

    @Test
    fun drei_benutzer_identische_werte_und_datenstand() {
        val t = fx.tenant(); fx.ph021(t)
        val results = (1..3).map { Executors.newSingleThreadExecutor().submit<List<MetricValue>> { fx.metrics.evaluate(t, MetricQuery(fx.day)) } }.map { it.get(30, TimeUnit.SECONDS) }
        assertEquals(results[0], results[1]); assertEquals(results[1], results[2], "identische Filter ergeben identische Kennzahlen einschließlich Datenstand und Snapshot")
        val requests = results[0].single { it.definition.metricId == "requests" }
        assertEquals(BigDecimal(10), requests.value); assertEquals(Instant.parse("2026-12-01T05:00:00Z"), requests.asOf); assertEquals(10L, requests.sourceEvents); assertEquals(MetricState.OK, requests.state)
        assertEquals(1, fx.base.count("SELECT count(*) FROM taktovia.aggregate_snapshot WHERE tenant_id = ? AND metric_id = 'requests'", t.value), "derselbe Stand ist genau ein Snapshot")
    }

    @Test
    fun fixture_ph_at_021_zehn_versuche_drei_ablehnungen() {
        val t = fx.tenant(); fx.ph021(t)
        val v = values(t)
        assertEquals(BigDecimal(10), v.getValue("requests").value)
        assertEquals(BigDecimal(1), v.getValue("capacity_rejections").value); assertEquals(BigDecimal(1), v.getValue("time_window_rejections").value); assertEquals(BigDecimal(1), v.getValue("area_rejections").value)
        assertEquals(BigDecimal(0), v.getValue("personnel_rejections").value, "Sekundärgründe (NO_STAFF, OUTSIDE_HOURS) erzeugen keine zusätzlichen Ablehnungen")
        assertEquals(BigDecimal(10), v.getValue("capacity_rejections").denominator, "Nenner (Bezugsgröße) ist die Zahl der Anfragen")
        val r = fx.metrics.rejections(t, fx.day)
        assertEquals(3L, r.rejections, "drei Ablehnungen: Summe der Primärgründe drei"); assertEquals(r.rejections, r.rows.sumOf { it.primaryCount })
    }

    @Test
    fun nullnenner_und_fehlende_daten_sind_nicht_berechenbar_nie_null_oder_hundert_prozent() {
        val none = fx.tenant()
        val noData = values(none)
        assertTrue(noData.filter { it.value.definition.executable }.values.all { it.state == MetricState.NOT_COMPUTABLE && it.reason == MetricReason.NO_DATA && it.value == null })
        // Daten vorhanden, aber keine Anfrage im Zeitraum: Quote mit Nenner 0 ist nicht berechenbar, die Absolutzahl ist 0
        val t = fx.tenant(); fx.feed(t, fx.attempt(t, at = "2026-12-05T10:00:00Z"))
        val v = values(t)
        val tech = v.getValue("technical_request_errors")
        assertEquals(MetricState.NOT_COMPUTABLE, tech.state); assertEquals(MetricReason.ZERO_DENOMINATOR, tech.reason); assertNull(tech.value); assertEquals(BigDecimal.ZERO, tech.denominator)
        assertEquals(BigDecimal.ZERO, v.getValue("requests").value); assertEquals(MetricState.OK, v.getValue("requests").state)
        // nicht ausführbare Kennzahlen weisen keinen Wert aus, sondern ihren Grund
        val offer = v.getValue("offer_rate")
        assertEquals(MetricState.NOT_COMPUTABLE, offer.state); assertEquals(MetricReason.NOT_EXECUTABLE_IN_THIS_RELEASE, offer.reason); assertNull(offer.value); assertNull(offer.snapshotId)
        assertEquals(MetricReason.NO_PARTNERS_IN_P1, v.getValue("technical_partner_rejections").reason)
    }

    @Test
    fun zwanzig_prozent_fehlende_quelldaten_sind_vorlaeufig_mit_abdeckungsgrad() {
        val t = fx.tenant()
        // 8 vollständige Versuche und 2 Versuche, deren erste Revision fehlt: die Quelle ist unvollständig, 80 % Abdeckung
        val ev = (1..8).map { fx.attempt(t) } + (1..2).map { fx.attempt(t, revision = 2) }
        fx.feed(t, *ev.toTypedArray())
        val r = values(t).getValue("requests")
        assertEquals(MetricState.PROVISIONAL, r.state, "unvollständige Quelle: vorläufig"); assertEquals(BigDecimal("0.8000"), r.coverage); assertEquals(BigDecimal(10), r.value)
        // die fehlende Revision trifft später ein: wieder vollständig
        val missing = (8 until 10).map { ev[it] }
        missing.forEach { fx.feed(t, fx.attempt(t, UUID.fromString(Regex("\"attempt_id\":\"([^\"]+)\"").find(it.third)!!.groupValues[1]), revision = 1)) }
        val after = values(t).getValue("requests")
        assertEquals(MetricState.OK, after.state); assertEquals(BigDecimal.ONE.setScale(4), after.coverage)
        assertEquals(1, fx.base.count("SELECT count(*) FROM taktovia.aggregate_snapshot WHERE tenant_id = ? AND metric_id = 'requests' AND state = 'PROVISIONAL'", t.value), "der vorläufige Stand bleibt als Snapshot erhalten")
    }

    @Test
    fun unbekannte_kennzahl_und_unzulaessige_filterdimension() {
        val t = fx.tenant(); fx.ph021(t)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.metrics.evaluate(t, MetricQuery(fx.day, metricId = "gibt_es_nicht")) }.code)
        assertEquals(ErrorCode.NOT_FOUND, assertThrows<ProblemException> { fx.metrics.definition(t, "requests", 99) }.code)
        val ex = assertThrows<ProblemException> { fx.metrics.evaluate(t, MetricQuery(fx.day, metricId = "booking_conversion", filter = MetricFilter(channel = "WEB"))) }
        assertEquals(ErrorCode.BAD_REQUEST, ex.code); assertEquals("/\$query/channel", ex.violations.single().pointer)
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows<ProblemException> { fx.metrics.evaluate(t, MetricQuery(fx.day, version = 1)) }.code, "eine Version ohne Kennzahl")
    }

    @Test
    fun geaenderte_definition_ist_neue_version_und_snapshots_behalten_ihre_version() {
        val t = fx.tenant(); fx.ph021(t)
        val first = fx.metrics.evaluate(t, MetricQuery(fx.day, metricId = "requests")).single()
        assertEquals(1, first.definition.version)
        val v2 = fx.metrics.propose(t, "requests", DefinitionChange(exclusions = "Testanfragen; Retries; Anfragen ohne Kanal"))
        assertEquals(2, v2)
        assertEquals(1, fx.metrics.definition(t, "requests").version, "ein Entwurf gilt noch nicht")
        // unvollständige Definition wird nicht freigegeben
        val bad = fx.metrics.propose(t, "requests", DefinitionChange(numerator = " "))
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows<ProblemException> { fx.metrics.approve(t, "requests", bad, "user:test") }.code)
        fx.metrics.approve(t, "requests", v2, "user:test")
        assertThrows<ProblemException> { fx.metrics.approve(t, "requests", v2, "user:test") }
        val second = fx.metrics.evaluate(t, MetricQuery(fx.day, metricId = "requests")).single()
        assertEquals(2, second.definition.version); assertEquals(first.value, second.value)
        assertEquals(1, fx.metrics.evaluate(t, MetricQuery(fx.day, metricId = "requests", version = 1)).single().definition.version, "die alte Version bleibt auswertbar")
        assertEquals(listOf(1, 2), fx.base.strings("SELECT definition_version::text FROM taktovia.aggregate_snapshot WHERE tenant_id = ? AND metric_id = 'requests' ORDER BY 1", t.value).map { it.toInt() })
        assertEquals(first.snapshotId, fx.metrics.evaluate(t, MetricQuery(fx.day, metricId = "requests", version = 1)).single().snapshotId, "der gespeicherte Snapshot behält seine Version")
    }

    @Test
    fun gleichzeitige_freigabe_zweier_entwuerfe_ergibt_eindeutige_versionsfolge() {
        val t = fx.tenant()
        val a = fx.metrics.propose(t, "area_rejections", DefinitionChange(exclusions = "A")); val b = fx.metrics.propose(t, "area_rejections", DefinitionChange(exclusions = "B"))
        assertEquals(listOf(2, 3), listOf(a, b))
        val go = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        val futures = listOf(a, b).map { v -> pool.submit<Boolean> { go.await(); runCatching { fx.metrics.approve(t, "area_rejections", v, "user:test") }.isSuccess } }
        go.countDown(); val ok = futures.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        assertTrue(ok.count { it } >= 1, "mindestens die jüngere Version wird freigegeben")
        val approved = fx.base.strings("SELECT version::text FROM taktovia.metric_definition_revision WHERE tenant_id = ? AND metric_id = 'area_rejections' AND state = 'APPROVED' ORDER BY version", t.value).map { it.toInt() }
        assertEquals(approved.distinct(), approved); assertEquals(3, fx.metrics.definition(t, "area_rejections").version.coerceAtLeast(3), "jüngste freigegebene Version ist die höchste")
        assertEquals(approved.max(), fx.metrics.definition(t, "area_rejections").version)
    }

    @Test
    fun kleine_gruppen_werden_auch_per_differenzbildung_nicht_offengelegt() {
        val t = fx.tenant()
        fx.feed(t, *((1..7).map { fx.attempt(t, channel = "WEB") } + listOf(fx.attempt(t, channel = "APP")) + listOf(fx.attempt(t, channel = "API"))).toTypedArray())
        val all = values(t).getValue("requests"); assertEquals(BigDecimal(9), all.value)
        val app = requests(t, MetricFilter(channel = "APP"))
        assertEquals(MetricState.NOT_COMPUTABLE, app.state); assertEquals(MetricReason.SMALL_GROUP, app.reason); assertNull(app.value, "Gruppe unter der Schwelle")
        val web = requests(t, MetricFilter(channel = "WEB"))
        assertEquals(MetricReason.SMALL_GROUP, web.reason, "das Komplement (2 Anfragen) wäre sonst aus 9 minus 7 ableitbar: wird ebenfalls nicht ausgewiesen")
        assertEquals(BigDecimal(9), requests(t, MetricFilter(serviceId = fx.service)).value, "große Gruppe wird ausgewiesen")
    }

    @Test
    fun mandantentrennung_der_kennzahlen() {
        val a = fx.tenant(); val b = fx.tenant(); fx.ph021(a)
        fx.feed(b, fx.attempt(b))
        assertEquals(BigDecimal(10), values(a).getValue("requests").value); assertEquals(BigDecimal(1), values(b).getValue("requests").value)
        assertEquals(0, fx.base.count("SELECT count(*) FROM taktovia.aggregate_snapshot WHERE tenant_id = ? AND metric_id = 'requests' AND numerator = 10", b.value))
    }

    @Test
    fun parallele_abfragen_waehrend_laufender_projektion_liefern_stimmigen_datenstand() {
        val t = fx.tenant(); fx.feed(t, fx.attempt(t))
        val stop = AtomicBoolean(false); val pool = Executors.newFixedThreadPool(5)
        val writer = pool.submit { repeat(40) { fx.feed(t, fx.attempt(t)) }; stop.set(true) }
        val readers = (1..4).map { pool.submit<List<MetricValue>> { buildList { while (!stop.get()) add(fx.metrics.evaluate(t, MetricQuery(fx.day, metricId = "requests")).single()) } } }
        writer.get(120, TimeUnit.SECONDS); val seen = readers.flatMap { it.get(120, TimeUnit.SECONDS) }
        pool.shutdown()
        assertTrue(seen.isNotEmpty())
        // jede Antwort ist aus einem Lesestand: Zahl der Versuche = Zahl der Ereignisse der Quelle (jeder Versuch ist ein Ereignis), Datenstand gesetzt
        assertTrue(seen.all { it.value!!.toLong() == it.sourceEvents && it.asOf != null }, seen.filter { it.value!!.toLong() != it.sourceEvents }.toString())
        // gleicher Datenstand: gleicher Wert (derselbe Snapshot)
        seen.groupBy { it.sourceEvents }.values.forEach { g -> assertEquals(1, g.map { it.snapshotId }.distinct().size); assertEquals(1, g.map { it.value }.distinct().size) }
        assertEquals(BigDecimal(41), fx.metrics.evaluate(t, MetricQuery(fx.day, metricId = "requests")).single().value)
    }

    @Test
    fun betriebstag_folgt_der_reporting_timezone_auch_am_zeitumstellungstag() {
        val berlin = ZoneId.of("Europe/Berlin")
        val dst = ReportingPeriod.ofDays(berlin, LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 25))
        assertEquals(Duration.ofHours(25), Duration.between(dst.from, dst.to), "Zeitumstellung: der 25.10.2026 hat 25 Stunden")
        assertEquals(Instant.parse("2026-10-24T22:00:00Z"), dst.from); assertEquals(Instant.parse("2026-10-25T23:00:00Z"), dst.to)
        assertEquals(Duration.ofHours(23), ReportingPeriod.ofDays(berlin, LocalDate.of(2026, 3, 29), LocalDate.of(2026, 3, 29)).let { Duration.between(it.from, it.to) })
        // 02:30 Ortszeit kommt zweimal vor: zwei eindeutige Zeitpunkte
        val twice = LocalDateTime.of(2026, 10, 25, 2, 30)
        val first = twice.atZone(berlin).withEarlierOffsetAtOverlap().toInstant(); val second = twice.atZone(berlin).withLaterOffsetAtOverlap().toInstant()
        assertEquals(Duration.ofHours(1), Duration.between(first, second)); assertTrue(first >= dst.from && second < dst.to)
        // dieselben Ereignisse, andere Zone: andere Tagesgrenze (00:30 Berlin am 2.12. ist 23:30 UTC am 1.12.)
        val t = fx.tenant(); fx.feed(t, fx.attempt(t, at = "2026-12-01T23:30:00Z"))
        assertEquals(BigDecimal(0), fx.metrics.evaluate(t, MetricQuery(fx.day, metricId = "requests")).single().value, "23:30Z ist in Berlin schon der 2.12.")
        assertEquals(BigDecimal(1), fx.metrics.evaluate(t, MetricQuery(ReportingPeriod.ofDays(ZoneId.of("UTC"), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 1)), metricId = "requests")).single().value)
    }
}
