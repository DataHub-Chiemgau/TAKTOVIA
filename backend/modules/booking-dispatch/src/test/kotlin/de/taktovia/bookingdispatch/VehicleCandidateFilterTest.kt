package de.taktovia.bookingdispatch

import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.CandidateRequest
import de.taktovia.bookingdispatch.api.CandidateSearch
import de.taktovia.bookingdispatch.api.EquipmentCode
import de.taktovia.bookingdispatch.api.ExclusionReason
import de.taktovia.bookingdispatch.api.FixturePlannedPositions
import de.taktovia.bookingdispatch.api.PlannedPosition
import de.taktovia.bookingdispatch.api.RejectionCategory
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.RouteInfo
import de.taktovia.bookingdispatch.api.RoutingException
import de.taktovia.bookingdispatch.api.RoutingFailure
import de.taktovia.bookingdispatch.api.RoutingPort
import de.taktovia.bookingdispatch.api.MatrixInfo
import de.taktovia.bookingdispatch.api.TimeWindow
import de.taktovia.bookingdispatch.internal.VehicleCandidateFilter
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertThrows

/**
 * WP-M2-15: Fahrzeugkandidatenfilter gegen die Referenzregion (Seed der Fixture, Runtime-Rolle) mit Fixture-Pool, -Freigabe, -Schicht und geplanten Standorten
 * (OP-16/OP-43). Das Routing ist ein Stub mit Fahrzeiten aus `erwartete-fahrzeiten.json` (R1: H1 nach H4 180 s, R4a: H3 nach H4 120 s); der echte Worker wird hier nicht
 * gestartet. Die Abnahme in M2 gilt nur für diese Fixtures; echte Quellen weisen WP-M3-06/-08/-34 und WP-M4-17 nach.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VehicleCandidateFilterTest {
    private lateinit var fx: BookingFixture
    private lateinit var api: BookingdispatchApi
    private val window = TimeWindow(Instant.parse("2026-12-01T06:00:00Z"), Instant.parse("2026-12-01T07:00:00Z"))
    private val latest = Instant.parse("2026-12-01T06:30:00Z")
    private val early = Instant.parse("2026-12-01T05:50:00Z")
    private fun a(key: String) = fixtureId("a", key)
    private fun b(key: String) = fixtureId("b", key)

    /** Fahrzeit-Stub: (von, nach) -> Sekunden; fehlender Eintrag = NO_ROUTE; `broken` = technischer Fehler. */
    private class StubRouting(val times: Map<Pair<String, String>, Int>, val broken: Boolean = false) : RoutingPort {
        override fun route(ctx: RequestContext, originStop: String, destinationStop: String, profile: String): RouteInfo {
            if (broken) throw RoutingException(RoutingFailure.PROVIDER_UNAVAILABLE, "Worker nicht erreichbar")
            val t = times[originStop to destinationStop] ?: throw RoutingException(RoutingFailure.NO_ROUTE, "keine Route")
            return RouteInfo(originStop, destinationStop, t, t * 8, emptyList(), profile, "stub", "stub")
        }
        override fun walk(ctx: RequestContext, originStop: String, destinationStop: String) = route(ctx, originStop, destinationStop, "walk")
        override fun matrix(ctx: RequestContext, originStops: List<String>, destinationStops: List<String>, profile: String): MatrixInfo = error("nicht benötigt")
    }

    private val routes = mapOf(("H1" to "H4") to 180, ("H3" to "H4") to 120, ("H5" to "H4") to 3600)

    private fun filter(positions: Map<UUID, PlannedPosition>, routing: RoutingPort? = StubRouting(routes)) =
        VehicleCandidateFilter(api.resources, routing, FixturePlannedPositions(positions))

    /** Standardpositionen: v-std an H3, v-wc an H1, beide ab 05:50Z frei. */
    private fun std() = mapOf(
        a("veh-v-std") to PlannedPosition("H3", early), a("veh-v-wc") to PlannedPosition("H1", early), a("veh-v-sued") to PlannedPosition("H3", early),
        b("veh-v-wc") to PlannedPosition("H1", early),
    )

    private fun req(service: UUID, demand: ResourceVector, w: TimeWindow = window, pickupLatest: Instant = latest) = CandidateRequest(service, w, demand, "H4", pickupLatest)
    private val twoSeats = ResourceVector(seats = 2)
    private val wheelchair = ResourceVector(seats = 1, wheelchair = 1, equipment = setOf(EquipmentCode.RAMP))

    private fun CandidateSearch.ids() = candidates.map { it.resource.vehicleId }.toSet()
    private fun CandidateSearch.reasonOf(id: UUID) = excluded.single { it.vehicleId == id }.reason

    @BeforeAll fun up() { fx = BookingFixture(); api = BookingdispatchApi.create(fx.ctxTx, Clock.systemUTC()) }
    @AfterAll fun down() = fx.close()

    @Test
    fun geeignetes_fahrzeug_wird_kandidat() {
        val r = filter(std()).find(fx.context(fx.tenantA), req(a("svc-nord"), twoSeats))
        assertEquals(setOf(a("veh-v-std"), a("veh-v-wc")), r.ids())
        assertEquals(null, r.rejection)
        val std = r.candidates.first { it.resource.vehicleId == a("veh-v-std") }
        assertEquals(120, std.travelSeconds); assertEquals(Instant.parse("2026-12-01T05:52:00Z"), std.estimatedArrival)
        assertEquals(listOf(a("veh-v-std"), a("veh-v-wc")).let { ids -> r.candidates.sortedWith(compareBy({ it.estimatedArrival }, { it.resource.vehicleId })).map { it.resource.vehicleId } }, r.candidates.map { it.resource.vehicleId })
    }

    @Test
    fun fahrzeug_ohne_rollstuhlplatz_kein_kandidat() {
        val r = filter(std()).find(fx.context(fx.tenantA), req(a("svc-nord"), wheelchair))
        assertEquals(setOf(a("veh-v-wc")), r.ids())
        assertEquals(ExclusionReason.EQUIPMENT_MISSING, r.reasonOf(a("veh-v-std")))
    }

    @Test
    fun fahrzeug_ausserhalb_dienstzeit_kein_kandidat() {
        val late = TimeWindow(Instant.parse("2026-12-01T13:30:00Z"), Instant.parse("2026-12-01T14:30:00Z"))
        val r = filter(std()).find(fx.context(fx.tenantA), req(a("svc-nord"), twoSeats, late, Instant.parse("2026-12-01T14:00:00Z")))
        assertTrue(r.candidates.isEmpty())
        assertEquals(ExclusionReason.SHIFT_ENDED_IN_WINDOW, r.reasonOf(a("veh-v-std")), "Schicht endet um 14:00 im Fenster (WP-M4-49: Schichtende)")
        assertEquals(ExclusionReason.NO_SHIFT, r.reasonOf(a("veh-v-noshift")))
        // Schicht am Vortag, auch im regulären Fenster
        assertEquals(ExclusionReason.NO_SHIFT, filter(std()).find(fx.context(fx.tenantA), req(a("svc-nord"), twoSeats)).reasonOf(a("veh-v-noshift")))
    }

    @Test
    fun fahrzeug_ohne_gueltige_oder_widerrufene_freigabe_kein_kandidat() {
        val r = filter(std()).find(fx.context(fx.tenantA), req(a("svc-nord"), twoSeats))
        for (v in listOf("v-expired", "v-norelease", "v-revoked", "v-stale")) assertEquals(ExclusionReason.NO_VALID_RELEASE, r.reasonOf(a("veh-$v")), v)
        assertTrue(r.ids().none { it in setOf(a("veh-v-expired"), a("veh-v-norelease"), a("veh-v-revoked"), a("veh-v-stale")) })
    }

    @Test
    fun gesperrtes_fahrzeug_kein_kandidat() {
        val r = filter(std() + (a("veh-v-blocked") to PlannedPosition("H1", early))).find(fx.context(fx.tenantA), req(a("svc-nord"), twoSeats))
        assertEquals(ExclusionReason.VEHICLE_BLOCKED, r.reasonOf(a("veh-v-blocked")))
        assertTrue(a("veh-v-blocked") !in r.ids())
    }

    @Test
    fun abholpunkt_nicht_erreichbar_kein_kandidat() {
        // v-std steht an H5: 3600 s bis H4 sprengen das Fenster; v-wc an H7: keine Route
        val pos = std() + (a("veh-v-std") to PlannedPosition("H5", early)) + (a("veh-v-wc") to PlannedPosition("H7", early))
        val r = filter(pos).find(fx.context(fx.tenantA), req(a("svc-nord"), twoSeats))
        assertTrue(r.candidates.isEmpty())
        assertEquals(ExclusionReason.PICKUP_UNREACHABLE, r.reasonOf(a("veh-v-std")))
        assertEquals(ExclusionReason.PICKUP_UNREACHABLE, r.reasonOf(a("veh-v-wc")))
        assertEquals(RejectionCategory.CAPACITY, r.rejection!!.primary)
        // zu spät frei: Fahrzeug wird erst 06:29 frei, Fahrt 120 s, spätester Abholzeitpunkt 06:30 -> 06:31 zu spät
        val tooLate = filter(std() + (a("veh-v-std") to PlannedPosition("H3", Instant.parse("2026-12-01T06:29:00Z")))).find(fx.context(fx.tenantA), req(a("svc-nord"), twoSeats))
        assertEquals(ExclusionReason.PICKUP_UNREACHABLE, tooLate.reasonOf(a("veh-v-std")))
        assertTrue(a("veh-v-wc") in tooLate.ids())
    }

    @Test
    fun position_unbekannt_oder_routingfehler() {
        val noPos = filter(emptyMap()).find(fx.context(fx.tenantA), req(a("svc-nord"), twoSeats))
        assertTrue(noPos.candidates.isEmpty()); assertEquals(ExclusionReason.POSITION_UNKNOWN, noPos.reasonOf(a("veh-v-std")))
        // technischer Routingfehler wird nicht zu "nicht erreichbar" umgedeutet
        val ex = assertThrows(RoutingException::class.java) { filter(std(), StubRouting(emptyMap(), broken = true)).find(fx.context(fx.tenantA), req(a("svc-nord"), twoSeats)) }
        assertEquals(RoutingFailure.PROVIDER_UNAVAILABLE, ex.failure)
    }

    @Test
    fun fremder_pool_kein_kandidat() {
        val nord = filter(std()).find(fx.context(fx.tenantA), req(a("svc-nord"), twoSeats))
        assertTrue(a("veh-v-sued") !in nord.ids() && nord.excluded.none { it.vehicleId == a("veh-v-sued") })
        val sued = filter(std()).find(fx.context(fx.tenantA), req(a("svc-sued"), twoSeats))
        assertEquals(setOf(a("veh-v-sued")), sued.ids())
        assertTrue(sued.excluded.isEmpty(), "Fahrzeuge anderer Pools erscheinen nicht einmal als Ausschluss")
    }

    @Test
    fun fremder_mandant_nie_kandidat() {
        val ctxB = fx.context(fx.tenantB)
        val b = filter(std()).find(ctxB, req(b("svc-nord"), twoSeats))
        assertEquals(setOf(b("veh-v-wc")), b.ids())
        val aIds = listOf("v-std", "v-wc", "v-blocked", "v-sued").map { a("veh-$it") }.toSet()
        assertTrue((b.ids() + b.excluded.map { it.vehicleId }).none { it in aIds })
        // Mandant A fragt mit dem Dienst von B: RLS, nichts sichtbar, Ablehnung ohne Fahrzeug
        val cross = filter(std()).find(fx.context(fx.tenantA), req(b("svc-nord"), twoSeats))
        assertTrue(cross.candidates.isEmpty() && cross.excluded.isEmpty())
        assertEquals(RejectionCategory.CAPACITY, cross.rejection!!.primary)
        // und umgekehrt: B fragt mit dem Dienst von A
        assertTrue(filter(std()).find(ctxB, req(a("svc-nord"), twoSeats)).let { it.candidates.isEmpty() && it.excluded.isEmpty() })
    }

    @Test
    fun kein_kandidat_alle_ausgelastet_primaergrund_kapazitaet() {
        // E2E-080 (Vorbereitung): einziges Fahrzeug des Dienstes "sued" hat 6 Sitze, Bedarf 7 -> Kapazität
        val r = filter(std()).find(fx.context(fx.tenantA), req(a("svc-sued"), ResourceVector(seats = 7)))
        assertTrue(r.candidates.isEmpty())
        assertEquals(ExclusionReason.CAPACITY_INSUFFICIENT, r.reasonOf(a("veh-v-sued")))
        assertEquals(RejectionCategory.CAPACITY, r.rejection!!.primary); assertEquals("Kapazitätsablehnungen", r.rejection!!.primary.metric)
        assertTrue(r.rejection!!.secondary.isEmpty())
    }

    @Test
    fun kein_kandidat_niemand_einsatzbereit_primaergrund_personalmangel() {
        // E2E-081 (Vorbereitung): Fahrzeug im Pool, frei und passend, aber keine Schicht im Fenster
        val late = TimeWindow(Instant.parse("2026-12-01T13:30:00Z"), Instant.parse("2026-12-01T14:30:00Z"))
        val r = filter(std()).find(fx.context(fx.tenantA), req(a("svc-sued"), twoSeats, late, Instant.parse("2026-12-01T14:00:00Z")))
        assertTrue(r.candidates.isEmpty())
        assertEquals(RejectionCategory.PERSONNEL, r.rejection!!.primary); assertEquals("Personalmangelablehnungen", r.rejection!!.primary.metric)
        // Personalmangel im Pool nord (Schicht vorhanden, aber keine Freigabe), ohne einsatzfähiges Fahrzeug
        val none = filter(std()).find(fx.context(fx.tenantA), req(a("svc-nord"), twoSeats, late, Instant.parse("2026-12-01T14:00:00Z")))
        assertEquals(RejectionCategory.PERSONNEL, none.rejection!!.primary)
    }

    @Test
    fun kein_kandidat_ausstattung_primaergrund_ausstattung() {
        // AT-04: Rollstuhlanfrage an einen Dienst, dessen Fahrzeug keinen Rollstuhlplatz hat
        val r = filter(std()).find(fx.context(fx.tenantA), req(a("svc-sued"), wheelchair))
        assertTrue(r.candidates.isEmpty())
        assertEquals(ExclusionReason.EQUIPMENT_MISSING, r.reasonOf(a("veh-v-sued")))
        assertEquals(RejectionCategory.EQUIPMENT, r.rejection!!.primary); assertEquals("Ausstattungsablehnungen", r.rejection!!.primary.metric)
        // Sekundärgründe: Pool nord mit Rollstuhlbedarf und Fenster nach Schichtende, v-std ohne Rollstuhl, andere ohne Schicht
        val mixed = filter(std()).find(fx.context(fx.tenantA), req(a("svc-nord"), ResourceVector(seats = 1, wheelchair = 1, equipment = setOf(EquipmentCode.LIFT))))
        assertEquals(RejectionCategory.EQUIPMENT, mixed.rejection!!.primary)
        assertEquals(setOf(RejectionCategory.PERSONNEL, RejectionCategory.CAPACITY), mixed.rejection!!.secondary.toSet())
    }
}
