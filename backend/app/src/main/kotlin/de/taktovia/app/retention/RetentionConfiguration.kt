package de.taktovia.app.retention

import de.taktovia.identity.api.PassengerArchive
import de.taktovia.identity.api.PassengerRetention
import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.operation.OperationService
import de.taktovia.platform.retention.ErasureEngine
import de.taktovia.platform.retention.ErasureOperationHandler
import de.taktovia.platform.retention.RetentionRules
import de.taktovia.platform.retention.RetentionTarget
import de.taktovia.platform.storage.TenantObjectStore
import de.taktovia.platform.tenant.TenantTransactions
import de.taktovia.stafftime.api.PositionRetention
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Verdrahtung der Aufbewahrungs-, Archivierungs- und Löschregeln (WP-M1-33). Fachmodule tragen ihre [RetentionTarget] als Beans bei; der Löschlauf läuft als
 * `RETENTION_RUN`-Operation über den Operation-Runner (`taktovia.operations.worker-enabled`). Der Objektspeicher ist optional (in M1 gibt es nur die
 * prozesslokale Umsetzung; ohne Bean entfällt die Löschung im Objektspeicher, nicht die in der Datenbank).
 */
@Configuration
class RetentionConfiguration {
    @Bean fun retentionRules(ctxTx: ContextTransactions, audit: AuditPort, operations: OperationService) = RetentionRules(ctxTx, audit, operations, Clock.systemUTC())

    @Bean fun passengerRetentionTarget(): RetentionTarget = PassengerRetention.target()

    /** Löschziel der Rohpositionen (`LK_STANDORT`, WP-M3-34); die Frist liefert die freigegebene Aufbewahrungsregel, nie ein Standardwert. */
    @Bean fun positionRetentionTarget(): RetentionTarget = PositionRetention.target()

    @Bean fun passengerArchive(ctxTx: ContextTransactions, audit: AuditPort): PassengerArchive = PassengerRetention.archive(ctxTx, audit)

    @Bean fun erasureEngine(tx: TenantTransactions, targets: ObjectProvider<RetentionTarget>, audit: AuditPort, objects: ObjectProvider<TenantObjectStore>) =
        ErasureEngine(tx, targets.orderedStream().toList(), audit, objects.getIfAvailable(), Clock.systemUTC())

    @Bean fun erasureOperationHandler(engine: ErasureEngine, tx: TenantTransactions) = ErasureOperationHandler(engine, tx)
}
