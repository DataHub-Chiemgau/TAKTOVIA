package de.taktovia.identity

import de.taktovia.identity.api.ConsentPurpose
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** WP-M1-30 / LH-6.2-IAM-011: Zustellport `isDeliveryAllowed` gegen die echte Datenbank (Nachweis mit echter Zustellung folgt mit WP-M2-32). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsentDeliveryPortTest {
    private lateinit var f: PassengerTestFixture
    @BeforeAll fun setup() { f = PassengerTestFixture() }
    @AfterAll fun teardown() { if (::f.isInitialized) f.close() }
    private val v get() = f.settings.consentTextVersion

    private fun allowed(t: TenantId, p: UUID, purpose: ConsentPurpose) = f.api.delivery.isDeliveryAllowed(t, p, purpose)

    @Test fun marketing_abgelehnt_sicherheitsinfo_erlaubt() {
        val t = f.tenant(); val (_, ctx) = f.verifiedPassenger(t); val p = f.api.accounts.own(ctx)!!.id
        f.api.consents.record(ctx, ConsentPurpose.MARKETING, true, v)
        assertTrue(allowed(t, p, ConsentPurpose.MARKETING))
        f.api.consents.record(ctx, ConsentPurpose.MARKETING, false, v)   // Widerruf = abgelehnt
        assertFalse(allowed(t, p, ConsentPurpose.MARKETING), "Marketing nach Widerruf nicht erlaubt")
        assertTrue(allowed(t, p, ConsentPurpose.OPERATIONAL_SAFETY), "sicherheitsrelevante Fahrtinformation bleibt erlaubt")
        // Revisionen: Einwilligung und Widerruf mit Zeitpunkt und Textversion, frühere bleiben lesbar
        val h = f.api.consents.settings(ctx).history
        assertEquals(listOf(false, true), h.map { it.granted }); assertEquals(listOf(2L, 1L), h.map { it.sequence }); assertTrue(h.all { it.textVersion == v })
        assertTrue(h[0].recordedAt >= h[1].recordedAt)
    }

    @Test fun marketing_ohne_revision_standardmaessig_nicht_erlaubt() {
        val t = f.tenant(); val (_, ctx) = f.verifiedPassenger(t); val p = f.api.accounts.own(ctx)!!.id
        assertFalse(allowed(t, p, ConsentPurpose.MARKETING)); assertTrue(allowed(t, p, ConsentPurpose.OPERATIONAL_SAFETY))
        val s = f.api.consents.settings(ctx)
        assertFalse(s.consents.single { it.purpose == ConsentPurpose.MARKETING }.granted); assertTrue(s.consents.single { it.purpose == ConsentPurpose.MARKETING }.changeable)
        assertTrue(s.consents.single { it.purpose == ConsentPurpose.OPERATIONAL_SAFETY }.granted); assertFalse(s.consents.single { it.purpose == ConsentPurpose.OPERATIONAL_SAFETY }.changeable)
        assertTrue(s.history.isEmpty())
        // unbekannter Fahrgast / anderer Mandant: Marketing nie erlaubt, betrieblich immer
        assertFalse(allowed(t, UUID.randomUUID(), ConsentPurpose.MARKETING)); assertFalse(allowed(f.tenant(), p, ConsentPurpose.MARKETING))
        // nur Revisionen mit granted=false bedeuten ebenfalls: keine Freigabe
        f.api.consents.record(ctx, ConsentPurpose.MARKETING, false, v)
        assertFalse(allowed(t, p, ConsentPurpose.MARKETING))
    }

    @Test fun widerruf_parallel_zur_zustellpruefung() {
        val t = f.tenant(); val (_, ctx) = f.verifiedPassenger(t); val p = f.api.accounts.own(ctx)!!.id
        f.api.consents.record(ctx, ConsentPurpose.MARKETING, true, v)
        val inTx = CountDownLatch(1); val release = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            // Der Widerruf hält seine Transaktion offen (Revision angelegt, noch nicht festgeschrieben) ...
            val revoke = pool.submit {
                f.base.tx.run(t) { j ->
                    val seq = j.queryForObject("SELECT max(sequence) FROM taktovia.consent_revision WHERE passenger_id = ? AND purpose = 'MARKETING'", Long::class.java, p)!! + 1
                    j.update("INSERT INTO taktovia.consent_revision (tenant_id, id, passenger_id, purpose, sequence, granted, text_version) VALUES (taktovia.current_tenant(), ?, ?, 'MARKETING', ?, false, ?)", UUID.randomUUID(), p, seq, v)
                    inTx.countDown(); release.await(10, TimeUnit.SECONDS)
                }
            }
            assertTrue(inTx.await(10, TimeUnit.SECONDS))
            // ... die Zustellprüfung davor sieht nur Festgeschriebenes: noch erlaubt (konsistenter Stand vor dem Widerruf)
            assertTrue(allowed(t, p, ConsentPurpose.MARKETING), "vor dem Commit gilt die letzte festgeschriebene Einwilligung")
            release.countDown(); revoke.get(10, TimeUnit.SECONDS)
            // Nach dem Commit des Widerrufs gibt es keine Marketingfreigabe mehr, auf keiner Verbindung
            repeat(20) { assertFalse(allowed(t, p, ConsentPurpose.MARKETING)) }
            val checks = (1..8).map { pool.submit<Boolean> { allowed(t, p, ConsentPurpose.MARKETING) } }.map { it.get() }
            assertTrue(checks.none { it })
        } finally { pool.shutdownNow() }
    }

    @Test fun zwei_parallele_aenderungen_erzeugen_zwei_revisionen_in_eindeutiger_reihenfolge() {
        val t = f.tenant(); val (_, ctx) = f.verifiedPassenger(t); val p = f.api.accounts.own(ctx)!!.id
        val pool = Executors.newFixedThreadPool(2); val barrier = java.util.concurrent.CyclicBarrier(2)
        try {
            (0..1).map { i -> pool.submit<Unit> { barrier.await(); f.api.consents.record(ctx, ConsentPurpose.MARKETING, i == 0, v) } }.forEach { it.get() }
            val seq = f.base.adminStrings("SELECT sequence::text FROM taktovia.consent_revision WHERE passenger_id = ?::uuid ORDER BY sequence", p.toString())
            assertEquals(listOf("1", "2"), seq)
            val last = f.base.adminStrings("SELECT granted::text FROM taktovia.consent_revision WHERE passenger_id = ?::uuid ORDER BY sequence DESC LIMIT 1", p.toString()).single().toBoolean()
            assertEquals(last, allowed(t, p, ConsentPurpose.MARKETING), "aktueller Stand = jüngste Revision")
        } finally { pool.shutdownNow() }
    }
}
