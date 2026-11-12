package de.taktovia.app.live

import de.taktovia.platform.live.LiveFeed
import de.taktovia.platform.live.LiveSettings
import de.taktovia.platform.live.LiveStream
import de.taktovia.platform.tenant.TenantTransactions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Duration

/**
 * Verdrahtung des Live-Kanals (WP-M3-35). Einstellungen `taktovia.live.*` (Planungsdefaults, OP-05): `poll-interval-ms` (1000), `heartbeat-seconds` (15), `max-seconds` (55),
 * `max-connections` (200).
 */
@Configuration
class LiveConfiguration {
    @Bean fun liveSettings(env: Environment): LiveSettings = LiveSettings(
        pollInterval = Duration.ofMillis(env.getProperty("taktovia.live.poll-interval-ms", Long::class.java, 1000L)),
        heartbeat = Duration.ofSeconds(env.getProperty("taktovia.live.heartbeat-seconds", Long::class.java, 15L)),
        maxDuration = Duration.ofSeconds(env.getProperty("taktovia.live.max-seconds", Long::class.java, 55L)),
        maxConnections = env.getProperty("taktovia.live.max-connections", Int::class.java, 200),
    )

    @Bean fun liveFeed(tx: TenantTransactions): LiveFeed = LiveFeed(tx)

    @Bean fun liveStream(feed: LiveFeed, settings: LiveSettings): LiveStream = LiveStream(feed, settings = settings)
}
