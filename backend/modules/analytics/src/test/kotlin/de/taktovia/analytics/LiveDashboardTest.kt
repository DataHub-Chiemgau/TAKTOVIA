package de.taktovia.analytics

import de.taktovia.analytics.api.LiveSettings
import de.taktovia.analytics.api.LiveState
import de.taktovia.analytics.api.ReportScope
import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * WP-M4-60 / LH-I.6-KPI-014: Live-Dashboard (offene Anfragen, verfügbare Kapazität, kritische Verspätungen, Fahrzeugausfälle) auf der echten Datenbank unter der Runtime-Rolle (RLS).
 * Die Projektion entsteht aus Ereignissen (`FactBase.consume`); Stammdaten (Fahrzeug, Schicht, Dienstzuordnung) legt der Administrator an. Die Aktualisierungsfrist ist eine Planungsannahme (D-05, 30 s).
 */
class LiveDashboardTest {
    private val settings = LiveSettings(toleranceMin = 5, alertMin = 15, refreshSeconds = 30, staleAfterSeconds = 120)

    private fun ReportFilterFixture.vehicle(w: ReportFilterFixture.World, service: UUID?, seats: Int = 4, wheelchair: Int = 1, shiftNow: Instant? = null): UUID {
        val v = UUID.randomUUID()
        exec("INSERT INTO taktovia.vehicle(tenant_id,id,owner_org_ref,capacity_config,vehicle_label,created_by) VALUES (?,?,'org',CAST(? AS jsonb),?,'user:test')", w.tenant.value, v, """{"seats":$seats,"wheelchair":$wheelchair}""", "V-" + v.toString().take(8))
        if (service != null) exec("INSERT INTO taktovia.vehicle_pool_assignment(tenant_id,id,service_id,vehicle_id,valid_from,created_by) VALUES (?,?,?,?,?,'user:test')", w.tenant.value, UUID.randomUUID(), service, v, java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(1))))
        if (shiftNow != null) exec("INSERT INTO taktovia.staff_shift(tenant_id,id,driver_ref,vehicle_id,starts_at,ends_at,timezone,roster_revision,state,started_at,started_device_id) VALUES (?,?,?,?,?,?,'Europe/Berlin',1,'STARTED',?,?)",
            w.tenant.value, UUID.randomUUID(), UUID.randomUUID(), v, java.sql.Timestamp.from(shiftNow.minus(Duration.ofHours(1))), java.sql.Timestamp.from(shiftNow.plus(Duration.ofHours(6))), java.sql.Timestamp.from(shiftNow.minus(Duration.ofHours(1))), UUID.randomUUID())
        return v
    }

    private fun ReportFilterFixture.block(w: ReportFilterFixture.World, vehicle: UUID, at: Instant, block: UUID = UUID.randomUUID()): UUID {
        val (_, json) = kpi.base.envelope(w.tenant, "fleet.vehicle_blocked", block, 1, at.toString(), """{"vehicle_id":"$vehicle","block_id":"$block","source":"SAFETY","reason_code":"MANUAL","safety_revision":2}""")
        kpi.base.analytics.facts.consume(w.tenant, json)
        return block
    }

    private fun ReportFilterFixture.lift(w: ReportFilterFixture.World, vehicle: UUID, block: UUID, at: Instant) {
        val (_, json) = kpi.base.envelope(w.tenant, "fleet.vehicle_block_lifted", block, 2, at.toString(), """{"vehicle_id":"$vehicle","block_id":"$block","source":"SAFETY","reason_code":"MANUAL","safety_revision":3}""")
        kpi.base.analytics.facts.consume(w.tenant, json)
    }

    private fun ReportFilterFixture.offer(w: ReportFilterFixture.World, service: UUID, at: Instant, expiresInMin: Long = 10): UUID {
        val attempt = attemptAtInstant(w, service, at.toString(), false, null, w.o1, w.r1, 0, java.time.LocalDate.now())
        val offer = UUID.randomUUID()
        val (_, json) = kpi.base.envelope(w.tenant, "booking.offer_created", offer, 1, at.toString(),
            """{"offer_id":"$offer","attempt_id":"$attempt","service_id":"$service","expires_at":"${at.plus(Duration.ofMinutes(expiresInMin))}","pickup_earliest":"${at.plusSeconds(600)}","pickup_latest":"${at.plusSeconds(900)}","gross_minor":850,"currency":"EUR"}""")
        kpi.base.analytics.facts.consume(w.tenant, json)
        return offer
    }

    private fun ReportFilterFixture.booking(w: ReportFilterFixture.World, service: UUID, confirmedAt: Instant, pickupLatest: Instant): UUID {
        val b = UUID.randomUUID()
        val (_, json) = kpi.base.envelope(w.tenant, "booking.confirmed", b, 3, confirmedAt.toString(),
            """{"booking_id":"$b","hold_id":"${UUID.randomUUID()}","service_id":"$service","pickup_earliest":"${pickupLatest.minusSeconds(600)}","pickup_latest":"$pickupLatest","gross_minor":850,"currency":"EUR","capacity_vector":{"seats":1}}""")
        kpi.base.analytics.facts.consume(w.tenant, json)
        return b
    }

    private fun ReportFilterFixture.driverEvent(w: ReportFilterFixture.World, at: Instant) {
        val id = UUID.randomUUID()
        val (_, json) = kpi.base.envelope(w.tenant, "driver.event_recorded", id, 1, at.toString(), """{"event_id":"$id","shift_id":"${UUID.randomUUID()}","vehicle_id":"${UUID.randomUUID()}","event_type":"CHECK_IN","outcome":"APPLIED","booking_id":"${UUID.randomUUID()}","effective_at":"$at"}""")
        kpi.base.analytics.facts.consume(w.tenant, json)
    }

    @Test
    fun fahrzeugstatus_innerhalb_aktualisierungsfrist_sichtbar() {
        ReportFilterFixture().use { fx ->
            val w = fx.world()
            val v = fx.vehicle(w, w.s1, shiftNow = Instant.now())
            fx.block(w, v, Instant.now().minusSeconds(5), UUID.randomUUID()).also { fx.lift(w, v, it, Instant.now().minusSeconds(4)) }   // Vorlauf: ein Datenstand liegt vor
            val before = fx.metrics.live(w.tenant, Instant.now(), settings)
            assertEquals(0L, before.tile("vehicle_outages").value)
            assertEquals(1L, before.tile("available_capacity").value)
            assertEquals(4L, before.tile("available_capacity").details["seats"])

            // Fahrzeugsperre: Ereignis -> Projektion -> Anzeige; gemessen wird bis zum ersten Live-Abruf, der die Sperre zeigt
            val t0 = System.nanoTime()
            fx.block(w, v, Instant.now())
            var seen: de.taktovia.analytics.api.LiveDashboard
            do { seen = fx.metrics.live(w.tenant, Instant.now(), settings) } while (seen.tile("vehicle_outages").value != 1L && Duration.ofNanos(System.nanoTime() - t0) < Duration.ofSeconds(settings.refreshSeconds.toLong()))
            val elapsed = Duration.ofNanos(System.nanoTime() - t0)
            assertTrue(elapsed < Duration.ofSeconds(settings.refreshSeconds.toLong()), "Sperre nach $elapsed sichtbar, Frist ${settings.refreshSeconds} s")
            assertEquals(1L, seen.tile("vehicle_outages").value)
            assertEquals(0L, seen.tile("available_capacity").value, "gesperrtes Fahrzeug zählt nicht zur verfügbaren Kapazität")
            assertEquals(LiveState.FRESH, seen.tile("vehicle_outages").state)
            assertNotNull(seen.tile("vehicle_outages").asOf)

            // vier getrennte Werte, jeder mit Datenstand
            assertEquals(listOf("open_requests", "available_capacity", "critical_delays", "vehicle_outages"), seen.tiles.map { it.id })
        }
    }

    @Test
    fun vier_getrennte_werte_offene_anfragen_kritische_verspaetung_lange_ausfaelle() {
        ReportFilterFixture().use { fx ->
            val w = fx.world(); val now = Instant.now()
            fx.offer(w, w.s1, now.minusSeconds(60), expiresInMin = 10)         // offen
            fx.offer(w, w.s1, now.minusSeconds(1200), expiresInMin = 10)       // abgelaufen
            val booked = fx.offer(w, w.s1, now.minusSeconds(30), expiresInMin = 10)
            fx.booking(w, w.s1, now.minusSeconds(20), now.minusSeconds(1800)).also { b ->   // Abholung vor 30 min zugesagt, nicht eingestiegen: kritisch
                assertNotNull(b)
            }
            fx.booking(w, w.s1, now.minusSeconds(20), now.plusSeconds(1800))   // nicht fällig
            fx.booking(w, w.s1, now.minusSeconds(20), now.minusSeconds(120))   // innerhalb der Toleranz (5 min)
            val v = fx.vehicle(w, w.s1)
            fx.block(w, v, now.minus(Duration.ofMinutes(20)))                  // lang
            val v2 = fx.vehicle(w, w.s1)
            fx.block(w, v2, now.minusSeconds(60))                              // kurz
            fx.driverEvent(w, now.minusSeconds(10))
            assertNotNull(booked)
            val d = fx.metrics.live(w.tenant, now, settings)
            assertEquals(2L, d.tile("open_requests").value, "zwei ungebuchte, nicht abgelaufene Angebote (eines wurde nur angelegt, nicht gebucht)")
            assertEquals(1L, d.tile("critical_delays").value)
            assertEquals(2L, d.tile("vehicle_outages").value)
            assertEquals(1L, d.tile("vehicle_outages").details["long_outages"])
            assertTrue(d.tiles.all { it.state == LiveState.FRESH }, d.tiles.toString())
        }
    }

    @Test
    fun ereignisstau_zeigt_datenalter_statt_scheinbar_aktuellem_wert() {
        ReportFilterFixture().use { fx ->
            val w = fx.world(); val v = fx.vehicle(w, w.s1)
            val last = Instant.now().minus(Duration.ofMinutes(10))
            fx.block(w, v, last)                                               // letztes verarbeitetes Ereignis vor 10 Minuten, danach Stau
            val d = fx.metrics.live(w.tenant, Instant.now(), settings)
            val t = d.tile("vehicle_outages")
            assertEquals(1L, t.value)
            assertEquals(LiveState.STALE, t.state)
            assertTrue(t.ageSeconds!! >= 600, "Datenalter ${t.ageSeconds}")
            assertTrue(Duration.between(last, t.asOf).abs() <= Duration.ofNanos(1_000), "Datenstand ${t.asOf} statt $last")   // PostgreSQL speichert Mikrosekunden (gerundet)
            // ohne jedes Ereignis der Quelle: kein Wert (nie 0), Zustand NO_DATA
            val other = fx.world()
            val none = fx.metrics.live(other.tenant, Instant.now(), settings)
            assertTrue(none.tiles.all { it.state == LiveState.NO_DATA && it.value == null && it.asOf == null }, none.tiles.toString())
            assertNull(none.asOf)
        }
    }

    @Test
    fun fremder_dienst_und_fremder_mandant_erscheinen_nicht() {
        ReportFilterFixture().use { fx ->
            val w = fx.world(); val now = Instant.now()
            val v1 = fx.vehicle(w, w.s1, shiftNow = now); val v2 = fx.vehicle(w, w.s2, shiftNow = now)
            fx.block(w, v1, now.minusSeconds(30)); fx.block(w, v2, now.minusSeconds(30))
            fx.offer(w, w.s1, now.minusSeconds(30)); fx.offer(w, w.s2, now.minusSeconds(30))
            val foreign = fx.world(); val fv = fx.vehicle(foreign, foreign.s1, shiftNow = now)
            fx.block(foreign, fv, now.minusSeconds(30))
            val all = fx.metrics.live(w.tenant, now, settings)
            assertEquals(2L, all.tile("vehicle_outages").value); assertEquals(2L, all.tile("open_requests").value)
            // Dienstscope nur S1: S2 erscheint weder bei Ausfällen noch bei Anfragen; Mandant B nie
            val only1 = fx.metrics.live(w.tenant, now, settings, ReportScope(scopeOf(w, setOf(w.s1))))
            assertEquals(1L, only1.tile("vehicle_outages").value, "nur Fahrzeuge des berechtigten Dienstes")
            assertEquals(1L, only1.tile("open_requests").value, "nur Anfragen des berechtigten Dienstes")
            val none = fx.metrics.live(w.tenant, now, settings, ReportScope(scopeOf(w, setOf(UUID.randomUUID()))))
            assertEquals(0L, none.tile("vehicle_outages").value)
            assertEquals(0L, none.tile("open_requests").value)
            assertEquals(1L, fx.metrics.live(foreign.tenant, now, settings).tile("vehicle_outages").value)
        }
    }

    @Test
    fun zwei_mandanten_gleichzeitige_statusaenderungen_ohne_vermischung() {
        ReportFilterFixture().use { fx ->
            val a = fx.world(); val b = fx.world()
            val va = (1..3).map { fx.vehicle(a, a.s1) }; val vb = (1..5).map { fx.vehicle(b, b.s1) }
            fx.block(a, va[0], Instant.now().minusSeconds(60)); fx.block(b, vb[0], Instant.now().minusSeconds(60))   // Datenstand beider Mandanten
            val pool = Executors.newFixedThreadPool(8); val go = CountDownLatch(1)
            try {
                val jobs = (va.drop(1).map { a to it } + vb.drop(1).map { b to it }).map { (w, v) ->
                    pool.submit { go.await(); fx.block(w, v, Instant.now()) }
                }
                // währenddessen fortlaufend lesen: jeder Mandant sieht nie mehr als die eigene Fahrzeugzahl
                val readers = listOf(a to 3L, b to 5L).map { (w, max) -> pool.submit { go.await(); repeat(30) { check(fx.metrics.live(w.tenant, Instant.now(), settings).tile("vehicle_outages").value!! <= max) } } }
                go.countDown(); (jobs + readers).forEach { it.get() }
            } finally { pool.shutdownNow() }
            assertEquals(3L, fx.metrics.live(a.tenant, Instant.now(), settings).tile("vehicle_outages").value)
            assertEquals(5L, fx.metrics.live(b.tenant, Instant.now(), settings).tile("vehicle_outages").value)
        }
    }

    @Test
    fun aufgehobene_sperre_zaehlt_nicht_mehr_und_kapazitaet_kehrt_zurueck() {
        ReportFilterFixture().use { fx ->
            val w = fx.world(); val now = Instant.now()
            val v = fx.vehicle(w, w.s1, seats = 6, wheelchair = 2, shiftNow = now)
            val b = fx.block(w, v, now.minusSeconds(60))
            assertEquals(1L, fx.metrics.live(w.tenant, now, settings).tile("vehicle_outages").value)
            fx.lift(w, v, b, now.minusSeconds(5))
            val d = fx.metrics.live(w.tenant, now, settings)
            assertEquals(0L, d.tile("vehicle_outages").value)
            assertEquals(1L, d.tile("available_capacity").value)
            assertEquals(6L, d.tile("available_capacity").details["seats"]); assertEquals(2L, d.tile("available_capacity").details["wheelchair_spaces"])
        }
    }

    /** Anfragekontext mit Dienstscope für die Berechtigungsgrenze (Mitgliedschaft in der Datenbank wie im Betrieb). */
    private fun scopeOf(w: ReportFilterFixture.World, services: Set<UUID>) =
        RequestContext(Actor(ActorType.HUMAN, "https://issuer.test", UUID.randomUUID().toString(), null, false), w.tenant, setOf("dispatcher"), setOf("analytics:read"), services, UUID.randomUUID())
}
