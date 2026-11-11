package de.taktovia.platform

import de.taktovia.platform.audit.ActorRef
import de.taktovia.platform.audit.AuditEntry
import de.taktovia.platform.audit.AuditExportVerifier
import de.taktovia.platform.audit.AuditExporter
import de.taktovia.platform.audit.ObjectRef
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** WP-M1-12: Export in die getrennte Integritätsdomäne (HMAC-Hashkette mit Schlüssel außerhalb der Datenbank); Manipulation wird erkannt. */
class AuditExportTest {
    companion object {
        private lateinit var s: AuditTestSupport
        private val key = ByteArray(32) { (it * 7 + 1).toByte() }
        private val otherKey = ByteArray(32) { (it * 11 + 5).toByte() }
        @BeforeAll @JvmStatic fun setup() { s = AuditTestSupport() }
        @AfterAll @JvmStatic fun teardown() { if (::s.isInitialized) s.close() }
    }

    private fun seed(tenant: de.taktovia.platform.tenant.TenantId, n: Int, tag: String): List<UUID> = s.tx.run(tenant) { j ->
        (0 until n).map { i ->
            s.audit.record(j, AuditEntry(ActorRef.system("exp-$tag"), "identity.organization.changed", ObjectRef("organization", "$tag-$i"), UUID.randomUUID(),
                mapOf("revision" to i), Instant.parse("2026-04-01T00:00:00Z").plusSeconds(i.toLong())))
        }
    }

    private fun export(tenant: de.taktovia.platform.tenant.TenantId): Pair<String, de.taktovia.platform.audit.ExportResult> {
        val sb = StringBuilder(); val r = AuditExporter(s.tx, key).export(tenant, sb); return sb.toString() to r
    }

    @Test fun `unveraenderter_export_ist_gueltig`() {
        val tenant = s.a; val ids = seed(tenant, 12, "ok")
        val (text, r) = export(tenant)
        assertTrue(r.count >= 12)
        val v = AuditExportVerifier.verify(text, key, expectedHead = r.head, expectedTenant = tenant.value)
        assertTrue(v.valid, v.problems.toString()); assertEquals(r.count, v.count)
        ids.forEach { assertTrue(text.contains(it.toString())) }
        assertFalse(text.contains("tenant_id\":\"${s.b}"), "Export enthält nur den Mandanten, für den er erstellt wurde")
    }

    @Test fun `manipulierte_kopie_wird_erkannt`() {
        seed(s.b, 8, "man")
        val (text, r) = export(s.b)
        val lines = text.trimEnd('\n').split('\n')
        assertTrue(AuditExportVerifier.verify(text, key).valid)
        fun bad(why: String, copy: String, k: ByteArray = key, head: String? = null) =
            assertFalse(AuditExportVerifier.verify(copy, k, expectedHead = head).valid, "nicht erkannt: $why")
        fun join(l: List<String>) = l.joinToString("\n", postfix = "\n")
        val e = lines.indices.filter { lines[it].contains("\"seq\"") }
        val mid = e[3]
        bad("Feld geändert", join(lines.toMutableList().also { it[mid] = it[mid].replace("exp-man", "exp-xxx") }))
        bad("Zeitpunkt geändert", join(lines.toMutableList().also { it[mid] = it[mid].replace("2026-04-01T00:00:0", "2027-04-01T00:00:0") }))
        bad("Eintrag gelöscht", join(lines.filterIndexed { i, _ -> i != mid }))
        bad("Eintrag vertauscht", join(lines.toMutableList().also { val t = it[e[2]]; it[e[2]] = it[e[4]]; it[e[4]] = t }))
        bad("Eintrag eingefügt", join(lines.toMutableList().also { it.add(mid, it[mid]) }))
        bad("Ende abgeschnitten (ohne Abschlusszeile)", join(lines.dropLast(1)))
        bad("Ende abgeschnitten samt Abschlusszeile neu geschrieben", join(lines.take(e[5] + 1) + lines.last()))
        bad("falscher Schlüssel (Kette ohne Schlüssel neu gerechnet)", text, otherKey)
        seed(s.b, 1, "man-neu")
        val newer = export(s.b).second
        bad("älterer, in sich gültiger Export als Rückschritt", text, head = newer.head)
        assertTrue(AuditExportVerifier.verify(text, key, expectedHead = r.head).valid)
        // eine Fälschung mit neu berechneter Kette, aber ohne den Exportschlüssel, besteht die Prüfung nicht
        val forged = AuditExportVerifier.rechain(join(lines.toMutableList().also { it[mid] = it[mid].replace("exp-man", "exp-xxx") }), otherKey)
        assertFalse(AuditExportVerifier.verify(forged, key).valid)
        assertTrue(AuditExportVerifier.verify(forged, otherKey).valid, "Selbsttest der Fälschung: mit dem Schlüssel des Fälschers ist sie in sich schlüssig")
    }

    @Test fun `export_parallel_zu_100_schreibvorgaengen_bleibt_integer`() {
        val tenant = s.a; val before = seed(tenant, 20, "par-pre")
        val written = java.util.concurrent.ConcurrentLinkedQueue<UUID>()
        val pool = Executors.newFixedThreadPool(10); val go = CountDownLatch(1)
        val writers = (0 until 100).map { i ->
            pool.submit(Callable {
                go.await()
                s.tx.run(tenant) { j -> written += s.audit.record(j, AuditEntry(ActorRef.system("exp-par"), "identity.organization.changed", ObjectRef("organization", "par-$i"), UUID.randomUUID(), emptyMap())) }
            })
        }
        val exports = (0 until 5).map { pool.submit(Callable { go.await(); export(tenant) }) }
        go.countDown()
        writers.forEach { it.get() }
        val results = exports.map { it.get() }; pool.shutdown()
        results.forEach { (text, r) ->
            val v = AuditExportVerifier.verify(text, key, expectedHead = r.head)
            assertTrue(v.valid, v.problems.toString())
            before.forEach { assertTrue(text.contains(it.toString()), "bereits festgeschriebener Eintrag fehlt im Export") }   // Momentaufnahme enthält alles Festgeschriebene
        }
        // nach Abschluss aller Schreibvorgänge enthält ein Export genau alle 100 neuen Einträge, lückenlos
        val (finalText, finalResult) = export(tenant)
        assertTrue(AuditExportVerifier.verify(finalText, key, expectedHead = finalResult.head).valid)
        assertEquals(100, written.size)
        written.forEach { assertTrue(finalText.contains(it.toString()), "Eintrag $it fehlt im Export") }
    }
}
