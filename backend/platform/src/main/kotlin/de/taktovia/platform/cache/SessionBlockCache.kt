package de.taktovia.platform.cache

import io.lettuce.core.RedisClient
import io.lettuce.core.ClientOptions
import io.lettuce.core.RedisURI
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import java.time.Duration

/**
 * Cache der **Sperrliste** für Sitzungen und Konten (WP-M1-10, LH-6.2-IAM-009). Leitlinie 4: Redis ist nie führend. Der Cache kennt nur
 * Sperren („Eintrag vorhanden = gesperrt“), keine positiven Antworten: Ein fehlender Eintrag heißt „Datenbank fragen“, nie „nicht gesperrt“.
 * So hängt die Sperrwirkung nicht von einer TTL ab und ein Ausfall des Caches erlaubt nie einen Zugriff. Eine Sperre setzt ihren Eintrag
 * **vor** dem Datenbank-Commit und entfernt ihn bei Rollback ([clear]), damit nach dem Commit begonnene Anfragen nie „nicht gesperrt“ sehen.
 * Alle Methoden dürfen scheitern; der Aufrufer behandelt das als Cache-Ausfall (Datenbank entscheidet).
 */
interface SessionBlockCache {
    fun isBlocked(key: String): Boolean
    fun block(key: String, ttl: Duration)
    fun clear(key: String)

    /** Ohne Cache: nie ein Treffer, die Datenbank entscheidet jedes Mal. */
    object None : SessionBlockCache {
        override fun isBlocked(key: String) = false
        override fun block(key: String, ttl: Duration) = Unit
        override fun clear(key: String) = Unit
    }
}

/** Redis-Umsetzung: Schlüssel mit Ablauf, kurzer Befehls-Timeout (ein hängendes Redis darf Anfragen nicht aufhalten, die Datenbank übernimmt). */
class RedisSessionBlockCache private constructor(private val client: RedisClient, private val prefix: String) : SessionBlockCache, AutoCloseable {
    private val connection: StatefulRedisConnection<String, String> = client.connect()

    override fun isBlocked(key: String): Boolean = connection.sync().exists(prefix + key) > 0
    override fun block(key: String, ttl: Duration) { connection.sync().set(prefix + key, "1", SetArgs.Builder.px(ttl.toMillis().coerceAtLeast(1))) }
    override fun clear(key: String) { connection.sync().del(prefix + key) }

    override fun close() { connection.close(); client.shutdown() }

    companion object {
        fun connect(url: String, commandTimeout: Duration = Duration.ofMillis(250), prefix: String = "taktovia:sessblock:"): RedisSessionBlockCache {
            val client = RedisClient.create(RedisURI.create(url).also { it.timeout = commandTimeout })
            // Ausfall: Befehle sofort ablehnen statt zu puffern, damit die Datenbank ohne Wartezeit entscheidet
            client.options = ClientOptions.builder().disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).autoReconnect(true).build()
            return RedisSessionBlockCache(client, prefix)
        }
    }
}
