package de.taktovia.platform.isolation

import de.taktovia.platform.context.Actor
import de.taktovia.platform.context.ActorType
import de.taktovia.platform.context.RequestContext
import de.taktovia.platform.problem.ErrorCode
import de.taktovia.platform.problem.ProblemException
import de.taktovia.platform.storage.InMemoryObjectStore
import de.taktovia.platform.storage.ObjectKey
import de.taktovia.platform.storage.ObjectPurposeCheck
import de.taktovia.platform.storage.SignedUrls
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** WP-M1-11: Objektschlüssel mit Mandantenpräfix, signierte URLs erst nach Objekt-/Zweckprüfung, höchstens 60 s, URL von B mit Sitzung A wird abgewiesen. */
class ObjectStorageIsolationTest {
    private val a = TenantId(UUID.randomUUID()); private val b = TenantId(UUID.randomUUID())
    private val secret = ByteArray(32) { it.toByte() }
    private class Tick(var now: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }
    private fun ctx(t: TenantId) = RequestContext(Actor(ActorType.HUMAN, "https://idp.test", "u-$t", "sid", true), t, setOf("tenant_admin"), setOf("documents:read"), emptySet(), UUID.randomUUID())
    private val allow = ObjectPurposeCheck { _, _, _ -> true }
    private fun notFound(block: () -> Any?) = assertEquals(ErrorCode.NOT_FOUND, assertThrows(ProblemException::class.java) { block() }.code)

    @Test
    fun objektschluessel_tragen_das_mandantenpraefix_und_erlauben_keine_pfadtricks() {
        assertEquals("tenants/${a.value}/belege/1.pdf", ObjectKey(a, "belege/1.pdf").path())
        for (bad in listOf("../${b.value}/x", "a/../b", "/abs", "a//b", "", "a b", "a/./b", "x/")) assertThrows(IllegalArgumentException::class.java, { ObjectKey(a, bad) }, bad)
        val store = InMemoryObjectStore()
        store.put(ObjectKey(a, "x.bin"), byteArrayOf(1, 2), "application/octet-stream"); store.put(ObjectKey(b, "x.bin"), byteArrayOf(9), "application/octet-stream")
        assertArrayEquals(byteArrayOf(1, 2), store.get(ObjectKey(a, "x.bin"))); assertArrayEquals(byteArrayOf(9), store.get(ObjectKey(b, "x.bin")))
        assertEquals(listOf("x.bin"), store.list(a)); store.delete(ObjectKey(a, "x.bin"))
        assertNull(store.get(ObjectKey(a, "x.bin"))); assertEquals(listOf("x.bin"), store.list(b), "Löschen in A lässt B unberührt")
    }

    @Test
    fun signierte_url_von_b_mit_sitzung_a_abgewiesen_und_hoechstens_60s() {
        val tick = Tick(); val urls = SignedUrls(secret, tick)
        val keyB = ObjectKey(b, "belege/b.pdf")
        val signed = urls.sign(ctx(b), keyB, "beleg_lesen", allow)
        assertEquals(tick.now.plusSeconds(60), signed.expiresAt, "Standardgültigkeit 60 s")
        assertEquals(keyB, urls.verify(ctx(b), signed.url).first, "mit der Sitzung von B gültig")
        notFound { urls.verify(ctx(a), signed.url) }   // Sitzung A mit der URL von B: wie nicht vorhanden
        // dieselbe Antwort wie für eine unbekannte URL (keine Existenzdetails)
        val unknown = assertThrows(ProblemException::class.java) { urls.verify(ctx(a), "/v1/objects/${a.value}/nichts.pdf?exp=1&purpose=x_y&sig=00") }
        val foreign = assertThrows(ProblemException::class.java) { urls.verify(ctx(a), signed.url) }
        assertEquals(unknown.code, foreign.code); assertEquals(unknown.detail, foreign.detail)
        // Gültigkeit über dem Höchstwert, Ablauf, Manipulation
        assertThrows(IllegalArgumentException::class.java) { urls.sign(ctx(b), keyB, "beleg_lesen", allow, Duration.ofSeconds(61)) }
        tick.now = tick.now.plusSeconds(61); notFound { urls.verify(ctx(b), signed.url) }
        tick.now = tick.now.minusSeconds(61)
        notFound { urls.verify(ctx(b), signed.url.replace("beleg_lesen", "beleg_loeschen")) }
        notFound { urls.verify(ctx(b), signed.url.replace("b.pdf", "c.pdf")) }
        notFound { urls.verify(ctx(b), signed.url.replace(Regex("sig=[0-9a-f]+"), "sig=" + "0".repeat(64))) }
        // Umschreiben des Mandanten in der URL auf A mit der Sitzung A: Signatur passt nicht
        notFound { urls.verify(ctx(a), signed.url.replace(b.toString(), a.toString())) }
    }

    @Test
    fun url_nur_nach_objekt_und_zweckpruefung_und_nie_fuer_fremde_objekte() {
        val urls = SignedUrls(secret)
        val deny = ObjectPurposeCheck { _, _, _ -> false }
        notFound { urls.sign(ctx(a), ObjectKey(a, "x.pdf"), "beleg_lesen", deny) }                // keine Berechtigung: wie nicht vorhanden
        notFound { urls.sign(ctx(a), ObjectKey(b, "x.pdf"), "beleg_lesen", allow) }               // Objekt eines anderen Mandanten, auch wenn die Prüfung zustimmen würde
        var asked = 0
        urls.sign(ctx(a), ObjectKey(a, "x.pdf"), "beleg_lesen", ObjectPurposeCheck { _, k, p -> asked++; assertEquals("beleg_lesen", p); assertEquals(a, k.tenant); true })
        assertEquals(1, asked, "die Prüfung läuft vor jeder Signatur")
        assertTrue(urls.maxTtl <= Duration.ofSeconds(60))
        assertThrows(IllegalArgumentException::class.java) { SignedUrls(ByteArray(8)) }
    }
}
