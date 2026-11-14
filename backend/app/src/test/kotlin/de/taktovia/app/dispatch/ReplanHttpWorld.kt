package de.taktovia.app.dispatch

import de.taktovia.app.driver.DriverRideSupport
import de.taktovia.app.driver.DriverRunHttpWorld
import de.taktovia.app.probe.Resp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Testaufbau der manuellen Umplanung über HTTP (WP-M3-40, Cluster wave14-c6): ein Dienst mit zwei Umläufen (Fahrer A und B auf eigenen Fahrzeugen im Pool des Dienstes), Umlauf A mit zwei bestätigten
 * Buchungen (Plan in der Zukunft, Zeiten im Zusagefenster). Ein Disponent des Dienstes ([token]) und einer eines anderen Dienstes ([otherToken]).
 */
internal class ReplanHttpWorld(val sup: DriverRideSupport = DriverRideSupport()) {
    val w: DriverRunHttpWorld = sup.w
    val env = sup.env
    val service: UUID = UUID.fromString(w.s.world.service)
    val token: String = w.s.operator(listOf(service))
    val otherToken: String = w.s.operator(listOf(UUID.randomUUID()))
    val driverA = w.driver(); val driverB = w.driver()
    val b1 = w.booking(); val b2 = w.booking()
    val base: Instant = Instant.parse(b1.held.issued.offer["pickup_window"]["earliest_at"].asString()).truncatedTo(ChronoUnit.SECONDS)

    init {
        sup.confirmed(b1); sup.confirmed(b2); w.plan(driverA.run, listOf(b1, b2), base = base)
        // Schichten und Freigaben beider Fahrzeuge decken die Zeit der Buchungen (die Fahrer-Schicht des Testaufbaus läuft nur ab jetzt)
        listOf(driverA, driverB).forEach { d ->
            env.exec("UPDATE taktovia.staff_shift SET starts_at = ?::timestamptz, ends_at = ?::timestamptz WHERE id = ?", base.minusSeconds(3600).toString(), base.plusSeconds(6 * 3600).toString(), d.shift)
            env.exec("""WITH old AS (DELETE FROM taktovia.resource_release WHERE shift_id = ? RETURNING *)
                INSERT INTO taktovia.resource_release(tenant_id,id,vehicle_id,shift_id,safety_revision,inspection_ref,qualification_revision,valid_until)
                SELECT tenant_id,id,vehicle_id,shift_id,safety_revision,inspection_ref,qualification_revision,?::timestamptz FROM old""", d.shift, base.plusSeconds(6 * 3600).toString())
        }
    }

    fun revision(run: UUID) = env.string("SELECT revision::text FROM taktovia.vehicle_run WHERE id = ?", run).single().toLong()
    fun stop(run: UUID, revision: Long, seq: Int) = UUID.fromString(env.string("SELECT id::text FROM taktovia.route_stop WHERE run_id = ? AND run_revision = ? AND seq = ?", run, revision, seq).single())
    fun vehicleOf(d: DriverRunHttpWorld.Driver) = sup.vehicleOf(d)

    /** Harmlose Umordnung von Umlauf A: der Einstieg der zweiten Buchung rückt an die erste Stelle. */
    fun swap(rev: Long = revision(driverA.run)) = """{"change":"MOVE","route_stop_id":"${stop(driverA.run, rev, 1)}","new_sequence":1}"""

    fun body(changes: String, base: Long = revision(driverA.run), reason: String? = "Disposition ordnet die Abholungen um", extra: String = ""): String =
        "{\"changes\":[$changes],\"base_plan_revision\":$base" + (reason?.let { ",\"reason\":\"$it\"" } ?: "") + extra + "}"

    fun post(run: UUID, json: String, ifMatch: Long? = null, token: String = this.token, key: String = "rev-" + UUID.randomUUID()): Resp =
        env.server.send("POST", "/v1/runs/$run/revisions", json, env.headers(token, extra = mapOf("Idempotency-Key" to key) + (ifMatch?.let { mapOf("If-Match" to "\"$it\"") } ?: emptyMap())))

    fun get(run: UUID, query: String = "", token: String = this.token) = env.get("/v1/runs/$run/revisions$query", token)
}
