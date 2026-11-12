package de.taktovia.platform.readports.stub

import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.readports.BookingPromiseView
import de.taktovia.platform.readports.BookingReadPort
import de.taktovia.platform.readports.DriverEventReadPort
import de.taktovia.platform.readports.DriverEventView
import de.taktovia.platform.readports.DriverReadPort
import de.taktovia.platform.readports.DriverView
import de.taktovia.platform.readports.FleetReadPort
import de.taktovia.platform.readports.PositionReadPort
import de.taktovia.platform.readports.PositionView
import de.taktovia.platform.readports.QualificationView
import de.taktovia.platform.readports.TripPlanReadPort
import de.taktovia.platform.readports.TripPlanView
import de.taktovia.platform.readports.TripStopView
import de.taktovia.platform.readports.VehicleRoleView
import de.taktovia.platform.readports.VehicleView
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Dieselbe ID wie `md5('m4ref/<mandant>/<schlüssel>')::uuid` im Seed der Referenzdaten. */
fun m4FixtureId(tenantLabel: String, key: String): UUID {
    val hex = MessageDigest.getInstance("MD5").digest("m4ref/$tenantLabel/$key".toByteArray()).joinToString("") { "%02x".format(it) }
    return UUID.fromString("${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}")
}

/**
 * **Stub** aller Leseports aus dem Katalog der Referenzdaten (`tests/acceptance/fixtures/m4_referenz/katalog.json`, WP-M4-02), solange M2/M3 die Quellen nicht liefern. Er hält die
 * Daten im Speicher, liefert je Mandant genau dessen Zeilen und besteht denselben Vertragstest wie die echten Implementierungen. Nur für Tests und die Entwicklung von M4: der
 * Betrieb verdrahtet die Implementierungen der Module (Bean-Konfiguration der Anwendung).
 */
class FixtureReadPorts private constructor(private val tenants: Map<UUID, Data>) : TripPlanReadPort, BookingReadPort, DriverEventReadPort, DriverReadPort, FleetReadPort, PositionReadPort {

    private class Data(
        val drivers: List<DriverView>, val vehicles: List<VehicleView>, val roles: List<VehicleRoleView>, val plans: List<TripPlanView>,
        val promises: List<BookingPromiseView>, val events: List<DriverEventView>, val positions: List<PositionView>,
    )

    private fun data(ctx: RequestContext): Data? = tenants[ctx.tenant.value]

    override fun currentPlan(ctx: RequestContext, runId: UUID) = data(ctx)?.plans?.firstOrNull { it.runId == runId }
    override fun currentPlanOfShift(ctx: RequestContext, shiftId: UUID) = data(ctx)?.plans?.firstOrNull { it.shiftId == shiftId }
    override fun promise(ctx: RequestContext, bookingId: UUID) = data(ctx)?.promises?.firstOrNull { it.bookingId == bookingId }
    override fun eventsOfShift(ctx: RequestContext, shiftId: UUID) = data(ctx)?.events?.filter { it.shiftId == shiftId }?.sortedWith(compareBy({ it.occurredAt }, { it.eventId })) ?: emptyList()
    override fun driver(ctx: RequestContext, driverId: UUID) = data(ctx)?.drivers?.firstOrNull { it.id == driverId }
    override fun drivers(ctx: RequestContext) = data(ctx)?.drivers?.sortedBy { it.id.toString() } ?: emptyList()
    override fun vehicle(ctx: RequestContext, vehicleId: UUID) = data(ctx)?.vehicles?.firstOrNull { it.id == vehicleId }
    override fun vehicles(ctx: RequestContext) = data(ctx)?.vehicles?.sortedBy { it.id.toString() } ?: emptyList()
    override fun roles(ctx: RequestContext, vehicleId: UUID) = data(ctx)?.roles?.filter { it.vehicleId == vehicleId }?.sortedWith(compareBy({ it.role }, { it.validFrom })) ?: emptyList()
    override fun latest(ctx: RequestContext, vehicleId: UUID) = data(ctx)?.positions?.filter { it.vehicleId == vehicleId }?.maxByOrNull { it.measuredAt }

    companion object {
        private val MAPPER = JsonMapper.builder().build()

        /** Liest den Katalog (`katalog.json`) und baut den Stub. */
        fun fromCatalog(json: String): FixtureReadPorts {
            val root = MAPPER.readTree(json)
            val per = root.get("je_mandant"); val a = root.get("mandant_a")
            val out = mutableMapOf<UUID, Data>()
            for ((label, idText) in root.get("mandanten").properties()) {
                fun id(key: String) = m4FixtureId(label, key)
                val drivers = per.get("fahrer").values().map { d ->
                    DriverView(id(d.get("key").asString()), "ACTIVE", d.get("qualifikationen").values().map { q ->
                        QualificationView(q.get("code").asString(), q.get("revision").asLong(), Instant.parse(q.get("valid_from").asString()), q.get("valid_to").takeIf { !it.isNull }?.let { Instant.parse(it.asString()) })
                    })
                }
                val vehicles = per.get("fahrzeuge").values().map { v ->
                    VehicleView(id(v.get("key").asString()), "M4-${label.uppercase()}-${v.get("key").asString().removePrefix("veh-")}", v.get("seats").asInt(), v.get("wheelchair").asInt(),
                        v.get("equipment").values().map { it.asString() }.toSet(), true)
                }
                val roles = per.get("fahrzeuge").values().flatMap { v ->
                    v.get("rollen").values().map { r -> VehicleRoleView(id(v.get("key").asString()), id(r.get("org").asString()), r.get("role").asString(), Instant.parse("2026-01-01T00:00:00Z"), null) }
                }
                val isA = label == "a"
                val plans = if (isA) listOf(plan(a.get("fahrtenplan"), ::id)) else emptyList()
                val promises = if (isA) listOf(promise(a.get("buchung"), ::id)) else emptyList()
                val events = if (isA) a.get("fahrerereignisse").values().map { e ->
                    DriverEventView(id(e.get("event").asString()), id(e.get("shift").asString()), e.get("booking").takeIf { !it.isNull }?.let { id(it.asString()) }, e.get("type").asString(), Instant.parse(e.get("occurred_at").asString()))
                } else emptyList()
                val positions = if (isA) a.get("positionen").values().map { p ->
                    PositionView(id(p.get("vehicle").asString()), id("shift-day"), p.get("lon").asDouble(), p.get("lat").asDouble(), Instant.parse(p.get("measured_at").asString()))
                } else emptyList()
                out[UUID.fromString(idText.asString())] = Data(drivers, vehicles, roles, plans, promises, events, positions)
            }
            return FixtureReadPorts(out)
        }

        private fun plan(p: JsonNode, id: (String) -> UUID) = TripPlanView(id(p.get("run").asString()), id(p.get("vehicle").asString()), id(p.get("shift").asString()), p.get("revision").asLong(), p.get("state").asString(),
            p.get("stopps").values().map { s ->
                TripStopView(s.get("seq").asInt(), id(s.get("stop").asString()), Instant.parse(s.get("planned_at").asString()), s.get("boarding").values().map { id(it.asString()) }, s.get("alighting").values().map { id(it.asString()) },
                    s.get("dauer_s").takeIf { !it.isNull }?.asInt(), s.get("strecke_m").takeIf { !it.isNull }?.asInt())
            })

        private fun promise(b: JsonNode, id: (String) -> UUID): BookingPromiseView {
            val z = b.get("zusage")
            return BookingPromiseView(id(b.get("key").asString()), id(b.get("service").asString()), b.get("state").asString(), 1, Instant.parse(z.get("pickup_earliest").asString()), Instant.parse(z.get("pickup_latest").asString()),
                Instant.parse(z.get("arrival_earliest").asString()), Instant.parse(z.get("arrival_latest").asString()), b.get("passagiere").asInt())
        }
    }
}
