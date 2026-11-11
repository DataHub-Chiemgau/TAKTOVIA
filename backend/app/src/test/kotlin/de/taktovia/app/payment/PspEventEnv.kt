package de.taktovia.app.payment

import de.taktovia.TaktoviaTestSupport
import de.taktovia.app.probe.ProbeServer
import de.taktovia.app.probe.Resp
import de.taktovia.app.security.TestIdp
import de.taktovia.farepayment.api.ProviderEventKey
import de.taktovia.farepayment.api.ProviderEventKeys
import de.taktovia.platform.tenant.TenantId
import de.taktovia.platform.webhook.WebhookSignature
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Schlüsselregister des Testeingangs (nur Tests): ersetzt die Secret-Verwaltung der Umgebung, die in dieser Umgebung nicht läuft (kein Secret-Dienst). */
object PspTestKeys {
    val keys = CopyOnWriteArrayList<ProviderEventKey>()
    fun register(tenant: TenantId, provider: String, secret: ByteArray, validUntil: Instant? = null) { keys += ProviderEventKey(tenant, provider, secret, validUntil) }
}

/** Nur in Tests (`taktovia.probe.psp-keys=true`): Schlüsselquelle aus [PspTestKeys]. Der produktive Pfad (Secretreferenzen über `taktovia.payments.providers`) hat eigene Tests. */
@Configuration
@ConditionalOnProperty("taktovia.probe.psp-keys", havingValue = "true")
class PspTestKeysConfig {
    @Bean @Primary fun testProviderEventKeys() = ProviderEventKeys { p -> PspTestKeys.keys.filter { it.provider == p } }
}

/**
 * Anwendung (echte Datenbank, Runtime-Rolle, nur produktive Routen) mit **eingeschaltetem Ressourcenserver** gegen einen lokalen Test-Issuer: der Eingang der PSP-Ereignisse
 * muss ohne Bearer-Token funktionieren und darf trotzdem nur mit gültiger PSP-Signatur durch. Eigene Klasse des Clusters (WP-M2-29).
 */
object PspEventEnv {
    val idp = TestIdp()
    const val MAX_BODY = 4096

    val server: ProbeServer by lazy {
        TaktoviaTestSupport.requireDatabase()
        Runtime.getRuntime().addShutdownHook(Thread { idp.close() })
        ProbeServer.production(
            "taktovia.idp.enabled=true", "taktovia.idp.issuer-uri=${idp.issuer}", "taktovia.idp.jwks-uri=${idp.jwksUri}", "taktovia.idp.audience=${idp.audience}",
            "taktovia.idp.jwks-min-refresh-seconds=0", "taktovia.idp.privileged-roles=tenant_admin,dispatcher,finance",
            "taktovia.api.rate-limit.requests=1000000", "taktovia.probe.psp-keys=true", "taktovia.payments.max-event-bytes=$MAX_BODY",
            "spring.datasource.hikari.maximum-pool-size=24",
        )
    }

    private val admin by lazy { TaktoviaTestSupport.adminDataSourceOrNull() ?: error("Administratorzugang nicht konfiguriert") }

    fun tenant(): TenantId = TenantId(UUID.randomUUID()).also { id ->
        admin.connection.use { c -> c.prepareStatement("INSERT INTO taktovia.tenant(id,name,status) VALUES (?,?, 'ACTIVE')").use { it.setObject(1, id.value); it.setString(2, "psp-$id"); it.executeUpdate() } }
    }

    private var simTenant: TenantId? = null

    /** Mandant des Simulator-Providers `simpsp` (Schlüssel aus dem Seed des Simulators); einmal je JVM, damit der Schlüssel eindeutig bleibt. */
    @Synchronized fun simulatorTenant(secret: String): TenantId = simTenant ?: tenant().also { simTenant = it; PspTestKeys.register(it, "simpsp", secret.toByteArray()) }

    fun provider() = "psp" + UUID.randomUUID().toString().replace("-", "").take(8)

    fun <T> admin(sql: String, vararg args: Any?, map: (java.sql.ResultSet) -> T): List<T> = admin.connection.use { c ->
        c.prepareStatement(sql).use { ps -> args.forEachIndexed { i, v -> ps.setObject(i + 1, v) }; ps.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } } }
    }
    fun count(sql: String, vararg args: Any?) = admin(sql, *args) { it.getInt(1) }.single()

    /** Signierter Aufruf ohne Authorization-Header. [skew] verschiebt den Zeitstempel (Sekunden). */
    fun post(provider: String, body: String, secret: ByteArray, skew: Long = 0, extra: Map<String, String> = emptyMap()): Resp {
        val ts = Instant.now().plusSeconds(skew)
        val sig = "t=${ts.epochSecond},v1=${WebhookSignature.hmacHex(secret, ts, body.toByteArray())}"
        return server.post("/v1/payments/provider-events/$provider", body, mapOf("X-Provider-Signature" to sig, "X-Provider-Timestamp" to ts.epochSecond.toString()) + extra)
    }

    fun body(id: String = "evt_" + UUID.randomUUID().toString().replace("-", "").take(16), type: String = "payment.authorized", state: String = "AUTHORIZED", dataExtra: String = "", top: String = "") =
        """{"event_id":"$id","sequence":1,"type":"$type","created_at":"2026-11-01T10:00:00Z"$top,"data":{"provider_ref":"pi_$id","state":"$state","authorized_minor":1250,"captured_minor":0,"refunded_minor":0,"currency":"EUR"$dataExtra}}"""
}
