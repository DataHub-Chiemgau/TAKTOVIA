package de.taktovia.bookingdispatch.internal

import de.taktovia.bookingdispatch.api.HoldExpiry
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.tenant.TenantTransactions
import java.util.UUID

/**
 * Expiry-Job für Holds (WP-M2-22). Je Mandant mit Mandantenkontext: die abgelaufenen `HELD`-Holds werden in stabiler Reihenfolge (Umlauf, ID) bestimmt und **je Hold in einer eigenen
 * Transaktion** unter dem Lock des Umlaufs verfallen gelassen (derselbe Lock wie bei Hold, Freigabe und Bestätigung). Ein Abbruch lässt bereits erledigte Holds erledigt, der nächste
 * Lauf setzt fort; ein Hold, der zwischenzeitlich bestätigt oder freigegeben wurde, bleibt unberührt (Zustandsprüfung unter dem Lock).
 */
internal class JdbcHoldExpiry(private val tx: TenantTransactions, private val holds: JdbcCapacityHolds) : HoldExpiry {
    override fun runOnce(tenant: TenantId, limit: Int): Int {
        require(limit in 1..10_000) { "limit" }
        val due = tx.run(tenant, readOnly = true) { j ->
            j.query("SELECT id FROM taktovia.capacity_hold WHERE state = 'HELD' AND expires_at <= clock_timestamp() ORDER BY run_id, id LIMIT ?", { rs, _ -> rs.getObject(1, UUID::class.java) }, limit)
        }
        return due.count { id -> tx.run(tenant) { j -> holds.expireIn(j, id, null) } }
    }
}
