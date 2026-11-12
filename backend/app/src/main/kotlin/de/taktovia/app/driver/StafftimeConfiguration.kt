package de.taktovia.app.driver

import de.taktovia.platform.audit.AuditPort
import de.taktovia.platform.context.ContextTransactions
import de.taktovia.platform.outbox.Outbox
import de.taktovia.stafftime.api.DriverRunSource
import de.taktovia.stafftime.api.StafftimeApi
import de.taktovia.stafftime.api.StafftimeSettings
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

/**
 * Verdrahtung des Moduls `staff-time` (WP-M3-24, WP-M3-25). `taktovia.stafftime.shift-start-tolerance-minutes` (Planungsdefault 30, 0 bis 720) ist der früheste Dienstantritt vor
 * Schichtbeginn (kein Katalogparameter). `taktovia.stafftime.position-stale-after-seconds` (Planungsdefault 30, OP-04) ist die Schwelle des Standortalters, `…position-future-tolerance-seconds` (5) die Toleranz für Messzeiten in der Zukunft. Die Stoppliste des Fahrers liefert eine optionale [DriverRunSource]-Bean (Run-Lesesicht von `booking-dispatch`, WP-M3-26); ohne sie bleibt sie leer.
 */
@Configuration
class StafftimeConfiguration {
    @Bean fun stafftimeApi(
        ctxTx: ContextTransactions, audit: AuditPort, outbox: Outbox, runSource: ObjectProvider<DriverRunSource>, config: ObjectProvider<de.taktovia.config.api.ConfigApi>,
        @Value("\${taktovia.stafftime.shift-start-tolerance-minutes:30}") startToleranceMinutes: Long,
        @Value("\${taktovia.stafftime.position-stale-after-seconds:30}") positionStaleAfterSeconds: Long,
        @Value("\${taktovia.stafftime.position-future-tolerance-seconds:5}") positionFutureToleranceSeconds: Long,
        @Value("\${taktovia.environment:production}") environment: String,
    ): StafftimeApi = StafftimeApi.create(
        ctxTx, audit, outbox, Clock.systemUTC(),
        StafftimeSettings(
            shiftStartTolerance = Duration.ofMinutes(startToleranceMinutes), positionStaleAfter = Duration.ofSeconds(positionStaleAfterSeconds),
            positionFutureTolerance = Duration.ofSeconds(positionFutureToleranceSeconds),
            // In Produktion nur mit freigegebener Aufbewahrungsregel für Standortdaten (WP-M3-34 Kriterium 5, D-10, D-11); andere Umgebungen erfassen ohne Regel.
            positionRequiresRetentionRule = environment.trim() == "production",
        ),
        runSource.getIfAvailable(),
        de.taktovia.app.staff.ConfigWorkRuleSource { config.getObject().effective },   // Regelparameter der Arbeitszeit-Hinweise (WP-M4-13) aus der wirksamen Konfiguration
    )

    /** ImpactProvider „Schichten“ (WP-M4-17, Zusage an WP-M1-23): wird von `ConfigApi` für die Wirkungsanalyse vor Publikation gesammelt. */
    @Bean fun shiftImpactProvider(): de.taktovia.config.api.ImpactProvider = StafftimeApi.impactProvider()

    /** Leseport „Schichtbesetzung“ für Disposition und Einsatzfreigabe (WP-M4-17); die Disposition wertet ihn statt der Schicht-Fixture aus (WP-M4-65). */
    @Bean fun shiftStaffingReadPort(api: StafftimeApi): de.taktovia.platform.readports.ShiftStaffingReadPort = api.shiftStaffing
}
