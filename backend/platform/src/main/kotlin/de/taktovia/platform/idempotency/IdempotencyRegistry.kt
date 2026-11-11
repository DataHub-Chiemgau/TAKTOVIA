package de.taktovia.platform.idempotency

import de.taktovia.platform.tenant.TenantTransactions
import org.springframework.jdbc.core.JdbcOperations
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Idempotenzregister (WP-M1-13) über `idempotency_record` (PK `(tenant_id, principal_ref, command_scope, key)`, RLS je Mandant). Jede Methode ist
 * eine kurze eigene Transaktion im Mandanten der Anfrage; die Fachtransaktion des Befehls läuft **nie** innerhalb dieser Transaktionen.
 *
 * Zustände: `IN_PROGRESS` (Erstverarbeitung läuft, `locked_until` ist die Lease) und `COMPLETED` (angenommenes 2xx-Ergebnis wird bis `expires_at` wiederholt).
 * Der Eintrag wird atomar angelegt (`INSERT … ON CONFLICT DO NOTHING`), danach unter `FOR UPDATE` bewertet; damit entscheidet die Datenbank, wer
 * ausführt, auch bei hundert gleichzeitigen Anfragen.
 */
class IdempotencyRegistry(private val tx: TenantTransactions) {
    private val mapper = JsonMapper.builder().build()

    fun begin(req: IdempotentRequest, now: Instant, lease: Duration, ttl: Duration): Begin = tx.run(req.tenant) { j ->
        val nowTs = Timestamp.from(now); val leaseTs = Timestamp.from(now.plus(lease)); val expiresTs = Timestamp.from(now.plus(ttl))
        for (attempt in 1..5) {
            val inserted = j.update(
                "INSERT INTO taktovia.idempotency_record(tenant_id,principal_ref,command_scope,key,request_sha256,state,locked_until,expires_at,created_at) " +
                    "VALUES (taktovia.current_tenant(),?,?,?,?,'IN_PROGRESS',?,?,?) ON CONFLICT DO NOTHING",
                req.principalRef, req.commandScope, req.key, req.fingerprint, leaseTs, expiresTs, nowTs)
            if (inserted == 1) return@run Begin.Execute
            val row = j.query(
                "SELECT request_sha256, state, locked_until, expires_at, http_status, response_headers::text, response_json::text, operation_id FROM taktovia.idempotency_record " +
                    "WHERE principal_ref = ? AND command_scope = ? AND key = ? FOR UPDATE",
                { rs, _ -> Row(rs.getString(1), rs.getString(2), rs.getTimestamp(3)?.toInstant(), rs.getTimestamp(4).toInstant(), rs.getObject(5) as Int?,
                    rs.getString(6), rs.getString(7), rs.getObject(8, UUID::class.java)) },
                req.principalRef, req.commandScope, req.key).firstOrNull() ?: continue   // zwischenzeitlich bereinigt: erneut versuchen
            val takeOver = (row.state == "COMPLETED" && !row.expiresAt.isAfter(now)) ||      // Antwortcache abgelaufen
                (row.state == "IN_PROGRESS" && (row.lockedUntil == null || !row.lockedUntil.isAfter(now)))   // Verarbeitung abgebrochen
            if (takeOver) {
                j.update("UPDATE taktovia.idempotency_record SET request_sha256 = ?, state = 'IN_PROGRESS', locked_until = ?, expires_at = ?, created_at = ?, completed_at = NULL, " +
                    "http_status = NULL, response_headers = NULL, response_json = NULL, operation_id = NULL WHERE principal_ref = ? AND command_scope = ? AND key = ?",
                    req.fingerprint, leaseTs, expiresTs, nowTs, req.principalRef, req.commandScope, req.key)
                return@run Begin.Execute
            }
            if (row.sha != req.fingerprint) return@run Begin.Conflict
            return@run if (row.state == "COMPLETED") Begin.Replay(toResponse(row)) else Begin.InProgress
        }
        error("Idempotenzregister: Eintrag nicht stabil anlegbar")
    }

    /** Speichert das angenommene Ergebnis. Falsch, wenn der Eintrag nicht mehr dieser Verarbeitung gehört (Lease abgelaufen und übernommen). */
    fun complete(req: IdempotentRequest, now: Instant, ttl: Duration, response: StoredResponse): Boolean {
        val json = response.body?.let { toJsonb(it, response.headers) }
        val headers = response.headers.filterKeys { it in STORED_HEADERS }.toMutableMap()
        if (response.body != null && json!!.second) headers[TEXT_MARKER] = "1"
        return tx.run(req.tenant) { j ->
            j.update("UPDATE taktovia.idempotency_record SET state = 'COMPLETED', http_status = ?, response_headers = ?::jsonb, response_json = ?::jsonb, operation_id = ?, " +
                "completed_at = ?, locked_until = NULL, expires_at = ? WHERE principal_ref = ? AND command_scope = ? AND key = ? AND request_sha256 = ? AND state = 'IN_PROGRESS'",
                response.status, mapper.writeValueAsString(headers), json?.first, response.operationId, Timestamp.from(now), Timestamp.from(now.plus(ttl)),
                req.principalRef, req.commandScope, req.key, req.fingerprint) == 1
        }
    }

    /** Gibt die Erstverarbeitung frei (Befehl nicht angenommen oder abgebrochen); eine Wiederholung führt dann neu aus. */
    fun release(req: IdempotentRequest) {
        tx.run(req.tenant) { j ->
            j.update("DELETE FROM taktovia.idempotency_record WHERE principal_ref = ? AND command_scope = ? AND key = ? AND request_sha256 = ? AND state = 'IN_PROGRESS'",
                req.principalRef, req.commandScope, req.key, req.fingerprint)
        }
    }

    /**
     * Bereinigung nach `expires_at` für einen Mandanten, höchstens [limit] Zeilen je Aufruf. Entfernt abgelaufene Ergebnisse und abgebrochene
     * Verarbeitungen (Lease abgelaufen); ein laufender Befehl (Lease gültig) behält seinen Eintrag auch nach `expires_at`.
     */
    fun purgeExpired(tenant: de.taktovia.platform.tenant.TenantId, now: Instant, limit: Int = 1000): Int = tx.run(tenant) { j: JdbcOperations ->
        j.update("WITH doomed AS MATERIALIZED (SELECT principal_ref, command_scope, key FROM taktovia.idempotency_record WHERE expires_at <= ? " +
            "AND (state = 'COMPLETED' OR locked_until IS NULL OR locked_until <= ?) ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED) " +
            "DELETE FROM taktovia.idempotency_record r USING doomed d WHERE r.principal_ref = d.principal_ref AND r.command_scope = d.command_scope AND r.key = d.key",
            Timestamp.from(now), Timestamp.from(now), limit)
    }

    private class Row(val sha: String, val state: String, val lockedUntil: Instant?, val expiresAt: Instant, val status: Int?, val headers: String?, val json: String?, val operationId: UUID?)

    private fun toResponse(r: Row): StoredResponse {
        val headers = r.headers?.let { mapper.readTree(it).properties().associate { e -> e.key to e.value.asString() } } ?: emptyMap()
        val body = r.json?.let { if (headers[TEXT_MARKER] == "1") mapper.readTree(it).asString() else it }
        return StoredResponse(r.status!!, headers.filterKeys { it != TEXT_MARKER }, body, r.operationId)
    }

    /** Body als jsonb-Text; kein JSON (trotz Content-Type) wird als JSON-String abgelegt und beim Lesen zurückgewandelt. Zweites: war Text. */
    private fun toJsonb(body: String, headers: Map<String, String>): Pair<String, Boolean> =
        try { mapper.readTree(body); body to false } catch (_: Exception) { mapper.writeValueAsString(body) to true }

    companion object {
        /** Nur diese Antwortheader werden gespeichert und wiederholt. */
        val STORED_HEADERS = setOf("Location", "ETag", "Content-Type", "Retry-After")
        private const val TEXT_MARKER = "_text"
    }
}
