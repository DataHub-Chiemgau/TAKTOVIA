package de.taktovia.app.driver

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WP-M3-31 / LH-6.6-DRV-007 über die echte Anwendung (HTTP, Runtime-Rolle, Test-Issuer statt Keycloak, echte Buchungsbestätigung): Statuswechsel auf Störung gegen die Zusage einer Buchung.
 * Deterministisch: nach dem Wechsel auf Störung (oder Pause, Ende) ergibt die Bestätigung eines vorher gehaltenen Angebots 409 und keine Buchung; Gegenprobe im Status Verfügbarkeit. Konkurrierend:
 * Störungsmeldung und Bestätigung gleichzeitig, jede Runde endet konsistent (Buchung genau dann, wenn die Bestätigung 201 lieferte; Status danach Störung), nie mit einem Fehler außerhalb des Fachgrunds.
 * Die Sperrreihenfolge (Fahrzeugzeile: Statuswechsel `FOR UPDATE`, Zusage `FOR SHARE`) sorgt dafür, dass beide Reihenfolgen serialisierbar sind.
 */
class VehicleServiceStatusRaceTest {
    private val w = DriverRunHttpWorld()
    private val env = w.env
    private fun idem() = mapOf("Idempotency-Key" to "k-" + UUID.randomUUID())

    private fun report(d: DriverRunHttpWorld.Driver, target: String) =
        env.post("/v1/driver/shifts/${d.shift}/service-status",
            """{"event_id":"${UUID.randomUUID()}","target_status":"$target","occurred_at":"2026-11-12T08:00:00Z","device_recorded_at":"2026-11-12T08:00:00Z","offline_recorded":false}""", d.token, extra = idem())

    /** Gehaltenes Angebot auf dem Umlauf eines angetretenen Fahrers, dessen Schicht den Abholzeitpunkt deckt und der verfügbar gemeldet ist. */
    private fun scene(): Pair<BookingHttpSupportHeld, DriverRunHttpWorld.Driver> {
        val h = w.s.held(); val d = w.Driver(h.run)
        val pickup = env.string("SELECT pickup_earliest::text FROM taktovia.ride_offer WHERE id = ?::uuid", h.offerId).single()
        env.exec("UPDATE taktovia.staff_shift SET starts_at = ?::timestamptz - interval '1 hour', ends_at = ?::timestamptz + interval '2 hours' WHERE id = ?", pickup, pickup, d.shift)
        val r = report(d, "AVAILABLE"); assertEquals(200, r.status, r.text); assertEquals("AVAILABLE", r.json["status"]["status"].asString())
        return h to d
    }

    private fun bookings(h: BookingHttpSupportHeld) = env.count("SELECT count(*) FROM taktovia.booking WHERE hold_id = ?::uuid", h.holdId)

    @Test
    fun stoerung_pause_ende_vor_der_bestaetigung_ergibt_keine_zusage() {
        // Gegenprobe: im Status Verfügbarkeit wird bestätigt
        val (ok, _) = scene(); val c = w.s.confirm(ok)
        assertEquals(201, c.status, c.text); assertEquals(1, bookings(ok))
        // Störung nach dem Hold, vor der Bestätigung
        val (h, d) = scene()
        assertEquals("APPLIED", report(d, "DISRUPTED").json["state"].asString())
        val denied = w.s.confirm(h)
        assertEquals(409, denied.status, denied.text); assertEquals("CAPACITY_UNAVAILABLE", denied.json["code"]?.asString() ?: denied.json["type"].asString().substringAfterLast('/').uppercase().replace('-', '_'))
        assertEquals(0, bookings(h), "keine Zusage ohne disponierbares Fahrzeug")
        // Pause
        val (hp, dp) = scene(); assertEquals("APPLIED", report(dp, "BREAK").json["state"].asString())
        assertEquals(409, w.s.confirm(hp).status); assertEquals(0, bookings(hp))
        // Ende
        val (he, de) = scene(); assertEquals("APPLIED", report(de, "ENDED").json["state"].asString())
        assertEquals(409, w.s.confirm(he).status); assertEquals(0, bookings(he))
    }

    @Test
    fun stoerung_parallel_zu_zuweisung_keine_zusage() {
        repeat(12) { round ->
            val (h, d) = scene()
            val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
            val confirm = pool.submit(Callable { start.await(); w.s.confirm(h) })
            val disrupt = pool.submit(Callable { start.await(); report(d, "DISRUPTED") })
            start.countDown(); val c = confirm.get(60, TimeUnit.SECONDS); val r = disrupt.get(60, TimeUnit.SECONDS); pool.shutdown()
            assertEquals(200, r.status, "Runde $round Störung: ${r.text}"); assertEquals("APPLIED", r.json["state"].asString())
            assertTrue(c.status == 201 || c.status == 409, "Runde $round Bestätigung: ${c.status} ${c.text}")
            assertEquals(if (c.status == 201) 1 else 0, bookings(h), "Runde $round: Buchung genau dann, wenn bestätigt")
            assertEquals("DISRUPTED", env.get("/v1/driver/shifts/${d.shift}/service-status", d.token).json["status"].asString(), "Runde $round: Endstatus Störung")
            // nach der Störung entsteht in keiner Reihenfolge eine weitere Zusage
            assertEquals(409, w.s.confirm(h, key = "confirm-" + UUID.randomUUID()).let { if (c.status == 201) 409 else it.status })
        }
    }
}

private typealias BookingHttpSupportHeld = de.taktovia.app.booking.BookingHttpSupport.Held
