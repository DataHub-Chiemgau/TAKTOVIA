package de.taktovia.communicationsupport

import de.taktovia.communicationsupport.api.BookingFacts
import de.taktovia.communicationsupport.api.BookingFactsSource
import de.taktovia.communicationsupport.api.DeliveryPurposePolicy
import de.taktovia.communicationsupport.api.PickupEta
import de.taktovia.communicationsupport.api.PickupEtaSource
import de.taktovia.communicationsupport.api.TriggerSettings
import de.taktovia.platform.audit.JdbcAuditPort
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.outbox.EventEnvelope
import de.taktovia.platform.outbox.InboxProcessor
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * WP-M4-66 Kriterium 4 (Vollabnahme zu WP-M2-34): die Auslösung „ETA geändert“ entscheidet mit der Prognosezeit des ETA-Grundmodells (Leseport „ETA je Stopp“). Eine Änderung der Prognose
 * oberhalb der Relevanzschwelle löst die Fahrgastnachricht aus (mit der Prognosezeit), eine Änderung darunter nicht, im Rückfall (keine aktuelle Prognose) gilt unverändert die Plan-ETA.
 * Gegen die echte Datenbank unter der Runtime-Rolle; die Prognosequelle ist ein Testdouble für den Port [PickupEtaSource].
 */
class EtaNotificationTriggerTest {
    private companion object {
        val f = CommFixture()
        @JvmStatic @AfterAll fun close() = f.close()
        val mapper: JsonMapper = JsonMapper.builder().build()
        val PICKUP: Instant = Instant.parse("2026-11-02T09:00:00Z")
    }

    private class Facts : BookingFactsSource {
        val map = ConcurrentHashMap<Pair<UUID, UUID>, BookingFacts>()
        override fun booking(jdbc: JdbcOperations, bookingId: UUID): BookingFacts? =
            map[jdbc.queryForObject("SELECT taktovia.current_tenant()", UUID::class.java)!! to bookingId]
    }

    private class Forecasts : PickupEtaSource {
        val map = ConcurrentHashMap<UUID, PickupEta>()
        override fun forecast(tenant: de.taktovia.platform.tenant.TenantId, bookingId: UUID): PickupEta? = map[bookingId]
    }

    private class Scene(val w: CommFixture.World, val facts: Facts, val forecasts: Forecasts, val consume: (String) -> Unit) {
        fun booking(i: Int, n: Int): UUID = UUID.randomUUID().also { id -> facts.map[w.tenant.value to id] = BookingFacts(id, "BK-2026-%06d".format(n), w.passengers[i], w.serviceA, "CONFIRMED", "Marktplatz", PICKUP, PICKUP.plusSeconds(900), "Europe/Berlin") }

        /** Planrevision, die die Abholzeit der Buchung um [shiftMin] Minuten verschiebt (Plan-ETA). */
        fun planChange(b: UUID, shiftMin: Long) {
            val run = UUID.randomUUID()
            val json = EventEnvelope(UUID.randomUUID(), "dispatch.run_revised", "1.0", run, 2, f.clock.instant(), f.clock.instant(), w.tenant.value, w.tenant.value, UUID.randomUUID(),
                mapper.readTree("""{"run_id":"$run","previous_revision":1,"revision":2,"cause":"MANUAL_CHANGE","affected_booking_ids":[],"booking_changes":[{"booking_id":"$b","kind":"TIME_CHANGED","sequence":2,
                    "pickup_before":"$PICKUP","pickup_after":"${PICKUP.plusSeconds(shiftMin * 60)}","dropoff_before":"${PICKUP.plusSeconds(900)}","dropoff_after":"${PICKUP.plusSeconds(900 + shiftMin * 60)}"}]}""")).toJson()
            consume(json)
        }
        fun types(passenger: UUID) = f.strings("SELECT event_type FROM taktovia.notification WHERE tenant_id = ? AND recipient_ref = ? ORDER BY created_at, id", w.tenant.value, passenger)
        fun etaParams(passenger: UUID) = f.strings("SELECT params::text FROM taktovia.notification WHERE tenant_id = ? AND recipient_ref = ? ORDER BY created_at, id", w.tenant.value, passenger)
    }

    private fun scene(): Scene {
        val w = f.world(); f.api.templates.seedDefaults(f.context(w.tenant))
        val facts = Facts(); val forecasts = Forecasts()
        val processor = InboxProcessor(f.tx, SecurityEventPort(JdbcAuditPort()), { _, _, _ -> error("kein Snapshot erwartet") })
        val consumer = f.api.transactionalConsumer(processor, facts, f.directory, DeliveryPurposePolicy.OPERATIONAL_ONLY, TriggerSettings(etaRelevanceThreshold = Duration.ofMinutes(5), pickupEta = forecasts))
        return Scene(w, facts, forecasts) { consumer.consume(it) }
    }

    @Test
    fun prognoseaenderung_oberhalb_der_schwelle_loest_aus_darunter_nicht() {
        val s = scene()
        val over = s.booking(0, 1); val under = s.booking(1, 2)
        // Plan verschiebt beide um 10 Minuten (nach Plan-ETA relevant); die Prognose des Grundmodells weicht aber nur um 3 Minuten von der zuletzt bekannten Zeit ab: keine Nachricht
        s.forecasts.map[under] = PickupEta(PICKUP.plusSeconds(3 * 60), fromModel = true)
        s.forecasts.map[over] = PickupEta(PICKUP.plusSeconds(8 * 60), fromModel = true)   // 8 Minuten Abweichung: relevant, die Nachricht nennt die Prognose
        s.planChange(under, 10); s.planChange(over, 10)
        assertEquals(emptyList<String>(), s.types(s.w.passengers[1]), "Prognoseänderung unter der Schwelle")
        assertEquals(listOf("eta.changed"), s.types(s.w.passengers[0]), "Prognoseänderung oberhalb der Schwelle")
        assertTrue(s.etaParams(s.w.passengers[0]).single().contains("10:08"), "die Nachricht nennt die Prognosezeit (09:08Z = 10:08 Ortszeit): ${s.etaParams(s.w.passengers[0])}")
    }

    @Test
    fun im_rueckfall_ohne_aktuelle_prognose_gilt_die_plan_eta() {
        val s = scene()
        val stale = s.booking(0, 3); val none = s.booking(1, 4)
        s.forecasts.map[stale] = PickupEta(PICKUP.plusSeconds(60), fromModel = false)   // veraltete Positionsdaten: Rückfall, die Zeit ist die Planzeit
        s.planChange(stale, 10); s.planChange(none, 10)    // keine Prognose vorhanden: Plan-ETA, 10 Minuten sind relevant
        assertEquals(listOf("eta.changed"), s.types(s.w.passengers[0]))
        assertTrue(s.etaParams(s.w.passengers[0]).single().contains("10:10"), "Plan-ETA 09:10Z = 10:10 Ortszeit")
        assertEquals(listOf("eta.changed"), s.types(s.w.passengers[1]))
        // eine Planverschiebung unter der Schwelle löst im Rückfall weiterhin nichts aus
        val small = s.booking(0, 5); s.planChange(small, 4)
        assertEquals(1, s.types(s.w.passengers[0]).size)
    }

    @Test
    fun ein_fehler_der_prognosequelle_laesst_die_plan_eta_gelten_und_verbraucht_das_ereignis() {
        val w = f.world(); f.api.templates.seedDefaults(f.context(w.tenant))
        val facts = Facts()
        val processor = InboxProcessor(f.tx, SecurityEventPort(JdbcAuditPort()), { _, _, _ -> error("kein Snapshot erwartet") })
        val consumer = f.api.transactionalConsumer(processor, facts, f.directory, DeliveryPurposePolicy.OPERATIONAL_ONLY,
            TriggerSettings(pickupEta = PickupEtaSource { _, _ -> throw IllegalStateException("Prognosequelle gestört") }))
        val sc = Scene(w, facts, Forecasts()) { consumer.consume(it) }
        // die Quelle wirft: der Auslöser fällt auf die Plan-ETA zurück, das Ereignis wird normal verarbeitet
        sc.planChange(sc.booking(0, 7), 10)
        assertEquals(listOf("eta.changed"), sc.types(w.passengers[0]))
    }
}
