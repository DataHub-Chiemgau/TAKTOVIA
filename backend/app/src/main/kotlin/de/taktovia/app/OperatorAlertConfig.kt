package de.taktovia.app

import de.taktovia.platform.alert.AlertChannel
import de.taktovia.platform.alert.HttpsWebhookChannel
import de.taktovia.platform.alert.OperatorAlertPort
import de.taktovia.platform.alert.OperatorAlertService
import de.taktovia.platform.secret.SecretRef
import de.taktovia.platform.secret.SecretResolver
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Verdrahtung des Betriebsalarmkanals (WP-M1-47): genau ein Empfängerkanal je Umgebung, Zugang nur als Secret-Referenz
 * (`taktovia.alert.channel-credential`). Ohne Kanal startet nur `taktovia.environment=local` (dann nur Metrik und Log).
 */
@Configuration
class OperatorAlertConfig {
    @Bean
    fun operatorAlertService(metrics: MeterRegistry, resolver: ObjectProvider<SecretResolver>,
                             @Value("\${taktovia.alert.channel-credential:}") ref: String,
                             @Value("\${taktovia.environment:production}") environment: String): OperatorAlertService {
        val channel: AlertChannel = when {
            ref.isNotBlank() -> HttpsWebhookChannel(SecretRef.requireRef("taktovia.alert.channel-credential", ref),
                resolver.getIfAvailable() ?: error("taktovia.alert.channel-credential benötigt taktovia.secrets.dir"))
            environment == "local" -> AlertChannel { LoggerFactory.getLogger("taktovia.operator").warn("kein Empfängerkanal konfiguriert (lokal)") }
            else -> error("Kein Betreiber-Empfängerkanal konfiguriert (taktovia.alert.channel-credential) – Start in '$environment' verweigert")
        }
        val svc = OperatorAlertService(channel, metrics)
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "operator-alert-flush").also { it.isDaemon = true } }
            .scheduleWithFixedDelay({ runCatching { svc.flush() } }, 5, 5, TimeUnit.SECONDS)
        return svc
    }

    @Bean
    fun operatorAlertPort(svc: OperatorAlertService): OperatorAlertPort = svc
}
