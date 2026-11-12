package de.taktovia.app.documents

import de.taktovia.documentsfinance.api.OdometerEventConsumer
import de.taktovia.documentsfinance.api.OdometerRegister
import de.taktovia.documentsfinance.api.OdometerServices
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.audit.SecurityEventPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.InboxProcessor
import de.taktovia.platform.outbox.Outbox
import de.taktovia.platform.outbox.SnapshotFetcher
import de.taktovia.platform.outbox.SnapshotNotYetAvailableException
import de.taktovia.platform.tenant.TenantTransactions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Clock

/**
 * Verdrahtung des Kilometerstandsregisters (WP-M4-41). `taktovia.odometer.telematics-productive` (Vorgabe `false`): solange der Telematikanbieter nicht beschlossen ist (D-09), nimmt das Register
 * Telematikstände nur mit Simulatorbezug (`sim:`) an. Der Ereignisverbraucher ([OdometerEventConsumer], Quelle: Broker-Verbraucher, nicht ausgeführt) ist lazy: ohne Aufrufer entsteht nichts.
 * Er verarbeitet jedes Ereignis als eigenes Aggregat und braucht deshalb keinen Snapshotabruf.
 */
@Configuration
class OdometerConfiguration {
    @Bean fun odometerServices(ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox, tx: TenantTransactions, security: SecurityEventPort, env: Environment): OdometerServices =
        OdometerServices.create(
            ctxTx, audit, outbox, Clock.systemUTC(),
            InboxProcessor(tx, security, SnapshotFetcher { _, _, _ -> throw SnapshotNotYetAvailableException("Das Kilometerstandsregister hält keinen Aggregatzustand") }),
            telematicsProductive = env.getProperty("taktovia.odometer.telematics-productive", Boolean::class.java, false),
        )

    @Bean fun odometerRegister(s: OdometerServices): OdometerRegister = s.register
    @org.springframework.context.annotation.Lazy @Bean fun odometerEventConsumer(s: OdometerServices): OdometerEventConsumer = s.events
}
