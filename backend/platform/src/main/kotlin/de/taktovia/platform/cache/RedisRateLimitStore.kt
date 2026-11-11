package de.taktovia.platform.cache

import de.taktovia.platform.api.RateLimitStore
import io.lettuce.core.RedisClient
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulRedisConnection

/**
 * Redis als gemeinsamer Rate-Limit-Speicher mehrerer Instanzen (Leitlinie 4: Redis nur Cache und Rate-Limit, nie
 * führender Zustand). Ein Lua-Skript zählt und setzt die Fensterlaufzeit atomar; geht ein Zähler verloren, ist die Folge
 * höchstens eine großzügigere Quote, nie ein Datenverlust. Der Fensterbeginn ist an die Redis-Zeit gebunden, nicht an
 * die Uhren der Anwendungsinstanzen.
 */
class RedisRateLimitStore private constructor(private val client: RedisClient) : RateLimitStore, AutoCloseable {
    private val connection: StatefulRedisConnection<String, String> = client.connect()

    override fun hit(key: String, windowSeconds: Int): Pair<Long, Int> {
        val t = connection.sync().time()
        val nowSeconds = t[0].toLong()
        val window = nowSeconds / windowSeconds
        val redisKey = "$key:$window"
        val result = connection.sync().eval<List<Long>>(SCRIPT, ScriptOutputType.MULTI, arrayOf(redisKey), (windowSeconds + 1).toString())
        val count = result[0]
        val retry = ((window + 1) * windowSeconds - nowSeconds).toInt().coerceAtLeast(1)
        return count to retry
    }

    override fun close() { connection.close(); client.shutdown() }

    companion object {
        fun connect(url: String) = RedisRateLimitStore(RedisClient.create(url))
        private const val SCRIPT = "local c = redis.call('INCR', KEYS[1]); if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end; return {c}"
    }
}
