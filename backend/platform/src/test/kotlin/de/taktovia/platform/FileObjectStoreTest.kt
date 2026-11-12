package de.taktovia.platform

import de.taktovia.platform.storage.FileObjectStore
import de.taktovia.platform.storage.ObjectKey
import de.taktovia.platform.tenant.TenantId
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** WP-M4-21: Dateisystem-Objektspeicher mit Mandantenpräfix, Schreibschutz (`putIfAbsent`, auch parallel) und ohne Zugriff über Mandantengrenzen. */
class FileObjectStoreTest {
    private val a = TenantId(UUID.randomUUID()); private val b = TenantId(UUID.randomUUID())

    @Test
    fun objekte_liegen_unter_dem_mandantenpraefix_und_sind_je_mandant_getrennt(@TempDir dir: Path) {
        val s = FileObjectStore(dir)
        s.put(ObjectKey(a, "receipts/x.bin"), byteArrayOf(1, 2, 3), "application/octet-stream"); s.put(ObjectKey(b, "receipts/x.bin"), byteArrayOf(9), "application/octet-stream")
        assertTrue(Files.exists(dir.resolve("tenants/${a.value}/receipts/x.bin")))
        assertArrayEquals(byteArrayOf(1, 2, 3), s.get(ObjectKey(a, "receipts/x.bin"))); assertArrayEquals(byteArrayOf(9), s.get(ObjectKey(b, "receipts/x.bin")))
        assertEquals(listOf("receipts/x.bin"), s.list(a)); assertEquals(listOf("receipts/x.bin"), s.list(b, "receipts/"))
        assertNull(s.get(ObjectKey(a, "receipts/gibt-es-nicht")))
        s.delete(ObjectKey(a, "receipts/x.bin")); assertNull(s.get(ObjectKey(a, "receipts/x.bin"))); assertArrayEquals(byteArrayOf(9), s.get(ObjectKey(b, "receipts/x.bin")), "Löschen in A lässt B unberührt")
    }

    @Test
    fun put_if_absent_ueberschreibt_nie_auch_nicht_parallel(@TempDir dir: Path) {
        val s = FileObjectStore(dir); val key = ObjectKey(a, "originals/doc")
        assertTrue(s.putIfAbsent(key, byteArrayOf(1), "x")); assertFalse(s.putIfAbsent(key, byteArrayOf(2), "x")); assertArrayEquals(byteArrayOf(1), s.get(key))
        val k2 = ObjectKey(a, "originals/race"); val pool = Executors.newFixedThreadPool(16); val go = CountDownLatch(1)
        val wins = (1..32).map { i -> pool.submit<Boolean> { go.await(); s.putIfAbsent(k2, byteArrayOf(i.toByte()), "x") } }
        go.countDown(); val results = wins.map { it.get(30, TimeUnit.SECONDS) }; pool.shutdown()
        assertEquals(1, results.count { it }, "genau ein Schreiber gewinnt")
        assertEquals(1, s.get(k2)!!.size); assertEquals(listOf("originals/doc", "originals/race"), s.list(a), "keine Temporärdateien übrig")
    }

    @Test
    fun pfadtricks_sind_unmoeglich(@TempDir dir: Path) {
        val s = FileObjectStore(dir)
        for (bad in listOf("../${b.value}/x", "a/../../b", "/etc/passwd")) org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java, { s.put(ObjectKey(a, bad), byteArrayOf(1), "x") }, bad)
        assertEquals(emptyList<String>(), s.list(b))
    }
}
