package de.taktovia.booking

import de.taktovia.bookingdispatch.BookingFixture
import de.taktovia.bookingdispatch.api.AssistanceCode
import de.taktovia.bookingdispatch.api.BookingDemand
import de.taktovia.bookingdispatch.api.BookingdispatchApi
import de.taktovia.bookingdispatch.api.Dimension
import de.taktovia.bookingdispatch.api.EquipmentCode
import de.taktovia.bookingdispatch.api.HoldOutcome
import de.taktovia.bookingdispatch.api.PlaceHold
import de.taktovia.bookingdispatch.api.PromiseCore
import de.taktovia.bookingdispatch.api.RejectionReason
import de.taktovia.bookingdispatch.api.ReserveRule
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.TimeWindow
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.problem.ViolationCode
import de.taktovia.platform.promise.PromiseSnapshotSchema
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tools.jackson.databind.json.JsonMapper
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/**
 * WP-M2-14 / LH-6.4-BKG-004: Ressourcenvektor und Mehrpersonenbuchung gegen die echte Datenbank unter der Runtime-Rolle (Hold und Bestätigung als Kernel von WP-M2-22/-23).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResourceVectorTest {
    private lateinit var fx: BookingFixture
    private lateinit var api: BookingdispatchApi
    private val window = TimeWindow(Instant.parse("2026-12-01T06:00:00Z"), Instant.parse("2026-12-01T07:00:00Z"))
    private val json = JsonMapper.builder().build()
    private val promise = PromiseCore("2026-12-01T07:00:00+01:00", "2026-12-01T07:15:00+01:00", "2026-12-01T07:40:00+01:00", "2026-12-01T07:55:00+01:00", 950, "EUR", "std-24h", "standard")

    @BeforeAll fun up() { fx = BookingFixture(); api = BookingdispatchApi.create(fx.ctxTx) }
    @AfterAll fun down() = fx.close()

    private fun cap(seats: Int, wheelchair: Int, vararg eq: String) = """{"schema_version":"1.0","seats":$seats,"wheelchair":$wheelchair,"equipment":[${eq.joinToString(",") { "\"$it\"" }}]}"""
    private fun hold(run: BookingFixture.RunRef, demand: ResourceVector, from: Int = 0, to: Int = 4, reserve: ReserveRule = ReserveRule.NONE) = api.holds.place(
        fx.context(fx.tenantA), PlaceHold(run.runId, run.revision, run.serviceId, window, from, to, demand, reserve, "ref-" + UUID.randomUUID()),
    )
    private fun scalar(sql: String, vararg a: Any?): String? = fx.admin().use { ds -> ds.connection.use { c -> c.prepareStatement(sql).use { ps -> a.forEachIndexed { i, v -> ps.setObject(i + 1, v) }; ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null } } } }

    @Test
    fun sitz_und_rollstuhl_getrennt_gespeichert() {
        val run = fx.createRun(cap(4, 1, "RAMP"))
        val demand = BookingDemand.of(3, 1, emptySet(), listOf("BOARDING_HELP"))   // zwei Sitzplätze und ein Rollstuhlplatz
        assertEquals(ResourceVector(2, 1), demand.vector)
        val placed = hold(run, demand.vector) as HoldOutcome.Placed
        val booking = api.holds.confirm(fx.context(fx.tenantA), placed.hold.id, run.serviceId, promise, demand)
        // beide Kapazitätsarten getrennt im Hold-Vektor ...
        assertEquals("2", scalar("SELECT capacity_vector->>'seats' FROM taktovia.capacity_hold WHERE id = ?", placed.hold.id))
        assertEquals("1", scalar("SELECT capacity_vector->>'wheelchair' FROM taktovia.capacity_hold WHERE id = ?", placed.hold.id))
        // ... und als Personen/Plätze in passenger_trip
        assertEquals(listOf(1 to "SEAT", 2 to "SEAT", 3 to "WHEELCHAIR"), booking.passengerTrips)
        assertEquals("3", scalar("SELECT count(*) FROM taktovia.passenger_trip WHERE booking_id = ?", booking.id))
        assertEquals("{BOARDING_HELP}", scalar("SELECT assistance_codes::text FROM taktovia.passenger_trip WHERE booking_id = ? AND seq = 3", booking.id))
        // Wiederholte Bestätigung findet dieselbe Buchung
        assertEquals(booking.id, api.holds.confirm(fx.context(fx.tenantA), placed.hold.id, run.serviceId, promise, demand).id)
        assertEquals("1", scalar("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?", placed.hold.id))
    }

    @Test
    fun belegter_rollstuhlplatz_nicht_durch_sitze_ersetzt() {
        val run = fx.createRun(cap(4, 1, "RAMP"))
        assertTrue(hold(run, ResourceVector(0, 1), 0, 5) is HoldOutcome.Placed)
        // Rollstuhlplatz auf dem Abschnitt belegt: weitere Rollstuhlbuchung wird nicht bestätigt, obwohl vier Sitze frei sind
        val second = hold(run, ResourceVector(0, 1), 3, 6)
        assertEquals(HoldOutcome.Rejected(RejectionReason.NO_CAPACITY_WHEELCHAIR, Dimension.WHEELCHAIR), second)
        // ein Sitz deckt keinen Rollstuhlplatz: ein Sitz-Bedarf bleibt zulässig, nimmt dem Rollstuhl aber nichts
        assertTrue(hold(run, ResourceVector(2, 0), 3, 6) is HoldOutcome.Placed)
        // auf einem nicht überlappenden Abschnitt ist der Rollstuhlplatz frei
        assertTrue(hold(run, ResourceVector(0, 1), 5, 8) is HoldOutcome.Placed)
        // Vektor-Logik: kein Ersatz einer Dimension durch eine andere
        assertFalse(ResourceVector(4, 0).covers(ResourceVector(0, 1)))
        assertEquals(setOf(Dimension.WHEELCHAIR), ResourceVector(4, 0).missing(ResourceVector(1, 1)))
        assertEquals(ResourceVector(3, 1, setOf(EquipmentCode.RAMP)), ResourceVector(1, 0, setOf(EquipmentCode.RAMP)) + ResourceVector(2, 1))
    }

    @Test
    fun fehlende_ausstattung_traegt_den_grund_der_kategorie_ausstattung() {
        val noWheelchair = fx.createRun(cap(4, 0))
        val r = hold(noWheelchair, ResourceVector(0, 1))
        assertEquals(HoldOutcome.Rejected(RejectionReason.EQUIPMENT_MISSING, Dimension.WHEELCHAIR), r)
        assertEquals("EQUIPMENT_MISSING", (r as HoldOutcome.Rejected).reason.primaryReason)   // zählt als Ausstattungsablehnung
        val noChildSeat = fx.createRun(cap(4, 1, "RAMP"))
        assertEquals(HoldOutcome.Rejected(RejectionReason.EQUIPMENT_MISSING, Dimension.EQUIPMENT), hold(noChildSeat, ResourceVector(1, 0, setOf(EquipmentCode.CHILD_SEAT))))
        assertTrue(hold(fx.createRun(cap(4, 1, "RAMP", "CHILD_SEAT")), ResourceVector(1, 0, setOf(EquipmentCode.CHILD_SEAT))) is HoldOutcome.Placed)
        // gesperrtes Fahrzeug: Einsatzprüfung unter dem Lock scheitert, nichts wird gehalten
        assertEquals(RejectionReason.RESOURCE_UNUSABLE, (hold(fx.createRun(cap(4, 1), blocked = true), ResourceVector(1, 0)) as HoldOutcome.Rejected).reason)
    }

    @Test
    fun unbekannte_dimension_abgewiesen() {
        val e = runCatching { ResourceVector.parse("""{"schema_version":"1.0","seats":1,"rollstuhl":1}""") }.exceptionOrNull() as ProblemException
        assertEquals(listOf("/capacity_vector/rollstuhl" to ViolationCode.UNKNOWN_FIELD), e.violations.map { it.pointer to it.code })
        for (bad in listOf("""{"seats":-1}""", """{"seats":"1"}""", """{"wheelchair":21}""", """{"equipment":["TELEPORTER"]}""", """{"equipment":["RAMP","RAMP"]}""", """{"schema_version":"2.0"}""", "[]", "nicht json")) {
            assertTrue(runCatching { ResourceVector.parse(bad) }.exceptionOrNull() is ProblemException, bad)
        }
        // auch die Datenbank weist einen Hold mit unbekannter Dimension ab (CHECK capacity_hold_vector_shape)
        val run = fx.createRun(cap(4, 1))
        val ex = runCatching {
            fx.migratorExec(fx.tenantA, "INSERT INTO taktovia.capacity_hold(tenant_id,id,run_id,run_revision,federation_ref,capacity_vector,interval_from,interval_to,expires_at,state) VALUES (?,?,?,1,?,CAST(? AS jsonb),0,1,now(),'HELD')",
                fx.tenantA.value, UUID.randomUUID(), run.runId, "ref-" + UUID.randomUUID(), """{"seats":1,"rollstuhl":1}""")
        }.exceptionOrNull()
        assertTrue(ex is SQLException && ex.sqlState == "23514", ex.toString())
        // die Zusage-Prüfung des Plattformbausteins kennt die Dimension ebenfalls nicht
        val snap = json.readTree("""{"pickup_earliest":"2026-12-01T07:00:00+01:00","pickup_latest":"2026-12-01T07:15:00+01:00","arrival_earliest":"2026-12-01T07:40:00+01:00","arrival_latest":"2026-12-01T07:55:00+01:00","gross_minor":1,"currency":"EUR","cancellation_policy_ref":"x","vehicle_class":"s","resource_vector":{"seats":1,"rollstuhl":1}}""")
        assertTrue(PromiseSnapshotSchema.validate("1.0", snap).any { it.pointer == "/content/resource_vector/rollstuhl" && it.code == ViolationCode.UNKNOWN_FIELD })
    }

    @Test
    fun unterstuetzungsbedarf_mit_diagnose_abgewiesen() {
        val e = runCatching { BookingDemand.of(2, 0, emptySet(), listOf("BOARDING_HELP", "DIABETES")) }.exceptionOrNull() as ProblemException
        assertEquals(listOf("/assistance_codes/1" to ViolationCode.NOT_IN_ENUM), e.violations.map { it.pointer to it.code })
        assertFalse(e.toString().contains("DIABETES") || (e.detail ?: "").contains("DIABETES"), "Fehlertext wiederholt die Eingabe nicht")
        assertTrue(runCatching { BookingDemand.of(2, 0, emptySet(), listOf("BOARDING_HELP", "BOARDING_HELP")) }.exceptionOrNull() is ProblemException)
        assertEquals(AssistanceCode.entries.map { it.name }.toSet(), setOf("BOARDING_HELP", "AUDIO_GUIDANCE", "LOW_FLOOR", "WHEELCHAIR_SPACE", "ASSISTANCE_ANIMAL", "ACCOMPANYING_PERSON"))
        // ASSISTANCE_ANIMAL verlangt zusätzlich den Platz für den Assistenzhund
        assertEquals(setOf(EquipmentCode.ASSISTANCE_DOG_SPACE), BookingDemand.of(1, 0, emptySet(), listOf("ASSISTANCE_ANIMAL")).vector.equipment)
        // die Datenbank erlaubt in passenger_trip nur Katalogcodes
        val run = fx.createRun(cap(4, 1)); val placed = hold(run, ResourceVector(1, 0)) as HoldOutcome.Placed
        val booking = api.holds.confirm(fx.context(fx.tenantA), placed.hold.id, run.serviceId, promise, BookingDemand.of(1, 0, emptySet(), emptyList()))
        val ex = runCatching { fx.migratorExec(fx.tenantA, "INSERT INTO taktovia.passenger_trip(tenant_id,id,booking_id,seq,place_kind,assistance_codes) VALUES (?,?,?,9,'SEAT',CAST(? AS text[]))", fx.tenantA.value, UUID.randomUUID(), booking.id, "{DIABETES}") }.exceptionOrNull()
        assertTrue(ex is SQLException && ex.sqlState == "23514", ex.toString())
    }

    @Test
    fun personenzahl_grenzen() {
        for (bad in listOf(0 to 0, -1 to 0, 9 to 0, 2 to 3, 3 to -1, 1 to 2)) {
            val e = runCatching { BookingDemand.of(bad.first, bad.second, emptySet(), emptyList()) }.exceptionOrNull()
            assertTrue(e is ProblemException, "Personen ${bad.first}, Rollstuhl ${bad.second}")
        }
        assertEquals(ResourceVector(6, 2), BookingDemand.of(8, 2, emptySet(), emptyList()).vector)   // Höchstwerte (Testwerte, OP-15)
    }

    @Test
    fun min_capacity_reserve_je_dimension() {
        // Sitzreserve 1: von vier Sitzen sind drei buchbar, der vierte ist geschützt; der Rollstuhlplatz bleibt unberührt
        val run = fx.createRun(cap(4, 1, "RAMP"))
        val seatReserve = ReserveRule(seats = 1)
        assertEquals(HoldOutcome.Rejected(RejectionReason.NO_CAPACITY_SEATS, Dimension.SEATS), hold(run, ResourceVector(4, 0), reserve = seatReserve))
        assertTrue(hold(run, ResourceVector(3, 0), reserve = seatReserve) is HoldOutcome.Placed)
        assertTrue(hold(run, ResourceVector(0, 1), reserve = seatReserve) is HoldOutcome.Placed, "Sitzreserve schützt keinen Rollstuhlplatz")
        // Reserve für den Rollstuhlplatz: der einzige Platz ist geschützt, Sitze bleiben buchbar
        val run2 = fx.createRun(cap(4, 1, "RAMP"))
        assertEquals(HoldOutcome.Rejected(RejectionReason.NO_CAPACITY_WHEELCHAIR, Dimension.WHEELCHAIR), hold(run2, ResourceVector(0, 1), reserve = ReserveRule(wheelchair = 1)))
        assertTrue(hold(run2, ResourceVector(4, 0), reserve = ReserveRule(wheelchair = 1)) is HoldOutcome.Placed)
        // Reserve größer als die tatsächliche Kapazität der Dimension wird abgewiesen (je Dimension geprüft)
        val e = runCatching { hold(fx.createRun(cap(4, 1)), ResourceVector(1, 0), reserve = ReserveRule(seats = 5)) }.exceptionOrNull() as ProblemException
        assertEquals("/min_capacity_reserve/seats", e.violations.single().pointer)
        val e2 = runCatching { hold(fx.createRun(cap(4, 0)), ResourceVector(1, 0), reserve = ReserveRule(wheelchair = 1)) }.exceptionOrNull() as ProblemException
        assertEquals("/min_capacity_reserve/wheelchair", e2.violations.single().pointer)
    }

    @Test
    fun vektor_im_promise_snapshot_unveraenderlich() {
        val run = fx.createRun(cap(4, 1, "RAMP"))
        val demand = BookingDemand.of(2, 1, emptySet(), emptyList())
        val placed = hold(run, demand.vector) as HoldOutcome.Placed
        val booking = api.holds.confirm(fx.context(fx.tenantA), placed.hold.id, run.serviceId, promise, demand)
        val snap = json.readTree(booking.promiseSnapshot)
        assertEquals(1, snap.get("resource_vector").get("seats").asInt()); assertEquals(1, snap.get("resource_vector").get("wheelchair").asInt())
        assertEquals("1.0", snap.get("resource_vector").get("schema_version").asString())
        assertEquals("1", scalar("SELECT promise_snapshot->'resource_vector'->>'wheelchair' FROM taktovia.booking WHERE id = ?", booking.id))
        // Überschreiben des Snapshots scheitert (Trigger), auch für die Runtime-Rolle; andere Spalten bleiben änderbar
        fx.ds.connection.use { c ->
            c.autoCommit = false
            c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)").use { it.setString(1, fx.tenantA.toString()); it.execute() }
            val ex = runCatching { c.prepareStatement("UPDATE taktovia.booking SET promise_snapshot = CAST(? AS jsonb) WHERE id = ?").use { it.setString(1, """{"resource_vector":{"seats":9}}"""); it.setObject(2, booking.id); it.executeUpdate() } }.exceptionOrNull()
            assertTrue(ex is SQLException && ex.sqlState == "23514", ex.toString())
            c.rollback()
            c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)").use { it.setString(1, fx.tenantA.toString()); it.execute() }
            assertEquals(1, c.prepareStatement("UPDATE taktovia.booking SET revision = revision WHERE id = ?").use { it.setObject(1, booking.id); it.executeUpdate() })
            c.commit()
        }
        assertEquals("1", scalar("SELECT promise_snapshot->'resource_vector'->>'wheelchair' FROM taktovia.booking WHERE id = ?", booking.id))
        assertNotNull(booking.promiseSnapshot)
    }

    @Test
    fun abgelaufener_hold_wird_nicht_bestaetigt() {
        val run = fx.createRun(cap(4, 1))
        val placed = api.holds.place(fx.context(fx.tenantA), PlaceHold(run.runId, 1, run.serviceId, window, 0, 2, ResourceVector(1, 0), reference = "ref-" + UUID.randomUUID(), ttl = java.time.Duration.ofMillis(50))) as HoldOutcome.Placed
        Thread.sleep(150)
        val e = runCatching { api.holds.confirm(fx.context(fx.tenantA), placed.hold.id, run.serviceId, promise, BookingDemand.of(1, 0, emptySet(), emptyList())) }.exceptionOrNull() as ProblemException
        assertEquals(de.taktovia.platform.problem.ErrorCode.HOLD_EXPIRED, e.code)
        assertEquals("0", scalar("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?", placed.hold.id))
        // der abgelaufene Hold belegt nichts mehr: der Platz ist wieder buchbar
        assertTrue(hold(run, ResourceVector(4, 0), 0, 2) is HoldOutcome.Placed)
    }
}
