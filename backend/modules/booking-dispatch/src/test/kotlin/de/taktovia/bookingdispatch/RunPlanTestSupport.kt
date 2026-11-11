package de.taktovia.bookingdispatch

import de.taktovia.bookingdispatch.api.PlanStop
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.StopMovement
import de.taktovia.platform.tenant.TenantId
import java.time.Instant
import java.util.UUID

/** Testhilfen für den Fahrtenplan (WP-M2-16): Plan-Bausteine und Schicht ohne Umlauf; eigene Klasse, ohne Eingriff in andere Fixtures. */
object RunPlanTestSupport {
    val T0: Instant = Instant.parse("2026-12-01T06:00:00Z")

    /** Haltepunktrevisionen der Referenzregion (Seed, Mandant A): `stoprev-H1` … `stoprev-H6`. */
    fun stopRev(i: Int, tenantLabel: String = "a"): UUID = fixtureId(tenantLabel, "stoprev-H${(i % 6) + 1}")

    fun vec(seats: Int, wheelchair: Int = 0, vararg equipment: de.taktovia.bookingdispatch.api.EquipmentCode) = ResourceVector(seats, wheelchair, equipment.toSet())

    fun mv(booking: UUID, v: ResourceVector) = StopMovement(booking, v)

    /** Stopp `seq`; beim letzten Stopp ohne Abschnittsdaten. */
    fun stop(seq: Int, last: Boolean = false, board: List<StopMovement> = emptyList(), alight: List<StopMovement> = emptyList()) =
        PlanStop(seq, stopRev(seq), T0.plusSeconds(300L * seq), board, alight, if (last) null else 240, if (last) null else 1800)

    /** Plan aus Fahrten `(Buchung, Vektor, Einstiegsindex, Ausstiegsindex)` über [n] Stopps. */
    fun planOf(n: Int, rides: List<Triple<UUID, ResourceVector, Pair<Int, Int>>>): List<PlanStop> = (0 until n).map { i ->
        stop(i, last = i == n - 1,
            board = rides.filter { it.third.first == i }.map { mv(it.first, it.second) },
            alight = rides.filter { it.third.second == i }.map { mv(it.first, it.second) })
    }

    /** Fahrzeug und Schicht ohne Umlauf (Migrator-Rolle, nur Testaufbau); liefert die Schicht-ID. */
    fun shiftWithoutRun(fx: BookingFixture, tenant: TenantId = fx.tenantA): UUID {
        val vehicle = UUID.randomUUID(); val shift = UUID.randomUUID()
        fx.migratorExec(tenant, "INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,active_block,safety_revision) VALUES (?,?,'fed-org-m2-a',CAST(? AS jsonb),false,1)",
            tenant.value, vehicle, """{"schema_version":"1.0","seats":4,"wheelchair":1,"equipment":["RAMP"]}""")
        fx.migratorExec(tenant, "INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state) VALUES (?,?,?,?,'2026-12-01 04:00:00+00','2026-12-01 14:00:00+00','Europe/Berlin',1,'PUBLISHED')",
            tenant.value, shift, UUID.randomUUID(), vehicle)
        return shift
    }
}
