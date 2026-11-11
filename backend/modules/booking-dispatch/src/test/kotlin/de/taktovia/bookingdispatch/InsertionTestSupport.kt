package de.taktovia.bookingdispatch

import de.taktovia.bookingdispatch.RunPlanTestSupport.T0
import de.taktovia.bookingdispatch.RunPlanTestSupport.stopRev
import de.taktovia.bookingdispatch.api.HardLimits
import de.taktovia.bookingdispatch.api.InsertionDemand
import de.taktovia.bookingdispatch.api.InsertionOutcome
import de.taktovia.bookingdispatch.api.InsertionParams
import de.taktovia.bookingdispatch.api.InsertionQuery
import de.taktovia.bookingdispatch.api.InsertionService
import de.taktovia.bookingdispatch.api.InsertionVariant
import de.taktovia.bookingdispatch.api.InsertionVehicle
import de.taktovia.bookingdispatch.api.InsertionWorker
import de.taktovia.bookingdispatch.api.MatrixCell
import de.taktovia.bookingdispatch.api.MatrixInfo
import de.taktovia.bookingdispatch.api.PromiseCore
import de.taktovia.bookingdispatch.api.PromiseWindow
import de.taktovia.bookingdispatch.api.ResourceVector
import de.taktovia.bookingdispatch.api.RouteInfo
import de.taktovia.bookingdispatch.api.RoutingPort
import de.taktovia.bookingdispatch.api.StopRef
import de.taktovia.platform.context.RequestContext
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Testhilfen für Einfügeheuristik, harte Grenzen und Schutz zugesagter Fenster (WP-M2-17 bis -19), eigene Klassen ohne Eingriff in andere Fixtures.
 *
 * [WorkerProcess] startet den ECHTEN Python-Worker (`workers/routing`, nur Standardbibliothek für `/v1/insert`) als Prozess; ohne `python3` im Pfad (oder `TAKTOVIA_PYTHON`)
 * werden die Tests übersprungen, mit `TAKTOVIA_REQUIRE_DB=true` schlagen sie fehl. Der Korridor hat sechs Haltepunkte `H1`..`H6` (Seed-Haltepunkte) mit 300 s je Abstand.
 */
object InsertionTestSupport {
    const val STEP = 300

    /** Haltepunkt `Hn` (n = 1..6) als Referenz mit Revision aus dem Seed. */
    fun stop(n: Int) = StopRef(stopRev(n - 1), "H$n")

    val stopKeys: Map<UUID, String> = (1..6).associate { stopRev(it - 1) to "H$it" }

    /** Fahrzeit nach Abstand der Haltepunktnummern, Distanz 10 m je Sekunde (nur für die Abschnittsdaten). */
    class CorridorRouting : RoutingPort {
        override fun route(ctx: RequestContext, originStop: String, destinationStop: String, profile: String): RouteInfo = throw UnsupportedOperationException()
        override fun walk(ctx: RequestContext, originStop: String, destinationStop: String): RouteInfo = throw UnsupportedOperationException()
        override fun matrix(ctx: RequestContext, originStops: List<String>, destinationStops: List<String>, profile: String): MatrixInfo {
            fun n(k: String) = k.substring(1).toInt()
            val cells = originStops.map { a -> destinationStops.map { b -> MatrixCell(Math.abs(n(a) - n(b)) * STEP, Math.abs(n(a) - n(b)) * STEP * 10) } }
            return MatrixInfo(originStops, destinationStops, cells, profile, "test", "corridor")
        }
    }

    class WorkerProcess : AutoCloseable {
        private val process: Process
        val url: String

        init {
            val python = System.getenv("TAKTOVIA_PYTHON")?.takeIf { it.isNotBlank() } ?: "python3"
            val src = Path.of(System.getProperty("taktovia.repoRoot")).resolve("workers/routing/src")
            val code = "from http.server import HTTPServer\nfrom taktovia_routing.server import make_handler\ns=HTTPServer(('127.0.0.1',0),make_handler(None))\nprint(s.server_port,flush=True)\ns.serve_forever()"
            val started = runCatching {
                ProcessBuilder(python, "-c", code).also { it.environment()["PYTHONPATH"] = src.toString() }.redirectErrorStream(false).start()
            }.getOrNull()
            if (started == null || !Files.isDirectory(src)) {
                if (System.getenv("TAKTOVIA_REQUIRE_DB") == "true") throw AssertionError("python3 für den Routing-Worker erforderlich (TAKTOVIA_REQUIRE_DB=true)")
                assumeTrue(false, "python3 nicht verfügbar")
            }
            process = started!!
            val port = BufferedReader(InputStreamReader(process.inputStream)).readLine()?.trim()?.toIntOrNull()
                ?: throw AssertionError("Routing-Worker nicht gestartet: " + String(process.errorStream.readNBytes(500)))
            url = "http://127.0.0.1:$port"
        }

        val worker = InsertionWorker { body, budget ->
            val resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("$url/v1/insert")).timeout(budget.plusSeconds(5)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            check(resp.statusCode() == 200) { "Worker: HTTP ${resp.statusCode()} ${resp.body()}" }
            resp.body()
        }

        override fun close() { process.destroy() }
    }

    val PARAMS = InsertionParams(poolingAllowed = true, stopDwellSec = 60, wheelchairBoardingSec = 120, limits = HardLimits(60, 100, 0, "cfg-1"))

    fun at(sec: Long): Instant = T0.plusSeconds(sec)

    /** Anfrage von Haltepunkt [from] nach [to] mit Fenstern in Sekunden nach [T0]. */
    fun demand(id: UUID, from: Int, to: Int, pe: Long, pl: Long, ae: Long, al: Long, v: ResourceVector = ResourceVector(1, 0)) =
        InsertionDemand(id, stop(from), stop(to), v, at(pe), at(pl), at(ae), at(al))

    fun vehicle(run: BookingFixture.RunRef, revision: Long, plan: de.taktovia.bookingdispatch.api.RunPlan?, windows: List<PromiseWindow>, capacity: ResourceVector = ResourceVector(4, 1, setOf(de.taktovia.bookingdispatch.api.EquipmentCode.RAMP))) =
        InsertionVehicle(run.vehicleId, run.runId, revision, capacity, stop(1), at(0), at(36000), plan?.stops ?: emptyList(), stopKeys, windows)

    fun promiseOf(w: PromiseWindow, gross: Long = 900) = PromiseCore(w.pickupEarliest.toString(), w.pickupLatest.toString(), w.arrivalEarliest.toString(), w.arrivalLatest.toString(), gross, "EUR", "std-24h", "standard")

    fun offered(o: InsertionOutcome): List<InsertionVariant> = (o as? InsertionOutcome.Offered)?.variants ?: emptyList()

    fun service(w: WorkerProcess) = InsertionService(CorridorRouting(), w.worker)

    val BUDGET: Duration = Duration.ofMillis(1000)
}

/** Ablauf Einfügen, Halten, Bestätigen gegen die Datenbank (echte Heuristik des Workers, echter Hold unter der Runtime-Rolle). */
class RideFlow(val fx: BookingFixture, val api: de.taktovia.bookingdispatch.api.BookingdispatchApi, val svc: InsertionService, val run: BookingFixture.RunRef) {
    val ctx: RequestContext = fx.context(fx.tenantA)

    fun currentVehicle(): InsertionVehicle {
        val plan = api.runs.currentPlan(ctx, run.runId)
        return InsertionTestSupport.vehicle(run, plan.revision, plan, api.holds.protectedWindows(ctx, run.runId))
    }

    fun find(d: InsertionDemand, params: InsertionParams = InsertionTestSupport.PARAMS, vehicle: InsertionVehicle = currentVehicle()): InsertionOutcome =
        svc.find(ctx, InsertionQuery(d, listOf(vehicle), params, "car", InsertionTestSupport.BUDGET))

    fun hold(v: InsertionVariant, d: InsertionDemand, expectedRevision: Long = v.planRevision) = api.holds.place(
        ctx, de.taktovia.bookingdispatch.api.PlaceHold(
            run.runId, expectedRevision, run.serviceId, de.taktovia.bookingdispatch.api.TimeWindow(InsertionTestSupport.at(0), InsertionTestSupport.at(14400)), 0, 1, d.vector,
            reference = "ref-" + UUID.randomUUID(), proposal = de.taktovia.bookingdispatch.api.PlanProposal(v.stops, v.window),
        ),
    )

    /** Beste Variante halten und bestätigen; liefert das Zusagefenster. */
    fun book(d: InsertionDemand, params: InsertionParams = InsertionTestSupport.PARAMS): PromiseWindow {
        val v = InsertionTestSupport.offered(find(d, params)).firstOrNull() ?: throw AssertionError("keine Variante für ${d.bookingId}")
        val placed = hold(v, d) as de.taktovia.bookingdispatch.api.HoldOutcome.Placed
        api.holds.confirm(ctx, placed.hold.id, run.serviceId, InsertionTestSupport.promiseOf(v.window), de.taktovia.bookingdispatch.api.BookingDemand(d.vector.seats + d.vector.wheelchair, d.vector, emptySet()))
        return v.window
    }
}
