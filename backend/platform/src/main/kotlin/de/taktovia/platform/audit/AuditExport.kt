package de.taktovia.platform.audit

import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class ExportResult(val tenant: TenantId, val count: Int, val head: String)

/**
 * Export des Audits in eine getrennte Integritäts-/Berechtigungsdomäne (WP-M1-12, Kap. 04 §4.3, Kap. 01 §1.7; Verfahren zu OP-14 als
 * Planungsannahme): eine NDJSON-Datei je Mandant mit **HMAC-SHA256-Hashkette**. Der Schlüssel liegt nicht in der Datenbank und nicht im
 * Anwendungsbestand der Fachdaten, sondern im Besitz der Exportdomäne (Konfiguration `taktovia.audit.export-key`, aus dem Secret-Speicher);
 * wer nur die Datei beschreiben kann, kann die Kette nicht neu rechnen.
 *
 * Format (jede Zeile `<Körper>,"chain":"<hex>"}`): Kopfzeile (`type=header`), je Eintrag eine Zeile (`type=entry`, `seq` ab 1) und
 * Abschlusszeile (`type=footer`, `count`, `head`). `chain(Kopf) = HMAC(key, LABEL + "\n" + Körper)`, `chain(n) = HMAC(key, chain(n-1) + "\n" + Körper)`.
 * Gelesen wird in **einer** SQL-Anweisung (eine Momentaufnahme): parallele Schreibvorgänge erzeugen weder Lücken noch Doppelungen; was
 * festgeschrieben war, ist enthalten, später Hinzukommendes steht im nächsten Export.
 */
class AuditExporter(private val tx: TenantTransactions, key: ByteArray, private val clock: Clock = Clock.systemUTC()) {
    private val mapper = JsonMapper.builder().build()
    private val key = key.copyOf().also { require(it.size >= 32) { "Exportschlüssel muss mindestens 32 Byte lang sein" } }

    fun export(tenant: TenantId, out: Appendable): ExportResult {
        val rows = tx.run(tenant, readOnly = true) { j ->
            j.query(
                "SELECT id, actor_ref, action, object_ref, occurred_at, correlation_id, redacted_changes::text AS changes FROM taktovia.audit_event ORDER BY occurred_at, id",
            ) { rs, _ ->
                arrayOf(rs.getString("id"), rs.getString("actor_ref"), rs.getString("action"), rs.getString("object_ref"),
                    rs.getTimestamp("occurred_at").toInstant().toString(), rs.getString("correlation_id"), rs.getString("changes"))
            }
        }
        val head = header(tenant)
        var chain = AuditExportFormat.chain(key, AuditExportFormat.LABEL, head)
        out.append(head).append(",\"chain\":\"").append(chain).append("\"}\n")
        rows.forEachIndexed { i, r ->
            val body = "{\"type\":\"entry\",\"seq\":${i + 1},\"id\":${q(r[0])},\"actor_ref\":${q(r[1])},\"action\":${q(r[2])},\"object_ref\":${q(r[3])}," +
                "\"occurred_at\":${q(r[4])},\"correlation_id\":${q(r[5])},\"redacted_changes\":${r[6]}"
            chain = AuditExportFormat.chain(key, chain, body)
            out.append(body).append(",\"chain\":\"").append(chain).append("\"}\n")
        }
        val footer = "{\"type\":\"footer\",\"count\":${rows.size},\"head\":\"$chain\""
        out.append(footer).append(",\"chain\":\"").append(AuditExportFormat.chain(key, chain, footer)).append("\"}\n")
        return ExportResult(tenant, rows.size, chain)
    }

    /** Schreibt den Export atomar in das Zielverzeichnis der Exportdomäne (Datei erst nach vollständigem Schreiben sichtbar, nur lesbar). */
    fun exportToDirectory(tenant: TenantId, dir: Path): Pair<Path, ExportResult> {
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, "audit-export-", ".tmp")
        val result = try { Files.newBufferedWriter(tmp, StandardCharsets.UTF_8).use { export(tenant, it) } } catch (e: Throwable) { Files.deleteIfExists(tmp); throw e }
        val target = dir.resolve("audit-$tenant-${clock.instant().toString().replace(':', '-')}-${result.head.take(12)}.ndjson")
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
        target.toFile().setWritable(false)
        return target to result
    }

    /** Mehrere Mandanten (Worker): je Mandant eine Datei; Fehler bei einem Mandanten stoppen die übrigen nicht. */
    fun exportAll(tenants: Collection<TenantId>, dir: Path): Map<TenantId, Result<Pair<Path, ExportResult>>> = tenants.distinct().associateWith { runCatching { exportToDirectory(it, dir) } }

    private fun header(tenant: TenantId) = "{\"type\":\"header\",\"version\":1,\"algorithm\":\"HMAC-SHA256\",\"tenant\":\"$tenant\",\"exported_at\":${q(clock.instant().toString())}"
    private fun q(s: String) = mapper.writeValueAsString(s)
}

internal object AuditExportFormat {
    const val LABEL = "taktovia-audit-export-v1"
    fun chain(key: ByteArray, previous: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256").also { it.init(SecretKeySpec(key, "HmacSHA256")) }
        return HexFormat.of().formatHex(mac.doFinal("$previous\n$body".toByteArray(StandardCharsets.UTF_8)))
    }
}

data class VerifyResult(val valid: Boolean, val count: Int, val problems: List<String>)

/**
 * Prüft einen Export (Kopie): jede Zeile gegen die Kette, lückenlose `seq`, genau eine Abschlusszeile mit passender Anzahl und Kopfwert, kein
 * Text dahinter. [expectedHead] ist der an anderer Stelle der Exportdomäne hinterlegte Kopfwert und erkennt auch das Zurückspielen eines
 * älteren, in sich gültigen Exports. [expectedTenant] verhindert die Verwechslung mit dem Export eines anderen Mandanten.
 */
object AuditExportVerifier {
    private val LINE = Regex("^(.*),\"chain\":\"([0-9a-f]{64})\"\\}$")
    private val mapper = JsonMapper.builder().build()

    fun verify(text: String, key: ByteArray, expectedHead: String? = null, expectedTenant: java.util.UUID? = null): VerifyResult {
        val problems = mutableListOf<String>()
        fun bad(s: String): VerifyResult { problems += s; return VerifyResult(false, 0, problems) }
        if (!text.endsWith("\n")) return bad("Ende der Datei fehlt (abgeschnitten)")
        val lines = text.dropLast(1).split('\n')
        var previous = AuditExportFormat.LABEL
        var seq = 0; var footerSeen = false
        for ((i, line) in lines.withIndex()) {
            val m = LINE.matchEntire(line) ?: return bad("Zeile ${i + 1} hat kein gültiges Format")
            val body = m.groupValues[1]
            val expected = AuditExportFormat.chain(key, previous, body)
            if (!MessageDigest.isEqual(expected.toByteArray(), m.groupValues[2].toByteArray())) return bad("Zeile ${i + 1}: Kettenwert ungültig (verändert, entfernt, vertauscht, eingefügt oder falscher Schlüssel)")
            previous = expected
            if (footerSeen) return bad("Zeile ${i + 1} nach der Abschlusszeile")
            val node = try { mapper.readTree("$body}") } catch (_: Exception) { return bad("Zeile ${i + 1}: kein gültiges JSON") }
            when (node.path("type").asString("")) {
                "header" -> {
                    if (i != 0) return bad("Kopfzeile nicht am Anfang")
                    if (node.path("version").asInt(0) != 1) return bad("unbekannte Version")
                    if (expectedTenant != null && node.path("tenant").asString("") != expectedTenant.toString()) return bad("Export gehört zu einem anderen Mandanten")
                }
                "entry" -> {
                    if (i == 0) return bad("Kopfzeile fehlt")
                    if (node.path("seq").asInt(-1) != seq + 1) return bad("Zeile ${i + 1}: Folgenummer nicht lückenlos")
                    seq++
                }
                "footer" -> {
                    if (i == 0) return bad("Kopfzeile fehlt")
                    footerSeen = true
                    val head = lines.getOrNull(i - 1)?.let { LINE.matchEntire(it)?.groupValues?.get(2) }
                    if (node.path("count").asInt(-1) != seq) return bad("Anzahl der Einträge stimmt nicht mit der Abschlusszeile überein")
                    if (node.path("head").asString("") != head) return bad("Kopfwert der Abschlusszeile stimmt nicht")
                    if (expectedHead != null && expectedHead != head) return bad("Kopfwert entspricht nicht dem hinterlegten Wert (älterer oder fremder Export)")
                }
                else -> return bad("Zeile ${i + 1}: unbekannter Typ")
            }
        }
        if (!footerSeen) return bad("Abschlusszeile fehlt (abgeschnitten)")
        return VerifyResult(true, seq, emptyList())
    }

    /** Nur für Tests: rechnet die Kette einer (veränderten) Kopie mit [key] neu, wie es ein Fälscher ohne den Exportschlüssel mit einem eigenen täte. */
    fun rechain(text: String, key: ByteArray): String {
        var previous = AuditExportFormat.LABEL
        val out = StringBuilder()
        for (line in text.dropLast(1).split('\n')) {
            var body = LINE.matchEntire(line)!!.groupValues[1]
            if (body.contains("\"type\":\"footer\"")) body = body.replace(Regex("\"head\":\"[0-9a-f]{64}\""), "\"head\":\"$previous\"")
            previous = AuditExportFormat.chain(key, previous, body)
            out.append(body).append(",\"chain\":\"").append(previous).append("\"}\n")
        }
        return out.toString()
    }
}
