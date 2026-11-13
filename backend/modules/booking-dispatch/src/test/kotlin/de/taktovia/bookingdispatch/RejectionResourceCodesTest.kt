package de.taktovia.bookingdispatch

import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.CandidateGateResult
import de.taktovia.bookingdispatch.api.CandidateSearchRequest
import de.taktovia.bookingdispatch.api.EquipmentCode
import de.taktovia.bookingdispatch.api.FixturePlannedPositions
import de.taktovia.bookingdispatch.api.MatrixInfo
import de.taktovia.bookingdispatch.api.PlannedPosition
import de.taktovia.bookingdispatch.api.RejectionCode
import de.taktovia.bookingdispatch.api.RejectionGroup
import de.taktovia.bookingdispatch.api.RejectionMetric
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.RideTimeWindow
import de.taktovia.bookingdispatch.api.RouteInfo
import de.taktovia.bookingdispatch.api.RoutingException
import de.taktovia.bookingdispatch.api.RoutingFailure
import de.taktovia.bookingdispatch.api.RoutingPort
import de.taktovia.bookingdispatch.api.WindowViolationCode
import de.taktovia.bookingdispatch.internal.VehicleCandidateFilter
import de.taktovia.bookingdispatch.internal.VehicleCandidateGate
import de.taktovia.platform.context.RequestContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * WP-M4-49/-50 (LH-I.6-KPI-003/-004) auf der Erzeugerseite: der Kandidatenfilter der Referenzregion (echte Datenbank, Runtime-Rolle, Schichten und Freigaben der Fixture) liefert über das Tor die fünf
 * ressourcenbezogenen Codes (keine Kapazität, kein Fahrzeug, kein Fahrer, Schichtende, falsche Ausstattung) als unterschiedliche Primärgründe (E2E-080, E2E-081); der Katalog der Datenbank entspricht
 * der Codeliste mit Gruppe, Kennzahl und deutschem Text; jede im Kern erzeugbare Grenzverletzung hat einen Code.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RejectionResourceCodesTest {
    private lateinit var fx: BookingFixture
    private lateinit var api: BookingdispatchApi
    @BeforeAll fun up() { fx = BookingFixture(); api = BookingdispatchApi.create(fx.ctxTx, Clock.systemUTC()) }
    @AfterAll fun down() = fx.close()

    private fun a(key: String) = fixtureId("a", key)
    private fun b(key: String) = fixtureId("b", key)
    private val early = Instant.parse("2026-12-01T05:50:00Z")

    /** Jedes Fahrzeug erreicht jeden Abholpunkt in 120 s; so entscheidet allein die Ausschlussstufe (Ausstattung, Schicht, Kapazität). */
    private val routing = object : RoutingPort {
        override fun route(ctx: RequestContext, originStop: String, destinationStop: String, profile: String) = RouteInfo(originStop, destinationStop, 120, 960, emptyList(), profile, "stub", "stub")
        override fun walk(ctx: RequestContext, originStop: String, destinationStop: String) = route(ctx, originStop, destinationStop, "walk")
        override fun matrix(ctx: RequestContext, originStops: List<String>, destinationStops: List<String>, profile: String): MatrixInfo = throw RoutingException(RoutingFailure.NO_ROUTE, "nicht benötigt")
    }
    private val positions = mapOf(a("veh-v-std") to PlannedPosition("H3", early), a("veh-v-wc") to PlannedPosition("H1", early), a("veh-v-sued") to PlannedPosition("H3", early))
    private val gate = lazy { VehicleCandidateGate(VehicleCandidateFilter(api.resources, routing, FixturePlannedPositions(positions))) }

    private fun window(from: String, to: String) = RideTimeWindow(Instant.parse(from), Instant.parse(to))
    private val day = window("2026-12-01T06:00:00Z", "2026-12-01T06:30:00Z")
    private val arrivalDay = window("2026-12-01T06:20:00Z", "2026-12-01T07:00:00Z")

    private fun causes(service: UUID, demand: ResourceVector, pickup: RideTimeWindow = day, arrival: RideTimeWindow = arrivalDay, tenantB: Boolean = false): List<RejectionCode> {
        val ctx = fx.context(if (tenantB) fx.tenantB else fx.tenantA)
        val r = gate.value.check(ctx, CandidateSearchRequest(service, demand, UUID.randomUUID(), pickup, arrival))
        return (r as CandidateGateResult.NoCandidate).causes
    }

    private fun primary(causes: List<RejectionCode>) = RejectionCode.decide(causes)!!.primary

    @Test
    fun fuenf_ressourcenfaelle_liefern_fuenf_unterschiedliche_primaergruende() {
        val keineKapazitaet = primary(causes(a("svc-sued"), ResourceVector(seats = 7)))                                                    // E2E-080: Fahrzeug da, alle Plätze zu klein
        val keinFahrzeug = primary(causes(b("svc-nord"), ResourceVector(seats = 2)))                                                      // Pool des Dienstes für diesen Mandanten leer
        val keinFahrer = primary(causes(a("svc-sued"), ResourceVector(seats = 2), window("2026-12-02T03:00:00Z", "2026-12-02T03:30:00Z"), window("2026-12-02T03:20:00Z", "2026-12-02T04:00:00Z")))
        val schichtende = primary(causes(a("svc-sued"), ResourceVector(seats = 2), window("2026-12-01T13:30:00Z", "2026-12-01T13:50:00Z"), window("2026-12-01T13:55:00Z", "2026-12-01T14:30:00Z")))
        val ausstattung = primary(causes(a("svc-sued"), ResourceVector(seats = 1, wheelchair = 1, equipment = setOf(EquipmentCode.RAMP))))
        assertEquals(listOf(RejectionCode.NO_CAPACITY, RejectionCode.NO_VEHICLE, RejectionCode.NO_STAFF, RejectionCode.SHIFT_ENDED, RejectionCode.EQUIPMENT_MISSING), listOf(keineKapazitaet, keinFahrzeug, keinFahrer, schichtende, ausstattung))
        assertEquals(5, setOf(keineKapazitaet, keinFahrzeug, keinFahrer, schichtende, ausstattung).size)
        assertEquals(listOf(RejectionMetric.CAPACITY, RejectionMetric.CAPACITY, RejectionMetric.PERSONNEL, RejectionMetric.PERSONNEL, RejectionMetric.EQUIPMENT),
            listOf(keineKapazitaet, keinFahrzeug, keinFahrer, schichtende, ausstattung).map { it.metric })
    }

    @Test
    fun freies_fahrzeug_ohne_einsatzbereiten_fahrer_ist_personalmangel_ohne_zusage() {
        // E2E-081: das Fahrzeug des Dienstes ist frei und passend, aber an diesem Tag ist keine Schicht veröffentlicht: Primärgrund Personalmangel, kein Kandidat (keine Kapazitätszusage)
        val c = causes(a("svc-sued"), ResourceVector(seats = 2), window("2026-12-03T05:00:00Z", "2026-12-03T05:30:00Z"), window("2026-12-03T05:20:00Z", "2026-12-03T06:00:00Z"))
        assertEquals(RejectionCode.NO_STAFF, primary(c)); assertEquals(RejectionMetric.PERSONNEL, primary(c).metric)
        assertTrue(RejectionCode.NO_CAPACITY !in c, "es entsteht keine Kapazitätsaussage")
    }

    @Test
    fun zwei_zutreffende_gruende_ergeben_primaer_und_sekundaergrund_in_katalogreihenfolge() {
        // Pool nord: Rollstuhlbedarf mit Hebebühne, v-std ohne Rollstuhl, andere ohne Freigabe oder Schicht, eines gesperrt
        val c = causes(a("svc-nord"), ResourceVector(seats = 1, wheelchair = 1, equipment = setOf(EquipmentCode.LIFT)))
        val d = RejectionCode.decide(c)!!
        assertEquals(RejectionCode.EQUIPMENT_MISSING, d.primary, "falsche Ausstattung vor kein Fahrer vor keine Kapazität")
        assertEquals(setOf(RejectionCode.NO_STAFF, RejectionCode.NO_CAPACITY), d.secondary.toSet(), c.toString())
        assertEquals(d.secondary.sortedBy { it.priority }, d.secondary, "Sekundärgründe nach Priorität"); assertTrue(d.primary !in d.secondary)
    }

    @Test
    fun primaergrund_ist_unabhaengig_von_der_reihenfolge_der_feststellung() {
        val all = listOf(RejectionCode.NO_CAPACITY, RejectionCode.SHIFT_ENDED, RejectionCode.NO_STAFF, RejectionCode.EQUIPMENT_MISSING, RejectionCode.NO_VEHICLE)
        val expected = RejectionCode.decide(all)!!
        assertEquals(RejectionCode.NO_VEHICLE, expected.primary)
        assertEquals(listOf(RejectionCode.EQUIPMENT_MISSING, RejectionCode.NO_STAFF, RejectionCode.SHIFT_ENDED, RejectionCode.NO_CAPACITY), expected.secondary)
        repeat(30) { i -> assertEquals(expected, RejectionCode.decide(all.shuffled(java.util.Random(i.toLong()))), "Seed $i") }
        assertNull(RejectionCode.decide(listOf(RejectionCode.TECHNICAL)), "technische Ursache ist nie ein Grund")
    }

    @Test
    fun codeliste_entspricht_dem_katalog_der_datenbank_mit_gruppe_kennzahl_und_text() {
        data class Row(val priority: Int, val counts: Boolean, val metric: String?, val label: String, val action: String, val group: String, val en: String?)
        val rows = fx.tx.run(fx.tenantA) { j -> j.query("SELECT code, priority, counts_as_rejection, metric, label, next_action, reason_group, texts->>'en' FROM taktovia.rejection_reason") { rs, _ ->
            rs.getString(1) to Row(rs.getInt(2), rs.getBoolean(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)) } }.toMap()
        assertEquals(RejectionCode.entries.map { it.name }.toSet(), rows.keys, "jeder vom Kern erzeugbare Grund hat einen Katalogeintrag und jeder Katalogeintrag einen Code")
        for (c in RejectionCode.entries) {
            val r = rows.getValue(c.name)
            assertEquals(c.priority, r.priority, c.name); assertEquals(c.counts, r.counts, c.name); assertEquals(c.metric?.name, r.metric, c.name)
            assertEquals(c.text, r.label, "${c.name}: deutscher Text"); assertEquals(c.nextAction, r.action, c.name); assertEquals(c.group.name, r.group, c.name)
            assertTrue(!r.en.isNullOrBlank(), "${c.name}: englischer Text")
        }
        assertEquals(RejectionGroup.entries.toSet(), RejectionCode.entries.map { it.group }.toSet(), "jede Gruppe ist belegt")
    }

    @Test
    fun grenzverletzungen_des_kerns_haben_einen_ablehnungscode() {
        assertEquals(RejectionCode.MAX_WAIT_EXCEEDED, RejectionCode.of(WindowViolationCode.MAX_WAIT_EXCEEDED)); assertEquals(RejectionCode.MAX_DETOUR_EXCEEDED, RejectionCode.of(WindowViolationCode.MAX_DETOUR_EXCEEDED))
        assertEquals(RejectionGroup.TIME_DEADLINE, RejectionCode.MAX_WAIT_EXCEEDED.group); assertEquals(RejectionMetric.TIME_WINDOW, RejectionCode.MAX_WAIT_EXCEEDED.metric)
        // Verletzungen bestehender Zusagen sind keine Gründe für eine neue Anfrage
        listOf(WindowViolationCode.NOT_IN_PLAN, WindowViolationCode.PICKUP_OUTSIDE_WINDOW, WindowViolationCode.ARRIVAL_OUTSIDE_WINDOW, WindowViolationCode.ARRIVAL_AFTER_GRACE).forEach { assertNull(RejectionCode.of(it), it.name) }
        // die sieben Gründe des Vertrags KPI-004 sind im Code vorhanden (Arbeitsname -> Code)
        val seven = listOf("OUTSIDE_AREA", "OUTSIDE_HOURS", "LEAD_TIME", "MAX_WAIT_EXCEEDED", "MAX_DETOUR_EXCEEDED", "PARTNER_UNAVAILABLE", "TECHNICAL")
        assertTrue(seven.all { RejectionCode.of(it) != null }); assertEquals(RejectionGroup.PARTNER, RejectionCode.PARTNER_UNAVAILABLE.group)
        assertEquals(false, RejectionCode.TECHNICAL.counts, "technischer Fehler ist keine fachliche Ablehnung")
    }
}
